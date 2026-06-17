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
            [clj-zig.fs :as fs]
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
  file recompiles; a body with no imports hashes exactly as before. External
  Zig modules enter as `:modules`, a name-to-fingerprint map, so a changed
  module relinks its dependents while leaving every other key untouched."
  [{:keys [spec body source deps options zig-version target aux-files modules]}]
  (subs (sha256-hex (canonical (cond-> {:spec spec :body body :source source :deps deps
                                        :options options :zig-version zig-version
                                        :target target}
                                 (seq aux-files) (assoc :aux aux-files)
                                 (seq modules)   (assoc :modules modules))))
        0 12))

;; --- Pure: external-module fingerprint (ADR 34) -------------------------

(defn dir-signature
  "A cheap, order-independent signature over a module's file closure, keyed
  on each file's path, size, and mtime, no contents read. An untouched tree
  yields the same signature; a changed size or mtime flips it. `stats` is a
  seq of `{:path :size :mtime}` gathered by the shell."
  [stats]
  (sha256-hex (canonical (set (map (juxt :path :size :mtime) stats)))))

(defn content-fingerprint
  "The twelve-char content fingerprint of a module's file closure: each
  file's path paired with the hash of its contents. Order-independent, so it
  depends only on the set of files and their contents. `contents` is a seq
  of `{:path :content}` read by the shell."
  [contents]
  (subs (sha256-hex (canonical (into {} (map (fn [{:keys [path content]}]
                                               [path (sha256-hex content)]))
                                     contents)))
        0 12))

(defn memoized-fingerprint
  "Resolve a module's fingerprint against a prior memo `entry` (`{:signature
  :fingerprint}`, or nil when none) and the current `signature`. Returns
  `[fingerprint next-entry]`. When the signature matches the memo, the
  fingerprint is reused and `compute` is never called; otherwise `compute`
  produces a fresh fingerprint that the returned entry records. Pure; the
  atom that carries the entry across calls is the shell."
  [entry signature compute]
  (if (= (:signature entry) signature)
    [(:fingerprint entry) entry]
    (let [fp (compute)]
      [fp {:signature signature :fingerprint fp}])))

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

;; --- Shell: external-module fingerprint (ADR 34) ------------------------

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

(defn- git-fingerprint
  "The twelve-char fingerprint of a pinned `:git/sha` module reference,
  derived from the sha and root alone: a pinned ref is already
  content-addressed, so no file is read."
  [{:keys [git/sha root]}]
  (subs (sha256-hex (canonical {:git/sha sha :root root})) 0 12))

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
  "The twelve-char fingerprint for one external module reference, the single
  value it contributes to a dependent function's content hash (ADR 34). A
  pinned `:git/sha` ref fingerprints from the sha and root, with no
  filesystem read; a dev `:path` ref fingerprints its file closure, memoized
  behind the cheap `dir-signature` so an unchanged tree reuses the
  fingerprint without rereading contents. `fs-io` supplies `:stat` and
  `:read` over the closure; tests inject fakes."
  [ref {:keys [stat read]}]
  (if (:git/sha ref)
    (git-fingerprint ref)
    (let [path        (:path ref)
          signature   (dir-signature (stat path))
          [fp entry]  (memoized-fingerprint (get @module-fingerprint-cache path)
                                            signature
                                            #(content-fingerprint (read path)))]
      (swap! module-fingerprint-cache assoc path entry)
      fp)))

(defn modules-fingerprint
  "The fingerprint of each external module in a `name -> ref` map, keyed by
  import name, for the content hash; nil when there are none. Two wrappers
  over the same module share a fingerprint, so the cache stays
  content-addressed."
  ([modules] (modules-fingerprint modules fs-io))
  ([modules io]
   (not-empty
    (reduce-kv (fn [m name ref] (assoc m name (module-fingerprint ref io)))
               {} modules))))

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
  (let [paths (artifact-paths {:root root :target (:target inputs)
                               :ns (:ns spec) :name (:name spec)
                               :hash (cache-key inputs)})]
    (fs/delete-recursively! (io/file (:dir paths)))))

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
  (zig-version)     ;; => "0.16.0"

  ;; The classpath layout a baked library ships under: the loader looks a
  ;; resource up by this path before compiling.
  ;; => "clj-zig/native/linux-x86_64/app.core/add-83a1c0f9e1b2/libadd-83a1c0f9e1b2.so"
  (bundled-resource-path {:target "linux-x86_64" :ns 'app.core
                          :name 'add :hash "83a1c0f9e1b2"}))
