(ns clj-zig.file-body-test
  "End-to-end file-sourced bodies: a defnz whose body is a {:zig/file ...}
  descriptor loads a complete Zig `pub fn`, the generated wrapper calls it,
  and the function round-trips. Fixtures are written to a temp directory
  and referenced by absolute path, so resolution is unambiguous; relative
  and classpath resolution are covered in fileref-test."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.core :as core]
            [clj-zig :as zig]))

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-file-body" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-zig [dir name content]
  (let [f (io/file dir name)]
    (spit f content)
    (.getPath f)))

(defn- define! [form]
  (binding [*ns* (the-ns 'clj-zig.file-body-test)]
    (eval form)))

(defn- the-fn [sym]
  (ns-resolve 'clj-zig.file-body-test sym))

(deftest file-mode-scalar-round-trips
  (let [dir  (scratch-dir)
        path (write-zig dir "plus.zig" "pub fn plus(x: i64, y: i64) i64 {\n    return x + y;\n}\n")]
    (define! `(core/defnz ~'plus [~'x :i64 ~'y :i64 :ret :i64] {:zig/file ~path}))
    (is (= 42 ((the-fn 'plus) 20 22)))))

(deftest file-mode-slice-reconstructs-and-munges-the-entry-name
  (testing "a kebab-case Clojure name maps to a snake_case Zig fn"
    (let [dir  (scratch-dir)
          path (write-zig dir "dot.zig"
                          (str "pub fn dot_product(a: []const f64, b: []const f64) f64 {\n"
                               "    var t: f64 = 0;\n"
                               "    var i: usize = 0;\n"
                               "    const n = @min(a.len, b.len);\n"
                               "    while (i < n) : (i += 1) t += a[i] * b[i];\n"
                               "    return t;\n"
                               "}\n"))]
      (define! `(core/defnz ~'dot-product
                  [~'a [:slice :const :f64] ~'b [:slice :const :f64] :ret :f64]
                  {:zig/file ~path}))
      (is (= 32.0 ((the-fn 'dot-product) (double-array [1.0 2.0 3.0])
                                         (double-array [4.0 5.0 6.0])))))))

(deftest file-mode-records-inspection-metadata
  (let [dir  (scratch-dir)
        path (write-zig dir "negate.zig" "pub fn negate(x: i64) i64 {\n    return -x;\n}\n")
        v    (define! `(core/defnz ~'negate [~'x :i64 :ret :i64] {:zig/file ~path}))]
    (testing "source is the file text the user wrote"
      (is (= "pub fn negate(x: i64) i64 {\n    return -x;\n}\n" (zig/source v))))
    (testing "the resolved source file and mode are recorded"
      (is (= path (get-in (meta v) [:clj-zig/info :source-file])))
      (is (= :file (get-in (meta v) [:clj-zig/info :source-mode]))))
    (testing "the generated source carries the user file and a wrapper that calls it"
      (let [gen (zig/generated-source v)]
        (is (str/includes? gen "pub fn negate(x: i64) i64"))
        (is (str/includes? gen "return negate(x);"))))))

(deftest file-mode-recompiles-when-the-file-changes
  (let [dir  (scratch-dir)
        path (write-zig dir "stepper.zig" "pub fn stepper(x: i64) i64 {\n    return x + 1;\n}\n")]
    (define! `(core/defnz ~'stepper [~'x :i64 :ret :i64] {:zig/file ~path}))
    (is (= 6 ((the-fn 'stepper) 5)))
    (testing "editing the file and re-evaluating the form picks up the new body"
      (spit (io/file dir "stepper.zig") "pub fn stepper(x: i64) i64 {\n    return x + 100;\n}\n")
      (define! `(core/defnz ~'stepper [~'x :i64 :ret :i64] {:zig/file ~path}))
      (is (= 105 ((the-fn 'stepper) 5))))))

(deftest file-descriptor-is-an-evaluated-expression
  (testing "the :zig/file path may be a runtime value, not only a literal"
    (let [dir  (scratch-dir)
          path (write-zig dir "ev.zig" "pub fn ev(x: i64) i64 {\n    return x + 7;\n}\n")]
      (define! `(let [p# ~path]
                  (core/defnz ~'ev [~'x :i64 :ret :i64] {:zig/file p#})))
      (is (= 17 ((the-fn 'ev) 10))))))

(deftest public-data-api-drives-file-mode-without-the-macro
  (let [spec (zig/build-spec '{:ns app.core :name dot
                               :signature [a [:slice :const :f64]
                                           b [:slice :const :f64] :ret :f64]})
        user (str "pub fn dot(a: []const f64, b: []const f64) f64 {\n"
                  "    var t: f64 = 0;\n"
                  "    var i: usize = 0;\n"
                  "    const n = @min(a.len, b.len);\n"
                  "    while (i < n) : (i += 1) t += a[i] * b[i];\n"
                  "    return t;\n"
                  "}\n")
        dot  (zig/fn spec user {:mode :file :entry "dot"})]
    (is (= 32.0 (dot (double-array [1.0 2.0 3.0]) (double-array [4.0 5.0 6.0]))))))

(deftest raw-mode-binds-a-hand-written-export-fn
  (testing "no wrapper is generated; :zig/symbol names the bound export fn"
    (let [dir  (scratch-dir)
          path (write-zig dir "raw.zig"
                          "export fn raw_add(x: i64, y: i64) i64 {\n    return x + y;\n}\n")
          v    (define! `(core/defnz ~'r-add [~'x :i64 ~'y :i64 :ret :i64]
                           {:zig/file ~path :zig/raw true :zig/symbol "raw_add"}))]
      (is (= 42 ((the-fn 'r-add) 20 22)))
      (is (= :raw (get-in (meta v) [:clj-zig/info :source-mode])))
      (testing "the generated source is the file as written, with no export wrapper added"
        (is (not (str/includes? (zig/generated-source v) "clj_zig_")))))))

(deftest defz-can-be-file-sourced
  (testing "a shared declaration block loads from a .zig file and bodies call it"
    (let [dir  (scratch-dir)
          path (write-zig dir "helpers.zig" "fn dbl(x: i64) i64 {\n    return x * 2;\n}\n")]
      (define! `(core/defz ~'helpers {:zig/file ~path}))
      (define! `(core/defnz ~'doubled [~'x :i64 :ret :i64] "return dbl(x);"))
      (is (= 42 ((the-fn 'doubled) 21))))))

(deftest entry-name-can-be-overridden-or-required
  (testing ":zig/fn names the entry fn when the Clojure name differs"
    (let [dir  (scratch-dir)
          path (write-zig dir "custom.zig" "pub fn custom(x: i64) i64 {\n    return x * 3;\n}\n")]
      (define! `(core/defnz ~'thrice [~'x :i64 :ret :i64]
                  {:zig/file ~path :zig/fn "custom"}))
      (is (= 21 ((the-fn 'thrice) 7)))))
  (testing "a Clojure name that is not a legal Zig identifier needs :zig/fn"
    (let [dir  (scratch-dir)
          path (write-zig dir "bang.zig" "pub fn bang(x: i64) i64 {\n    return x;\n}\n")
          ex   (try (define! `(core/defnz ~'bang! [~'x :i64 :ret :i64] {:zig/file ~path}))
                    (catch clojure.lang.ExceptionInfo e e)
                    (catch clojure.lang.Compiler$CompilerException e (.getCause e)))]
      (is (= :clj-zig/entry-name-needed (:error/code (ex-data ex)))))))

(deftest trailing-forms-after-the-body-are-rejected
  (testing "stray forms after the Zig body are not silently dropped"
    (let [ex (try (define! `(core/defnz ~'junk [~'x :i64 :ret :i64] "return x;" :extra))
                  (catch clojure.lang.ExceptionInfo e e)
                  (catch clojure.lang.Compiler$CompilerException e (.getCause e)))]
      (is (= :clj-zig/malformed-defnz (:error/code (ex-data ex)))))))
