(ns clj-zig.toolchain
  "Resolve the `zig` executable (imperative shell). One seam decides which
  compiler every other namespace runs, in order: an explicit override, a
  `zig` on PATH, then a pinned hermetic Zig under `.clj-zig/zig/<version>/`.
  Preferring PATH means a developer who already has Zig sees no download;
  the pinned fallback means a fresh machine still compiles on first use."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clj-zig.fs :as fs]))

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

(defn- expected-shasum
  "The SHA-256 the Zig release index publishes for a host's pinned archive.
  Verifying against this published digest is the integrity check; a host or
  version the index does not list is a clear error, not an unverified
  download."
  [os arch version]
  (let [index (json/read-str (slurp (str base-url "/index.json")))
        entry (get-in index [version (archive-key os arch)])]
    (or (get entry "shasum")
        (throw (ex-info (str "The Zig release index lists no " version " build for "
                             (archive-key os arch) ".")
                        {:level :error :error/code :clj-zig/zig-release-not-listed
                         :clj-zig/pinned-version version
                         :clj-zig/host (archive-key os arch)})))))

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
  "Unpack a zip archive into `dest-dir`."
  [archive dest-dir]
  (with-open [zin (java.util.zip.ZipInputStream. (io/input-stream archive))]
    (loop []
      (when-let [entry (.getNextEntry zin)]
        (let [out (io/file dest-dir (.getName entry))]
          (if (.isDirectory entry)
            (.mkdirs out)
            (do (io/make-parents out)
                (with-open [os (io/output-stream out)]
                  (io/copy zin os)))))
        (recur)))))

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
        staging (io/file parent (str ".staging-" stem))
        archive (io/file staging (str stem "." (archive-ext os)))]
    (when (.exists staging) (fs/delete-recursively! staging))
    (.mkdirs staging)
    (try
      (download-to! (download-url os arch pinned-version) archive)
      (verify-archive! archive (expected-shasum os arch pinned-version))
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

(comment
  (zig-exe)
  (pinned-dir)
  (download-url "macos" "aarch64" pinned-version)
  (expected-shasum "macos" "aarch64" pinned-version)
  (ensure-pinned!))
