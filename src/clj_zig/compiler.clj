(ns clj-zig.compiler
  "Resolve the `zig` executable and report its version (imperative shell).
  One seam decides which compiler every other namespace runs, in order: an
  explicit override, a `zig` on PATH, then a pinned hermetic Zig under
  `.clj-zig/zig/<version>/`. Preferring PATH means a developer who already
  has Zig sees no download; the pinned fallback means a fresh machine still
  compiles on first use."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clj-zig.fs :as fs])
  (:import (java.lang ProcessHandle)))

(def pinned-version
  "The Zig release the bootstrap installs and the generated wrappers
  assume. Part of the content hash through `zig version`."
  "0.16.0")

(def ^:private base-url "https://ziglang.org/download")

(def ^:private override-prop "clj-zig.zig")
(def ^:private override-env "CLJ_ZIG_ZIG")

(defn- windows? []
  (str/includes? (str/lower-case (System/getProperty "os.name")) "win"))

(defn- exe-name []
  (if (windows?) "zig.exe" "zig"))

(defn- executable? [path]
  (let [f (io/file path)]
    (and (.isFile f) (.canExecute f))))

(defn- override
  "An explicit Zig path from a system property or environment variable, or
  nil. A non-executable override is an error, not a silent fall-through."
  []
  (when-let [o (or (System/getProperty override-prop) (System/getenv override-env))]
    (if (executable? o)
      o
      (throw (ex-info (str "The configured Zig at " (pr-str o) " is not an executable file.")
                      {:level :error :error/code :clj-zig/zig-override-invalid
                       :clj-zig/path o})))))

