(ns bigint
  "The 128-bit integers cross as BigInteger. A probe confirmed FFM passes
  and returns __int128 correctly as a struct of two longs. Load a REPL
  with `clojure -M:repl` and evaluate the comment block."
  (:require [clj-zig.core :refer [defnz]]))

(defnz big-add
  "Add two signed 128-bit integers."
  [a :i128
   b :i128
   :ret :i128]
  "return a + b;")

(defnz big-id
  "Echo an unsigned 128-bit integer (values above the signed range stay
  non-negative BigInteger, never truncated negative)."
  [x :u128
   :ret :u128]
  "return x;")

(comment
  (big-add 1 2)                                       ;; => 3
  (big-add 100000000000000000000M
           200000000000000000000M)                    ;; => 300000000000000000000M

  ;; 2^128 - 1, the unsigned maximum: stays a non-negative BigInteger.
  (big-id 340282366920938463463374607431768211455M)
  )
