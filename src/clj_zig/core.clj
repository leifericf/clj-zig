(ns clj-zig.core
  "The `defnz` and `defz` defining forms. These macros stay thin: they
  parse the form into data, hand it to the pure pipeline (signature, then
  spec, then source) and the shell (compile or cache, then load, then
  bind), then rebind an ordinary Clojure Var. A user can reach every
  stage through the data functions without the macro.

  `defnz` defines a Clojure function backed by a Zig body. `defz`
  registers Zig declarations that the bodies in its namespace may call
  but that are not themselves callable from Clojure."
  (:require [clojure.string :as str]
            [clj-zig.cache :as cache]
            [clj-zig.compile :as compile]
            [clj-zig.descriptor :as descriptor]
            [clj-zig.diagnostics :as diagnostics]
            [clj-zig.ffm :as ffm]
            [clj-zig.imports :as imports]
            [clj-zig.infer :as infer]
            [clj-zig.layout :as layout]
            [clj-zig.signature :as signature]
            [clj-zig.source :as source]
            [clj-zig.spec :as spec]
            [clj-zig.toolchain :as toolchain]
            [clj-zig.type :as type]
            [clj-zig.zig :as zig]))

;; Namespace-scoped Zig declarations

(defonce ^:private zig-decls (atom {}))

(defonce ^:private named-types (atom {}))

