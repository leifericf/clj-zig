(ns clj-zig.source-file-mode-test
  "File-mode source generation: the wrapper reconstructs its arguments and
  calls a user-written `pub fn` instead of splicing a body string. Every
  structural case must stay canonical Zig, call the entry fn, carry the
  same boundary shape as inline mode, and emit no inner impl fn."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.gen :as g]
            [clj-zig.source :as source]
            [clj-zig.spec :as spec]))

(defn- zig-fmt-clean? [src]
  (let [f (java.io.File/createTempFile "clj-zig-file-mode" ".zig")]
    (try
      (spit f src)
      (zero? (:exit (sh/sh "zig" "fmt" "--check" (.getPath f))))
      (finally (.delete f)))))

(defn- file-source [case entry]
  (source/generate (spec/build-spec case) nil {:mode :file :entry entry}))

(deftest scalar-file-mode-calls-the-entry-fn-directly
  (is (= (str "export fn clj_zig_app_2e_core_add(x: i64, y: i64) i64 {\n"
              "    return add(x, y);\n"
              "}\n")
         (file-source '{:ns app.core :name add
                        :signature [x :i64 y :i64 :ret :i64]}
                      "add"))))

(deftest slice-file-mode-reconstructs-then-calls
  (is (= (str "export fn clj_zig_app_2e_core_sum(xs_ptr: [*]const f64, xs_len: usize) f64 {\n"
              "    const xs = xs_ptr[0..xs_len];\n"
              "    return sum(xs);\n"
              "}\n")
         (file-source '{:ns app.core :name sum
                        :signature [xs [:slice :const :f64] :ret :f64]}
                      "sum"))))

(deftest void-file-mode-returns-the-void-call
  (is (= (str "export fn clj_zig_app_2e_core_noop() void {\n"
              "    return noop();\n"
              "}\n")
         (file-source '{:ns app.core :name noop :signature [:ret :void]}
                      "noop"))))

(deftest error-union-file-mode-translates-the-error
  (let [out (file-source '{:ns app.core :name divide
                           :signature [a :i64 b :i64 :ret [:error-union DivError :i64]]}
                         "divide")]
    (is (str/includes? out "const __value = divide(a, b) catch |__err_value| {"))
    (is (str/includes? out "__err: [*]u8, __errlen: *usize"))
    (is (not (str/includes? out "__impl")))))

(deftest owned-file-mode-emits-an-inline-import-free-shim
  (let [out (file-source '{:ns app.core :name make
                           :signature [b :u8 n :usize :ret [:owned [:slice :u8]]]}
                         "make")]
    (is (str/includes? out "const __r = make(b, n);"))
    (is (str/includes? out "__ptr.* = @intFromPtr(__r.ptr);"))
    (testing "the free shim imports std inline so a user file's std never collides"
      (is (str/includes? out "@import(\"std\").heap.c_allocator.free(__p[0..__len]);"))
      (is (not (str/includes? out "const std = @import"))))))

(deftest file-mode-matrix-is-fmt-clean-calls-entry-and-has-no-impl
  (let [cases (g/structural-cases)]
    (is (< 80 (count cases)) "the matrix spans the cross-product, not a sample")
    (doseq [case cases]
      (let [s   (spec/build-spec case)
            out (source/generate s nil {:mode :file :entry "userfn"})]
        (testing (pr-str (:signature case))
          (is (zig-fmt-clean? out) "file-mode source is canonical Zig")
          (is (str/includes? out "userfn(") "the wrapper calls the entry fn")
          (is (not (str/includes? out "__impl")) "no inner impl fn in file mode")
          (is (str/includes? out (str "export fn " (:symbol s) "("))))))))
