(ns zigar.layout-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zigar.layout :as layout]))

(deftest describes-fields-in-order-with-offsets
  (let [d (layout/describe 'Point '[x :f64 y :f64])]
    (is (= 'Point (:name d)))
    (is (= [{:name 'x :type {:kind :scalar :name :f64} :offset 0}
            {:name 'y :type {:kind :scalar :name :f64} :offset 8}]
           (:fields d)))
    (is (= 16 (:size d)))
    (is (= 8 (:align d)))))

(deftest pads-mixed-fields-to-c-alignment
  (let [d (layout/describe 'Mix '[a :u8 b :i32])]
    (testing "the i32 starts at the next aligned offset"
      (is (= [0 4] (mapv :offset (:fields d)))))
    (testing "the struct takes its widest field's alignment and rounds up"
      (is (= 4 (:align d)))
      (is (= 8 (:size d))))))

(deftest rejects-non-scalar-fields
  (testing "a field without an FFM carrier is a clear diagnostic"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"carrier"
                          (layout/describe 'Bad '[p [:slice :u8]])))))

(deftest emits-an-extern-struct
  (let [src (layout/zig-struct (layout/describe 'Point '[x :f64 y :f64]))]
    (is (str/includes? src "const Point = extern struct {"))
    (is (str/includes? src "x: f64,"))
    (is (str/includes? src "y: f64,"))))
