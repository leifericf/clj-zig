(ns clj-zig.zig
  "The three-level node model for Zig source generation. Generators
  produce declaration, statement, and expression nodes as plain data;
  the single `render` function turns them into Zig source text. This is
  the ONLY namespace that knows about braces, newlines, indentation, and
  semicolons.

  The node model is a pruned view of Zig's own AST decomposition. It
  covers the subset clj-zig generates: function declarations, struct and
  enum declarations, the common statement forms (const, assign, return,
  if, for, defer), and a small expression vocabulary (ref, field, deref,
  call, lit, as, slice). The `:raw` node at each level is the escape
  hatch for user-written Zig body text and one-off complex expressions
  the generators do not model.

  Generators compose nodes with the constructor functions, never with
  `str`. Type strings (like `\"i64\"` or `\"[]const f64\"`) are the
  exception: they are opaque tokens passed through from `zig-type`. The
  four-space indent appears ONLY in the renderer's `indent` function."
  (:require [clojure.string :as str]))

;; --- Indentation (the only place four spaces live) -----------------------

(defn- indent
  "The indentation string for `level` (four spaces per level)."
  [level]
  (str/join (repeat level "    ")))

(defn- indent-lines
  "Indent each non-blank line of `text` by `level`. The text itself uses
  level-0 relative indentation; this function adds the absolute offset."
  [text level]
  (let [pad (indent level)]
    (->> (str/split-lines text)
         (map (fn [line] (if (str/blank? line) "" (str pad line))))
         (str/join "\n"))))

;; --- Expression nodes ----------------------------------------------------

(defmulti ^:private render-expr
  "Dispatch on the `:expr` key of the node. A raw string is also accepted
  and returned as-is."
  :expr)

(defmethod render-expr :default [e]
  (if (string? e) e
      (throw (ex-info "Unknown expression node" {:node e}))))

(defmethod render-expr :ref [{:keys [name]}] name)
(defmethod render-expr :field [{:keys [base field]}]
  (str (render-expr base) "." field))
(defmethod render-expr :deref [{:keys [base]}]
  (str (render-expr base) ".*"))
(defmethod render-expr :call [{:keys [fn args]}]
  (str fn "(" (str/join ", " (map render-expr args)) ")"))
(defmethod render-expr :lit [{:keys [value]}] value)
(defmethod render-expr :as [{:keys [type value]}]
  (str "@as(" type ", " (render-expr value) ")"))
(defmethod render-expr :slice [{:keys [base from to]}]
  (str (render-expr base) "[" (render-expr from) ".." (render-expr to) "]"))
(defmethod render-expr :raw [{:keys [text]}] text)

;; --- Statement nodes -----------------------------------------------------

(declare render-body)

(defmulti ^:private render-stmt
  "Dispatch on the `:stmt` key. The `level` argument is the indentation
  level of the enclosing block (1 for a function body, 2 inside a for or
  if, etc.)."
  (fn [stmt _level] (:stmt stmt)))

(defmethod render-stmt :const [{:keys [name type init]} level]
  (let [init-str (render-expr init)
        decl (if type
               (str name ": " type " = " init-str)
               (str name " = " init-str))]
    (str (indent level) "const " decl ";")))

(defmethod render-stmt :assign [{:keys [target value]} level]
  (str (indent level) (render-expr target) " = " (render-expr value) ";"))

(defmethod render-stmt :return [{:keys [value]} level]
  (if value
    (str (indent level) "return " (render-expr value) ";")
    (str (indent level) "return;")))

(defmethod render-stmt :if [{:keys [cond body else]} level]
  (let [then-str (render-body body (inc level))
        base (str (indent level) "if (" (render-expr cond) ") {\n"
                  then-str "\n"
                  (indent level) "}")]
    (if (seq else)
      (str base " else {\n"
           (render-body else (inc level)) "\n"
           (indent level) "}")
      base)))

(defmethod render-stmt :for [{:keys [header body]} level]
  (str (indent level) "for " header " {\n"
       (render-body body (inc level)) "\n"
       (indent level) "}"))

(defmethod render-stmt :defer [{:keys [expr]} level]
  (str (indent level) "defer " (render-expr expr) ";"))

(defmethod render-stmt :expr-stmt [{:keys [value]} level]
  (str (indent level) (render-expr value) ";"))

(defmethod render-stmt :raw [{:keys [text]} level]
  (indent-lines text level))

