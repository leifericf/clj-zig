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
  (:require [clojure.java.io :as io])
  (:import (java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
                              MemoryLayout MemorySegment SymbolLookup ValueLayout)
           (java.lang.invoke MethodHandle MethodHandles MethodType)
           (java.nio.charset StandardCharsets)))

;; --- linker and layout shorthands ---------------------------------------
;; The native linker is a process-wide, thread-safe singleton; bind it
;; once. The layout shorthands let a caller describe a C signature without
;; importing `ValueLayout`, keeping the FFM surface in this one namespace.
;; They are interned `ValueLayout` constants, so they are also safe cache
;; keys (see `downcall`).

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

;; --- describing a signature ---------------------------------------------

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

;; --- opening and resolving a library ------------------------------------

(defn library-lookup
  "Open a native library by absolute path or by name (resolved the way
  `dlopen`/`LoadLibrary` resolve it), bound to the global Arena so the
  lookup lives for the process -- a library is a process-lifetime resource
  (ADR 16). Returns the `SymbolLookup`, or throws an ex-info tagged
  `:foreign/error :library-open-failed` (so the caller can catch and
  degrade as data, ADR 19) when the library cannot be opened."
  ^SymbolLookup [^String path]
  (try
    (SymbolLookup/libraryLookup path (Arena/global))
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

;; --- downcalls (Clojure to native) --------------------------------------
;; Bound MethodHandles, keyed by the resolvable identity of the call:
;; [lookup nm ret arg-layouts]. A SymbolLookup is a process-lifetime
;; resource on the global Arena, so its identity is a stable key, and the
;; layouts are interned ValueLayout constants. Binding a handle resolves
;; the symbol and builds a FunctionDescriptor + .downcallHandle -- per-call
;; linker work a real-time loop forbids -- so cache it once per distinct
;; call and let the per-frame path invoke the cached handle directly.

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

;; --- upcalls (native to Clojure callbacks) ------------------------------
;; The reverse direction: native code calling a Clojure fn through a
;; function pointer. This is a SYNCHRONOUS upcall stub -- the native side
;; fires it inside a downcall, on the calling thread -- which is the
;; bounded subset of bidirectional interop ADR 10's deferral does not argue
;; against (no embedded JVM, no async callback into a parked runtime). It
;; is the cold half: a stub is built once at setup, never per frame, and
;; shares nothing with the downcall cache.

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

;; --- reading bounded native strings -------------------------------------

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

;; --- teardown -----------------------------------------------------------

(defn join-then-close-arena
  "The teardown tail for a native resource driven on a worker thread: join
  `worker` up to `timeout-ms`, then close `arena` only once the worker is
  dead. The ordering is load-bearing -- closing a shared `Arena` while a
  native frame is still live on the worker faults the VM -- so the close is
  gated on the worker no longer being alive. The caller performs any
  resource-specific signal step (flip a running flag, close a handle to
  unblock a blocking call) BEFORE calling this. Both steps swallow their
  exceptions: teardown must not throw."
  [^Thread worker ^Arena arena ^long timeout-ms]
  (when (and worker (.isAlive worker))
    (try (.join worker timeout-ms) (catch Throwable _ nil)))
  (when (and arena worker (not (.isAlive worker)))
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
