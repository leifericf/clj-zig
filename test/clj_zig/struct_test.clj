(ns clj-zig.struct-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :refer [defnz deftypez]]))

(deftypez Point
  [x :f64
   y :f64])

(deftypez Pixel
  [r :u8
   g :u8
   b :u8
   a :u8])

;; --- Struct arguments ---------------------------------------------------

(defnz norm
  [p Point
   :ret :f64]
  "return @sqrt(p.x * p.x + p.y * p.y);")

(defnz alpha
  [px Pixel
   :ret :u8]
  "return px.a;")

(deftest a-struct-argument-is-a-clojure-map
  (testing "a two-field struct of doubles"
    (is (= 5.0 (norm {:x 3.0 :y 4.0}))))
  (testing "a padded struct of bytes reads the right field"
    (is (= 255 (alpha {:r 10 :g 20 :b 30 :a 255})))))

;; --- Struct returns -----------------------------------------------------

(defnz midpoint
  [a Point
   b Point
   :ret Point]
  "return .{ .x = (a.x + b.x) / 2.0, .y = (a.y + b.y) / 2.0 };")

(defnz brighten
  [px Pixel
   :ret Pixel]
  "return .{ .r = px.r +| 10, .g = px.g +| 10, .b = px.b +| 10, .a = px.a };")

(deftest a-struct-return-is-a-clojure-map
  (testing "a struct comes back as a map keyed by field name"
    (is (= {:x 2.0 :y 3.0} (midpoint {:x 0.0 :y 0.0} {:x 4.0 :y 6.0}))))
  (testing "a byte struct round-trips with saturating arithmetic"
    (is (= {:r 20 :g 30 :b 40 :a 250} (brighten {:r 10 :g 20 :b 30 :a 250})))
    (is (= {:r 255 :g 255 :b 255 :a 0} (brighten {:r 250 :g 250 :b 250 :a 0})))))

(deftest struct-values-are-copies
  (testing "a struct argument is passed by value, not mutated"
    (let [p {:x 1.0 :y 2.0}]
      (norm p)
      (is (= {:x 1.0 :y 2.0} p)))))

;; --- Nested struct fields ------------------------------------------------

(deftypez Rect
  [origin Point
   size   Point])

(defnz make-rect
  [x :f64 y :f64 w :f64 h :f64
   :ret Rect]
  "return .{ .origin = .{ .x = x, .y = y }, .size = .{ .x = w, .y = h } };")

(defnz rect-area
  [r Rect
   :ret :f64]
  "return r.size.x * r.size.y;")

(deftest a-nested-struct-return-is-a-nested-map
  (testing "a struct with struct fields comes back as a map of maps"
    (is (= {:origin {:x 1.0 :y 2.0} :size {:x 4.0 :y 6.0}}
           (make-rect 1.0 2.0 4.0 6.0)))))

(deftest a-nested-struct-argument-reads-its-inner-fields
  (testing "the body reads inner fields of a struct-valued argument"
    (is (= 24.0 (rect-area {:origin {:x 0.0 :y 0.0} :size {:x 4.0 :y 6.0}}))))
  (testing "a nested struct argument is copied, not aliased"
    (let [r {:origin {:x 0.0 :y 0.0} :size {:x 4.0 :y 6.0}}]
      (rect-area r)
      (is (= {:origin {:x 0.0 :y 0.0} :size {:x 4.0 :y 6.0}} r)))))

;; --- Slices and arrays of structs ---------------------------------------

