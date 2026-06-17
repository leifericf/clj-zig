(ns clj-zig.core
  "The `defnz` and `defz` defining forms. These macros stay thin: they
  parse the form into data, hand it to the pure pipeline (signature ->
  spec -> source) and the shell (compile/cache -> load -> bind), then
  rebind an ordinary Clojure Var. A user can reach every stage through
  the data functions without the macro.

  `defnz` defines a Clojure function backed by a Zig body. `defz`
  registers Zig declarations that the bodies in its namespace may call
  but that are not themselves callable from Clojure."
  (:require [clojure.string :as str]
            [clj-zig.cache :as cache]
            [clj-zig.compile :as compile]
            [clj-zig.diagnostics :as diagnostics]
            [clj-zig.ffm :as ffm]
            [clj-zig.fileref :as fileref]
            [clj-zig.imports :as imports]
            [clj-zig.infer :as infer]
            [clj-zig.layout :as layout]
            [clj-zig.signature :as signature]
            [clj-zig.source :as source]
            [clj-zig.spec :as spec]))

;; --- Namespace-scoped Zig declarations ----------------------------------

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

(defn register-deps!
  "Register the namespace-level C-interop options for `ns-sym`, replacing
  any previous registration. Public because the `zig-deps` expansion calls
  it from the user's namespace."
  [ns-sym descriptor]
  (swap! ns-deps assoc ns-sym (c-options descriptor)))

(defn deps-in
  "The namespace-level C-interop options registered for `ns-sym`, or nil."
  [ns-sym]
  (get @ns-deps ns-sym))

(defn- preamble
  "The Zig declarations registered in `ns-sym`: the named-type structs,
  then the `defz` declarations, joined for splicing ahead of a wrapper."
  [ns-sym]
  (let [structs (->> (vals (types-in ns-sym))
                     (sort-by (comp str :name))
                     (map layout/zig-decl))
        decls   (map :source (get @zig-decls ns-sym))]
    (str/join "\n\n" (concat structs decls))))

;; --- Establishing a native function -------------------------------------

(defn- join-sources
  "Join non-blank Zig sections with a blank line between them."
  [sections]
  (str/join "\n\n" (remove str/blank? sections)))

(defn build-inputs
  "The cache/compile inputs for `spec` and `body`: the generated source,
  prefixed with this namespace's `defz` declarations, plus the toolchain
  identity that the content hash includes. `gen` selects the source shape:
  inline splices the body string into a wrapper; file concatenates the
  user's file text and a wrapper that calls its `pub fn`; raw uses the file
  text as-is. `gen` also carries any C-interop `:options-extra`. Options
  layer by precedence: the optimize default, then the namespace `zig-deps`,
  then the descriptor, so a function inherits its namespace's link flags
  and may still override them. A file body that imports other Zig files
  carries them as `:aux-files`, reproduced beside the source."
  ([spec body] (build-inputs spec body {:mode :inline}))
  ([spec body {:keys [mode entry options-extra aux-files] :or {mode :inline}}]
   (let [decls (preamble (:ns spec))
         src   (case mode
                 :raw  (join-sources [decls body])
                 :file (join-sources [decls body (source/generate spec body {:mode :file :entry entry})])
                 (join-sources [decls (source/generate spec body)]))]
     (cond-> {:spec        spec
              :body        body
              :source      src
              :deps        decls
              :options     (merge {:optimize "ReleaseSafe"} (deps-in (:ns spec)) options-extra)
              :zig-version (cache/zig-version)
              :target      (cache/target-triple)}
       aux-files (assoc :aux-files aux-files)))))

(defn artifact
  "Compile or reuse the native library for `spec` and `body`. Returns the
  inspection data describing the build: the spec, the body, the generated
  source, the library and source paths, the symbol, and whether the
  library was reused (`:cached`) or freshly built (`:compiled`)."
  ([spec body] (artifact spec body {:mode :inline}))
  ([spec body gen]
   (let [inputs (build-inputs spec body gen)
         paths  (cache/ensure-library! inputs compile/compile!)]
     {:spec             spec
      :body             body
      :generated-source (:source inputs)
      :library          (:library-path paths)
      :source-path      (:source-path paths)
      :symbol           (:symbol spec)
      :status           (if (:cached? paths) :cached :compiled)})))

(defn establish!
  "Build the artifact for `spec` and `body` and bind its symbol. Returns
  the inspection data plus the native invoker under `:invoke`. Throws the
  compile diagnostic when the Zig does not build."
  ([spec body] (establish! spec body {:mode :inline}))
  ([spec body gen]
   (let [a (artifact spec body gen)]
     (assoc a :invoke (ffm/bind spec (:library a))))))

;; --- Binding and rebinding a Var ----------------------------------------

