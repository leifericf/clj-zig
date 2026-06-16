(ns error-unions
  "Small, self-contained error-union examples. A `[:error-union E T]`
  return gives back the value on success and the Zig error name as a
  keyword on failure. Start a REPL with `clojure -M:repl`, load this
  file, and evaluate the forms in the comment block."
  (:require [clj-zig.core :refer [defnz defz]]))

(defz DivError "const DivError = error{DivByZero};")

(defnz divide
  "Integer division that reports division by zero as data."
  [a :i64
   b :i64
   :ret [:error-union DivError :i64]]
  "if (b == 0) return error.DivByZero;
   return @divTrunc(a, b);")

(defnz checked-sqrt
  "Square root of a non-negative double, or an error keyword."
  [x :f64
   :ret [:error-union anyerror :f64]]
  "if (x < 0) return error.Negative;
   return @sqrt(x);")

(comment
  (divide 20 4)        ;; => 5
  (divide 1 0)         ;; => :DivByZero

  (checked-sqrt 9.0)   ;; => 3.0
  (checked-sqrt -1.0)  ;; => :Negative

  ;; Branch on the result: a keyword is the error.
  (let [r (divide 7 0)]
    (if (keyword? r) (str "failed: " r) r)))
