(ns rest-args
  "Variadic scalar functions: `& binding type` lowers to a const slice.
  Load a REPL with `clojure -M:repl` and evaluate the comment block."
  (:require [clj-zig.core :refer [defnz]]))

(defnz maximum
  "The largest of a leading value and any number of trailing ones."
  [x :i64
   & rest :i64
   :ret :i64]
  "var m = x;
   for (rest) |v| { if (v > m) m = v; }
   return m;")

(defnz total
  "Sum any number of doubles. The empty rest is a zero-length slice."
  [& xs :f64
   :ret :f64]
  "var t: f64 = 0;
   for (xs) |x| t += x;
   return t;")

(comment
  (maximum 1 2 3 4)      ;; => 4
  (maximum 7)            ;; => 7, empty rest
  (maximum -1 -2 -3)     ;; => -1

  (total 1.0 2.0 3.0)    ;; => 6.0
  (total)                ;; => 0.0, no rest args at all
  )
