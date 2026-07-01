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

;; Declared types for element-rejection rows: a scalar-only struct (a valid
;; slice element) and a buffer-carrying struct (an invalid one).
(def matrix-types
  {'Point (layout/describe 'Point '[x :f64 y :f64])
   'Buf   (layout/describe 'Buf '[media :string] {})})

;; --- Rejections that surface through build-spec -------------------------

(def spec-rejections
  "Each row is a `build-spec` input paired with the code it must raise.
  `build-spec` runs signature normalization, type normalization, and
  contract validation, so all three layers' rejections show up here."
  [;; signature shape
   {:code :clj-zig/invalid-signature  :signature '(a :i64 :ret :i64)}
   {:code :clj-zig/misplaced-rest      :signature '[a :i64 & b :ret :i64]}
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
   {:code :clj-zig/unsupported-optional   :signature '[a [:optional :u128] :ret :i64]}
   {:code :clj-zig/unsupported-optional   :signature '[a [:optional [:slice :u8]] :ret :i64]}
   {:code :clj-zig/unsupported-error-union :signature '[a [:error-union E :i64] :ret :i64]}
   {:code :clj-zig/unsupported-ownership  :signature '[a [:owned [:slice :u8]] :ret :i64]}
   {:code :clj-zig/unsupported-handle     :signature '[a [:handle :i64] :ret :i64]}
   {:code :clj-zig/unsupported-carrier    :signature '[a :f80 :ret :i64]}
   {:code :clj-zig/unknown-field          :signature '[{x :x} {:y :i64} :ret :i64]}
   ;; contract validation, return position
   {:code :clj-zig/unsupported-optional    :signature '[:ret [:optional [:manyptr :i64]]]}
   {:code :clj-zig/unsupported-optional    :signature '[:ret [:optional [:slice :u8]]]}
   {:code :clj-zig/unsupported-error-union :signature '[:ret [:error-union E [:slice :i64]]]}
   {:code :clj-zig/unsupported-error-union :signature '[:ret [:error-union E [:slice :u8]]]}
   {:code :clj-zig/malformed-error-set    :signature '[:ret [:error-union 42 :i64]]}
   {:code :clj-zig/malformed-error-set    :signature '[:ret [:error-union [:slice :u8] :i64]]}
   {:code :clj-zig/unsupported-ownership   :signature '[:ret [:owned :i64]]}
   {:code :clj-zig/unsupported-handle      :signature '[:ret [:handle :i64]]}
    {:code :clj-zig/unsupported-carrier     :signature '[:ret :f128]}
    ;; bare indirection kinds in return position (no return dispatch exists)
    {:code :clj-zig/unsupported-return-kind :signature '[:ret [:slice :i64]]}
    {:code :clj-zig/unsupported-return-kind :signature '[:ret [:array 3 :i64]]}
    {:code :clj-zig/unsupported-return-kind :signature '[:ret [:ptr :i64]]}
    {:code :clj-zig/unsupported-return-kind :signature '[:ret [:manyptr :i64]]}
    ;; named type the registry does not declare
   {:code :clj-zig/unknown-type-name       :signature '[a Point :ret :i64]}
   ;; :bytes is a return-only owned u8-slice wrapper
   {:code :clj-zig/unsupported-bytes       :signature '[a [:bytes [:slice :u8]] :ret :i64]}
   {:code :clj-zig/unsupported-bytes       :signature '[:ret [:bytes :u8]]}
   {:code :clj-zig/unsupported-bytes       :signature '[:ret [:bytes [:slice :i64]]]}
    ;; a slice/array of a scalar struct is a valid element now (crossed by
    ;; value), so the element-rejection rows name the shapes still rejected:
    ;; a pointer to any named type (a :ptr/:manyptr element must be scalar),
    ;; an argument slice/array of a buffer-carrying struct (the marshaller
    ;; writes extern slots and cannot lay out a nice struct's buffers there),
    ;; a borrowed slice of one (the wrapper's wire slab would leak with no
    ;; free shim), and a nested indirection.
    {:code :clj-zig/unsupported-element     :signature '[xs [:ptr Point] :ret :i64]
     :types matrix-types}
    {:code :clj-zig/unsupported-element     :signature '[xs [:manyptr Point] :ret :i64]
     :types matrix-types}
    {:code :clj-zig/unsupported-element     :signature '[xs [:manyptr :const Point] :ret :i64]
     :types matrix-types}
    {:code :clj-zig/unsupported-element     :signature '[xs [:slice Buf] :ret :i64]
     :types matrix-types}
    {:code :clj-zig/unsupported-element     :signature '[xs [:array 3 Buf] :ret :i64]
     :types matrix-types}
    {:code :clj-zig/unsupported-borrowed-buffer-slice :signature '[:ret [:borrowed [:slice Buf]]]
     :types matrix-types}
    {:code :clj-zig/unsupported-element     :signature '[xs [:slice [:slice :u8]] :ret :i64]}
    {:code :clj-zig/unsupported-element     :signature '[:ret [:slice [:slice :u8]]]}])

(deftest spec-rejection-matrix
  (doseq [{:keys [code signature types]} spec-rejections]
    (testing (pr-str signature)
      (is (= code (build-code {:ns 'clj-zig.matrix :name 'f :signature signature
                               :types types}))))))

(deftest spec-rejection-codes-are-distinct-where-expected
  ;; Every documented rejection arm appears in the table.
  (is (= #{:clj-zig/invalid-signature :clj-zig/misplaced-rest :clj-zig/missing-ret
           :clj-zig/extra-ret :clj-zig/empty-ret :clj-zig/misplaced-ret
           :clj-zig/uneven-signature :clj-zig/invalid-binding :clj-zig/unknown-type
           :clj-zig/unknown-scalar :clj-zig/malformed-compound :clj-zig/void-argument
           :clj-zig/unsupported-optional :clj-zig/unsupported-error-union
           :clj-zig/unsupported-ownership :clj-zig/unsupported-handle
             :clj-zig/unsupported-carrier :clj-zig/unknown-field
             :clj-zig/unknown-type-name :clj-zig/unsupported-bytes
              :clj-zig/unsupported-element :clj-zig/malformed-error-set
              :clj-zig/unsupported-borrowed-buffer-slice
              :clj-zig/unsupported-return-kind}
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

(deftest optional-carrier-scalar-is-not-a-rejection
  ;; A carrier scalar under :optional lowers to a nullable pointer-to-const
  ;; cell, so build-spec accepts it in argument and return position and
  ;; returns no diagnostic. (A carrierless scalar and a slice under
  ;; :optional are rejected -- see the matrix above.)
  (testing "[:optional :i64] in argument, return, or both yields no diagnostic"
    (is (nil? (build-code {:ns 'clj-zig.matrix :name 'f
                           :signature '[x [:optional :i64] :ret :i64]})))
    (is (nil? (build-code {:ns 'clj-zig.matrix :name 'f
                           :signature '[b :bool :ret [:optional :i64]]})))
    (is (nil? (build-code {:ns 'clj-zig.matrix :name 'f
                           :signature '[x [:optional :i64] :ret [:optional :i64]]})))
    (is (nil? (build-code {:ns 'clj-zig.matrix :name 'f
                           :signature '[x [:optional :f64] :ret [:optional :bool]]})))))

;; --- Rejections from the layout describers ------------------------------

(deftest layout-rejection-matrix
  (is (= :clj-zig/malformed-fields    (code-from #(layout/describe 'T '[a :i64 b]))))
  ;; A slice is a valid buffer field now (it expands to a {ptr, len} pair),
  ;; and a nested struct is a valid by-value field when its inner type is
  ;; declared and scalar-only, so the rejection rows name the field types
  ;; the wire struct still cannot carry: an undeclared named type, an
  ;; unbounded pointer, and a carrierless scalar.
  (is (= :clj-zig/unknown-field       (code-from #(layout/describe 'T '[p Point]))))
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
