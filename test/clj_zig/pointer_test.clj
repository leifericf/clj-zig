(ns clj-zig.pointer-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig :as zig]
            [clj-zig.core :refer [defnz]]))

;; --- Single-item pointers -----------------------------------------------

(defnz deref-f64
  [p [:ptr :const :f64]
   :ret :f64]
  "return p.*;")

(defnz store-42!
  [p [:ptr :i64]
   :ret :void]
  "p.* = 42;")

(deftest reads-through-a-const-pointer
  (is (= 3.5 (deref-f64 (double-array [3.5])))))

(deftest writes-through-a-mutable-pointer
  (let [cell (long-array [0])]
    (is (nil? (store-42! cell)))
    (is (= 42 (aget cell 0)))))

(deftest a-single-item-pointer-needs-a-one-element-array
  (testing "a wrong-sized array is a clear diagnostic, not a silent overread"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"one-element"
                          (store-42! (long-array [1 2]))))))

;; --- Many-item pointers -------------------------------------------------

(defnz sum-many
  [p [:manyptr :const :i32]
   n :usize
   :ret :i32]
  "var total: i32 = 0;
   var i: usize = 0;
   while (i < n) : (i += 1) {
       total += p[i];
   }
   return total;")

(defnz inc-many!
  [p [:manyptr :i32]
   n :usize
   :ret :void]
  "var i: usize = 0;
   while (i < n) : (i += 1) {
       p[i] += 1;
   }")

(deftest reads-a-const-many-item-pointer
  (is (= 60 (sum-many (int-array [10 20 30]) 3))))

(deftest mutates-through-a-many-item-pointer
  (let [xs (int-array [1 2 3])]
    (is (nil? (inc-many! xs 3)))
    (is (= [2 3 4] (vec xs)))))

;; --- Generated source ---------------------------------------------------

(deftest generates-pointer-types
  (testing "single-item pointers"
    (is (str/includes? (zig/generated-source #'deref-f64) "p: *const f64"))
    (is (str/includes? (zig/generated-source #'store-42!) "p: *i64")))
  (testing "many-item pointers"
    (is (str/includes? (zig/generated-source #'sum-many) "p: [*]const i32"))
    (is (str/includes? (zig/generated-source #'inc-many!) "p: [*]i32"))))
