(ns enums
  "Small, self-contained enum examples. A `defenumz` names an `i32`-backed
  Zig enum; its members cross the boundary as Clojure keywords. A `defnz`
  may take the enum as an argument or return it: an argument is a member
  keyword and a return comes back as one. Start a REPL with
  `clojure -M:repl`, load this file, and evaluate the comment block."
  (:require [zigar.core :refer [defnz defenumz]]))

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

(comment
  (red? :clubs)        ;; => false
  (red? :hearts)       ;; => true
  (next-suit :clubs)   ;; => :diamonds
  (next-suit :spades)) ;; => :clubs
