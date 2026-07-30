(ns clj-zig.perf.stats
  "Pure measurement shaping: turn raw Criterium result maps into the
  numbers-record entries the perf log consumes.

  This namespace is the second half of the pure measurement core
  (ADR 16). It consumes Criterium result maps as DATA -- the keys
  Criterium writes for one timed expression, chiefly :mean and :median
  -- and emits the entry maps the run shell hands to the perf log. It
  does NOT require Criterium itself: the bench shell clj-zig.perf.run is
  the only file that requires Criterium, and stats consumes whatever it
  produces.

  Derived quantities:

    overhead-ns        = defnz-median - floor-median
    overhead-ratio     = defnz-median / floor-median
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

;; body-leak guard

(def ^:const body-leak-fraction
  "Largest acceptable floor median as a fraction of the defnz median.
  The 2/5 ratio is calibrated against the invokeWithArguments floor
  (ADR 37, ADR 39); above it the entry flags :body-leak-suspect."
  2/5)

(defn- body-leak?
  "True when the floor median is more than body-leak-fraction of the
  defnz median. The comparison is strict: a floor at exactly the
  threshold fraction stays unflagged (the boundary is the largest
  acceptable floor)."
  [defnz-median floor-median]
  (> floor-median (* (double defnz-median) (double body-leak-fraction))))

;; shaping a Criterium result map into an entry

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
  produced by the bench shell's two Criterium runs, plus an optional
  :native-allocations count (the per-shape native allocation count the
  profiling build supplies when the bench runs under the
  :zig/track-allocations flag; nil when the shape was not measured under
  the flag). Returns a map with :kind, :name, :defnz-median,
  :floor-median, :overhead-ns, :body-leak-suspect, and
  :native-allocations. The count is pure data the shell threads in, so
  this namespace stays JVM-free and unit-testable without Criterium.
  Throws ex-info listing the missing Criterium results when :defnz or
  :floor lacks a :median point estimate, so a partial result is surfaced
  at shaping time rather than an opaque NPE in the double coercion."
  [ctx results]
  (let [defnz-result (:defnz results)
        floor-result (:floor results)
        missing (vec (sort (for [[k r] [[:defnz defnz-result]
                                       [:floor floor-result]]
                                  :when (nil? (:median r))]
                              k)))]
    (when (seq missing)
      (throw (ex-info (str "shape-entry missing required Criterium results: " missing)
                      {:missing missing})))
    (let [defnz-med (median defnz-result)
          floor-med (median floor-result)]
      (assoc ctx
             :defnz-median        defnz-med
             :floor-median        floor-med
             :overhead-ns         (- (double defnz-med) (double floor-med))
             :overhead-ratio      (/ (double defnz-med) (double floor-med))
             :body-leak-suspect   (body-leak? defnz-med floor-med)
             :native-allocations  (:native-allocations results)))))

;; regression gate

(def ^:private ratio-budgets
  "Per-shape ceiling on the defnz/floor overhead ratio. A catastrophic
  regression gate, not a tight perf target: each budget sits above the
  shape's normal within-run ratio and below the ratio a reflection-class
  regression produces.

  The floor uses invokeWithArguments (the deliberately naive direct-handle
  path), which is slower than the defnz invokeExact spreader for several
  shapes, so normal ratios are often below 1. That is fine: a regression
  inflates the defnz median 30-80x while the floor is unchanged, so the
  ratio spikes past any budget drawn between normal and regressed. The
  owned-slice reflection regression, for example, lifted owned-return from
  a ~0.1 ratio to ~5x.

  The ratio is within-run (defnz and floor measured back-to-back in one
  process), so it is portable across runners of different speed; no
  cross-run baseline is compared. The deterministic reflection gate is
  the primary defense; this is the backstop for non-reflection
  regressions the reflection gate cannot see."
  {:scalar-passthrough 4.0
   :struct-by-value    6.0
   :enum               4.0
   :slice-arg          4.0
   :string             3.0
   :owned-return       3.0
   :handle             4.0})

