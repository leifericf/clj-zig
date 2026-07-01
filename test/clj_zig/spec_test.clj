(ns clj-zig.spec-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.layout :as layout]
            [clj-zig.spec :as spec]))

(defn- error-code [f]
  (try
    (f)
    ::no-throw
    (catch clojure.lang.ExceptionInfo e
      (:error/code (ex-data e)))))

(def add-spec
  (spec/build-spec '{:ns app.core :name add
                     :signature [x :i64 y :i64 :ret :i64]}))

(deftest builds-scalar-spec
  (testing "params carry normalized types in order"
    (is (= '[{:binding x :type {:kind :scalar :name :i64}}
             {:binding y :type {:kind :scalar :name :i64}}]
           (:params add-spec))))
  (testing "the return type is normalized"
    (is (= {:kind :scalar :name :i64} (:ret add-spec))))
  (testing "identity and the original signature are preserved for inspection"
    (is (= 'app.core (:ns add-spec)))
    (is (= 'add (:name add-spec)))
    (is (= '[x :i64 y :i64 :ret :i64] (:signature add-spec)))))

(deftest computes-stable-collision-free-symbol
  (is (= "clj_zig_app_2e_core_add" (:symbol add-spec)))
  (testing "the same identity always yields the same symbol"
    (is (= (:symbol add-spec)
           (:symbol (spec/build-spec '{:ns app.core :name add
                                       :signature [a :i64 :ret :i64]})))))
  (testing "characters that are illegal in C identifiers are escaped, not collapsed"
    (is (= "clj_zig_my_2d_ns_do_2d_thing"
           (spec/symbol-name 'my-ns 'do-thing)))
    (is (not= (spec/symbol-name 'a-b 'c)
              (spec/symbol-name 'a_b 'c)))))

(deftest normalizes-compound-argument-types
  (let [s (spec/build-spec '{:ns app.core :name sum
                             :signature [xs [:slice :const :f64] :ret :f64]})]
    (is (= {:kind :slice :const? true :of {:kind :scalar :name :f64}}
           (-> s :params first :type)))))

(deftest void-return-is-allowed
  (is (= {:kind :scalar :name :void}
         (:ret (spec/build-spec '{:ns app.core :name noop
                                  :signature [:ret :void]})))))

(deftest accepts-string-as-a-boundary-type
  (testing "a :string argument is accepted and normalized"
    (let [s (spec/build-spec '{:ns app.core :name f
                               :signature [s :string :ret :i64]})]
      (is (= {:kind :string} (-> s :params first :type)))
      (is (= {:kind :scalar :name :i64} (:ret s)))))
  (testing "a :string return is accepted and normalized"
    (let [s (spec/build-spec '{:ns app.core :name f
                               :signature [n :i64 :ret :string]})]
      (is (= {:kind :string} (:ret s)))))
  (testing ":string in both argument and return position"
    (let [s (spec/build-spec '{:ns app.core :name f
                               :signature [s :string :ret :string]})]
      (is (= {:kind :string} (-> s :params first :type)))
      (is (= {:kind :string} (:ret s))))))

(deftest accepts-owned-and-borrowed-records-in-return-position
  (let [scalar-rec (layout/describe 'ScalarRec '[a :i32 b :i64])
        buf-rec    (layout/describe 'BufRec
                                   '[flag :u32 msg :string bytes [:bytes [:slice :u8]]])
        types      {'ScalarRec scalar-rec 'BufRec buf-rec}]
    (testing "[:owned NamedRecord] is accepted for a scalar-only record"
      (is (map? (spec/build-spec {:ns 'app.core :name 'f
                                  :signature '[:ret [:owned ScalarRec]]
                                  :types types}))))
    (testing "[:owned NamedRecord] is accepted for a buffer-carrying record"
      (is (map? (spec/build-spec {:ns 'app.core :name 'f
                                  :signature '[:ret [:owned BufRec]]
                                  :types types}))))
    (testing "[:borrowed NamedRecord] is accepted for either record kind"
      (is (map? (spec/build-spec {:ns 'app.core :name 'f
                                  :signature '[:ret [:borrowed ScalarRec]]
                                  :types types})))
      (is (map? (spec/build-spec {:ns 'app.core :name 'f
                                  :signature '[:ret [:borrowed BufRec]]
                                  :types types}))))
    (testing "the relaxation is uniform: ownership works for any struct"
      (is (map? (spec/build-spec {:ns 'app.core :name 'f
                                  :signature '[:ret [:owned ScalarRec]]
                                  :types types}))))))

(deftest rejects-owned-and-borrowed-over-an-enum
  ;; An enum crosses as a scalar; ownership over it has no meaning and no
  ;; free shim is emitted, so it is rejected at spec time rather than
  ;; crashing at marshal time (the no-silent-trap guardrail).
  (let [enum-rec (layout/describe-enum 'Status '[ok 0 bad 1])
        types     {'Status enum-rec}]
    (is (= :clj-zig/unsupported-ownership
           (error-code #(spec/build-spec {:ns 'app.core :name 'f
                                          :signature '[:ret [:owned Status]]
                                          :types types}))))
    (is (= :clj-zig/unsupported-ownership
           (error-code #(spec/build-spec {:ns 'app.core :name 'f
                                          :signature '[:ret [:borrowed Status]]
                                          :types types}))))))

