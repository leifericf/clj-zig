(ns clj-zig.smoke-test
  "Confirms the test harness runs. Real coverage arrives with each
  namespace; this only proves `clojure -M:test` is wired up."
  (:require [clojure.test :refer [deftest is]]))

(deftest harness-runs
  (is (= 4 (+ 2 2))))
