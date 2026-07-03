(ns clj-zig.perf.stats
  "Pure measurement shaping: turn raw Criterium result maps into the
  numbers-record entries the perf log consumes.

  This namespace is the second half of the pure measurement core
  (ADR 16). It consumes Criterium result maps as DATA -- the keys
  Criterium writes for one timed expression, chiefly :mean and :median
  -- and emits the entry maps the run shell hands to the perf log. It
  does NOT require Criterium itself: the bench shell
  (clj-zig.perf.run, landed in a later phase) is the only file that
  requires Criterium, and stats consumes whatever it produces.

  Derived quantities:

    overhead-ns        = defnz-median - floor-median
    body-leak-suspect  = floor-median > defnz-median * body-leak-fraction

  The body-leak guard is load-bearing for honest reporting: the floor
  measures pure clj-zig.foreign direct-handle invoke cost (ADR 37) and
  is expected to be a small fraction of even the cheapest defnz median.
  When it is not, the body itself is doing measurable work (an
  allocating contract like :string, :owned-return, or :handle, or a
  non-trivial body), so the reported 'overhead' no longer isolates
  per-call invoke cost. The guard flags the entry so the reader treats
  the overhead number with care; it does not suppress the measurement.

  The meta-block builder takes its inputs as DATA supplied by the
  shell, so this namespace never probes the JVM or the working copy
  and is unit-testable without a process."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; --- body-leak guard ------------------------------------------------------

(def ^:const body-leak-fraction
  "The largest acceptable floor median as a fraction of the defnz
  median. A floor at or below this fraction is a clean isolate of
  per-call invoke cost; a floor above it flags the entry as
  :body-leak-suspect.

  Chosen at 1/10: the clj-zig.foreign direct-handle invoke path
  allocates nothing per call (foreign.clj PERFORMANCE), so it is well
  under 10% of even the cheapest defnz median (the ADR 39 scalar hot
  path, which still boxes its carriers and pays the & args sequence).
  Named as a constant so a maintainer tunes it without editing the
  predicate, and so a unit test can assert the boundary without
  copying a magic number."
  1/10)

(defn- body-leak?
  "True when the floor median is more than body-leak-fraction of the
  defnz median. The comparison is strict: a floor at exactly the
  threshold fraction stays unflagged (the boundary is the largest
  acceptable floor)."
  [defnz-median floor-median]
  (> floor-median (* (double defnz-median) (double body-leak-fraction))))

;; --- shaping a Criterium result map into an entry ------------------------

(defn- median
  "Pull the median point estimate (in nanoseconds) out of a Criterium
  result map. Criterium stores it under :median. The other keys
  (:mean, :variance, :tail-quantile, ...) pass through untouched."
  [result]
  (:median result))

(defn shape-entry
  "Shape one shape's raw Criterium results into a numbers-record entry.

  `ctx` carries the shape's identity ({:kind, :name, ...}); `results`
  is a map of {:defnz criterium-result :floor criterium-result} as
  produced by the bench shell's two Criterium runs. Returns a map with
  :kind, :name, :defnz-median, :floor-median, :overhead-ns, and
  :body-leak-suspect."
  [ctx results]
  (let [defnz-med (median (:defnz results))
        floor-med (median (:floor results))]
    (assoc ctx
           :defnz-median      defnz-med
           :floor-median      floor-med
           :overhead-ns       (- (double defnz-med) (double floor-med))
           :body-leak-suspect (boolean (body-leak? defnz-med floor-med)))))

;; --- the meta block -------------------------------------------------------

(def ^:private required-meta-keys
  "Every field the meta block must carry. A measurement record without
  one of these is ambiguous: a future reader cannot tell which JDK or
  optimize mode produced the numbers. Missing fields throw rather than
  silently gap."
  [:jdk :os :arch :optimize-mode :arena-pool? :commit])

(defn meta-block
  "Build the meta block of a numbers record from inputs the bench
  shell supplies as data. Throws ex-info listing the missing fields
  when any required input is absent, so a partial meta block is a
  measurement bug surfaced at shaping time, never a silent gap."
  [inputs]
  (let [missing (vec (sort (set/difference (set required-meta-keys)
                                            (set (keys inputs)))))]
    (when (seq missing)
      (throw (ex-info (str "meta-block missing required fields: " missing)
                      {:missing missing}))))
  (select-keys inputs required-meta-keys))

;; --- diagnostic shaping ---------------------------------------------------

(defn- ex-chain-messages
  "Walk the cause chain of `throwable` and return each Throwable's
  message, root cause first. Bounded by the chain length; nil
  messages are dropped."
  [throwable]
  (loop [t throwable out [] seen 0]
    (if (or (nil? t) (> seen 32))
      (reverse out)
      (let [msg (ex-message t)]
        (recur (ex-cause t)
               (cond-> out (some? msg) (conj msg))
               (inc seen))))))

(defn diagnostic-entry
  "Shape a caught exception for one shape into a numbers-record entry
  with :status :errored and a :diagnostic string. The diagnostic
  carries the shape kind and name (for triage), the ex-data map, and
  the full cause-chain messages (root cause first), so a reader
  diffing two records can tell at a glance which shape failed and why.
  Handles a plain Throwable without ex-data or a cause so a per-shape
  try/catch never rethrows on the shaping step."
  [ctx throwable]
  (let [data       (ex-data throwable)
        messages   (ex-chain-messages throwable)
        parts      (cond-> []
                     true            (conj (str "shape " (pr-str (:kind ctx))))
                     true            (conj (str "name " (:name ctx)))
                     (seq messages)  (conj (str "messages " (pr-str messages)))
                     (seq data)      (conj (str "ex-data " (pr-str data))))]
    (assoc ctx
           :status     :errored
           :diagnostic (str/join "; " parts))))

;; --- the top-level numbers record ----------------------------------------

(defn numbers-record
  "Combine shaped entries and a meta block (built from `meta-inputs`)
  into the top-level record the perf log consumes. Entries are
  ordered by the caller; the meta block is built here so every record
  carries a consistent meta shape."
  [entries meta-inputs]
  {:meta   (meta-block meta-inputs)
   :shapes (vec entries)})
