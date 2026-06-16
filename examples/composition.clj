(ns composition
  "Small, self-contained composability examples. A signature is plain data,
  so ordinary Clojure builds it. A type builder is a function returning a
  type form, and a macro can generate whole `defnz` forms. Start a REPL
  with `clojure -M:repl`, load this file, and evaluate the comment block."
  (:require [zigar.core :refer [defnz]]))

;; Type builders are ordinary functions over data.
(defn slice-of [t] [:slice t])
(defn const-slice-of [t] [:slice :const t])
(def byte-slice (const-slice-of :u8))

(defnz count-needle
  "Counts how many times a byte appears in a read-only slice."
  [input byte-slice
   needle :u8
   :ret :usize]
  "var n: usize = 0;
   for (input) |b| {
       if (b == needle) n += 1;
   }
   return n;")

;; A macro generates a binary operator over one scalar type.
(defmacro defbinaryz [name op t]
  `(defnz ~name
     [x ~t y ~t :ret ~t]
     ~(str "return x " op " y;")))

(defbinaryz add-f64 "+" :f64)
(defbinaryz mul-i64 "*" :i64)

(comment
  (count-needle (byte-array (.getBytes "banana" "UTF-8")) (byte (int \a)))  ;; => 3
  (add-f64 2.0 3.0)   ;; => 5.0
  (mul-i64 3 4))       ;; => 12
