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
