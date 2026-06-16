(ns clj-zig.struct-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :refer [defnz deftypez]]))

(deftypez Point
  [x :f64
   y :f64])

(deftypez Pixel
  [r :u8
   g :u8
   b :u8
   a :u8])

;; --- Struct arguments ---------------------------------------------------

(defnz norm
  [p Point
   :ret :f64]
  "return @sqrt(p.x * p.x + p.y * p.y);")

(defnz alpha
  [px Pixel
   :ret :u8]
  "return px.a;")

(deftest a-struct-argument-is-a-clojure-map
  (testing "a two-field struct of doubles"
    (is (= 5.0 (norm {:x 3.0 :y 4.0}))))
  (testing "a padded struct of bytes reads the right field"
    (is (= 255 (alpha {:r 10 :g 20 :b 30 :a 255})))))

;; --- Struct returns -----------------------------------------------------

(defnz midpoint
  [a Point
   b Point
   :ret Point]
  "return .{ .x = (a.x + b.x) / 2.0, .y = (a.y + b.y) / 2.0 };")

(defnz brighten
  [px Pixel
   :ret Pixel]
  "return .{ .r = px.r +| 10, .g = px.g +| 10, .b = px.b +| 10, .a = px.a };")

(deftest a-struct-return-is-a-clojure-map
  (testing "a struct comes back as a map keyed by field name"
    (is (= {:x 2.0 :y 3.0} (midpoint {:x 0.0 :y 0.0} {:x 4.0 :y 6.0}))))
  (testing "a byte struct round-trips with saturating arithmetic"
    (is (= {:r 20 :g 30 :b 40 :a 250} (brighten {:r 10 :g 20 :b 30 :a 250})))
    (is (= {:r 255 :g 255 :b 255 :a 0} (brighten {:r 250 :g 250 :b 250 :a 0})))))

(deftest struct-values-are-copies
  (testing "a struct argument is passed by value, not mutated"
    (let [p {:x 1.0 :y 2.0}]
      (norm p)
      (is (= {:x 1.0 :y 2.0} p)))))
