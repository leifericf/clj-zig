(ns zigar.marshalling-test
  "Unit checks for scalar marshalling helpers that need no compile."
  (:require [clojure.test :refer [deftest is]]
            [zigar.ffm :as ffm])
  (:import (java.lang.foreign Arena)))

(defn- f32-round-trip [v]
  (let [t {:kind :scalar :name :f32}]
    (with-open [arena (Arena/ofConfined)]
      (let [seg (.allocate arena 8 8)]
        (#'ffm/write-scalar seg t 0 (#'ffm/to-carrier {:type t} v))
        (#'ffm/coerce-scalar t (#'ffm/read-scalar seg t 0))))))

(deftest f32-carries-infinity
  (is (= Double/POSITIVE_INFINITY (f32-round-trip Double/POSITIVE_INFINITY)))
  (is (= Double/NEGATIVE_INFINITY (f32-round-trip Double/NEGATIVE_INFINITY))))
