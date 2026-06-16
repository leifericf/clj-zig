(ns zigar.rejection-matrix-test
  "Negative-space enumeration: every malformed contract must be rejected
  with its own `:error/code`. The error code is the oracle. Where the
  example tests pin one rejection apiece, this walks the rejection arms of
  `signature/normalize`, `type/normalize`, `spec/validate!`, and the layout
  describers as a table, so a new branch without its own code is caught."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [zigar.gen :as g]
            [zigar.layout :as layout]
            [zigar.spec :as spec]))

(defn- code-from
  "Run `thunk` and return the `:error/code` of the diagnostic it throws, or
  nil when it does not throw."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:error/code (ex-data e)))))

(defn- build-code [case]
  (code-from #(spec/build-spec case)))

;; --- Rejections that surface through build-spec -------------------------

(def spec-rejections
  "Each row is a `build-spec` input paired with the code it must raise.
  `build-spec` runs signature normalization, type normalization, and
  contract validation, so all three layers' rejections show up here."
  [;; signature shape
   {:code :zigar/invalid-signature  :signature '(a :i64 :ret :i64)}
   {:code :zigar/reserved-rest-arg  :signature '[a :i64 & b :ret :i64]}
   {:code :zigar/missing-ret        :signature '[a :i64]}
   {:code :zigar/extra-ret          :signature '[a :i64 :ret :i64 :ret :i64]}
   {:code :zigar/empty-ret          :signature '[a :i64 :ret]}
   {:code :zigar/misplaced-ret      :signature '[:ret :i64 a :i64]}
   {:code :zigar/uneven-signature   :signature '[a :i64 b :ret :i64]}
   {:code :zigar/invalid-binding    :signature '[42 :i64 :ret :i64]}
   ;; type shape
   {:code :zigar/unknown-type       :signature '[a 42 :ret :i64]}
   {:code :zigar/unknown-scalar     :signature '[a :bogus :ret :i64]}
   {:code :zigar/malformed-compound :signature '[a [:slice] :ret :i64]}
   {:code :zigar/malformed-compound :signature '[a [:bogus :u8] :ret :i64]}
   {:code :zigar/malformed-compound :signature '[a [:array -1 :u8] :ret :i64]}
   ;; contract validation, argument position
   {:code :zigar/void-argument          :signature '[a :void :ret :i64]}
   {:code :zigar/unsupported-optional   :signature '[a [:optional :i64] :ret :i64]}
   {:code :zigar/unsupported-error-union :signature '[a [:error-union E :i64] :ret :i64]}
   {:code :zigar/unsupported-ownership  :signature '[a [:owned [:slice :u8]] :ret :i64]}
   {:code :zigar/unsupported-handle     :signature '[a [:handle :i64] :ret :i64]}
   {:code :zigar/unsupported-carrier    :signature '[a :u128 :ret :i64]}
   {:code :zigar/unknown-field          :signature '[{x :x} {:y :i64} :ret :i64]}
   ;; contract validation, return position
   {:code :zigar/unsupported-optional    :signature '[:ret [:optional [:manyptr :i64]]]}
   {:code :zigar/unsupported-error-union :signature '[:ret [:error-union E [:slice :i64]]]}
   {:code :zigar/unsupported-ownership   :signature '[:ret [:owned :i64]]}
   {:code :zigar/unsupported-handle      :signature '[:ret [:handle :i64]]}
   {:code :zigar/unsupported-carrier     :signature '[:ret :i128]}
   ;; named type the registry does not declare
   {:code :zigar/unknown-type-name       :signature '[a Point :ret :i64]}])

(deftest spec-rejection-matrix
  (doseq [{:keys [code signature]} spec-rejections]
    (testing (pr-str signature)
      (is (= code (build-code {:ns 'zigar.matrix :name 'f :signature signature}))))))

(deftest spec-rejection-codes-are-distinct-where-expected
  ;; Every documented rejection arm appears in the table.
  (is (= #{:zigar/invalid-signature :zigar/reserved-rest-arg :zigar/missing-ret
           :zigar/extra-ret :zigar/empty-ret :zigar/misplaced-ret
           :zigar/uneven-signature :zigar/invalid-binding :zigar/unknown-type
           :zigar/unknown-scalar :zigar/malformed-compound :zigar/void-argument
           :zigar/unsupported-optional :zigar/unsupported-error-union
           :zigar/unsupported-ownership :zigar/unsupported-handle
           :zigar/unsupported-carrier :zigar/unknown-field
           :zigar/unknown-type-name}
         (set (map :code spec-rejections)))))

;; --- Rejections from the layout describers ------------------------------

(deftest layout-rejection-matrix
  (is (= :zigar/malformed-fields    (code-from #(layout/describe 'T '[a :i64 b]))))
  (is (= :zigar/unsupported-field   (code-from #(layout/describe 'T '[a [:slice :u8]]))))
  (is (= :zigar/unsupported-field   (code-from #(layout/describe 'T '[a :u128]))))
  (is (= :zigar/malformed-members   (code-from #(layout/describe-enum 'E '[ok 0 bad]))))
  (is (= :zigar/non-integer-member  (code-from #(layout/describe-enum 'E '[ok :zero])))))

;; --- Generative breadth: junk in argument position is rejected ----------

(defspec junk-argument-types-are-rejected 200
  (prop/for-all [junk g/gen-junk-form]
    (some? (build-code {:ns 'zigar.matrix :name 'f
                        :signature ['a junk :ret :i64]}))))
