(ns structs
  "Small, self-contained struct examples. A `deftypez` names an
  `extern struct` layout; a `defnz` may take it as an argument or return
  it. A struct argument is a Clojure map and a struct return comes back as
  a map keyed by field name. Start a REPL with `clojure -M:repl`, load
  this file, and evaluate the forms in the comment block."
  (:require [clj-zig.core :refer [defnz deftypez]]))

(deftypez Point
  [x :f64
   y :f64])

(defnz distance
  "Euclidean distance between two points."
  [a Point
   b Point
   :ret :f64]
  "const dx = a.x - b.x;
   const dy = a.y - b.y;
   return @sqrt(dx * dx + dy * dy);")

(defnz translate
  "Shifts a point by a delta, returning a new point."
  [p Point
   dx :f64
   dy :f64
   :ret Point]
  "return .{ .x = p.x + dx, .y = p.y + dy };")

(comment
  (distance {:x 0.0 :y 0.0} {:x 3.0 :y 4.0})   ;; => 5.0
  (translate {:x 1.0 :y 1.0} 2.0 3.0))         ;; => {:x 3.0 :y 4.0}
