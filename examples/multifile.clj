(ns multifile
  "Splitting a Zig body across files. The body in `multifile/stats.zig`
  `@import`s a sibling `moments.zig` and calls into it; clj-zig reproduces
  the imported file beside the generated source and compiles them together.
  Relative and subdirectory imports resolve from the body file, exactly as
  Zig resolves them. Start a REPL with `clojure -M:repl`, load this file,
  and evaluate the comment block."
  (:require [clj-zig.core :refer [defnz]]))

(defnz variance
  "Population variance of a sample, computed in Zig across two files."
  [xs [:slice :const :f64]
   :ret :f64]
  {:zig/file "multifile/stats.zig"})

(comment
  ;; stats.zig imports moments.zig for the mean; both compile together.
  (variance (double-array [2.0 4.0 4.0 4.0 5.0 5.0 7.0 9.0])))
  ;; => 4.0
