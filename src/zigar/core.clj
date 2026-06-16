(ns zigar.core
  "The `defnz` and `defz` defining forms. These macros stay thin: they
  parse the form into data, hand it to the pure pipeline (signature ->
  spec -> source) and the shell (compile/cache -> load -> bind), then
  rebind an ordinary Clojure Var. A user can reach every stage through
  the data functions without the macro.

  `defnz` defines a Clojure function backed by a Zig body. `defz`
  registers Zig declarations that the bodies in its namespace may call
  but that are not themselves callable from Clojure."
  (:require [clojure.string :as str]
            [zigar.cache :as cache]
            [zigar.compile :as compile]
            [zigar.diagnostics :as diagnostics]
            [zigar.ffm :as ffm]
            [zigar.layout :as layout]
            [zigar.signature :as signature]
            [zigar.source :as source]
            [zigar.spec :as spec]))

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

(defn- preamble
  "The Zig declarations registered in `ns-sym`: the named-type structs,
  then the `defz` declarations, joined for splicing ahead of a wrapper."
  [ns-sym]
  (let [structs (->> (vals (types-in ns-sym))
                     (sort-by (comp str :name))
                     (map layout/zig-struct))
        decls   (map :source (get @zig-decls ns-sym))]
    (str/join "\n\n" (concat structs decls))))

;; --- Establishing a native function -------------------------------------

(defn build-inputs
  "The cache/compile inputs for `spec` and `body`: the generated wrapper,
  prefixed with this namespace's `defz` declarations, plus the toolchain
  identity that the content hash includes."
  [spec body]
  (let [decls   (preamble (:ns spec))
        wrapper (source/generate spec body)
        src     (if (str/blank? decls) wrapper (str decls "\n\n" wrapper))]
    {:spec        spec
     :body        body
     :source      src
     :deps        decls
     :options     {:optimize "ReleaseSafe"}
     :zig-version (cache/zig-version)
     :target      (cache/target-triple)}))

(defn artifact
  "Compile or reuse the native library for `spec` and `body`. Returns the
  inspection data describing the build: the spec, the body, the generated
  source, the library and source paths, the symbol, and whether the
  library was reused (`:cached`) or freshly built (`:compiled`)."
  [spec body]
  (let [inputs (build-inputs spec body)
        paths  (cache/ensure-library! inputs compile/compile!)]
    {:spec             spec
     :body             body
     :generated-source (:source inputs)
     :library          (:library-path paths)
     :source-path      (:source-path paths)
     :symbol           (:symbol spec)
     :status           (if (:cached? paths) :cached :compiled)}))

(defn establish!
  "Build the artifact for `spec` and `body` and bind its symbol. Returns
  the inspection data plus the native invoker under `:invoke`. Throws the
  compile diagnostic when the Zig does not build."
  [spec body]
  (let [a (artifact spec body)]
    (assoc a :invoke (ffm/bind spec (:library a)))))

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
  [the-var spec body var-meta wrap]
  (try
    (let [result (establish! spec body)]
      (alter-var-root the-var (constantly (wrap (:invoke result))))
      (swap! rebinders assoc the-var wrap)
      (alter-meta! the-var
                   #(-> (merge % var-meta {:zigar/info (dissoc result :invoke)})
                        (dissoc :zigar/failed-attempt)))
      the-var)
    (catch clojure.lang.ExceptionInfo e
      (alter-meta! the-var assoc
                   :zigar/failed-attempt (assoc (ex-data e) :body body))
      (throw (ex-info (diagnostics/render (ex-data e)) (ex-data e) e)))))

(defn recompile!
  "Force a fresh build of `the-var`'s current spec and body, ignoring the
  cached artifact, and rebind. Returns the Var."
  [the-var]
  (let [{:keys [spec body]} (:zigar/info (meta the-var))
        wrap (get @rebinders the-var)]
    (when-not (and spec wrap)
      (throw (ex-info "recompile! needs a defnz Var with a current binding."
                      {:level :error :error/code :zigar/not-recompilable
                       :var the-var})))
    (cache/evict! (build-inputs spec body))
    (establish-binding! the-var spec body {} wrap)))

;; --- defnz / defz -------------------------------------------------------

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
  the boundary contract; the trailing string is the Zig body:

      (defnz add
        [x :i64
         y :i64
         :ret :i64]
        \"return x + y;\")

  Redefining recompiles; a failed recompile keeps the last good binding."
  [fn-name & tail]
  (let [{:keys [docstring attr-map signature body]} (parse-defnz tail)]
    (when-not (string? body)
      (throw (ex-info "defnz needs a signature vector and a Zig body string."
                      {:level :error :error/code :zigar/malformed-defnz
                       :var fn-name})))
    (let [the-ns    (ns-name *ns*)
          spec      (spec/build-spec {:ns the-ns :name fn-name :signature signature
                                      :types (types-in the-ns)})
          arglist   (mapv :binding (:args (signature/normalize signature)))
          call-args (mapv :binding (:params spec))
          var-meta  (merge (when docstring {:doc docstring})
                           attr-map
                           {:arglists (list arglist)})]
      `(do
         (def ~fn-name)
         (establish-binding! (var ~fn-name) '~spec ~body '~var-meta
                             (fn [invoke#] (fn ~arglist (invoke# ~@call-args))))))))

(defmacro defz
  "Register a Zig declaration usable by the `defnz` bodies in this
  namespace. It is not callable from Clojure; the Var holds its source."
  [decl-name decl-source]
  (let [the-ns (ns-name *ns*)]
    `(do
       (register-decl! '~the-ns '~decl-name ~decl-source)
       (def ~decl-name ~decl-source)
       (alter-meta! (var ~decl-name) assoc :zigar/decl true)
       (var ~decl-name))))

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
       (alter-meta! (var ~type-name) merge {:zigar/type-layout true}
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

(comment
  ;; The headline workflow: a Clojure fn with a Zig body.
  (defnz add [x :i64 y :i64 :ret :i64] "return x + y;")
  (add 20 22)                              ;; => 42

  ;; Redefine like any defn; a fresh library is compiled.
  (defnz add [x :i64 y :i64 :ret :i64] "return x + y + 10;")
  (add 20 22)                              ;; => 52

  ;; Inspect the generated wrapper from the Var's metadata.
  (get-in (meta #'add) [:zigar/info :generated-source])

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
  (scale (->Vec2 2.0 3.0) 2.0))            ;; => #user.Vec2{:x 4.0 :y 6.0}
