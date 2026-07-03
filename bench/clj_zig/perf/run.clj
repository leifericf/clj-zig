(ns clj-zig.perf.run
  "The imperative shell of the per-call overhead harness (ADR 16).

  This is the ONLY namespace in the project that requires Criterium, and
  it carries NO deftest, so cognitect-test-runner never loads it under
  the :test lane (the load-bearing assertion verified in p2-t3).

  The shell drives all seven shapes from clj-zig.perf.shape: for each
  shape it compiles the trivial body through clj-zig.core (the normal
  defnz boundary path), pairs the defnz measurement against its
  clj-zig.foreign direct-handle floor, drives both under Criterium,
  shapes the results through clj-zig.perf.stats, and writes the numbers
  record EDN to ~/.agentic-sdk/clj-zig/artifacts/perf/.

  The floor for a non-allocating shape is a plain invoke of the cached
  foreign handle against a reused object-array (the floor-invoke
  pattern settled in p2-t2). The floor for an allocating shape
  (:string, :owned-return, :handle) invokes the free shim after each
  call inside the timed thunk, so the floor measures the minimal
  correct native ROUND-TRIP (call + body + free) and leaks nothing in
  the Criterium loop. The :free-shim field discriminates; the thunk
  construction dispatches on :free-shim presence and on whether the
  main :ret is :void (result via out-params) or non-:void (result via
  return), so no per-shape kind case leaks into the shape loop.

  Per-shape failures are isolated (p3-t2): a compile, floor-bind, or
  Criterium fault on one shape is caught and shaped into one :errored
  entry via stats/diagnostic-entry, and the run continues the rest.

  Governing principle: MEASUREMENT ONLY. Nothing here optimizes; the
  first optimization is a later backlog item, not pickable until this
  feature and its baseline land."
  (:require [clj-zig.core :as clj-zig]
            [clj-zig.cache :as cache]
            [clj-zig.compile :as compile]
            [clj-zig.foreign :as foreign]
            [clj-zig.fs :as fs]
            [clj-zig.layout :as layout]
            [clj-zig.perf.opts :as opts]
            [clj-zig.perf.shape :as shape]
            [clj-zig.perf.stats :as stats]
            [clj-zig.spec :as spec]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [criterium.core :as criterium])
  (:import (java.lang ProcessHandle)
           (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.nio.charset StandardCharsets)))

;; --- output location -----------------------------------------------------

(def ^:private artifacts-dir
  "Where numbers records land. The campaign's docs rule keeps numbers
  out of the repo (gitignored under ~/.agentic-sdk/clj-zig/artifacts/);
  the shell creates the perf subdir on first run so a clean machine has
  no setup step."
  (io/file (System/getenv "HOME")
           ".agentic-sdk" "clj-zig" "artifacts" "perf"))

(defn- ensure-dir!
  "Create `dir` (and parents) when missing. Returns `dir`."
  [^java.io.File dir]
  (.mkdirs dir)
  dir)

;; --- per-shape namespace and setup registration --------------------------

(defn- shape-ns
  "The per-shape registration namespace. Each shape compiles into its
  own ns so the named-type and defz registries clj-zig.core holds per-ns
  do not accumulate across shapes and a malformed setup decl on one
  shape cannot pollute another's preamble (p3-t2 isolation). The ns is
  only a registration key and a C-symbol prefix, never a Clojure
  namespace the shell requires."
  [shape]
  (symbol (str "clj-zig.perf.shape-" (name (:kind shape)))))

(defn- register-setup!
  "Register `shape`'s :setup declarations (deftypez/defrecordz/defenumz/
  defz) into `shape-ns` so build-inputs and build-spec see them.
  Idempotent: re-registering the same name replaces the prior entry."
  [shape-ns setup]
  (doseq [decl setup]
    (case (:kind decl)
      :deftypez   (clj-zig.core/register-type!
                   shape-ns (layout/describe (:name decl) (:fields decl)))
      :defrecordz (clj-zig.core/register-type!
                   shape-ns (layout/describe (:name decl) (:fields decl)))
      :defenumz   (clj-zig.core/register-type!
                   shape-ns (layout/describe-enum (:name decl) (:members decl)))
      :defz       (clj-zig.core/register-decl!
                   shape-ns (:name decl) (:body decl)))))

(defn- build-spec-for
  "Build the clj-zig boundary spec for `shape` in its per-shape ns,
  resolving named-type references against the types registered for that
  ns. Throws (via spec/build-spec) when the signature names a type the
  :setup did not register."
  [shape shape-ns]
  (spec/build-spec {:ns        shape-ns
                    :name      (symbol (:name shape))
                    :signature (:signature shape)
                    :types     (clj-zig.core/types-in shape-ns)}))

;; --- floor layout resolution --------------------------------------------

(def ^:private floor-layouts
  "The keyword spellings clj-zig.perf.shape uses in a floor descriptor
  mapped to the clj-zig.foreign c-* ValueLayout vars they name. The pure
  shape core speaks keywords so it stays off the FFM classpath; the
  shell resolves them once per shape at bind time."
  {:c-byte   foreign/c-byte
   :c-short  foreign/c-short
   :c-int    foreign/c-int
   :c-long   foreign/c-long
   :c-float  foreign/c-float
   :c-double foreign/c-double
   :c-ptr    foreign/c-ptr})

