(ns slices
  "Small, self-contained slice examples, each defined and called end to
  end. A const slice is a read-only view; a mutable slice may be changed
  during the call. Start a REPL with `clojure -M:repl`, load this file,
  and evaluate the forms in the comment block."
  (:require [zigar.core :refer [defnz]]))

(defnz sum
  "Sums a read-only slice of doubles."
  [xs [:slice :const :f64]
   :ret :f64]
  "var total: f64 = 0;
   for (xs) |x| {
       total += x;
   }
   return total;")

(defnz scale!
  "Multiplies every element of a mutable slice in place."
  [xs [:slice :i32]
   factor :i32
   :ret :void]
  "for (xs) |*e| {
       e.* *= factor;
   }")

(comment
  (sum (double-array [1.0 2.0 3.0 4.0]))   ;; => 10.0
  (sum (double-array []))                  ;; => 0.0

  ;; A mutable slice is changed in place and copied back.
  (let [xs (int-array [1 2 3 4])]
    (scale! xs 10)
    (vec xs)))                             ;; => [10 20 30 40]
