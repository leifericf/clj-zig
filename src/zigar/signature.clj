(ns zigar.signature
  "Parse and normalize a `defnz` signature vector into boundary-contract
  data. Pure: a vector goes in, a normalized map comes out, or a
  structured diagnostic is thrown.

  A signature pairs argument bindings with boundary types and ends with
  a required final `:ret` marker and its return type (ADR 06):

      [x :i64
       y :i64
       :ret :i64]

  normalizes to:

      {:args [{:binding x :type :i64}
              {:binding y :type :i64}]
       :ret :i64}

  Type forms are preserved verbatim; `zigar.type` normalizes them. A map
  binding carries Clojure-side destructuring that lowers to native
  scalars before the call (ADR 13); its argument is marked
  `:destructured? true`.")

(declare normalize-arg fail)

(def ^:private rest-marker
  "Reserved for future Clojure-style rest arguments (ADR 06, docs/02)."
  '&)

(defn normalize
  "Normalize a `defnz` signature vector to `{:args [...] :ret <type>}`.
  Throws a diagnostic (`ex-info`) when the shape is invalid."
  [signature]
  (when-not (vector? signature)
    (fail signature :zigar/invalid-signature
          "A signature must be a vector." {:found (type signature)}))
  (when (some #{rest-marker} signature)
    (fail signature :zigar/reserved-rest-arg
          "& is reserved for future rest arguments and is not yet supported." {}))
  (let [n       (count signature)
        ret-ats (vec (keep-indexed (fn [i x] (when (= x :ret) i)) signature))]
    (cond
      (empty? ret-ats)
      (fail signature :zigar/missing-ret
            "A signature must end with :ret <return-type>." {})

      (> (count ret-ats) 1)
      (fail signature :zigar/extra-ret
            ":ret may appear once, as the final marker." {:ret-positions ret-ats})

      (= (first ret-ats) (dec n))
      (fail signature :zigar/empty-ret
            ":ret must be followed by a return type." {})

      (not= (first ret-ats) (- n 2))
      (fail signature :zigar/misplaced-ret
            "Nothing may appear after the return type." {})

      :else
      (let [args-region (subvec signature 0 (first ret-ats))]
        (when (odd? (count args-region))
          (fail signature :zigar/uneven-signature
                "Each argument needs a binding and a type." {}))
        {:args (mapv #(normalize-arg signature %) (partition 2 args-region))
         :ret  (peek signature)}))))

(defn- normalize-arg
  "Normalize one `[binding type]` pair. A map binding is Clojure-side
  destructuring (ADR 13); a symbol binding is an ordinary argument."
  [signature [binding type]]
  (cond
    (map? binding)    {:binding binding :type type :destructured? true}
    (symbol? binding) {:binding binding :type type}
    :else             (fail signature :zigar/invalid-binding
                            "An argument binding must be a symbol or a destructuring map."
                            {:binding binding})))

(defn- fail
  "Throw a structured diagnostic carrying the offending signature
  (docs/04). The shell renders it; callers can branch on `:error/code`."
  [signature code message extra]
  (throw (ex-info message
                  (merge {:level :error
                          :error/code code
                          :message message
                          :signature signature}
                         extra))))
