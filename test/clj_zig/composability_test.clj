(ns clj-zig.composability-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig :as zig]
            [clj-zig.core :refer [defnz]]))

;; --- Type builders are ordinary functions over data ---------------------

(defn- slice-of [t] [:slice t])
(defn- const-slice-of [t] [:slice :const t])
(def ^:private byte-slice (const-slice-of :u8))

(deftest a-type-builder-produces-data-the-pipeline-accepts
  (testing "a builder returns a plain compound vector"
    (is (= [:slice :u8] (slice-of :u8)))
    (is (= [:slice :const :u8] byte-slice)))
  (testing "the built form normalizes like any other type"
    (is (= {:kind :slice :const? true :of {:kind :scalar :name :u8}}
           (zig/normalize-type byte-slice)))))

(defnz count-needle
  [input byte-slice
   needle :u8
   :ret :usize]
  "var n: usize = 0;
   for (input) |b| {
       if (b == needle) n += 1;
   }
   return n;")

(deftest a-built-type-flows-through-defnz
  (is (= 3 (count-needle (byte-array (.getBytes "banana" "UTF-8"))
                         (byte (int \a))))))

;; --- The data pipeline reaches a callable without the macro -------------

(deftest the-pipeline-composes-without-the-macro
  (testing "build-spec then fn yields a working native function"
    (let [spec (zig/build-spec '{:ns app.core :name mul
                                 :signature [x :i64 y :i64 :ret :i64]})
          mul  (zig/fn spec "return x * y;")]
      (is (= 42 (mul 6 7)))))
  (testing "generate-source is a pure step over the same spec"
    (let [spec (zig/build-spec '{:ns app.core :name mul
                                 :signature [x :i64 y :i64 :ret :i64]})]
      (is (re-find #"return x \* y;" (zig/generate-source spec "return x * y;"))))))

;; --- A user macro generates defnz forms ---------------------------------

(defmacro defbinaryz [name op t]
  `(defnz ~name
     [x ~t y ~t :ret ~t]
     ~(str "return x " op " y;")))

(defbinaryz add-f64 "+" :f64)
(defbinaryz mul-i64 "*" :i64)

(deftest a-user-macro-generates-working-functions
  (is (= 5.0 (add-f64 2.0 3.0)))
  (is (= 12 (mul-i64 3 4))))
