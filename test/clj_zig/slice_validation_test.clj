(ns clj-zig.slice-validation-test
  "Adversarial probes for slice argument validation at the boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :refer [defnz]]))

(defnz probe-sum
  [xs [:slice :f64] :ret :f64]
  "var t: f64 = 0; for (xs) |x| t += x; return t;")

(defn- error-code [e]
  (:error/code (ex-data e)))

(deftest wrong-component-type-array-rejected
  (testing "int-array for [:slice :f64]"
    (is (= :clj-zig/type-mismatch
           (error-code (try (probe-sum (int-array [1 2 3]))
                            (catch Exception e e))))))
  (testing "long-array for [:slice :f64]"
    (is (= :clj-zig/type-mismatch
           (error-code (try (probe-sum (long-array [1 2 3]))
                            (catch Exception e e)))))))

(deftest non-array-rejected
  (testing "PersistentVector for [:slice :f64]"
    (is (= :clj-zig/type-mismatch
           (error-code (try (probe-sum [1.0 2.0 3.0])
                            (catch Exception e e))))))
  (testing "list for [:slice :f64]"
    (is (= :clj-zig/type-mismatch
           (error-code (try (probe-sum '(1.0 2.0 3.0))
                            (catch Exception e e)))))))

(deftest correct-array-still-works
  (is (= 6.0 (probe-sum (double-array [1.0 2.0 3.0])))))

(deftest empty-array-still-works
  (is (= 0.0 (probe-sum (double-array [])))))
