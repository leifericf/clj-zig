(ns clj-zig.foreign
  "A foreign-function toolkit for binding a prebuilt native library
  alongside compiled Zig, through the finalized Foreign Function & Memory
  API (Java 22+).

  `clj-zig.core`/`clj-zig.ffm` cover the everyday case: a `defnz` body is
  Zig that clj-zig compiles, and the boundary is described by a signature
  vector. But a real program also reaches libraries it did NOT compile:
  the platform's windowing or input library, a system framework, libc,
  the graphics loader. Those expose a flat C ABI with no Zig source and no
  signature spec to derive carriers from. Binding them is the same FFM
  work every time -- open the library, describe a signature, bind a
  downcall, occasionally hand native code a callback -- so this namespace
  publishes that work once as a small, data-in/data-out toolkit rather
  than leaving each consumer to re-derive it.

  This is imperative-shell / native-edge code (ADR 16). It loads
  libraries, holds linker handles, and crosses the FFM boundary; it
  carries no domain knowledge and the pure core never sees a
  `MemorySegment`. Native pointers returned across the boundary are opaque
  handles (ADR 22): they are threaded back into native calls, never
  dereferenced into Clojure logic.

  PERFORMANCE. A real-time consumer (a 60fps present loop, an audio
  callback) calls some of these every frame. `downcall` therefore binds
  the symbol, builds the descriptor, and links the handle AT MOST ONCE per
  distinct call and caches the `MethodHandle`; the per-frame path invokes
  the cached handle directly with typed arguments and allocates nothing.
  `call` is the convenience invoker for the cold path (setup, teardown,
  once-per-batch reads), where the per-call argument array is fine.

  NATIVE ACCESS. Loading a library and calling native code are restricted
  operations; a JVM that denies native access throws
  `IllegalCallerException`. Run with `--enable-native-access=ALL-UNNAMED`
  (the `:repl` and `:test` aliases in deps.edn set it)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
                              MemoryLayout MemorySegment SymbolLookup ValueLayout)
           (java.lang.invoke MethodHandle MethodHandles MethodType)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent RejectedExecutionException)))

;; linker and layout shorthands

(def ^Linker linker (Linker/nativeLinker))

(def ^:private ^"[Ljava.lang.foreign.Linker$Option;" no-options
  (make-array Linker$Option 0))

(def c-byte   "JAVA_BYTE: a C `char`/`int8`/`bool`-as-byte carrier." ValueLayout/JAVA_BYTE)
(def c-short  "JAVA_SHORT: a C `short`/`int16` carrier."            ValueLayout/JAVA_SHORT)
(def c-int    "JAVA_INT: a C `int`/`int32`/`DWORD` carrier."        ValueLayout/JAVA_INT)
(def c-long   "JAVA_LONG: a C `long long`/`int64`/`size_t` carrier." ValueLayout/JAVA_LONG)
(def c-float  "JAVA_FLOAT: a C `float`/`f32` carrier."              ValueLayout/JAVA_FLOAT)
(def c-double "JAVA_DOUBLE: a C `double`/`f64` carrier."            ValueLayout/JAVA_DOUBLE)
(def c-ptr    "ADDRESS: a C pointer / opaque handle carrier."       ValueLayout/ADDRESS)

;; describing a signature

(defn descriptor
  "Build a `FunctionDescriptor` from `ret` (a `ValueLayout` -- use the
  `c-*` shorthands -- or `:void`) and `arg-layouts` (a seq of
  `ValueLayout`s). The data shape a caller hands `downcall` and
  `upcall-stub` to describe a native signature without importing the FFM
  classes."
  ^FunctionDescriptor [ret arg-layouts]
  (let [args (into-array MemoryLayout arg-layouts)]
    (if (= ret :void)
      (FunctionDescriptor/ofVoid args)
      (FunctionDescriptor/of ^MemoryLayout ret args))))

;; opening and resolving a library