(defn- resolve-floor-layout
  "Resolve one floor descriptor layout keyword to its clj-zig.foreign
  ValueLayout var (or :void for a void return). Throws an ex-info when
  the keyword is outside the floor vocabulary so a new shape that needs
  a fresh carrier surfaces the gap rather than binding a wrong ABI."
  [kw]
  (if (= :void kw)
    :void
    (let [v (get floor-layouts kw ::missing)]
      (when (= v ::missing)
        (throw (ex-info (str "Floor layout " (pr-str kw)
                             " has no clj-zig.foreign c-* resolution.")
                        {:floor-layout kw})))
      v)))

;; --- struct layout offsets (for struct segments) -------------------------

(defn- layout-byte-size
  "The C ABI byte size of one floor layout keyword. Primitives align to
  their own width; a pointer is one machine word (8 bytes on 64-bit, the
  only target this campaign measures)."
  [kw]
  (case kw
    :c-byte                    1
    :c-short                   2
    (:c-int :c-float)          4
    (:c-long :c-double :c-ptr) 8))

(defn- struct-layout-offsets
  "Compute the C ABI byte offsets and total size of a struct from its
  field layout keywords. Mirrors clj-zig.layout's extern-struct
  placement (each field rounded up to its own width then placed) so the
  bench-built input segment matches the layout clj-zig lowers."
  [field-layouts]
  (loop [flds (seq field-layouts) offset 0 out []]
    (if-not flds
      {:size offset :offsets out}
      (let [size (layout-byte-size (first flds))
            off  (* size (quot (+ offset size -1) size))]
        (recur (next flds) (+ off size) (conj out off))))))

;; --- the floor carriers --------------------------------------------------
;;
;; Each shape's :floor-args-fn returns a vector with one element per
;; INPUT floor arg (the trailing out-args are allocated by the shell,
;; not described here). A scalar input is a raw number the shell boxes
;; to the carrier width the arg layout demands (FFM rejects a Long where
;; the MethodType expects an int, so the boxing must match the layout);
;; a pointer input is a tagged descriptor the shell turns into a
;; MemorySegment on the global arena:
;;   <number>             a scalar (boxed per the arg layout)
;;   [:ptr-bytes s]       a UTF-8 byte segment for a string input
;;   [:ptr-doubles [..]]  an f64 segment for a slice input
;;   [:ptr-struct vs ls]  a struct segment (field values + field layouts)

(def ^:private global-arena
  "The process-lifetime Arena every floor segment is allocated on. The
  bench runs once and exits; the segments are few and small per shape,
  so process-lifetime is the simplest discipline that keeps a segment
  live across Criterium's warmup and measurement batches."
  (Arena/global))

