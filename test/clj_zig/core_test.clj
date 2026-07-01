(ns clj-zig.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :as core :refer [defnz defz]]
            [clj-zig.spec :as spec]
            [clj-zig.compiler :as compiler]))

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

(deftest build-inputs-needs-no-compiler
  (testing "the hash takes the pinned Zig version, so deriving build inputs
  never resolves or runs zig; a consumer with no compiler reproduces the
  same hash a bake produced"
    (with-redefs [compiler/zig-exe (fn [] (throw (ex-info "zig must not be resolved here" {})))]
      (let [s  (spec/build-spec '{:ns app.core :name add
                                  :signature [x :i64 y :i64 :ret :i64]})
            in (core/build-inputs s "return x + y;")]
        (is (= compiler/pinned-version (:zig-version in)))
        (is (string? (:source in)))))))

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

(deftest rest-argument-lowers-to-a-slice
  (testing "a variadic scalar function boxes the rest into a long array"
    (eval `(defnz ~'variadic-max
             [~'x :i64 ~'& ~'more :i64 :ret :i64]
             "var m = x;
              for (more) |v| { if (v > m) m = v; }
              return m;"))
    (let [f (resolve 'variadic-max)]
      (is (= 4 (f 1 2 3 4)))
      (is (= 7 (f 7)))
      (is (= -1 (f -1 -2 -3)))))
  (testing "a rest of :f64 boxes doubles"
    (eval `(defnz ~'variadic-sum
             [~'& ~'xs :f64 :ret :f64]
             "var t: f64 = 0;
              for (xs) |v| t += v;
              return t;"))
    (let [f (resolve 'variadic-sum)]
      (is (= 10.0 (f 1.0 2.0 3.0 4.0)))
      (is (= 0.0 (f))))))
