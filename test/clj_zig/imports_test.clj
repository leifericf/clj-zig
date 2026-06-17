(ns clj-zig.imports-test
  "Scanning a body for relative `@import` targets and resolving the
  transitive import graph into paths relative to its common ancestor."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.imports :as imports]))

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-imports" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    (.getPath f)))

(defn- rels [{:keys [files]}]
  (mapv :rel files))

(deftest scan-finds-relative-zig-imports
  (testing "a relative .zig target is an import to reproduce"
    (is (= ["util.zig"] (imports/scan "const u = @import(\"util.zig\");"))))
  (testing "subdirectory targets keep their path"
    (is (= ["math/vec.zig"] (imports/scan "const v = @import(\"math/vec.zig\");"))))
  (testing "compiler modules and non-.zig targets are skipped"
    (is (= [] (imports/scan "const s = @import(\"std\"); const b = @import(\"builtin\");"))))
  (testing "@cImport and @cInclude are not @import"
    (is (= [] (imports/scan "const c = @cImport({ @cInclude(\"math.h\"); });"))))
  (testing "duplicate imports collapse"
    (is (= ["a.zig"] (imports/scan "@import(\"a.zig\"); @import(\"a.zig\");"))))
  (testing "whitespace inside the call is tolerated"
    (is (= ["a.zig"] (imports/scan "@import(\n  \"a.zig\"\n)")))))

(deftest closure-of-a-bodyless-import-is-just-the-body
  (let [dir  (scratch-dir)
        body (write dir "geometry.zig" "pub fn f() void {}\n")
        out  (imports/closure body (slurp body))]
    (is (= "" (:source-reldir out)))
    (is (= ["geometry.zig"] (rels out)))))

(deftest closure-follows-a-transitive-chain
  (let [dir  (scratch-dir)
        body (write dir "a.zig" "const b = @import(\"b.zig\");\npub fn f() void {}\n")]
    (write dir "b.zig" "const c = @import(\"c.zig\");\n")
    (write dir "c.zig" "pub const K = 1;\n")
    (is (= ["a.zig" "b.zig" "c.zig"] (rels (imports/closure body (slurp body)))))))

(deftest closure-collapses-a-diamond
  (let [dir  (scratch-dir)
        body (write dir "a.zig" "const b = @import(\"b.zig\");\nconst c = @import(\"c.zig\");\n")]
    (write dir "b.zig" "const d = @import(\"d.zig\");\n")
    (write dir "c.zig" "const d = @import(\"d.zig\");\n")
    (write dir "d.zig" "pub const K = 1;\n")
    (is (= ["a.zig" "b.zig" "c.zig" "d.zig"] (rels (imports/closure body (slurp body)))))))

(deftest closure-tolerates-a-cycle
  (let [dir  (scratch-dir)
        body (write dir "a.zig" "const b = @import(\"b.zig\");\n")]
    (write dir "b.zig" "const a = @import(\"a.zig\");\n")
    (is (= ["a.zig" "b.zig"] (rels (imports/closure body (slurp body)))))))

(deftest closure-spans-parent-directories
  (let [dir  (scratch-dir)
        body (write dir "app/geometry.zig" "const m = @import(\"../lib/math.zig\");\n")]
    (write dir "lib/math.zig" "pub const PI = 3.14;\n")
    (let [out (imports/closure body (slurp body))]
      (testing "the common ancestor places the body under its real directory"
        (is (= "app" (:source-reldir out)))
        (is (= ["app/geometry.zig" "lib/math.zig"] (rels out)))))))

(deftest closure-leaves-a-missing-import-for-the-compiler
  (let [dir  (scratch-dir)
        body (write dir "a.zig" "const gone = @import(\"missing.zig\");\n")]
    (testing "an unresolved import is not in the closure; zig reports it"
      (is (= ["a.zig"] (rels (imports/closure body (slurp body))))))))
