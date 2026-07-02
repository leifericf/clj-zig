(ns clj-zig.result-record-e2e-test
  "End-to-end round-trips for owned and borrowed result records (ADR 21
  Phase 3). A `defnz` returning `[:owned RecordType]` hands back a
  Clojure map (or a record, for a `defrecordz`) with every field decoded:
  scalars as numbers, an enum as a keyword, a `:string` as a `String`, and
  a `[:bytes [:slice :u8]]` field as a `byte[]`. Empty buffer fields
  marshal without dereferencing the pointer, and an owned result driven in
  volume leaks nothing. Needs a real `zig` and JDK 22+."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.fixtures :as f])
  (:import (java.util Arrays)))

(deftest owned-result-round-trips-every-field-kind
  (let [r (f/render-fixed)]
    (testing "a scalar field decodes as a number"
      (is (= 800 (:width r)))
      (is (= 600 (:height r))))
    (testing "an enum field decodes as its member keyword"
      (is (= :ok (:status r))))
    (testing "a :string field decodes as a String"
      (is (instance? String (:media r)))
      (is (= "image/png" (:media r))))
    (testing "a :bytes field decodes as a byte[]"
      (is (bytes? (:bytes r)))
      (is (Arrays/equals ^bytes (:bytes r) (byte-array [65 66 67]))))
    (testing "the result is a plain map, not a record (deftypez)"
      (is (map? r))
      (is (not (record? r))))))

(deftest owned-result-echo-round-trips-arbitrary-inputs
  (let [media  "héllo-世界"
        data   (byte-array [10 20 30 40])
        r      (f/render-echo media data)]
    (is (= :no_output (:status r)))
    (is (= 13 (:width r)) "the media length crosses as its UTF-8 byte count")
    (is (= 4 (:height r)) "the payload length crosses as a u32")
    (is (= media (:media r)))
    (is (Arrays/equals ^bytes (:bytes r) data))))

(deftest empty-buffer-fields-marshal-without-dereferencing
  ;; Every buffer field is zero-length; the read copies nothing and returns
  ;; an empty String and an empty byte[] without ever touching the pointer
  ;; (the single-slice guard, generalized to a record).
  (let [r (f/render-empty)]
    (is (= :invalid (:status r)))
    (is (= "" (:media r)))
    (is (zero? (.length ^String (:media r))))
    (is (= 0 (alength ^bytes (:bytes r))))))

(deftest scalar-only-record-is-owned-uniformly
  ;; The ownership relaxation is uniform: [:owned RecordType] works for a
  ;; scalar-only record too, not only a buffer-carrying one. The free shim
  ;; is empty (no buffers to free) but still present.
  (let [p (f/render-pixel)]
    (is (= {:r 10 :g 20 :b 30} p))))

(deftest borrowed-result-copies-without-freeing
  ;; A borrowed result reads exactly like an owned one but emits no free
  ;; shim; the bytes come from static storage the body keeps alive.
  (let [r (f/render-borrowed-static)]
    (is (= :ok (:status r)))
    (is (= 1 (:width r)))
    (is (= 2 (:height r)))
    (is (= "hello" (:media r)))
    (is (= 0 (alength ^bytes (:bytes r))))))

(deftest defrecordz-result-rebuilds-via-map-factory
  ;; A defrecordz result rebuilds as a record via its map-> factory, so the
  ;; Clojure value is a TaggedCount, not a plain map. A record is not equal
  ;; to a plain map of the same fields.
  (let [r (f/render-tagged-count "alpha")]
    (is (instance? clj_zig.fixtures.TaggedCount r))
    (is (= "alpha" (:tag r)))
    (is (= 5 (:n r)))
    (is (not= {:tag "alpha" :n 5} r) "a record is not a plain map")))
