(ns clj-zig.source-file-mode-test
  "File-mode source generation: the wrapper reconstructs its arguments and
  calls a user-written `pub fn` instead of splicing a body string. Every
  structural case must stay canonical Zig, call the entry fn, carry the
  same boundary shape as inline mode, and emit no inner impl fn."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.compile :as compile]
            [clj-zig.gen :as g]
            [clj-zig.layout :as layout]
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

;; --- Owned/borrowed result records in file mode (doc 10 Phase 2) ---------

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-record-file" (make-array java.nio.file.attribute.FileAttribute 0))))

(def ^:private record-layout
  (layout/describe 'RenderResult '[flag :u32 msg :string]))

(defn- record-spec [ownership]
  {:ns     'app.core
   :name   'render
   :symbol (spec/symbol-name 'app.core 'render)
   :params []
   :ret    {:kind ownership
            :of   {:kind :named :name 'RenderResult :layout record-layout}}})

(deftest owned-record-file-mode-emits-wire-wrapper-and-inline-std-free-shim
  (let [out (source/generate (record-spec :owned) nil
                             {:mode :file :entry "render"})]
    (testing "no top-level std import; the shim imports std inline"
      (is (not (str/includes? out "const std = @import")))
      (is (str/includes? out "@import(\"std\").heap.c_allocator.free")))
    (testing "the wrapper calls the entry fn and writes every wire field"
      (is (str/includes? out "export fn clj_zig_app_2e_core_render(__ret: *RenderResult__wire) void {"))
      (is (str/includes? out "const __r = render();"))
      (is (str/includes? out "__ret.*.flag = __r.flag;"))
      (is (str/includes? out "__ret.*.msg_ptr = @intFromPtr(__r.msg.ptr);"))
      (is (str/includes? out "__ret.*.msg_len = __r.msg.len;")))
    (testing "the free shim reads ptr/len back off the wire struct"
      (is (str/includes? out
                         (str "export fn clj_zig_app_2e_core_render__free"
                              "(__ret: *const RenderResult__wire) void {")))
      (is (str/includes? out
                         "@import(\"std\").heap.c_allocator.free(@as([*]u8, @ptrFromInt(__ret.msg_ptr))[0..__ret.msg_len]);")))
    (testing "the generated source is canonical Zig"
      (is (zig-fmt-clean? out)))))

(deftest borrowed-record-file-mode-emits-wire-wrapper-without-a-shim
  (let [out (source/generate (record-spec :borrowed) nil
                             {:mode :file :entry "render"})]
    (testing "no std anywhere"
      (is (not (str/includes? out "std"))))
    (testing "the wrapper calls the entry fn and writes every wire field"
      (is (str/includes? out "const __r = render();"))
      (is (str/includes? out "__ret.*.flag = __r.flag;"))
      (is (str/includes? out "__ret.*.msg_ptr = @intFromPtr(__r.msg.ptr);"))
      (is (str/includes? out "__ret.*.msg_len = __r.msg.len;")))
    (testing "no free shim"
      (is (not (str/includes? out "__free"))))
    (testing "the generated source is canonical Zig"
      (is (zig-fmt-clean? out)))))

(deftest owned-record-file-mode-wrapper-compiles
  (let [layout (layout/describe 'RenderResult
                                '[flag :u32
                                  msg  :string
                                  raw  [:bytes [:slice :u8]]
                                  xs   [:slice :i64]])
        spec   {:ns     'app.core
                :name   'render
                :symbol (spec/symbol-name 'app.core 'render)
                :params []
                :ret    {:kind :owned
                         :of   {:kind :named :name 'RenderResult :layout layout}}}
        nice   (str "const RenderResult = struct {\n"
                    "    flag: u32,\n"
                    "    msg: []u8,\n"
                    "    raw: []u8,\n"
                    "    xs: []i64,\n"
                    "};\n\n"
                    "pub fn render() RenderResult {\n"
                    "    return .{ .flag = 1, .msg = &[_]u8{}, .raw = &[_]u8{}, .xs = &[_]i64{} };\n"
                    "}\n\n")
        dir    (scratch-dir)]
    (is (.exists (io/file
                  (:library
                   (compile/compile!
                    {:source       (str nice (source/generate spec nil {:mode :file :entry "render"}))
                     :source-path  (str dir "/source.zig")
                     :library-path (str dir "/librender." (compile/dynamic-library-extension))
                     :ctx          {:var 'app.core/render}}))))
        "the file-mode owned-record wrapper and inline-std free shim build")))
