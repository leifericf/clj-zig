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
            [zigar.ffm :as ffm]
            [zigar.signature :as signature]
            [zigar.source :as source]
            [zigar.spec :as spec]))

;; --- Namespace-scoped Zig declarations ----------------------------------

(defonce ^:private zig-decls (atom {}))

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

(defn- preamble
  "The Zig declarations registered in `ns-sym`, joined for splicing ahead
  of a wrapper."
  [ns-sym]
  (str/join "\n\n" (map :source (get @zig-decls ns-sym))))

;; --- Establishing a native function -------------------------------------

(defn establish!
  "Generate, compile or reuse, and bind the native function for `spec`
  and `body`. Returns the native invoker under `:invoke` plus the data
  the Var exposes for inspection. Throws the compile diagnostic when the
  Zig does not build."
  [spec body]
  (let [decls   (preamble (:ns spec))
        wrapper (source/generate spec body)
        src     (if (str/blank? decls) wrapper (str decls "\n\n" wrapper))
        paths   (cache/ensure-library!
                 {:spec spec
                  :body body
                  :source src
                  :deps decls
                  :options {:optimize "ReleaseSafe"}
                  :zig-version (cache/zig-version)
                  :target (cache/target-triple)}
                 compile/compile!)]
    {:invoke           (ffm/bind spec (:library-path paths))
     :spec             spec
     :body             body
     :generated-source src
     :library          (:library-path paths)
     :source-path      (:source-path paths)
     :symbol           (:symbol spec)
     :status           (if (:cached? paths) :cached :compiled)}))

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
          spec      (spec/build-spec {:ns the-ns :name fn-name :signature signature})
          arglist   (mapv :binding (:args (signature/normalize signature)))
          call-args (mapv :binding (:params spec))
          var-meta  (merge (when docstring {:doc docstring})
                           attr-map
                           {:arglists (list arglist)})]
      `(do
         (def ~fn-name)
         (let [result# (establish! '~spec ~body)
               invoke# (:invoke result#)]
           (alter-var-root (var ~fn-name) (constantly (fn ~arglist (invoke# ~@call-args))))
           (alter-meta! (var ~fn-name) merge '~var-meta {:zigar/info (dissoc result# :invoke)})
           (var ~fn-name))))))

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
  (doubled 21))                            ;; => 42
