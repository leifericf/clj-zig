(ns clj-zig.perf.stats-test
  "Tier-0 unit tests for the pure perf stats core. Asserts the
  overhead derivation, the body-leak guard with its named threshold
  constant, the meta-block builder fed from fixture inputs (no JVM
  probe needed), the diagnostic-entry shaper, and the source-level
  purity of clj-zig.perf.stats (no Criterium, no clj-zig native
  require)."
  (:require [clojure.test :refer [deftest is]]
            [clj-zig.perf.stats :as stats]))

;; --- fixture inputs ------------------------------------------------------
;; Pure data: the same map shape Criterium produces for a single
;; measurement, captured here so the unit tests do not require
;; Criterium on the classpath.

(defn- criterium-result
  "A minimal Criterium result map carrying the fields stats consumes:
  the mean and median point estimates in nanoseconds. The other
  Criterium keys (:variance, :tail-quantile, etc.) are passed through
  untouched."
  [median mean]
  {:mean mean :median median})

(def ^:private clean-defnz      (criterium-result 60.0 62.0))
(def ^:private clean-floor      (criterium-result 5.0  6.0))
(def ^:private leaky-defnz      (criterium-result 600.0 610.0))
(def ^:private leaky-floor      (criterium-result 500.0 510.0))
;; A 5ns floor against a 60ns defnz: the floor is ~8% of defnz, under
;; the threshold (a small fraction), so no body-leak flag fires.
(def ^:private boundary-defnz   (criterium-result 60.0 62.0))
(def ^:private boundary-floor   (criterium-result (* 60.0 stats/body-leak-fraction)
                                                  (* 62.0 stats/body-leak-fraction)))

(def ^:private fixture-meta-inputs
  "Fixture inputs the meta-block builder consumes. Every value is data
  supplied by the shell, so the unit test does not probe the JVM."
  {:jdk           "26.0.1+9"
   :os            :macos
   :arch          :aarch64
   :optimize-mode "ReleaseSafe"
   :arena-pool?   false
   :commit        "abc1234"})

(deftest overhead-is-defnz-minus-floor
  (let [entry (stats/shape-entry
               {:kind :scalar-passthrough :name "echo-i64"}
               {:defnz clean-defnz :floor clean-floor})]
    (is (= 55.0 (:overhead-ns entry)))
    (is (= 60.0 (:defnz-median entry)))
    (is (= 5.0  (:floor-median entry)))))

(deftest overhead-entry-carries-shape-and-name
  (let [entry (stats/shape-entry
               {:kind :scalar-passthrough :name "echo-i64"}
               {:defnz clean-defnz :floor clean-floor})]
    (is (= :scalar-passthrough (:kind entry)))
    (is (= "echo-i64" (:name entry)))))

(deftest body-leak-guard-flagged-when-floor-is-not-a-small-fraction
  (let [entry (stats/shape-entry
               {:kind :owned-return :name "owned-double"}
               {:defnz leaky-defnz :floor leaky-floor})]
    (is (:body-leak-suspect entry)
        "a floor close to the defnz median flags the measurement")))

(deftest body-leak-guard-clear-when-floor-is-a-small-fraction
  (let [entry (stats/shape-entry
               {:kind :scalar-passthrough :name "echo-i64"}
               {:defnz clean-defnz :floor clean-floor})]
    (is (not (:body-leak-suspect entry))
        "a small floor against a larger defnz does not flag")))

(deftest body-leak-threshold-is-a-named-constant
  ;; The threshold is a named constant (not a magic number in the
  ;; body-leak predicate) so it is assertable and tunable.
  (is (number? stats/body-leak-fraction))
  (is (pos? stats/body-leak-fraction))
  (is (< stats/body-leak-fraction 1.0)
      "the body-leak fraction is a proper fraction of the defnz median"))

