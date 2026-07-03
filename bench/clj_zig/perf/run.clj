(ns clj-zig.perf.run
  "The imperative shell of the per-call overhead harness (ADR 16).

  This is the ONLY namespace in the project that requires Criterium, and
  it carries NO deftest, so cognitect-test-runner never loads it under
  the :test lane (the load-bearing assertion verified in p2-t3).

  The shell drives one or more shapes from clj-zig.perf.shape: it
  compiles each shape's trivial body through clj-zig.core (the normal
  defnz boundary path), pairs the defnz measurement against its
  clj-zig.foreign direct-handle floor, drives both under Criterium,
  shapes the results through clj-zig.perf.stats, and writes the numbers
  record EDN to ~/.agentic-sdk/clj-zig/artifacts/perf/.

  This phase drives ONE shape, :scalar-passthrough, end to end and
  settles the measurement-design choices the shape matrix reuses: the
  floor-invoke pattern, the iteration target, and the median bridge
  into stats. Each choice is documented at the form that realizes it
  and recorded in the campaign decisions log.

  Governing principle: MEASUREMENT ONLY. Nothing here optimizes; the
  first optimization is a later backlog item, not pickable until this
  feature and its baseline land."
  (:require [clj-zig.core :as clj-zig]
            [clj-zig.compile :as compile]
            [clj-zig.foreign :as foreign]
            [clj-zig.perf.shape :as shape]
            [clj-zig.perf.stats :as stats]
            [clj-zig.spec :as spec]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [criterium.core :as criterium])
  (:import (java.lang.invoke MethodHandle)))

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

;; --- the floor invoker --------------------------------------------------
;;
;; The floor is the cheapest reliable Clojure invoke of the cached
;; clj-zig.foreign downcall handle. foreign.clj's PERFORMANCE note
;; describes the IDEAL a Java caller reaches: invoke the cached handle
;; directly with exactly-typed primitive arguments so the per-frame
;; path allocates nothing. Clojure's compiler cannot emit that call
;; site -- a primitive-hinted defn around `(.invoke h x)` fails
;; AbstractMethodError (the hinted fn class is generated with an
;; abstract invokePrim the body cannot satisfy), and an inline
;; `(.invoke h (long x))` fails ClassCastException (Clojure emits the
;; call against `invoke(Object[])`, so a primitive long cannot cross).
;; The reliable alloc-frugal path is `invokeWithArguments` against a
;; per-shape reused object-array -- the same discipline clj-zig's own
;; ADR 39 scalar hot path uses. The defnz side carries the same
;; per-call carrier boxing plus clj-zig's to-carrier and from-return
;; coercion, the arity check, and the & args sequence, so the measured
;; overhead isolates clj-zig's per-call wrapping cost. That is the
;; honest answer for a Clojure caller.
;;
;; The carriers are pre-filled ONCE at setup so the per-call fn is
;; pure invoke cost. The values do not change between calls (the shape
;; repeats one input), so re-filling per call would measure the fill,
;; not the invoke.

(defn- floor-invoke-fn
  "Build a zero-arg callable that invokes the cached floor `handle`
  through `carriers`, pre-filled with `arg-values` at setup. Returns
  the per-call fn Criterium times: a single invokeWithArguments against
  the reused carrier array. The fill loop runs once here, not per
  call, so the timed fn measures only the invoke."
  [^MethodHandle handle ^objects carriers arg-values]
  (loop [i 0 vs (seq arg-values)]
    (when vs
      (aset carriers i (long (first vs)))
      (recur (unchecked-inc-int i) (next vs))))
  (fn floor-call []
    (.invokeWithArguments handle carriers)))

;; --- Criterium measurement ----------------------------------------------

(def ^:private bench-options
  "Criterium options for the per-shape measurement. The scalar call is
  sub-100 ns; Criterium's default warmup clears HotSpot's tiered
  compilation threshold for the MethodHandle call site (Criterium runs
  billions of iterations during warmup, well past the ~10k-call C2
  threshold). Setting :target-execution-time explicitly to 1e8 ns
  (100ms per measured batch) holds the measurement above Criterium's
  per-sample timer noise floor while keeping the run time bounded; the
  default :tail-propagation keeps the call in a position the JIT
  cannot hoist out of the timed loop. Recorded as the iteration-target
  resolution; reused unchanged by the shape matrix."
  {:target-execution-time (long 1e8)})

(defn- point-estimate
  "Pull Criterium's central-tendency point estimate (in nanoseconds)
  out of one benchmark result. Criterium 0.4.6 returns :mean as
  `[point (ci-low ci-high)]` in SECONDS; the point estimate is the
  first element, scaled to nanoseconds (x 1e9). clj-zig.perf.stats
  consumes a :median key, so this is the bridge: the underlying
  statistic is Criterium's mean point estimate (the central-tendency
  number Criterium reports; Criterium 0.4.6 does not expose a median
  directly), exposed to stats under :median. The overhead and
  body-leak math in stats works identically on a mean point estimate
  as on a median."
  [result]
  (* (first (:mean result)) 1e9))