(deftest rejects-unsupported-128-bit-carriers
  (testing "a 128-bit integer scalar is valid as a top-level arg and return"
    (is (map? (spec/build-spec '{:ns app.core :name f :signature [x :u128 :ret :i64]})))
    (is (map? (spec/build-spec '{:ns app.core :name f :signature [x :i64 :ret :i128]}))))
  (testing "a 128-bit integer as a slice element is rejected (bulk marshalling not wired)"
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:slice :i128] :ret :i64]}))))))

(deftest rejects-floats-without-an-ffm-carrier
  (testing "stable FFM carries only 32- and 64-bit floats"
    (is (= :clj-zig/unsupported-carrier
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [x :f16 :ret :f64]}))))
    (is (= :clj-zig/unsupported-carrier
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [x :f64 :ret :f128]}))))
    (is (= :clj-zig/unsupported-carrier
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [x :f80 :ret :f64]}))))))

(deftest rejects-void-argument
  (is (= :clj-zig/void-argument
         (error-code #(spec/build-spec '{:ns app.core :name f
                                         :signature [x :void :ret :i64]})))))

(deftest accepts-a-slice-of-a-scalar-struct
  (let [types {'Point (layout/describe 'Point '[x :f64 y :f64])}]
    (testing "a slice element may be a named scalar-only struct"
      (is (map? (spec/build-spec {:ns 'app.core :name 'f
                                  :signature '[xs [:slice :const Point] :ret :i64]
                                  :types types}))))
    (testing "an owned slice of a scalar struct is a valid return"
      (is (map? (spec/build-spec {:ns 'app.core :name 'f
                                  :signature '[:ret [:owned [:slice Point]]]
                                  :types types}))))))

(deftest rejects-a-mutable-struct-element-slice
  (let [types {'Point (layout/describe 'Point '[x :f64 y :f64])}]
    (testing "a non-const struct-element slice cannot propagate mutations to maps"
      (is (= :clj-zig/mutable-struct-slice
             (error-code #(spec/build-spec {:ns 'app.core :name 'f
                                            :signature '[xs [:slice Point] :ret :i64]
                                            :types types})))))
    (testing "the const variant is accepted"
      (is (map? (spec/build-spec {:ns 'app.core :name 'f
                                  :signature '[xs [:slice :const Point] :ret :i64]
                                  :types types}))))
    (testing "a mutable scalar slice still propagates (no over-rejection)"
      (is (map? (spec/build-spec '{:ns app.core :name f
                                   :signature [xs [:slice :i64] :ret :i64]}))))))

(deftest rejects-a-slice-of-a-non-scalar-struct
  (testing "a buffer-carrying struct element is rejected"
    (let [types {'Buf (layout/describe 'Buf '[media :string] {})
                 'Point (layout/describe 'Point '[x :f64 y :f64])}]
      (is (= :clj-zig/unsupported-element
             (error-code #(spec/build-spec {:ns 'app.core :name 'f
                                             :signature '[xs [:slice :const Buf] :ret :i64]
                                             :types types})))))))

(deftest expands-clojure-side-destructuring-into-native-params
  (testing "each destructured local becomes a native scalar param"
    (let [s (spec/build-spec
             '{:ns app.core :name distance
               :signature [{x1 :x y1 :y} {:x :f64 :y :f64}
                           {x2 :x y2 :y} {:x :f64 :y :f64}
                           :ret :f64]})]
      (testing "one native param per destructured local"
        (is (= 4 (count (:params s))))
        (is (= '#{x1 y1 x2 y2} (set (map :binding (:params s))))))
      (is (every? #(= {:kind :scalar :name :f64} (:type %)) (:params s)))
      (testing "each param records which argument and field it came from"
        (is (= #{{:arg 0 :field :x} {:arg 0 :field :y}
                 {:arg 1 :field :x} {:arg 1 :field :y}}
               (set (map :destructured-from (:params s)))))))))

(deftest rejects-unknown-destructuring-field
  (is (= :clj-zig/unknown-field
         (error-code #(spec/build-spec
                       '{:ns app.core :name f
                         :signature [{a :x} {:y :f64} :ret :f64]})))))

(deftest diagnostic-carries-the-var-and-signature
  (try
    (spec/build-spec '{:ns app.core :name f :signature [x :f80 :ret :i64]})
    (is false "expected a diagnostic")
    (catch clojure.lang.ExceptionInfo e
      (is (= 'app.core/f (:var (ex-data e))))
      (is (= '[x :f80 :ret :i64] (:signature (ex-data e)))))))

(deftest accepts-and-rejects-struct-elements-per-indirection
  (let [types {'Point (layout/describe 'Point '[x :f64 y :f64])
               'Buf   (layout/describe 'Buf '[media :string] {})}]
    (testing "a scalar-only struct is a valid slice, array, and owned-slice element"
      (is (map? (spec/build-spec {:ns 'app.core :name 'f
                                  :signature '[xs [:slice :const Point] :ret :i64]
                                  :types types})))
      (is (map? (spec/build-spec {:ns 'app.core :name 'f
                                  :signature '[xs [:array 3 Point] :ret :i64]
                                  :types types})))
      (is (map? (spec/build-spec {:ns 'app.core :name 'f
                                  :signature '[:ret [:owned [:slice Point]]]
                                  :types types}))))
    (testing "a pointer or many-pointer must still hold a scalar element"
      (is (= :clj-zig/unsupported-element
             (error-code #(spec/build-spec {:ns 'app.core :name 'f
                                             :signature '[xs [:ptr Point] :ret :i64]
                                             :types types}))))
      (is (= :clj-zig/unsupported-element
             (error-code #(spec/build-spec {:ns 'app.core :name 'f
                                             :signature '[xs [:manyptr :const Point] :ret :i64]
                                             :types types})))))
    (testing "a slice or array of a buffer-carrying struct is rejected"
      (is (= :clj-zig/unsupported-element
             (error-code #(spec/build-spec {:ns 'app.core :name 'f
                                             :signature '[xs [:slice Buf] :ret :i64]
                                             :types types}))))
      (is (= :clj-zig/unsupported-element
             (error-code #(spec/build-spec {:ns 'app.core :name 'f
                                             :signature '[:ret [:owned [:slice Buf]]]
                                             :types types}))))))
  (testing "a nested indirection element is rejected (the element is not a plain scalar or struct)"
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:slice [:slice :u8]] :ret :i64]}))))
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:array 2 [:ptr :i64]] :ret :i64]}))))
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [:ret [:slice [:slice :u8]]]})))))
  (testing "a carrier-scalar element is still accepted (no over-rejection)"
    (is (map? (spec/build-spec '{:ns app.core :name f
                                 :signature [xs [:slice :u8] :ret :i64]})))
    (is (map? (spec/build-spec '{:ns app.core :name f
                                 :signature [xs [:manyptr :const :f64] :ret :f64]})))
    (is (map? (spec/build-spec '{:ns app.core :name f
                                 :signature [:ret [:owned [:slice :u8]]]}))))
  (testing "the offending element is named in the diagnostic ex-data"
    (let [types {'Buf (layout/describe 'Buf '[media :string] {})}]
      (try
        (spec/build-spec {:ns 'app.core :name 'f
                          :signature '[xs [:slice Buf] :ret :i64]
                          :types types})
        (is false "expected a diagnostic")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (= :clj-zig/unsupported-element (:error/code d)))
            (is (= :slice (:indirection d)))
            (is (= {:kind :named :name 'Buf} (:element d)))))))
    (testing "a nested-slice element names the slice in the diagnostic"
      (try
        (spec/build-spec '{:ns app.core :name f
                           :signature [xs [:slice [:slice :u8]] :ret :i64]})
        (is false "expected a diagnostic")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (= :clj-zig/unsupported-element (:error/code d)))
            (is (= :slice (:indirection d)))
            (is (= {:kind :slice} (:element d)))))))))
