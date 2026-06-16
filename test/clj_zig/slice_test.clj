(ns clj-zig.slice-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig :as zig]
            [clj-zig.core :refer [defnz]]))

(defnz sum
  [xs [:slice :const :f64]
   :ret :f64]
  "var total: f64 = 0;
   for (xs) |x| {
       total += x;
   }
   return total;")

(defnz double-all!
  [xs [:slice :u8]
   :ret :void]
  "for (xs) |*e| {
       e.* *= 2;
   }")

(deftest reads-a-const-slice
  (testing "a const slice is passed as a read-only view and summed"
    (is (= 6.0 (sum (double-array [1.0 2.0 3.0]))))
    (is (= 0.0 (sum (double-array [])))))
  (testing "the input array is untouched by a const slice"
    (let [xs (double-array [1.0 2.0 3.0])]
      (sum xs)
      (is (= [1.0 2.0 3.0] (vec xs))))))

(deftest mutates-a-mutable-slice-in-place
  (testing "a mutable slice may be mutated during the call and is copied back"
    (let [xs (byte-array [1 2 3])]
      (is (nil? (double-all! xs)))
      (is (= [2 4 6] (vec xs)))))
  (testing "an empty mutable slice is a no-op"
    (let [xs (byte-array [])]
      (is (nil? (double-all! xs)))
      (is (= [] (vec xs))))))

(deftest generates-a-pointer-plus-length-wrapper
  (let [src (zig/generated-source #'sum)]
    (testing "the slice becomes a many-item pointer and a usize length"
      (is (str/includes? src "xs_ptr: [*]const f64"))
      (is (str/includes? src "xs_len: usize")))
    (testing "the slice is reconstructed before the body runs"
      (is (str/includes? src "const xs = xs_ptr[0..xs_len];"))))
  (testing "a mutable slice drops the const qualifier"
    (is (str/includes? (zig/generated-source #'double-all!) "xs_ptr: [*]u8"))))
