(ns clj-zig.marshalling-test
  "Unit checks for scalar marshalling helpers that need no compile."
  (:require [clojure.test :refer [deftest is]]
            [clj-zig.ffm :as ffm])
  (:import (java.lang.foreign Arena MemorySegment ValueLayout)))

(defn- f32-round-trip [v]
  (let [t {:kind :scalar :name :f32}]
    (with-open [arena (Arena/ofConfined)]
      (let [seg (.allocate arena 8 8)]
        (#'ffm/write-scalar seg t 0 (#'ffm/to-carrier {:type t} v))
        (#'ffm/coerce-scalar t (#'ffm/read-scalar seg t 0))))))

(deftest f32-carries-infinity
  (is (= Double/POSITIVE_INFINITY (f32-round-trip Double/POSITIVE_INFINITY)))
  (is (= Double/NEGATIVE_INFINITY (f32-round-trip Double/NEGATIVE_INFINITY))))

(deftest bool-slice-round-trips
  (let [param {:type {:kind :slice :const? false :of {:kind :scalar :name :bool}}}
        arr   (boolean-array [true false true])]
    (with-open [arena (Arena/ofConfined)]
      (let [{:keys [address length]} (#'ffm/marshal-array arena param arr)]
        (is (= [true false true]
               (#'ffm/read-slice-values
                (.address ^MemorySegment address)
                length {:kind :scalar :name :bool})))))))

(deftest bool-slice-copies-back-mutations
  (let [param {:type {:kind :slice :const? false :of {:kind :scalar :name :bool}}}
        arr   (boolean-array [false false false])]
    (with-open [arena (Arena/ofConfined)]
      (let [{:keys [address copy-back]} (#'ffm/marshal-array arena param arr)
            seg ^MemorySegment address]
        (.set seg ValueLayout/JAVA_BOOLEAN 1 true)
        (copy-back)
        (is (= [false true false] (vec arr)))))))
