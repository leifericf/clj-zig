(ns arrays
  "Small, self-contained fixed-size array examples. An `[:array n T]`
  argument is a read-only value of exactly `n` elements; the caller passes
  a matching primitive array. Start a REPL with `clojure -M:repl`, load
  this file, and evaluate the forms in the comment block."
  (:require [clj-zig.core :refer [defnz]]))

(defnz mean3
  "Mean of a fixed run of three doubles."
  [xs [:array 3 :f64]
   :ret :f64]
  "var total: f64 = 0;
   for (xs) |x| {
       total += x;
   }
   return total / xs.len;")

(defnz max4
  "Largest of four signed integers, by value."
  [xs [:array 4 :i32]
   :ret :i32]
  "var best = xs[0];
   for (xs) |x| {
       if (x > best) best = x;
   }
   return best;")

(comment
  (mean3 (double-array [1.0 2.0 6.0]))   ;; => 3.0
  (max4 (int-array [3 9 2 7])))          ;; => 9
