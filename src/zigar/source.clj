(ns zigar.source
  "Generate the Zig wrapper source from a boundary spec (pure). A spec
  and the user's body go in, a string of readable Zig comes out.

  For a scalar function the wrapper is an `export fn` (C ABI, so FFM can
  bind it) whose parameters are the native params and whose body is the
  user's Zig spliced in verbatim:

      export fn zigar_app_2e_core_add(x: i64, y: i64) i64 {
          return x + y;
      }

  `zig fmt` owns final formatting; the generator emits already-canonical
  Zig for the structure it controls, and the compile shell runs `zig
  fmt` over the whole file to normalize the body."
  (:require [clojure.string :as str]))

(defn- zig-type
  "The Zig type name for a normalized boundary type. Handles scalars and
  named types; `param-decls` lowers slices."
  [t]
  (case (:kind t)
    :scalar (name (:name t))
    :named  (str (:name t))
    (throw (ex-info "Source generation does not yet support this boundary type."
                    {:level :error
                     :error/code :zigar/unsupported-source-type
                     :kind (:kind t)
                     :zigar/type-form t}))))

(defn- pointee
  "The Zig pointee type of a slice or pointer, with its `const` qualifier."
  [{:keys [const? of]}]
  (str (when const? "const ") (zig-type of)))

(defn- param-decls
  "The Zig parameter declarations for one boundary param. A scalar or
  pointer is one declaration; a slice is two, a many-item pointer and a
  `usize` length, the pair it crosses the C ABI as."
  [{:keys [binding type]}]
  (case (:kind type)
    :slice   [(str binding "_ptr: [*]" (pointee type))
              (str binding "_len: usize")]
    :manyptr [(str binding ": [*]" (pointee type))]
    :ptr     [(str binding ": *" (pointee type))]
    [(str binding ": " (zig-type type))]))

(defn- slice-reconstruction
  "The statement that rebuilds a slice from its pointer and length."
  [{:keys [binding type]}]
  (when (= :slice (:kind type))
    (str "const " binding " = " binding "_ptr[0.." binding "_len];")))

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
        params-str (str/join ", " (mapcat param-decls params))
        prelude    (str/join "\n" (keep slice-reconstruction params))
        full-body  (if (str/blank? prelude) body (str prelude "\n" body))]
    (str "export fn " sym "(" params-str ") " (zig-type ret) " {\n"
         (indent-body full-body) "\n"
         "}\n")))

(comment
  (require '[zigar.spec :as spec])
  (let [s (spec/build-spec '{:ns app.core :name add :signature [x :i64 y :i64 :ret :i64]})]
    (print (generate s "return x + y;")))
  ;; export fn zigar_app_2e_core_add(x: i64, y: i64) i64 {
  ;;     return x + y;
  ;; }

  ;; A slice crosses as a pointer and a length, rebuilt before the body.
  (let [s (spec/build-spec '{:ns app.core :name sum :signature [xs [:slice :const :f64] :ret :f64]})]
    (print (generate s "var t: f64 = 0; for (xs) |x| t += x; return t;"))))
  ;; export fn zigar_app_2e_core_sum(xs_ptr: [*]const f64, xs_len: usize) f64 {
  ;;     const xs = xs_ptr[0..xs_len];
  ;;     ...
  ;; }
