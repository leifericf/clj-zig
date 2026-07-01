(ns nested-structs
  "A struct field may itself be a struct, crossed by value. The inner
  `extern struct` is embedded in the outer, the way a C struct holds
  another by value. Load a REPL with `clojure -M:repl` and evaluate the
  comment block."
  (:require [clj-zig.core :refer [defnz deftypez]]))

(deftypez Point
  [x :f64
   y :f64])

(deftypez Rect
  [origin Point
   size   Point])

(defnz make-rect
  "Build a rectangle from a corner and a size."
  [x :f64
   y :f64
   w :f64
   h :f64
   :ret Rect]
  "return .{ .origin = .{ .x = x, .y = y }, .size = .{ .x = w, .y = h } };")

(defnz rect-area
  "The area, reading the inner size fields of a Rect argument."
  [r Rect
   :ret :f64]
  "return r.size.x * r.size.y;")

(defnz center
  "The center point of a rectangle, returning a nested-struct field of a
  struct return."
  [r Rect
   :ret Point]
  "return .{ .x = r.origin.x + r.size.x / 2.0, .y = r.origin.y + r.size.y / 2.0 };")

(comment
  (make-rect 0.0 0.0 4.0 6.0)
  ;; => {:origin {:x 0.0 :y 0.0}, :size {:x 4.0 :y 6.0}}

  (rect-area {:origin {:x 0.0 :y 0.0} :size {:x 4.0 :y 6.0}})
  ;; => 24.0

  (center {:origin {:x 0.0 :y 0.0} :size {:x 4.0 :y 6.0}})
  ;; => {:x 2.0 :y 3.0}
  )
