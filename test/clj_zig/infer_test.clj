(ns clj-zig.infer-test
  "Parsing a Zig pub fn prototype into binding/type data, declaration only."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.infer :as infer]))

(deftest parses-scalar-parameters-and-return
  (is (= {:name "add"
          :params [{:binding "x" :zig-type "i64"}
                   {:binding "y" :zig-type "i64"}]
          :ret "i64"}
         (infer/prototype "pub fn add(x: i64, y: i64) i64 {\n    return x + y;\n}\n" "add"))))

(deftest parses-a-no-parameter-function
  (is (= {:name "now" :params [] :ret "i64"}
         (infer/prototype "pub fn now() i64 {\n    return 0;\n}\n" "now"))))

(deftest parses-void-return
  (is (= {:name "noop" :params [{:binding "x" :zig-type "i64"}] :ret "void"}
         (infer/prototype "pub fn noop(x: i64) void {}" "noop"))))

(deftest keeps-compound-type-strings-intact
  (testing "slices, const, arrays, optionals, pointers, and error unions pass through verbatim"
    (is (= [{:binding "a" :zig-type "[]const f64"}
            {:binding "b" :zig-type "[]u8"}
            {:binding "n" :zig-type "[4]i32"}
            {:binding "o" :zig-type "?f64"}
            {:binding "p" :zig-type "*const u8"}
            {:binding "q" :zig-type "[*]u8"}]
           (:params (infer/prototype
                     (str "pub fn f(a: []const f64, b: []u8, n: [4]i32, "
                          "o: ?f64, p: *const u8, q: [*]u8) Err!void {}")
                     "f"))))
    (is (= "Err!void"
           (:ret (infer/prototype
                  "pub fn f(a: []const f64, b: []u8, n: [4]i32, o: ?f64, p: *const u8, q: [*]u8) Err!void {}"
                  "f"))))))

(deftest tolerates-newlines-and-extra-whitespace
  (is (= {:name "dot"
          :params [{:binding "a" :zig-type "[]const f64"}
                   {:binding "b" :zig-type "[]const f64"}]
          :ret "f64"}
         (infer/prototype "pub fn dot(\n    a: []const f64,\n    b: []const f64,\n) f64 {\n}\n" "dot"))))

(deftest selects-the-named-function-among-several
  (let [src (str "pub fn first(x: i64) i64 {\n    return x;\n}\n"
                 "pub fn second(a: f64, b: f64) f64 {\n    return a + b;\n}\n")]
    (is (= {:binding "a" :zig-type "f64"} (first (:params (infer/prototype src "second")))))
    (is (= "f64" (:ret (infer/prototype src "second"))))))

(deftest parses-a-private-fn-without-pub
  (is (= {:name "helper" :params [{:binding "x" :zig-type "i64"}] :ret "i64"}
         (infer/prototype "fn helper(x: i64) i64 {\n    return x;\n}\n" "helper"))))

(deftest returns-nil-when-the-function-is-absent
  (is (nil? (infer/prototype "pub fn other() void {}" "missing"))))
