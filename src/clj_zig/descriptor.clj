(ns clj-zig.descriptor
  "The `:zig/*` and `:c/*` option language a `defnz` descriptor and a
  namespace-level `zig-deps` carry: parse, validate, and normalize the
  compile options (C-interop flags, optimize mode, build flags) and the
  external Zig modules into the option maps the build shell and the
  content hash consume. Pure: a descriptor map goes in, a validated
  option or module map comes out, or a diagnostic is thrown."
  (:require [clojure.string :as str]
            [clj-zig.compiler :as compiler]))

(declare zig-build-flags)

(defn c-options
  "The C-interop compile options a descriptor's `:c/*` keys carry, or nil
  when it carries none. These reach `zig build-lib` as flags and enter the
  content hash. Shared by a per-function `{:zig/file ...}` descriptor and a
  namespace-level `zig-deps`."
  [descriptor]
  (not-empty
   (cond-> {}
     (:c/include-path descriptor)        (assoc :include-path (vec (:c/include-path descriptor)))
     (:c/system-include-path descriptor) (assoc :system-include-path (vec (:c/system-include-path descriptor)))
     (:c/link-path descriptor)           (assoc :link-path (vec (:c/link-path descriptor)))
     (:c/link descriptor)                (assoc :link (vec (:c/link descriptor))))))

(def optimize-modes
  "The Zig optimize modes a descriptor or `zig-deps` may name. The keyword
  form is canonical; it lowers to the string `zig build-lib -O` takes."
  #{:Debug :ReleaseSafe :ReleaseFast :ReleaseSmall})

(defn- optimize-option
  "The `{:optimize <mode-string>}` entry for a descriptor's
  `:zig/optimize`, or nil when it declares none. The keyword must be one
  of `optimize-modes`; anything else throws `:clj-zig/bad-optimize-mode`
  so a typo fails before the compile, not as a confusing Zig error."
  [descriptor]
  (when-let [mode (:zig/optimize descriptor)]
    (when-not (optimize-modes mode)
      (throw (ex-info (str ":zig/optimize must be one of "
                           (->> optimize-modes sort (map name) (str/join ", "))
                           "; got " (pr-str mode) ".")
                      {:level :error :error/code :clj-zig/bad-optimize-mode
                       :mode mode})))
    {:optimize (name mode)}))

(defn descriptor-options
  "The compile options a descriptor carries: its C-interop flags, its
  optimize mode, and its Zig build flags (`:zig/single-threaded`,
  `:zig/pic`, `:zig/stack-check`, `:zig/panic-fn`). Returns nil when it
  carries none. Shared by a per-function descriptor and a namespace-level
  `zig-deps`, so both paths layer options the same way."
  [descriptor]
  (not-empty (merge (c-options descriptor)
                    (optimize-option descriptor)
                    (zig-build-flags descriptor))))

(def ^:private recognized-zig-keys
  "The curated `:zig/*` keys ADR 48 reserves. Unknown `:zig/*` keys are
  rejected at macro expansion time with `:clj-zig/unknown-zig-option`."
  #{:zig/optimize :zig/file :zig/fn :zig/raw :zig/symbol :zig/modules
    :zig/panic-fn :zig/single-threaded :zig/pic :zig/stack-check})

(defn- zig-build-flags
  "The Zig build flags a descriptor carries beyond optimize mode:
  `:single-threaded`, `:pic`, `:stack-check` (all boolean, included only
  when truthy), and `:panic-fn` (a string). These reach `zig build-lib`
  as `-f` flags and enter the content hash."
  [descriptor]
  (not-empty
   (cond-> {}
     (:zig/single-threaded descriptor) (assoc :single-threaded true)
     (:zig/pic descriptor)              (assoc :pic true)
     (:zig/stack-check descriptor)      (assoc :stack-check true)
     (:zig/panic-fn descriptor)         (assoc :panic-fn (str (:zig/panic-fn descriptor))))))

