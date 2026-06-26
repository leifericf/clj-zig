(ns clj-zig.string-e2e-prop-test
  "End-to-end :string crossing. A `defnz` taking a `:string` and returning
  a `:string` hands back a Clojure `String` on the common path, not a byte
  array the caller decodes (guardrail #1). Random valid UTF-8 round-trips
  through the boundary as a String; an empty string round-trips to an empty
  String without dereferencing the pointer; invalid UTF-8 decodes with the
  JVM's replacement action and never throws across the boundary. Needs a
  real `zig` and JDK 22+."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clj-zig.fixtures :as f])
  (:import (java.nio.charset StandardCharsets)))

(def gen-utf8-string
  "A Java String over random non-surrogate BMP code points, so its UTF-8
  encoding round-trips losslessly through encode then decode. Spans one-,
  two-, and three-byte UTF-8 sequences to exercise the multi-byte path."
  (gen/fmap (fn [cps] (->> cps (map char) (apply str)))
            (gen/vector (gen/one-of [(gen/choose 0x20 0x7E)     ; ASCII printable (1-byte)
                                     (gen/choose 0xA1 0x3FF)     ; Latin to Cyrillic (2-byte)
                                     (gen/choose 0x500 0xD7FF)   ; Hebrew to pre-surrogate (3-byte)
                                     (gen/choose 0xE000 0xF8FF)]) ; private use (3-byte)
                        0 24)))

(deftest string-return-is-ceremony-free
  (testing "the common path returns a String, not a byte array"
    (is (instance? String (f/string-upper "foo")))
    (is (= "FOO" (f/string-upper "foo")))))

(defspec random-utf8-round-trips-as-a-string 200
  (prop/for-all [s gen-utf8-string]
    (let [out (f/string-identity s)]
      (and (instance? String out)
           (= s out)))))

(deftest empty-string-round-trips-without-dereferencing
  (let [out (f/string-identity "")]
    (is (instance? String out))
    (is (= "" out))
    (is (zero? (.length ^String out)))))

(deftest invalid-utf8-decodes-with-replacement-and-never-throws
  (testing "raw invalid bytes fed through a :string arg come back decoded"
    (let [invalid (byte-array (map unchecked-byte [0xFF 0xFE 0xFD])) ; no valid lead bytes
          out     (f/string-identity invalid)]
      (is (instance? String out) "a String, never a thrown exception")
      ;; The boundary decode must match the JVM's own replacement decode of
      ;; the same bytes, so a malformed sequence becomes U+FFFD, not a fault.
      (is (= (String. invalid StandardCharsets/UTF_8) out))
      (is (some #(= \uFFFD %) out) "the replacement char is present"))))

(deftest a-byte-array-argument-is-accepted-as-raw-utf8
  (testing "a byte[] of multi-byte UTF-8 echoes back as the same String"
    (let [bs (.getBytes "héllo-世界" StandardCharsets/UTF_8)]
      (is (= "héllo-世界" (f/string-identity bs)))))
  (testing "an ASCII byte[] uppercases exactly like the String path"
    (let [bs (.getBytes "hello" StandardCharsets/UTF_8)]
      (is (= "HELLO" (f/string-upper bs))))))
