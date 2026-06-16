(ns inline-asm
  "Inline assembly inside a Clojure-defined function, which is simply
  impossible from Clojure or the JVM: there is no way to drop to raw
  machine instructions from bytecode. Here a `defnz` body contains an
  `asm volatile` block and emits the instructions directly. The bodies
  switch on the target architecture at compile time, so the same example
  builds on x86_64 and aarch64. Start a REPL with `clojure -M:repl`, load
  this file, and evaluate the comment block."
  (:require [clj-zig.core :refer [defnz]]))

(defnz add
  "Adds two signed integers using a single hardware add instruction
  written by hand, rather than the compiler's generated arithmetic."
  [a :i64
   b :i64
   :ret :i64]
  "return switch (@import(\"builtin\").cpu.arch) {
       .aarch64 => asm (\"add %[ret], %[a], %[b]\"
           : [ret] \"=r\" (-> i64),
           : [a] \"r\" (a),
             [b] \"r\" (b),
       ),
       .x86_64 => asm (\"addq %[b], %[ret]\"
           : [ret] \"=r\" (-> i64),
           : [a] \"0\" (a),
             [b] \"r\" (b),
       ),
       else => @compileError(\"add: unsupported architecture\"),
   };")

(defnz timestamp
  "Reads the CPU's cycle or virtual timer counter straight from a control
  register: `rdtsc` on x86_64, `cntvct_el0` on aarch64. There is no
  portable instruction and no JVM equivalent; this reaches the register
  the only way it can be reached."
  [:ret :u64]
  "return switch (@import(\"builtin\").cpu.arch) {
       .aarch64 => asm volatile (\"mrs %[ret], cntvct_el0\"
           : [ret] \"=r\" (-> u64),
       ),
       .x86_64 => blk: {
           var low: u32 = undefined;
           var high: u32 = undefined;
           asm volatile (\"rdtsc\"
               : [low] \"={eax}\" (low),
                 [high] \"={edx}\" (high),
           );
           break :blk (@as(u64, high) << 32) | @as(u64, low);
       },
       else => @compileError(\"timestamp: unsupported architecture\"),
   };")

(comment
  ;; The hand-written add instruction returns the same answer the compiler
  ;; would, computed by the instruction the body names.
  (add 20 22)
  ;; => 42

  ;; The counter is a large unsigned value, so it crosses back as a
  ;; BigInteger, and it only moves forward between two reads.
  (timestamp)
  (let [a (timestamp)
        b (timestamp)]
    (< a b)))
  ;; => true