(defn validate-descriptor-keys
  "Throw when `descriptor` carries a `:zig/*` key outside the curated set
  (ADR 48). Non-`:zig/*` keys pass through to Var metadata. Public so the
  macro expansions call it on the attr-map, body descriptor, and
  `zig-deps`."
  [descriptor]
  (doseq [k (keys descriptor)]
    (when (and (keyword? k)
               (= "zig" (namespace k))
               (not (recognized-zig-keys k)))
      (throw (ex-info (str "Unknown :" (namespace k) "/" (name k)
                           " key; recognized :zig/* keys are: "
                           (->> recognized-zig-keys sort (map name) (str/join ", "))
                           ".")
                      {:level :error
                       :error/code :clj-zig/unknown-zig-option
                       :key k})))))

(def ^:private reserved-module-names
  "Module names Zig supplies itself; a dependency may not shadow them."
  #{"std" "builtin" "root"})

(defn- normalize-module
  "Validate and canonicalize one external-module reference, keyed by the
  name a body imports: a dev `:path` to the module root, or a pinned
  `:git/sha` with a `:root`. An optional `:zig/version` must match the
  pinned compiler. Throws a diagnostic with a specific `:error/code` for
  each malformed shape."
  [module-name descriptor]
  (when-not (string? module-name)
    (throw (ex-info (str "A Zig module name must be a string, got "
                         (pr-str module-name) ".")
                    {:level :error :error/code :clj-zig/bad-module-name
                     :module module-name})))
  (when (reserved-module-names module-name)
    (throw (ex-info (str "The Zig module name " (pr-str module-name)
                         " is reserved by the compiler.")
                    {:level :error :error/code :clj-zig/reserved-module-name
                     :module module-name})))
  (when-not (map? descriptor)
    (throw (ex-info (str "The Zig module " (pr-str module-name)
                         " needs a descriptor map, got " (pr-str descriptor) ".")
                    {:level :error :error/code :clj-zig/bad-module-ref
                     :module module-name})))
  (when-let [v (:zig/version descriptor)]
    (when (not= v compiler/pinned-version)
      (throw (ex-info (str "The Zig module " (pr-str module-name) " pins Zig "
                           v " but clj-zig pins " compiler/pinned-version ".")
                      {:level :error :error/code :clj-zig/module-zig-version-mismatch
                       :module module-name
                       :requested v
                       :pinned compiler/pinned-version}))))
  (cond
    ;; A pinned reference fingerprints from sha and root; an optional :path is
    ;; a local checkout bake and the dev loop compile from (ADR 36).
    (and (:git/sha descriptor) (:root descriptor))
    (cond-> {:git/sha (str (:git/sha descriptor)) :root (str (:root descriptor))}
      (:path descriptor) (assoc :path (str (:path descriptor))))

    (:path descriptor)
    {:path (str (:path descriptor))}

    :else
    (throw (ex-info (str "The Zig module " (pr-str module-name)
                         " needs a :path, or a :git/sha with a :root.")
                    {:level :error :error/code :clj-zig/module-missing-root
                     :module module-name}))))

(defn zig-modules
  "The external Zig modules a descriptor's `:zig/modules` declares,
  normalized and keyed by import name, or nil when it declares none. Each
  becomes a `-M name=<root>` module the bodies in the namespace may
  `@import` (ADR 34). Shared by a namespace-level `zig-deps`."
  [descriptor]
  (when-let [modules (:zig/modules descriptor)]
    (when-not (map? modules)
      (throw (ex-info (str ":zig/modules must be a map of name to descriptor, got "
                           (pr-str modules) ".")
                      {:level :error :error/code :clj-zig/bad-modules
                       :modules modules})))
    (not-empty
     (reduce-kv (fn [m module-name desc]
                  (assoc m module-name (normalize-module module-name desc)))
                {} modules))))
