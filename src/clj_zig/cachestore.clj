(ns clj-zig.cachestore
  "The shell over `clj-zig.cache`: resolve the toolchain version and target,
  fingerprint external modules over the filesystem (memoized behind the
  cheap directory signature), and read, write, and resolve the artifact
  cache. The pure content-address logic -- hashing, the cache key, artifact
  and resource paths, and the fingerprint primitives -- lives in
  `clj-zig.cache`; this namespace owns the IO, the classpath, and the
  per-process module-fingerprint memo (ADR 16/34)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clj-zig.cache :as cache]
            [clj-zig.fs :as fs]
            [clj-zig.toolchain :as toolchain]))

;; --- Environment ---------------------------------------------------------

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

;; --- External-module fingerprint over the filesystem (ADR 34) ------------

(defn- closure-files
  "The `.zig` files in a module root's directory tree. The closure is a
  heuristic for the module's sources: every Zig file under the directory
  holding the root. A path with no directory yields an empty closure."
  [root-path]
  (let [root (io/file root-path)
        dir  (if (.isDirectory root) root (.getParentFile root))]
    (when dir
      (->> (file-seq dir)
           (filter (fn [^java.io.File f]
                     (and (.isFile f) (str/ends-with? (.getName f) ".zig"))))))))

(defn- stat-closure
  "The `dir-signature` stat entries for a module root's closure."
  [root-path]
  (mapv (fn [^java.io.File f]
          {:path (.getPath f) :size (.length f) :mtime (.lastModified f)})
        (closure-files root-path)))

(defn- read-closure
  "The `content-fingerprint` content entries for a module root's closure."
  [root-path]
  (mapv (fn [^java.io.File f] {:path (.getPath f) :content (slurp f)})
        (closure-files root-path)))

(defonce ^:private module-fingerprint-cache
  ;; Per module-root path, the last seen `{:signature :fingerprint}`, so an
  ;; unchanged tree reuses its fingerprint without rereading contents.
  (atom {}))

(def fs-io
  "The filesystem reader `module-fingerprint` uses outside tests: `:stat`
  gathers a closure's stat entries for the signature, `:read` its contents
  for the fingerprint."
  {:stat stat-closure :read read-closure})

(defn module-fingerprint
  "The fingerprint for one external module reference, the single value it
  contributes to a dependent function's content hash (ADR 34). A
  pinned `:git/sha` ref fingerprints from the sha and root, with no
  filesystem read; a dev `:path` ref fingerprints its file closure, memoized
  behind the cheap `dir-signature` so an unchanged tree reuses the
  fingerprint without rereading contents. `fs-io` supplies `:stat` and
  `:read` over the closure; tests inject fakes."
  [ref {:keys [stat read]}]
  (if (:git/sha ref)
    (cache/git-fingerprint ref)
    (let [path        (:path ref)
          signature   (cache/dir-signature (stat path))
          [fp entry]  (cache/memoized-fingerprint (get @module-fingerprint-cache path)
                                                  signature
                                                  #(cache/content-fingerprint (read path)))]
      (swap! module-fingerprint-cache assoc path entry)
      fp)))

(defn modules-fingerprint
  "The fingerprint of each external module in a map from name to ref, keyed by
  import name, for the content hash; nil when there are none. Two wrappers
  over the same module share a fingerprint, so the cache stays
  content-addressed."
  ([modules] (modules-fingerprint modules fs-io))
  ([modules io]
   (not-empty
    (reduce-kv (fn [m name ref] (assoc m name (module-fingerprint ref io)))
               {} modules))))

;; --- Artifact resolution -------------------------------------------------

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
  "Read the manifest for a built artifact, or nil when none exists. The
  manifest is written as data, so it round-trips through the EDN reader
  rather than the Clojure reader: a tampered cache dir cannot then craft
  a payload that leans on Clojure-reader quirks."
  [paths]
  (let [f (io/file (:manifest-path paths))]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn clean!
  "Remove the entire artifact cache under `root` (default `.clj-zig/cache`)."
  ([] (clean! ".clj-zig/cache"))
  ([root]
   (let [d (io/file root)]
     (when (.exists d) (fs/delete-recursively! d)))))

(defn evict!
  "Remove the single cached artifact for these `inputs`, so the next build
  recompiles instead of reusing it. Inputs match `ensure-library!`."
  [{:keys [spec root] :as inputs}]
  (let [paths (cache/artifact-paths {:root root :target (:target inputs)
                                     :ns (:ns spec) :name (:name spec)
                                     :hash (cache/cache-key inputs)})]
    (fs/delete-recursively! (io/file (:dir paths)))))

(defn- bundled-library
  "The classpath URL of a baked library for these coordinates, or nil when
  none ships on the classpath."
  [coords]
  (io/resource (cache/bundled-resource-path coords)))

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
  (let [artifact-key (cache/cache-key inputs)
        coords       {:target (:target inputs) :ns (:ns spec) :name (:name spec)
                      :hash artifact-key}
        paths        (cache/artifact-paths (assoc coords :root root))
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
      (let [roots   (:module-roots inputs)
            missing (remove (set (keys roots)) (keys (:modules inputs)))]
        ;; A fresh compile needs every declared module's source. A pinned
        ;; module with no local checkout (ADR 36) resolves a baked library
        ;; above; reaching here means none shipped for this target.
        (when (seq missing)
          (throw (ex-info (str "Cannot compile: Zig module(s) "
                               (str/join ", " (map pr-str missing))
                               " are pinned with no local checkout, and no baked"
                               " library ships for " (:target inputs) ".")
                          {:level :error :error/code :clj-zig/module-not-checked-out
                           :modules (vec missing) :target (:target inputs)})))
        (compile!-fn {:source       source
                      :source-path  (:source-path paths)
                      :library-path (:library-path paths)
                      :options      (:options inputs)
                      :module-roots roots
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
    (cache/cache-key {:spec s :body "return x + y;" :options {:optimize "ReleaseSafe"}
                      :zig-version (zig-version) :target (target-triple)}))
  (target-triple)   ;; => "macos-aarch64"
  (zig-version))    ;; => "0.16.0"