;; A defnz Var's wrap fn, kept so `recompile!` can rebind with the same
;; arglist and destructuring the macro built.
(defonce ^:private rebinders (atom {}))

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
                    (:aux-files gen)     (assoc :aux-files (:aux-files gen)))]
       (alter-var-root the-var (constantly (wrap (:invoke result))))
       (swap! rebinders assoc the-var wrap)
       (alter-meta! the-var
                    #(-> (merge % var-meta {:clj-zig/info info})
                         (dissoc :clj-zig/failed-attempt)))
       the-var)
     (catch clojure.lang.ExceptionInfo e
       (alter-meta! the-var assoc
                    :clj-zig/failed-attempt (assoc (ex-data e) :body body))
       (throw (ex-info (diagnostics/render (ex-data e)) (ex-data e) e))))))

(defn recompile!
  "Force a fresh build of `the-var`'s current spec and body, ignoring the
  cached artifact, and rebind. Returns the Var. Rebuilds in the same mode
  the function was defined with (inline, file, or raw)."
  [the-var]
  (let [{:keys [spec body source-mode entry source-file options-extra aux-files]}
        (:clj-zig/info (meta the-var))
        gen  (cond-> {:mode (or source-mode :inline)}
               entry         (assoc :entry entry)
               source-file   (assoc :source-file source-file)
               options-extra (assoc :options-extra options-extra)
               aux-files     (assoc :aux-files aux-files))
        wrap (get @rebinders the-var)]
    (when-not (and spec wrap)
      (throw (ex-info "recompile! needs a defnz Var with a current binding."
                      {:level :error :error/code :clj-zig/not-recompilable
                       :var the-var})))
    (cache/evict! (build-inputs spec body gen))
    (establish-binding! the-var spec body {} wrap gen)))

;; --- File-sourced bodies ------------------------------------------------

