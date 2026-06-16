(ns records
  "Small, self-contained record examples. A `defrecordz` defines both a
  Clojure record and an `extern struct` layout under one name. A `defnz`
  may take the record as an argument or return it; an argument is a record
  (or a plain map with the same fields) and a record return comes back as
  a record, not a map. Start a REPL with `clojure -M:repl`, load this
  file, and evaluate the forms in the comment block."
  (:require [zigar.core :refer [defnz defrecordz]]))

(defrecordz Point
  "A 2D point shared between Clojure and Zig."
  [x :f64
   y :f64])

(defnz length
  "Distance of a point from the origin."
  [p Point
   :ret :f64]
  "return @sqrt(p.x * p.x + p.y * p.y);")

(defnz midpoint
  "The point halfway between two points."
  [a Point
   b Point
   :ret Point]
  "return .{ .x = (a.x + b.x) / 2.0, .y = (a.y + b.y) / 2.0 };")

(comment
  (length (->Point 3.0 4.0))                       ;; => 5.0
  (length {:x 3.0 :y 4.0})                          ;; => 5.0
  (midpoint (->Point 0.0 0.0) (->Point 4.0 6.0))   ;; => #records.Point{:x 2.0 :y 3.0}
  (instance? Point (midpoint (->Point 0.0 0.0) (->Point 2.0 2.0))))  ;; => true
