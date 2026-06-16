(ns zigar.check-smoke-test
  "Confirms the property-testing engine is wired into the test alias.
  Real properties live beside the code they exercise; this only proves
  `defspec` runs under `clojure -M:test`."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(defspec addition-commutes 100
  (prop/for-all [a gen/large-integer
                 b gen/large-integer]
    (= (+ a b) (+ b a))))
