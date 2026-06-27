(ns clj-zig.optional-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig :as zig]
            [clj-zig.core :refer [defnz defz]]))

;; --- Optional pointer arguments -----------------------------------------

(defnz deref-or
  [p [:optional [:ptr :const :i64]]
   :ret :i64]
  "return if (p) |q| q.* else -1;")

(defnz first-or
  [p [:optional [:manyptr :const :i32]]
   :ret :i32]
  "return if (p) |q| q[0] else -1;")

(deftest nil-crosses-as-a-null-pointer
  (testing "a present single-item pointer is dereferenced"
    (is (= 7 (deref-or (long-array [7])))))
  (testing "nil is none"
    (is (= -1 (deref-or nil))))
  (testing "an optional many-item pointer is present or none"
    (is (= 9 (first-or (int-array [9 8 7]))))
    (is (= -1 (first-or nil)))))

;; --- Optional pointer return --------------------------------------------

(defz answer "const answer: i64 = 42;")
(defz pi "const pi_val: f64 = 3.5;")

(defnz lookup-i64
  [found :bool
   :ret [:optional [:ptr :const :i64]]]
  "return if (found) &answer else null;")

(defnz lookup-f64
  [found :bool
   :ret [:optional [:ptr :const :f64]]]
  "return if (found) &pi_val else null;")

(deftest an-optional-pointer-return-is-the-value-or-nil
  (testing "a non-null return is dereferenced to the value"
    (is (= 42 (lookup-i64 true)))
    (is (= 3.5 (lookup-f64 true))))
  (testing "a null return is nil"
    (is (nil? (lookup-i64 false)))
    (is (nil? (lookup-f64 false)))))

;; --- Optional scalar arguments and returns (nil-or-int) ------------------
;; A carrier scalar under :optional lowers to the same wire shape as
;; [:optional [:ptr :const T]]: nil crosses as NULL, a present value as a
;; one-element native cell. The Clojure caller passes nil or the scalar
;; itself (nil-or-int), not a one-element array.

(defnz opt-int-or
  [x [:optional :i64]
   :ret :i64]
  "return if (x) |p| p.* else -1;")

(defnz lookup-opt-i64
  [found :bool
   :ret [:optional :i64]]
  "return if (found) &answer else null;")

(deftest an-optional-scalar-argument-is-nil-or-int
  (testing "a present int crosses as a one-element cell and is dereferenced"
    (is (= 7 (opt-int-or 7))))
  (testing "nil crosses as NULL"
    (is (= -1 (opt-int-or nil)))))

(deftest an-optional-scalar-return-is-nil-or-int
  (testing "a non-null return is dereferenced to the int"
    (is (= 42 (lookup-opt-i64 true))))
  (testing "a null return is nil"
    (is (nil? (lookup-opt-i64 false)))))

;; --- Contract validation ------------------------------------------------

(deftest optional-must-wrap-a-pointer-or-carrier-scalar
  (testing "an optional carrierless scalar is rejected: no FFM cell to point at"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"optional"
                          (zig/build-spec '{:ns t :name f
                                            :signature [x [:optional :u128] :ret :i64]}))))
  (testing "an optional slice is rejected: no length semantics under optional"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"optional"
                          (zig/build-spec '{:ns t :name f
                                            :signature [x [:optional [:slice :u8]] :ret :i64]}))))
  (testing "a many-item optional cannot be returned: it has no length to read"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"optional"
                          (zig/build-spec '{:ns t :name f
                                            :signature [x :bool
                                                        :ret [:optional [:manyptr :u8]]]})))))

;; --- Generated source ---------------------------------------------------

(deftest generates-optional-pointer-types
  (is (str/includes? (zig/generated-source #'deref-or) "p: ?*const i64"))
  (is (str/includes? (zig/generated-source #'first-or) "p: ?[*]const i32"))
  (is (str/includes? (zig/generated-source #'lookup-i64) ") ?*const i64 {")))

(deftest generates-optional-scalar-pointer-types
  (testing "an optional scalar lowers to a nullable pointer-to-const cell"
    (is (str/includes? (zig/generated-source #'opt-int-or) "x: ?*const i64"))
    (is (str/includes? (zig/generated-source #'lookup-opt-i64) ") ?*const i64 {"))))
