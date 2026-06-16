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

(deftest compiles-a-scalar-function
  (let [dir    (scratch-dir)
        result (compile-add dir "return x + y;")]
    (is (.exists (io/file (:library result))))
    (is (.exists (io/file (:source-path result))))
    (testing "the exported symbol is present in the library"
      (let [{:keys [out]} (sh/sh "nm" "-gU" (:library result))]
        (is (str/includes? out "clj_zig_app_2e_core_add"))))))

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
