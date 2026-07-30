(ns clj-zig.comptime-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :refer [defnz]]))

(defnz ct-multiplier
  [x :i64 ^:comptime factor :i32 :ret :i64]
  "return x * factor;")

(deftest comptime-specializes-per-value
  (testing "different comptime values compile different libraries"
    (is (= 20 (ct-multiplier 10 2)))
    (is (= 30 (ct-multiplier 10 3)))
    (is (= 50 (ct-multiplier 10 5))))
  (testing "same comptime value reuses the cached library"
    (is (= 40 (ct-multiplier 20 2)))
    (is (= 60 (ct-multiplier 20 3)))))

(deftest comptime-arity-includes-comptime-param
  (is (thrown? clojure.lang.ExceptionInfo
               (ct-multiplier 10)))
  (is (thrown? clojure.lang.ExceptionInfo
               (ct-multiplier 10 2 3))))

(deftest non-comptime-function-still-works
  (eval `(defnz ~'plain-add [~'x :i64 ~'y :i64 :ret :i64] "return x + y;"))
  (is (= 7 ((resolve 'plain-add) 3 4))))

(deftest comptime-nil-value-throws-clear-error
  (is (thrown-with-msg?
       Exception #"cannot be nil"
       (ct-multiplier 10 nil))))

(deftest comptime-must-be-trailing
  (let [ct-k      (with-meta 'k {:comptime true})
        ct-factor (with-meta 'factor {:comptime true})
        code      (try
                    (macroexpand
                     `(defnz ~'bad-ct
                        [~'x :i64 ~ct-k :i32 ~'y :i64 :ret :i64]
                        "return x * k + y;"))
                    :no-throw
                    (catch Exception e
                      ;; The throw happens during macroexpansion, so it is
                      ;; wrapped in a CompilerException; walk the cause chain
                      ;; to recover the diagnostic's ex-data.
                      (->> (iterate #(.getCause ^Throwable %) e)
                           (some #(when (instance? clojure.lang.ExceptionInfo %)
                                    (ex-data %)))
                           :error/code)))]
    (testing "a non-comptime arg after a comptime one is rejected at expansion"
      (is (= :clj-zig/misplaced-comptime code)))
    (testing "the trailing form the suite uses still expands"
      (is (some? (macroexpand
                  `(defnz ~'good-ct
                     [~'x :i64 ~ct-factor :i32 :ret :i64]
                     "return x * factor;")))))))
