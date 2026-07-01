(ns slice-of-struct
  "A slice or array may hold a named struct element, crossed by value in
  bulk. A const slice argument is a Clojure collection of maps; an owned
  slice return comes back as a vector of maps and frees its slab. Load a
  REPL with `clojure -M:repl` and evaluate the comment block."
  (:require [clj-zig.core :refer [defnz deftypez]]))

(deftypez Vertex
  [x :f32
   y :f32
   z :f32])

(defnz centroid
  "The mean of every vertex a const slice carries."
  [vs [:slice :const Vertex]
   :ret Vertex]
  "var sx: f32 = 0; var sy: f32 = 0; var sz: f32 = 0;
   for (vs) |v| { sx += v.x; sy += v.y; sz += v.z; }
   const n: f32 = @floatFromInt(vs.len);
   return .{ .x = sx / n, .y = sy / n, .z = sz / n };")

(defnz lattice
  "Allocate n vertices (0,0,0), (1,0,0), (2,0,0), ... and return them
  as an owned slice the caller reads as a vector of maps."
  [n :usize
   :ret [:owned [:slice Vertex]]]
  "const out = std.heap.c_allocator.alloc(Vertex, n) catch @panic(\"oom\");
   for (out, 0..) |*v, i| v.* = .{ .x = @floatFromInt(i), .y = 0, .z = 0 };
   return out;")

(defnz triangle-centroid
  "A fixed-size array of three vertices, summed."
  [vs [:array 3 Vertex]
   :ret Vertex]
  "var sx: f32 = 0; var sy: f32 = 0; var sz: f32 = 0;
   for (vs) |v| { sx += v.x; sy += v.y; sz += v.z; }
   return .{ .x = sx / 3.0, .y = sy / 3.0, .z = sz / 3.0 };")

(comment
  (centroid [{:x 0.0 :y 0.0 :z 0.0}
             {:x 3.0 :y 0.0 :z 0.0}
             {:x 0.0 :y 3.0 :z 0.0}])
  ;; => {:x 1.0 :y 1.0 :z 0.0}

  (lattice 4)
  ;; => [{:x 0.0 :y 0.0 :z 0.0} {:x 1.0 :y 0.0 :z 0.0}
  ;;     {:x 2.0 :y 0.0 :z 0.0} {:x 3.0 :y 0.0 :z 0.0}]

  (triangle-centroid [{:x 0.0 :y 0.0 :z 0.0}
                      {:x 6.0 :y 0.0 :z 0.0}
                      {:x 0.0 :y 6.0 :z 0.0}])
  ;; => {:x 2.0 :y 2.0 :z 0.0}
  )