(defn ratio-budget
  "The max acceptable defnz/floor overhead ratio for `kind`, or nil when
  the kind has no budget (the shape is not gated)."
  [kind]
  (get ratio-budgets kind))

(defn breaching-shape?
  "True when a shaped `entry` breaches its ratio budget. An :errored entry
  never breaches (a measurement failure is reported separately, not a perf
  regression). A shape with no budget never breaches. The ratio must
  exceed the budget; a ratio at exactly the budget is accepted."
  [entry]
  (and (not= :errored (:status entry))
       (when-let [budget (ratio-budget (:kind entry))]
         (> (double (:overhead-ratio entry)) (double budget)))))

(defn gate-breaches
  "The subset of shaped `entries` that breach their ratio budget, in
  order. Empty when every shape is within budget. The bench shell exits
  non-zero when this is non-empty."
  [entries]
  (filterv breaching-shape? entries))

;; the meta block

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

;; diagnostic shaping

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

;; Axis-1 authoring-latency tier shaping

(defn tier-entry
  "Shape one Axis-1 shape's three-tier redefine timings into a
  numbers-record entry. `ctx` carries the shape identity ({:kind,
  :name}); `results` carries the three tier redefine wall-clock medians
  and the separated subprocess wall-clock as DATA the shell supplies:

    :cold                median redefine wall-clock (ms) for the cold
                         tier (clj-zig cache and global cache cleared)
    :global-cache-hit    median redefine wall-clock (ms) for the tier
                         that clears the clj-zig cache but keeps the
                         zig global cache (so the zig artifact is reused
                         but clj-zig re-links)
    :clj-zig-cache-hit   median redefine wall-clock (ms) for the tier
                         that keeps both caches (the fastest path; no
                         subprocess runs)
    :subprocess-ms       the zig build-lib subprocess wall-clock (ms)
                         separated from JVM-side time, captured in the
                         cold tier where the subprocess dominates; nil
                         when the tier loop did not invoke the subprocess
    :tier-contaminated   truthy when a stale cache entry survived a
                         clear and a tier did not reach its intended
                         cache state; the entry is flagged, never
                         silently misreported as a clean number

  Returns a map carrying the shape identity plus :cold,
  :global-cache-hit, :clj-zig-cache-hit, :subprocess-ms, and
  :tier-contaminated. Pure data the shell threads in, so this namespace
  stays JVM-free and unit-testable without Criterium or a native lib."
  [ctx results]
  (assoc ctx
         :cold (:cold results)
         :global-cache-hit (:global-cache-hit results)
         :clj-zig-cache-hit (:clj-zig-cache-hit results)
         :subprocess-ms (:subprocess-ms results)
         :tier-contaminated (boolean (:tier-contaminated results))))

(defn tier-contaminated?
  "True when `observed` cache state does not match the contract for
  `tier`. Run AFTER a clear, BEFORE the tier's first sample.

  Tier contracts:
    :cold                no artifact and no usable global cache
    :global-cache-hit    no artifact and a present non-empty global cache
    :clj-zig-cache-hit   artifact present

  `observed` keys: :artifact-exists?, :global-cache-exists?,
  :global-cache-empty?."
  [tier observed]
  (case tier
    :cold              (or (:artifact-exists? observed)
                           (and (:global-cache-exists? observed)
                                (not (:global-cache-empty? observed))))
    :global-cache-hit  (or (:artifact-exists? observed)
                           (not (:global-cache-exists? observed))
                           (:global-cache-empty? observed))
    :clj-zig-cache-hit (not (:artifact-exists? observed))))

;; the top-level numbers record

(defn numbers-record
  "Combine shaped entries and a meta block (built from `meta-inputs`)
  into the top-level record the perf log consumes. Entries are
  ordered by the caller; the meta block is built here so every record
  carries a consistent meta shape."
  [entries meta-inputs]
  {:meta   (meta-block meta-inputs)
   :shapes (vec entries)})
