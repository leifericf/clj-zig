(ns zigar.struct-e2e-prop-test
  "End-to-end round-trips for named types against the compiled fixtures. A
  struct argument echoes back to an equal map, a record echoes back to an
  equal record (not a plain map), and an enum member echoes back to its
  keyword."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [zigar.fixtures :as f]))

(def gen-finite (gen/double* {:NaN? false}))

(defspec struct-echoes-to-an-equal-map 300
  (prop/for-all [m (gen/hash-map :x gen-finite :y gen-finite)]
    (= m (f/echo-point m))))

(defspec record-echoes-to-an-equal-record 300
  (prop/for-all [[x y] (gen/tuple gen-finite gen-finite)]
    (let [v (f/->Vec2 x y)
          r (f/echo-vec2 v)]
      (and (= v r) (instance? zigar.fixtures.Vec2 r)))))

(defspec enum-member-echoes-to-its-keyword 200
  (prop/for-all [s (gen/elements f/suit-members)]
    (= s (f/echo-suit s))))
