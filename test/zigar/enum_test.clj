(ns zigar.enum-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zigar :as zig]
            [zigar.core :refer [defnz defenumz]]))

(defenumz ParseStatus
  "The outcome of parsing one byte."
  [ok 0
   invalid 1
   eof 2])

(deftest defenumz-registers-an-enum-descriptor
  (testing "the Var holds the enum layout descriptor"
    (is (= 'ParseStatus (:name ParseStatus)))
    (is (:enum ParseStatus))
    (is (= [0 1 2] (mapv :value (:values ParseStatus)))))
  (testing "the descriptor is marked and documented on the Var"
    (is (:zigar/type-layout (meta #'ParseStatus)))
    (is (= "The outcome of parsing one byte." (:doc (meta #'ParseStatus))))))

;; --- An enum as a return ------------------------------------------------

(defnz classify
  [c :u8
   :ret ParseStatus]
  "return if (c == 0) .eof else if (c < 10) .ok else .invalid;")

(deftest an-enum-return-is-a-member-keyword
  (is (= :eof (classify 0)))
  (is (= :ok (classify 5)))
  (is (= :invalid (classify 50))))

;; --- An enum as an argument ---------------------------------------------

(defnz advance
  [s ParseStatus
   :ret ParseStatus]
  "return switch (s) { .ok => .invalid, .invalid => .eof, .eof => .ok };")

(deftest an-enum-argument-is-a-member-keyword
  (testing "a member keyword crosses as its backing value"
    (is (= :invalid (advance :ok)))
    (is (= :eof (advance :invalid)))
    (is (= :ok (advance :eof))))
  (testing "a keyword that names no member is a clear diagnostic"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a member"
                          (advance :nope)))))

(deftest the-enum-decl-appears-in-the-generated-preamble
  (is (str/includes? (zig/generated-source #'classify)
                     "const ParseStatus = enum(i32) {")))
