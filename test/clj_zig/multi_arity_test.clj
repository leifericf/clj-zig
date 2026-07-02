(ns clj-zig.multi-arity-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :refer [defnz]]))

(defnz multi-add
  ([x :i64 :ret :i64] "return x;")
  ([x :i64 y :i64 :ret :i64] "return x + y;"))

(defnz multi-signature
  "Different return types across arities."
  ([n :u64 :ret :u64] "return n * n;")
  ([a :f64 b :f64 :ret :f64] "return a * b;"))

(deftest multi-arity-dispatches-by-argument-count
  (testing "one-arg arity returns the identity"
    (is (= 42 (multi-add 42))))
  (testing "two-arg arity returns the sum"
    (is (= 30 (multi-add 10 20))))
  (testing "wrong arity throws"
    (is (thrown? clojure.lang.ExceptionInfo (multi-add 1 2 3)))))

(deftest multi-arity-supports-different-signatures
  (is (= 9 (multi-signature 3)))
  (is (= 12.0 (multi-signature 3.0 4.0))))

(deftest single-arity-form-is-unchanged
  (testing "a vector as first tail element is single-arity"
    (eval `(defnz ~'sa-fn [~'x :i64 :ret :i64] "return x + 1;"))
    (is (= 6 ((resolve 'sa-fn) 5)))))

(deftest multi-arity-redefinition-keeps-last-good
  (let [define (fn [b1 b2]
                 (eval `(defnz ~'ma-kg
                          ([~'x :i64 :ret :i64] ~b1)
                          ([~'x :i64 ~'y :i64 :ret :i64] ~b2))))]
    (define "return x + 1;" "return x + y + 1;")
    (is (= 6 ((resolve 'ma-kg) 5)))
    (is (= 8 ((resolve 'ma-kg) 5 2)))
    (testing "redefinition rebinds both arities"
      (define "return x + 100;" "return x + y + 100;")
      (is (= 105 ((resolve 'ma-kg) 5)))
      (is (= 107 ((resolve 'ma-kg) 5 2))))
    (testing "a failed recompile of one arity keeps both previous bindings"
      (is (thrown? clojure.lang.ExceptionInfo
                   (define "return x + ;" "return x + y + 100;")))
      (is (= 105 ((resolve 'ma-kg) 5)))
      (is (= 107 ((resolve 'ma-kg) 5 2))))))

(deftest multi-arity-attaches-arglists-metadata
  (let [m (meta #'multi-add)]
    (is (= '([x] [x y]) (:arglists m)))))

(deftest duplicate-arity-count-rejected
  (testing "two arities with the same parameter count throw at macro expansion"
    (is (thrown?
         Exception
         (eval `(defnz ~'dup-arity
                  ([~'x :i64 :ret :i64] "return x;")
                  ([~'y :i64 :ret :i64] "return y + 1;")))))))
