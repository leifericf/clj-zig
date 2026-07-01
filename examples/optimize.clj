(ns optimize
  "Per-function and per-namespace Zig optimize modes. The default is
  ReleaseSafe; a hot kernel earns ReleaseFast, a debug session earns
  Debug. Load a REPL with `clojure -M:repl` and evaluate the comment
  block."
  (:require [clj-zig.core :refer [defnz zig-deps]]))

;; ReleaseFast for every function in this namespace.
(zig-deps {:zig/optimize :ReleaseFast})

(defnz dot
  "A dot product compiled ReleaseFast because the namespace says so."
  [xs [:slice :const :f64]
   ys [:slice :const :f64]
   :ret :f64]
  "var t: f64 = 0;
   for (xs, ys) |x, y| t += x * y;
   return t;")

;; A per-function override needs a file-mode body, whose descriptor also
;; carries the mode:
;;
;;   (defnz noisy-kernel
;;     [xs [:slice :const :f64] :ret :f64]
;;     {:zig/file "optimize/noisy.zig" :zig/optimize :Debug})

(comment
  (dot (double-array [1.0 2.0 3.0])
       (double-array [4.0 5.0 6.0]))   ;; => 32.0
  )