(deftest body-leak-boundary-is-inclusive
  ;; At exactly the threshold fraction, the guard does NOT fire: a
  ;; floor at the threshold is the largest acceptable floor and stays
  ;; unflagged. A floor above the threshold fires.
  (let [at-threshold (stats/shape-entry
                      {:kind :scalar-passthrough :name "echo-i64"}
                      {:defnz boundary-defnz :floor boundary-floor})]
    (is (not (:body-leak-suspect at-threshold))
        "a floor at exactly the threshold fraction stays unflagged"))
  (let [above (stats/shape-entry
               {:kind :scalar-passthrough :name "echo-i64"}
               {:defnz boundary-defnz
                :floor (criterium-result (* 61.0 stats/body-leak-fraction)
                                         (* 62.0 stats/body-leak-fraction))})]
    (is (:body-leak-suspect above)
        "a floor above the threshold fraction fires")))

(deftest meta-block-built-from-fixture-inputs
  (let [meta (stats/meta-block fixture-meta-inputs)]
    (is (= "26.0.1+9"          (:jdk meta)))
    (is (= :macos              (:os meta)))
    (is (= :aarch64            (:arch meta)))
    (is (= "ReleaseSafe"       (:optimize-mode meta)))
    (is (= false               (:arena-pool? meta)))
    (is (= "abc1234"           (:commit meta)))))

(deftest meta-block-rejects-missing-inputs
  ;; The shell MUST supply every meta field; a partial input is a
  ;; measurement-record bug, not a silent gap.
  (is (thrown? clojure.lang.ExceptionInfo
               (stats/meta-block (dissoc fixture-meta-inputs :commit)))))

(deftest diagnostic-entry-shaper-from-exception
  (let [err    (ex-info "compile failed" {:shape :owned-return :phase :compile}
                        (RuntimeException. "zig exit 1"))
        entry  (stats/diagnostic-entry
                {:kind :owned-return :name "owned-double"} err)]
    (is (= :errored (:status entry)))
    (is (= :owned-return (:kind entry)))
    (is (= "owned-double" (:name entry)))
    (is (string? (:diagnostic entry)))
    (is (re-find #"compile failed" (:diagnostic entry)))
    (is (re-find #"owned-return" (:diagnostic entry))
        "the diagnostic carries the shape kind for triage")))

(deftest diagnostic-entry-preserves-cause-chain
  (let [cause  (RuntimeException. "zig exit 1")
        err    (ex-info "compile failed" {:shape :owned-return} cause)
        entry  (stats/diagnostic-entry {:kind :owned-return :name "o"} err)]
    (is (re-find #"zig exit 1" (:diagnostic entry))
        "the underlying cause message appears in the diagnostic")))

(deftest diagnostic-entry-handles-throwable-without-ex-data
  ;; A plain Throwable (no ex-data, no cause) still shapes cleanly so a
  ;; per-shape try/catch never rethrows on the shaping step.
  (let [entry (stats/diagnostic-entry
               {:kind :string :name "s"}
               (RuntimeException. "boom"))]
    (is (= :errored (:status entry)))
    (is (re-find #"boom" (:diagnostic entry)))))

(deftest numbers-record-shapes-many-entries
  (let [entries [(stats/shape-entry
                  {:kind :scalar-passthrough :name "a"}
                  {:defnz clean-defnz :floor clean-floor})
                 (stats/shape-entry
                  {:kind :enum :name "b"}
                  {:defnz clean-defnz :floor clean-floor})]
        record (stats/numbers-record entries fixture-meta-inputs)]
    (is (= 2 (count (:shapes record))))
    (is (= (stats/meta-block fixture-meta-inputs)
           (:meta record)))))

(deftest stats-source-is-pure
  ;; Source-level purity check: stats consumes Criterium result maps
  ;; but does not require the Criterium namespace itself.
  (let [src (slurp "bench/clj_zig/perf/stats.clj")]
    (is (not (re-find #"\[\s*clj-zig\.core\b" src)))
    (is (not (re-find #"\[\s*clj-zig\.ffm\b" src)))
    (is (not (re-find #"\[\s*clj-zig\.foreign\b" src)))
    (is (not (re-find #"\[\s*criterium" src)))))
