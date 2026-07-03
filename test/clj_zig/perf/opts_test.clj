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

(deftest opts-source-is-pure
  ;; Source-level purity: the parse namespace requires neither Criterium
  ;; nor clj-zig native edges, so the :test lane can load it.
  (let [src (slurp "bench/clj_zig/perf/opts.clj")]
    (is (not (re-find #"\[\s*criterium" src)))
    (is (not (re-find #"\[\s*clj-zig\.foreign\b" src)))))
