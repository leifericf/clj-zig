(ns clj-zig.check-reflection-test
  "Tier-0 tests for the reflection gate's pure verdict. The gate is a CI
  safety mechanism, so its decision logic is pure and pinned here."
  (:require [clojure.test :refer [deftest is]]
            [clj-zig.check-reflection :as gate]))

(deftest verdict-ok-when-files-loaded-and-no-warnings
  (is (= :ok (gate/gate-verdict 23 "")))
  (is (= :ok (gate/gate-verdict 5 "unrelated stderr noise but no warning"))))

(deftest verdict-fail-when-a-reflection-warning-was-captured
  (is (= :fail (gate/gate-verdict
                23 "Reflection warning, clj_zig/x.clj:1:1 - ..."))))

(deftest verdict-empty-when-no-files-were-loaded
  ;; A gate that reports OK after loading zero files silently hides a
  ;; misconfiguration (wrong cwd, src/ moved). The empty case must fail.
  (is (= :empty (gate/gate-verdict 0 ""))))

(deftest verdict-fail-takes-precedence-over-empty
  ;; When warnings are present the verdict is :fail regardless of the file
  ;; count, so a reflection regression is never masked by an empty count.
  (is (= :fail (gate/gate-verdict 0 "Reflection warning, ..."))))
