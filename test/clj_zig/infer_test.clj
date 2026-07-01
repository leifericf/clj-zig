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

(deftest maps-scalar-types
  (is (= :i64 (infer/zig-type->boundary "i64")))
  (is (= :f64 (infer/zig-type->boundary "f64")))
  (is (= :bool (infer/zig-type->boundary "bool")))
  (is (= :void (infer/zig-type->boundary "void")))
  (is (= :usize (infer/zig-type->boundary "usize")))
  (testing "noreturn is a scalar, not a named-type symbol"
    (is (= :noreturn (infer/zig-type->boundary "noreturn")))
    (is (= :noreturn (infer/zig-type->boundary "noreturn" :return)))))

(deftest maps-compound-types
  (is (= [:slice :const :f64] (infer/zig-type->boundary "[]const f64")))
  (is (= [:slice :u8] (infer/zig-type->boundary "[]u8")))
  (is (= [:array 4 :i32] (infer/zig-type->boundary "[4]i32")))
  (is (= [:optional :f64] (infer/zig-type->boundary "?f64")))
  (is (= [:ptr :const :u8] (infer/zig-type->boundary "*const u8")))
  (is (= [:ptr :i64] (infer/zig-type->boundary "*i64")))
  (is (= [:manyptr :u8] (infer/zig-type->boundary "[*]u8")))
  (is (= [:error-union 'Err :void] (infer/zig-type->boundary "Err!void"))))

(deftest maps-a-named-type-to-a-symbol
  (is (= 'Point (infer/zig-type->boundary "Point"))))

(deftest a-return-shape-is-determined-for-by-value-types
  (testing "scalars, arrays, optionals, and named returns need no policy"
    (is (= :f64 (infer/zig-type->boundary "f64" :return)))
    (is (= [:array 4 :f32] (infer/zig-type->boundary "[4]f32" :return)))
    (is (= [:optional :i64] (infer/zig-type->boundary "?i64" :return)))
    (is (= 'Point (infer/zig-type->boundary "Point" :return)))))

(deftest a-returned-slice-or-pointer-needs-a-policy
  (testing "ownership of a []T and handle-vs-ptr of a *T are not in the type"
    (is (= :clj-zig.infer/policy-needed (infer/zig-type->boundary "[]u8" :return)))
    (is (= :clj-zig.infer/policy-needed (infer/zig-type->boundary "[]const u8" :return)))
    (is (= :clj-zig.infer/policy-needed (infer/zig-type->boundary "[]const f64" :return)))
    (is (= :clj-zig.infer/policy-needed (infer/zig-type->boundary "*Point" :return)))
    (is (= :clj-zig.infer/policy-needed (infer/zig-type->boundary "[*]u8" :return)))))

(deftest a-const-u8-slice-parameter-infers-to-string
  (testing "a []const u8 parameter is a string argument (always const)"
    (is (= :string (infer/zig-type->boundary "[]const u8")))
    (is (= :string (infer/zig-type->boundary "[]const u8" :param))))
  (testing "only const u8 is promoted; other u8 and slice shapes keep their form"
    (is (= [:slice :u8] (infer/zig-type->boundary "[]u8")))                 ; mutable u8, not a string
    (is (= [:slice :const :f64] (infer/zig-type->boundary "[]const f64")))) ; only u8 promoted
  (testing "a []const u8 return still needs an ownership policy"
    (is (= :clj-zig.infer/policy-needed (infer/zig-type->boundary "[]const u8" :return)))))

(deftest a-malformed-parameter-surfaces-as-data
  (testing "a parameter with no colon is a structured diagnostic, not a raw index error"
    (let [ex (try (infer/prototype "pub fn f(x: i64, nope) void {}" "f")
                  (catch clojure.lang.ExceptionInfo e e)
                  (catch Throwable t t))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= :clj-zig/malformed-parameter (:error/code (ex-data ex)))))))
