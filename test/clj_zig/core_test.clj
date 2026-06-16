(ns clj-zig.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :refer [defnz defz]]))

;; Functions defined at namespace load, then exercised below.

(defnz add-fn
  "Adds two signed integers."
  [x :i64
   y :i64
   :ret :i64]
  "return x + y;")

(defnz sum-of-squares
  [{a :a b :b} {:a :f64 :b :f64}
   :ret :f64]
  "return a * a + b * b;")

(defz helpers
  "fn triple(x: i64) i64 {
      return x * 3;
  }")

(defnz tripled
  [x :i64 :ret :i64]
  "return triple(x);")

(deftest defines-and-calls-a-scalar-function
  (is (= 3 (add-fn 1 2)))
  (is (= 42 (add-fn 20 22)))
  (is (= -5 (add-fn 5 -10))))

(deftest lowers-clojure-side-destructuring-to-scalars
  (testing "a map argument is destructured in Clojure and passed as scalars"
    (is (= 25.0 (sum-of-squares {:a 3.0 :b 4.0})))))

(deftest defz-declarations-are-callable-from-bodies
  (is (= 9 (tripled 3))))

(deftest attaches-inspection-metadata-to-the-var
  (let [m (meta #'add-fn)]
    (is (= "Adds two signed integers." (:doc m)))
    (is (= '([x y]) (:arglists m)))
    (let [info (:clj-zig/info m)]
      (is (map? info))
      (is (= "clj_zig_clj_2d_zig_2e_core_2d_test_add_2d_fn" (:symbol info)))
      (is (string? (:generated-source info)))
      (is (contains? #{:compiled :cached} (:status info))))))

(deftest redefinition-recompiles-and-keeps-last-good
  (let [define (fn [body] (eval `(defnz ~'kg-fn [~'x :i64 :ret :i64] ~body)))]
    (define "return x + 1;")
    (is (= 6 ((resolve 'kg-fn) 5)))
    (testing "redefinition rebinds the Var"
      (define "return x + 100;")
      (is (= 105 ((resolve 'kg-fn) 5))))
    (testing "a failed recompile throws but keeps the last good binding"
      (is (thrown? clojure.lang.ExceptionInfo (define "return x + ;")))
      (is (= 105 ((resolve 'kg-fn) 5))))))
