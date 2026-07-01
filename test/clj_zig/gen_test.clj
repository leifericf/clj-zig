(ns clj-zig.gen-test
  "Self-tests for the shared generators: every form they make is the thing
  the property tests assume, so a generator bug never masquerades as a
  library bug. Generated type forms normalize, generated signatures build
  specs, junk forms are rejected, and the structural enumeration is whole."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clj-zig.gen :as g]
            [clj-zig.layout :as layout]
            [clj-zig.spec :as spec]
            [clj-zig.type :as type]))

(defspec generated-type-forms-normalize 200
  (prop/for-all [form (g/gen-type-form)]
    (map? (type/normalize form))))

(defspec generated-signatures-build-specs 200
  (prop/for-all [sig g/gen-signature]
    (let [s (spec/build-spec {:ns 'clj-zig.gen-test :name 'f :signature sig})]
      (and (= sig (:signature s)) (vector? (:params s))))))

(deftest enum-members-generate-at-the-smallest-size
  ;; vector-distinct must find up to six distinct values even when the
  ;; generator size is zero; a size-bounded element generator exhausts there.
  (is (every? vector? (doall (repeatedly 200 #(gen/generate g/gen-enum-members 0))))))

(defspec generated-field-lists-describe 100
  (prop/for-all [fields g/gen-field-list]
    (let [desc (layout/describe 'T fields)]
      (= (quot (count fields) 2) (count (:fields desc)))))) ;; pairs in, fields out

(defspec generated-values-have-the-right-shape 100
  (prop/for-all [[kw v] (gen/bind g/gen-scalar-type
                                  (fn [kw] (gen/tuple (gen/return kw)
                                                      (g/gen-scalar-value kw))))]
    ;; A scalar value is a boolean for :bool, otherwise a number.
    (case (:category (type/scalars kw))
      :bool  (boolean? v)
      (number? v))))

(defspec junk-forms-are-rejected 100
  (prop/for-all [form g/gen-junk-form]
    (try (type/normalize form) false
         (catch clojure.lang.ExceptionInfo e
           (= :error (:level (ex-data e)))))))

(deftest structural-cases-all-build
  (let [cases (g/structural-cases)]
    (is (< 50 (count cases)) "the matrix is more than a handful of cases")
    (doseq [c cases]
      (is (map? (spec/build-spec c))
          (str "case must build a spec: " (:signature c))))))

(defspec module-trees-yield-aligned-stats-and-contents 100
  (prop/for-all [tree g/gen-module-tree]
    (let [stats (g/tree->stats tree)
          conts (g/tree->contents tree)]
      (and (= (count tree) (count stats) (count conts))
           (= (set (keys tree)) (set (map :path stats)) (set (map :path conts)))
           (every? #(and (integer? (:size %)) (integer? (:mtime %))) stats)
           (every? #(string? (:content %)) conts)))))

(deftest carrier-vocabulary-is-sound
  (is (every? type/has-carrier? g/carrier-scalars))
  ;; The 128-bit integers now have carriers (a 16-byte struct of two longs),
  ;; so the generator's primitive-only matrix excludes them; f16/f80/f128
  ;; and the void/noreturn non-values still have no carrier.
  (is (not-any? type/has-carrier? [:f16 :f80 :f128 :void :noreturn]))
  (is (not-any? #(some (partial = %) g/carrier-scalars) [:i128 :u128]))
  (is (seq g/int-carriers))
  (is (seq g/float-carriers)))
