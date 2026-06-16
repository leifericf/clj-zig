(ns zigar.structural-e2e-test
  "Drive the structural matrix all the way through compile and call. Every
  argument shape, over a representative scalar per category, crosses into a
  consuming wrapper that returns zero; every cleanly producible return shape
  is built and read back against its oracle. The content-addressed cache
  means each distinct shape compiles once and is reused. Borrowed,
  handle, and named-type round-trips are driven by their own end-to-end
  suites and are logged here rather than repeated."
  (:require [clojure.test :refer [deftest is]]
            [zigar :as zig]
            [zigar.core :as core]
            [zigar.gen :as g]
            [zigar.spec :as spec]
            [zigar.type :as type]))

(def matrix-ns 'zigar.e2e-matrix)

;; A real error set so the error-union wrappers compile in this namespace.
(core/register-decl! matrix-ns 'Error "const Error = error{Boom};")

(def matrix-scalars
  "A representative scalar per category: signed int, unsigned past the
  signed range, float, and bool. The per-carrier sweep lives in the scalar
  end-to-end suite; here the axis of interest is shape."
  [:i32 :u64 :f64 :bool])

(defn- arg-value
  "A representative Clojure value for an argument type form."
  [af]
  (if (keyword? af)
    (if (= :bool af) true 1)
    (case (first af)
      (:slice :manyptr) (g/primitive-array (last af) [1 2 3])
      :ptr              (g/primitive-array (last af) [1])
      :array            (g/primitive-array (last af) (vec (repeat (second af) 1)))
      :optional         (g/primitive-array (last (second af)) [1]))))

(defn- callable [name signature body]
  (zig/fn (spec/build-spec {:ns matrix-ns :name name :signature signature}) body))

(defn- producer-body [ret]
  (cond
    (type/void-type? ret)      "return;"
    (= :scalar (:kind ret))    (if (= :bool (:name ret)) "return false;" "return 0;")
    (= :optional (:kind ret))  "return null;"
    (= :owned (:kind ret))     (str "return std.heap.c_allocator.alloc("
                                    (name (-> ret :of :of :name))
                                    ", 0) catch @panic(\"oom\");")
    (= :error-union (:kind ret)) (let [v (:of ret)]
                                   (cond (type/void-type? v) "return;"
                                         (= :bool (:name v))  "return false;"
                                         :else                "return 0;"))))

(defn- producer-oracle [ret]
  (cond
    (type/void-type? ret)      nil
    (= :scalar (:kind ret))    (if (= :bool (:name ret)) false 0)
    (= :optional (:kind ret))  nil
    (= :owned (:kind ret))     []
    (= :error-union (:kind ret)) (let [v (:of ret)]
                                   (cond (type/void-type? v) nil
                                         (= :bool (:name v))  false
                                         :else                0))))

(defn- result-ok? [oracle result]
  (cond (nil? oracle)     (nil? result)
        (vector? oracle)  (= oracle result)
        (boolean? oracle) (= oracle result)
        :else             (== oracle result)))

(deftest argument-shape-matrix-crosses-and-returns
  (let [cases (for [e matrix-scalars af (g/arg-forms e)] [e af])]
    (doseq [[_ af] cases]
      (let [f (callable 'consume ['a af :ret :i64] "_ = a; return 0;")]
        (is (zero? (f (arg-value af))) (str "argument shape " (pr-str af)))))
    (println "structural-e2e: drove" (count cases) "argument-shape cases")))

(deftest return-shape-matrix-produces-and-reads
  (let [forms   (for [e matrix-scalars rf (g/ret-forms e)] rf)
        driven  (remove #(and (vector? %) (= :borrowed (first %))) forms)
        skipped (filter #(and (vector? %) (= :borrowed (first %))) forms)]
    (doseq [rf driven]
      (let [s   (spec/build-spec {:ns matrix-ns :name 'produce :signature [:ret rf]})
            ret (:ret s)
            f   (zig/fn s (producer-body ret))]
        (is (result-ok? (producer-oracle ret) (f)) (str "return shape " (pr-str rf)))))
    (println "structural-e2e: drove" (count driven) "return-shape cases; skipped"
             (count skipped) "borrowed (covered by ownership-e2e), plus named-type"
             "and handle returns (covered by struct-e2e and ownership-e2e)")))
