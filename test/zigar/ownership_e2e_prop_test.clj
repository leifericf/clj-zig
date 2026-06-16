(ns zigar.ownership-e2e-prop-test
  "End-to-end round-trips for ownership and handles against the compiled
  fixtures. An owned slice return copies into a vector that matches the
  oracle; a borrowed view copies the tail; a handle threads a live value
  across calls and is rejected when its tag does not match."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [zigar.fixtures :as f]
            [zigar.gen :as g]))

(defn- code-from [thunk]
  (try (thunk) nil (catch clojure.lang.ExceptionInfo e (:error/code (ex-data e)))))

(def gen-finite-doubles
  (gen/vector (gen/double* {:NaN? false}) 0 12))

(defspec owned-slice-copies-the-doubled-values 300
  (prop/for-all [v gen-finite-doubles]
    (let [arr (double-array v)]
      (= (mapv #(* 2.0 %) (vec arr)) (f/owned-double arr)))))

(defspec borrowed-slice-copies-the-tail 300
  (prop/for-all [v (gen/vector (g/gen-scalar-value :u8) 0 12)]
    (let [arr (g/primitive-array :u8 v)
          u8s (mapv #(bit-and (long %) 0xff) (vec arr))]
      (= (vec (rest u8s)) (f/borrowed-rest arr)))))

(defspec handle-threads-a-value-across-calls 300
  (prop/for-all [v (g/gen-scalar-value :i64)]
    (let [b (f/box v)]
      (try (== v (f/unbox b))
           (finally (f/free-box b))))))

(deftest a-wrong-handle-tag-is-rejected
  (let [t (f/tracker-new)]
    (testing "unboxing a Tracker handle where a Box is expected is rejected"
      (is (= :zigar/handle-type-mismatch (code-from #(f/unbox t)))))
    (f/tracker-free t)))

(deftest an-empty-borrowed-view-is-an-empty-vector
  (is (= [] (f/borrowed-rest (byte-array [])))))
