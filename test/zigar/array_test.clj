(ns zigar.array-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zigar :as zig]
            [zigar.core :refer [defnz]]))

(defnz sum4
  [a [:array 4 :f32]
   :ret :f32]
  "var total: f32 = 0;
   for (a) |x| {
       total += x;
   }
   return total;")

(defnz length-of
  [a [:array 4 :i32]
   :ret :usize]
  "return a.len;")

(defnz dot3
  [a [:array 3 :f64]
   b [:array 3 :f64]
   :ret :f64]
  "var total: f64 = 0;
   for (a, b) |x, y| {
       total += x * y;
   }
   return total;")

(deftest reads-a-fixed-size-array
  (is (= 10.0 (sum4 (float-array [1.0 2.0 3.0 4.0]))))
  (testing "the comptime length is available as a.len"
    (is (= 4 (length-of (int-array [9 9 9 9])))))
  (testing "two array arguments cross independently"
    (is (= 32.0 (dot3 (double-array [1.0 2.0 3.0]) (double-array [4.0 5.0 6.0]))))))

(deftest a-fixed-size-array-is-a-read-only-value
  (testing "the caller's array is never written back"
    (let [a (float-array [1.0 2.0 3.0 4.0])]
      (sum4 a)
      (is (= [1.0 2.0 3.0 4.0] (vec a))))))

(deftest the-array-length-must-match-the-declared-size
  (testing "a wrong-length array is a clear diagnostic"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"length 4"
                          (sum4 (float-array [1.0 2.0 3.0]))))))

(deftest generates-a-pointer-to-array-wrapper
  (let [src (zig/generated-source #'sum4)]
    (testing "the array crosses as a pointer to a fixed-size array"
      (is (str/includes? src "a_ptr: *const [4]f32")))
    (testing "the array value is reconstructed before the body runs"
      (is (str/includes? src "const a = a_ptr.*;")))))
