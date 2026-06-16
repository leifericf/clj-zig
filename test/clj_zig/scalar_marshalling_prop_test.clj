(ns clj-zig.scalar-marshalling-prop-test
  "Properties of scalar marshalling, with a scratch arena and no compile.
  Writing a coerced carrier into native memory and reading it back yields
  the original value for every carrier type at its boundary values. The
  unsigned path is checked against a BigInteger oracle: coercing a carrier
  is the same as taking the value modulo two to the bit width, so an
  unsigned value beyond the signed range never comes back negative."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clj-zig.ffm :as ffm]
            [clj-zig.gen :as g]
            [clj-zig.type :as type])
  (:import (java.lang.foreign Arena)))

(defn- pow2 [n] (bigint (.shiftLeft (biginteger 1) (int n))))

(def unsigned-carriers
  (vec (filter type/unsigned-int? g/int-carriers)))

(defn- to-carrier [kw v]
  (#'ffm/to-carrier {:type {:kind :scalar :name kw}} v))

(defn- coerce [kw raw]
  (#'ffm/coerce-scalar {:kind :scalar :name kw} raw))

(defn- through-memory
  "Coerce `v` to `kw`'s carrier, write it to a fresh segment, read it back,
  and coerce to Clojure: the full marshalling round trip."
  [kw v]
  (let [t {:kind :scalar :name kw}]
    (with-open [arena (Arena/ofConfined)]
      (let [seg (.allocate arena 8 8)]
        (#'ffm/write-scalar seg t 0 (to-carrier kw v))
        (coerce kw (#'ffm/read-scalar seg t 0))))))

(defn- same-value? [kw v rt]
  (case (:category (type/scalars kw))
    :bool  (= (boolean v) rt)
    :float (if (Double/isNaN (double v)) (Double/isNaN (double rt)) (== v rt))
    (== v rt)))

(def gen-typed-value
  (gen/bind g/gen-scalar-type
            (fn [kw] (gen/tuple (gen/return kw) (g/gen-scalar-value kw)))))

(defspec scalar-round-trips-through-memory 400
  (prop/for-all [[kw v] gen-typed-value]
    (same-value? kw v (through-memory kw v))))

(defspec unsigned-round-trips-are-never-negative 300
  (prop/for-all [[kw v] (gen/bind (gen/elements unsigned-carriers)
                                  (fn [kw] (gen/tuple (gen/return kw)
                                                      (g/gen-scalar-value kw))))]
    (let [rt (through-memory kw v)]
      (and (not (neg? rt)) (== v rt)))))

(def gen-big-integer
  "Integers that range well past the signed and unsigned 64-bit bounds in
  both directions, to stress the two's-complement wrap."
  (gen/fmap (fn [[a b]] (+' (*' a 4294967296) b))
            (gen/tuple gen/large-integer gen/large-integer)))

(defspec unsigned-coercion-matches-the-modulo-oracle 400
  (prop/for-all [kw (gen/elements unsigned-carriers)
                 v  gen-big-integer]
    ;; Coercing the carrier equals the value taken modulo two to the width.
    (let [bits (:bits (type/scalars kw))]
      (== (mod v (pow2 bits)) (coerce kw (to-carrier kw v))))))
