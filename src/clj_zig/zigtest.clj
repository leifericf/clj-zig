(ns clj-zig.zigtest
  "Run Zig `test` blocks from Clojure. `deftestz` defines a Clojure test
  that compiles a Zig body with `zig test`, runs the resulting test
  binary, and asserts the exit code is zero (all tests passed).

  The Zig body may include `test` blocks and any declarations the test
  blocks reference. The namespace's `defz` declarations and named types
  are prepended automatically.

      (deftestz add-test
        \\\"test add returns the sum\\\"
        \\\"const std = @import(\\\\\\\"std\\\\\\\");
        test one_plus_one {
            try std.testing.expect(1 + 1 == 2);
        }\\\")
  "
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clj-zig.compiler :as compiler]))

(defn run-zig-test
  "Compile and run `source` as a Zig test binary. Returns
  `{:pass true}` on exit code 0, or `{:pass false :output stderr}` on
  failure."
  [source]
  (let [zig (compiler/zig-exe)
        tmp (str (java.nio.file.Files/createTempDirectory
                  "clj-zig-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        src (io/file tmp "test.zig")]
    (spit src source)
    (let [{:keys [exit out err]} (sh/sh zig "test" (.getAbsolutePath src)
                                       :dir tmp)]
      (if (zero? exit)
        {:pass true}
        {:pass false :output (str/join "\n" (remove str/blank? [out err]))}))))

(defmacro deftestz
  "Define a Clojure test that runs Zig `test` blocks. The `body` is a
  string of Zig source containing `test` blocks. The test passes when
  `zig test` exits zero; on failure, the compiler/test-runner output is
  included in the assertion message."
  ([name body]
   `(deftest ~name
      (let [result# (run-zig-test ~body)]
        (is (:pass result#)
            (str "Zig test failed:\n" (:output result#))))))
  ([name docstring body]
   `(deftest ~name
      ~(str docstring)
      (let [result# (run-zig-test ~body)]
        (is (:pass result#)
            (str "Zig test failed:\n" (:output result#)))))))
