(ns clj-zig.fixtures
  "Compile-once native fixtures for the end-to-end property tests. Every
  `defnz` here is an echo, identity, or oracle function built when this
  namespace loads; the content-addressed cache means a shape compiles once
  and is reused on later runs. The property suites vary the inputs and hold
  these fixtures fixed, so a single compile backs thousands of calls.

  A shared mutable counter cannot live in a `defz` global, because each
  `defnz` compiles to its own library with its own copy. The allocation
  tracker therefore lives in heap memory addressed by a handle threaded
  across calls, which all the libraries dereference through the same
  pointer."
  (:require [clj-zig.core :refer [defnz defz deftypez defrecordz defenumz]]))

;; --- Scalar echoes, one per carrier -------------------------------------

(defnz echo-i8 [x :i8 :ret :i8] "return x;")
(defnz echo-i16 [x :i16 :ret :i16] "return x;")
(defnz echo-i32 [x :i32 :ret :i32] "return x;")
(defnz echo-i64 [x :i64 :ret :i64] "return x;")
(defnz echo-u8 [x :u8 :ret :u8] "return x;")
(defnz echo-u16 [x :u16 :ret :u16] "return x;")
(defnz echo-u32 [x :u32 :ret :u32] "return x;")
(defnz echo-u64 [x :u64 :ret :u64] "return x;")
(defnz echo-isize [x :isize :ret :isize] "return x;")
(defnz echo-usize [x :usize :ret :usize] "return x;")
(defnz echo-f32 [x :f32 :ret :f32] "return x;")
(defnz echo-f64 [x :f64 :ret :f64] "return x;")
(defnz echo-bool [x :bool :ret :bool] "return x;")

(defnz swallow [x :i64 :ret :void] "_ = x;")

(def scalar-echo
  "A fixture echo function per scalar carrier, so a property can pick the
  one matching a generated type."
  {:i8 echo-i8 :i16 echo-i16 :i32 echo-i32 :i64 echo-i64
   :u8 echo-u8 :u16 echo-u16 :u32 echo-u32 :u64 echo-u64
   :isize echo-isize :usize echo-usize
   :f32 echo-f32 :f64 echo-f64 :bool echo-bool})

;; --- Slices, arrays, optionals ------------------------------------------

(defnz sum-f64 [xs [:slice :const :f64] :ret :f64]
  "var t: f64 = 0; for (xs) |v| t += v; return t;")

(defnz sum-i64 [xs [:slice :const :i64] :ret :i64]
  "var t: i64 = 0; for (xs) |v| t +%= v; return t;")

(defnz double-all! [xs [:slice :f64] :ret :void]
  "for (xs) |*v| v.* *= 2.0;")

(defnz array4-sum [xs [:array 4 :i32] :ret :i64]
  "var t: i64 = 0; for (xs) |v| t += v; return t;")

(defz answer "const answer: i64 = 42;")

(defnz maybe-answer [found :bool :ret [:optional [:ptr :const :i64]]]
  "return if (found) &answer else null;")

(defnz deref-or-default [p [:optional [:ptr :const :i64]] :ret :i64]
  "return if (p) |q| q.* else -1;")

;; --- Structs, records, enums --------------------------------------------

(deftypez Point [x :f64 y :f64])

(defnz echo-point [p Point :ret Point] "return p;")

(defrecordz Vec2 [x :f64 y :f64])

(defnz echo-vec2 [v Vec2 :ret Vec2] "return v;")

(defenumz Suit [clubs 0 diamonds 1 hearts 2 spades 3])

(defnz echo-suit [s Suit :ret Suit] "return s;")

(def suit-members [:clubs :diamonds :hearts :spades])

;; --- Owned and borrowed returns -----------------------------------------

(defnz owned-double [xs [:slice :const :f64] :ret [:owned [:slice :f64]]]
  "const out = std.heap.c_allocator.alloc(f64, xs.len) catch @panic(\"oom\");
   for (xs, 0..) |v, i| out[i] = v * 2.0;
   return out;")

(defnz borrowed-rest [s [:slice :const :u8] :ret [:borrowed [:slice :const :u8]]]
  "if (s.len == 0) return s;
   return s[1..];")

(defnz bytes-echo [xs [:slice :const :u8] :ret [:bytes [:slice :u8]]]
  "const out = std.heap.c_allocator.alloc(u8, xs.len) catch @panic(\"oom\");
   @memcpy(out, xs);
   return out;")

;; --- Handles ------------------------------------------------------------

(defz Box "const Box = struct { v: i64 };")

(defnz box [v :i64 :ret [:handle Box]]
  "const b = std.heap.c_allocator.create(Box) catch @panic(\"oom\");
   b.* = .{ .v = v };
   return b;")

(defnz unbox [b [:handle Box] :ret :i64] "return b.v;")

(defnz free-box [b [:handle Box] :ret :void] "std.heap.c_allocator.destroy(b);")

;; --- Allocation tracker (the leak lane) ---------------------------------

(defz Tracker "const Tracker = struct { live: i64 };")
(defz Node "const Node = struct { v: i64 };")

(defnz tracker-new [:ret [:handle Tracker]]
  "const t = std.heap.c_allocator.create(Tracker) catch @panic(\"oom\");
   t.* = .{ .live = 0 };
   return t;")

(defnz tracker-live [t [:handle Tracker] :ret :i64] "return t.live;")

(defnz tracker-free [t [:handle Tracker] :ret :void]
  "std.heap.c_allocator.destroy(t);")

(defnz node-new [t [:handle Tracker] v :i64 :ret [:handle Node]]
  "t.live += 1;
   const n = std.heap.c_allocator.create(Node) catch @panic(\"oom\");
   n.* = .{ .v = v };
   return n;")

(defnz node-get [n [:handle Node] :ret :i64] "return n.v;")

(defnz node-free [t [:handle Tracker] n [:handle Node] :ret :void]
  "t.live -= 1;
   std.heap.c_allocator.destroy(n);")
