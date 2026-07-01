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

(deftest closure-of-a-body-with-no-imports-is-empty
  (let [dir  (scratch-dir)
        body (write dir "geometry.zig" "pub fn f() void {}\n")]
    (is (= [] (rels (imports/closure body (slurp body)))))))

(deftest closure-follows-a-transitive-chain
  (let [dir  (scratch-dir)
        body (write dir "a.zig" "const b = @import(\"b.zig\");\npub fn f() void {}\n")]
    (write dir "b.zig" "const c = @import(\"c.zig\");\n")
    (write dir "c.zig" "pub const K = 1;\n")
    (testing "the body is inlined, not copied; its imports follow transitively"
      (is (= ["b.zig" "c.zig"] (rels (imports/closure body (slurp body))))))))

(deftest closure-collapses-a-diamond
  (let [dir  (scratch-dir)
        body (write dir "a.zig" "const b = @import(\"b.zig\");\nconst c = @import(\"c.zig\");\n")]
    (write dir "b.zig" "const d = @import(\"d.zig\");\n")
    (write dir "c.zig" "const d = @import(\"d.zig\");\n")
    (write dir "d.zig" "pub const K = 1;\n")
    (is (= ["b.zig" "c.zig" "d.zig"] (rels (imports/closure body (slurp body)))))))

(deftest closure-tolerates-a-cycle
  (let [dir  (scratch-dir)
        body (write dir "a.zig" "const b = @import(\"b.zig\");\n")]
    (write dir "b.zig" "const a = @import(\"a.zig\");\n")
    (testing "b imports a back; a is the inlined body, so only b is copied"
      (is (= ["b.zig"] (rels (imports/closure body (slurp body))))))))

(deftest closure-keeps-subdirectory-imports
  (let [dir  (scratch-dir)
        body (write dir "geometry.zig" "const v = @import(\"math/vec.zig\");\n")]
    (write dir "math/vec.zig" "const s = @import(\"scalar.zig\");\n")
    (write dir "math/scalar.zig" "pub const K = 1;\n")
    (testing "a subdirectory import and its sibling keep their relative paths"
      (is (= ["math/scalar.zig" "math/vec.zig"] (rels (imports/closure body (slurp body))))))))

(deftest closure-drops-an-import-above-the-body-directory
  (let [dir  (scratch-dir)
        body (write dir "app/geometry.zig" "const m = @import(\"../lib/math.zig\");\n")]
    (write dir "lib/math.zig" "pub const PI = 3.14;\n")
    (testing "an import outside the body's directory is left for zig to reject"
      (is (= [] (rels (imports/closure body (slurp body))))))))

(deftest closure-keeps-a-sibling-whose-name-starts-with-two-dots
  ;; A directory literally named `..drafts` is inside the body's directory;
  ;; it relativizes to `..drafts/util.zig`, which starts with `..` but is not
  ;; an escape by segment. It must be kept, not dropped as if it climbed out.
  (let [dir  (scratch-dir)
        body (write dir "geometry.zig" "const d = @import(\"..drafts/util.zig\");\n")]
    (write dir "..drafts/util.zig" "pub const K = 1;\n")
    (is (= ["..drafts/util.zig"] (rels (imports/closure body (slurp body)))))))

(deftest closure-leaves-a-missing-import-for-the-compiler
  (let [dir  (scratch-dir)
        body (write dir "a.zig" "const gone = @import(\"missing.zig\");\n")]
    (testing "an unresolved import is not in the closure; zig reports it"
      (is (= [] (rels (imports/closure body (slurp body))))))))
