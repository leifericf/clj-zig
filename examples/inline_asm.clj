(ns inline-asm
  "Inline assembly inside a Clojure-defined function, which is simply
  impossible from Clojure or the JVM: there is no way to drop to raw
  machine instructions from bytecode. Each body lives in a sibling `.zig`
  file, so the assembly is real Zig with no string escaping and full
  editor and `zig fmt` support; the `{:zig/file ...}` descriptor points
  `defnz` at it. The bodies switch on the target architecture at compile
  time, so the same example builds on x86_64 and aarch64. Start a REPL
  with `clojure -M:repl`, load this file, and evaluate the comment block."
  (:require [clj-zig.core :refer [defnz]]))

(defnz add
  "Adds two signed integers using a single hardware add instruction
  written by hand, rather than the compiler's generated arithmetic."
  [a :i64
   b :i64
   :ret :i64]
  {:zig/file "inline_asm/add.zig"})

(defnz timestamp
  "Reads the CPU's cycle or virtual timer counter straight from a control
  register. There is no portable instruction and no JVM equivalent."
  [:ret :u64]
  {:zig/file "inline_asm/timestamp.zig"})

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
