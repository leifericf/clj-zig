(ns zigar.type-test
  (:require [clojure.test :refer [deftest is testing]]
            [zigar.type :as type]))

(defn- error-code [f]
  (try
    (f)
    ::no-throw
    (catch clojure.lang.ExceptionInfo e
      (:error/code (ex-data e)))))

(deftest normalizes-scalars
  (testing "every documented scalar normalizes to {:kind :scalar :name kw}"
    (doseq [kw [:i8 :i16 :i32 :i64 :i128 :u8 :u16 :u32 :u64 :u128
                :isize :usize :f16 :f32 :f64 :f80 :f128 :bool :void :noreturn]]
      (is (= {:kind :scalar :name kw} (type/normalize kw))))))

(deftest normalizes-named-type-reference
  (testing "a symbol is a reference to a named type, resolved later"
    (is (= '{:kind :named :name Point} (type/normalize 'Point)))))

(deftest normalizes-const-slice
  (testing "the canonical const-slice example"
    (is (= {:kind :slice :const? true :of {:kind :scalar :name :u8}}
           (type/normalize [:slice :const :u8])))))

(deftest normalizes-mutable-slice
  (is (= {:kind :slice :const? false :of {:kind :scalar :name :f64}}
         (type/normalize [:slice :f64]))))

(deftest normalizes-pointers
  (is (= {:kind :ptr :const? true :of {:kind :scalar :name :i64}}
         (type/normalize [:ptr :const :i64])))
  (is (= {:kind :ptr :const? false :of {:kind :scalar :name :i64}}
         (type/normalize [:ptr :i64])))
  (is (= {:kind :manyptr :const? true :of {:kind :scalar :name :u8}}
         (type/normalize [:manyptr :const :u8])))
  (is (= {:kind :manyptr :const? false :of {:kind :scalar :name :u8}}
         (type/normalize [:manyptr :u8]))))

(deftest normalizes-array
  (is (= {:kind :array :length 4 :of {:kind :scalar :name :f32}}
         (type/normalize [:array 4 :f32])))
  (testing "zero length is allowed"
    (is (= {:kind :array :length 0 :of {:kind :scalar :name :u8}}
           (type/normalize [:array 0 :u8])))))

(deftest normalizes-optional
  (is (= {:kind :optional :of {:kind :scalar :name :i32}}
         (type/normalize [:optional :i32]))))

(deftest normalizes-error-union
  (testing "the error set is preserved as written; its semantics are settled later"
    (is (= '{:kind :error-union :error ParseError :of {:kind :scalar :name :i64}}
           (type/normalize '[:error-union ParseError :i64])))))

(deftest normalizes-ownership-and-handle-wrappers
  (is (= {:kind :owned :of {:kind :slice :const? false :of {:kind :scalar :name :u8}}}
         (type/normalize [:owned [:slice :u8]])))
  (is (= {:kind :borrowed :of {:kind :scalar :name :u8}}
         (type/normalize [:borrowed :u8])))
  (is (= '{:kind :handle :of {:kind :named :name Parser}}
         (type/normalize '[:handle Parser]))))

(deftest normalizes-nested-compounds
  (is (= {:kind :optional
          :of {:kind :slice :const? true :of {:kind :scalar :name :f64}}}
         (type/normalize [:optional [:slice :const :f64]]))))

(deftest rejects-unknown-scalar
  (is (= :zigar/unknown-scalar
         (error-code #(type/normalize :i7)))))

(deftest rejects-unknown-type
  (testing "a non-type value in type position"
    (is (= :zigar/unknown-type (error-code #(type/normalize 42))))
    (is (= :zigar/unknown-type (error-code #(type/normalize nil))))
    (is (= :zigar/unknown-type (error-code #(type/normalize "i64"))))))

(deftest rejects-malformed-compound
  (testing "empty vector"
    (is (= :zigar/malformed-compound (error-code #(type/normalize [])))))
  (testing "unknown head"
    (is (= :zigar/malformed-compound (error-code #(type/normalize [:tuple :i64])))))
  (testing "slice with a stray qualifier"
    (is (= :zigar/malformed-compound (error-code #(type/normalize [:slice :mut :u8])))))
  (testing "array with a non-integer length"
    (is (= :zigar/malformed-compound (error-code #(type/normalize [:array :x :u8])))))
  (testing "array with a negative length"
    (is (= :zigar/malformed-compound (error-code #(type/normalize [:array -1 :u8])))))
  (testing "wrapper with the wrong arity"
    (is (= :zigar/malformed-compound (error-code #(type/normalize [:optional :u8 :u8]))))))

(deftest propagates-inner-errors
  (testing "a bad element type surfaces as a diagnostic"
    (is (= :zigar/unknown-scalar
           (error-code #(type/normalize [:slice :const :nope]))))))

(deftest classifies-scalars
  (testing "signed, unsigned, float widths feed the unsigned policy and :u128 rejection"
    (is (= {:category :int :signed? true :bits 64} (type/scalar-info :i64)))
    (is (= {:category :int :signed? false :bits 64} (type/scalar-info :u64)))
    (is (= {:category :int :signed? false :bits 128} (type/scalar-info :u128)))
    (is (= :float (:category (type/scalar-info :f64))))
    (is (= 64 (:bits (type/scalar-info :f64))))
    (is (true? (type/unsigned-int? :u32)))
    (is (false? (type/unsigned-int? :i32)))
    (is (false? (type/unsigned-int? :f64)))
    (is (nil? (type/scalar-info :not-a-scalar)))))
