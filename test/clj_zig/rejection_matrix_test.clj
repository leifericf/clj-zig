(ns clj-zig.rejection-matrix-test
  "Negative-space enumeration: every malformed contract must be rejected
  with its own `:error/code`. The error code is the oracle. Where the
  example tests pin one rejection apiece, this walks the rejection arms of
  `signature/normalize`, `type/normalize`, `spec/validate!`, and the layout
  describers as a table, so a new branch without its own code is caught."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clj-zig.core :as core]
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
   {:code :clj-zig/unknown-type-name       :signature '[a Point :ret :i64]}
   ;; :bytes is a return-only owned u8-slice wrapper
   {:code :clj-zig/unsupported-bytes       :signature '[a [:bytes [:slice :u8]] :ret :i64]}
   {:code :clj-zig/unsupported-bytes       :signature '[:ret [:bytes :u8]]}
   {:code :clj-zig/unsupported-bytes       :signature '[:ret [:bytes [:slice :i64]]]}
   ;; a slice/array/ptr/manyptr whose element is not a scalar would validate
   ;; but crash at marshal time (the marshaller handles scalar elements only),
   ;; so it is rejected at spec time instead. No silent trap.
   {:code :clj-zig/unsupported-element     :signature '[xs [:slice Point] :ret :i64]}
   {:code :clj-zig/unsupported-element     :signature '[xs [:array 3 Point] :ret :i64]}
   {:code :clj-zig/unsupported-element     :signature '[xs [:ptr Point] :ret :i64]}
   {:code :clj-zig/unsupported-element     :signature '[xs [:manyptr Point] :ret :i64]}
   {:code :clj-zig/unsupported-element     :signature '[xs [:manyptr :const Point] :ret :i64]}
   {:code :clj-zig/unsupported-element     :signature '[:ret [:slice Point]]}
   {:code :clj-zig/unsupported-element     :signature '[xs [:slice [:slice :u8]] :ret :i64]}
   {:code :clj-zig/unsupported-element     :signature '[:ret [:slice [:slice :u8]]]}])

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
           :clj-zig/unknown-type-name :clj-zig/unsupported-bytes
           :clj-zig/unsupported-element}
         (set (map :code spec-rejections)))))

(deftest string-is-not-a-rejection
  ;; :string is a first-class buffer type: build-spec accepts it in argument
  ;; and return position and returns a spec, never a diagnostic. build-code
  ;; returns nil when no diagnostic is thrown.
  (testing ":string in argument, return, or both yields no diagnostic"
    (is (nil? (build-code {:ns 'clj-zig.matrix :name 'f
                           :signature '[s :string :ret :i64]})))
    (is (nil? (build-code {:ns 'clj-zig.matrix :name 'f
                           :signature '[n :i64 :ret :string]})))
    (is (nil? (build-code {:ns 'clj-zig.matrix :name 'f
                           :signature '[s :string :ret :string]})))
    (is (nil? (build-code {:ns 'clj-zig.matrix :name 'f
                           :signature '[:ret :string]})))))

;; --- Rejections from the layout describers ------------------------------

(deftest layout-rejection-matrix
  (is (= :clj-zig/malformed-fields    (code-from #(layout/describe 'T '[a :i64 b]))))
  ;; A slice is a valid buffer field now (it expands to a {ptr, len} pair),
  ;; so the rejection rows name the field types the wire struct still cannot
  ;; carry: a named type (a nested struct or enum), an unbounded pointer,
  ;; and a carrierless scalar.
  (is (= :clj-zig/unsupported-field   (code-from #(layout/describe 'T '[p Point]))))
  (is (= :clj-zig/unsupported-field   (code-from #(layout/describe 'T '[p [:ptr :i64]]))))
  (is (= :clj-zig/unsupported-field   (code-from #(layout/describe 'T '[p [:manyptr :i64]]))))
  (is (= :clj-zig/unsupported-field   (code-from #(layout/describe 'T '[n :u128]))))
  (is (= :clj-zig/malformed-members   (code-from #(layout/describe-enum 'E '[ok 0 bad]))))
  (is (= :clj-zig/non-integer-member  (code-from #(layout/describe-enum 'E '[ok :zero])))))

(deftest layout-buffer-fields-are-not-a-rejection
  ;; The buffer field kinds doc 10 §3 introduces are accepted by the layout
  ;; describer and yield no diagnostic: a :string, a [:bytes [:slice :u8]],
  ;; and a bare, owned, or borrowed slice of a carrier scalar.
  (is (nil? (code-from #(layout/describe 'T '[s :string]))))
  (is (nil? (code-from #(layout/describe 'T '[b [:bytes [:slice :u8]]]))))
  (is (nil? (code-from #(layout/describe 'T '[xs [:slice :i64]]))))
  (is (nil? (code-from #(layout/describe 'T '[xs [:owned [:slice :i64]]]))))
  (is (nil? (code-from #(layout/describe 'T '[xs [:borrowed [:slice :i64]]])))))

;; --- Rejections from external-module declarations -----------------------

(def module-rejections
  "Each row is a `core/zig-modules` descriptor paired with the code its
  malformed `:zig/modules` declaration must raise (ADR 34)."
  [{:code :clj-zig/bad-modules                 :descriptor {:zig/modules ["phane"]}}
   {:code :clj-zig/bad-module-name             :descriptor {:zig/modules {:phane {:path "r.zig"}}}}
   {:code :clj-zig/reserved-module-name        :descriptor {:zig/modules {"std" {:path "r.zig"}}}}
   {:code :clj-zig/bad-module-ref              :descriptor {:zig/modules {"phane" "r.zig"}}}
   {:code :clj-zig/module-missing-root         :descriptor {:zig/modules {"phane" {}}}}
   {:code :clj-zig/module-zig-version-mismatch :descriptor {:zig/modules {"phane" {:path "r.zig"
                                                                                  :zig/version "0.13.0"}}}}])

(deftest module-rejection-matrix
  (doseq [{:keys [code descriptor]} module-rejections]
    (testing (pr-str descriptor)
      (is (= code (code-from #(core/zig-modules descriptor)))))))

;; --- Generative breadth: junk in argument position is rejected ----------

(defspec junk-argument-types-are-rejected 200
  (prop/for-all [junk g/gen-junk-form]
    (some? (build-code {:ns 'clj-zig.matrix :name 'f
                        :signature ['a junk :ret :i64]}))))
