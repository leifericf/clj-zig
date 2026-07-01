(ns clj-zig
  "The public clj-zig API, meant to be aliased `zig`:

      (require '[clj-zig.core :refer [defnz defz]] '[clj-zig :as zig])

  `defnz`/`defz` (in `clj-zig.core`) are the everyday forms. This namespace
  publishes everything else: the inspection helpers that hang off a Var,
  and the data functions that make up the pipeline a macro hides
  (`normalize-signature`, `normalize-type`, `build-spec`,
  `generate-source`, `compile!`, `load!`). A library or macro author can
  drive the whole pipeline through these without the macro."
  (:refer-clojure :exclude [fn symbol])
  (:require [clj-zig.cachestore :as cachestore]
            [clj-zig.core :as core]
            [clj-zig.ffm :as ffm]
            [clj-zig.inspect :as inspect]
            [clj-zig.signature :as signature]
            [clj-zig.source :as source]
            [clj-zig.spec :as spec]
            [clj-zig.type :as type]))

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

(defn modules
  "The external Zig modules the function links, each `{:name :fingerprint
  :status}`, or nil when it depends on none."
  [the-var]
  (inspect/modules the-var))

(defn source-mode
  "How the body was supplied: `:inline`, `:file`, or `:raw`."
  [the-var]
  (inspect/source-mode the-var))

(defn source-file
  "The path of the `.zig` file the body was loaded from, or nil for an
  inline body."
  [the-var]
  (inspect/source-file the-var))

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
  "Remove the artifact cache under `root` (default `.clj-zig/cache`)."
  ([] (cachestore/clean!))
  ([root] (cachestore/clean! root)))

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
  "Emit the Zig wrapper for `spec` with `body` spliced in. With `opts`
  `{:mode :file :entry \"name\"}` the wrapper instead calls a user fn."
  ([spec body] (source/generate spec body))
  ([spec body opts] (source/generate spec body opts)))

;; --- Shell pipeline -----------------------------------------------------

(defn compile!
  "Compile or reuse the native library for `spec` and `body`, returning the
  artifact data (paths, symbol, generated source, build status). `gen`
  selects the source mode (`:inline`, `:file`, `:raw`) and carries any
  C-interop options."
  ([spec body] (core/artifact spec body))
  ([spec body gen] (core/artifact spec body gen)))

(defn load!
  "Load a compiled `artifact` and return the callable native invoker."
  [artifact]
  (ffm/bind (:spec artifact) (:library artifact)))

(defn fn
  "Build a callable function from `spec` and `body` in one step:
  `compile!` then `load!`."
  ([spec body] (load! (compile! spec body)))
  ([spec body gen] (load! (compile! spec body gen))))

(comment
  (core/defnz add [x :i64 y :i64 :ret :i64] "return x + y;")
  (source #'add)                   ;; => "return x + y;"
  (spec #'add)                     ;; => {:ns ... :name add :params [...] :ret {...} ...}

  ;; Drive the pipeline directly, without the macro.
  (let [s   (build-spec '{:ns app.core :name mul :signature [x :i64 y :i64 :ret :i64]})
        mul (fn s "return x * y;")]
    (mul 6 7)))                     ;; => 42