(ns clj-zig.signature
  "Parse and normalize a `defnz` signature vector into boundary-contract
  data. Pure: a vector goes in, a normalized map comes out, or a
  structured diagnostic is thrown.

  A signature pairs argument bindings with boundary types and ends with
  a required final `:ret` marker and its return type:

      [x :i64
       y :i64
       :ret :i64]

  normalizes to:

      {:args [{:binding x :type :i64}
              {:binding y :type :i64}]
       :ret :i64}

  Type forms are preserved verbatim; `clj-zig.type` normalizes them. A map
  binding carries Clojure-side destructuring that lowers to native
  scalars before the call; its argument is marked
  `:destructured? true`. A trailing `& binding type` is a rest argument,
  sugar for a const slice of the named carrier scalar; its argument is
  marked `:rest? true`."
  (:require [clj-zig.type :as type]))

(declare normalize-arg parse-args-region fail)

(def ^:private rest-marker
  "The Clojure-style rest-argument marker. A trailing `& binding type`
  lowers to a const slice of the named carrier scalar."
  '&)

(defn normalize
  "Normalize a `defnz` signature vector to `{:args [...] :ret <type>}`.
  Throws a diagnostic (`ex-info`) when the shape is invalid."
  [signature]
  (when-not (vector? signature)
    (fail signature :clj-zig/invalid-signature
          "A signature must be a vector." {:found (type signature)}))
  (let [n       (count signature)
        ret-ats (vec (keep-indexed (fn [i x] (when (= x :ret) i)) signature))]
    (cond
      (empty? ret-ats)
      (fail signature :clj-zig/missing-ret
            "A signature must end with :ret <return-type>." {})

      (> (count ret-ats) 1)
      (fail signature :clj-zig/extra-ret
            ":ret may appear once, as the final marker." {:ret-positions ret-ats})

      (= (first ret-ats) (dec n))
      (fail signature :clj-zig/empty-ret
            ":ret must be followed by a return type." {})

      (not= (first ret-ats) (- n 2))
      (fail signature :clj-zig/misplaced-ret
            "Nothing may appear after the return type." {})

      :else
      (let [args-region (subvec signature 0 (first ret-ats))]
        {:args (:args (parse-args-region signature args-region))
         :ret  (peek signature)}))))

(defn- parse-args-region
  "Walk the args region (everything before `:ret`) into a vector of
  normalized args. Leading binding/type pairs are consumed in order; a
  trailing `& binding type` becomes one rest-flagged arg lowered to a
  const slice of the named carrier scalar. Throws a diagnostic for a
  misplaced `&`, an incomplete pair, a non-symbol rest binding, or a rest
  element that is not a carrier scalar."
  [signature region]
  (loop [i 0 args []]
    (cond
      (>= i (count region))
      {:args args}

      (= (nth region i) rest-marker)
      (let [rest-start (inc i)]
        (when-not (= rest-start (- (count region) 2))
          (fail signature :clj-zig/misplaced-rest
                "& must introduce the final rest argument: & binding type." {}))
        (let [binding (nth region rest-start)
              elem    (nth region (inc rest-start))]
          (when-not (symbol? binding)
            (fail signature :clj-zig/invalid-binding
                  "A rest argument's binding must be a symbol." {:binding binding}))
          (when-not (type/has-carrier? elem)
            (fail signature :clj-zig/unsupported-rest-element
                  (str "A rest argument's element must be a carrier scalar; "
                       "& cannot carry " (pr-str elem) ".")
                  {:element elem}))
          {:args (conj args (-> (normalize-arg signature [binding [:slice :const elem]])
                                (assoc :rest? true)))}))

      :else
      (if (>= (inc i) (count region))
        (fail signature :clj-zig/uneven-signature
              "Each argument needs a binding and a type." {})
        (recur (+ i 2)
               (conj args (normalize-arg signature
                                         [(nth region i) (nth region (inc i))])))))))

(defn- normalize-arg
  "Normalize one `[binding type]` pair. A map binding is Clojure-side
  destructuring; a symbol binding is an ordinary argument."
  [signature [binding type]]
  (cond
    (map? binding)    {:binding binding :type type :destructured? true}
    (symbol? binding) {:binding binding :type type}
    :else             (fail signature :clj-zig/invalid-binding
                            "An argument binding must be a symbol or a destructuring map."
                            {:binding binding})))

(defn- fail
  "Throw a structured diagnostic carrying the offending signature
  The shell renders it; callers can branch on `:error/code`."
  [signature code message extra]
  (throw (ex-info message
                  (merge {:level :error
                          :error/code code
                          :message message
                          :signature signature}
                         extra))))

(comment
  ;; Normalize a signature into boundary-contract data.
  (normalize '[x :i64 y :i64 :ret :i64])
  ;; => {:args [{:binding x :type :i64} {:binding y :type :i64}] :ret :i64}

  ;; A map binding is captured for Clojure-side destructuring.
  (normalize '[{x :x y :y} {:x :f64 :y :f64} :ret :f64])

  ;; An invalid shape throws a data diagnostic.
  (try (normalize '[x :i64]) (catch clojure.lang.ExceptionInfo e (ex-data e))))
