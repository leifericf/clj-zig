(ns clj-zig.doc
  "Generate documentation for `defnz` functions from their inspection
  metadata. Pure functions turn a Var (or its metadata) into a markdown
  string; `emit-namespace-to-file!` is the shell writer.

      (clj-zig.doc/emit-var #'add)
      ;; => \"### add\\n\\n`[x :i64 y :i64 :ret :i64]`\\n\\nAdds two integers.\\n\"

      (clj-zig.doc/emit-namespace 'my.app)
      ;; => full markdown for every defnz in the namespace"
  (:require [clojure.string :as str]))

(defn- of-type-str
  "A readable string for the element under a slice or stream wrapper's `:of`."
  [t]
  (case (get-in t [:of :kind])
    :scalar (name (get-in t [:of :name]))
    :named  (str (get-in t [:of :name]))
    (pr-str (:of t))))

(defn- type-str
  "A readable string for a normalized boundary type."
  [t]
  (case (:kind t)
    :scalar (name (:name t))
    :named  (str (:name t))
    :string "string"
    :void   "void"
    :slice  (str "[:slice " (when (:const? t) ":const ")
                 (of-type-str t) "]")
    :stream (str "[:stream " (of-type-str t) "]")
    (pr-str t)))

(defn- param-table-row
  "One markdown table row for a boundary param."
  [{:keys [binding type]}]
  (str "| `" binding "` | `" (type-str type) "` |"))

(defn- param-table
  "A markdown table of params and their types."
  [params]
  (when (seq params)
    (str "| Param | Type |\n|-------|------|\n"
         (str/join "\n" (map param-table-row params)))))

(defn emit-var
  "Generate a markdown section documenting one `defnz` Var. Includes the
  function name, docstring, signature table, and return type. Returns nil
  for a non-defnz Var (no `:clj-zig/info` metadata)."
  [the-var]
  (let [m     (meta the-var)
        info  (:clj-zig/info m)]
    (when info
      (let [var-name (str (:name m))
            doc      (:doc m)
            spec     (:spec info)
            params   (:params spec)
            ret      (:ret spec)]
        (str/join "\n\n"
                  (remove nil?
                          [(str "### " var-name)
                           (str "```clojure\n(" var-name
                                (when (seq params)
                                  (str " " (str/join " " (map (comp str :binding) params))))
                                ")\n```")
                           doc
                           (param-table params)
                           (str "**Returns:** `" (type-str ret) "`")]))))))

(defn emit-namespace
  "Generate markdown documentation for every `defnz` Var in `ns-sym`.
  Vars are sorted alphabetically by name."
  [ns-sym]
  (let [vars (->> (ns-interns ns-sym)
                  (filter (fn [[_ v]] (:clj-zig/info (meta v))))
                  (sort-by (comp str key))
                  (map val))]
    (str "# " ns-sym "\n\n"
         (str/join "\n\n---\n\n" (map emit-var vars)))))

(defn emit-namespace-to-file!
  "Write the documentation for `ns-sym` to `path` as markdown."
  [ns-sym path]
  (spit path (emit-namespace ns-sym)))