(defn- write-struct-field!
  "Write one scalar `value` into `seg` at byte `offset` using the floor
  layout keyword. Doubles cross as JAVA_DOUBLE; every integer carrier
  crosses as JAVA_LONG (FFM narrows to the layout's width)."
  [^MemorySegment seg layout-kw ^long offset value]
  (case layout-kw
    (:c-float :c-double) (.set seg ValueLayout/JAVA_DOUBLE offset (double value))
    (.set seg ValueLayout/JAVA_LONG offset (long value))))

(defn- box-scalar
  "Box `value` to the primitive carrier width `layout` demands. FFM's
  MethodHandle MethodType is layout-exact (a JAVA_INT slot expects an
  int, not a long), and invokeWithArguments narrows only via the
  explicit cast the linker already baked in -- a Long where the slot
  expects an int throws WrongMethodTypeException. Boxing here to the
  exact width keeps the floor invoke signature-honest."
  [layout value]
  (case layout
    :c-byte   (byte value)
    :c-short  (short value)
    :c-int    (int value)
    :c-long   (long value)
    :c-float  (float value)
    :c-double (double value)))

(defn- build-ptr-carrier
  "Interpret one pointer-input descriptor and return the MemorySegment
  (on the global arena) the :c-ptr MethodType slot expects. Allocation
  happens once here, at setup; the timed thunk reuses the segment."
  [descriptor]
  (case (first descriptor)
    :ptr-bytes   (let [^String s (second descriptor)
                       bs  (.getBytes s StandardCharsets/UTF_8)
                       len (alength bs)
                       seg (.allocate global-arena (long len) (long 1))]
                   (when (pos? len)
                     (MemorySegment/copy bs (long 0) seg
                                         ValueLayout/JAVA_BYTE (long 0) (long len)))
                   seg)
    :ptr-doubles (let [xs (second descriptor)
                       n   (count xs)
                       arr (double-array xs)
                       seg (.allocate global-arena (long (* 8 n)) (long 8))]
                   (MemorySegment/copy arr (int 0) seg
                                       ValueLayout/JAVA_DOUBLE (long 0) (int n))
                   seg)
    :ptr-struct  (let [values       (second descriptor)
                       field-layouts (nth descriptor 2)
                       {:keys [size offsets]} (struct-layout-offsets field-layouts)
                       seg (.allocate global-arena (long size)
                                      (long (layout-byte-size (first field-layouts))))]
                   (dotimes [i (count field-layouts)]
                     (write-struct-field! seg (nth field-layouts i)
                                          (nth offsets i) (nth values i)))
                   seg)))

(defn- build-input-carrier
  "Build one INPUT carrier for the floor invoke. A scalar layout boxes
  the raw number to the carrier width; a :c-ptr layout builds the
  MemorySegment from the tagged descriptor. Called once at setup; the
  timed thunk reuses the result."
  [layout value]
  (if (= :c-ptr layout)
    (build-ptr-carrier value)
    (box-scalar layout value)))

(defn- alloc-out-seg
  "Allocate one out-segment for a trailing out-arg of the floor
  descriptor. The size comes from :struct-layout when the shape carries
  one (struct-by-value's __ret slab); otherwise the out-seg holds one
  machine word (8 bytes, the storage a *usize out-param writes through
  for :string/:owned-return). Reused across every call so the floor
  allocates nothing per call."
  [floor]
  (let [struct-layout (:struct-layout floor)
        size (if struct-layout
               (:size (struct-layout-offsets (:fields struct-layout)))
               8)]
    (.allocate global-arena (long size) (long 8))))

(defn- floor-thunk
  "Build the zero-arg callable Criterium times for `shape`. Resolves the
  floor descriptor, binds the main floor handle (and the free handle
  when the shape carries a :free-shim), pre-builds and pre-fills the
  reused carrier array, and returns a thunk that does only the per-call
  work: the main invoke, plus the free-shim dance for allocating shapes.

  `free-binding` is the lookup+symbol the free handle binds against: for
  auto-emitted shims (:string, :owned-return) the shim lives in the MAIN
  library, so free-binding shares the main lookup with a derived symbol;
  for a sibling :free-body (:handle) the shim lives in its OWN library,
  so free-binding carries that library's lookup and the free-body symbol.
  nil for a non-allocating shape (no free shim).

  The three measurement modes are uniform in construction, dispatched
  on descriptor fields rather than shape kind:

    plain              no :free-shim. One invoke against the reused
                       array.
    free-via-outparams :free-shim and main :ret :void (result written
                       to trailing out-segs). Invoke main, read the
                       out-segs, fill the free array, invoke free.
                       (:string, :owned-return)
    free-via-return    :free-shim and main :ret non-:void (result is
                       the return value). Invoke main, fill the free
                       array with the returned pointer, invoke free.
                       (:handle)"
  [{:keys [floor] :as shape} main-symbol lookup free-binding]
  (let [ret-layout   (resolve-floor-layout (:ret floor))
        arg-keywords (:args floor)
        arg-layouts  (mapv resolve-floor-layout arg-keywords)
        n-out        (:out-args floor 0)
        n-in         (- (count arg-layouts) n-out)
        input-descs  ((:floor-args-fn shape))
        handle       (foreign/downcall lookup main-symbol ret-layout arg-layouts)
        carriers     (object-array (count arg-layouts))
        out-segs     (vec (for [_ (range n-out)] (alloc-out-seg floor)))]
    ;; Pass the KEYWORD layout (not the resolved ValueLayout) to the
    ;; carrier builder: box-scalar's case dispatches on the keyword, and
    ;; a ValueLayout's str is an opaque tag (e.g. "j8") no case matches.
    (dotimes [i n-in]
      (aset carriers i (build-input-carrier (nth arg-keywords i)
                                            (nth input-descs i))))
    (dotimes [i n-out]
      (aset carriers (+ n-in i) (nth out-segs i)))
    (if-not free-binding
      (fn floor-call []
        (.invokeWithArguments handle carriers))
      (let [{:keys [free-lookup free-symbol]} free-binding
            free-layouts  (mapv resolve-floor-layout (:free-shim-args floor))
            free-handle   (foreign/downcall free-lookup free-symbol
                                            :void free-layouts)
            free-carriers (object-array (count free-layouts))]
        (if (= :void ret-layout)
          (do (assert (= n-out (count free-layouts))
                      "free-via-outparams: out-arg count must match free-shim arity")
              (fn floor-call []
                (.invokeWithArguments handle carriers)
                (dotimes [i n-out]
                  (aset free-carriers i
                        (.get ^MemorySegment (nth out-segs i)
                              ValueLayout/JAVA_LONG (long 0))))
                (.invokeWithArguments free-handle free-carriers)))
          (do (assert (= 1 (count free-layouts))
                      "free-via-return: free-shim takes exactly the returned pointer")
              (fn floor-call []
                (let [ptr (.invokeWithArguments handle carriers)]
                  (aset free-carriers 0 ptr)
                  (.invokeWithArguments free-handle free-carriers)))))))))

;; --- Criterium measurement ----------------------------------------------

(def ^:private bench-options
  "Criterium options for the per-shape measurement. A per-call invoke is
  sub-100 ns; Criterium's default warmup clears HotSpot's tiered
  compilation threshold for the MethodHandle call site. Setting
  :target-execution-time explicitly to 1e8 ns (100ms per measured batch)
  holds the measurement above Criterium's per-sample timer noise floor
  while keeping the run time bounded; the default :tail-propagation
  keeps the call in a position the JIT cannot hoist out of the timed
  loop. Recorded as the iteration-target resolution; reused unchanged by
  every shape."
  {:target-execution-time (long 1e8)})

(defn- point-estimate
  "Pull Criterium's central-tendency point estimate (in nanoseconds)
  out of one benchmark result. Criterium 0.4.6 returns :mean as
  [point (ci-low ci-high)] in SECONDS; the point estimate is the first
  element, scaled to nanoseconds (x 1e9). clj-zig.perf.stats consumes a
  :median key, so this is the bridge: the underlying statistic is
  Criterium's mean point estimate, exposed to stats under :median."
  [result]
  (* (first (:mean result)) 1e9))

(defn- measure
  "Time a zero-arg callable under Criterium and return the result map
  normalized to the shape clj-zig.perf.stats consumes:
  {:median <point-estimate-ns>}. Uses criterium.core/benchmark* (the
  function variant) so the callable is driven by Criterium's harness
  rather than captured as a bare expression by the macro. An FFM
  downcall is a native side-effect, so the JIT cannot dead-code-eliminate
  the call when the lambda's return is discarded; no explicit blackhole
  is needed."
  [thunk]
  {:median (point-estimate (criterium/benchmark* thunk bench-options))})

;; --- meta block inputs --------------------------------------------------

(defn- git-head-sha
  "The current working copy's HEAD commit SHA via a single git
  invocation, or :unknown when git is unavailable. The meta block
  records the commit so a future reader of a numbers record can check
  out exactly the source that produced it."
  []
  (try
    (let [proc (.start (ProcessBuilder. ["git" "rev-parse" "HEAD"]))]
      (if (zero? (.waitFor proc))
        (str/trim (slurp (.getInputStream proc)))
        :unknown))
    (catch Exception _ :unknown)))

(defn- meta-inputs
  "The data the meta block consumes, supplied by the shell so
  clj-zig.perf.stats stays JVM- and working-copy-free. Records the JDK,
  OS, arch, the clj-zig compile optimize mode (the default, ReleaseSafe),
  the arena-pool flag (-Dclj-zig.arena-pool, off by default), and the
  current commit SHA. Every field is required by stats/meta-block."
  []
  {:jdk           (System/getProperty "java.version")
   :os            (System/getProperty "os.name")
   :arch          (System/getProperty "os.arch")
   :optimize-mode compile/default-optimize-mode
   :arena-pool?   (Boolean/getBoolean "clj-zig.arena-pool")
   :commit        (git-head-sha)})

;; --- per-shape measurement ----------------------------------------------

(defn- shape-identity
  "The identity slice of a shape record -- the keys that name it in the
  numbers record, independent of the measurement itself."
  [shape]
  (select-keys shape [:kind :name]))

(defn- defnz-thunk
  "Build the zero-arg callable Criterium times for the defnz (high-level)
  side of `shape`. For a non-allocating shape (or :string/:owned-return,
  whose marshaller auto-frees the wrapper's buffer) the thunk is a plain
  apply of the defnz invoker against the repeated input. For :handle
  (whose marshaller does NOT auto-free; ADR 22) the thunk calls the
  sibling free invoker on each result so the Criterium loop leaks
  nothing -- matching the floor's leak-free discipline on the same call."
  [shape defnz-invoke free-invoke arg-values]
  (if (:free-body shape)
    (let [[h] arg-values]
      (fn defnz-call []
        (let [r (defnz-invoke h)]
          (free-invoke r))))
    (fn defnz-call []
      (apply defnz-invoke arg-values))))

(defn- measure-shape
  "Measure `shape` end to end. Registers the shape's :setup declarations
  into its per-shape ns, builds the boundary spec, compiles the trivial
  body once through clj-zig.core/establish! to get the artifact; when
  the shape carries a :free-body it establishes that sibling too; opens
  the library through clj-zig.foreign (process-lifetime); runs Criterium
  over the defnz invoker and the direct-handle floor invoker; returns
  the two normalized result maps stats consumes.

  When `track?` is true the bench runs under :zig/track-allocations: the
  artifact is compiled with the flag (a counting allocator over
  c_allocator), and a per-shape native allocation count is read around
  the defnz measured loop -- reset before, read after -- so the entry
  carries :native-allocations for the defnz path. The count is read
  BEFORE the floor loop runs, so the floor's wrapper invokes do not
  pollute it. The count fns are the codegen-emitted per-symbol
  `__alloc_count_get` / `__alloc_count_reset` the flag adds to the MAIN
  library."
  ([shape] (measure-shape shape nil))
  ([shape track?]
   (let [shape-ns  (shape-ns shape)
         _         (register-setup! shape-ns (:setup shape))
         the-spec  (build-spec-for shape shape-ns)
         body      (:body shape)
         gen-opts  (when track? {:options-extra {:track-allocations true}})
         artifact  (clj-zig/establish! the-spec body gen-opts)
         library   (:library artifact)
         main-sym  (:symbol artifact)
         defnz-fn  (:invoke artifact)
         lookup    (foreign/library-lookup library)
         free-art  (when (:free-body shape)
                     (let [fb (:free-body shape)]
                       (clj-zig/establish!
                        (spec/build-spec {:ns        shape-ns
                                          :name      (symbol (:name fb))
                                          :signature (:signature fb)
                                          :types     (clj-zig.core/types-in shape-ns)})
                        (:body fb)
                        gen-opts)))
         free-fn   (some-> free-art :invoke)
         ;; The free binding the floor uses to bind its free handle. For
         ;; auto-emitted shims (:string, :owned-return) the shim lives in
         ;; the MAIN library, so the binding shares the main lookup with
         ;; the derived <sym>__free symbol. For a sibling :free-body
         ;; (:handle) the shim lives in its OWN library, so the binding
         ;; carries that library's lookup and the free-body's symbol.
         free-binding (when (:free-shim (:floor shape))
                        (if free-art
                          {:free-lookup (foreign/library-lookup (:library free-art))
                           :free-symbol (:symbol free-art)}
                          {:free-lookup lookup
                           :free-symbol (str main-sym "__free")}))
         ;; Count handles for the profiling build: the codegen-emitted
         ;; per-symbol get/reset fns in the MAIN library. Bound only
         ;; under the flag; nil otherwise.
         count-get   (when track?
                       (foreign/downcall lookup (str main-sym "__alloc_count_get")
                                         foreign/c-long []))
         count-reset (when track?
                       (foreign/downcall lookup (str main-sym "__alloc_count_reset")
                                         :void []))
         arg-values ((:arg-fn shape))
         defnz-call (defnz-thunk shape defnz-fn free-fn arg-values)
         floor-call (floor-thunk shape main-sym lookup free-binding)
         ;; The defnz count is snapshot between the reset and the read,
         ;; so it isolates the defnz path's native allocations. The floor
         ;; loop runs after the read and does not affect the count.
         _ (when track? (foreign/call count-reset))
         defnz-result (measure defnz-call)
         native-allocations (when track? (foreign/call count-get))
         floor-result (measure floor-call)]
     (cond-> {:defnz defnz-result :floor floor-result}
       track? (assoc :native-allocations native-allocations)))))

;; --- profiler attach window ---------------------------------------------
;;
;; An opt-in attach window so an external profiler can attach against a
;; stable pid before the measured run. The window is OFF by default: a
;; run without --attach-window (and without CLJ_ZIG_ATTACH_WINDOW) prints
;; no pid line and never sleeps, so the captured baseline numbers and the
;; default-run stdout are unchanged. When set, the shell prints the pid
;; and sleeps that many seconds; an external async-profiler (asprof) or
;; the JDK's jcmd JFR.start attaches against the printed pid in that
;; window. See bench/RUNBOOK.md for the full capture procedure.

(defn- attach-profiler!
  "When `opts` carries an :attach-window, print the process id and sleep
  that many seconds so an external profiler can attach before the
  measured run. No-op when :attach-window is nil, so a default run is
  unchanged. The pid print and the sleep are the attach window: the
  contributor reads the pid, starts the profiler against it, and the
  bench waits."
  [opts]
  (when-let [secs (:attach-window opts)]
    (let [pid (.pid (ProcessHandle/current))]
      (println "bench pid" pid "-- attach within" (long secs) "s"))
    (Thread/sleep (* 1000 (long secs)))))

;; --- the numbers record write ------------------------------------------

(defn- write-record!
  "Write `record` (EDN) to `file`. Pretty-prints so a diff between two
  records is straightforward."
  [file record]
  (ensure-dir! (.getParentFile file))
  (with-open [w (io/writer file)]
    (binding [*out* w]
      (pr record)))
  file)

;; --- Axis-1: authoring-latency harness ----------------------------------
;;
;; Axis-1 measures a defnz redefine's wall-clock across three cache tiers
;; and separates the zig build-lib subprocess wall-clock from JVM-side
;; time. p1 found a single redefine is subprocess-dominated: the clj-zig
;; authoring code (cache lookup, source generation, toolchain hand-off)
;; runs for milliseconds, while the zig build-lib subprocess wait
;; dominates the roughly one-second wall-clock. The separation lets
;; optimization see where redefine time actually goes.
;;
;; TIERS (the cache state each tier's timed loop sees):
;;   cold                clj-zig artifact cache (.clj-zig/cache) AND the
;;                       zig global cache (.clj-zig/global-cache) cleared.
;;                       The redefine recompiles from scratch.
;;   global-cache-hit    clj-zig artifact cache cleared; zig global cache
;;                       KEPT. The recompile reuses the prior std/preamble
;;                       build in the zig global cache but relinks the
;;                       wrapper (clj-zig re-links).
;;   clj-zig-cache-hit   both KEPT. The redefine hits the clj-zig artifact
;;                       cache and runs no subprocess. The fastest path.
;;
;; CACHE-SCOPING INVARIANT (ADR 35, load-bearing): clearing is scoped
;; strictly to the bench-owned .clj-zig/ roots. The bench NEVER touches
;; the machine's per-user zig cache (clj-zig does not use it post-ADR-35;
;; every compile passes --global-cache-dir at .clj-zig/global-cache), and
;; never touches any other clj-zig project on the machine. ADR 35's
;; "persistent" qualifier on the global cache is about NOT scattering one
;; project's intermediate artifacts into a shared per-user location; the
;; project-local .clj-zig/global-cache/ the bench clears shares the
;; .clj-zig/ lifecycle the toolchain and cache already own.

(def ^:private axis1-artifact-cache-root
  "The clj-zig content-addressed artifact cache root the bench clears.
  Defaults to clj-zig.cache/artifact-paths's default so the bench clears
  exactly the directory clj-zig writes libraries into."
  ".clj-zig/cache")

(defn- axis1-global-cache-dir
  "The zig global cache directory clj-zig.compile points every compile
  at. Reuses compile/global-cache-dir so the bench clears exactly what
  clj-zig fills, never a sibling."
  []
  (io/file (compile/global-cache-dir)))

(defn- clear-tier-cache!
  "Clear the cache roots `tier`'s redefine must not see. Scoped strictly
  to the bench-owned .clj-zig/ roots; never touches the machine's per-user
  zig cache (ADR 35)."
  [tier]
  (case tier
    :cold              (do (cache/clean! axis1-artifact-cache-root)
                           (let [g (axis1-global-cache-dir)]
                             (when (.exists g)
                               (fs/delete-recursively! g))))
    :global-cache-hit  (cache/clean! axis1-artifact-cache-root)
    :clj-zig-cache-hit nil))

(defn- tier-cache-state
  "Gather the observed cache state at the predicted artifact path for
  `inputs` (the spec/body/gen the bench is about to compile) and the
  zig global cache. Returns a map the contamination check consumes. The
  shell computes the predicted path through the same build-inputs +
  artifact-paths pipeline establish! uses, so 'stale entry surviving a
  clear' means 'the predicted artifact path exists when the tier
  intended it gone'."
  [inputs]
  (let [artifact-dir (io/file (:dir (cache/artifact-paths
                                      {:root axis1-artifact-cache-root
                                       :target (:target inputs)
                                       :ns     (-> inputs :spec :ns)
                                       :name   (-> inputs :spec :name)
                                       :hash   (cache/cache-key inputs)})))]
    {:artifact-exists?     (.exists artifact-dir)
     :artifact-path        (.getAbsolutePath artifact-dir)
     :global-cache-exists? (.exists (axis1-global-cache-dir))
     :global-cache-empty?  (let [g (axis1-global-cache-dir)]
                             (when (.exists g)
                               (empty? (.listFiles g))))}))

(defn- axis1-tier-contaminated?
  "True when the observed cache state does not match the intended state
  for `tier`. A stale entry that survives a clear is contamination: the
  timed loop would hit the cache and report a clean cold number when the
  cold tier in fact ran warm. The detection runs AFTER a clear and
  BEFORE the tier's first timed sample. Inline here; the same decision
  table lands as a pure fn in stats.clj in the next task so a Tier-0
  test can pin it."
  [tier observed]
  (case tier
    :cold              (or (:artifact-exists? observed)
                           (and (:global-cache-exists? observed)
                                (not (:global-cache-empty? observed))))
    :global-cache-hit  (or (:artifact-exists? observed)
                           (not (:global-cache-exists? observed))
                           (:global-cache-empty? observed))
    :clj-zig-cache-hit (not (:artifact-exists? observed))))

(def ^:private axis1-tier-order
  "The tier run order. Cold first establishes a fresh global cache the
  global-cache-hit tier then hits; global-cache-hit populates the
  clj-zig artifact cache the clj-zig-cache-hit tier then hits. Running
  in any other order would leave a tier without the cache state its
  contract assumes."
  [:cold :global-cache-hit :clj-zig-cache-hit])

(def ^:private axis1-sample-count
  "The number of redefine samples per tier. A redefine is subprocess-
  dominated (~1s per cold sample per p1); a handful of samples bounds
  each tier's wall-clock cost while still yielding a stable median. Five
  samples rejects a one-off GC pause or filesystem hiccup without paying
  for statistical tightness the subprocess variance dwarfs."
  5)

(defn- median-of
  "The median of a seq of numbers as a double, or nil for an empty seq
  so a tier that did not run reports nil rather than dividing by zero."
  [xs]
  (when (seq xs)
    (let [sorted (sort xs)
          n      (count sorted)
          mid    (quot n 2)]
      (if (odd? n)
        (double (nth sorted mid))
        (double (/ (+ (nth sorted (dec mid)) (nth sorted mid)) 2.0))))))

(defn- time-axis1-sample
  "Time one defnz redefine of `spec` with `body` and `gen` via the
  clj-zig.core/establish! path, returning a map of:
    :wall-ms        the total redefine wall-clock (ms)
    :subprocess-ms  the zig build-lib subprocess wall-clock (ms), or nil
                    when no subprocess ran (the clj-zig-cache-hit tier)
  The cache clear runs OUTSIDE the timed region (only the establish!
  call is timed) so the sample measures redefine cost, not clear cost.
  compile/*subprocess-ms-box* is bound to a fresh volatile so the
  subprocess-ms is the build-lib wall-clock apart from JVM-side time."
  [spec body gen]
  (let [sub-box (volatile! nil)
        start   (System/nanoTime)
        _       (binding [compile/*subprocess-ms-box* sub-box]
                  (clj-zig/establish! spec body gen))
        wall-ms (quot (- (System/nanoTime) start) 1000000)]
    {:wall-ms wall-ms
     :subprocess-ms @sub-box}))

(defn- measure-axis1-tier
  "Measure `tier`'s redefine wall-clock and subprocess wall-clock over
  axis1-sample-count samples. Returns a map of:
    :wall-ms        the median redefine wall-clock (ms) across samples
    :subprocess-ms  the median zig build-lib subprocess wall-clock (ms),
                    or nil when the tier runs no subprocess
    :contaminated   truthy when the cache state after the tier's clear
                    did not match the tier's intended state
  The clear and the contamination check run ONCE per tier, before the
  first sample; each cold/global-cache-hit sample then re-clears so
  every sample sees the tier's intended state. clj-zig-cache-hit samples
  do not clear (the tier's contract keeps both caches)."
  [tier spec body gen inputs]
  (clear-tier-cache! tier)
  (let [contaminated (axis1-tier-contaminated? tier (tier-cache-state inputs))
        samples      (vec (for [_ (range axis1-sample-count)]
                            (do (when (#{:cold :global-cache-hit} tier)
                                  (clear-tier-cache! tier))
                                (time-axis1-sample spec body gen))))
        walls        (mapv :wall-ms samples)
        subs         (remove nil? (mapv :subprocess-ms samples))]
    {:wall-ms       (median-of walls)
     :subprocess-ms (when (seq subs) (median-of subs))
     :contaminated  contaminated}))

(defn- axis1-build-inputs
  "The clj-zig.cache build-inputs map for `shape`'s redefine, used to
  compute the predicted artifact path the contamination check probes.
  Reuses the same clj-zig.core/build-inputs path establish! takes, so
  the predicted path matches the path establish! writes to."
  [shape shape-ns]
  (clj-zig/build-inputs
   (build-spec-for shape shape-ns)
   (:body shape)
   {:mode :inline}))

(defn- measure-axis1-shape
  "Measure `shape`'s redefine across all three tiers. Returns the
  stats/tier-entry map (the pure-shape the numbers record consumes).
  Tiers run in axis1-tier-order so each tier's cache contract is
  reachable: cold establishes a fresh global cache the global-cache-hit
  tier then hits; global-cache-hit populates the clj-zig artifact cache
  the clj-zig-cache-hit tier then hits. The cold-tier subprocess-ms is
  the entry's :subprocess-ms (the dominant term per p1); the entry's
  :tier-contaminated flag is true when any tier detected cache-state
  contamination."
  [shape]
  (let [identity (shape-identity shape)
        shape-ns (shape-ns shape)
        _        (register-setup! shape-ns (:setup shape))
        inputs   (axis1-build-inputs shape shape-ns)
        spec     (:spec inputs)
        body     (:body inputs)
        gen      {:mode :inline}
        per-tier (fn [tier]
                   (measure-axis1-tier tier spec body gen inputs))
        results  (into {} (for [tier axis1-tier-order]
                            [tier (per-tier tier)]))
        cold     (:cold results)
        gch      (:global-cache-hit results)
        czh      (:clj-zig-cache-hit results)]
    (stats/tier-entry
     identity
     {:cold              (:wall-ms cold)
      :global-cache-hit  (:wall-ms gch)
      :clj-zig-cache-hit (:wall-ms czh)
      :subprocess-ms     (:subprocess-ms cold)
      :tier-contaminated (or (:contaminated cold)
                             (:contaminated gch)
                             (:contaminated czh))})))

(defn- axis1-artifact-file
  "The gitignored output file for an Axis-1 run on `date`. The bench
  writes axis1-<millis>.edn alongside the per-call records so a future
  reader diffing two Axis-1 runs sees the tier medians side by side."
  [date]
  (io/file artifacts-dir (str "axis1-" (.getTime date) ".edn")))

(defn- run-axis1
  "Drive the Axis-1 authoring-latency harness over `shapes`. For each
  shape: establish! once per tier per sample (cold clears both caches;
  global-cache-hit clears the clj-zig cache only; clj-zig-cache-hit
  keeps both), separate the zig build-lib subprocess wall-clock from
  JVM-side time, detect tier contamination, and shape the result via
  stats/tier-entry. Writes the record to the gitignored perf dir and
  prints one line per shape so a manual run sees the tier ordering at a
  glance."
  [shapes opts]
  (ensure-dir! artifacts-dir)
  (attach-profiler! opts)
  (let [entries (mapv measure-axis1-shape shapes)
        record  (stats/numbers-record entries (meta-inputs))
        out     (axis1-artifact-file (java.util.Date.))]
    (write-record! out record)
    (println "wrote" (str out) "--" (count entries) "axis1 shapes")
    (doseq [e entries]
      (println "  " (:kind e)
               "cold" (int (:cold e)) "ms"
               "gch" (int (:global-cache-hit e)) "ms"
               "czh" (int (:clj-zig-cache-hit e)) "ms"
               "sub" (when-let [s (:subprocess-ms e)] (int s)) "ms"
               (when (:tier-contaminated e) "(tier-contaminated)")))
    nil))

(defn- measure-one
  "Measure one shape with error isolation: a compile, floor-bind, or
  Criterium fault is caught and shaped into an :errored entry via
  stats/diagnostic-entry, so the run continues the remaining shapes
  rather than aborting the JVM. Returns the entry map. `track?` threads
  the :zig/track-allocations profiling build through to measure-shape."
  ([shape] (measure-one shape nil))
  ([shape track?]
   (let [identity (shape-identity shape)]
     (try
       (stats/shape-entry identity (measure-shape shape track?))
       (catch Throwable e
         (stats/diagnostic-entry identity e))))))

(defn- print-summary
  "Print one line per entry so a manual run sees the headline numbers
  without opening the record."
  [entries]
  (doseq [e entries]
    (if (= :errored (:status e))
      (println "  " (:kind e) ": ERRORED;" (:diagnostic e))
      (println "  " (:kind e)
               "defnz" (int (:defnz-median e)) "ns"
               "floor" (int (:floor-median e)) "ns"
               "overhead" (int (:overhead-ns e)) "ns"
               (when (:body-leak-suspect e) "(body-leak-suspect)")
               (when (some? (:native-allocations e))
                 (str "allocs " (:native-allocations e))))))
  nil)

(defn- select-shapes
  "The shapes to drive for this run. An optional `kind-arg` (a shape kind
  keyword string like \"enum\") narrows the run to one shape -- the lever
  the p3-t2 error-isolation smoke pulls: break one body, re-run just that
  shape, confirm one diagnostic. With no arg, every shape runs."
  [kind-arg]
  (if kind-arg
    (let [kw (keyword kind-arg)]
      (when-not (contains? shape/shapes kw)
        (throw (ex-info (str "Unknown shape kind: " (pr-str kind-arg)
                             "; expected one of " (sort (keys shape/shapes)))
                        {:kind kind-arg})))
      [(shape/shapes kw)])
    (shape/shape-list)))

(defn -main
  "The bench entry point. Drives every shape from clj-zig.perf.shape in
  canonical order (or one shape when a kind arg is passed), isolating
  per-shape failures so one broken body yields one :errored entry and
  the run continues the rest. Shapes each entry through stats, builds
  the numbers record, and writes it to the perf artifacts dir.

  An optional --attach-window <secs> (or CLJ_ZIG_ATTACH_WINDOW) opens a
  profiler attach window before the measured run: the bench prints its
  pid and sleeps, so an external profiler can attach. Off by default;
  see bench/RUNBOOK.md.

  An optional --track-allocations (or CLJ_ZIG_TRACK_ALLOCATIONS=1)
  enables the profiling build: each shape's wrapper is compiled under
  :zig/track-allocations and the entry carries a per-shape native
  allocation count for the defnz path (0 for non-allocating shapes,
  >0 for allocating ones). Off by default; a run without the option
  builds the default library and matches the baseline, and the
  profiling build's cache key is distinct from the default's (ADR 12).

  An optional --axis1 (or CLJ_ZIG_AXIS1=1) selects the Axis-1
  authoring-latency harness in place of the per-call overhead run.
  Axis-1 times a defnz redefine across three cache tiers (cold,
  global-cache-hit, clj-zig-cache-hit), separates the zig build-lib
  subprocess wall-clock from JVM-side time, and flags any tier whose
  cache state did not match its contract. The optional kind positional
  narrows the run to one shape, as in the default mode. Off by default;
  a run without the option takes the per-call path unchanged."
  [& args]
  (ensure-dir! artifacts-dir)
  (let [opts   (opts/parse-args args (System/getenv))
        shapes (select-shapes (:kind opts))]
    (if (:axis1 opts)
      (run-axis1 shapes opts)
      (let [_       (attach-profiler! opts)
            track?  (:track-allocations opts)
            entries (mapv #(measure-one % track?) shapes)
            record  (stats/numbers-record entries (meta-inputs))
            out     (io/file artifacts-dir
                             (str "perf-" (.getTime (java.util.Date.)) ".edn"))]
        (write-record! out record)
        (println "wrote" (str out) "--" (count entries) "shapes"
                 (when track? "(track-allocations)"))
        (print-summary entries)))))
