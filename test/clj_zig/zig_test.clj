(ns clj-zig.zig-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clj-zig.zig :as zig]))

;; --- Expression rendering ------------------------------------------------

(deftest render-ref
  (is (= "x" (zig/render-expr-for-test (zig/ref "x")))))

(deftest render-field-chain
  (is (= "__r.flag"
         (zig/render-expr-for-test
          (zig/field (zig/ref "__r") "flag")))))

(deftest render-deref
  (is (= "__ret.*"
         (zig/render-expr-for-test
          (zig/deref (zig/ref "__ret"))))))

(deftest render-call
  (is (= "@intFromPtr(__r.msg.ptr)"
         (zig/render-expr-for-test
          (zig/call "@intFromPtr"
                    [(zig/field (zig/field (zig/ref "__r") "msg") "ptr")])))))

(deftest render-lit
  (is (= "42" (zig/render-expr-for-test (zig/lit "42")))))

(deftest render-as
  (is (= "@as([*]u8, ptr)"
         (zig/render-expr-for-test
          (zig/as "[*]u8" (zig/ref "ptr"))))))

(deftest render-slice
  (is (= "xs_ptr[0..xs_len]"
         (zig/render-expr-for-test
          (zig/slice (zig/ref "xs_ptr") (zig/lit "0") (zig/ref "xs_len"))))))

(deftest render-raw-expr
  (is (= "complex + thing"
         (zig/render-expr-for-test
          (zig/raw-expr "complex + thing")))))

;; --- Statement rendering -------------------------------------------------

(deftest render-const-stmt
  (is (= "    const xs = xs_ptr[0..xs_len];"
         (zig/render-stmt-for-test
          (zig/const-stmt "xs"
                          (zig/slice (zig/ref "xs_ptr") (zig/lit "0") (zig/ref "xs_len")))
          1))))

(deftest render-typed-const-stmt
  (is (= "    const __nice: Type = init;"
         (zig/render-stmt-for-test
          (zig/const-stmt "__nice" "Type" (zig/ref "init")) 1))))

(deftest render-assign-stmt
  (is (= "    __ret.*.flag = __r.flag;"
         (zig/render-stmt-for-test
          (zig/assign-stmt
           (zig/field (zig/deref (zig/ref "__ret")) "flag")
           (zig/field (zig/ref "__r") "flag"))
          1))))

(deftest render-return-stmt
  (is (= "    return __value;"
         (zig/render-stmt-for-test
          (zig/return-stmt (zig/ref "__value")) 1)))
  (is (= "    return;"
         (zig/render-stmt-for-test
          (zig/return-stmt) 1))))

(deftest render-if-stmt
  (is (= (str "    if (cond) {\n"
              "        __out.* = __val;\n"
              "        return true;\n"
              "    }")
         (zig/render-stmt-for-test
          (zig/if-stmt (zig/ref "cond")
                       [(zig/assign-stmt
                         (zig/deref (zig/ref "__out"))
                         (zig/ref "__val"))
                        (zig/return-stmt (zig/lit "true"))])
          1))))

(deftest render-for-stmt
  (is (= (str "    for (__nice, 0..) |*__dst, __i| {\n"
              "        __dst.field = __src.field;\n"
              "    }")
         (zig/render-stmt-for-test
          (zig/for-stmt "(__nice, 0..) |*__dst, __i|"
                        [(zig/assign-stmt
                          (zig/field (zig/ref "__dst") "field")
                          (zig/field (zig/ref "__src") "field"))])
          1))))

(deftest render-defer-stmt
  (is (= "    defer std.heap.c_allocator.free(__nice);"
         (zig/render-stmt-for-test
          (zig/defer-stmt
           (zig/call "std.heap.c_allocator.free" [(zig/ref "__nice")]))
          1))))

(deftest render-assign-to-deref
  (is (= "    __errlen.* = 0;"
         (zig/render-stmt-for-test
          (zig/assign-stmt (zig/deref (zig/ref "__errlen")) (zig/lit "0"))
          1))))

(deftest render-raw-stmt-multiline
  (is (= (str "    const __value = impl(x) catch |e| {\n"
              "        return undefined;\n"
              "    };")
         (zig/render-stmt-for-test
          (zig/raw-stmt
           (str "const __value = impl(x) catch |e| {\n"
                "    return undefined;\n"
                "};"))
          1))))

;; --- Declaration rendering -----------------------------------------------

(deftest render-export-fn
  (is (= (str "export fn add(x: i64, y: i64) i64 {\n"
              "    return x + y;\n"
              "}")
         (zig/render [(zig/export-fn-decl
                       "add"
                       [(zig/param "x" "i64") (zig/param "y" "i64")]
                       "i64"
                       [(zig/raw-stmt "return x + y;")])]))))

(deftest render-non-export-fn
  (is (= (str "fn add__impl(x: i64) i64 {\n"
              "    return x;\n"
              "}")
         (zig/render [(zig/fn-decl
                       "add__impl"
                       [(zig/param "x" "i64")]
                       "i64"
                       [(zig/raw-stmt "return x;")])]))))

(deftest render-extern-struct
  (is (= (str "const Point = extern struct {\n"
              "    x: f64,\n"
              "    y: f64,\n"
              "};")
         (zig/render [(zig/extern-struct-decl
                       "Point"
                       [(zig/field-data "x" "f64")
                        (zig/field-data "y" "f64")])]))))

(deftest render-enum
  (is (= (str "const Tag = enum(i32) {\n"
              "    a = 0,\n"
              "    b = 1,\n"
              "};")
         (zig/render [(zig/enum-decl "Tag" "i32"
                                    [{:name "a" :value 0}
                                     {:name "b" :value 1}])]))))

(deftest render-top-level-const
  (is (= "const std = @import(\"std\");"
         (zig/render [(zig/const-decl "std"
                                      (zig/raw-expr "@import(\"std\")"))]))))

(deftest render-multiple-decls-separated
  (let [out (zig/render [(zig/const-decl "std" (zig/raw-expr "@import(\"std\")"))
                         (zig/export-fn-decl "foo" [] "void" [])])]
    (is (str/includes? out "const std = @import(\"std\");\n\nexport fn foo() void {"))))

(deftest render-raw-decl
  (is (= "fn helper(x: i64) i64 { return x * 2; }"
         (zig/render [(zig/raw-decl "fn helper(x: i64) i64 { return x * 2; }")]))))
