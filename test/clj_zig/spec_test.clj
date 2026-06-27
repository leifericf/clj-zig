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
  (testing "as an argument"
    (is (= :clj-zig/unsupported-carrier
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [x :u128 :ret :i64]})))))
  (testing "as a return"
    (is (= :clj-zig/unsupported-carrier
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [x :i64 :ret :i128]})))))
  (testing "nested inside a compound"
    (is (= :clj-zig/unsupported-carrier
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:slice :u128] :ret :i64]}))))))

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
    (spec/build-spec '{:ns app.core :name f :signature [x :u128 :ret :i64]})
    (is false "expected a diagnostic")
    (catch clojure.lang.ExceptionInfo e
      (is (= 'app.core/f (:var (ex-data e))))
      (is (= '[x :u128 :ret :i64] (:signature (ex-data e)))))))

(deftest rejects-non-scalar-slice-and-pointer-elements
  (testing "a named element is rejected for every indirection kind"
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:slice Point] :ret :i64]}))))
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:array 3 Point] :ret :i64]}))))
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:ptr Point] :ret :i64]}))))
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:manyptr Point] :ret :i64]}))))
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:manyptr :const Point] :ret :i64]})))))
  (testing "the const variant of every indirection kind is covered"
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:slice :const Point] :ret :i64]}))))
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:ptr :const Point] :ret :i64]})))))
  (testing "a nested indirection element is rejected (the element is not a scalar)"
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:slice [:slice :u8]] :ret :i64]}))))
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:array 2 [:ptr :i64]] :ret :i64]}))))
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [:ret [:slice [:slice :u8]]]})))))
  (testing "a non-scalar element wrapped in ownership is still rejected"
    ;; [:owned [:slice Point]] would otherwise pass the ownership check
    ;; (it wraps a slice) and reach the marshaller, where it crashes.
    (is (= :clj-zig/unsupported-element
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [:ret [:owned [:slice Point]]]})))))
  (testing "a carrier-scalar element is still accepted (no over-rejection)"
    (is (map? (spec/build-spec '{:ns app.core :name f
                                 :signature [xs [:slice :u8] :ret :i64]})))
    (is (map? (spec/build-spec '{:ns app.core :name f
                                 :signature [xs [:manyptr :const :f64] :ret :f64]})))
    (is (map? (spec/build-spec '{:ns app.core :name f
                                 :signature [:ret [:owned [:slice :u8]]]}))))
  (testing "the offending element is named in the diagnostic ex-data"
    (try
      (spec/build-spec '{:ns app.core :name f
                         :signature [xs [:slice Point] :ret :i64]})
      (is false "expected a diagnostic")
      (catch clojure.lang.ExceptionInfo e
        (let [d (ex-data e)]
          (is (= :clj-zig/unsupported-element (:error/code d)))
          (is (= :slice (:indirection d)))
          (is (= {:kind :named :name 'Point} (:element d))))))
    (testing "a nested-slice element names the slice in the diagnostic"
      (try
        (spec/build-spec '{:ns app.core :name f
                           :signature [xs [:slice [:slice :u8]] :ret :i64]})
        (is false "expected a diagnostic")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (= :clj-zig/unsupported-element (:error/code d)))
            (is (= :slice (:indirection d)))
            (is (= {:kind :slice} (:element d))))))))
  (testing "the rejection targets the element shape, not the named type itself"
    ;; A declared Point is a valid bare argument; only as a slice element is
    ;; it rejected, because the marshaller carries scalar elements only.
    (let [types {'Point (layout/describe 'Point '[x :f64 y :f64])}]
      (is (map? (spec/build-spec {:ns 'app.core :name 'f
                                  :signature '[p Point :ret :f64]
                                  :types types})))
      (is (= :clj-zig/unsupported-element
             (error-code #(spec/build-spec {:ns 'app.core :name 'f
                                            :signature '[xs [:slice Point] :ret :f64]
                                            :types types})))))))
