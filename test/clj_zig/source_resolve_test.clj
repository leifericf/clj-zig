(ns clj-zig.source-resolve-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.source :as source]))

(deftest candidate-paths-resolves-relative-to-the-defining-file-first
  (is (= ["/home/me/src/app/dot.zig" "dot.zig"]
         (source/candidate-paths "/home/me/src/app/core.clj" "dot.zig"))))

(deftest candidate-paths-without-a-defining-file-tries-only-the-cwd
  (testing "nil defining file"
    (is (= ["dot.zig"] (source/candidate-paths nil "dot.zig"))))
  (testing "REPL sentinel"
    (is (= ["dot.zig"] (source/candidate-paths "NO_SOURCE_PATH" "dot.zig")))))

(deftest candidate-paths-uses-an-absolute-path-as-is
  (is (= ["/opt/zig/dot.zig"]
         (source/candidate-paths "/home/me/src/app/core.clj" "/opt/zig/dot.zig"))))

(deftest resolve-and-read-reads-a-file-next-to-the-defining-file
  (let [dir   (str (java.nio.file.Files/createTempDirectory
                    "clj-zig-source" (make-array java.nio.file.attribute.FileAttribute 0)))
        zig   (io/file dir "body.zig")
        defn  (io/file dir "core.clj")]
    (spit zig "pub fn f() void {}\n")
    (let [{:keys [text path]} (source/resolve-and-read (.getPath defn) "body.zig")]
      (is (= "pub fn f() void {}\n" text))
      (is (= (.getPath zig) path)))))

(deftest resolve-and-read-throws-a-structured-diagnostic-when-missing
  (let [ex (try (source/resolve-and-read "/nowhere/core.clj" "does-not-exist.zig")
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :clj-zig/zig-file-not-found (:error/code (ex-data ex))))
    (is (= "does-not-exist.zig" (:clj-zig/file (ex-data ex))))
    (is (some #(re-find #"classpath:" %) (:clj-zig/tried (ex-data ex))))))
