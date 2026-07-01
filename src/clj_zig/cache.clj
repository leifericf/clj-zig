(ns clj-zig.cache
  "Content-address generated artifacts. The hash of the normalized spec,
  body, dependencies, options, Zig version, and target becomes the artifact
  path, so an unchanged form reuses its library and a changed form gets a
  fresh one. The JVM never reloads a stale library.

      .clj-zig/cache/macos-aarch64/app.core/add-83a1c0f9e1b2dead/
        source.zig
        libadd-83a1c0f9e1b2dead.dylib
        manifest.edn

  This is the PURE core of content addressing: hashing, the cache key,
  artifact and resource paths, and the module-fingerprint primitives. It
  depends only on `clojure.string`; resolving the toolchain version and
  target, reading and writing the filesystem, and the module-fingerprint
  memo live in `clj-zig.cachestore` (the shell)."
  (:require [clojure.string :as str]))

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
  seq of `{:path :size :mtime}` gathered by the shell."
  [stats]
  (sha256-hex (canonical (set (map (juxt :path :size :mtime) stats)))))

(defn content-fingerprint
  "The content fingerprint of a module's file closure: each file's path
  paired with the hash of its contents. Order-independent, so it depends
  only on the set of files and their contents. `contents` is a seq of
  `{:path :content}` read by the shell."
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
  atom that carries the entry across calls is the shell."
  [entry signature compute]
  (if (= (:signature entry) signature)
    [(:fingerprint entry) entry]
    (let [fp (compute)]
      [fp {:signature signature :fingerprint fp}])))

(defn git-fingerprint
  "The fingerprint of a pinned `:git/sha` module reference, derived from
  the sha and root alone: a pinned ref is already content-addressed, so no
  file is read. The pinned counterpart of `content-fingerprint` for a dev
  `:path` ref; `cachestore/module-fingerprint` chooses between them."
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
  counterpart to `cachestore/modules-fingerprint`, which feeds the content
  hash."
  [modules]
  (not-empty (reduce-kv (fn [m name ref]
                          (if-let [root (module-root name ref)]
                            (assoc m name root)
                            m))
                        {} modules)))

(comment
  (require '[clj-zig.spec :as spec])
  (let [s (spec/build-spec '{:ns app.core :name add :signature [x :i64 y :i64 :ret :i64]})]
    (cache-key {:spec s :body "return x + y;" :options {:optimize "ReleaseSafe"}
                :zig-version "0.16.0" :target "macos-aarch64"}))

  ;; The classpath layout a baked library ships under: the loader looks a
  ;; resource up by this path before compiling.
  ;; => "clj-zig/native/linux-x86_64/app.core/add-83a1c0f9e1b2/libadd-83a1c0f9e1b2.so"
  (bundled-resource-path {:target "linux-x86_64" :ns 'app.core
                          :name 'add :hash "83a1c0f9e1b2"}))
