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
            [clj-zig.compile :as compile]
            [clj-zig.foreign :as foreign]
            [clj-zig.layout :as layout]
            [clj-zig.perf.shape :as shape]
            [clj-zig.perf.stats :as stats]
            [clj-zig.spec :as spec]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [criterium.core :as criterium])
  (:import (java.lang.invoke MethodHandle)
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
  the two normalized result maps stats consumes."
  [shape]
  (let [shape-ns  (shape-ns shape)
        _         (register-setup! shape-ns (:setup shape))
        the-spec  (build-spec-for shape shape-ns)
        body      (:body shape)
        artifact  (clj-zig/establish! the-spec body)
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
                       (:body fb))))
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
        arg-values ((:arg-fn shape))
        defnz-call (defnz-thunk shape defnz-fn free-fn arg-values)
        floor-call (floor-thunk shape main-sym lookup free-binding)]
    {:defnz (measure defnz-call)
     :floor (measure floor-call)}))

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

(defn- measure-one
  "Measure one shape with error isolation: a compile, floor-bind, or
  Criterium fault is caught and shaped into an :errored entry via
  stats/diagnostic-entry, so the run continues the remaining shapes
  rather than aborting the JVM. Returns the entry map."
  [shape]
  (let [identity (shape-identity shape)]
    (try
      (stats/shape-entry identity (measure-shape shape))
      (catch Throwable e
        (stats/diagnostic-entry identity e)))))

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
               (when (:body-leak-suspect e) "(body-leak-suspect)"))))
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
  the numbers record, and writes it to the perf artifacts dir."
  [& args]
  (ensure-dir! artifacts-dir)
  (let [shapes  (select-shapes (first args))
        entries (mapv measure-one shapes)
        record  (stats/numbers-record entries (meta-inputs))
        out     (io/file artifacts-dir
                         (str "perf-" (.getTime (java.util.Date.)) ".edn"))]
    (write-record! out record)
    (println "wrote" (str out) "--" (count entries) "shapes")
    (print-summary entries)))
