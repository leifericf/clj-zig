(ns clj-zig.source-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.compile :as compile]
            [clj-zig.layout :as layout]
            [clj-zig.source :as source]
            [clj-zig.spec :as spec]))

(defn- zig-fmt-clean?
  "True when `src` already passes `zig fmt --check`, i.e. it is canonical
  Zig the generator can emit without the compiler reformatting it."
  [src]
  (let [f (java.io.File/createTempFile "clj-zig-source" ".zig")]
    (try
      (spit f src)
      (zero? (:exit (sh/sh "zig" "fmt" "--check" (.getPath f))))
      (finally (.delete f)))))

(def add-spec
  (spec/build-spec '{:ns app.core :name add
                     :signature [x :i64 y :i64 :ret :i64]}))

(deftest generates-readable-scalar-source
  (is (= (str "export fn clj_zig_app_2e_core_add(x: i64, y: i64) i64 {\n"
              "    return x + y;\n"
              "}\n")
         (source/generate add-spec "return x + y;"))))

(deftest embeds-the-stable-symbol-name
  (is (str/includes? (source/generate add-spec "return x + y;")
                     "clj_zig_app_2e_core_add"))
  (testing "the symbol is exactly the spec's symbol"
    (is (str/includes? (source/generate add-spec "return x + y;")
                       (str "export fn " (:symbol add-spec) "(")))))

(deftest maps-scalar-types-to-zig
  (let [s (spec/build-spec '{:ns app.core :name mix
                             :signature [a :f64 b :u8 c :bool :ret :usize]})
        out (source/generate s "return 0;")]
    (is (str/includes? out "a: f64"))
    (is (str/includes? out "b: u8"))
    (is (str/includes? out "c: bool"))
    (is (str/includes? out ") usize {"))))

