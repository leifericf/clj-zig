(ns clj-zig.cinterop-test
  "End-to-end C interop: a file-mode body that `@cImport`s a C header and
  links a C library through the descriptor's `:zig/link`. This exercises
  the file body and the compile-flag hook together against real headers."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.core :as core]))

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-cinterop" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- define! [form]
  (binding [*ns* (the-ns 'clj-zig.cinterop-test)]
    (eval form)))

(deftest cimport-of-math-h-links-libm-and-round-trips
  (testing "a body that calls c.sqrt links libm via :zig/link and returns 5.0"
    (let [dir  (scratch-dir)
          path (io/file dir "hyp.zig")]
      (spit path (str "const c = @cImport({\n"
                      "    @cInclude(\"math.h\");\n"
                      "});\n"
                      "pub fn hyp(a: f64, b: f64) f64 {\n"
                      "    return c.sqrt(a * a + b * b);\n"
                      "}\n"))
      (define! `(core/defnz ~'hyp [~'a :f64 ~'b :f64 :ret :f64]
                  {:zig/file ~(.getPath path) :zig/link ["m"]}))
      (is (= 5.0 ((ns-resolve 'clj-zig.cinterop-test 'hyp) 3.0 4.0))))))
