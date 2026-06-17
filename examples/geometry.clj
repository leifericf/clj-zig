(ns geometry
  "A namespace of native functions. The Clojure namespace is the Zig
  namespace: each function is declared with no body and no signature, and
  both come from the matching `pub fn` in the co-located `geometry.zig`.
  The signature is inferred from the prototype; shared imports, a helper,
  and the C link live once in that file and in `zig-deps`. Start a REPL
  with `clojure -M:repl`, load this file, and evaluate the comment block."
  (:require [clj-zig.core :refer [defnz zig-deps]]))

;; Link libm once for the whole namespace; geometry.zig @cImports math.h
;; and calls c.sqrt.
(zig-deps {:c/link ["m"]})

;; No signature: it is read from `pub fn hypotenuse(a: f64, b: f64) f64`.
(defnz hypotenuse
  "Euclidean distance from the origin, via C's sqrt.")

(defnz circle-area
  "Area of a circle of radius r.")

(comment
  ;; Each call runs the pub fn of the same name in geometry.zig.
  (hypotenuse 3.0 4.0)   ;; => 5.0
  (circle-area 2.0))     ;; => 12.566370614359172
