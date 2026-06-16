(ns zigar.spec
  "Build the canonical boundary spec for a `defnz` function. Pure: an
  identity-plus-signature map goes in, a validated spec comes out, or a
  diagnostic is thrown.

  The spec is the native boundary contract that source generation, FFM
  binding, and cache hashing all consume:

      {:ns app.core
       :name add
       :symbol \"zigar_app_2e_core_add\"
       :params [{:binding x :type {:kind :scalar :name :i64}}
                {:binding y :type {:kind :scalar :name :i64}}]
       :ret {:kind :scalar :name :i64}
       :signature [x :i64 y :i64 :ret :i64]}

  `:params` is the flat list of native parameters in call order.
  Clojure-side destructuring is expanded here: each destructured
  local becomes one native param tagged with `:destructured-from`, so the
  core macro can lower a map argument to scalars before the call."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [zigar.signature :as signature]
            [zigar.type :as type]))

(declare expand-arg scalar-names validate! fail)

(defn- munge-part
  "Escape a namespace or name to an ASCII C-identifier fragment. Letters
  and digits pass through; every other character becomes `_<hex>_`, so
  distinct inputs never collapse to the same fragment."
  [s]
  (->> s
       (map (fn [c]
              (if (and (< (int c) 128) (Character/isLetterOrDigit c))
                (str c)
                (format "_%x_" (int c)))))
       (apply str)))

(defn symbol-name
  "The stable, collision-free C symbol for a Var: `zigar_<ns>_<name>`."
  [var-ns var-name]
  (str "zigar_" (munge-part (str var-ns)) "_" (munge-part (str var-name))))

(defn build-spec
  "Build the boundary spec from `{:ns :name :signature}`. Throws a
  diagnostic (`ex-info`) when the contract is invalid."
  [{:keys [ns name signature]}]
  (let [{:keys [args ret]} (signature/normalize signature)
        ident  {:ns ns :name name :signature signature}
        params (into [] (comp (map-indexed (partial expand-arg ident)) cat) args)
        spec   (assoc ident
                      :symbol (symbol-name ns name)
                      :params params
                      :ret    (type/normalize ret))]
    (validate! spec)
    spec))

(defn- expand-arg
  "Expand one normalized signature argument into native params. A plain
  argument yields one param; a destructured map yields one param per
  local, typed from the field-map. `ident` carries the Var and
  signature so a field error is as rich as any other diagnostic."
  [ident idx {:keys [binding destructured?] t :type}]
  (if destructured?
    (vec (map (fn [[local field]]
                (let [field-type (get t field ::missing)]
                  (when (= field-type ::missing)
                    (fail ident :zigar/unknown-field
                          (str "Destructuring binding refers to field " field
                               " which the field-map does not declare.")
                          {:field field}))
                  {:binding local
                   :type (type/normalize field-type)
                   :destructured-from {:arg idx :field field}}))
              binding))
    [{:binding binding :type (type/normalize t)}]))

(defn- scalar-names
  "The set of scalar names appearing anywhere in a normalized type.
  Named-type references resolve against the registry later, so their
  carriers are checked once resolution lands, not here."
  [t]
  (case (:kind t)
    :scalar #{(:name t)}
    :named  #{}
    (if-let [of (:of t)] (scalar-names of) #{})))

(defn- validate!
  "Reject contracts FFM cannot honor: `:void`/`:noreturn` in argument
  position, and any value-position scalar without an FFM carrier."
  [{:keys [params ret] :as spec}]
  (doseq [{:keys [type]} params]
    (when (type/void-type? type)
      (fail spec :zigar/void-argument
            (str (:name type) " is not a valid argument type.")
            {})))
  (let [ret-scalars (if (type/void-type? ret) #{} (scalar-names ret))
        value-scalars (apply set/union ret-scalars (map (comp scalar-names :type) params))
        no-carrier (remove type/has-carrier? value-scalars)]
    (when (seq no-carrier)
      (fail spec :zigar/unsupported-carrier
            (str "Types " (str/join ", " (sort no-carrier))
                 " have no FFM carrier and cannot cross the boundary.")
            {}))))

(defn- fail
  "Throw a structured diagnostic. `spec` may be nil before it is built."
  [spec code message extra]
  (throw (ex-info message
                  (merge {:level :error
                          :error/code code
                          :message message}
                         (when spec
                           {:var (clojure.core/symbol (str (:ns spec)) (str (:name spec)))
                            :signature (:signature spec)})
                         extra))))
