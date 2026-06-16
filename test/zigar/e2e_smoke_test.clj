(ns zigar.e2e-smoke-test
  "Proves the compile-once fixtures all build and call. Loading
  `zigar.fixtures` compiles every native function; this calls each once so
  a broken body or wrapper fails here, before the property suites that lean
  on them. The property suites supply the breadth; this is the gate."
  (:require [clojure.test :refer [deftest is testing]]
            [zigar.fixtures :as f]))

(deftest scalar-echoes-round-trip-a-sample
  (doseq [[kw echo] f/scalar-echo]
    (testing kw
      (is (some? (echo (if (= :bool kw) true 1)))))))

(deftest void-returns-nil
  (is (nil? (f/swallow 7))))

(deftest slice-array-optional-fixtures-work
  (is (= 6.0 (f/sum-f64 (double-array [1.0 2.0 3.0]))))
  (is (= 6 (f/sum-i64 (long-array [1 2 3]))))
  (is (= 10 (f/array4-sum (int-array [1 2 3 4]))))
  (let [xs (double-array [1.0 2.0 3.0])]
    (f/double-all! xs)
    (is (= [2.0 4.0 6.0] (vec xs))))
  (is (= 42 (f/maybe-answer true)))
  (is (nil? (f/maybe-answer false)))
  (is (= 7 (f/deref-or-default (long-array [7]))))
  (is (= -1 (f/deref-or-default nil))))

(deftest struct-record-enum-fixtures-work
  (is (= {:x 1.0 :y 2.0} (f/echo-point {:x 1.0 :y 2.0})))
  (is (= (f/->Vec2 1.0 2.0) (f/echo-vec2 (f/->Vec2 1.0 2.0))))
  (is (instance? zigar.fixtures.Vec2 (f/echo-vec2 (f/->Vec2 1.0 2.0))))
  (is (= :hearts (f/echo-suit :hearts))))

(deftest owned-borrowed-handle-fixtures-work
  (is (= [2.0 4.0] (f/owned-double (double-array [1.0 2.0]))))
  (is (= [101 108 108 111] (f/borrowed-rest (.getBytes "hello" "UTF-8"))))
  (let [b (f/box 99)]
    (is (= 99 (f/unbox b)))
    (f/free-box b)))

(deftest allocation-tracker-balances
  (let [t (f/tracker-new)]
    (is (= 0 (f/tracker-live t)))
    (let [n (f/node-new t 5)]
      (is (= 1 (f/tracker-live t)))
      (is (= 5 (f/node-get n)))
      (f/node-free t n)
      (is (= 0 (f/tracker-live t))))
    (f/tracker-free t)))