(defn register-decl!
  "Register or replace a `defz` declaration in its namespace, preserving
  declaration order so the generated preamble is stable. Public because
  the `defz` expansion calls it from the user's namespace."
  [ns-sym decl-name decl-source]
  (swap! zig-decls update ns-sym
         (fn [decls]
           (let [decls (or decls [])
                 idx   (first (keep-indexed #(when (= (:name %2) decl-name) %1) decls))
                 entry {:name decl-name :source decl-source}]
             (if idx (assoc decls idx entry) (conj decls entry))))))

(defn register-type!
  "Register or replace a `deftypez` layout descriptor in its namespace.
  Public because the `deftypez` expansion calls it from the user's
  namespace."
  [ns-sym descriptor]
  (swap! named-types update ns-sym assoc (:name descriptor) descriptor))

(defn types-in
  "The map of named-type descriptors declared in `ns-sym`, keyed by name."
  [ns-sym]
  (get @named-types ns-sym {}))

(defonce ^:private ns-deps (atom {}))

(defonce ^:private ns-modules (atom {}))

(defn register-deps!
  "Register the namespace-level C-interop options, optimize mode, and
  external Zig modules for `ns-sym`, replacing any previous registration.
  Public because the `zig-deps` expansion calls it from the user's
  namespace."
  [ns-sym descriptor]
  (swap! ns-deps assoc ns-sym (descriptor/descriptor-options descriptor))
  (swap! ns-modules assoc ns-sym (descriptor/zig-modules descriptor toolchain/pinned-version)))

(defn deps-in
  "The namespace-level C-interop options registered for `ns-sym`, or nil."
  [ns-sym]
  (get @ns-deps ns-sym))

(defn modules-in
  "The namespace-level external Zig modules registered for `ns-sym`, keyed
  by import name, or nil."
  [ns-sym]
  (get @ns-modules ns-sym))

(defn- preamble-nodes
  "The declaration nodes for this namespace: the named-type structs and
  enums, then the `defz` declarations. Returns a vector of declaration
  nodes."
  [ns-sym]
  (let [structs (->> (vals (types-in ns-sym))
                     (sort-by (comp str :name))
                     (map layout/zig-decl))
        decls   (->> (get @zig-decls ns-sym)
                     (map :source)
                     (map zig/raw-decl))]
    (vec (concat structs decls))))

;; Establishing a native function

(defn build-inputs
  "The cache/compile inputs for `spec` and `body`: the generated source,
  prefixed with this namespace's `defz` declarations, plus the compiler
  identity that the content hash includes. The Zig version in the hash is
  the pinned version, not a live `zig version`: every machine pins the same
  compiler, so a baked library and a function built in place hash alike,
  and a consumer with no compiler reproduces the hash without running Zig.
  `gen` selects the source shape: inline splices the body string into a
  wrapper; file concatenates the user's file text and a wrapper that calls
  its `pub fn`; raw uses the file text as-is. `gen` also carries any
  C-interop `:options-extra`. Options layer by precedence: the optimize
  default, then the namespace `zig-deps`, then the descriptor, so a function
  inherits its namespace's link flags and may still override them. A file
  body that imports other Zig files carries them as `:aux-files`, reproduced
  beside the source."
  ([spec body] (build-inputs spec body {:mode :inline}))
  ([spec body {:keys [mode entry options-extra aux-files build-id] :or {mode :inline}}]
   (let [preamble    (preamble-nodes (:ns spec))
         mods        (modules-in (:ns spec))
         body-nodes  (case mode
                       (:raw :file) (if (str/blank? body) [] [(zig/raw-decl body)])
                       [])
         wrapper-nodes (case mode
                         :raw  []
                         :file (source/file-nodes spec entry)
                         (source/inline-nodes spec body))
         all-nodes   (vec (concat preamble body-nodes wrapper-nodes))
         rendered    (str (zig/render all-nodes) "\n")
         options     (merge {:optimize compile/default-optimize-mode}
                            (deps-in (:ns spec)) options-extra)
          ;; :zig/track-allocations (ADR 41) lives in :options, which
          ;; cache/cache-key hashes, so a profiling build gets its own
          ;; cache key (ADR 12) and never reuses a default library.
         src         (if (:track-allocations options)
                       (source/tracking-wrap rendered (:symbol spec))
                       rendered)]
      (cond-> {:spec        spec
               :body        body
               :source      src
               :deps        (zig/render preamble)
               :options     options
               :zig-version toolchain/pinned-version
               :target      (cache/target-id)
               :cpu         compile/default-cpu}
        aux-files (assoc :aux-files aux-files)
        build-id  (assoc :build-id build-id)
        mods      (assoc :modules      (cache/modules-fingerprint mods)
                        :module-roots (cache/module-roots mods))))))

(defn- module-info
  "The external modules a build linked, for inspection (ADR 34): each
  declared module's import name, content fingerprint, and provenance
  (`:local` for a dev `:path`, `:pinned` for a reproducible reference),
  sorted by name. Nil when the function depends on no modules."
  [{:keys [modules module-roots]}]
  (when modules
    (mapv (fn [[name fingerprint]]
            {:name name :fingerprint fingerprint
             :status (if (get module-roots name) :local :pinned)})
          (sort-by key modules))))

(defn artifact
  "Compile or reuse the native library for `spec` and `body`. Returns the
  inspection data describing the build: the spec, the body, the generated
  source, the library and source paths, the symbol, whether the library was
  reused (`:cached`) or freshly built (`:compiled`), and the external modules
  it links (`:modules`) when the namespace declares any."
  ([spec body] (artifact spec body {:mode :inline}))
   ([spec body gen]
    (let [inputs (build-inputs spec body gen)
          paths  (cache/ensure-library! inputs compile/compile!)
          modules (module-info inputs)]
      (cond-> {:spec             spec
               :body             body
               :generated-source (:source inputs)
               :library          (:library-path paths)
               :source-path      (:source-path paths)
               :symbol           (:symbol spec)
               :status           (if (:cached? paths) :cached :compiled)}
        modules (assoc :modules modules)))))

(defn establish!
  "Build the artifact for `spec` and `body` and bind its symbol. Returns
  the inspection data plus the native invoker under `:invoke`. Throws the
  compile diagnostic when the Zig does not build."
  ([spec body] (establish! spec body {:mode :inline}))
  ([spec body gen]
   (let [a (artifact spec body gen)]
     (assoc a :invoke (ffm/bind spec (:library a))))))

;; Binding and rebinding a Var

;; A defnz Var's wrap fn, kept so `recompile!` can rebind with the same
;; arglist and destructuring the macro built.
(defonce ^:private rebinders (atom {}))

;; Multi-arity rebind data: {the-var {:arity-specs [...] :invoke-table
;; {count invoke}}}. Defined here so `recompile!` (below) can reference it.
(defonce ^:private multi-rebinders (atom {}))

(defonce ^:private comptime-rebinders (atom {}))

;; A per-process counter for `recompile!` build-ids, so each forced rebuild
;; targets a unique cache path (see `recompile!`).
(defonce ^:private recompile-counter (atom 0))

(declare establish-multi-binding!)

(defn- nested-info
  "Build the ADR 47 nested inspection structure from the flat info map,
  keeping the flat keys alongside for backward compat. Each nested key
  groups the flat keys by domain: `:contract` holds the boundary spec,
  signature, and symbol; `:source` holds the body, generated source, and
  provenance; `:build` holds the library, status, and modules; `:lifecycle`
  is empty for single-arity and carries per-arity data for multi-arity."
  [info spec]
  (assoc info
         :contract {:spec spec
                    :signature (:signature spec)
                    :symbol (:symbol spec)}
         :source {:body (:body info)
                  :generated-source (:generated-source info)
                  :mode (:source-mode info)
                  :file (:source-file info)
                  :entry (:entry info)
                  :options-extra (:options-extra info)
                  :aux-files (:aux-files info)}
         :build {:library (:library info)
                 :status (:status info)
                 :modules (:modules info)}
         :lifecycle {}))

(defn- failure-data
  "The diagnostic data map for a build-time exception, carrying an
  ExceptionInfo's data through or wrapping a non-iex in a
  `:clj-zig/shell-failure`. Adds `:body` so the failure context reaches
  the diagnostic renderer. The single source for the failure-data shape
  so `establish-binding!` and `try-establish-arity` cannot drift."
  [^Throwable e body]
  (-> (if (instance? clojure.lang.ExceptionInfo e)
        (ex-data e)
        {:level      :error
         :error/code :clj-zig/shell-failure
         :message    (.getMessage e)
         :cause      e})
      (assoc :body body)))

(defn- failed-results
  "The failed-arity results in `results`, in order."
  [results]
  (filter #(= :failed (:status %)) results))

(defn establish-binding!
  "Establish the native function for `the-var` and rebind it. `wrap` turns
  the raw invoker into the public fn (it carries the arglist and any
  destructuring). On success the root is swapped, inspection metadata is
  merged, and any stale failure is cleared. On failure the last good root
  is left untouched, the failed attempt is recorded for inspection, and
  the rendered diagnostic is rethrown so the REPL shows it at once."
  ([the-var spec body var-meta wrap]
   (establish-binding! the-var spec body var-meta wrap {:mode :inline}))
  ([the-var spec body var-meta wrap gen]
   (try
     (let [result (establish! spec body gen)
           info   (cond-> (merge (dissoc result :invoke) {:source-mode (:mode gen)})
                    (:source-file gen)   (assoc :source-file (:source-file gen))
                    (:entry gen)         (assoc :entry (:entry gen))
                    (:options-extra gen) (assoc :options-extra (:options-extra gen))
                    (:aux-files gen)     (assoc :aux-files (:aux-files gen)))
            info   (nested-info info spec)]
       (alter-var-root the-var (constantly (wrap (:invoke result))))
       (swap! rebinders assoc the-var wrap)
       (alter-meta! the-var
                    #(-> (merge % var-meta {:clj-zig/info info})
                         (dissoc :clj-zig/failed-attempt)))
       the-var)
     (catch Throwable e
       (let [data (cond-> (failure-data e body)
                    (not (instance? clojure.lang.ExceptionInfo e))
                    (assoc :message (str "clj-zig hit an unexpected failure while"
                                         " building the native function: "
                                         (.getMessage e))))]
         (alter-meta! the-var assoc :clj-zig/failed-attempt data)
         (throw (ex-info (diagnostics/render data) data e)))))))

(defn gen-from-info
  "The build `gen` map reconstructed from a Var's inspection info: the
  source mode plus the file-mode keys (`:entry`, `:source-file`,
  `:options-extra`, `:aux-files`) that `establish-binding!` recorded.
  The single source for how `recompile!` and `bake` re-derive a
  function's build mode, so a new gen key lands in one place and the
  bake-equals-recompile hash invariant cannot drift (docs/04)."
  [{:keys [source-mode entry source-file options-extra aux-files]}]
  (cond-> {:mode (or source-mode :inline)}
    entry         (assoc :entry entry)
    source-file   (assoc :source-file source-file)
    options-extra (assoc :options-extra options-extra)
    aux-files     (assoc :aux-files aux-files)))

;; Comptime specialization (ADR 50/R4)

(defn- float-literal
  "Render a Clojure double as a Zig source literal. NaN and Infinity have no
  Zig literal syntax, so they cross through std.math.nan/inf parameterized
  by the target type."
  [value type-kw]
  (let [t (name type-kw)]
    (cond
      (Double/isNaN value)      (str "@import(\"std\").math.nan(" t ")")
      (Double/isInfinite value) (if (pos? value)
                                  (str "@import(\"std\").math.inf(" t ")")
                                  (str "-@import(\"std\").math.inf(" t ")"))
      :else                     (str value))))

(defn- zig-literal
  "Render a Clojure value as a Zig source literal for the given scalar type."
  [value type-kw]
  (case (:category (type/scalar-info type-kw))
    :bool  (if value "true" "false")
    :float (float-literal value type-kw)
    :int   (str value)))

(defn- comptime-const
  "Render one comptime `const` declaration, throwing for a nil value."
  [{:keys [binding type]} value]
  (when (nil? value)
    (throw (ex-info (str "Comptime parameter " binding
                         " cannot be nil; pass a "
                         (name type) " value.")
                    {:level :error
                     :error/code :clj-zig/comptime-nil-value
                     :param binding})))
  (str "const " binding ": " (name type) " = "
       (zig-literal value type) ";"))

(defn- comptime-prefix
  "The `const` declarations prepended to the body for a set of comptime
  values. Each `param` is `{:binding :type}`, and `values` is a parallel
  vector of the caller's comptime arguments. Throws for nil values since
  a null comptime argument has no valid Zig literal."
  [params values]
  (str/join "\n" (mapv comptime-const params values)))

(defn establish-comptime-binding!
  "Bind a comptime-specialized `defnz` Var. The body is compiled once per
  distinct set of comptime values (cached by content hash). At each call,
  comptime values are spliced into the body as `const` declarations, the
  modified body is compiled or reused from cache, and the non-comptime
  arguments are passed to the invoker.

  `spec` contains only the non-comptime params (the FFM boundary).
  `comptime-params` is a vector of `{:binding :type}` for the comptime
  params. `body` is the Zig body template. `gen` carries build options
  passed through to each `establish!` -- a `:build-id` here forces a
  forced rebuild onto a unique path (the same Windows-locked-DLL fix
  `recompile!` applies to the non-comptime path)."
  ([the-var spec body comptime-params var-meta wrap]
   (establish-comptime-binding! the-var spec body comptime-params var-meta wrap {}))
  ([the-var spec body comptime-params var-meta wrap gen]
   (let [n-comptime (count comptime-params)
         n-regular  (count (:params spec))
         total-arity (+ n-comptime n-regular)
         var-sym    (symbol (str (:ns spec)) (str (:name spec)))
         cache      (atom {})]
     (alter-var-root
      the-var
      (constantly
       (fn [& args]
         (when (not= (count args) total-arity)
           (throw (ex-info (str "Wrong number of arguments to " var-sym
                                ": expected " total-arity ", got " (count args))
                           {:level :error
                            :error/code :clj-zig/arity
                            :var var-sym
                            :expected total-arity
                            :actual (count args)})))
         (let [all-args   (vec args)
               reg-args   (subvec all-args 0 n-regular)
               ct-values  (subvec all-args n-regular)
               ct-key     (vec ct-values)
               invoke-fn  (if-let [cached (get @cache ct-key)]
                            cached
                            (let [prefix  (comptime-prefix comptime-params ct-values)
                                  full-body (if (str/blank? prefix)
                                              body
                                              (str prefix "\n" body))
                                  result  (establish! spec full-body gen)
                                  inv     (wrap (:invoke result))]
                              (swap! cache assoc ct-key inv)
                              inv))]
           (apply invoke-fn reg-args)))))
    (swap! rebinders assoc the-var :comptime)
    (swap! comptime-rebinders assoc the-var
           {:spec spec :body body :comptime-params comptime-params
            :var-meta var-meta :wrap wrap})
    (alter-meta! the-var #(merge % var-meta {:clj-zig/info {:spec spec :body body
                                                             :comptime-params comptime-params}}))
    the-var)))

