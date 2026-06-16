(ns simd
  "Explicit SIMD over native vector registers, something the JVM does not
  give a Clojure programmer. The JVM auto-vectorizes unpredictably and its
  Vector API is an incubating module that is awkward to drive from Clojure.
  Here the width is named in the source: the body loads `@Vector(8, f32)`
  registers from the argument slices, multiplies and accumulates a lane at
  a time, and reduces at the end. Start a REPL with `clojure -M:repl`, load
  this file, and evaluate the forms in the comment block."
  (:require [clj-zig.core :refer [defnz]]))

(defnz dot
  "Dot product of two f32 slices, accumulated eight lanes at a time in a
  single vector register, with a scalar tail for the remainder."
  [xs [:slice :const :f32]
   ys [:slice :const :f32]
   :ret :f32]
  "const lanes = 8;
   const V = @Vector(lanes, f32);
   const n = @min(xs.len, ys.len);
   var acc: V = @splat(0.0);
   var i: usize = 0;
   while (i + lanes <= n) : (i += lanes) {
       const a: V = xs[i..][0..lanes].*;
       const b: V = ys[i..][0..lanes].*;
       acc += a * b;
   }
   var total: f32 = @reduce(.Add, acc);
   while (i < n) : (i += 1) {
       total += xs[i] * ys[i];
   }
   return total;")

(defnz saxpy!
  "In-place single-precision a*x + y: scales `xs` by `a` and adds `ys`,
  writing the result back into `xs` a vector at a time."
  [xs [:slice :f32]
   ys [:slice :const :f32]
   a :f32
   :ret :void]
  "const lanes = 8;
   const V = @Vector(lanes, f32);
   const n = @min(xs.len, ys.len);
   const av: V = @splat(a);
   var i: usize = 0;
   while (i + lanes <= n) : (i += lanes) {
       const x: V = xs[i..][0..lanes].*;
       const y: V = ys[i..][0..lanes].*;
       xs[i..][0..lanes].* = av * x + y;
   }
   while (i < n) : (i += 1) {
       xs[i] = a * xs[i] + ys[i];
   }")

(comment
  ;; A vector register accumulates the products; the result matches the
  ;; plain scalar dot product to f32 precision.
  (dot (float-array [1.0 2.0 3.0 4.0 5.0 6.0 7.0 8.0 9.0])
       (float-array [9.0 8.0 7.0 6.0 5.0 4.0 3.0 2.0 1.0]))
  ;; => 165.0

  (dot (float-array [2.0 2.0 2.0]) (float-array [3.0 3.0 3.0]))
  ;; => 18.0

  ;; saxpy! rewrites xs in place: 2*x + y.
  (let [xs (float-array [1.0 2.0 3.0 4.0])
        ys (float-array [10.0 10.0 10.0 10.0])]
    (saxpy! xs ys 2.0)
    (vec xs)))
  ;; => [12.0 14.0 16.0 18.0]
