(ns zigar.inspect
  "Read what a `defnz` Var records about its native function. The defining
  form leaves inspection data on the Var; these helpers read it back, so
  everything a developer asks about a function hangs off its Var:

      (zig/source #'add)             ;; the Zig body as written
      (zig/generated-source #'add)   ;; the full wrapper around it
      (zig/spec #'add)               ;; the normalized boundary spec
      (zig/explain #'add)            ;; the last failure, rendered

  Pure reads of metadata, save for `explain`, which renders a recorded
  diagnostic for a human."
  (:refer-clojure :exclude [symbol])
  (:require [zigar.diagnostics :as diagnostics]))

(defn- info [the-var]
  (:zigar/info (meta the-var)))

(defn source
  "The Zig body the function was defined with."
  [the-var]
  (:body (info the-var)))

(defn generated-source
  "The full Zig wrapper generated around the body."
  [the-var]
  (:generated-source (info the-var)))

(defn spec
  "The normalized boundary spec the function was built from."
  [the-var]
  (:spec (info the-var)))

(defn signature
  "The signature vector the function was defined with."
  [the-var]
  (:signature (spec the-var)))

(defn library
  "The path to the compiled shared library backing the function."
  [the-var]
  (:library (info the-var)))

(defn symbol
  "The stable C symbol the native function is exported under."
  [the-var]
  (:symbol (info the-var)))

(defn status
  "Whether the backing library was freshly built (`:compiled`) or reused
  from the cache (`:cached`)."
  [the-var]
  (:status (info the-var)))

(defn failed-attempt
  "The recorded data for the last failed (re)definition, or nil if the
  current binding is the last attempt."
  [the-var]
  (:zigar/failed-attempt (meta the-var)))

(defn explain
  "Render the last failed attempt for `the-var` as the multi-line string a
  developer reads, or nil when there is nothing to explain."
  [the-var]
  (when-let [attempt (failed-attempt the-var)]
    (diagnostics/render attempt)))

(comment
  (require '[zigar.core :refer [defnz]])
  (defnz add [x :i64 y :i64 :ret :i64] "return x + y;")
  (source #'add)            ;; => "return x + y;"
  (signature #'add)         ;; => [x :i64 y :i64 :ret :i64]
  (status #'add)            ;; => :compiled (or :cached on reuse)
  (print (generated-source #'add)))