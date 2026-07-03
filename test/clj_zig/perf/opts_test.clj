(ns clj-zig.perf.opts-test
  "Tier-0 unit tests for the pure bench-CLI parse. Asserts the attach
  window is off by default (the option that guards default-run numbers),
  honored via the flag and the env var, the flag wins over the env, and
  the shape-kind positional survives a preceding flag value."
  (:require [clojure.test :refer [deftest is]]
            [clj-zig.perf.opts :as opts]))

(deftest attach-window-off-by-default
  (let [m (opts/parse-args [] {})]
    (is (nil? (:attach-window m)))
    (is (nil? (:kind m)))))

(deftest attach-window-off-with-only-kind
  (let [m (opts/parse-args ["scalar-passthrough"] {})]
    (is (nil? (:attach-window m)))
    (is (= "scalar-passthrough" (:kind m)))))

(deftest attach-window-set-via-flag
  (is (= 30 (:attach-window (opts/parse-args ["--attach-window" "30"] {})))))

(deftest attach-window-set-via-env
  (is (= 45 (:attach-window
             (opts/parse-args [] {"CLJ_ZIG_ATTACH_WINDOW" "45"})))))

(deftest attach-window-flag-wins-over-env
  (is (= 10 (:attach-window
             (opts/parse-args ["--attach-window" "10"]
                              {"CLJ_ZIG_ATTACH_WINDOW" "99"})))))

(deftest attach-window-and-kind-together
  (let [m (opts/parse-args ["--attach-window" "30" "scalar-passthrough"] {})]
    (is (= 30 (:attach-window m)))
    (is (= "scalar-passthrough" (:kind m)))))

(deftest kind-before-flag
  (let [m (opts/parse-args ["enum" "--attach-window" "20"] {})]
    (is (= 20 (:attach-window m)))
    (is (= "enum" (:kind m)))))

(deftest trailing-flag-without-value-is-off
  ;; A trailing --attach-window with no value parses off (nil), so a
  ;; malformed invocation does not hang the bench in an indefinite sleep.
  (is (nil? (:attach-window (opts/parse-args ["--attach-window"] {})))))

(deftest non-numeric-flag-value-is-off
  (is (nil? (:attach-window (opts/parse-args ["--attach-window" "abc"] {})))))

(deftest non-positive-flag-value-is-off
  ;; A negative or zero attach window would throw in Thread/sleep; the
  ;; parse treats it as absent so a malformed invocation is off.
  (is (nil? (:attach-window (opts/parse-args ["--attach-window" "-5"] {}))))
  (is (nil? (:attach-window (opts/parse-args ["--attach-window" "0"] {}))))
  (is (nil? (:attach-window (opts/parse-args ["scalar" "--attach-window" "-1"] {}))))
  (is (nil? (:attach-window (opts/parse-args [] {"CLJ_ZIG_ATTACH_WINDOW" "-3"})))))

(deftest track-allocations-off-by-default
  ;; The profiling build is OFF by default: a run with no option compiles
  ;; the default library and matches the baseline. Pinned so a regression
  ;; that flips the default surfaces as a test failure.
  (let [m (opts/parse-args [] {})]
    (is (not (:track-allocations m)))))

(deftest track-allocations-set-via-flag
  (is (:track-allocations (opts/parse-args ["--track-allocations"] {}))))

(deftest track-allocations-set-via-env
  (is (:track-allocations
       (opts/parse-args [] {"CLJ_ZIG_TRACK_ALLOCATIONS" "1"}))))

(deftest track-allocations-env-falsy-stays-off
  ;; Any value other than the truthy set keeps the flag off, so unsetting
  ;; the env or a stray empty string never accidentally enables the
  ;; profiling build.
  (is (not (:track-allocations
            (opts/parse-args [] {"CLJ_ZIG_TRACK_ALLOCATIONS" "0"}))))
  (is (not (:track-allocations
            (opts/parse-args [] {"CLJ_ZIG_TRACK_ALLOCATIONS" ""})))))

(deftest track-allocations-with-kind-and-attach-window
  (let [m (opts/parse-args ["--track-allocations" "string" "--attach-window" "5"] {})]
    (is (:track-allocations m))
    (is (= "string" (:kind m)))
    (is (= 5 (:attach-window m)))))

(deftest axis1-off-by-default
  ;; The Axis-1 harness is OFF by default: a run with no option takes the
  ;; default per-call overhead path. Pinned so a regression that flips the
  ;; default surfaces as a test failure.
  (let [m (opts/parse-args [] {})]
    (is (not (:axis1 m)))))

(deftest axis1-set-via-flag
  (is (:axis1 (opts/parse-args ["--axis1"] {}))))

(deftest axis1-set-via-env
  (is (:axis1 (opts/parse-args [] {"CLJ_ZIG_AXIS1" "1"}))))

(deftest axis1-env-falsy-stays-off
  (is (not (:axis1 (opts/parse-args [] {"CLJ_ZIG_AXIS1" "0"}))))
  (is (not (:axis1 (opts/parse-args [] {"CLJ_ZIG_AXIS1" ""})))))

(deftest axis1-with-kind-positional
  ;; The optional kind positional narrows Axis-1 to one shape, parallel to
  ;; the default mode.
  (let [m (opts/parse-args ["--axis1" "scalar-passthrough"] {})]
    (is (:axis1 m))
    (is (= "scalar-passthrough" (:kind m)))))

(deftest opts-source-is-pure
  ;; Source-level purity: the parse namespace requires neither Criterium
  ;; nor clj-zig native edges, so the :test lane can load it.
  (let [src (slurp "bench/clj_zig/perf/opts.clj")]
    (is (not (re-find #"\[\s*criterium" src)))
    (is (not (re-find #"\[\s*clj-zig\.foreign\b" src)))))
