(ns clj-zig.toolchain-test
  "Resolving the zig executable: an explicit override wins, an invalid
  override is an error, and otherwise a zig on PATH is used."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
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

(deftest a-release-url-follows-the-zig-naming-scheme
  (testing "arch-first, os, version, with a tarball off Windows and a zip on it"
    (is (= "https://ziglang.org/download/0.16.0/zig-x86_64-linux-0.16.0.tar.xz"
           (#'toolchain/download-url "linux" "x86_64" "0.16.0")))
    (is (= "https://ziglang.org/download/0.16.0/zig-aarch64-macos-0.16.0.tar.xz"
           (#'toolchain/download-url "macos" "aarch64" "0.16.0")))
    (is (= "https://ziglang.org/download/0.16.0/zig-x86_64-windows-0.16.0.zip"
           (#'toolchain/download-url "windows" "x86_64" "0.16.0"))))
  (testing "the release-index key is arch-os"
    (is (= "x86_64-linux" (#'toolchain/archive-key "linux" "x86_64")))
    (is (= "aarch64-macos" (#'toolchain/archive-key "macos" "aarch64")))))

(deftest every-host-in-the-matrix-has-a-well-formed-url
  (testing "across the OS and architecture matrix the URL names the pinned
  release and ends in the right archive suffix"
    (doseq [os   ["linux" "macos" "windows"]
            arch ["x86_64" "aarch64"]]
      (let [url (#'toolchain/download-url os arch "0.16.0")]
        (is (str/includes? url (str "zig-" arch "-" os "-0.16.0")))
        (is (str/starts-with? url "https://ziglang.org/download/0.16.0/"))
        (is (str/ends-with? url (if (= os "windows") ".zip" ".tar.xz")))))))

(deftest every-host-in-the-matrix-has-a-pinned-shasum
  (testing "the integrity anchor is a local constant for the whole host
  matrix, so verification never fetches a digest from the download host"
    (doseq [os   ["linux" "macos" "windows"]
            arch ["x86_64" "aarch64"]]
      (let [sum (#'toolchain/expected-shasum os arch)]
        (is (re-matches #"[0-9a-f]{64}" sum)
            (str arch "-" os " has a 64-hex pinned shasum")))))
  (testing "a host not pinned fails fast rather than downloading unverified"
    (let [ex (try (#'toolchain/expected-shasum "bsd" "riscv64")
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :clj-zig/zig-release-not-listed (:error/code (ex-data ex)))))))

(defn- temp-dir []
  (let [d (.toFile (java.nio.file.Files/createTempDirectory
                    "clj-zig-toolchain" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit d)
    d))

(deftest the-pinned-toolchain-is-resolved-after-install
  (testing "with no executable yet under the pinned dir, the resolver installs
  the toolchain once and returns a runnable zig"
    (let [system-zig (toolchain/zig-exe)
          dir        (temp-dir)
          installs   (atom 0)]
      (with-redefs [toolchain/pinned-dir   (constantly dir)
                    toolchain/install-pinned! (fn []
                                                (swap! installs inc)
                                                (let [src  (io/file system-zig)
                                                      dest (io/file dir (.getName src))]
                                                  (io/copy src dest)
                                                  (.setExecutable dest true)))]
        (let [installed (toolchain/ensure-pinned!)]
          (is (= 1 @installs) "the fetch runs once")
          (is (= (.getPath (io/file dir (.getName (io/file system-zig)))) installed))
          (is (zero? (:exit (sh/sh installed "version"))) "the installed zig runs")
          (is (= installed (toolchain/ensure-pinned!)) "a second call reuses the install")
          (is (= 1 @installs) "and does not fetch again"))))))

(deftest pinned-exe-rejects-a-directory-named-zig
  (testing "a directory with the execute bit is not a runnable zig"
    (let [dir (temp-dir)]
      (.mkdirs (io/file dir "zig"))
      (with-redefs [toolchain/pinned-dir (constantly dir)]
        (is (nil? (#'toolchain/pinned-exe))
            "the resolver skips a 'zig' that is a directory, not a file")))))

(deftest extract-zip-rejects-an-entry-outside-the-install-root
  (testing "a crafted zip with a ../ entry is refused (zip-slip defense)"
    (let [dest    (temp-dir)
          evil    (io/file (.getParentFile dest) "clj-zig-zipslip-evil.txt")
          archive (java.io.File/createTempFile "clj-zig-zip" ".zip")]
      (.deleteOnExit archive)
      (when (.exists evil) (.delete evil))
      (with-open [zos (java.util.zip.ZipOutputStream. (io/output-stream archive))]
        (let [entry (java.util.zip.ZipEntry. "../clj-zig-zipslip-evil.txt")]
          (.putNextEntry zos entry)
          (.write zos (.getBytes "pwned"))
          (.closeEntry zos)))
      (let [ex (try (#'toolchain/extract-zip! archive dest)
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :clj-zig/zig-extract-failed (:error/code (ex-data ex))))
        (is (not (.exists evil)) "nothing was written outside the install root")))))
