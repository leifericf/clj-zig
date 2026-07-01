(ns clj-zig.cache
  "Content-address generated artifacts. The hash of the normalized spec,
  body, dependencies, options, Zig version, and target becomes the artifact
  path, so an unchanged form reuses its library and a changed form gets a
  fresh one. The JVM never reloads a stale library.

      .clj-zig/cache/macos-aarch64/app.core/add-83a1c0f9e1b2dead/
        source.zig
        libadd-83a1c0f9e1b2dead.dylib
        manifest.edn

  This namespace owns the cache domain: the pure content-address logic
  (hashing, the cache key, artifact and resource paths, the module
  fingerprint primitives) and the effectful artifact resolution
  (filesystem fingerprinting, the classpath bundled-library lookup, the
  manifest, and the compile-or-reuse ensure-library!). The pure and
  effectful functions sit side by side behind section comments; the
  split is function-level, not namespace-level."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clj-zig.fs :as fs]
            [clj-zig.toolchain :as toolchain]))

;; --- Pure: hashing and the cache key -------------------------------------

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

(def ^:private fingerprint-width
  "The hex digits retained from the SHA-256 digest for a content-address
  fingerprint, shared by `cache-key`, `content-fingerprint`, and
  `git-fingerprint`. Sixteen hex digits is 64 bits, comfortably past the
  birthday bound for the number of artifacts this cache will ever hold."
  16)

(defn- fingerprint
  "The truncated SHA-256 digest of `x`'s canonical form: the single
  content-address primitive every cache fingerprint shares. `dir-signature`
  alone keeps the full digest, since it is compared, not stored in a path."
  [x]
  (subs (sha256-hex (canonical x)) 0 fingerprint-width))

(defn- present?
  "True when `x` should enter the content hash: non-nil, and when it is a
  collection or string, non-empty. Safer than `seq` on the gate, which
  throws on a non-seqable value (a number, a keyword); `present?` keeps
  such a value so `canonical` renders it."
  [x]
  (cond
    (nil? x)                        false
    (or (coll? x) (string? x))      (some? (seq x))
    :else                           true))

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
  (fingerprint (cond-> {:spec spec :body body :source source
                        :options options :zig-version zig-version
                        :target target}
                 (present? deps)      (assoc :deps deps)
                 (present? aux-files) (assoc :aux aux-files)
                 (present? modules)   (assoc :modules modules))))

;; --- Pure: external-module fingerprint primitives (ADR 34) ----------------

(defn dir-signature
  "A cheap, order-independent signature over a module's file closure, keyed
  on each file's path, size, and mtime, no contents read. An untouched tree
  yields the same signature; a changed size or mtime flips it. `stats` is a
  seq of `{:path :size :mtime}` gathered by the effectful fingerprint path."
  [stats]
  (sha256-hex (canonical (set (map (juxt :path :size :mtime) stats)))))

(defn content-fingerprint
  "The content fingerprint of a module's file closure: each file's path
  paired with the hash of its contents. Order-independent, so it depends
  only on the set of files and their contents. `contents` is a seq of
  `{:path :content}` read by the effectful fingerprint path."
  [contents]
  (fingerprint (into {} (map (fn [{:keys [path content]}]
                               [path (sha256-hex content)]))
                     contents)))

