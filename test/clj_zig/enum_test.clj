(ns clj-zig.enum-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig :as zig]
            [clj-zig.core :refer [defnz defenumz]]))

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
    (is (:clj-zig/type-layout (meta #'ParseStatus)))
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

;; --- A backed enum (u8 tag) ---------------------------------------------

(defenumz CompactTag
  [red 0
   green 1
   blue 2]
  {:backing :u8})

(defnz flag-for
  [c :u8
   :ret CompactTag]
  "return switch (c) { 0 => .red, 1 => .green, else => .blue };")

(defnz tag-code
  [t CompactTag
   :ret :u8]
  "return @intFromEnum(t);")

(deftest a-backed-enum-round-trips-at-its-backing-width
  (testing "a u8-backed enum returns its member keyword"
    (is (= :red (flag-for 0)))
    (is (= :blue (flag-for 99))))
  (testing "a u8-backed enum crosses as an argument at byte width"
    (is (= 1 (tag-code :green))))
  (testing "the generated preamble declares the backing width"
    (is (str/includes? (zig/generated-source #'flag-for)
                       "const CompactTag = enum(u8) {"))))
