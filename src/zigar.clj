(ns zigar
  "The public Zigar API, meant to be aliased `zig`:

      (require '[zigar.core :refer [defnz defz]] '[zigar :as zig])

  `defnz`/`defz` (in `zigar.core`) are the everyday forms. This namespace
  publishes everything else: the inspection helpers that hang off a Var,
  and the data functions that make up the pipeline a macro hides
  (`normalize-signature`, `normalize-type`, `build-spec`,
  `generate-source`, `compile!`, `load!`). A library or macro author can
  drive the whole pipeline through these without the macro."
  (:refer-clojure :exclude [fn symbol])
  (:require [zigar.cache :as cache]
            [zigar.core :as core]
            [zigar.ffm :as ffm]
            [zigar.inspect :as inspect]
            [zigar.signature :as signature]
            [zigar.source :as source]
            [zigar.spec :as spec]
            [zigar.type :as type]))

;; --- Inspection (hangs off a Var) ---------------------------------------

(defn source
  "The Zig body the function was defined with."
  [the-var]
  (inspect/source the-var))

(defn generated-source
  "The full Zig wrapper generated around the body."
  [the-var]
  (inspect/generated-source the-var))

(defn spec
  "The normalized boundary spec the function was built from."
  [the-var]
  (inspect/spec the-var))

(defn signature
  "The signature vector the function was defined with."
  [the-var]
  (inspect/signature the-var))

(defn library
  "The path to the compiled shared library backing the function."
  [the-var]
  (inspect/library the-var))

(defn symbol
  "The stable C symbol the native function is exported under."
  [the-var]
  (inspect/symbol the-var))

(defn status
  "Whether the backing library was freshly built (`:compiled`) or reused
  from the cache (`:cached`)."
  [the-var]
  (inspect/status the-var))

(defn explain
  "Render the last failed attempt for `the-var`, or nil when there is
  nothing to explain."
  [the-var]
  (inspect/explain the-var))

(defn recompile!
  "Force a fresh build of `the-var`'s current spec and body, ignoring the
  cached artifact, and rebind. Returns the Var."
  [the-var]
  (core/recompile! the-var))

(defn clean!
  "Remove the artifact cache under `root` (default `.zigar/cache`)."
  ([] (cache/clean!))
  ([root] (cache/clean! root)))

;; --- Pure data pipeline -------------------------------------------------

(defn normalize-type
  "Normalize a boundary type form to its canonical data shape."
  [form]
  (type/normalize form))

(defn normalize-signature
  "Normalize a signature vector to `{:args [...] :ret <type>}`."
  [sig]
  (signature/normalize sig))

(defn build-spec
  "Build the canonical boundary spec from `{:ns :name :signature}`."
  [ident]
  (spec/build-spec ident))

(defn generate-source
  "Emit the Zig wrapper for `spec` with `body` spliced in."
  [spec body]
  (source/generate spec body))

;; --- Shell pipeline -----------------------------------------------------

(defn compile!
  "Compile or reuse the native library for `spec` and `body`, returning the
  artifact data (paths, symbol, generated source, build status)."
  [spec body]
  (core/artifact spec body))

(defn load!
  "Load a compiled `artifact` and return the callable native invoker."
  [artifact]
  (ffm/bind (:spec artifact) (:library artifact)))

(defn fn
  "Build a callable function from `spec` and `body` in one step:
  `compile!` then `load!`."
  [spec body]
  (load! (compile! spec body)))

(comment
  (require '[zigar.core :refer [defnz]])
  (defnz add [x :i64 y :i64 :ret :i64] "return x + y;")
  (source #'add)                   ;; => "return x + y;"
  (spec #'add)                     ;; => {:ns ... :name add :params [...] :ret {...} ...}

  ;; Drive the pipeline directly, without the macro.
  (let [s   (build-spec '{:ns app.core :name mul :signature [x :i64 y :i64 :ret :i64]})
        mul (fn s "return x * y;")]
    (mul 6 7)))                     ;; => 42