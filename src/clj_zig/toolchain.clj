(ns clj-zig.toolchain
  "Resolve the `zig` executable (imperative shell). One seam decides which
  compiler every other namespace runs, in order: an explicit override, a
  `zig` on PATH, then a pinned hermetic Zig under `.clj-zig/zig/<version>/`.
  Preferring PATH means a developer who already has Zig sees no download;
  the pinned fallback means a fresh machine still compiles on first use."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def pinned-version
  "The Zig release the bootstrap installs and the generated wrappers
  assume. Part of the content hash through `zig version`."
  "0.16.0")

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
         (filter #(.canExecute ^java.io.File %))
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
    (when (.canExecute f) (.getPath f))))

(defn ensure-pinned!
  "Install the pinned hermetic Zig and return its path. The fetch lands in
  a later step; until then a missing toolchain is a clear error."
  []
  (throw (ex-info (str "No `zig` on PATH and no pinned toolchain installed. "
                       "Install Zig " pinned-version ", or set the "
                       override-prop " system property to a zig executable.")
                  {:level :error :error/code :clj-zig/zig-not-found
                   :clj-zig/pinned-version pinned-version})))

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
  (pinned-dir))
