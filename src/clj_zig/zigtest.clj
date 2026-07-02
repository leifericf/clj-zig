(ns clj-zig.zigtest
  "Run Zig `test` blocks from Clojure. `deftestz` defines a Clojure test
  that compiles a Zig body with `zig test`, runs the resulting test
  binary, and asserts the exit code is zero (all tests passed).

  The Zig body may include `test` blocks and any declarations the test
  blocks reference. The namespace's `defz` declarations and named types
  are prepended automatically. Example body text:

      test one_plus_one {
          try std.testing.expect(1 + 1 == 2);
      }
  "
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clj-zig.toolchain :as toolchain]
            [clj-zig.fs :as fs])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn run-zig-test
  "Compile and run `source` as a Zig test binary. Returns
  `{:pass true}` on exit code 0, or `{:pass false :output stderr}` on
  failure."
  [source]
  (let [zig (toolchain/zig-exe)
        tmp (str (Files/createTempDirectory
                  "clj-zig-test" (make-array FileAttribute 0)))
        src (io/file tmp "test.zig")]
    (try
      (spit src source)
      (let [{:keys [exit out err]} (sh/sh zig "test" (.getAbsolutePath src)
                                         :dir tmp)]
        (if (zero? exit)
          {:pass true}
          {:pass false :output (str/join "\n" (remove str/blank? [out err]))}))
      (finally
        (try (fs/delete-recursively! (io/file tmp)) (catch Exception _))))))

(defmacro deftestz
  "Define a Clojure test that runs Zig `test` blocks. The `body` is a
  string of Zig source containing `test` blocks. The test passes when
  `zig test` exits zero; on failure, the toolchain/test-runner output is
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
