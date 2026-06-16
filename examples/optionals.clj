(ns optionals
  "Small, self-contained optional examples. An `[:optional [:ptr T]]`
  argument is nil or a one-element array; nil crosses as a null pointer. A
  single-item optional pointer return comes back as the value or nil.
  Start a REPL with `clojure -M:repl`, load this file, and evaluate the
  forms in the comment block."
  (:require [zigar.core :refer [defnz defz]]))

(defnz value-or-zero
  "The integer behind an optional pointer, or zero when absent."
  [p [:optional [:ptr :const :i64]]
   :ret :i64]
  "return if (p) |q| q.* else 0;")

(defz pi_val "const pi_val: f64 = 3.14159;")

(defnz maybe-pi
  "Returns pi when `want` is true, otherwise nil."
  [want :bool
   :ret [:optional [:ptr :const :f64]]]
  "return if (want) &pi_val else null;")

(comment
  (value-or-zero (long-array [99]))   ;; => 99
  (value-or-zero nil)                 ;; => 0

  (maybe-pi true)                     ;; => 3.14159
  (maybe-pi false))                   ;; => nil
