(ns clj-zig.cache
  "Content-address generated artifacts. The hash of the
  normalized spec, body, dependencies, options, Zig version, and target
  becomes the artifact path, so an unchanged form reuses its library and
  a changed form gets a fresh one. The JVM never reloads a stale library.

      .clj-zig/cache/macos-aarch64/app.core/add-83a1c0f9e1b2/
        source.zig
        libadd-83a1c0f9e1b2.dylib
        manifest.edn

  `cache-key` and `artifact-paths` are pure; resolving the toolchain
  version and target, and reading and writing the filesystem, are the
  shell."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clj-zig.toolchain :as toolchain]))

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
  "The content hash for these build inputs. The generated `source` enters
  the hash directly, so a change to the source generator yields a new key
  even when the spec and body are unchanged; the spec, body, dependencies,
  options, Zig version, and target enter it as well. A body that imports
  other Zig files adds their contents under `:aux`, so editing an imported
  file recompiles; a body with no imports hashes exactly as before."
  [{:keys [spec body source deps options zig-version target aux-files]}]
  (subs (sha256-hex (canonical (cond-> {:spec spec :body body :source source :deps deps
                                        :options options :zig-version zig-version
                                        :target target}
                                 (seq aux-files) (assoc :aux aux-files))))
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
  (default `.clj-zig/cache`)."
  [{:keys [root target ns name hash]}]
  (let [root (or root ".clj-zig/cache")
        dir  (str root "/" target "/" ns "/" name "-" hash)]
    {:dir           dir
     :source-path   (str dir "/source.zig")
     :library-path  (str dir "/lib" name "-" hash "." (extension-for-target target))
     :manifest-path (str dir "/manifest.edn")}))

(def ^:private resource-root
  "The classpath root under which a library ships its baked native code.
  The layout below it mirrors the filesystem cache, so a baked artifact is
  found by the same target, namespace, name, and hash."
  "clj-zig/native")

(defn bundled-resource-path
  "The classpath resource path for a baked library, mirroring the cache
  layout under the resource root. The library's content hash is in the
  path, so a resource matches a function's hash for a target or it does
  not."
  [{:keys [target ns name hash]}]
  (str resource-root "/" target "/" ns "/" name "-" hash
       "/lib" name "-" hash "." (extension-for-target target)))

;; --- Shell: environment, filesystem -------------------------------------

(defn- var-symbol [spec]
  (symbol (str (:ns spec)) (str (:name spec))))

(defn zig-version
  "The `zig` compiler version string, part of the cache key."
  []
  (str/trim (:out (sh/sh (toolchain/zig-exe) "version"))))

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

(defn library-present?
  "True when a usable library exists. A zero-byte file (left by a failed
  build) does not count, so a poisoned path recompiles instead of loading
  an invalid library."
  [paths]
  (let [f (io/file (:library-path paths))]
    (and (.exists f) (pos? (.length f)))))

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

(defn read-manifest
  "Read the manifest for a built artifact, or nil when none exists."
  [paths]
  (let [f (io/file (:manifest-path paths))]
    (when (.exists f)
      (read-string (slurp f)))))

(defn- delete-recursively [^java.io.File f]
  (when (.isDirectory f)
    (run! delete-recursively (.listFiles f)))
  (.delete f))

(defn clean!
  "Remove the entire artifact cache under `root` (default `.clj-zig/cache`)."
  ([] (clean! ".clj-zig/cache"))
  ([root]
   (let [d (io/file root)]
     (when (.exists d) (delete-recursively d)))))

(defn evict!
  "Remove the single cached artifact for these `inputs`, so the next build
  recompiles instead of reusing it. Inputs match `ensure-library!`."
  [{:keys [spec root] :as inputs}]
  (let [paths (artifact-paths {:root root :target (:target inputs)
                               :ns (:ns spec) :name (:name spec)
                               :hash (cache-key inputs)})]
    (delete-recursively (io/file (:dir paths)))))

(defn- bundled-library
  "The classpath URL of a baked library for these coordinates, or nil when
  none ships on the classpath."
  [coords]
  (io/resource (bundled-resource-path coords)))

(defn- extract-bundled!
  "Copy a baked library resource into the filesystem cache at
  `library-path`, so a bundled artifact loads like a locally built one."
  [resource library-path]
  (let [out (io/file library-path)]
    (io/make-parents out)
    (with-open [in (io/input-stream resource)]
      (io/copy in out))))

(defn ensure-library!
  "Return the cached artifact paths for these `inputs`, resolving a library
  in three steps: a present filesystem artifact, then a baked library on
  the classpath, then a fresh compile through `compile!-fn`. A baked
  library is extracted into the cache and loaded without invoking Zig.
  Identical inputs reuse a resolved library; any change resolves a fresh
  path. `inputs` carries `:spec`, `:body`, `:source`, `:deps`, `:options`,
  `:zig-version`, `:target`, and an optional `:root`."
  [{:keys [spec source root aux-files] :as inputs} compile!-fn]
  (let [artifact-key (cache-key inputs)
        coords       {:target (:target inputs) :ns (:ns spec) :name (:name spec)
                      :hash artifact-key}
        paths        (artifact-paths (assoc coords :root root))
        bundled      (bundled-library coords)]
    (cond
      (library-present? paths)
      (assoc paths :hash artifact-key :cached? true)

      bundled
      (do
        (extract-bundled! bundled (:library-path paths))
        (write-manifest! paths inputs artifact-key)
        (assoc paths :hash artifact-key :cached? false :bundled? true))

      :else
      (do
        (compile!-fn {:source       source
                      :source-path  (:source-path paths)
                      :library-path (:library-path paths)
                      :options      (:options inputs)
                      :aux-files    (mapv (fn [{:keys [rel text]}]
                                            {:path (str (:dir paths) "/" rel) :text text})
                                          aux-files)
                      :ctx          {:var (var-symbol spec)
                                     :signature (:signature spec)}})
        (write-manifest! paths inputs artifact-key)
        (assoc paths :hash artifact-key :cached? false)))))

(comment
  (require '[clj-zig.spec :as spec])
  (let [s (spec/build-spec '{:ns app.core :name add :signature [x :i64 y :i64 :ret :i64]})]
    (cache-key {:spec s :body "return x + y;" :options {:optimize "ReleaseSafe"}
                :zig-version (zig-version) :target (target-triple)}))
  (target-triple)   ;; => "macos-aarch64"
  (zig-version))    ;; => "0.16.0"
