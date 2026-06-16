(ns clj-zig.scalar-test
  "The first native round-trip: build a spec, generate Zig, compile, load
  through FFM, and call. Drives the lower-level pipeline directly; the
  `defnz` macro wires the same path in the next commit."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.compile :as compile]
            [clj-zig.ffm :as ffm]
            [clj-zig.source :as source]
            [clj-zig.spec :as spec])
  (:import (java.math BigInteger)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- scratch-dir []
  (str (Files/createTempDirectory "clj-zig-scalar" (make-array FileAttribute 0))))

(defn- native-fn
  "Compile `body` for `signature` and return the bound Clojure fn."
  [fn-name signature body]
  (let [spec   (spec/build-spec {:ns 'app.core :name fn-name :signature signature})
        dir    (scratch-dir)
        result (compile/compile!
                {:source (source/generate spec body)
                 :source-path (str dir "/source.zig")
                 :library-path (str dir "/lib" fn-name "."
                                    (compile/dynamic-library-extension))
                 :ctx {:var (symbol "app.core" (str fn-name)) :signature signature}})]
    (ffm/bind spec (:library result))))

(deftest calls-i64-function
  (let [add (native-fn 'add '[x :i64 y :i64 :ret :i64] "return x + y;")]
    (is (= 42 (add 20 22)))
    (is (instance? Long (add 20 22)))
    (is (= -5 (add 5 -10)))))

(deftest calls-f64-function
  (let [add (native-fn 'addf '[x :f64 y :f64 :ret :f64] "return x + y;")]
    (is (= 3.75 (add 1.5 2.25)))
    (is (instance? Double (add 1.5 2.25)))))

(deftest calls-bool-function
  (let [negate (native-fn 'negate '[x :bool :ret :bool] "return !x;")]
    (is (false? (negate true)))
    (is (true? (negate false)))))

(deftest converts-void-to-nil
  (let [noop (native-fn 'noop '[x :i64 :ret :void] "_ = x;")]
    (is (nil? (noop 5)))))

(deftest calls-i32-function
  (let [negate (native-fn 'negi '[x :i32 :ret :i32] "return -x;")]
    (is (= -7 (negate 7)))
    (is (= 7 (negate -7)))))

(deftest unsigned-u8-wraps-within-range
  (let [inc8 (native-fn 'inc8 '[x :u8 :ret :u8] "return x +% 1;")]
    (is (= 0 (inc8 255)))
    (is (= 128 (inc8 127)))))

(deftest large-u64-return-promotes-to-biginteger
  (testing "a u64 beyond the signed range comes back exact, never negative"
    (let [maxu64 (native-fn 'maxu64 '[:ret :u64] "return 18446744073709551615;")
          result (maxu64)]
      (is (= (biginteger "18446744073709551615") result))
      (is (instance? BigInteger result)))))

(deftest u64-in-signed-range-stays-long
  (let [identity64 (native-fn 'idu64 '[x :u64 :ret :u64] "return x;")]
    (is (= 42 (identity64 42)))
    (is (instance? Long (identity64 42)))))

(deftest usize-round-trips
  (testing ":usize shares the 64-bit unsigned carrier with :u64"
    (let [idu (native-fn 'idusize '[x :usize :ret :usize] "return x;")]
      (is (= 42 (idu 42)))
      (is (instance? Long (idu 42))))))

(deftest large-u64-argument-round-trips
  (testing "a BigInteger argument crosses without truncation"
    (let [identity64 (native-fn 'idu64b '[x :u64 :ret :u64] "return x;")
          big (biginteger "18446744073709551615")]
      (is (= big (identity64 big))))))

(deftest wrong-arity-is-a-clear-error
  (let [add (native-fn 'add2 '[x :i64 y :i64 :ret :i64] "return x + y;")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Wrong number of arguments"
                          (add 1)))))