(defn recompile!
  "Force a fresh build of `the-var`'s current spec and body, ignoring the
  cached artifact, and rebind. Returns the Var. Rebuilds in the same mode
  the function was defined with (inline, file, or raw). A multi-arity Var
  rebuilds every arity. A comptime Var clears its per-value cache and
  rebinds, so each distinct comptime combination rebuilds lazily on its
  next call (the values to build for are not known until called)."
  [the-var]
  (let [wrap (get @rebinders the-var)
        ;; A forced rebuild targets a unique path (a per-process build-id
        ;; nonce), so it never writes to the content-addressed path of a
        ;; library the JVM has already mapped. Windows holds a loaded DLL
        ;; locked against overwrite and deletion, so evicting and rewriting
        ;; the same path -- the Unix flow -- is not portable. The build-id
        ;; never enters the cache hash; only the on-disk path differs.
        build-id (str "rc" (swap! recompile-counter inc))]
    (cond
      (= :multi-arity wrap)
      (let [{:keys [arity-specs]} (get @multi-rebinders the-var)]
        (establish-multi-binding! the-var arity-specs {} {:build-id build-id}))

      (= :comptime wrap)
      (let [{:keys [spec body comptime-params var-meta wrap]} (get @comptime-rebinders the-var)]
        (establish-comptime-binding! the-var spec body comptime-params var-meta wrap {:build-id build-id}))

      (some? wrap)
      (let [{:keys [spec body] :as info} (:clj-zig/info (meta the-var))
            gen  (gen-from-info info)]
        (when-not (and spec wrap)
          (throw (ex-info "recompile! needs a defnz Var with a current binding."
                          {:level :error :error/code :clj-zig/not-recompilable
                           :var the-var})))
        (establish-binding! the-var spec body {} wrap (assoc gen :build-id build-id)))

      :else
      (throw (ex-info "recompile! needs a defnz Var with a current binding."
                      {:level :error :error/code :clj-zig/not-recompilable
                       :var the-var})))))