(defn- on-path
  "The first `zig` found on the PATH, or nil."
  []
  (let [sep (re-pattern (java.util.regex.Pattern/quote java.io.File/pathSeparator))
        exe (exe-name)]
    (->> (str/split (or (System/getenv "PATH") "") sep)
         (remove str/blank?)
         (map #(io/file % exe))
         (filter executable?)
         (map #(.getPath ^java.io.File %))
         first)))

(defn pinned-dir
  "The directory the pinned hermetic Zig installs into."
  []
  (io/file ".clj-zig" "zig" pinned-version))

(defn- pinned-exe
  "The pinned `zig` path if already installed, else nil."
  []
  (let [f (io/file (pinned-dir) (exe-name))]
    (when (executable? f) (.getPath f))))

;; --- The bootstrap fetch: name a release, download it, verify it, place it ---

(defn- archive-key
  "Zig's release-index key for a host, `<arch>-<os>` (e.g. `x86_64-linux`)."
  [os arch]
  (str arch "-" os))

(defn- archive-stem
  "The release archive stem, e.g. `zig-x86_64-linux-0.16.0`."
  [os arch version]
  (str "zig-" arch "-" os "-" version))

(defn- archive-ext
  "The release archive suffix for an OS: a zip on Windows, an xz tarball
  elsewhere."
  [os]
  (if (= os "windows") "zip" "tar.xz"))

(defn- download-url
  "The full download URL for a host's pinned release."
  [os arch version]
  (str base-url "/" version "/" (archive-stem os arch version) "." (archive-ext os)))

(defn- host-os
  "Zig's OS token for this machine."
  []
  (let [os (str/lower-case (System/getProperty "os.name"))]
    (cond
      (str/includes? os "mac")   "macos"
      (str/includes? os "win")   "windows"
      (str/includes? os "linux") "linux"
      :else (throw (ex-info (str "No pinned Zig build for this operating system: " (pr-str os) ".")
                            {:level :error :error/code :clj-zig/unsupported-host
                             :clj-zig/os os})))))

(defn- host-arch
  "Zig's architecture token for this machine."
  []
  (let [arch (str/lower-case (System/getProperty "os.arch"))]
    (cond
      (#{"aarch64" "arm64"} arch) "aarch64"
      (#{"x86_64" "amd64"} arch)  "x86_64"
      :else (throw (ex-info (str "No pinned Zig build for this architecture: " (pr-str arch) ".")
                            {:level :error :error/code :clj-zig/unsupported-host
                             :clj-zig/arch arch})))))

(def ^:private pinned-shasums
  "The SHA-256 each supported host's `pinned-version` archive published,
  recorded from Zig's release index at pin time. This is the integrity
  anchor: a download is checked against THIS constant, not against a digest
  fetched from the same host that serves the archive, so a host compromise
  cannot tamper the two in concert. Update this map whenever
  `pinned-version` changes; a host not listed fails fast in
  `expected-shasum` rather than downloading unverified."
  {"aarch64-macos"   "b23d70deaa879b5c2d486ed3316f7eaa53e84acf6fc9cc747de152450d401489"
   "x86_64-macos"    "0387557ed1877bc6a2e1802c8391953baddba76081876301c522f52977b52ba7"
   "aarch64-windows" "aee38316ee4111717900f45dd3130145c39289e105541d737eb8c5ed653c78ef"
   "x86_64-windows"  "68659eb5f1e4eb1437a722f1dd889c5a322c9954607f5edcf337bc3684a75a7e"
   "aarch64-linux"   "ea4b09bfb22ec6f6c6ceac57ab63efb6b46e17ab08d21f69f3a48b38e1534f17"
   "x86_64-linux"    "70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00"})

(defn- expected-shasum
  "The pinned SHA-256 for a host's `pinned-version` archive. The integrity
  anchor lives in `pinned-shasums`, recorded at pin time, so verification
  does not depend on a digest fetched from the download host. A host not
  pinned fails fast rather than downloading unverified."
  [os arch]
  (or (get pinned-shasums (archive-key os arch))
      (throw (ex-info (str "No pinned Zig build for " (archive-key os arch)
                           " at " pinned-version ".")
                      {:level :error :error/code :clj-zig/zig-release-not-listed
                       :clj-zig/pinned-version pinned-version
                       :clj-zig/host (archive-key os arch)}))))

(defn- download-to!
  "Stream the resource at `url` into `file`."
  [url file]
  (with-open [in  (io/input-stream (.toURL (java.net.URI/create url)))
              out (io/output-stream file)]
    (io/copy in out)))

(defn- sha256-file
  "The lowercase hex SHA-256 of a file's bytes."
  [file]
  (let [md  (java.security.MessageDigest/getInstance "SHA-256")
        buf (byte-array 8192)]
    (with-open [in (io/input-stream file)]
      (loop []
        (let [n (.read in buf)]
          (when (pos? n)
            (.update md buf 0 n)
            (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn- verify-archive!
  "Confirm a downloaded archive matches its published digest, refusing to
  install a tampered or corrupt download."
  [file expected]
  (let [actual (sha256-file file)]
    (when-not (= actual expected)
      (throw (ex-info "The downloaded Zig archive does not match its published checksum."
                      {:level :error :error/code :clj-zig/zig-checksum-mismatch
                       :clj-zig/expected expected :clj-zig/actual actual})))))

(defn- extract-tar!
  "Unpack an xz tarball into `dest-dir` using the system `tar`."
  [archive dest-dir]
  (let [{:keys [exit err]} (sh/sh "tar" "-xf" (.getAbsolutePath archive)
                                  "-C" (.getAbsolutePath dest-dir))]
    (when-not (zero? exit)
      (throw (ex-info (str "Could not extract the Zig archive: " (str/trim err))
                      {:level :error :error/code :clj-zig/zig-extract-failed
                       :clj-zig/stderr err})))))

(defn- extract-zip!
  "Unpack a zip archive into `dest-dir`. Each entry is validated to stay
  within `dest-dir` before it is written, so a crafted archive with a
  `../` entry (zip-slip) cannot write outside the install root; the
  download is checksum-verified first, but this is defense in depth."
  [archive dest-dir]
  (let [dest-canonical (.getCanonicalPath dest-dir)]
    (with-open [zin (java.util.zip.ZipInputStream. (io/input-stream archive))]
      (loop []
        (when-let [entry (.getNextEntry zin)]
          (let [out (io/file dest-dir (.getName entry))]
            (when-not (str/starts-with? (.getCanonicalPath out) dest-canonical)
              (throw (ex-info (str "Refusing to extract a zip entry outside the"
                                   " install dir: " (.getName entry))
                              {:level :error :error/code :clj-zig/zig-extract-failed
                               :clj-zig/entry (.getName entry)})))
            (if (.isDirectory entry)
              (.mkdirs out)
              (do (io/make-parents out)
                  (with-open [os (io/output-stream out)]
                    (io/copy zin os)))))
          (recur))))))

(defn- install-pinned!
  "Download, verify, and extract the pinned Zig so `(pinned-dir)` holds a
  runnable `zig`. The one network and filesystem step; staging stays under
  the install root so the final move never crosses a filesystem boundary."
  []
  (let [os      (host-os)
        arch    (host-arch)
        stem    (archive-stem os arch pinned-version)
        target  (.getAbsoluteFile (pinned-dir))
        parent  (.getParentFile target)
        ;; A process-unique staging dir so two JVMs bootstrapping at once
        ;; (parallel CI, a monorepo) cannot race on the same path: each
        ;; downloads and extracts into its own dir, then renames into place.
        staging (io/file parent (str ".staging-" stem "-"
                                     (.pid (ProcessHandle/current))))
        archive (io/file staging (str stem "." (archive-ext os)))]
    (when (.exists staging) (fs/delete-recursively! staging))
    (.mkdirs staging)
    (try
      (download-to! (download-url os arch pinned-version) archive)
      (verify-archive! archive (expected-shasum os arch))
      (if (= os "windows")
        (extract-zip! archive staging)
        (extract-tar! archive staging))
      (when (.exists target) (fs/delete-recursively! target))
      (when-not (.renameTo (io/file staging stem) target)
        (throw (ex-info (str "Could not move the extracted Zig into " (.getPath target) ".")
                        {:level :error :error/code :clj-zig/zig-install-failed
                         :clj-zig/target (.getPath target)})))
      (finally
        (fs/delete-recursively! staging)))))

(defn ensure-pinned!
  "Install the pinned hermetic Zig on first use and return its path. Prints
  one line to stderr on the initial fetch and reuses the install after."
  []
  (or (pinned-exe)
      (do
        (binding [*out* *err*]
          (println (str "clj-zig: no zig on PATH; fetching pinned Zig " pinned-version
                        " into " (.getPath (pinned-dir)) " (one time).")))
        (install-pinned!)
        (or (pinned-exe)
            (throw (ex-info (str "Installed the pinned Zig but found no runnable zig in "
                                 (.getPath (pinned-dir)) ".")
                            {:level :error :error/code :clj-zig/zig-bootstrap-failed
                             :clj-zig/pinned-version pinned-version}))))))

(defn zig-exe
  "The path to the `zig` executable to run: an explicit override, then a
  `zig` on PATH, then the pinned hermetic Zig, installing it if needed."
  []
  (or (override)
      (on-path)
      (pinned-exe)
      (ensure-pinned!)))

(defn zig-version
  "The `zig` compiler version string, part of the cache key."
  []
  (str/trim (:out (sh/sh (zig-exe) "version"))))

(comment
  (zig-exe)
  (zig-version)
  (pinned-dir)
  (download-url "macos" "aarch64" pinned-version)
  (expected-shasum "macos" "aarch64")
  (ensure-pinned!))
