(ns zigar.scalar-e2e-prop-test
  "End-to-end scalar round-trips against the compiled echo fixtures. Every
  carrier returns the value it was given across its whole range, including
  unsigned values beyond the signed 64-bit bound, which must come back
  exact and never negative. A void return is nil."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [zigar.fixtures :as f]
            [zigar.gen :as g]
            [zigar.type :as type])
  (:import (java.math BigInteger)))

(def gen-typed-value
  (gen/bind g/gen-scalar-type
            (fn [kw] (gen/tuple (gen/return kw) (g/gen-scalar-value kw)))))

(defspec scalar-echo-round-trips 400
  (prop/for-all [[kw v] gen-typed-value]
    (g/same-scalar? kw v ((f/scalar-echo kw) v))))

(def unsigned-carriers
  (vec (filter type/unsigned-int? g/int-carriers)))

(defspec unsigned-echo-is-exact-and-never-negative 300
  (prop/for-all [[kw v] (gen/bind (gen/elements unsigned-carriers)
                                  (fn [kw] (gen/tuple (gen/return kw)
                                                      (g/gen-scalar-value kw))))]
    (let [r ((f/scalar-echo kw) v)]
      (and (not (neg? r)) (== v r)))))

(defspec large-u64-echo-promotes-to-biginteger 100
  (prop/for-all [v (gen/fmap #(+' (biginteger Long/MAX_VALUE) 1 (Math/abs (long %)))
                             gen/large-integer)]
    ;; A value strictly above the signed range comes back as a BigInteger.
    (let [r (f/echo-u64 v)]
      (and (instance? BigInteger r) (== v r)))))

(deftest void-echo-returns-nil
  (is (nil? (f/swallow 0)))
  (is (nil? (f/swallow Long/MAX_VALUE))))
