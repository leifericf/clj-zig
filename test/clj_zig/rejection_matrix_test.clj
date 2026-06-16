(ns clj-zig.rejection-matrix-test
  "Negative-space enumeration: every malformed contract must be rejected
  with its own `:error/code`. The error code is the oracle. Where the
  example tests pin one rejection apiece, this walks the rejection arms of
  `signature/normalize`, `type/normalize`, `spec/validate!`, and the layout
  describers as a table, so a new branch without its own code is caught."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clj-zig.gen :as g]
            [clj-zig.layout :as layout]
            [clj-zig.spec :as spec]))

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
   {:code :clj-zig/invalid-signature  :signature '(a :i64 :ret :i64)}
   {:code :clj-zig/reserved-rest-arg  :signature '[a :i64 & b :ret :i64]}
   {:code :clj-zig/missing-ret        :signature '[a :i64]}
   {:code :clj-zig/extra-ret          :signature '[a :i64 :ret :i64 :ret :i64]}
   {:code :clj-zig/empty-ret          :signature '[a :i64 :ret]}
   {:code :clj-zig/misplaced-ret      :signature '[:ret :i64 a :i64]}
   {:code :clj-zig/uneven-signature   :signature '[a :i64 b :ret :i64]}
   {:code :clj-zig/invalid-binding    :signature '[42 :i64 :ret :i64]}
   ;; type shape
   {:code :clj-zig/unknown-type       :signature '[a 42 :ret :i64]}
   {:code :clj-zig/unknown-scalar     :signature '[a :bogus :ret :i64]}
   {:code :clj-zig/malformed-compound :signature '[a [:slice] :ret :i64]}
   {:code :clj-zig/malformed-compound :signature '[a [:bogus :u8] :ret :i64]}
   {:code :clj-zig/malformed-compound :signature '[a [:array -1 :u8] :ret :i64]}
   ;; contract validation, argument position
   {:code :clj-zig/void-argument          :signature '[a :void :ret :i64]}
   {:code :clj-zig/unsupported-optional   :signature '[a [:optional :i64] :ret :i64]}
   {:code :clj-zig/unsupported-error-union :signature '[a [:error-union E :i64] :ret :i64]}
   {:code :clj-zig/unsupported-ownership  :signature '[a [:owned [:slice :u8]] :ret :i64]}
   {:code :clj-zig/unsupported-handle     :signature '[a [:handle :i64] :ret :i64]}
   {:code :clj-zig/unsupported-carrier    :signature '[a :u128 :ret :i64]}
   {:code :clj-zig/unknown-field          :signature '[{x :x} {:y :i64} :ret :i64]}
   ;; contract validation, return position
   {:code :clj-zig/unsupported-optional    :signature '[:ret [:optional [:manyptr :i64]]]}
   {:code :clj-zig/unsupported-error-union :signature '[:ret [:error-union E [:slice :i64]]]}
   {:code :clj-zig/unsupported-ownership   :signature '[:ret [:owned :i64]]}
   {:code :clj-zig/unsupported-handle      :signature '[:ret [:handle :i64]]}
   {:code :clj-zig/unsupported-carrier     :signature '[:ret :i128]}
   ;; named type the registry does not declare
   {:code :clj-zig/unknown-type-name       :signature '[a Point :ret :i64]}])

(deftest spec-rejection-matrix
  (doseq [{:keys [code signature]} spec-rejections]
    (testing (pr-str signature)
      (is (= code (build-code {:ns 'clj-zig.matrix :name 'f :signature signature}))))))

(deftest spec-rejection-codes-are-distinct-where-expected
  ;; Every documented rejection arm appears in the table.
  (is (= #{:clj-zig/invalid-signature :clj-zig/reserved-rest-arg :clj-zig/missing-ret
           :clj-zig/extra-ret :clj-zig/empty-ret :clj-zig/misplaced-ret
           :clj-zig/uneven-signature :clj-zig/invalid-binding :clj-zig/unknown-type
           :clj-zig/unknown-scalar :clj-zig/malformed-compound :clj-zig/void-argument
           :clj-zig/unsupported-optional :clj-zig/unsupported-error-union
           :clj-zig/unsupported-ownership :clj-zig/unsupported-handle
           :clj-zig/unsupported-carrier :clj-zig/unknown-field
           :clj-zig/unknown-type-name}
         (set (map :code spec-rejections)))))

;; --- Rejections from the layout describers ------------------------------

(deftest layout-rejection-matrix
  (is (= :clj-zig/malformed-fields    (code-from #(layout/describe 'T '[a :i64 b]))))
  (is (= :clj-zig/unsupported-field   (code-from #(layout/describe 'T '[a [:slice :u8]]))))
  (is (= :clj-zig/unsupported-field   (code-from #(layout/describe 'T '[a :u128]))))
  (is (= :clj-zig/malformed-members   (code-from #(layout/describe-enum 'E '[ok 0 bad]))))
  (is (= :clj-zig/non-integer-member  (code-from #(layout/describe-enum 'E '[ok :zero])))))

;; --- Generative breadth: junk in argument position is rejected ----------

(defspec junk-argument-types-are-rejected 200
  (prop/for-all [junk g/gen-junk-form]
    (some? (build-code {:ns 'clj-zig.matrix :name 'f
                        :signature ['a junk :ret :i64]}))))
