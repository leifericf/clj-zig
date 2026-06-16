(ns clj-zig.error-union-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig :as zig]
            [clj-zig.core :refer [defnz defz]]))

;; --- Scalar value or error ----------------------------------------------

(defnz doubled-non-negative
  [n :i64
   :ret [:error-union anyerror :i64]]
  "if (n < 0) return error.Negative;
   return n * 2;")

(deftest a-value-or-an-error-keyword
  (testing "success returns the value"
    (is (= 10 (doubled-non-negative 5))))
  (testing "failure returns the error name as a keyword"
    (is (= :Negative (doubled-non-negative -1)))))

;; --- A named error set --------------------------------------------------

(defz LimitError "const LimitError = error{TooBig};")

(defnz capped
  [n :i64
   :ret [:error-union LimitError :i64]]
  "if (n > 100) return error.TooBig;
   return n;")

(deftest a-named-error-set-crosses-by-name
  (is (= 50 (capped 50)))
  (is (= :TooBig (capped 200))))

;; --- Void value or error ------------------------------------------------

(defnz require-even
  [n :i64
   :ret [:error-union anyerror :void]]
  "if (@rem(n, 2) != 0) return error.Odd;")

(deftest a-void-error-union-is-nil-or-an-error
  (testing "success is nil"
    (is (nil? (require-even 4))))
  (testing "failure is the error keyword"
    (is (= :Odd (require-even 3)))))

;; --- Contract validation ------------------------------------------------

(deftest error-unions-are-return-only-and-value-shaped
  (testing "an error union argument is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"error-union"
                          (zig/build-spec '{:ns t :name f
                                            :signature [x [:error-union anyerror :i64] :ret :i64]}))))
  (testing "an error union over a non-scalar value is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"error-union"
                          (zig/build-spec '{:ns t :name f
                                            :signature [x :i64
                                                        :ret [:error-union anyerror [:slice :u8]]]})))))

;; --- Generated source ---------------------------------------------------

(deftest generates-an-impl-fn-and-a-translating-wrapper
  (let [src (zig/generated-source #'doubled-non-negative)]
    (testing "the user body lives in an inner impl fn returning the error union"
      (is (str/includes? src "__impl"))
      (is (str/includes? src "anyerror!i64")))
    (testing "the wrapper translates the error by name"
      (is (str/includes? src "@errorName")))))
