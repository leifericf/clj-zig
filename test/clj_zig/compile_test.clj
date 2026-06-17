(ns clj-zig.compile-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.compile :as compile]
            [clj-zig.source :as source]
            [clj-zig.spec :as spec]))

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-compile" (make-array java.nio.file.attribute.FileAttribute 0))))

(def add-spec
  (spec/build-spec '{:ns app.core :name add
                     :signature [x :i64 y :i64 :ret :i64]}))

(defn- compile-add [dir body]
  (compile/compile! {:source (source/generate add-spec body)
                     :source-path (str dir "/source.zig")
                     :library-path (str dir "/libadd." (compile/dynamic-library-extension))
                     :ctx {:var 'app.core/add
                           :signature '[x :i64 y :i64 :ret :i64]}}))

(deftest options-become-zig-build-flags
  (testing "empty options add nothing"
    (is (= [] (compile/options->flags nil)))
    (is (= [] (compile/options->flags {}))))
  (testing "include, system-include, link-path, and link map to flags in order"
    (is (= ["-I" "/inc" "-isystem" "/sys" "-L" "/lib" "-lm" "-lz"]
           (compile/options->flags {:include-path ["/inc"]
                                    :system-include-path ["/sys"]
                                    :link-path ["/lib"]
                                    :link ["m" "z"]})))))

(deftest build-arguments-without-modules-pass-the-source-positionally
  (let [args (compile/build-arguments
              "zig" {:source-abs "/b/source.zig" :library-abs "/b/lib.dylib"
                     :options {:link ["m"]} :global-cache-dir "/g"})]
    (testing "the persistent global cache dir is always present (ADR 35)"
      (is (= ["--global-cache-dir" "/g"]
             (subvec args (.indexOf args "--global-cache-dir")
                     (+ 2 (.indexOf args "--global-cache-dir"))))))
    (testing "the source is the positional root, after the per-module flags"
      (is (= "/b/source.zig" (last args)))
      (is (< (.indexOf args "-lc") (.indexOf args "/b/source.zig")))
      (is (< (.indexOf args "-lm") (.indexOf args "/b/source.zig"))))
    (testing "no module flags appear"
      (is (not-any? #(and (string? %) (str/starts-with? % "-M")) args))
      (is (not ((set args) "--dep"))))))

(deftest build-arguments-with-modules-declare-and-define-each-module
  (let [args (compile/build-arguments
              "zig" {:source-abs "/b/source.zig" :library-abs "/b/lib.dylib"
                     :options nil :global-cache-dir "/g"
                     :module-roots {"mylib" "/pkg/root.zig"}})]
    (testing "the source becomes the main module and each module is declared and defined"
      (is ((set args) "--dep"))
      (is ((set args) "mylib"))
      (is ((set args) "-Mroot=/b/source.zig"))
      (is ((set args) "-Mmylib=/pkg/root.zig")))
    (testing "a --dep precedes the main module so the import resolves"
      (is (< (.indexOf args "--dep") (.indexOf args "-Mroot=/b/source.zig"))))
    (testing "the link flags attach to the main module, before -Mroot"
      (is (< (.indexOf args "-lc") (.indexOf args "-Mroot=/b/source.zig"))))))

(deftest attribute-failure-points-at-the-module-or-the-wrapper
  (let [roots {"mymod" "/pkg/src/root.zig"}]
    (testing "stderr under a module's source dir is attributed to that module"
      (is (= {:zig/origin :module :zig/module "mymod"}
             (compile/attribute-failure "/pkg/src/util.zig:3:1: error: bad" roots))))
    (testing "stderr in the wrapper source is attributed to the wrapper"
      (is (= {:zig/origin :wrapper}
             (compile/attribute-failure "/cache/x/source.zig:2:1: error: bad" roots))))
    (testing "with no modules a failure is always the wrapper"
      (is (= {:zig/origin :wrapper}
             (compile/attribute-failure "/cache/x/source.zig:2:1: error" nil))))))

(deftest compiles-a-scalar-function
  (let [dir    (scratch-dir)
        result (compile-add dir "return x + y;")]
    (is (.exists (io/file (:library result))))
    (is (.exists (io/file (:source-path result))))
    (testing "the exported symbol is present in the library"
      (let [{:keys [out]} (sh/sh "nm" "-gU" (:library result))]
        (is (str/includes? out "clj_zig_app_2e_core_add"))))))

(deftest cross-compiles-for-a-named-target
  (testing "a target triple cross-compiles for another platform; the
  artifact is produced even though the host cannot load it"
    (let [dir (scratch-dir)
          lib (str dir "/libadd.so")
          {:keys [library]} (compile/compile!
                             {:source (source/generate add-spec "return x + y;")
                              :source-path (str dir "/source.zig")
                              :library-path lib
                              :target "x86_64-linux-musl"
                              :ctx {:var 'app.core/add
                                    :signature '[x :i64 y :i64 :ret :i64]}})]
      (is (.exists (io/file library)))
      (testing "the artifact is an ELF object, not a host Mach-O or PE"
        (is (str/includes? (:out (sh/sh "file" library)) "ELF"))))))

(deftest writes-canonical-source
  (let [dir    (scratch-dir)
        result (compile-add dir "const s = x + y;\nreturn s;")
        on-disk (slurp (:source-path result))]
    (testing "the source on disk passes zig fmt --check"
      (is (zero? (:exit (sh/sh "zig" "fmt" "--check" (:source-path result))))
          on-disk))))

(deftest reports-compile-failure-as-a-structured-diagnostic
  (let [dir (scratch-dir)]
    (try
      (compile-add dir "return x + ;")
      (is false "expected a compile diagnostic")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :zig/compile-failed (:error/code data)))
          (is (= 'app.core/add (:var data)))
          (is (= '[x :i64 y :i64 :ret :i64] (:signature data)))
          (is (pos? (:zig/exit-code data)))
          (is (str/includes? (:zig/stderr data) "error"))
          (is (str/ends-with? (:zig/source-path data) "source.zig")))))))

(deftest failed-compile-leaves-no-library-behind
  (testing "a failed build does not poison the path with a zero-byte library"
    (let [dir (scratch-dir)
          lib (str dir "/libadd." (compile/dynamic-library-extension))]
      (try (compile/compile! {:source (source/generate add-spec "return x + ;")
                              :source-path (str dir "/source.zig")
                              :library-path lib
                              :ctx {:var 'app.core/add :signature '[x :i64 y :i64 :ret :i64]}})
           (catch clojure.lang.ExceptionInfo _ nil))
      (is (not (.exists (io/file lib)))))))