;; Multi-arity binding (ADR 51)

(defn- try-establish-arity
  "Compile one arity of a multi-arity defnz, returning a result map:
  `{:status :ok ...}` with the invoke fn and inspection info on success,
  or `{:status :failed ...}` with the rendered diagnostic data on failure.
  The catch isolates a failed arity so its siblings can still compile and
  keep their previous bindings."
  [{:keys [spec body wrap arity-count]} gen]
  (try
    (let [result (establish! spec body gen)
          invoke (wrap (:invoke result))
          info   (-> (merge (dissoc result :invoke) {:source-mode :inline})
                     (nested-info spec))]
      {:status :ok :arity-count arity-count
       :invoke invoke :info info
       :spec spec :body body})
    (catch Throwable e
      (let [data (failure-data e body)]
        {:status :failed :arity-count arity-count
         :error e :error-data data
         :spec spec :body body}))))

(defn- arity-invoke-table
  "The arity-count to invoke-fn table from the per-arity results: a
  successful arity's new invoke, or the previous invoke kept for a failed
  arity so it stays callable."
  [results prev-table]
  (into {}
        (map (fn [{:keys [arity-count status] :as r}]
               [arity-count
                (if (= :ok status) (:invoke r) (get prev-table arity-count))])
             results)))

(defn- arity-dispatch-fn
  "The Var's root fn: dispatch by argument count to each arity's invoke
  fn, throwing :clj-zig/arity for a count no arity owns."
  [arity-specs new-table]
  (let [first-spec (-> arity-specs first :spec)
        var-sym    (symbol (str (:ns first-spec)) (str (:name first-spec)))
        expected   (sort (keys new-table))]
    (fn [& args]
      (let [n (count args)]
        (if-let [invk (get new-table n)]
          (apply invk args)
          (throw (ex-info (str "Wrong number of arguments to " var-sym
                               ": expected one of " expected ", got " n)
                          {:level :error
                           :error/code :clj-zig/arity
                           :var var-sym
                           :expected expected
                           :actual n})))))))

(defn- multi-arity-info
  "The inspection info for a multi-arity Var from the per-arity results.
  Each arity's info is keyed by count under :lifecycle :arities; a failed
  arity that kept its previous invoke carries its new failure data. The
  flat top-level info is the first successful arity's; any failure also
  records its diagnostic under :lifecycle for `explain`."
  [results prev-table prev-arities]
  (let [arities-info (into {}
                           (map (fn [{:keys [arity-count status] :as r}]
                                  [arity-count
                                   (cond
                                     (= :ok status) (:info r)
                                     (get prev-table arity-count)
                                     (-> (or (get prev-arities arity-count)
                                             {:spec (:spec r) :body (:body r)})
                                         (assoc :failed-attempt (:error-data r)))
                                     :else {:spec (:spec r) :body (:body r)})]))
                                 results)
        first-ok  (first (filter #(= :ok (:status %)) results))
        flat-info (or (:info first-ok)
                      (some #(when-let [pi (get prev-arities (:arity-count %))]
                               (dissoc pi :failed-attempt))
                            results)
                      {:spec (:spec (first results))
                       :body (:body (first results))})
        failed    (failed-results results)]
    (cond-> (assoc flat-info :lifecycle {:arities arities-info})
      (seq failed)
      (assoc-in [:lifecycle :failed-attempt]
                (:error-data (first failed))))))

(defn establish-multi-binding!
  "Establish a multi-arity `defnz` function (ADR 51). Each arity is
  compiled independently into its own native library with its own invoke
  fn; the Var's root fn dispatches by argument count. On redefinition,
  each arity is recompiled independently: a failed arity keeps its
  previous invoke fn while successful arities get new ones, so a
  redefinition that breaks one arity leaves the others callable."
  ([the-var arity-specs var-meta]
   (establish-multi-binding! the-var arity-specs var-meta {:mode :inline}))
  ([the-var arity-specs var-meta gen]
   (let [prev-entry  (get @multi-rebinders the-var)
         prev-table  (:invoke-table prev-entry)
         prev-arities (get-in (meta the-var) [:clj-zig/info :lifecycle :arities] {})
          results     (mapv #(try-establish-arity % gen) arity-specs)
         failed      (failed-results results)
        first-hard-fail (first (filter #(and (= :failed (:status %))
                                             (nil? (get prev-table (:arity-count %))))
                                       results))]
    (when first-hard-fail
      (let [data (:error-data first-hard-fail)]
        (alter-meta! the-var assoc :clj-zig/failed-attempt data)
        (throw (ex-info (diagnostics/render data) data (:error first-hard-fail)))))
    (let [new-table (arity-invoke-table results prev-table)
          dispatch  (arity-dispatch-fn arity-specs new-table)]
      (alter-var-root the-var (constantly dispatch))
      (swap! multi-rebinders assoc the-var
             {:arity-specs arity-specs :invoke-table new-table})
      (let [info (multi-arity-info results prev-table prev-arities)]
        (swap! rebinders assoc the-var :multi-arity)
        (alter-meta! the-var
                     #(-> (merge % var-meta {:clj-zig/info info})
                          (cond-> (empty? failed) (dissoc :clj-zig/failed-attempt)))))
      (when-let [first-fail (first failed)]
        (let [data (:error-data first-fail)]
          (throw (ex-info (diagnostics/render data) data (:error first-fail)))))
      the-var))))

;; File-sourced bodies

