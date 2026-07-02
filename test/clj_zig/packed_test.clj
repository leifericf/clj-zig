(ns clj-zig.packed-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clj-zig.core :refer [defnz deftypez]]
            [clj-zig.layout :as layout]
            [clj-zig.zig :as zig]))

(deftypez RGB
  [r :u8 g :u8 b :u8]
  {:packed true})

(deftypez RGBA
  [r :u8 g :u8 b :u8 a :u8]
  {:packed true})

(deftest packed-struct-has-no-padding
  (testing "three u8 fields yield a 3-byte struct"
    (is (= 3 (:size RGB))))
  (testing "four u8 fields yield a 4-byte struct"
    (is (= 4 (:size RGBA))))
  (testing "alignment is 1 for a packed struct"
    (is (= 1 (:align RGB)))))

(deftest packed-struct-field-offsets-are-sequential
  (let [fields (:fields RGB)]
    (is (= [0 1 2] (map :offset fields)))))

(deftest packed-struct-emits-packed-zig
  (testing "the Zig declaration uses packed struct, not extern struct"
    (is (str/includes? (zig/render [(layout/zig-decl RGB)]) "packed struct")))
  (testing "a non-packed struct still uses extern struct"
    (let [desc (layout/describe 'P2 ['x :f64 'y :f64])]
      (is (str/includes? (zig/render [(layout/zig-decl desc)]) "extern struct")))))

(deftest packed-struct-crosses-the-boundary
  (let [desc (layout/describe 'Px ['a :u8 'b :u8 'c :u8] {} {:packed true})]
    (is (= 3 (:size desc)))
    (is (= 1 (:align desc)))))

(deftest packed-struct-rejects-buffer-fields
  (testing "a buffer field in a packed struct is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (layout/describe 'Bad
                                  [:buf :string]
                                  {}
                                  {:packed true})))))