(defn memoized-fingerprint
  "Resolve a module's fingerprint against a prior memo `entry` (`{:signature
  :fingerprint}`, or nil when none) and the current `signature`. Returns
  `[fingerprint next-entry]`. When the signature matches the memo, the
  fingerprint is reused and `compute` is never called; otherwise `compute`
  produces a fresh fingerprint that the returned entry records. Pure; the
  atom that carries the entry across calls is effectful."
  [entry signature compute]
  (if (= (:signature entry) signature)
    [(:fingerprint entry) entry]
    (let [fp (compute)]
      [fp {:signature signature :fingerprint fp}])))

(defn git-fingerprint
  "The fingerprint of a pinned `:git/sha` module reference, derived from
  the sha and root alone: a pinned ref is already content-addressed, so no
  file is read. The pinned counterpart of `content-fingerprint` for a dev
  `:path` ref; `module-fingerprint` chooses between them."
  [{:keys [git/sha root]}]
  (fingerprint {:git/sha sha :root root}))

;; --- Pure: paths ---------------------------------------------------------

(defn- extension-for-target
  "The shared-library suffix for a target triple, without the dot."
  [target]
  (cond
    (str/includes? target "macos")   "dylib"
    (str/includes? target "windows") "dll"
    :else                            "so"))

(defn- escapes-segment?
  "True when the string form of `x` could break out of a single cache path
  segment: a path separator (`/` or `\\`), a NUL byte, or the parent token
  `..` standing alone. A name with internal dots stays a single segment and
  does not climb, so only `..` as a whole segment is rejected, mirroring the
  import-escape rule in `clj-zig.imports`."
  [x]
  (let [s (str x)]
    (or (str/includes? s "/")
        (str/includes? s "\\")
        (str/includes? s "\u0000")
        (= s ".."))))

(defn- segment
  "The string form of the cache path component named `label` (`x`), checked
  against `escapes-segment?`. The cache interpolates `ns`, `name`, `target`,
  and `hash` straight into filesystem and resource paths, so each is gated
  here; a value that could escape its segment is refused as data."
  [label x]
  (let [s (str x)]
    (when (escapes-segment? s)
      (throw (ex-info (str "Refusing a cache path component that escapes its"
                           " segment: " label " = " (pr-str s))
                      {:level        :error
                       :error/code   :clj-zig/unsafe-path-component
                       :component    label
                       :value        s})))
    s))

(defn artifact-paths
  "The artifact directory and file paths for a build, under `root`
  (default `.clj-zig/cache`). Each path component is checked, so a malformed
  spec cannot write outside the cache."
  [{:keys [root target ns name hash]}]
  (let [root   (or root ".clj-zig/cache")
        target (segment :target target)
        ns     (segment :ns ns)
        name   (segment :name name)
        hash   (segment :hash hash)
        dir    (str root "/" target "/" ns "/" name "-" hash)]
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
  not. Components are checked, as in `artifact-paths`."
  [{:keys [target ns name hash]}]
  (let [target (segment :target target)
        ns     (segment :ns ns)
        name   (segment :name name)
        hash   (segment :hash hash)]
    (str resource-root "/" target "/" ns "/" name "-" hash
         "/lib" name "-" hash "." (extension-for-target target))))

;; --- Pure: external-module roots -----------------------------------------

(defn- module-root
  "The local source path a module reference compiles from, or nil when none
  is available here. A dev `:path` ref, or a pinned ref carrying a local
  `:path` checkout (ADR 36), uses that path. A pinned ref with no `:path` has
  no source in this environment (as for a consumer resolving only a baked
  library), so it yields nil and compiles only if its baked artifact is
  missing."
  [_name ref]
  (:path ref))

(defn module-roots
  "Each resolvable external module's root source path, keyed by import name,
  for the compile shell to pass as `-M<name>=<root>`; nil when none resolve. A
  pinned reference with no local checkout is omitted (ADR 36). The path
  counterpart to `modules-fingerprint`, which feeds the content hash."
  [modules]
  (not-empty (reduce-kv (fn [m name ref]
                          (if-let [root (module-root name ref)]
                            (assoc m name root)
                            m))
                        {} modules)))

;; --- Effectful: environment -----------------------------------------------

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

;; --- Effectful: external-module fingerprint over the filesystem (ADR 34) --

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
    (git-fingerprint ref)
    (let [path        (:path ref)
          signature   (dir-signature (stat path))
          [fp entry]  (memoized-fingerprint (get @module-fingerprint-cache path)
                                                  signature
                                                  #(content-fingerprint (read path)))]
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

;; --- Effectful: artifact resolution ---------------------------------------

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
    (cache-key {:spec s :body "return x + y;" :options {:optimize "ReleaseSafe"}
                :zig-version "0.16.0" :target "macos-aarch64"}))

  ;; The classpath layout a baked library ships under: the loader looks a
  ;; resource up by this path before compiling.
  ;; => "clj-zig/native/linux-x86_64/app.core/add-83a1c0f9e1b2/libadd-83a1c0f9e1b2.so"
  (bundled-resource-path {:target "linux-x86_64" :ns 'app.core
                          :name 'add :hash "83a1c0f9e1b2"})

  (let [s (spec/build-spec '{:ns app.core :name add :signature [x :i64 y :i64 :ret :i64]})]
    (cache-key {:spec s :body "return x + y;" :options {:optimize "ReleaseSafe"}
                :zig-version (zig-version) :target (target-triple)}))
  (target-triple)   ;; => "macos-aarch64"
  (zig-version))    ;; => "0.16.0"
