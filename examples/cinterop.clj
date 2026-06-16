(ns cinterop
  "Calling a C library from a Clojure function. The body lives in a sibling
  `.zig` file that `@cImport`s a C header (`math.h`) and calls into it; the
  `{:zig/file ...}` descriptor links the C library with `:zig/link`. The
  JVM can reach C too, but here the C header is imported directly into the
  body and the whole unit, import and function, is one real Zig file. Start
  a REPL with `clojure -M:repl`, load this file, and evaluate the comment
  block."
  (:require [clj-zig.core :refer [defnz]]))

(defnz hypotenuse
  "Euclidean distance, computed by C's `sqrt` from math.h."
  [a :f64
   b :f64
   :ret :f64]
  {:zig/file "cinterop/hyp.zig"
   :zig/link ["m"]})

(comment
  ;; The body calls c.sqrt; :zig/link ["m"] links libm so it resolves.
  (hypotenuse 3.0 4.0))
  ;; => 5.0
