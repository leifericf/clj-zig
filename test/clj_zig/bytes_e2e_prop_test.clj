(ns clj-zig.bytes-e2e-prop-test
  "End-to-end byte-buffer return. A `defnz` returning `[:bytes [:slice :u8]]`
  copies its input slice and hands back a Java `byte[]` rather than a boxed
  Clojure vector, so a multi-megabyte buffer crosses as one array and one
  free, not a million boxed elements. Random buffers, including a large one,
  round-trip identically. Needs a real `zig` and JDK 22+."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clj-zig.fixtures :as f]))

(defspec bytes-echo-round-trips-any-buffer-as-a-byte-array 100
  (prop/for-all [bs (gen/vector (gen/choose -128 127) 0 64)]
    (let [in  (byte-array bs)
          out (f/bytes-echo in)]
      (and (bytes? out)
           (= (count bs) (alength ^bytes out))
           (java.util.Arrays/equals ^bytes out in)))))

(deftest an-empty-buffer-round-trips-to-an-empty-array
  (let [out (f/bytes-echo (byte-array 0))]
    (is (bytes? out))
    (is (zero? (alength ^bytes out)))))

(deftest a-large-buffer-round-trips-as-a-byte-array
  (testing "a one-megabyte buffer returns identical, as a byte[] not a vector"
    (let [n   (* 1024 1024)
          in  (byte-array (map unchecked-byte (range n)))
          out (f/bytes-echo in)]
      (is (bytes? out))
      (is (= n (alength ^bytes out)))
      (is (java.util.Arrays/equals ^bytes out in)))))
