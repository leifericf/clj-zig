(ns scalar
  "Small, self-contained scalar examples, each defined and called end to
  end. Start a REPL with `clojure -M:repl`, load this file, and evaluate
  the forms in the comment block."
  (:require [zigar.core :refer [defnz]]))

(defnz add
  "Adds two signed integers."
  [x :i64
   y :i64
   :ret :i64]
  "return x + y;")

(defnz hypotenuse
  [a :f64
   b :f64
   :ret :f64]
  "return @sqrt(a * a + b * b);")

(defnz even-i64?
  [n :i64
   :ret :bool]
  "return @rem(n, 2) == 0;")

(comment
  (add 20 22)            ;; => 42
  (hypotenuse 3.0 4.0)   ;; => 5.0
  (even-i64? 10)         ;; => true
  (even-i64? 7)          ;; => false

  ;; An ordinary Clojure function: compose it freely.
  (map add (range 5) (range 5)))   ;; => (0 2 4 6 8)
