(ns zigar.slice-e2e-prop-test
  "End-to-end round-trips for slices, arrays, and optionals against the
  compiled fixtures. A const slice sums to the same value `reduce` does
  across empty, single, and large inputs; a mutable slice is doubled in
  place; a fixed array sums and rejects the wrong length; an optional
  pointer carries a value or nil, and a single-item pointer rejects a
  multi-element array."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [zigar.fixtures :as f]
            [zigar.gen :as g]))

(defn- code-from [thunk]
  (try (thunk) nil (catch clojure.lang.ExceptionInfo e (:error/code (ex-data e)))))

(def gen-finite-doubles
  (gen/vector (gen/double* {:infinite? false :NaN? false}) 0 12))

(defspec const-slice-sums-like-reduce 300
  (prop/for-all [v gen-finite-doubles]
    (let [arr (double-array v)]
      (== (f/sum-f64 arr) (reduce + 0.0 (vec arr))))))

(defspec const-int-slice-sums-with-wraparound 300
  (prop/for-all [v (gen/vector (g/gen-scalar-value :i64) 0 12)]
    (let [arr (g/primitive-array :i64 v)]
      ;; The fixture accumulates with wrapping add, like Zig's +%=.
      (== (f/sum-i64 arr) (reduce unchecked-add 0 (vec arr))))))

(defspec mutable-slice-doubles-in-place 300
  (prop/for-all [v gen-finite-doubles]
    (let [arr (double-array v)
          before (vec arr)]
      (f/double-all! arr)
      (= (mapv #(* 2.0 %) before) (vec arr)))))

(defspec fixed-array-sums-its-four 200
  (prop/for-all [v (gen/vector (g/gen-scalar-value :i32) 4)]
    (let [arr (g/primitive-array :i32 v)]
      (== (f/array4-sum arr) (reduce + (map long (vec arr)))))))

(defspec optional-pointer-carries-value-or-nil 300
  (prop/for-all [v (g/gen-scalar-value :i64)]
    (and (== v (f/deref-or-default (long-array [v])))
         (= -1 (f/deref-or-default nil)))))

(deftest fixed-array-rejects-the-wrong-length
  (is (= :zigar/array-length (code-from #(f/array4-sum (int-array [1 2 3])))))
  (is (= :zigar/array-length (code-from #(f/array4-sum (int-array [1 2 3 4 5]))))))

(deftest single-item-pointer-rejects-a-multi-element-array
  (is (= :zigar/pointer-arity (code-from #(f/deref-or-default (long-array [1 2]))))))

(deftest optional-return-is-the-value-or-nil
  (testing "a present optional pointer dereferences to its value"
    (is (= 42 (f/maybe-answer true))))
  (testing "a null optional pointer is nil"
    (is (nil? (f/maybe-answer false)))))

(deftest empty-and-single-slices-are-handled
  (is (= 0.0 (f/sum-f64 (double-array []))))
  (is (= 5.0 (f/sum-f64 (double-array [5.0])))))