(defnz sum-points
  "Sum the x and y of every point a const slice carries."
  [ps [:slice :const Point]
   :ret Point]
  "var sx: f64 = 0; var sy: f64 = 0;
   for (ps) |p| { sx += p.x; sy += p.y; }
   return .{ .x = sx, .y = sy };")

(defnz make-points
  "Allocate and return n points (0,0), (1,0), (2,0), ... as an owned slice."
  [n :usize
   :ret [:owned [:slice Point]]]
  "const out = std.heap.c_allocator.alloc(Point, n) catch @panic(\"oom\");
   for (out, 0..) |*p, i| p.* = .{ .x = @floatFromInt(i), .y = 0 };
   return out;")

(defnz array-sum
  "Sum a fixed-size array of three points."
  [ps [:array 3 Point]
   :ret Point]
  "var sx: f64 = 0; var sy: f64 = 0;
   for (ps) |p| { sx += p.x; sy += p.y; }
   return .{ .x = sx, .y = sy };")

(deftest a-slice-of-structs-argument-round-trips
  (testing "a const slice of maps is read element by element in the body"
    (is (= {:x 6.0 :y 9.0}
           (sum-points [{:x 1.0 :y 2.0} {:x 2.0 :y 3.0} {:x 3.0 :y 4.0}]))
        (= {:x 0.0 :y 0.0} (sum-points [])))))

(deftest an-owned-slice-of-structs-return-is-a-vector-of-maps
  (testing "the body's allocation is copied out and freed"
    (is (= [{:x 0.0 :y 0.0} {:x 1.0 :y 0.0} {:x 2.0 :y 0.0} {:x 3.0 :y 0.0}]
           (make-points 4))))
  (testing "an empty owned slice returns an empty vector"
    (is (= [] (make-points 0))))
  (testing "the slab is freed each call (the owned-slice free shim runs in volume)"
    ;; The free shim is the same one-element slab free the scalar owned-slice
    ;; lane covers; this drives it 400 times for the struct element so a
    ;; missing free surfaces as a growing footprint.
    (is (every? #(= {:x 9.0 :y 0.0} (last %))
                (repeatedly 400 #(make-points 10))))))

(deftest an-array-of-structs-argument-round-trips
  (is (= {:x 6.0 :y 9.0}
         (array-sum [{:x 1.0 :y 2.0} {:x 2.0 :y 3.0} {:x 3.0 :y 4.0}]))))

;; --- Owned slices of buffer-carrying structs --------------------------------
;; A buffer-carrying struct element needs the nice-to-wire transform: the body
;; builds nice records (with real slice fields), the wrapper copies each into a
;; wire (extern) slab, and a walking free shim frees every element's buffers
;; then the slab.

(deftypez Label
  [tag :string
   n   :i64])

(defnz make-labels
  "Allocate `count` labels, each with a three-byte c_allocator tag and an
  index, returned as an owned slice."
  [count :usize
   :ret  [:owned [:slice Label]]]
  "const out = std.heap.c_allocator.alloc(Label, count) catch @panic(\"oom\");
   for (out, 0..) |*b, i| {
       const s = std.heap.c_allocator.alloc(u8, 3) catch @panic(\"oom\");
       @memcpy(s, \"tag\");
       b.* = .{ .tag = s, .n = @intCast(i) };
   }
   return out;")

(defnz make-labels-with-payload
  "Allocate `count` labels, each tag echoing the caller's bytes and a
  per-element byte buffer, exercising a :string and a :bytes field together."
  [text  :string
   count :usize
   :ret  [:owned [:slice Label]]]
  "const out = std.heap.c_allocator.alloc(Label, count) catch @panic(\"oom\");
   for (out, 0..) |*b, i| {
       const s = std.heap.c_allocator.alloc(u8, text.len) catch @panic(\"oom\");
       @memcpy(s, text);
       b.* = .{ .tag = s, .n = @intCast(i) };
   }
   return out;")

(deftest an-owned-slice-of-buffer-carrying-structs-is-a-vector-of-maps
  (testing "each element's string field is copied out at the right stride"
    (is (= [{:tag "tag" :n 0} {:tag "tag" :n 1} {:tag "tag" :n 2}]
           (make-labels 3))))
  (testing "a caller-supplied string echoes through each element"
    (is (= [{:tag "abc" :n 0} {:tag "abc" :n 1}]
           (make-labels-with-payload "abc" 2))))
  (testing "an empty owned slice returns an empty vector"
    (is (= [] (make-labels 0))))
  (testing "the walking free shim frees every element's buffer in volume"
    (is (every? #(= {:tag "tag" :n 9} (last %))
                (repeatedly 300 #(make-labels 10))))))

;; --- Const slice arguments of buffer-carrying structs -------------------
;; A const slice of buffer-carrying structs arrives as a wire (extern) slab;
;; the wrapper allocates a nice-record slab, converts each wire element (ptr/len
;; words reinterpreted as real slices), runs the body, and frees the nice slab.

(defnz longest-tag
  "Return the length of the longest tag in a const slice of Labels."
  [ls [:slice :const Label]
   :ret :i64]
  "var best: i64 = 0;
   for (ls) |l| { if (l.tag.len > best) best = @intCast(l.tag.len); }
   return best;")

(deftest a-const-slice-of-buffer-structs-argument-round-trips
  (testing "the body reads each element's string field"
    (is (= 5 (longest-tag [{:tag "hello" :n 1} {:tag "hi" :n 2} {:tag "hey" :n 3}]))))
  (testing "an empty const slice is valid"
    (is (= 0 (longest-tag []))))
  (testing "a single element round-trips"
    (is (= 3 (longest-tag [{:tag "abc" :n 0}])))))

(defnz count-labels
  "Count the labels whose n field is positive."
  [ls [:slice :const Label]
   :ret :i64]
  "var c: i64 = 0;
   for (ls) |l| { if (l.n > 0) c += 1; }
   return c;")

(deftest a-const-slice-of-buffer-structs-reads-scalar-fields
  (is (= 2 (count-labels [{:tag "a" :n 0} {:tag "b" :n 1} {:tag "c" :n 2}])))
  (is (= 0 (count-labels [{:tag "x" :n 0}]))))

;; --- Optional over a named struct (nil-or-struct) -----------------------
;; [:optional Point] lowers to ?*const Point: nil is NULL, a present value is a
;; c_allocator pointer the FFM reads through and frees in a finally.

(defnz maybe-point
  "Return a Point only when the flag is set; otherwise null."
  [found :bool
   :ret  [:optional Point]]
  "return if (found) blk: {
    const p = std.heap.c_allocator.create(Point) catch @panic(\"oom\");
    p.* = .{ .x = 3.0, .y = 4.0 };
    break :blk p;
} else null;")

(deftest an-optional-struct-return-round-trips
  (testing "a present value returns a Point map"
    (is (= {:x 3.0 :y 4.0} (maybe-point true))))
  (testing "nil returns nil"
    (is (nil? (maybe-point false))))
  (testing "the struct is freed each call (volume leak lane)"
    (is (every? #(= {:x 3.0 :y 4.0} %)
                (repeatedly 200 #(maybe-point true))))))

(defnz use-maybe-point
  "Deref an optional Point argument: return its distance from origin or -1."
  [p [:optional Point]
   :ret :f64]
  "return if (p) |q| @sqrt(q.x * q.x + q.y * q.y) else -1.0;")

(deftest an-optional-struct-argument-round-trips
  (testing "a present value dereferences to the Point fields"
    (is (= 5.0 (use-maybe-point {:x 3.0 :y 4.0}))))
  (testing "nil dereferences to the default"
    (is (= -1.0 (use-maybe-point nil)))))

;; --- Nested buffer-carrying structs as slice elements ------------------
;; A slice element whose struct nests another buffer-carrying struct exercises
;; the recursive nice-to-wire transform and the recursive walking free shim.

(deftypez Inner [tag :string n :i64])
(deftypez Outer [label :string count :i64 detail Inner])

(defnz make-outers
  "Allocate count Outers, each with a label and a nested Inner."
  [count :usize
   :ret  [:owned [:slice Outer]]]
  "const out = std.heap.c_allocator.alloc(Outer, count) catch @panic(\"oom\");
   for (out, 0..) |*o, i| {
       const lab = std.heap.c_allocator.alloc(u8, 3) catch @panic(\"oom\");
       @memcpy(lab, \"abc\");
       const tg = std.heap.c_allocator.alloc(u8, 5) catch @panic(\"oom\");
       @memcpy(tg, \"inner\");
       o.* = .{ .label = lab, .count = @intCast(i), .detail = .{ .tag = tg, .n = @intCast(i * 2) } };
   }
   return out;")

(deftest an-owned-slice-of-nested-buffer-structs-round-trips
  (testing "each element's nested struct fields are copied out"
    (is (= [{:label "abc" :count 0 :detail {:tag "inner" :n 0}}
            {:label "abc" :count 1 :detail {:tag "inner" :n 2}}]
           (make-outers 2))))
  (testing "an empty owned slice returns an empty vector"
    (is (= [] (make-outers 0))))
   (testing "the recursive walking free shim frees every nested buffer in volume"
    (is (every? #(= {:tag "inner" :n 18} (:detail (last %)))
                (repeatedly 200 #(make-outers 10))))))

;; --- Per-field mixed ownership (Phase F) --------------------------------
;; A record with an owned buffer field beside a borrowed one: the free shim
;; frees the owned field and leaves the borrowed field alone.

(deftypez Mixed [owned_tag :string borrowed_tag [:borrowed [:slice :u8]]])

(defnz make-mixed
  "Allocate a Mixed: owned_tag is c_allocator bytes the shim frees;
  borrowed_tag points at static storage the shim must NOT free."
  [text :string
   :ret  [:owned Mixed]]
  "const s = std.heap.c_allocator.alloc(u8, text.len) catch @panic(\"oom\");
   @memcpy(s, text);
   const S = struct { var buf: [16]u8 = .{0} ** 16; };
   @memcpy(S.buf[0..text.len], text);
   return .{ .owned_tag = s, .borrowed_tag = S.buf[0..text.len] };")

(deftest a-mixed-ownership-record-frees-only-owned-fields
  (testing "both fields are read"
    (let [r (make-mixed "hello")]
      (is (= "hello" (:owned_tag r)))
      (is (= (vec (map byte "hello")) (seq (:borrowed_tag r))))))
  (testing "the owned field is freed each call (volume leak lane)"
    (is (every? #(= "hello" (:owned_tag %))
                (repeatedly 200 #(make-mixed "hello"))))))
