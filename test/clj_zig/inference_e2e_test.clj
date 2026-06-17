(ns clj-zig.inference-e2e-test
  "A signatureless defnz infers its boundary contract from the matching
  `pub fn` in the co-located `.zig`, then compiles and round-trips like any
  other. A return whose policy is not in the Zig type, and a missing pub
  fn, fail with a clear diagnostic. Fixtures bind `*file*` to a defining
  path and write a sibling `.zig`."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.core :as core]))

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-inference" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-zig [dir name content]
  (let [f (io/file dir name)]
    (spit f content)
    (.getPath f)))

(defn- define! [file form]
  (binding [*ns* (the-ns 'clj-zig.inference-e2e-test)
            *file* file]
    (eval form)))

(defn- the-fn [sym]
  (ns-resolve 'clj-zig.inference-e2e-test sym))

(defn- ex-of [thunk]
  (try (thunk)
       (catch clojure.lang.ExceptionInfo e e)
       (catch clojure.lang.Compiler$CompilerException e (.getCause e))))

(deftest infers-an-all-scalar-signature
  (let [dir (scratch-dir)
        clj (str (io/file dir "inf.clj"))]
    (write-zig dir "inf.zig" "pub fn add(x: i64, y: i64) i64 {\n    return x + y;\n}\n")
    (let [v (define! clj `(core/defnz ~'add))]
      (testing "the function round-trips with no signature given"
        (is (= 7 ((the-fn 'add) 3 4))))
      (testing "the inferred arglist takes the Zig parameter names"
        (is (= '([x y]) (:arglists (meta v))))))))

(deftest infers-a-slice-argument
  (let [dir (scratch-dir)
        clj (str (io/file dir "inf.clj"))]
    (write-zig dir "inf.zig"
               (str "pub fn total(xs: []const f64) f64 {\n"
                    "    var t: f64 = 0;\n    for (xs) |x| t += x;\n    return t;\n}\n"))
    (define! clj `(core/defnz ~'total))
    (is (= 6.0 ((the-fn 'total) (double-array [1.0 2.0 3.0]))))))

(deftest infers-a-named-struct-argument
  (let [dir (scratch-dir)
        clj (str (io/file dir "inf.clj"))]
    (write-zig dir "inf.zig"
               "pub fn norm(p: Point) f64 {\n    return @sqrt(p.x * p.x + p.y * p.y);\n}\n")
    (define! clj `(core/deftypez ~'Point [~'x :f64 ~'y :f64]))
    (define! clj `(core/defnz ~'norm))
    (is (= 5.0 ((the-fn 'norm) {:x 3.0 :y 4.0})))))

(deftest a-policy-laden-return-demands-an-explicit-signature
  (let [dir (scratch-dir)
        clj (str (io/file dir "inf.clj"))]
    (write-zig dir "inf.zig"
               (str "const std = @import(\"std\");\n"
                    "pub fn make_bytes(n: usize) []u8 {\n"
                    "    return std.heap.c_allocator.alloc(u8, n) catch @panic(\"oom\");\n}\n"))
    (testing "a returned []u8 cannot infer its ownership policy"
      (let [ex (ex-of #(define! clj `(core/defnz ~'make-bytes)))]
        (is (= :clj-zig/contract-policy-needed (:error/code (ex-data ex))))))))

(deftest a-missing-pub-fn-is-reported
  (let [dir (scratch-dir)
        clj (str (io/file dir "inf.clj"))]
    (write-zig dir "inf.zig" "pub fn present() void {}\n")
    (let [ex (ex-of #(define! clj `(core/defnz ~'absent)))]
      (is (= :clj-zig/inferred-fn-not-found (:error/code (ex-data ex)))))))
