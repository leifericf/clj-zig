(ns pointers
  "Small, self-contained pointer examples. A single-item pointer (`:ptr`)
  points at one value behind a one-element array; a many-item pointer
  (`:manyptr`) points at a run of values whose length the caller tracks. A
  const pointee is read-only; a mutable one is copied back after the call.
  Start a REPL with `clojure -M:repl`, load this file, and evaluate the
  forms in the comment block."
  (:require [zigar.core :refer [defnz]]))

(defnz incr!
  "Increments the single integer a mutable pointer addresses."
  [p [:ptr :i64]
   :ret :void]
  "p.* += 1;")

(defnz dot
  "Dot product of two equal-length runs of doubles."
  [a [:manyptr :const :f64]
   b [:manyptr :const :f64]
   n :usize
   :ret :f64]
  "var total: f64 = 0;
   var i: usize = 0;
   while (i < n) : (i += 1) {
       total += a[i] * b[i];
   }
   return total;")

(comment
  (let [cell (long-array [41])]
    (incr! cell)
    (aget cell 0))                  ;; => 42

  (dot (double-array [1.0 2.0 3.0])
       (double-array [4.0 5.0 6.0])
       3))                          ;; => 32.0
