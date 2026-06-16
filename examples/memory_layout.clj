(ns memory-layout
  "Explicit memory layout over a packed native buffer, mutated in place.
  The JVM gives a Clojure programmer neither control over how numbers are
  laid out nor freedom from the garbage collector: a vector of points is a
  tree of boxed objects, and a numeric loop fights allocation and GC
  pauses. Here a flat run of f64 is laid out by hand as [x, y, vx, vy] per
  particle. The kernels stride it with explicit offsets, read and write
  the caller's memory directly, and allocate nothing. Start a REPL with
  `clojure -M:repl`, load this file, and evaluate the comment block."
  (:require [clj-zig.core :refer [defnz]]))

(defnz step!
  "Advances packed 2D particles one timestep in place. Gravity changes
  each vy, then position integrates from velocity. No copy, no allocation:
  the caller's buffer is the working set."
  [buf [:slice :f64]
   dt :f64
   g :f64
   :ret :void]
  "const stride = 4;
   var i: usize = 0;
   while (i + stride <= buf.len) : (i += stride) {
       buf[i + 3] += g * dt;
       buf[i + 0] += buf[i + 2] * dt;
       buf[i + 1] += buf[i + 3] * dt;
   }")

(defnz kinetic-energy
  "Total kinetic energy of the same packed particles, read in place with
  no copy: it strides the [x, y, vx, vy] layout and sums one half m v^2
  with unit mass."
  [buf [:slice :const :f64]
   :ret :f64]
  "const stride = 4;
   var total: f64 = 0;
   var i: usize = 0;
   while (i + stride <= buf.len) : (i += stride) {
       const vx = buf[i + 2];
       const vy = buf[i + 3];
       total += 0.5 * (vx * vx + vy * vy);
   }
   return total;")

(comment
  ;; Two particles packed as [x, y, vx, vy, x, y, vx, vy].
  (def world (double-array [0.0 0.0 1.0 0.0
                            10.0 0.0 0.0 2.0]))

  ;; Read the layout in place, no allocation: 0.5*(1) + 0.5*(4) = 2.5.
  (kinetic-energy world)
  ;; => 2.5

  ;; One step with dt = 1 and g = -10 rewrites the same buffer in place.
  (step! world 1.0 -10.0)
  (vec world))
  ;; => [1.0 -10.0 1.0 -10.0 10.0 -8.0 0.0 -8.0]
