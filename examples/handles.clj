(ns handles
  "Small, self-contained handle examples. A `[:handle T]` is an opaque
  pointer to a native resource Zig owns across calls. Clojure holds the
  handle as a tagged token, threads it back to functions that take it, and
  frees the resource explicitly. The resource's Zig type is declared with
  `defz`. Start a REPL with `clojure -M:repl`, load this file, and
  evaluate the comment block."
  (:require [clj-zig.core :refer [defnz defz]]))

(defz Counter "const Counter = struct { n: i64 };")

(defnz counter
  "Allocates a counter starting at zero."
  [:ret [:handle Counter]]
  "const c = std.heap.c_allocator.create(Counter) catch @panic(\"oom\");
   c.* = .{ .n = 0 };
   return c;")

(defnz bump
  "Adds to the counter and returns the new total."
  [c [:handle Counter]
   by :i64
   :ret :i64]
  "c.n += by;
   return c.n;")

(defnz release
  "Frees the counter."
  [c [:handle Counter]
   :ret :void]
  "std.heap.c_allocator.destroy(c);")

(comment
  (def c (counter))
  (bump c 3)        ;; => 3
  (bump c 4)        ;; => 7
  (release c))
