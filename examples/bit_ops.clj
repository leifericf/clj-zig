(ns bit-ops
  "Sub-byte packing and hardware bit intrinsics, which the byte-oriented
  JVM makes cumbersome and slow. Clojure reaches bit work through boxed
  longs and method calls, and it has no native unsigned types. Here the
  body packs flags below the byte and calls Zig builtins that lower to a
  single machine instruction each: `@popCount`, `@clz`, and `@byteSwap`,
  over native unsigned integers and buffers. Start a REPL with
  `clojure -M:repl`, load this file, and evaluate the comment block."
  (:require [clj-zig.core :refer [defnz]]))

(defnz pack-flags
  "Packs up to 64 byte-flags into one unsigned word, most significant
  first: a nonzero `flags[i]` sets the bit at position `n - 1 - i`."
  [flags [:slice :const :u8]
   :ret :u64]
  "var acc: u64 = 0;
   const n = @min(flags.len, 64);
   var i: usize = 0;
   while (i < n) : (i += 1) {
       if (flags[i] != 0) {
           const shift: u6 = @intCast(n - 1 - i);
           acc |= @as(u64, 1) << shift;
       }
   }
   return acc;")

(defnz count-bits
  "Total set bits across a byte buffer, accumulating `@popCount` of each
  byte. The builtin is one instruction per byte on a modern CPU."
  [buf [:slice :const :u8]
   :ret :u64]
  "var total: u64 = 0;
   for (buf) |b| {
       total += @popCount(b);
   }
   return total;")

(defnz leading-zeros
  "Count of leading zero bits in a 64-bit word, via `@clz`."
  [x :u64
   :ret :u32]
  "return @clz(x);")

(defnz swap-bytes
  "Reverses the byte order of a 32-bit word, via `@byteSwap`: the native
  endian flip behind reading big-endian data on a little-endian machine."
  [x :u32
   :ret :u32]
  "return @byteSwap(x);")

(comment
  ;; [1 0 1 1] packs most significant first to 1011b = 11.
  (pack-flags (byte-array [1 0 1 1]))
  ;; => 11

  ;; 0xFF has 8 set bits, 0x01 has 1, 0x00 has none.
  (count-bits (byte-array [-1 1 0]))
  ;; => 9

  ;; The value 1 has 63 leading zeros in 64 bits.
  (leading-zeros 1)
  ;; => 63

  ;; 0x01020304 reversed is 0x04030201 = 67305985.
  (swap-bytes 0x01020304))
  ;; => 67305985
