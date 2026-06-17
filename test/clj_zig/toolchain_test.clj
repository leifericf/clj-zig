(ns clj-zig.toolchain-test
  "Resolving the zig executable: an explicit override wins, an invalid
  override is an error, and otherwise a zig on PATH is used."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.toolchain :as toolchain]))

(defn- temp-file [executable?]
  (let [f (java.io.File/createTempFile "clj-zig-zig" "")]
    (.deleteOnExit f)
    (.setExecutable f executable?)
    f))

(defn- with-override [path thunk]
  (try
    (System/setProperty "clj-zig.zig" path)
    (thunk)
    (finally
      (System/clearProperty "clj-zig.zig"))))

(deftest an-executable-override-is-used
  (let [f (temp-file true)]
    (with-override (.getPath f)
      #(is (= (.getPath f) (toolchain/zig-exe))))))

(deftest a-non-executable-override-is-an-error
  (let [f (temp-file false)]
    (with-override (.getPath f)
      (fn []
        (let [ex (try (toolchain/zig-exe)
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :clj-zig/zig-override-invalid (:error/code (ex-data ex)))))))))

(deftest a-zig-on-path-is-resolved
  (testing "with no override, the resolver returns a runnable zig"
    (let [zig (toolchain/zig-exe)]
      (is (string? zig))
      (is (.canExecute (io/file zig)))
      (is (zero? (:exit (sh/sh zig "version")))))))
