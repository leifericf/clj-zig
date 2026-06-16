(ns clj-zig.record-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig :as zig]
            [clj-zig.core :refer [defnz defrecordz]]))

(defrecordz Point
  "A 2D point shared between Clojure and Zig."
  [x :f64
   y :f64])

;; --- The Clojure record -------------------------------------------------

(deftest defrecordz-defines-an-ordinary-record
  (testing "the positional and map factories build a record"
    (is (instance? Point (->Point 1.0 2.0)))
    (is (= 1.0 (:x (->Point 1.0 2.0))))
    (is (= (->Point 1.0 2.0) (map->Point {:x 1.0 :y 2.0}))))
  (testing "the docstring is carried for inspection"
    (is (= "A 2D point shared between Clojure and Zig."
           (:doc (meta #'map->Point))))))

;; --- A record as an argument --------------------------------------------

(defnz norm
  [p Point
   :ret :f64]
  "return @sqrt(p.x * p.x + p.y * p.y);")

(deftest a-record-argument-marshals-by-field
  (testing "a record instance crosses as its struct fields"
    (is (= 5.0 (norm (->Point 3.0 4.0)))))
  (testing "a plain map with the same fields also crosses"
    (is (= 5.0 (norm {:x 3.0 :y 4.0})))))

;; --- A record as a return -----------------------------------------------

(defnz midpoint
  [a Point
   b Point
   :ret Point]
  "return .{ .x = (a.x + b.x) / 2.0, .y = (a.y + b.y) / 2.0 };")

(deftest a-record-return-is-a-record-not-a-map
  (testing "a Point return comes back as a Point record"
    (let [m (midpoint (->Point 0.0 0.0) (->Point 4.0 6.0))]
      (is (instance? Point m))
      (is (= (->Point 2.0 3.0) m))
      (testing "and a record is not equal to a plain map of the same fields"
        (is (not= {:x 2.0 :y 3.0} m))))))

(deftest the-record-struct-appears-in-the-generated-preamble
  (is (str/includes? (zig/generated-source #'norm)
                     "const Point = extern struct {")))
