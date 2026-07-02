(ns clj-zig.stream-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :refer [defnz deftypez defz]]))

(deftypez Counter
  [state :i64]
  {:clj-zig/iter {:next "counter_next" :deinit "counter_deinit"}})

(defz counter-impl
  "pub fn counter_next(self: *Counter) ?i64 {
       if (self.state >= 100) return null;
       const val = self.state;
       self.state += 1;
       return val;
   }
   pub fn counter_deinit(self: *Counter) void {
       @import(\"std\").heap.c_allocator.destroy(self);
   }")

(defnz counter-stream
  [:ret [:stream :i64 of Counter]]
  "const c = std.heap.c_allocator.create(Counter) catch @panic(\"oom\");
   c.state = 0;
   return c;")

(deftest streaming-returns-a-reducible
  (testing "into collects all elements"
    (is (= (vec (range 100)) (into [] (counter-stream)))))
  (testing "transduce works"
    (is (= (reduce + (range 100)) (transduce (map identity) + 0 (counter-stream)))))
  (testing "early exit with reduced frees the iterator"
    (is (= [0 1] (into [] (comp (take 2)) (counter-stream)))))
  (testing "count works"
    (is (= 100 (count (into [] (counter-stream)))))))