(defn- check-namespace!
  "Throw when a `.zig` file's `//! clj-zig: <ns>` header names a namespace
  other than the one using it. The path binds the file to its namespace;
  the header only catches a file wired to the wrong namespace."
  [expected-ns text file]
  (when-let [declared (source/declared-namespace text)]
    (when (not= declared (str expected-ns))
      (throw (ex-info (str "The Zig file " (pr-str file) " declares namespace "
                           declared " but is used from " expected-ns ".")
                      {:level :error :error/code :clj-zig/namespace-mismatch
                       :clj-zig/file file
                       :clj-zig/declared declared
                       :clj-zig/expected (str expected-ns)})))))

(defn establish-binding-from!
  "Resolve a `{:zig/file ...}` descriptor relative to `defining-file`, read
  the Zig source, and establish the binding for `the-var`. File mode
  generates a wrapper that calls the user's `pub fn`; `:zig/raw` skips the
  wrapper and binds `:zig/symbol` (or the spec's symbol) directly. The
  body's relative `@import`s are resolved and carried along so they compile
  in the cache directory. An optional `//! clj-zig: <ns>` header in the
  file must match the using namespace. Public because the `defnz`
  expansion calls it at load time, so re-evaluating the form re-reads the
  file and its imports."
  [the-var spec descriptor defining-file var-meta wrap]
  (let [{:keys [text path]} (source/resolve-and-read defining-file (:zig/file descriptor))
        _        (check-namespace! (:ns spec) text path)
        raw?     (boolean (:zig/raw descriptor))
        opts     (descriptor/descriptor-options descriptor)
        {:keys [files]} (imports/closure path text)
        the-spec (cond-> spec
                   (:zig/symbol descriptor) (assoc :symbol (:zig/symbol descriptor)))
        gen      (cond-> {:mode (if raw? :raw :file) :source-file path}
                   opts        (assoc :options-extra opts)
                   (not raw?)  (assoc :entry (source/entry-name spec descriptor))
                   (seq files) (assoc :aux-files files))]
    (establish-binding! the-var the-spec text var-meta wrap gen)))

;; defnz / defz

(defn- builder-value
  "The type form a type-builder Var holds, or nil. A symbol resolves to the
  value its Var holds when that value is a type form, a scalar keyword or a
  compound vector. A named-type symbol (a `deftypez`/`defrecordz`/
  `defenumz`) and every non-symbol are left alone."
  [the-ns form]
  (when (symbol? form)
    (when-let [v (ns-resolve the-ns form)]
      (when (var? v)
        (let [value (deref v)]
          (when (or (keyword? value) (vector? value)) value))))))

(defn- simple-binding
  "Strip the namespace a syntax-quote adds to a binding symbol, since a
  binding is a local name and the Zig param takes its bare name. A
  destructuring map binding passes through unchanged."
  [binding]
  (if (and (symbol? binding) (namespace binding)) (symbol (name binding)) binding))

(defn- rest-array-ctor
  "The Clojure primitive-array constructor symbol for a rest argument's
  element scalar, matching the FFM carrier the slice marshaller copies
  from. The element must be a carrier scalar (validated at signature
  time)."
  [elem-kw]
  (let [{:keys [category bits]} (type/scalar-info elem-kw)]
    (case category
      :int   (case bits 8 'byte-array 16 'short-array 32 'int-array 64 'long-array)
      :float (case bits 32 'float-array 64 'double-array)
      :bool  'boolean-array)))

(defn- build-arglist
  "The Clojure-facing arglist for a normalized signature: each binding
  with its namespace stripped, and `&` inserted before a rest argument so
  the wrapper is variadic."
  [norm]
  (if (some :rest? (:args norm))
    (vec (mapcat (fn [a]
                   (let [b (simple-binding (:binding a))]
                     (if (:rest? a) ['& b] [b])))
                 (:args norm)))
    (mapv #(simple-binding (:binding %)) (:args norm))))

(defn- rest-wrap
  "The variadic wrapper for a rest-arg signature: the rest binding is
  boxed into its element's primitive array before the invoker runs, so
  the slice marshaller receives the carrier it expects. `call-args` names
  the invoker's parameters (the rest binding among them); the let shadows
  that binding with the boxed array."
  [arglist call-args rest-arg]
  (let [rest-sym (simple-binding (:binding rest-arg))
        elem-kw  (nth (:type rest-arg) 2)
        ctor     (rest-array-ctor elem-kw)]
    `(fn [invoke#]
       (fn ~arglist
         (let [~rest-sym (~ctor ~rest-sym)]
           (invoke# ~@call-args))))))

(defn- prepare-signature
  "Ready a raw `defnz` signature for the pipeline: replace each
  type-position symbol that names a type-builder Var with the form it
  holds, so a builder composes into a signature as plain data, and strip
  the namespace a syntax-quote adds to each binding symbol so a
  macro-generated signature binds cleanly. Named-type references and
  literal type forms pass through unchanged."
  [the-ns signature]
  (let [{:keys [args ret]} (signature/normalize signature)
        resolve-form #(or (builder-value the-ns %) %)]
    (-> (vec (mapcat (fn [{:keys [binding type]}]
                       [(simple-binding binding) (resolve-form type)])
                     args))
        (conj :ret (resolve-form ret)))))

(defn- parse-defnz
  "Split a `defnz` tail into its parts, mirroring the optional docstring
  and attr-map of `defn`. When the first element after the optional
  docstring and attr-map is a list, the form is multi-arity (ADR 51):
  each remaining element is a `([signature] body)` pair. Otherwise the
  form is single-arity and `:signature`, `:body`, and `:trailing` are
  populated as before."
  [tail]
  (let [[docstring tail] (if (string? (first tail)) [(first tail) (next tail)] [nil tail])
        [attr-map tail]  (if (map? (first tail)) [(first tail) (next tail)] [nil tail])]
    (if (and (seq tail) (seq? (first tail)))
      {:docstring    docstring
       :attr-map     attr-map
       :multi-arity? true
       :arities      (mapv (fn [pair]
                             (when-not (and (sequential? pair)
                                            (vector? (first pair))
                                            (= 2 (count pair)))
                               (throw (ex-info (str "Each multi-arity pair must be"
                                                    " ([signature] body); got " (pr-str pair))
                                               {:level :error
                                                :error/code :clj-zig/malformed-defnz})))
                             {:signature (first pair) :body (second pair)})
                           tail)}
      {:docstring docstring
       :attr-map  attr-map
       :signature (first tail)
       :body      (second tail)
       :trailing  (nnext tail)})))

(defn- reject-ambiguous-file-attr!
  "Throw when `attr-map` is a `{:zig/file ...}` descriptor in the attribute
  map position. A file body must follow a signature; this catches the
  common slip of putting it where the attr-map goes."
  [fn-name attr-map]
  (when (and (map? attr-map) (contains? attr-map :zig/file))
    (throw (ex-info (str "defnz " fn-name " has a {:zig/file ...} map where an"
                         " attribute map goes; a file body must follow a signature.")
                    {:level :error :error/code :clj-zig/ambiguous-body-form
                     :var fn-name}))))

(defn- validate-single-arity-form!
  "Validate the parsed single-arity defnz form, throwing for a malformed
  body, ambiguous file/attr, or trailing forms. Returns the body-shape
  flags the macro needs downstream."
  [fn-name parsed]
  (let [{:keys [attr-map signature body trailing]} parsed
        file-body? (and (map? body) (contains? body :zig/file))
        bodyless?  (and (nil? body) (vector? signature))
        infer?     (and (nil? body) (nil? signature))]
    (when (map? attr-map) (descriptor/validate-descriptor-keys attr-map))
    (when (map? body) (descriptor/validate-descriptor-keys body))
    (reject-ambiguous-file-attr! fn-name attr-map)
    (when (seq trailing)
      (throw (ex-info (str "defnz " fn-name " has " (count trailing)
                           " form(s) after the body; nothing may follow the Zig body.")
                      {:level :error :error/code :clj-zig/malformed-defnz
                       :var fn-name})))
    (when-not (or (string? body) file-body? bodyless? infer?)
      (throw (ex-info "defnz needs a Zig body: a string, a {:zig/file ...} descriptor, or a signature with the body in the namespace's .zig."
                      {:level :error :error/code :clj-zig/malformed-defnz
                       :var fn-name})))
    {:file-body? file-body? :bodyless? bodyless? :infer? infer?}))

(defmacro defnz
  "Define a Clojure function whose body is Zig. The signature vector is
  the boundary contract; the trailing form is the Zig body, a string or a
  `{:zig/file \"name.zig\"}` descriptor, and may be omitted to source the
  body from the namespace's co-located `.zig`:

      (defnz add
        [x :i64
         y :i64
         :ret :i64]
        \"return x + y;\")

      (defnz dot
        [a [:slice :const :f64]
         b [:slice :const :f64]
         :ret :f64]
        {:zig/file \"dot.zig\"})

      ;; body in app/geometry.zig's `pub fn hypotenuse`
      (defnz hypotenuse
        [a :f64 b :f64 :ret :f64])

      ;; signature inferred from app/geometry.zig's `pub fn hypotenuse`
      (defnz hypotenuse)

  A file holds a complete Zig `pub fn` the generated wrapper calls; a
  bodyless form calls the `pub fn` of the same name in the `.zig` beside
  the namespace's source, inferring the signature from it when the
  signature is omitted too. The descriptor may also carry C-interop options
  (`:c/link`, `:c/include-path`, ...), an entry name (`:zig/fn`), and a raw
  escape hatch (`:zig/raw`, `:zig/symbol`). Redefining recompiles; a failed
  recompile keeps the last good binding.

  Multi-arity (ADR 51) mirrors `defn`:

      (defnz add
        ([x :i64 :ret :i64] \"return x;\")
        ([x :i64 y :i64 :ret :i64] \"return x + y;\"))

  Each arity compiles independently and the Var dispatches by argument
  count. File-body descriptors are not supported in multi-arity."
  [fn-name & tail]
  (let [parsed (parse-defnz tail)]
    (if (:multi-arity? parsed)
      (let [{:keys [docstring attr-map arities]} parsed
            the-ns (ns-name *ns*)
            _ (when (map? attr-map) (descriptor/validate-descriptor-keys attr-map))
            _ (reject-ambiguous-file-attr! fn-name attr-map)
            prepared (mapv (fn [{:keys [signature body]}]
                             (when-not (string? body)
                               (throw (ex-info (str "Multi-arity defnz " fn-name
                                                    " needs a string body for every arity.")
                                               {:level :error :error/code :clj-zig/malformed-defnz
                                                :var fn-name})))
                             (let [raw-norm (signature/normalize signature)
                                   rest-arg  (some #(when (:rest? %) %) (:args raw-norm))
                                   _ (when rest-arg
                                       (throw (ex-info (str "Multi-arity defnz " fn-name
                                                            " does not support rest arguments; use"
                                                            " single-arity with & instead.")
                                                       {:level :error :error/code :clj-zig/malformed-defnz
                                                        :var fn-name})))
                                   sig       (prepare-signature the-ns signature)
                                   spec      (spec/build-spec {:ns the-ns :name fn-name
                                                               :signature sig :types (types-in the-ns)})
                                   arglist   (build-arglist raw-norm)
                                   call-args (mapv :binding (:params spec))
                                   wrap      `(fn [invoke#] (fn ~arglist (invoke# ~@call-args)))]
                               {:spec spec :body body :wrap wrap
                                :arity-count (count (:args raw-norm))
                                :arglist arglist}))
                           arities)
             arglists    (mapv :arglist prepared)
             _ (when (not= (count prepared) (count (set (map :arity-count prepared))))
                 (throw (ex-info (str "defnz " fn-name " has duplicate arity counts; each"
                                      " arity must take a distinct number of arguments.")
                                 {:level :error :error/code :clj-zig/duplicate-arity
                                  :var fn-name})))
             var-meta    (merge (when docstring {:doc docstring})
                               attr-map
                               {:arglists arglists})]
       `(do
          (def ~fn-name)
          (establish-multi-binding!
           (var ~fn-name)
           [~@(for [{:keys [spec body wrap arity-count]} prepared]
                `{:spec '~spec :body ~body :wrap ~wrap :arity-count ~arity-count})]
           '~var-meta)
          ~@(when (:clj-zig/spec attr-map)
              `((clj-zig.spec/register! (var ~fn-name))))))
      (let [{:keys [docstring attr-map signature body]} parsed
            {:keys [bodyless? infer?]} (validate-single-arity-form! fn-name parsed)
            the-ns        (ns-name *ns*)
            defining-file *file*
            raw-signature (if infer?
                             (infer/infer-signature
                              (:text (source/resolve-and-read
                                      defining-file (source/namespace-zig-file defining-file)))
                              (str/replace (name fn-name) "-" "_"))
                            signature)
              raw-norm      (signature/normalize raw-signature)
              rest-arg      (some #(when (:rest? %) %) (:args raw-norm))
              comptime-args (vec (filter #(-> % :binding meta :comptime) (:args raw-norm)))
              has-comptime? (and (seq comptime-args) (string? body))
              _ (when (seq comptime-args)
                  ;; ADR 55: comptime arguments are trailing. The call-time
                  ;; splice (establish-comptime-binding!) takes regular args
                  ;; first, comptime values last; a non-comptime arg after a
                  ;; comptime one would be silently spliced into the wrong
                  ;; slot, so reject it at expansion rather than miscompile.
                  (let [comptime? #(-> % :binding meta :comptime)
                        after-first-ct (drop-while (complement comptime?) (:args raw-norm))]
                    (when-not (every? comptime? after-first-ct)
                      (throw (ex-info (str "Comptime arguments must be trailing in "
                                           fn-name "'s signature; a non-comptime"
                                           " argument follows a ^:comptime one.")
                                      {:level :error
                                       :error/code :clj-zig/misplaced-comptime
                                       :fn fn-name})))))
              non-ct-sig    (when has-comptime?
                              (let [non-ct (remove #(-> % :binding meta :comptime) (:args raw-norm))]
                                (-> (vec (mapcat (fn [{:keys [binding type]}]
                                                   [(simple-binding binding) type])
                                                 non-ct))
                                    (conj :ret (:ret raw-norm)))))
              prep-sig      (if has-comptime?
                              (prepare-signature the-ns non-ct-sig)
                              (prepare-signature the-ns raw-signature))
              spec          (spec/build-spec {:ns the-ns :name fn-name :signature prep-sig
                                              :types (types-in the-ns)})
              arglist       (build-arglist raw-norm)
              call-args     (mapv :binding (:params spec))
              wrap-arglist  (if has-comptime?
                              (mapv #(simple-binding (:binding %))
                                    (remove #(-> % :binding meta :comptime) (:args raw-norm)))
                              arglist)
              var-meta      (merge (when docstring {:doc docstring})
                                   attr-map
                                   {:arglists (list arglist)})
              wrap          (cond
                              has-comptime?
                              `(fn [invoke#] (fn ~wrap-arglist (invoke# ~@call-args)))
                              rest-arg
                              (rest-wrap arglist call-args rest-arg)
                              :else
                              `(fn [invoke#] (fn ~arglist (invoke# ~@call-args))))
              ct-params     (when has-comptime?
                              (mapv (fn [a] {:binding (simple-binding (:binding a))
                                             :type (:type a)})
                                    comptime-args))
              descriptor    (if (or bodyless? infer?)
                               `{:zig/file (source/namespace-zig-file ~defining-file)}
                              body)]
          `(do
             (def ~fn-name)
             ~(cond
                has-comptime?
                `(establish-comptime-binding! (var ~fn-name) '~spec ~body '~ct-params '~var-meta ~wrap)
                (string? body)
                `(establish-binding! (var ~fn-name) '~spec ~body '~var-meta ~wrap)
                :else
                `(establish-binding-from! (var ~fn-name) '~spec ~descriptor ~defining-file
                                          '~var-meta ~wrap))
             ~@(when (:clj-zig/spec attr-map)
                 `((clj-zig.spec/register! (var ~fn-name)))))))))

(defn resolve-decl-source
  "The Zig text for a `defz` declaration: a string as-is, or the contents
  of the `{:zig/file ...}` it names, resolved relative to `defining-file`.
  Public because the `defz` expansion calls it at load time."
  [decl-source defining-file]
  (if (map? decl-source)
    (:text (source/resolve-and-read defining-file (:zig/file decl-source)))
    decl-source))

(defmacro defz
  "Register a Zig declaration usable by the `defnz` bodies in this
  namespace. It is not callable from Clojure; the Var holds its source.
  The source is a string or a `{:zig/file \"shared.zig\"}` descriptor,
  the natural home for a shared `@cImport` block and helper fns."
  [decl-name decl-source]
  (let [the-ns        (ns-name *ns*)
        defining-file *file*]
    `(let [src# (resolve-decl-source ~decl-source ~defining-file)]
       (register-decl! '~the-ns '~decl-name src#)
       (def ~decl-name src#)
       (alter-meta! (var ~decl-name) assoc :clj-zig/decl true)
       (var ~decl-name))))

(defmacro zig-deps
  "Declare the dependencies shared by every `defnz` in this namespace. The
  C-interop options let a co-located `.zig` `@cImport` a header and link its
  library without repeating the flags on each function; `:zig/modules`
  declares external Zig packages the bodies may `@import` by name (ADR 34):

      (zig-deps {:c/link [\"m\"]
                 :zig/modules {\"phane\" {:path \"../phane/src/root.zig\"}}})

  A function descriptor still overrides the C options. The options enter each
  function's content hash, so changing them recompiles the namespace's
  functions."
  [descriptor]
  (descriptor/validate-descriptor-keys descriptor)
  (let [the-ns (ns-name *ns*)]
    `(do
       (register-deps! '~the-ns ~descriptor)
       nil)))

(defmacro deftypez
  "Define a named boundary type with an `extern struct` layout. The
  signatures in this namespace may name it as an argument or return type;
  the Var holds the layout descriptor.

  An optional trailing options map may carry `:packed true` to emit a
  Zig `packed struct` with no alignment padding. Packed structs support
  scalar and enum fields only.

      (deftypez Point
        [x :f64
         y :f64])

      (deftypez RGB
        [r :u8 g :u8 b :u8]
        {:packed true})"
  [type-name & tail]
  (let [has-doc?   (string? (first tail))
        docstring  (when has-doc? (first tail))
        after-doc  (if has-doc? (next tail) tail)
        fields     (first after-doc)
        opts       (when (and (next after-doc) (map? (second after-doc)))
                     (second after-doc))
        the-ns     (ns-name *ns*)
        descriptor (-> (layout/describe type-name fields (types-in the-ns) opts)
                       (cond-> (:clj-zig/iter opts) (assoc :clj-zig/iter (:clj-zig/iter opts))))]
    `(do
       (register-type! '~the-ns '~descriptor)
       (def ~type-name '~descriptor)
       (alter-meta! (var ~type-name) merge {:clj-zig/type-layout true}
                    ~(when docstring {:doc docstring}))
       (var ~type-name))))

(defmacro defrecordz
  "Define both a Clojure record and a named boundary type sharing its
  layout. A signature in this namespace may name the record as an
  argument or return type; a record-typed return rebuilds the record on
  the Clojure side instead of returning a plain map.

      (defrecordz Point
        [x :f64
         y :f64])

  Unlike `deftypez`/`defenumz`, the optional docstring does NOT land on
  the type symbol: `defrecord` interns the type as a Class, and a class
  symbol has no Var to carry `:doc`. The docstring is attached to the
   `map->Type` factory Var instead, the interned Var closest to the type
   and the one the FFM return path resolves when rebuilding a record."
  [type-name & tail]
  (let [[docstring fields] (if (string? (first tail))
                             [(first tail) (second tail)]
                             [nil (first tail)])
        the-ns     (ns-name *ns*)
        descriptor (layout/describe-record type-name fields the-ns (types-in the-ns))
        field-syms (mapv :name (:fields descriptor))
        factory    (symbol (str "map->" type-name))]
    `(do
       (defrecord ~type-name ~field-syms)
       (register-type! '~the-ns '~descriptor)
       ~@(when docstring
           [`(alter-meta! (var ~factory) assoc :doc ~docstring)])
       ~type-name)))

(defmacro defenumz
  "Define an enum boundary type backed by an integer scalar (default
  `:i32`). Members cross as keywords named for each member; the Var holds
  the descriptor. The optional options map may carry `:backing` to widen
  or narrow the tag (`:u8`, `:u32`, ...), matching the C enum widths real
  libraries use.

       (defenumz ParseStatus
         [ok 0
          invalid 1
          eof 2])

       (defenumz CompactTag
         [a 0 b 1 c 2]
         {:backing :u8})"
  [type-name & tail]
  (let [head     (first tail)
        [docstring members opts] (if (string? head)
                                   [head (second tail) (nth tail 2 nil)]
                                   [nil head (second tail)])
        the-ns     (ns-name *ns*)
        descriptor (layout/describe-enum type-name members opts)]
    `(do
       (register-type! '~the-ns '~descriptor)
       (def ~type-name '~descriptor)
       (alter-meta! (var ~type-name) merge {:clj-zig/type-layout true}
                    ~(when docstring {:doc docstring}))
       (var ~type-name))))

(comment
  ;; The headline workflow: a Clojure fn with a Zig body.
  (defnz add [x :i64 y :i64 :ret :i64] "return x + y;")
  (add 20 22)                              ;; => 42

  ;; Redefine like any defn; a fresh library is compiled.
  (defnz add [x :i64 y :i64 :ret :i64] "return x + y + 10;")
  (add 20 22)                              ;; => 52

  ;; Inspect the generated wrapper from the Var's metadata.
  (get-in (meta #'add) [:clj-zig/info :generated-source])

  ;; A Zig helper shared by the bodies in this namespace.
  (defz helpers "fn dbl(x: i64) i64 { return x * 2; }")
  (defnz doubled [x :i64 :ret :i64] "return dbl(x);")
  (doubled 21)                             ;; => 42

  ;; A named boundary type the signatures in this namespace can use.
  (deftypez Point [x :f64 y :f64])
  (:size Point)                            ;; => 16

  ;; A record bridges a Clojure record and the same struct layout; a
  ;; record-typed return rebuilds the record.
  (defrecordz Vec2 [x :f64 y :f64])
  (defnz scale [v Vec2 k :f64 :ret Vec2] "return .{ .x = v.x * k, .y = v.y * k };")
  (scale (->Vec2 2.0 3.0) 2.0)             ;; => #user.Vec2{:x 4.0 :y 6.0}

  ;; An enum bridges Zig members and Clojure keywords.
  (defenumz Suit [clubs 0 diamonds 1 hearts 2 spades 3])
  (defnz red? [s Suit :ret :bool] "return s == .diamonds or s == .hearts;")
  (red? :hearts)                           ;; => true

  ;; An owned slice return is copied into a vector and then freed.
  (defnz repeat-byte [b :u8 n :usize :ret [:owned [:slice :u8]]]
    "const out = std.heap.c_allocator.alloc(u8, n) catch @panic(\"oom\");
     @memset(out, b);
     return out;")
  (repeat-byte 65 3)                       ;; => [65 65 65]

  ;; An opaque handle threads a native resource across calls.
  (defz Box "const Box = struct { v: i64 };")
  (defnz box [v :i64 :ret [:handle Box]]
    "const b = std.heap.c_allocator.create(Box) catch @panic(\"oom\");
     b.* = .{ .v = v };
     return b;")
  (defnz unbox [b [:handle Box] :ret :i64] "return b.v;")
  (unbox (box 42))                         ;; => 42

  ;; A type-builder Var resolves to its form when named in a signature.
  (def f64s [:slice :const :f64])
  (defnz total [xs f64s :ret :f64] "var t: f64 = 0; for (xs) |v| t += v; return t;")
  (total (double-array [1.0 2.0 3.0])))    ;; => 6.0