(deftest generates-void-return
  (let [s (spec/build-spec '{:ns app.core :name noop :signature [:ret :void]})]
    (is (= "export fn clj_zig_app_2e_core_noop() void {\n    return;\n}\n"
           (source/generate s "return;")))))

(deftest generates-a-bytes-return-as-an-owned-u8-slice
  (let [s   (spec/build-spec '{:ns app.core :name dup
                               :signature [xs [:slice :const :u8] :ret [:bytes [:slice :u8]]]})
        src (source/generate s "return xs;")]
    (testing "the wrapper writes the slice pointer and length to out-params"
      (is (str/includes? src "__ptr: *usize, __len: *usize"))
      (is (str/includes? src "[]u8")))
    (testing "it emits the c_allocator free shim, like an owned return"
      (is (str/includes? src (str (:symbol s) "__free")))
      (is (str/includes? src "std.heap.c_allocator.free")))
    (testing "the generated source is canonical Zig"
      (is (zig-fmt-clean? src)))))

(deftest generated-source-passes-zig-fmt-check
  (testing "single statement"
    (is (zig-fmt-clean? (source/generate add-spec "return x + y;"))))
  (testing "multiple statements stay canonical at the body level"
    (is (zig-fmt-clean? (source/generate add-spec "const s = x + y;\nreturn s;"))))
  (testing "void function"
    (let [s (spec/build-spec '{:ns app.core :name noop :signature [:ret :void]})]
      (is (zig-fmt-clean? (source/generate s "return;"))))))

;; --- Owned/borrowed result records (ADR 21) ---------------------

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-record" (make-array java.nio.file.attribute.FileAttribute 0))))

;; A minimal record carrying one scalar and one buffer field. Validation
;; still rejects [:owned NamedRecord] until p2c relaxes it, so the spec map
;; is assembled directly with the layout descriptor attached, the shape the
;; codegen consumes once validation admits it.
(def ^:private record-layout
  (layout/describe 'RenderResult '[flag :u32 msg :string]))

(defn- record-spec [ownership]
  {:ns     'app.core
   :name   'render
   :symbol (spec/symbol-name 'app.core 'render)
   :params []
   :ret    {:kind ownership
            :of   {:kind :named :name 'RenderResult :layout record-layout}}})

(deftest owned-record-return-emits-wire-struct-write-wrapper-and-free-shim
  (let [src (source/generate (record-spec :owned)
                             "return .{ .flag = 1, .msg = \"hi\" };")]
    (testing "an owned result pulls std into scope for the free shim"
      (is (str/starts-with? src "const std = @import(\"std\");")))
    (testing "the wire struct expands the buffer field to ptr/len usize words"
      (is (str/includes? src "const RenderResult__wire = extern struct {"))
      (is (str/includes? src "flag: u32,"))
      (is (str/includes? src "msg_ptr: usize,"))
      (is (str/includes? src "msg_len: usize,")))
    (testing "the inner impl returns the nice record by value"
      (is (str/includes? src "fn clj_zig_app_2e_core_render__impl() RenderResult {")))
    (testing "the wrapper writes the scalar directly and the buffer as ptr/len"
      (is (str/includes? src "export fn clj_zig_app_2e_core_render(__ret: *RenderResult__wire) void {"))
      (is (str/includes? src "__ret.*.flag = __r.flag;"))
      (is (str/includes? src "__ret.*.msg_ptr = @intFromPtr(__r.msg.ptr);"))
      (is (str/includes? src "__ret.*.msg_len = __r.msg.len;")))
    (testing "the free shim frees every buffer field reading ptr/len off the wire"
      (is (str/includes? src
                         (str "export fn clj_zig_app_2e_core_render__free"
                              "(__ret: *const RenderResult__wire) void {")))
      (is (str/includes? src
                         "std.heap.c_allocator.free(@as([*]u8, @ptrFromInt(__ret.msg_ptr))[0..__ret.msg_len]);")))
    (testing "the generated source is canonical Zig"
      (is (zig-fmt-clean? src)))))

(deftest borrowed-record-return-omits-the-free-shim-and-std-import
  (let [src (source/generate (record-spec :borrowed)
                             "return .{ .flag = 1, .msg = \"hi\" };")]
    (testing "a borrowed result pulls no std into scope"
      (is (not (str/includes? src "const std = @import"))))
    (testing "the wrapper still writes every wire field"
      (is (str/includes? src "__ret.*.flag = __r.flag;"))
      (is (str/includes? src "__ret.*.msg_ptr = @intFromPtr(__r.msg.ptr);"))
      (is (str/includes? src "__ret.*.msg_len = __r.msg.len;")))
    (testing "no free shim is emitted"
      (is (not (str/includes? src "__free"))))
    (testing "the generated source is canonical Zig"
      (is (zig-fmt-clean? src)))))

(deftest owned-record-wrapper-compiles
  ;; A richer record covering every buffer element kind the free shim casts
  ;; (:string and :bytes free as u8; a slice field frees as its element).
  ;; The emitted Zig must build, not merely look right: the wrapper writes
  ;; every wire field and the shim reinterprets each pointer back to a slice.
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
                    "};\n\n")
        body   "return .{ .flag = 1, .msg = &[_]u8{}, .raw = &[_]u8{}, .xs = &[_]i64{} };"
        dir    (scratch-dir)]
    (is (.exists (io/file
                  (:library
                   (compile/compile!
                    {:source       (str nice (source/generate spec body))
                     :source-path  (str dir "/source.zig")
                     :library-path (str dir "/librender." (compile/dynamic-library-extension))
                     :ctx          {:var 'app.core/render}}))))
        "the owned-record wrapper and per-field free shim build")))

;; --- Owned slices of buffer-carrying structs (Follow-up 4) ----------------

(def ^:private label-layout
  (layout/describe 'Label '[tag :string n :i64]))

(defn- label-slice-spec [ownership]
  {:ns     'app.core
   :name   'make
   :symbol (spec/symbol-name 'app.core 'make)
   :params []
   :ret    {:kind ownership
            :of   {:kind :slice :const? false
                   :of   {:kind :named :name 'Label :layout label-layout}}}})

(deftest owned-buffer-slice-emits-transform-wrapper-and-walking-free-shim
  (let [src (source/generate (label-slice-spec :owned) "return &[_]Label{};")]
    (testing "an owned slice pulls std into scope for the slab alloc and free"
      (is (str/starts-with? src "const std = @import(\"std\");")))
    (testing "the wire struct expands the buffer field to ptr/len usize words"
      (is (str/includes? src "const Label__wire = extern struct {"))
      (is (str/includes? src "tag_ptr: usize,"))
      (is (str/includes? src "tag_len: usize,"))
      (is (str/includes? src "n: i64,")))
    (testing "the inner impl returns the nice record slice"
      (is (str/includes? src "fn clj_zig_app_2e_core_make__impl() []Label {")))
    (testing "the wrapper transforms the nice slice into a wire slab"
      (is (str/includes? src "const __nice = clj_zig_app_2e_core_make__impl();"))
      (is (str/includes? src "const __wire = std.heap.c_allocator.alloc(Label__wire, __nice.len)"))
      (is (str/includes? src "std.heap.c_allocator.free(__nice);")))
    (testing "the copy loop decomposes the buffer field and copies scalars"
      (is (str/includes? src "for (__nice, 0..) |__src, __i| {"))
      (is (str/includes? src "__wire[__i].tag_ptr = @intFromPtr(__src.tag.ptr);"))
      (is (str/includes? src "__wire[__i].tag_len = __src.tag.len;"))
      (is (str/includes? src "__wire[__i].n = __src.n;")))
    (testing "the free shim walks the wire slab freeing each buffer then the slab"
      (is (str/includes? src
                         (str "export fn clj_zig_app_2e_core_make__free"
                              "(__ptr: usize, __len: usize) void {")))
      (is (str/includes? src "for (__slice) |__e| {"))
      (is (str/includes? src
                         "std.heap.c_allocator.free(@as([*]u8, @ptrFromInt(__e.tag_ptr))[0..__e.tag_len]);"))
      (is (str/includes? src "std.heap.c_allocator.free(__slice);")))
    (testing "the generated source is canonical Zig"
      (is (zig-fmt-clean? src)))))
