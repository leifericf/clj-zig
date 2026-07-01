(ns clj-zig.signature-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.signature :as sig]))

(defn- error-code
  "Run `f`, returning the diagnostic's `:error/code`, or ::no-throw."
  [f]
  (try
    (f)
    ::no-throw
    (catch clojure.lang.ExceptionInfo e
      (:error/code (ex-data e)))))

(deftest parses-scalar-signature
  (is (= '{:args [{:binding x :type :i64}
                  {:binding y :type :i64}]
           :ret :i64}
         (sig/normalize '[x :i64 y :i64 :ret :i64]))))

(deftest preserves-binding-names
  (is (= '[total scale]
         (mapv :binding (:args (sig/normalize '[total :f64 scale :f64 :ret :f64]))))))

(deftest zero-argument-signature
  (is (= '{:args [] :ret :void}
         (sig/normalize '[:ret :void]))))

(deftest preserves-compound-type-forms
  (testing "the signature parser keeps type forms verbatim; clj-zig.type normalizes them"
    (is (= '{:args [{:binding xs :type [:slice :const :f64]}]
             :ret :f64}
           (sig/normalize '[xs [:slice :const :f64] :ret :f64])))))

(deftest requires-final-ret
  (is (= :clj-zig/missing-ret
         (error-code #(sig/normalize '[x :i64 y :i64])))))

(deftest rejects-misplaced-ret
  (testing "arguments after the return type"
    (is (= :clj-zig/misplaced-ret
           (error-code #(sig/normalize '[x :i64 :ret :i64 y :i64])))))
  (testing "a second :ret marker is a distinct condition"
    (is (= :clj-zig/extra-ret
           (error-code #(sig/normalize '[x :i64 :ret :ret :i64]))))))

(deftest rejects-ret-without-return-type
  (is (= :clj-zig/empty-ret
         (error-code #(sig/normalize '[x :i64 :ret])))))

(deftest rejects-uneven-argument-list
  (is (= :clj-zig/uneven-signature
         (error-code #(sig/normalize '[x :i64 y :ret :i64])))))

(deftest rejects-non-vector-signature
  (testing "a list"
    (is (= :clj-zig/invalid-signature
           (error-code #(sig/normalize '(x :i64 :ret :i64))))))
  (testing "nil"
    (is (= :clj-zig/invalid-signature
           (error-code #(sig/normalize nil)))))
  (testing "a map"
    (is (= :clj-zig/invalid-signature
           (error-code #(sig/normalize {:x :i64}))))))

(deftest rejects-invalid-binding
  (is (= :clj-zig/invalid-binding
         (error-code #(sig/normalize '[:i64 x :ret :i64])))))

(deftest lowers-rest-argument-to-a-const-slice
  (testing "a trailing & binding type lowers to a const slice flagged :rest?"
    (is (= '{:args [{:binding x :type :i64}
                    {:binding rest :type [:slice :const :i64] :rest? true}]
             :ret :i64}
           (sig/normalize '[x :i64 & rest :i64 :ret :i64]))))
  (testing "an all-rest signature has no leading pairs"
    (is (= '{:args [{:binding rest :type [:slice :const :f64] :rest? true}]
             :ret :f64}
           (sig/normalize '[& rest :f64 :ret :f64])))))

(deftest rejects-misplaced-rest-marker
  (testing "& must be followed by exactly a binding and type, then :ret"
    (is (= :clj-zig/misplaced-rest
           (error-code #(sig/normalize '[x :i64 & rest :i64 y :i64 :ret :i64])))))
  (testing "a lone & with no binding and type"
    (is (= :clj-zig/misplaced-rest
           (error-code #(sig/normalize '[x :i64 & :ret :i64])))))
  (testing "a second & is caught by the trailing-position rule"
    (is (= :clj-zig/misplaced-rest
           (error-code #(sig/normalize '[& a :i64 & b :i64 :ret :i64]))))))

(deftest rejects-non-scalar-rest-element
  (testing "a named-type element is not a carrier scalar"
    (is (= :clj-zig/unsupported-rest-element
           (error-code #(sig/normalize '[& rest Point :ret :i64])))))
  (testing "a non-carrier scalar (i128) is rejected"
    (is (= :clj-zig/unsupported-rest-element
           (error-code #(sig/normalize '[& rest :i128 :ret :i64])))))
  (testing "a compound element is rejected"
    (is (= :clj-zig/unsupported-rest-element
           (error-code #(sig/normalize '[& rest [:slice :i64] :ret :i64]))))))

(deftest captures-clojure-side-destructuring
  (testing "a map binding with a field-map type is captured for Clojure-side lowering"
    (let [arg (-> (sig/normalize '[{x1 :x y1 :y} {:x :f64 :y :f64} :ret :f64])
                  :args
                  first)]
      (is (true? (:destructured? arg)))
      (is (= '{x1 :x y1 :y} (:binding arg)))
      (is (= {:x :f64 :y :f64} (:type arg))))))

(deftest diagnostic-carries-the-signature
  (testing "a thrown diagnostic includes the offending signature as data"
    (try
      (sig/normalize '[x :i64 y :i64])
      (is false "expected a diagnostic")
      (catch clojure.lang.ExceptionInfo e
        (is (= '[x :i64 y :i64] (:signature (ex-data e))))
        (is (= :error (:level (ex-data e))))))))
