(ns zigar.stateful-smoke-test
  "Confirms the model-based testing engine is wired in. A tiny counter
  model proves a stateful-check specification runs green under the test
  alias; the real define-and-redefine lifecycle model builds on this shape."
  (:require [clojure.test :refer [deftest is]]
            [stateful-check.core :refer [specification-correct?]]))

(def ^:private counter (atom 0))

(def counter-spec
  {:commands
   {:inc  {:command       (fn [] (swap! counter inc))
           :next-state    (fn [state _ _] (inc state))
           :postcondition (fn [_prev next _args result] (= result next))}
    :read {:command       (fn [] @counter)
           :postcondition (fn [_prev next _args result] (= result next))}}
   :setup         (fn [] (reset! counter 0))
   :initial-state (fn [_setup] 0)})

(deftest counter-model-holds
  (is (specification-correct? counter-spec
                              {:gen {:max-length 12} :run {:num-tests 60}})))