(defn library-lookup
  "Open the native library at `path` (a file path string), bound to the
  global Arena so the lookup lives for the process -- a library is a
  process-lifetime resource (ADR 16). The path loads as an exact file (the
  FFM `Path` overload), not a platform search name, so a content-addressed
  cache loads precisely its artifact and a relative name never resolves
  through `dlopen`/`LoadLibrary` search paths. Returns the `SymbolLookup`,
  or throws an ex-info tagged `:foreign/error :library-open-failed` (so the
  caller can catch and degrade as data, ADR 19) when the library cannot be
  opened."
  ^SymbolLookup [^String path]
  (when (str/blank? path)
    (throw (ex-info (str "Cannot open native library: " (pr-str path))
                    {:foreign/error :library-open-failed :path path})))
  (try
    (SymbolLookup/libraryLookup (.toPath (io/file path)) (Arena/global))
    (catch IllegalArgumentException e
      (throw (ex-info (str "Cannot open native library: " path)
                      {:foreign/error :library-open-failed :path path}
                      e)))))

(defn resolve-library
  "Resolve which library path to open, as data, from a config map:

      {:env        [\"MYLIB_PATH\" \"LIBFOO\"]   ; env vars to consult, in order
       :candidates [\"/opt/.../libfoo.dylib\"    ; concrete paths to probe
                    \"/usr/local/.../libfoo.dylib\"]
       :default    \"/opt/.../libfoo.dylib\"}    ; fallback when none exists

  Returns the first set environment variable's value, else the first
  candidate path that exists on disk, else `:default` (which may be nil).
  The mechanism is general; the platform-shaped env names, candidate
  paths, and `.dylib`/`.dll`/`.so` default belong to the caller. Pair the
  result with `library-lookup`."
  [{:keys [env candidates default]}]
  (or (some (fn [v] (System/getenv v)) env)
      (first (filter (fn [p] (.exists (io/file ^String p))) candidates))
      default))

(defn find-symbol
  "Resolve symbol `nm` in `lookup`, returning its address as a
  `MemorySegment`, or throwing an ex-info tagged
  `:foreign/error :symbol-not-found` the caller can catch and degrade (ADR
  19) rather than NPE on a null segment."
  ^MemorySegment [^SymbolLookup lookup ^String nm]
  (let [sym (.find lookup nm)]
    (if (.isPresent sym)
      (.get sym)
      (throw (ex-info (str "Native symbol not found: " nm)
                      {:foreign/error :symbol-not-found :symbol nm})))))

(defn symbol-present?
  "True when `nm` resolves in `lookup`. The probe a caller uses before
  binding a downcall, so a missing symbol degrades as data (ADR 19) rather
  than throwing at bind time."
  [^SymbolLookup lookup ^String nm]
  (.isPresent (.find lookup nm)))

;; downcalls (Clojure to native)

(defonce ^:private handle-cache (atom {}))

(defn downcall
  "Bind a cached downcall handle for `nm` in `lookup`. `ret` is a
  `ValueLayout` (a `c-*` shorthand) or `:void`; `arg-layouts` is a seq of
  `ValueLayout`s. Returns a `java.lang.invoke.MethodHandle`, cached per
  distinct `[lookup nm ret arg-layouts]` so the symbol lookup, descriptor
  build, and link happen at most once -- a per-frame call goes through the
  cache and does no linker work. Throws (via `find-symbol`) when the symbol
  is absent so the caller degrades rather than faulting on a null segment.

  Invoke the returned handle directly with exactly-typed arguments
  (`(.invoke h ...)`) on a hot path -- that allocates nothing -- or hand it
  to `call` on the cold path."
  ^MethodHandle [^SymbolLookup lookup ^String nm ret arg-layouts]
  (let [k [lookup nm ret (vec arg-layouts)]]
    (or (get @handle-cache k)
        (let [h (.downcallHandle linker (find-symbol lookup nm)
                                 (descriptor ret arg-layouts) no-options)]
          (swap! handle-cache assoc k h)
          h))))

(defn call
  "Invoke a downcall handle `h` with `args`. `MethodHandle.invoke` is
  signature-polymorphic and cannot be called reflectively from Clojure, so
  this goes through `invokeWithArguments`, an ordinary varargs method that
  builds a per-call argument array. That array is fine for cold-path work
  -- setup, teardown, once-per-batch reads -- but NOT for a per-frame hot
  path: there, invoke the cached handle directly with typed arguments to
  allocate nothing."
  [^MethodHandle h & args]
  (.invokeWithArguments h (object-array args)))

;; upcalls (native to Clojure callbacks)

(defn- upcall-method-handle
  "Reflect a `MethodHandle` onto `IFn.invoke` at `arity` args, bind it to
  `f`, and adapt it to the carrier `MethodType` the descriptor demands. A
  Clojure fn is an `IFn`, so its bound handle is `(Object xN)Object`; the
  FFM linker wants a handle whose type matches the descriptor's carriers,
  so the boxed args and the (Object or void) return are explicit-cast to
  the descriptor's method type. `arity` is the descriptor's argument count,
  so one builder serves any callback signature. All args box as `Object`,
  so an arity above four is fine even though Clojure caps PRIMITIVE args at
  four."
  ^MethodHandle [f ^long arity ^FunctionDescriptor desc]
  (let [lk    (MethodHandles/lookup)
        mt    (MethodType/methodType
               Object ^"[Ljava.lang.Class;" (into-array Class (repeat arity Object)))
        bound (.bindTo (.findVirtual lk clojure.lang.IFn "invoke" mt) f)]
    (MethodHandles/explicitCastArguments bound (.toMethodType desc))))

(defn upcall-stub
  "Build a native upcall stub for Clojure fn `f` against `FunctionDescriptor`
  `desc`, bound to `arena`. Returns the stub as a `MemorySegment` -- a C
  function pointer native code calls back through. The callback arity is
  derived from the descriptor's argument count, so one primitive serves
  every callback shape. `f` receives the native arguments boxed as
  `Object`s (a pointer arrives as a `MemorySegment`, an integral carrier as
  a `Long`, a float as a `Double`); guard its body so a single faulty
  callback cannot escape into the native run loop.

  LIFETIME DISCIPLINE -- load-bearing. `arena` governs how long the stub's
  native pointer stays valid, and freeing it while native code may still
  call through it faults the VM. If native code RETAINS the pointer (a
  registered window/input callback, a stream callback fired from a run
  loop), `arena` MUST outlive every possible call -- use the
  process-lifetime `(Arena/global)`, never a per-frame or confined arena.
  Only when the stub is used and discarded entirely within one bounded
  scope (a comparator passed to a sort that returns before the scope ends)
  may a confined arena own it. This primitive takes `arena` as a parameter
  rather than choosing for you; choosing wrong is a use-after-free."
  ^MemorySegment [f ^FunctionDescriptor desc ^Arena arena]
  (let [arity (count (.argumentLayouts desc))
        mh    (upcall-method-handle f arity desc)]
    (.upcallStub linker mh desc arena no-options)))

;; async upcalls (route, not run)

(defn- default-error-handler
  "The floor of error visibility for async dispatch failures: print the
  throwable and invocation to *err*. The dispatch map's :error-handler
  overrides this for structured handling."
  [throwable invocation]
  (binding [*out* *err*]
    (println "clj-zig async upcall error on stub" (:stub invocation))
    (println "  args:" (pr-str (:args invocation)))
    (println "  cause:" (ex-message throwable)))
  nil)

(defn- make-envelope
  "Build the invocation envelope: which stub fired, the args, and a
  nanoTime stamp for ordering and latency diagnosis."
  [stub-id args]
  {:stub stub-id :args (vec args) :stamp (System/nanoTime)})

(def ^:private async-modes #{:executor :agent})

(defn validate-dispatch-map
  "Validate `m` as an async dispatch map, apply defaults, and return the
  normalized map. Throws ex-info tagged `:foreign/error
  :invalid-dispatch-map` when malformed.

  Required keys:
    :mode   :executor or :agent
    :target a java.util.concurrent.Executor (:executor)
            or a Clojure agent (:agent)

  Optional:
    :error-handler (fn [throwable invocation]); default logs to *err*.
    The handler may run on the dispatch target's thread (when f throws)
    or on the native thread (for dispatch errors). It must be thread-safe.
    clj-zig wraps it so a throwing handler never propagates.

  The dispatch target's own queue bound, thread count, and rejection
  policy are the back-pressure mechanism. Configure them on the target."
  [m]
  (let [mode (:mode m)
        known-keys #{:mode :target :error-handler}
        unknown (not-empty (reduce disj (set (keys m)) known-keys))]
    (when (seq unknown)
      (throw (ex-info (str "Unknown dispatch map keys: " (pr-str (vec unknown)))
                      {:foreign/error :invalid-dispatch-map :unknown-keys unknown})))
    (when-not (async-modes mode)
      (throw (ex-info (str "Invalid dispatch map :mode: " (pr-str mode))
                      {:foreign/error :invalid-dispatch-map :mode mode})))
    (when (nil? (:target m))
      (throw (ex-info (str "Dispatch map :mode " mode " requires :target")
                      {:foreign/error :invalid-dispatch-map :mode mode})))
    (when (and (= :executor mode)
               (not (instance? java.util.concurrent.Executor (:target m))))
      (throw (ex-info ":executor :target must be a java.util.concurrent.Executor"
                      {:foreign/error :invalid-dispatch-map :mode mode})))
    (when (and (= :agent mode)
               (not (instance? clojure.lang.Agent (:target m))))
      (throw (ex-info ":agent :target must be a Clojure agent"
                      {:foreign/error :invalid-dispatch-map :mode mode})))
    (let [eh (or (:error-handler m) default-error-handler)]
      (when-not (ifn? eh)
        (throw (ex-info ":error-handler must be a fn"
                        {:foreign/error :invalid-dispatch-map})))
      {:mode          mode
       :target        (:target m)
       :error-handler eh})))

(defn onto-executor
  "Build a dispatch map routing async invocations onto `exec`, a
  `java.util.concurrent.Executor`. The optional `opts` map may carry
  :error-handler (fn [throwable invocation]); default logs to *err*.
  The executor's own queue bound, thread count, and rejection policy
  are the back-pressure mechanism."
  ([exec] (onto-executor exec {}))
  ([exec opts]
   (validate-dispatch-map (assoc opts :mode :executor :target exec))))

(defn onto-agent
  "Build a dispatch map routing async invocations onto `agent`, a Clojure
  agent. Dispatch uses `send`, so actions run on Clojure's fixed send pool
  (sized to the CPU count). Use this for non-blocking fns; a blocking fn
  starves the shared pool and can deadlock unrelated agents. Route onto an
  executor instead when the fn blocks. The optional `opts` map may carry
  :error-handler."
  ([agnt] (onto-agent agnt {}))
  ([agnt opts]
   (validate-dispatch-map (assoc opts :mode :agent :target agnt))))

(defn read-bytes-bounded
  "Read up to `max-bytes` from `seg` into a fresh byte array. The cap is
  the guard: never more than `max-bytes` cross the boundary. Returns nil
  for a NULL segment, and a shorter array when the segment's own size is
  below the cap. `max-bytes` must be non-negative; a negative cap throws
  ex-info tagged :foreign/error :invalid-cap. The caller-side counterpart
  to `read-utf8-bounded` for raw byte buffers."
  (^bytes [^MemorySegment seg ^long max-bytes]
   (when (neg? max-bytes)
     (throw (ex-info "read-bytes-bounded requires a non-negative max-bytes"
                     {:foreign/error :invalid-cap :max-bytes max-bytes})))
   (when (and seg (not (.equals MemorySegment/NULL seg)))
     (let [size (min (.byteSize seg) max-bytes)
           out  (byte-array size)]
       (when (pos? size)
         (MemorySegment/copy seg ValueLayout/JAVA_BYTE (long 0) out (int 0) (int size)))
       out))))

;; async upcall stubs

(defonce ^:private stub-registry (atom {}))

(defn- dispatch-invocation
  "Submit the invocation envelope to the dispatch target. For :executor,
  submits a Runnable and catches RejectedExecutionException. For :agent,
  sends a per-invocation action that preserves the agent value. The
  native thread never runs f."
  [env f dispatch-map]
  (let [eh     (:error-handler dispatch-map)
        target (:target dispatch-map)]
    (case (:mode dispatch-map)
      :executor
      (let [run-fn (fn []
                     (try (apply f (:args env))
                          (catch Throwable t (eh t env))))]
        (try (.execute ^java.util.concurrent.Executor target ^Runnable run-fn)
             (catch RejectedExecutionException e
               (eh e env))))
      :agent
      (send target (fn [curr]
                     (try (apply f (:args env))
                          (catch Throwable t (eh t env)))
                     curr)))))

(defn- build-async-router
  "Build the routing fn and the quiesced volatile for an async stub.
  The routing fn runs on the native thread: it reads the args, builds
  the invocation envelope, submits to the dispatch target, and returns
  nil (void). The error handler is wrapped so it can never propagate,
  protecting both the native run loop and the agent's state. A throwing
  handler falls back to default-error-handler so both failures reach
  *err* rather than vanishing."
  [f stub-id dispatch-map]
  (let [quiesced? (volatile! false)
        raw-eh    (:error-handler dispatch-map)
        safe-eh   (fn [t env]
                    (try (raw-eh t env)
                         (catch Throwable h
                           (default-error-handler h env)
                           (default-error-handler t env))))
        safe-map  (assoc dispatch-map :error-handler safe-eh)]
    {:router
     (fn async-route [& args]
       (when-not @quiesced?
         (let [env (make-envelope stub-id args)]
           (try
             (dispatch-invocation env f safe-map)
             (catch Throwable t (safe-eh t env)))))
       nil)
     :quiesced? quiesced?}))

(defn async-upcall-stub
  "Build a native upcall stub that ROUTES rather than RUNS. The native
  thread that fires this stub reads the args, builds an invocation
  envelope, submits it to the dispatch target, and returns void
  immediately. The user's fn runs on the dispatch target's thread(s),
  never on the native thread.

  CONTRACT (enforced at build time):
  - `desc` MUST be void-returning. A non-void async stub is incoherent:
    the native caller cannot consume a value it will never synchronously
    receive. Use `upcall-stub` for value-returning callbacks.
  - `arena` MUST be the global Arena. A confined arena closes when this
    call returns; the stub fires later, from anywhere. Only the global
    Arena survives every possible fire.
  - `dispatch-map` routes the invocation. Build one with
    `onto-executor` or `onto-agent`.

  THREADING: your fn runs on the dispatch target's thread(s), not the
  native thread. The native thread does only read-envelope-submit-return.

  SEGMENT SAFETY: pointer args arrive as MemorySegment, but the fn runs
  on the dispatch target's thread after the routing handle has returned.
  The native caller may free or reuse the buffer before the fn reads it.
  The routing handle does not copy segment data, and there is no hook to
  copy on the native thread. A pointer arg is safe only when the native
  caller keeps the buffer alive past the dispatch drain. When it does,
  copy the bytes out at the start of the fn (read-utf8-bounded for
  strings, read-bytes-bounded for raw bytes) before doing other work.

  BACK-PRESSURE: configure the executor's queue bound and rejection
  policy; they are the back-pressure mechanism. A rejected execution
  routes to the error handler.

  Call `release-stub!` to stop dispatch after native code has stopped
  firing."
  ^MemorySegment [f ^FunctionDescriptor desc ^Arena arena dispatch-map]
  (when (.isPresent (.returnLayout desc))
    (throw (ex-info "async-upcall-stub requires a void descriptor"
                    {:foreign/error :async-non-void-descriptor})))
  (when-not (= arena (Arena/global))
    (throw (ex-info "async-upcall-stub requires the global Arena"
                    {:foreign/error :async-non-global-arena})))
  (let [validated            (validate-dispatch-map dispatch-map)
        stub-id              (keyword (gensym "async-stub"))
        {:keys [router quiesced?]} (build-async-router f stub-id validated)
        arity                (count (.argumentLayouts desc))
        mh                   (upcall-method-handle router arity desc)
        stub                 (.upcallStub linker mh desc arena no-options)]
    (swap! stub-registry assoc stub {:id stub-id :quiesced? quiesced? :dispatch validated})
    stub))

(defn release-stub!
  "Mark the stub registered for `seg` as quiesced and remove it from the
  registry. The dispatch target stops receiving invocations after any
  in-flight ones drain. The segment itself is not freed (the global
  Arena owns it for the process). The caller MUST signal native code to
  stop firing BEFORE calling this."
  [^MemorySegment seg]
  (when-let [entry (get @stub-registry seg)]
    (vreset! (:quiesced? entry) true)
    (swap! stub-registry dissoc seg))
  nil)

(defn- registered-stub-count
  "The number of currently registered async stubs."
  ^long []
  (count @stub-registry))

(defn shutdown-async-stubs
  "Mark all registered async stubs quiesced, drain agent targets, and
  clear the registry. Installed as a JVM shutdown hook so pending work
  quiesces before the JVM exits; also callable directly. Idempotent.

  Executor targets are NOT drained here: the caller owns the executor's
  lifecycle. A blocking native callback that cannot quiesce is the
  caller's responsibility."
  []
  (let [entries (vals @stub-registry)]
    (doseq [entry entries]
      (vreset! (:quiesced? entry) true))
    (doseq [entry entries]
      (when (= :agent (:mode (:dispatch entry)))
        (await-for 2000 (:target (:dispatch entry)))))
    (reset! stub-registry {}))
  nil)

(defonce ^:private shutdown-hook-installed
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. ^Runnable (fn [] (shutdown-async-stubs)))))

;; reading bounded native strings

(defn read-utf8-bounded
  "Read the NUL-terminated UTF-8 C string at `seg` (a pointer, typically
  into memory the OS or another library owns) as a Java `String`, scanning
  no further than `max-bytes`. Returns nil when `seg` is NULL, and nil when
  no NUL is found within the cap.

  The cap is the load-bearing guard, not a convenience: the bytes are
  untrusted, so the segment is reinterpreted to exactly `max-bytes` (plus
  the terminator slot), NEVER to `Long/MAX_VALUE`. A missing or corrupt NUL
  is then a bounded data outcome (nil), never an unbounded read off the end
  of a foreign allocation. `arena` scopes the reinterpreted view; the
  `MemorySegment` never escapes this fn."
  ^String [^MemorySegment seg ^long max-bytes ^Arena arena]
  (when (and seg (not (.equals MemorySegment/NULL seg)))
    (let [limit   (inc max-bytes)
          bounded (.reinterpret seg limit arena nil)]
      (loop [i 0]
        (cond
          (>= i limit) nil
          (zero? (.get bounded ValueLayout/JAVA_BYTE (long i)))
          (let [out (byte-array i)]
            (MemorySegment/copy bounded ValueLayout/JAVA_BYTE (long 0) out (int 0) (int i))
            (String. out StandardCharsets/UTF_8))
          :else (recur (inc i)))))))

;; teardown

(defn join-then-close-arena
  "The teardown tail for a native resource driven on a worker thread: join
  `worker` up to `timeout-ms`, then close `arena` once the worker is no
  longer alive. The ordering is load-bearing -- closing a shared `Arena`
  while a native frame is still live on the worker faults the VM -- so the
  close is gated on the worker no longer being alive. A nil `worker` (no
  thread was started, or it is already gone) still closes `arena`, so a
  caller cannot leak the arena by passing nil. The caller performs any
  resource-specific signal step (flip a running flag, close a handle to
  unblock a blocking call) BEFORE calling this. Both steps swallow their
  exceptions: teardown must not throw."
  [^Thread worker ^Arena arena ^long timeout-ms]
  (when (and worker (.isAlive worker))
    (try (.join worker timeout-ms) (catch Throwable _ nil)))
  (when (and arena (or (nil? worker) (not (.isAlive worker))))
    (try (.close arena) (catch Throwable _ nil)))
  nil)

(comment
  ;; Bind a prebuilt library and call a symbol from it.
  (let [lk (library-lookup (resolve-library {:env       ["LIBFOO"]
                                             :candidates ["/opt/homebrew/lib/libfoo.dylib"]
                                             :default   "/opt/homebrew/lib/libfoo.dylib"}))
        h  (downcall lk "foo_add" c-int [c-int c-int])]
    (call h (int 20) (int 22)))                 ;; => 42

  ;; Hand native code a Clojure callback (lifetime: global if retained).
  (let [desc (descriptor :void [c-ptr c-int])
        stub (upcall-stub (fn [_win code] (println :got code)) desc (Arena/global))]
    stub))
