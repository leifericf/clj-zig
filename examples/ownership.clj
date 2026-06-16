(ns ownership
  "Small, self-contained ownership examples. A returned Zig slice carries
  explicit ownership: `[:owned T]` is memory Zig allocates and Zigar frees
  after copying it out, while `[:borrowed T]` is a view Zig keeps. Both
  come back to Clojure as an immutable vector of the element values. Start
  a REPL with `clojure -M:repl`, load this file, and evaluate the comment
  block."
  (:require [zigar.core :refer [defnz]]))

(defnz shout
  "An upper-cased copy of the bytes, owned by Clojure."
  [s [:slice :const :u8]
   :ret [:owned [:slice :u8]]]
  "const out = std.heap.c_allocator.alloc(u8, s.len) catch @panic(\"oom\");
   for (s, 0..) |c, i| out[i] = std.ascii.toUpper(c);
   return out;")

(defnz tail
  "Everything after the first byte, a borrowed view copied out."
  [s [:slice :const :u8]
   :ret [:borrowed [:slice :const :u8]]]
  "return s[1..];")

(comment
  (shout (byte-array (.getBytes "hello" "UTF-8")))   ;; => [72 69 76 76 79]
  (tail (byte-array (.getBytes "hello" "UTF-8"))))    ;; => [101 108 108 111]
