(ns clj-zig.handle-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig :as zig]
            [clj-zig.core :refer [defnz defz]]))

(defz Counter "const Counter = struct { n: i64 };")

(defnz counter-new
  [start :i64
   :ret [:handle Counter]]
  "const c = std.heap.c_allocator.create(Counter) catch @panic(\"oom\");
   c.* = .{ .n = start };
   return c;")

(defnz counter-inc
  [c [:handle Counter]
   :ret :void]
  "c.n += 1;")

(defnz counter-get
  [c [:handle Counter]
   :ret :i64]
  "return c.n;")

(defnz counter-free
  [c [:handle Counter]
   :ret :void]
  "std.heap.c_allocator.destroy(c);")

(deftest a-handle-threads-a-live-resource-across-calls
  (let [c (counter-new 5)]
    (counter-inc c)
    (counter-inc c)
    (is (= 7 (counter-get c)))
    (counter-free c)))

(deftest a-handle-is-opaque-and-tagged-with-its-type
  (let [c (counter-new 0)]
    (testing "the tag names the native type"
      (is (= 'Counter (:type c))))
    (testing "the handle prints opaquely, not as its raw pointer"
      (is (= "#clj-zig/handle[Counter]" (pr-str c))))
    (counter-free c)))

(deftest a-wrong-handle-type-is-rejected
  (defz Other "const Other = struct { x: i64 };")
  (defnz other-new [:ret [:handle Other]]
    "const o = std.heap.c_allocator.create(Other) catch @panic(\"oom\");
     o.* = .{ .x = 0 };
     return o;")
  (let [o (other-new)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"handle"
          (counter-get o)))))

(deftest the-handle-pointer-is-in-the-generated-source
  (is (str/includes? (zig/generated-source #'counter-get) "c: *Counter")))

(deftest a-handle-over-a-non-named-type-is-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"handle"
        (zig/build-spec '{:ns app :name f
                          :signature [x :i64 :ret [:handle [:slice :u8]]]}))))
