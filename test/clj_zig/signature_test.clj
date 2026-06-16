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

(deftest reserves-rest-argument
  (is (= :clj-zig/reserved-rest-arg
         (error-code #(sig/normalize '[x :i64 & more :ret :i64])))))

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