(defn ^:private render-body
  "Render a vector of statement nodes at `level`, one per line."
  [stmts level]
  (str/join "\n" (map #(render-stmt % level) stmts)))

;; --- Declaration nodes ---------------------------------------------------

(defmulti ^:private render-decl
  "Dispatch on the `:decl` key."
  :decl)

(defmethod render-decl :fn [{:keys [export? name params ret body]}]
  (let [prefix (if export? "export fn" "fn")
        params-str (str/join ", " (map (fn [{:keys [name type]}]
                                         (str name ": " type))
                                       params))
        body-str (if (seq body)
                   (str (render-body body 1) "\n")
                   "")]
    (str prefix " " name "(" params-str ") " ret " {\n" body-str "}")))

(defmethod render-decl :struct [{:keys [name kind fields]}]
  (let [kw (case kind :extern "extern" :packed "packed" "")
        head (if (str/blank? kw)
               (str "const " name " = struct {")
               (str "const " name " = " kw " struct {"))
        fields-str (if (seq fields)
                     (->> (map (fn [{:keys [name type]}]
                                 (str (indent 1) name ": " type ","))
                               fields)
                          (str/join "\n"))
                     "")]
    (if (seq fields)
      (str head "\n" fields-str "\n};")
      (str head "};"))))

(defmethod render-decl :enum [{:keys [name backing members]}]
  (let [members-str (->> (map (fn [{:keys [name value]}]
                                (str (indent 1) name " = " value ","))
                              members)
                         (str/join "\n"))]
    (str "const " name " = enum(" backing ") {\n" members-str "\n};")))

(defmethod render-decl :const [{:keys [name init]}]
  (str "const " name " = " (render-expr init) ";"))

(defmethod render-decl :raw [{:keys [text]}]
  text)

;; --- Public renderer -----------------------------------------------------

(defn render
  "Render a vector of declaration nodes to Zig source text. Each
  declaration is separated by a blank line. The output carries no
  trailing newline."
  [decls]
  (str/join "\n\n" (map render-decl decls)))

;; --- Test helpers (public for unit testing) ------------------------------

(defn render-expr-for-test [e] (render-expr e))
(defn render-stmt-for-test [stmt level] (render-stmt stmt level))

;; --- Constructors: declarations -----------------------------------------

(defn fn-decl
  "A non-exported function declaration node."
  [name params ret body]
  {:decl :fn :export? false :name name
   :params params :ret ret :body body})

(defn export-fn-decl
  "An exported function declaration node (C ABI entry point)."
  [name params ret body]
  {:decl :fn :export? true :name name
   :params params :ret ret :body body})

(defn struct-decl
  "A regular `struct` declaration node."
  [name fields]
  {:decl :struct :name name :kind :regular :fields fields})

(defn extern-struct-decl
  "An `extern struct` declaration node (C ABI layout)."
  [name fields]
  {:decl :struct :name name :kind :extern :fields fields})

(defn packed-struct-decl
  "A `packed struct` declaration node."
  [name fields]
  {:decl :struct :name name :kind :packed :fields fields})

(defn enum-decl
  "An `enum(backing)` declaration node."
  [name backing members]
  {:decl :enum :name name :backing backing :members members})

(defn const-decl
  "A top-level `const` declaration node."
  [name init]
  {:decl :const :name name :init init})

(defn raw-decl
  "A raw declaration node (opaque Zig source text)."
  [text]
  {:decl :raw :text text})

;; --- Constructors: statements -------------------------------------------

(defn const-stmt
  "A local `const` statement: `const name = init;` or
  `const name: type = init;`."
  ([name init]
   {:stmt :const :name name :init init})
  ([name type init]
   {:stmt :const :name name :type type :init init}))

(defn assign-stmt
  "An assignment statement: `target = value;`."
  [target value]
  {:stmt :assign :target target :value value})

(defn return-stmt
  "A return statement. With no argument, a bare `return;`."
  ([]
   {:stmt :return})
  ([value]
   {:stmt :return :value value}))

(defn if-stmt
  "An if statement: `if (cond) { body }` with an optional else branch."
  ([cond body]
   {:stmt :if :cond cond :body body})
  ([cond body else]
   {:stmt :if :cond cond :body body :else else}))

(defn for-stmt
  "A for loop: `for header { body }`. The `header` is a raw string like
  `(__nice, 0..) |*__dst, __i|` because the for-loop capture syntax is
  too varied to model."
  [header body]
  {:stmt :for :header header :body body})

(defn defer-stmt
  "A defer statement: `defer expr;`."
  [expr]
  {:stmt :defer :expr expr})

(defn expr-stmt
  "An expression statement: `expr;`."
  [value]
  {:stmt :expr-stmt :value value})

(defn raw-stmt
  "A raw statement node (opaque Zig source, indented by the renderer at
  the enclosing level). Used for user body text and multi-line fragments
  whose indentation is relative to the function body."
  [text]
  {:stmt :raw :text text})

;; --- Constructors: expressions ------------------------------------------

(defn ref [name]
  {:expr :ref :name name})

(defn field [base field-name]
  {:expr :field :base base :field field-name})

(defn deref [base]
  {:expr :deref :base base})

(defn call [fn-name args]
  {:expr :call :fn fn-name :args args})

(defn lit [value]
  {:expr :lit :value value})

(defn as [type value]
  {:expr :as :type type :value value})

(defn slice [base from to]
  {:expr :slice :base base :from from :to to})

(defn raw-expr [text]
  {:expr :raw :text text})

;; --- Field and param data constructors ----------------------------------

(defn field-data
  "One struct field as node data: `{:name name :type type}`."
  [name type]
  {:name name :type type})

(defn param
  "One function parameter as node data: `{:name name :type type}`."
  [name type]
  {:name name :type type})
