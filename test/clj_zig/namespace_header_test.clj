(ns clj-zig.namespace-header-test
  "A `.zig` file may assert which namespace it belongs to with a leading
  `//! clj-zig: <ns>` doc comment. The path is the binder; the header is a
  guard that catches a file wired to the wrong namespace."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.core :as core]
            [clj-zig.fileref :as fileref]))

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-ns-header" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-zig [dir name content]
  (let [f (io/file dir name)]
    (spit f content)
    (.getPath f)))

(defn- define! [file form]
  (binding [*ns* (the-ns 'clj-zig.namespace-header-test)
            *file* file]
    (eval form)))

(defn- the-fn [sym]
  (ns-resolve 'clj-zig.namespace-header-test sym))

(defn- ex-of [thunk]
  (try (thunk)
       (catch clojure.lang.ExceptionInfo e e)
       (catch clojure.lang.Compiler$CompilerException e (.getCause e))))

(deftest declared-namespace-reads-the-header
  (testing "the marker line yields the declared namespace"
    (is (= "app.geometry"
           (fileref/declared-namespace "//! clj-zig: app.geometry\npub fn f() void {}\n"))))
  (testing "a file with no marker declares nothing"
    (is (nil? (fileref/declared-namespace "//! a plain doc comment\npub fn f() void {}\n")))
    (is (nil? (fileref/declared-namespace "pub fn f() void {}\n")))))

(deftest matching-header-passes
  (let [dir (scratch-dir)
        clj (str (io/file dir "ok.clj"))]
    (write-zig dir "ok.zig"
               (str "//! clj-zig: clj-zig.namespace-header-test\n"
                    "pub fn ok(x: i64) i64 {\n    return x + 1;\n}\n"))
    (define! clj `(core/defnz ~'ok [~'x :i64 :ret :i64]))
    (is (= 6 ((the-fn 'ok) 5)))))

(deftest mismatched-header-throws
  (let [dir (scratch-dir)
        clj (str (io/file dir "wrong.clj"))]
    (write-zig dir "wrong.zig"
               (str "//! clj-zig: some.other.namespace\n"
                    "pub fn wrong(x: i64) i64 {\n    return x;\n}\n"))
    (let [ex (ex-of #(define! clj `(core/defnz ~'wrong [~'x :i64 :ret :i64])))]
      (is (= :clj-zig/namespace-mismatch (:error/code (ex-data ex))))
      (is (= "some.other.namespace" (:clj-zig/declared (ex-data ex))))
      (is (= "clj-zig.namespace-header-test" (:clj-zig/expected (ex-data ex)))))))
