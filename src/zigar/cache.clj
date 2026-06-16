(ns zigar.cache
  "Content-address generated artifacts (ADR 12). The hash of the
  normalized spec, body, dependencies, options, Zig version, and target
  becomes the artifact path, so an unchanged form reuses its library and
  a changed form gets a fresh one. The JVM never reloads a stale library.

      .zigar/cache/macos-aarch64/app.core/add-83a1c0f9e1b2/
        source.zig
        libadd-83a1c0f9e1b2.dylib
        manifest.edn

  `cache-key` and `artifact-paths` are pure; resolving the toolchain
  version and target, and reading and writing the filesystem, are the
  shell."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

;; --- Pure: hashing and path layout --------------------------------------

(defn- canonical
  "A deterministic, order-independent string for `x`, so the hash does
  not depend on map or set iteration order."
  [x]
  (cond
    (map? x)  (str "{" (->> x
                            (sort-by (comp pr-str key))
                            (map (fn [[k v]] (str (canonical k) " " (canonical v))))
                            (str/join ", "))
                   "}")
    (set? x)  (str "#{" (str/join " " (sort (map canonical x))) "}")
    (or (vector? x) (seq? x)) (str "[" (str/join " " (map canonical x)) "]")
    :else     (pr-str x)))

(defn- sha256-hex [s]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn cache-key
  "The content hash for these build inputs. Any change to the spec, body,
  dependencies, options, Zig version, or target yields a new key."
  [{:keys [spec body deps options zig-version target]}]
  (subs (sha256-hex (canonical {:spec spec :body body :deps deps
                                :options options :zig-version zig-version
                                :target target}))
        0 12))

(defn- extension-for-target
  "The shared-library suffix for a target triple, without the dot."
  [target]
  (cond
    (str/includes? target "macos")   "dylib"
    (str/includes? target "windows") "dll"
    :else                            "so"))

(defn artifact-paths
  "The artifact directory and file paths for a build, under `root`
  (default `.zigar/cache`)."
  [{:keys [root target ns name hash]}]
  (let [root (or root ".zigar/cache")
        dir  (str root "/" target "/" ns "/" name "-" hash)]
    {:dir           dir
     :source-path   (str dir "/source.zig")
     :library-path  (str dir "/lib" name "-" hash "." (extension-for-target target))
     :manifest-path (str dir "/manifest.edn")}))

;; --- Shell: environment, filesystem -------------------------------------

(defn- var-symbol [spec]
  (symbol (str (:ns spec)) (str (:name spec))))

(defn zig-version
  "The `zig` compiler version string, part of the cache key."
  []
  (str/trim (:out (sh/sh "zig" "version"))))

(defn target-triple
  "The development target as `<os>-<arch>`, part of the cache key and the
  artifact path."
  []
  (let [os   (str/lower-case (System/getProperty "os.name"))
        arch (str/lower-case (System/getProperty "os.arch"))
        os   (cond (str/includes? os "mac")   "macos"
                   (str/includes? os "win")   "windows"
                   (str/includes? os "linux") "linux"
                   :else                      os)
        arch (cond (#{"aarch64" "arm64"} arch) "aarch64"
                   (#{"x86_64" "amd64"} arch)  "x86_64"
                   :else                       arch)]
    (str os "-" arch)))

(defn library-present? [paths]
  (.exists (io/file (:library-path paths))))

(defn write-manifest!
  "Write the human-readable manifest describing a built artifact."
  [paths {:keys [spec deps options zig-version target]} artifact-key]
  (spit (:manifest-path paths)
        (with-out-str
          (pprint/pprint {:var          (var-symbol spec)
                          :hash         artifact-key
                          :target       target
                          :zig-version  zig-version
                          :signature    (:signature spec)
                          :symbol       (:symbol spec)
                          :deps         deps
                          :options      options
                          :source-path  (:source-path paths)
                          :library-path (:library-path paths)}))))

(defn read-manifest [paths]
  (let [f (io/file (:manifest-path paths))]
    (when (.exists f)
      (read-string (slurp f)))))

(defn- delete-recursively [^java.io.File f]
  (when (.isDirectory f)
    (run! delete-recursively (.listFiles f)))
  (.delete f))

(defn clean!
  "Remove the entire artifact cache under `root` (default `.zigar/cache`)."
  ([] (clean! ".zigar/cache"))
  ([root]
   (let [d (io/file root)]
     (when (.exists d) (delete-recursively d)))))

(defn ensure-library!
  "Return the cached artifact paths for these `inputs`, compiling through
  `compile!-fn` only when the library is absent. Identical inputs reuse
  the cached library; any change rebuilds under a fresh path. `inputs`
  carries `:spec`, `:body`, `:source`, `:deps`, `:options`,
  `:zig-version`, `:target`, and an optional `:root`."
  [{:keys [spec source root] :as inputs} compile!-fn]
  (let [artifact-key (cache-key inputs)
        paths        (artifact-paths {:root root :target (:target inputs)
                                      :ns (:ns spec) :name (:name spec)
                                      :hash artifact-key})]
    (if (library-present? paths)
      (assoc paths :hash artifact-key :cached? true)
      (do
        (compile!-fn {:source       source
                      :source-path  (:source-path paths)
                      :library-path (:library-path paths)
                      :ctx          {:var (var-symbol spec)
                                     :signature (:signature spec)}})
        (write-manifest! paths inputs artifact-key)
        (assoc paths :hash artifact-key :cached? false)))))
