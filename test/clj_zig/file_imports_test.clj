(ns clj-zig.file-imports-test
  "A file-mode body that `@import`s other Zig files compiles, round-trips,
  and recompiles when any file in the import graph changes. The imported
  files are reproduced in the cache directory at their resolved positions,
  so relative, subdirectory, and `../` imports resolve as Zig resolves
  them. Fixtures write a body file and its imports under a temp tree and
  reference the body by absolute path."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.core :as core]))

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-file-imports" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    (.getPath f)))

(defn- define! [form]
  (binding [*ns* (the-ns 'clj-zig.file-imports-test)]
    (eval form)))

(defn- the-fn [sym]
  (ns-resolve 'clj-zig.file-imports-test sym))

(deftest body-imports-a-sibling-file
  (testing "a body calling an imported pub fn round-trips"
    (let [dir  (scratch-dir)
          path (write dir "area.zig"
                      (str "const u = @import(\"util.zig\");\n"
                           "pub fn area(r: f64) f64 {\n"
                           "    return u.square(r) * 3.141592653589793;\n"
                           "}\n"))]
      (write dir "util.zig" "pub fn square(x: f64) f64 {\n    return x * x;\n}\n")
      (define! `(core/defnz ~'area [~'r :f64 :ret :f64] {:zig/file ~path}))
      (is (= 12.566370614359172 ((the-fn 'area) 2.0))))))

(deftest body-imports-transitively
  (testing "an imported file that imports another resolves the whole chain"
    (let [dir  (scratch-dir)
          path (write dir "top.zig"
                      (str "const a = @import(\"a.zig\");\n"
                           "pub fn top(x: i64) i64 {\n    return a.bump(x);\n}\n"))]
      (write dir "a.zig"
             (str "const b = @import(\"b.zig\");\n"
                  "pub fn bump(x: i64) i64 {\n    return b.inc(x) + 1;\n}\n"))
      (write dir "b.zig" "pub fn inc(x: i64) i64 {\n    return x + 1;\n}\n")
      (define! `(core/defnz ~'top [~'x :i64 :ret :i64] {:zig/file ~path}))
      (is (= 7 ((the-fn 'top) 5))))))

(deftest body-imports-a-subdirectory-file
  (testing "a subdirectory import and its own sibling resolve"
    (let [dir  (scratch-dir)
          path (write dir "geo.zig"
                      (str "const v = @import(\"math/vec.zig\");\n"
                           "pub fn hyp(a: f64, b: f64) f64 {\n"
                           "    return v.len2(a, b);\n"
                           "}\n"))]
      (write dir "math/vec.zig"
             (str "const s = @import(\"scalar.zig\");\n"
                  "pub fn len2(a: f64, b: f64) f64 {\n"
                  "    return @sqrt(s.sq(a) + s.sq(b));\n"
                  "}\n"))
      (write dir "math/scalar.zig" "pub fn sq(x: f64) f64 {\n    return x * x;\n}\n")
      (define! `(core/defnz ~'hyp [~'a :f64 ~'b :f64 :ret :f64] {:zig/file ~path}))
      (is (= 5.0 ((the-fn 'hyp) 3.0 4.0))))))

(deftest an-import-above-the-body-directory-is-rejected
  (testing "Zig's own module-path rule rejects an import escaping the body's directory"
    (let [dir  (scratch-dir)
          path (write dir "app/geo.zig"
                      (str "const m = @import(\"../lib/math.zig\");\n"
                           "pub fn hyp(a: f64, b: f64) f64 {\n"
                           "    return m.sqrt2(a * a + b * b);\n"
                           "}\n"))]
      (write dir "lib/math.zig" "pub fn sqrt2(x: f64) f64 {\n    return @sqrt(x);\n}\n")
      (is (thrown? clojure.lang.ExceptionInfo
                   (define! `(core/defnz ~'hyp [~'a :f64 ~'b :f64 :ret :f64] {:zig/file ~path})))))))

(deftest editing-an-imported-file-recompiles
  (testing "a change to an imported file picks up on re-evaluation"
    (let [dir  (scratch-dir)
          path (write dir "callsite.zig"
                      (str "const h = @import(\"helper.zig\");\n"
                           "pub fn run(x: i64) i64 {\n    return h.f(x);\n}\n"))]
      (write dir "helper.zig" "pub fn f(x: i64) i64 {\n    return x + 1;\n}\n")
      (define! `(core/defnz ~'run [~'x :i64 :ret :i64] {:zig/file ~path}))
      (is (= 6 ((the-fn 'run) 5)))
      (write dir "helper.zig" "pub fn f(x: i64) i64 {\n    return x + 100;\n}\n")
      (define! `(core/defnz ~'run [~'x :i64 :ret :i64] {:zig/file ~path}))
      (is (= 105 ((the-fn 'run) 5))))))

(deftest a-broken-import-keeps-the-last-good-binding
  (testing "a syntactically broken imported file fails the build and keeps the prior binding"
    (let [dir  (scratch-dir)
          path (write dir "main.zig"
                      (str "const h = @import(\"dep.zig\");\n"
                           "pub fn run(x: i64) i64 {\n    return h.f(x);\n}\n"))]
      (write dir "dep.zig" "pub fn f(x: i64) i64 {\n    return x * 2;\n}\n")
      (define! `(core/defnz ~'run [~'x :i64 :ret :i64] {:zig/file ~path}))
      (is (= 10 ((the-fn 'run) 5)))
      (write dir "dep.zig" "pub fn f(x: i64) i64 {\n    return nonsense;\n}\n")
      (is (thrown? clojure.lang.ExceptionInfo
                   (define! `(core/defnz ~'run [~'x :i64 :ret :i64] {:zig/file ~path}))))
      (is (= 10 ((the-fn 'run) 5))))))

(deftest imported-content-is-part-of-the-cache-key
  (testing "two helpers with the same call site produce distinct artifacts"
    (let [dir1 (scratch-dir)
          dir2 (scratch-dir)
          mk   (fn [dir helper]
                 (let [path (write dir "site.zig"
                                   (str "const h = @import(\"h.zig\");\n"
                                        "pub fn site(x: i64) i64 {\n    return h.g(x);\n}\n"))]
                   (write dir "h.zig" helper)
                   path))
          p1   (mk dir1 "pub fn g(x: i64) i64 {\n    return x + 1;\n}\n")
          p2   (mk dir2 "pub fn g(x: i64) i64 {\n    return x + 2;\n}\n")
          v1   (define! `(core/defnz ~'s1 [~'x :i64 :ret :i64] {:zig/file ~p1 :zig/fn "site"}))
          v2   (define! `(core/defnz ~'s2 [~'x :i64 :ret :i64] {:zig/file ~p2 :zig/fn "site"}))]
      (is (= 6 ((the-fn 's1) 5)))
      (is (= 7 ((the-fn 's2) 5)))
      (is (not= (get-in (meta v1) [:clj-zig/info :library])
                (get-in (meta v2) [:clj-zig/info :library]))))))

(deftest a-body-without-imports-is-unchanged
  (testing "a file body that imports nothing records no aux files"
    (let [dir  (scratch-dir)
          path (write dir "plain.zig" "pub fn plain(x: i64) i64 {\n    return x + 1;\n}\n")
          v    (define! `(core/defnz ~'plain [~'x :i64 :ret :i64] {:zig/file ~path}))]
      (is (= 6 ((the-fn 'plain) 5)))
      (is (nil? (get-in (meta v) [:clj-zig/info :aux-files])))
      (is (nil? (get-in (meta v) [:clj-zig/info :source-reldir]))))))
