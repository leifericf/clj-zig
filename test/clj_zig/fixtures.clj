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

;; --- Strings (a :string crosses as UTF-8 in both directions) -----------

;; A :string argument reconstructs to []const u8; a :string return is an
;; owned []u8 the free shim releases after the Clojure side decodes it.

(defnz string-upper [s :string :ret :string]
  "const out = std.heap.c_allocator.alloc(u8, s.len) catch @panic(\"oom\");
   for (s, 0..) |c, i| out[i] = std.ascii.toUpper(c);
   return out;")

(defnz string-identity [s :string :ret :string]
  "const out = std.heap.c_allocator.alloc(u8, s.len) catch @panic(\"oom\");
   @memcpy(out, s);
   return out;")

;; --- Result records (doc 10 Phase 3): owned/borrowed record returns -----

;; A result record carries scalars, an enum, and owned buffer fields. The
;; nice record is a regular Zig struct the body constructs; the wrapper
;; decomposes it field by field into the wire extern struct and a per-field
;; __free shim releases every owned buffer after the Clojure side copies
;; the bytes out.

(defenumz RenderStatus [ok 0 invalid 1 no_output 2 oom 3])

(deftypez RenderResult
  [status RenderStatus
   width   :u32
   height  :u32
   media   :string
   bytes   [:bytes [:slice :u8]]])

;; An owned result the body builds from c_allocator buffers; the per-field
;; free shim releases each one once the bytes are copied out.
(defnz render-fixed
  [:ret [:owned RenderResult]]
  "const m = std.heap.c_allocator.alloc(u8, 9) catch @panic(\"oom\");
   @memcpy(m, \"image/png\");
   const b = std.heap.c_allocator.alloc(u8, 3) catch @panic(\"oom\");
   @memcpy(b, \"ABC\");
   return .{ .status = .ok, .width = 800, .height = 600, .media = m, .bytes = b };")

;; An owned result echoing the caller's media length and bytes, so the e2e
;; property test can round-trip arbitrary inputs through every field kind.
(defnz render-echo
  [media   :string
   payload [:slice :const :u8]
   :ret    [:owned RenderResult]]
  "const m = std.heap.c_allocator.alloc(u8, media.len) catch @panic(\"oom\");
   @memcpy(m, media);
   const b = std.heap.c_allocator.alloc(u8, payload.len) catch @panic(\"oom\");
   @memcpy(b, payload);
   return .{ .status = .no_output, .width = @intCast(media.len), .height = @intCast(payload.len), .media = m, .bytes = b };")

;; A borrowed result: the wrapper writes the fields but emits no free shim,
;; so the bytes come from memory the body must keep alive past the call.
;; Static storage lives for the program, so it is safe to borrow.
(defnz render-borrowed-static
  [:ret [:borrowed RenderResult]]
  "const S = struct { var media_buf: [5]u8 = .{ 'h', 'e', 'l', 'l', 'o' }; };
   return .{ .status = .ok, .width = 1, .height = 2, .media = S.media_buf[0..], .bytes = S.media_buf[0..0] };")

;; An empty owned result: every buffer field is zero-length, so the read
;; copies nothing and never dereferences the (zeroed) pointer. This is the
;; single-slice guard generalized to a record.
(defnz render-empty
  [:ret [:owned RenderResult]]
  "return .{ .status = .invalid, .width = 0, .height = 0, .media = &[_]u8{}, .bytes = &[_]u8{} };")

;; A scalar-only record returned as [:owned ...], proving the ownership
;; relaxation is uniform: any record, not only buffer-carrying ones.
(deftypez Pixel [r :u8 g :u8 b :u8])

(defnz render-pixel [:ret [:owned Pixel]] "return .{ .r = 10, .g = 20, .b = 30 };")

;; A defrecordz result: the return rebuilds via the map-> factory, so the
;; Clojure value is a record, not a plain map.
(defrecordz TaggedCount [tag :string n :i64])

(defnz render-tagged-count
  [label :string
   :ret  [:owned TaggedCount]]
  "const t = std.heap.c_allocator.alloc(u8, label.len) catch @panic(\"oom\");
    @memcpy(t, label);
    return .{ .tag = t, .n = @intCast(label.len) };")

;; --- Error-union over a struct (P3b): success returns the struct, failure
;; returns the error keyword. The combined wire shape carries the existing
;; error-union out-params (errbuf, errlen) PLUS the struct out-pointer
;; (__ret). On failure the wrapper writes the error name and returns WITHOUT
;; writing the struct, so nothing was allocated-for-the-result and the free
;; shim runs on the success path only (no leak on either branch).

;; A buffer-carrying record under an error union: the body allocates the
;; buffers BEFORE checking the fail flag would leak on the error path, so
;; the fail flag is checked FIRST and the body returns the error before any
;; allocation. The success path allocates and the wrapper's per-field free
;; shim releases every buffer once the bytes are copied out.
(defnz render-may-fail
  [fail :bool
   :ret  [:error-union anyerror RenderResult]]
  "if (fail) return error.RenderFailed;
   const m = std.heap.c_allocator.alloc(u8, 9) catch @panic(\"oom\");
   @memcpy(m, \"image/png\");
   const b = std.heap.c_allocator.alloc(u8, 3) catch @panic(\"oom\");
   @memcpy(b, \"ABC\");
   return .{ .status = .ok, .width = 800, .height = 600, .media = m, .bytes = b };")

;; A scalar-only record under an error union: no buffers to free, but the
;; wrapper still emits a no-op free shim (uniform with the buffer-carrying
;; case) and the FFM calls it on the success path.
(defnz pixel-may-fail
  [fail :bool
   :ret  [:error-union anyerror Pixel]]
  "if (fail) return error.PixelFailed;
   return .{ .r = 10, .g = 20, .b = 30 };")

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