(defn- measure
  "Time a zero-arg callable under Criterium and return the result map
  normalized to the shape clj-zig.perf.stats consumes:
  `{:median <point-estimate-ns>}`. Uses criterium.core/benchmark* (the
  function variant) so the callable is driven by Criterium's harness
  rather than captured as a bare expression by the macro. An FFM
  downcall is a native side-effect, so the JIT cannot dead-code-
  eliminate the call when the lambda's return is discarded; no
  explicit blackhole is needed."
  [thunk]
  {:median (point-estimate (criterium/benchmark* thunk bench-options))})

;; --- meta block inputs --------------------------------------------------

(defn- git-head-sha
  "The current working copy's HEAD commit SHA via a single git
  invocation, or :unknown when git is unavailable. The meta block
  records the commit so a future reader of a numbers record can check
  out exactly the source that produced it. `git rev-parse` writes a
  trailing newline; trimmed so the EDN value is the bare SHA."
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
  OS, arch, the clj-zig compile optimize mode (the default, ReleaseSafe,
  matching the decision-log smallest-sufficient baseline row), the
  arena-pool flag (-Dclj-zig.arena-pool, off by default), and the
  current commit SHA. Every field is required by stats/meta-block; a
  missing field throws at shaping time, surfacing a measurement bug
  rather than a silent gap."
  []
  {:jdk           (System/getProperty "java.version")
   :os            (System/getProperty "os.name")
   :arch          (System/getProperty "os.arch")
   :optimize-mode compile/default-optimize-mode
   :arena-pool?   (Boolean/getBoolean "clj-zig.arena-pool")
   :commit        (git-head-sha)})

;; --- per-shape measurement ----------------------------------------------

(defn- shape-identity
  "The identity slice of a shape record -- the keys that name it in
  the numbers record, independent of the measurement itself. Carried
  into the shaped entry so a reader diffing two records sees which
  shape a row describes."
  [shape]
  (select-keys shape [:kind :name]))

(defn- build-spec-for
  "Build the clj-zig boundary spec for `shape`. The spec's :ns is this
  shell namespace (every shape compiles into its own library; the
  munged C symbol is unique per shape name within the ns) and :name is
  the shape's :name. :signature and :body come straight off the record;
  the body is trivial so user computation is negligible (the body-leak
  guard catches a body that is not)."
  [shape]
  (spec/build-spec {:ns        'clj-zig.perf.run
                    :name      (symbol (:name shape))
                    :signature (:signature shape)}))

(defn- measure-scalar-passthrough
  "Measure the :scalar-passthrough shape end to end. Compiles the
  trivial body once through clj-zig.core/establish! to get the
  artifact ({:library :symbol :invoke}); opens the library through
  clj-zig.foreign (process-lifetime, never closed mid-run); binds the
  floor handle via clj-zig.foreign/downcall against the spec's emitted
  C symbol (the same symbol the defnz path binds); runs Criterium over
  the defnz invoker (:invoke, the ADR 39 scalar hot path) and the
  direct-handle floor invoker (the cached MethodHandle, alloc-frugal
  per call); and returns the two normalized result maps stats
  consumes."
  [shape]
  (let [body        (:body shape)
        the-spec    (build-spec-for shape)
        artifact    (clj-zig/establish! the-spec body)
        library     (:library artifact)
        c-symbol    (:symbol artifact)
        defnz-fn    (:invoke artifact)
        floor       (:floor shape)
        ret-layout  (resolve-floor-layout (:ret floor))
        arg-layouts (mapv resolve-floor-layout (:args floor))
        lookup      (foreign/library-lookup library)
        handle      (foreign/downcall lookup c-symbol ret-layout arg-layouts)
        arg-values  ((:arg-fn shape))
        carriers    (object-array (count arg-values))
        floor-call  (floor-invoke-fn handle carriers arg-values)
        defnz-call  (fn [] (apply defnz-fn arg-values))]
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

(defn -main
  "The bench entry point. Drives the :scalar-passthrough shape, shapes
  the entry through stats, builds the numbers record, and writes it to
  the perf artifacts dir. Prints a short summary so a manual run sees
  the headline numbers without opening the record.

  A Criterium or compile fault on the one shape is caught and shaped
  into one :errored entry via stats/diagnostic-entry, so the run still
  writes a record and exits 0 rather than aborting the JVM with a
  stack trace (p3 generalizes this isolation to every shape; the
  single-shape shell carries the same discipline)."
  [& _args]
  (ensure-dir! artifacts-dir)
  (let [shape (shape/shapes :scalar-passthrough)
        {:keys [entry errored?]}
        (try
          {:entry   (stats/shape-entry
                     (shape-identity shape)
                     (measure-scalar-passthrough shape))
           :errored? false}
          (catch Throwable e
            {:entry   (stats/diagnostic-entry (shape-identity shape) e)
             :errored? true}))
        record (stats/numbers-record [entry] (meta-inputs))
        out    (io/file artifacts-dir
                        (str "perf-" (name (:kind shape)) "-"
                             (.getTime (java.util.Date.)) ".edn"))]
    (write-record! out record)
    (println "wrote" (str out))
    (if errored?
      (println "  status:    :errored")
      (do (println "  defnz-median:" (:defnz-median entry) "ns")
          (println "  floor-median:" (:floor-median entry) "ns")
          (println "  overhead-ns: " (:overhead-ns entry))
          (println "  body-leak-suspect:" (:body-leak-suspect entry))))))
