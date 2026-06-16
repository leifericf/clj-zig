(ns zigar.source
  "Generate the Zig wrapper source from a boundary spec (pure). A spec
  and the user's body go in, a string of readable Zig comes out.

  For a scalar function the wrapper is an `export fn` (C ABI, so FFM can
  bind it) whose parameters are the native params and whose body is the
  user's Zig spliced in verbatim:

      export fn zigar_app_2e_core_add(x: i64, y: i64) i64 {
          return x + y;
      }

  `zig fmt` owns final formatting (CLAUDE.md); the generator emits
  already-canonical Zig for the structure it controls, and the compile
  shell runs `zig fmt` over the whole file to normalize the body."
  (:require [clojure.string :as str]))

(defn- zig-type
  "The Zig type name for a normalized boundary type. Scalars only for
  now; compound types arrive with the type vocabulary in Part 2."
  [t]
  (case (:kind t)
    :scalar (name (:name t))
    :named  (str (:name t))
    (throw (ex-info "Source generation does not yet support this boundary type."
                    {:level :error
                     :error/code :zigar/unsupported-source-type
                     :kind (:kind t)
                     :zigar/type-form t}))))

(defn- param-decl [{:keys [binding type]}]
  (str binding ": " (zig-type type)))

(defn- indent-body
  "Trim the body and indent every non-blank line by four spaces, the Zig
  function-body level."
  [body]
  (->> (str/split-lines (str/trim body))
       (map (fn [line] (if (str/blank? line) "" (str "    " line))))
       (str/join "\n")))

(defn generate
  "Emit the Zig wrapper for `spec` with the user's `body` spliced in."
  [spec body]
  (let [{:keys [params ret] sym :symbol} spec
        params-str (str/join ", " (map param-decl params))]
    (str "export fn " sym "(" params-str ") " (zig-type ret) " {\n"
         (indent-body body) "\n"
         "}\n")))
