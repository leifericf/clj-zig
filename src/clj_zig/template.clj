(ns clj-zig.template
  "Reusable Zig body templates for common patterns. Each template returns
  a string of Zig source a `defnz` body can splice in or call. The
  templates are data-driven: they take the parameter names and produce
  idiomatic Zig, so the user does not hand-write the same boilerplate
  for every binary operation, slice fold, or comparison.

      (defnz square [x :f64 :ret :f64]
        (template/binary-op \"*\" 'x 'x))

      (defnz sum-slice [xs [:slice :const :f64] :ret :f64]
        (template/slice-fold 'xs 0.0 \"+\"))"
  (:require [clojure.string :as str]))

(defn binary-op
  "Zig source for `lhs op rhs`, returning the result. A simple wrapper
  for the most common body shape: one binary operation on two named
  bindings."
  [op lhs rhs]
  (str "return " lhs " " op " " rhs ";"))

(defn reducer-step
  "Zig source that applies `op` (a compound assignment like `+=`, `*=`)
  between `acc` and each element of `binding`, with an optional
  accumulator type annotation."
  ([binding init op] (reducer-step binding nil init op))
  ([binding acc-type init op]
   (str "var acc" (when acc-type (str ": " acc-type)) " = " init ";\n"
        "for (" binding ") |__elem| acc " op " __elem;\n"
        "return acc;")))

(defn slice-fold
  "Zig source that folds a slice with an accumulator. Iterates `binding`,
  combines each element with the accumulator using the compound assignment
  `op` (e.g. `+=`, `*=`, etc.), starting from `init`. Returns the accumulator."
  [binding init op]
  (reducer-step binding init op))

(defn comparator
  "Zig source for a comparison returning bool. `op` is one of `<`, `>`,
  `<=`, `>=`, `==`, `!=`."
  [op lhs rhs]
  (binary-op op lhs rhs))

(defn clamp
  "Zig source that clamps `val` between `lo` and `hi`."
  [val lo hi]
  (str "if (" val " < " lo ") return " lo ";\n"
       "if (" val " > " hi ") return " hi ";\n"
       "return " val ";"))

(defn conditional
  "Zig source for a conditional return: `if (pred) return t; return f;`"
  [pred t f]
  (str "if (" pred ") return " t ";\n"
       "return " f ";"))
