(ns clj-zig.template-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clj-zig.template :as tpl]
            [clj-zig.core :refer [defnz]]))

(deftest binary-op-generates-correct-zig
  (is (= "return x * y;" (tpl/binary-op "*" 'x 'y))))

(deftest slice-fold-generates-fold-loop
  (let [src (tpl/slice-fold 'xs 0.0 "+=")]
    (is (str/includes? src "var acc = 0.0;"))
    (is (str/includes? src "for (xs)"))
    (is (str/includes? src "acc += __elem;"))))

(deftest comparator-generates-comparison
  (is (= "return a < b;" (tpl/comparator "<" 'a 'b))))

(deftest clamp-generates-bounds-check
  (let [src (tpl/clamp 'v '0 '255)]
    (is (str/includes? src "if (v < 0)"))
    (is (str/includes? src "if (v > 255)"))))

(deftest template-works-with-defnz
  (eval `(defnz ~'tpl-square [~'x :f64 :ret :f64] ~(tpl/binary-op "*" 'x 'x)))
  (eval `(defnz ~'tpl-sum [~'xs [:slice :const :f64] :ret :f64]
           ~(tpl/reducer-step 'xs "f64" "0.0" "+=")))
  (is (= 25.0 ((resolve 'tpl-square) 5.0)))
  (is (= 6.0 ((resolve 'tpl-sum) (double-array [1.0 2.0 3.0])))))
