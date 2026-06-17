(ns clj-zig.source-test
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
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