(defn- valid-zig-ident?
  "True when `s` is a legal Zig identifier, so it can name the user fn the
  file-mode wrapper calls."
  [s]
  (boolean (re-matches #"[A-Za-z_][A-Za-z0-9_]*" s)))

(defn- entry-name
  "The user fn name the file-mode wrapper calls: `:zig/fn` when given, else
  the Clojure fn name with hyphens as underscores, the way Clojure names
  munge for the JVM (`dot-product` -> `dot_product`). A name still not a
  legal Zig identifier, such as `red?` or `saxpy!`, needs `:zig/fn`."
  [spec descriptor]
  (or (:zig/fn descriptor)
      (let [n (str/replace (name (:name spec)) "-" "_")]
        (if (valid-zig-ident? n)
          n
          (throw (ex-info (str "The Clojure name " (pr-str (:name spec))
                               " is not a legal Zig identifier; name the entry"
                               " fn with :zig/fn.")
                          {:level :error :error/code :clj-zig/entry-name-needed
                           :name (:name spec)}))))))

(defn namespace-zig-file
  "The `.zig` file co-located with a namespace's Clojure source: the
  defining file's path with its `.clj`/`.cljc` extension replaced by
  `.zig`. A bodyless `defnz` sources its body from this file's matching
  `pub fn`. Pure; the filesystem and classpath resolution happens in
  `establish-binding-from!`. Throws when there is no defining file, as at
  the REPL, where a bodyless `defnz` has no co-located file to read."
  [defining-file]
  (when (or (nil? defining-file) (= defining-file "NO_SOURCE_PATH"))
    (throw (ex-info (str "A bodyless defnz needs a file-loaded namespace with"
                         " a co-located .zig; give an explicit {:zig/file ...}"
                         " body when there is no defining file.")
                    {:level :error :error/code :clj-zig/no-namespace-file})))
  (str/replace defining-file #"\.cljc?$" ".zig"))

(defn declared-namespace
  "The namespace a `.zig` file asserts it belongs to via a leading
  `//! clj-zig: <ns>` doc-comment line, or nil when it makes no such
  assertion. Pure."
  [zig-text]
  (some (fn [line]
          (second (re-matches #"\s*//!\s*clj-zig:\s*(\S+)\s*" line)))
        (str/split-lines zig-text)))

(defn- check-namespace!
  "Throw when a `.zig` file's `//! clj-zig: <ns>` header names a namespace
  other than the one using it. The path binds the file to its namespace;
  the header only catches a file wired to the wrong namespace."
  [expected-ns text file]
  (when-let [declared (declared-namespace text)]
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
  (let [{:keys [text path]} (fileref/resolve-and-read defining-file (:zig/file descriptor))
        _        (check-namespace! (:ns spec) text path)
        raw?     (boolean (:zig/raw descriptor))
        opts     (c-options descriptor)
        {:keys [files]} (imports/closure path text)
        the-spec (cond-> spec
                   (:zig/symbol descriptor) (assoc :symbol (:zig/symbol descriptor)))
        gen      (cond-> {:mode (if raw? :raw :file) :source-file path}
                   opts        (assoc :options-extra opts)
                   (not raw?)  (assoc :entry (entry-name spec descriptor))
                   (seq files) (assoc :aux-files files))]
    (establish-binding! the-var the-spec text var-meta wrap gen)))

;; --- defnz / defz -------------------------------------------------------

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
  "Split a `defnz` tail into `{:docstring :attr-map :signature :body}`,
  mirroring the optional docstring and attr-map of `defn`."
  [tail]
  (let [[docstring tail] (if (string? (first tail)) [(first tail) (next tail)] [nil tail])
        [attr-map tail]  (if (map? (first tail)) [(first tail) (next tail)] [nil tail])]
    {:docstring docstring
     :attr-map  attr-map
     :signature (first tail)
     :body      (second tail)}))

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
  recompile keeps the last good binding."
  [fn-name & tail]
  (let [{:keys [docstring attr-map signature body]} (parse-defnz tail)
        file-body? (and (map? body) (contains? body :zig/file))
        bodyless?  (and (nil? body) (vector? signature))
        infer?     (and (nil? body) (nil? signature))]
    (when-not (or (string? body) file-body? bodyless? infer?)
      (throw (ex-info "defnz needs a Zig body: a string, a {:zig/file ...} descriptor, or a signature with the body in the namespace's .zig."
                      {:level :error :error/code :clj-zig/malformed-defnz
                       :var fn-name})))
    (let [the-ns        (ns-name *ns*)
          defining-file *file*
          signature     (if infer?
                          (infer/infer-signature
                           (:text (fileref/resolve-and-read
                                   defining-file (namespace-zig-file defining-file)))
                           (str/replace (name fn-name) "-" "_"))
                          signature)
          signature     (prepare-signature the-ns signature)
          spec          (spec/build-spec {:ns the-ns :name fn-name :signature signature
                                          :types (types-in the-ns)})
          arglist       (mapv :binding (:args (signature/normalize signature)))
          call-args     (mapv :binding (:params spec))
          var-meta      (merge (when docstring {:doc docstring})
                               attr-map
                               {:arglists (list arglist)})
          wrap          `(fn [invoke#] (fn ~arglist (invoke# ~@call-args)))
          descriptor    (if (or bodyless? infer?)
                          `{:zig/file (namespace-zig-file ~defining-file)}
                          body)]
      `(do
         (def ~fn-name)
         ~(if (string? body)
            `(establish-binding! (var ~fn-name) '~spec ~body '~var-meta ~wrap)
            `(establish-binding-from! (var ~fn-name) '~spec ~descriptor ~defining-file
                                      '~var-meta ~wrap))))))

(defn resolve-decl-source
  "The Zig text for a `defz` declaration: a string as-is, or the contents
  of the `{:zig/file ...}` it names, resolved relative to `defining-file`.
  Public because the `defz` expansion calls it at load time."
  [decl-source defining-file]
  (if (map? decl-source)
    (:text (fileref/resolve-and-read defining-file (:zig/file decl-source)))
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
  "Declare the C-interop options shared by every `defnz` in this namespace,
  so a co-located `.zig` may `@cImport` a header and link its library
  without repeating the flags on each function:

      (zig-deps {:c/link [\"m\"]})

  A function descriptor still overrides these. The options enter each
  function's content hash, so changing them recompiles the namespace's
  functions."
  [descriptor]
  (let [the-ns (ns-name *ns*)]
    `(do
       (register-deps! '~the-ns ~descriptor)
       nil)))

(defmacro deftypez
  "Define a named boundary type with an `extern struct` layout. The
  signatures in this namespace may name it as an argument or return type;
  the Var holds the layout descriptor.

      (deftypez Point
        [x :f64
         y :f64])"
  [type-name & tail]
  (let [[docstring fields] (if (string? (first tail))
                             [(first tail) (second tail)]
                             [nil (first tail)])
        the-ns     (ns-name *ns*)
        descriptor (layout/describe type-name fields)]
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
         y :f64])"
  [type-name & tail]
  (let [[docstring fields] (if (string? (first tail))
                             [(first tail) (second tail)]
                             [nil (first tail)])
        the-ns     (ns-name *ns*)
        descriptor (layout/describe-record type-name fields the-ns)
        field-syms (mapv :name (:fields descriptor))
        factory    (symbol (str "map->" type-name))]
    `(do
       (defrecord ~type-name ~field-syms)
       (register-type! '~the-ns '~descriptor)
       ~@(when docstring
           [`(alter-meta! (var ~factory) assoc :doc ~docstring)])
       ~type-name)))

(defmacro defenumz
  "Define an enum boundary type backed by an `i32`. Members cross as
  keywords named for each member; the Var holds the descriptor.

      (defenumz ParseStatus
        [ok 0
         invalid 1
         eof 2])"
  [type-name & tail]
  (let [[docstring members] (if (string? (first tail))
                              [(first tail) (second tail)]
                              [nil (first tail)])
        the-ns     (ns-name *ns*)
        descriptor (layout/describe-enum type-name members)]
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
