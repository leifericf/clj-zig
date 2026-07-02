(ns clj-zig.spec-check
  "Generate and register `clojure.spec.alpha` predicates for `defnz`
  boundary types and functions. Pure: a normalized type goes in, a spec
  form comes out. The `register!` effect writes the specs to the
  registry.

  Scalars map to `int?`, `double?`, or `boolean?`; enums map to the
  member keyword set; named structs map to `map?`; slices map to
  `coll-of` predicates; strings to `string?`; bytes to `bytes?`;
  optional wraps in `nilable`; handle to `some?`; void to `nil?`."
  (:require [clojure.spec.alpha :as s]
            [clj-zig.type :as type]))

(defn spec-for-type
  "A clojure.spec predicate form for a normalized boundary type.
  Scalars map to their JVM predicate; enums map to the member keyword
  set; named structs map to `map?`; slices and collections map to
  `coll-of`; strings to `string?`; bytes to `bytes?`; optional wraps in
  `nilable`; handle to `some?`; void to `nil?`."
  [t]
  (cond
    (= :scalar (:kind t))
    (case (:category (type/scalar-info (:name t)))
      :int   'int?
      :float 'double?
      :bool  'boolean?
      :void  'nil?
      'some?)

    (= :string (:kind t))
    'string?

    (= :slice (:kind t))
    (list 'clojure.spec.alpha/coll-of (spec-for-type (:of t)))

    (= :array (:kind t))
    (list 'clojure.spec.alpha/coll-of (spec-for-type (:of t)) :count (:length t))

    (= :optional (:kind t))
    (list 'clojure.spec.alpha/nilable (spec-for-type (:of t)))

    (= :handle (:kind t))
    'some?

    (= :bytes (:kind t))
    'bytes?

    (= :named (:kind t))
    (if (get-in t [:layout :enum])
      (set (for [v (get-in t [:layout :values])]
             (keyword (str (:name v)))))
      'map?)

    (and (= :owned (:kind t)) (= :slice (get-in t [:of :kind])))
    (list 'clojure.spec.alpha/coll-of (spec-for-type (get-in t [:of :of])))

    (and (= :borrowed (:kind t)) (= :slice (get-in t [:of :kind])))
    (list 'clojure.spec.alpha/coll-of (spec-for-type (get-in t [:of :of])))

    (and (contains? #{:owned :borrowed} (:kind t)) (= :named (get-in t [:of :kind])))
    'map?

    (= :error-union (:kind t))
    (spec-for-type (:of t))

    :else 'some?))

(defn spec-for-param
  "The argument-side spec for a boundary param. A slice argument is
  driven by the caller, who passes a Java primitive array; so the spec
  is permissive (`array?` or `coll?`). A scalar is exact."
  [param]
  (let [t (:type param)]
    (cond
      (= :slice (:kind t))     '(clojure.spec.alpha/or :array array? :coll coll?)
      (= :string (:kind t))    '(clojure.spec.alpha/or :str string? :bytes bytes?)
      (= :named (:kind t))     (if (get-in t [:layout :enum])
                                 (spec-for-type t)
                                 'map?)
      (= :array (:kind t))     'array?
      :else                     (spec-for-type t))))

(defn register!
  "Register `clojure.spec.alpha` predicates for the `defnz` Var's
  arguments and return. Reads the boundary spec from the Var's
  `:clj-zig/info` metadata. Idempotent: re-registering replaces the
  specs."
  [the-var]
  (let [info     (:clj-zig/info (meta the-var))
        spec     (:spec info)
        params   (:params spec)
        ret      (:ret spec)
        ns-str   (str (:ns spec))
        name-str (str (:name spec))
        var-sym  (symbol ns-str name-str)
        arg-spec (if (seq params)
                   (cons 'clojure.spec.alpha/cat
                         (mapcat (fn [p]
                                   [(keyword (str (:binding p))) (spec-for-param p)])
                                 params))
                   '(clojure.spec.alpha/cat))
        ret-spec (spec-for-type ret)
        arg-key  (keyword ns-str (str name-str "-args"))
        ret-key  (keyword ns-str (str name-str "-ret"))]
    (eval `(s/def ~arg-key ~arg-spec))
    (eval `(s/def ~ret-key ~ret-spec))
    (eval `(s/fdef ~var-sym :args ~arg-key :ret ~ret-key))
    the-var))
