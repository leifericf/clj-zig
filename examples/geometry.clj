(ns geometry
  "A namespace of native functions. The Clojure namespace is the Zig
  namespace: each function is declared bodyless and its body is the
  matching `pub fn` in the co-located `geometry.zig`. Shared imports, a
  helper, and the C link live once in that file and in `zig-deps`. Start a
  REPL with `clojure -M:repl`, load this file, and evaluate the comment
  block."
  (:require [clj-zig.core :refer [defnz zig-deps]]))

;; Link libm once for the whole namespace; geometry.zig @cImports math.h
;; and calls c.sqrt.
(zig-deps {:c/link ["m"]})

(defnz hypotenuse
  "Euclidean distance from the origin, via C's sqrt."
  [a :f64
   b :f64
   :ret :f64])

(defnz circle-area
  "Area of a circle of radius r."
  [r :f64
   :ret :f64])

(comment
  ;; Each call runs the pub fn of the same name in geometry.zig.
  (hypotenuse 3.0 4.0)   ;; => 5.0
  (circle-area 2.0))     ;; => 12.566370614359172
