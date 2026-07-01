(ns enums
  "Small, self-contained enum examples. A `defenumz` names a Zig enum; its
  members cross the boundary as Clojure keywords. The default backing is
  `i32`; the optional options map may narrow it (`:u8` here), matching the
  C enum widths real libraries use. Start a REPL with `clojure -M:repl`,
  load this file, and evaluate the comment block."
  (:require [clj-zig.core :refer [defnz defenumz]]))

(defenumz Suit
  "The four suits of a deck of cards."
  [clubs 0
   diamonds 1
   hearts 2
   spades 3])

(defnz red?
  "Whether a suit is one of the red suits."
  [s Suit
   :ret :bool]
  "return s == .diamonds or s == .hearts;")

(defnz next-suit
  "The next suit in clubs, diamonds, hearts, spades order, wrapping."
  [s Suit
   :ret Suit]
  "return @enumFromInt(@mod(@intFromEnum(s) + 1, 4));")

;; A u8-backed enum: the tag crosses at byte width, the way a C header
;; typically lays out a small set of status codes.
(defenumz StatusCode
  [ok 0
   busy 1
   done 2]
  {:backing :u8})

(defnz classify-job
  [n :u8
   :ret StatusCode]
  "return switch (n) { 0 => .ok, 1 => .busy, else => .done };")

(comment
  (red? :clubs)            ;; => false
  (red? :hearts)           ;; => true
  (next-suit :clubs)       ;; => :diamonds
  (next-suit :spades)      ;; => :clubs

  (classify-job 0)         ;; => :ok
  (classify-job 99))       ;; => :done
