(ns clj-zig.spec
  "Build the canonical boundary spec for a `defnz` function. Pure: an
  identity-plus-signature map goes in, a validated spec comes out, or a
  diagnostic is thrown.

  The spec is the native boundary contract that source generation, FFM
  binding, and cache hashing all consume:

      {:ns app.core
       :name add
       :symbol \"clj_zig_app_2e_core_add\"
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
            [clj-zig.layout :as layout]
            [clj-zig.signature :as signature]
            [clj-zig.type :as type]))

(declare expand-arg scalar-names find-non-scalar-element article
         element-description check-element! validate! fail)

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
  "The stable, collision-free C symbol for a Var: `clj_zig_<ns>_<name>`."
  [var-ns var-name]
  (str "clj_zig_" (munge-part (str var-ns)) "_" (munge-part (str var-name))))

(defn- resolve-named
  "Attach a named type's layout descriptor from `types`, or fail when the
  signature names a type that is not declared. A named type nested under
  an ownership wrapper (`:owned` or `:borrowed`) or an `:error-union`
  value is resolved too, so `[:owned RecordType]`, `[:owned EnumType]`,
  and `[:error-union E EnumType]` carry the layout the validator and the
  marshaller both read off `(:of ret)`. (`:handle` wraps an opaque
  `defz` resource not in the named-type registry, so it is left
  unresolved; `:optional` and `:bytes` wrap pointers and slices,
  never a named type.)"
  [ident types t]
  (cond
    (= :named (:kind t))
    (if-let [layout (get types (:name t))]
      (assoc t :layout layout)
      (fail ident :clj-zig/unknown-type-name
            (str "Signature names type " (:name t)
                 " which no deftypez/defrecordz/defenumz declares.")
            {:type-name (:name t)}))

    (:of t)
    ;; :handle wraps an opaque defz resource that is NOT in the named-type
    ;; registry, so it is left unresolved; every other :of-bearing wrapper
    ;; recurses so a named element (a slice of a struct, an owned record)
    ;; carries its layout.
    (if (= :handle (:kind t))
      t
      (update t :of (partial resolve-named ident types)))

    :else t))

(defn build-spec
  "Build the boundary spec from `{:ns :name :signature}`, resolving any
  named-type references against an optional `:types` map. Throws a
  diagnostic (`ex-info`) when the contract is invalid."
  [{:keys [ns name signature types] :or {types {}}}]
  (let [{:keys [args ret]} (signature/normalize signature)
        ident  {:ns ns :name name :signature signature}
        params (into [] (comp (map-indexed (partial expand-arg ident)) cat) args)
        params (mapv #(update % :type (partial resolve-named ident types)) params)
        spec   (assoc ident
                      :symbol (symbol-name ns name)
                      :params params
                      :ret    (resolve-named ident types (type/normalize ret)))]
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
                    (fail ident :clj-zig/unknown-field
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
  carriers are checked once resolution lands, not here. A `:string` is a
  buffer type, not a scalar, so it contributes no carrier name (same as a
  `:named` reference); the carrier check never fires on it."
  [t]
  (case (:kind t)
    :scalar #{(:name t)}
    :named  #{}
    :string #{}
    (if-let [of (:of t)] (scalar-names of) #{})))

(defn- find-non-scalar-element
  "Return the first indirection node (`:slice`, `:array`, `:ptr`, or
  `:manyptr`) in `t` whose immediate `:of` element the marshaller cannot
  carry, or nil when every element is carryable. A scalar element is
  always carryable; a named struct element is carryable when its layout
  is scalar-only (it crosses by value, like a nested struct field in ADR
  43). When `broad-elements?` is true (return position), a struct that
  carries buffer fields is also a carryable slice element: the wrapper
  transforms the body's nice-record slice into a wire slab and the free
  shim walks each element's buffers. A pointer or many-pointer must still
  hold a scalar; a slice or array of a buffer-carrying struct, an enum, a
  pointer, an optional, or a wrapper element is rejected. The walk
  descends through single-element wrappers so an indirection nested under
  ownership is caught."
  ([t] (find-non-scalar-element t false))
  ([t broad-elements?]
   (when (map? t)
     (let [k (:kind t)]
       (cond
         (contains? #{:slice :array} k)
         (let [elem (:of t)]
           (if (or (and (map? elem) (= :scalar (:kind elem))
                        (not (type/i128-type? (:name elem)))
                        (type/has-carrier? (:name elem)))
                   (and (map? elem) (= :named (:kind elem))
                        (get-in elem [:layout])
                        (not (get-in elem [:layout :enum]))
                        (if broad-elements?
                          (layout/slice-element-layout? (get-in elem [:layout]))
                          (layout/scalar-only-layout? (get-in elem [:layout])))))
             nil
             t))

         (contains? #{:ptr :manyptr} k)
         (let [elem (:of t)]
           (if (and (map? elem) (= :scalar (:kind elem))) nil t))

         (contains? #{:optional :owned :borrowed :bytes :handle :error-union} k)
         (find-non-scalar-element (:of t) broad-elements?)

         :else nil)))))

(defn- article
  "`A` or `An` for a kind keyword, by the first letter of its name. The
  existing diagnostics read `An :optional` and `A :bytes`; this keeps a
  generated message on the same idiom."
  [kw]
  (if (#{\a \e \i \o \u} (first (name kw)))
    "An"
    "A"))

(defn- optional-inner-ok?
  "True when `t` is a shape an `:optional` argument may wrap: a single or
  many-item pointer, or a carrier scalar (which lowers to a nullable
  pointer-to-const-scalar, `?*const T`, reusing the optional-pointer wire
  shape). A slice, array, named type, a carrierless scalar, or a 128-bit
  integer (whose 16-byte struct has no optional cell path) is rejected."
  [t]
  (or (contains? #{:ptr :manyptr} (:kind t))
      (and (= :scalar (:kind t))
           (type/has-carrier? (:name t))
           (not (type/i128-type? (:name t))))))

(defn- element-description
  "A short human label for a non-scalar element, for the diagnostic
  message. `elem` is the offending element of an indirection."
  [elem]
  (if (= :named (:kind elem))
    (str "the named type " (:name elem))
    (str "an element of kind " (name (:kind elem)))))

(defn- check-element!
  "Reject any indirection in `t` whose element is not a scalar. The
  offending indirection kind and element are attached to the diagnostic
  so the caller can point at the bad element. When `broad-elements?` is
  true (return position), a buffer-carrying struct is a carryable slice
  element (the wrapper transforms it into a wire slab)."
  ([spec t] (check-element! spec t false))
  ([spec t broad-elements?]
   (when-let [bad (find-non-scalar-element t broad-elements?)]
     (let [elem (:of bad)]
       (fail spec :clj-zig/unsupported-element
             (str (article (:kind bad)) " :" (name (:kind bad))
                  " must hold a scalar element; "
                  (element-description elem) " is not supported as an element.")
             {:indirection (:kind bad)
              :element     (select-keys elem [:kind :name])})))))

(defn- borrowed-buffer-slice?
  "True when `ret` is a `:borrowed` wrapper around a slice whose named
  element carries buffer fields. The wrapper would allocate a wire slab
  to transform the body's nice records, but a borrowed return emits no
  free shim, so the wire slab (and its per-element buffer addresses)
  would leak."
  [ret]
  (and (= :borrowed (:kind ret))
       (= :slice (get-in ret [:of :kind]))
       (= :named (get-in ret [:of :of :kind]))
       (let [layout (get-in ret [:of :of :layout])]
         (and layout
              (not (:enum layout))
              (not (layout/scalar-only-layout? layout))
              (layout/slice-element-layout? layout)))))

(defn- validate!
  "Reject contracts FFM cannot honor: `:void`/`:noreturn` in argument
  position, an `:optional` over anything but a pointer or a carrier scalar,
  an `:error-union` outside a scalar, `:void`, or named-type return, and
  any value-position scalar without an FFM carrier."
  [{:keys [params ret] :as spec}]
  (doseq [{:keys [type]} params]
    (when (type/void-type? type)
      (fail spec :clj-zig/void-argument
            (str (:name type) " is not a valid argument type.")
            {}))
    (when (and (= :optional (:kind type))
               (not (optional-inner-ok? (:of type))))
      (fail spec :clj-zig/unsupported-optional
            "An :optional argument must wrap a :ptr, :manyptr, or a carrier scalar." {}))
    (when (= :error-union (:kind type))
      (fail spec :clj-zig/unsupported-error-union
            "An :error-union is supported in return position only." {}))
    (when (contains? #{:owned :borrowed} (:kind type))
      (fail spec :clj-zig/unsupported-ownership
            "An :owned or :borrowed type is supported in return position only." {}))
    (when (= :bytes (:kind type))
      (fail spec :clj-zig/unsupported-bytes
            "A :bytes type is supported in return position only." {}))
    (when (and (= :handle (:kind type)) (not= :named (:kind (:of type))))
      (fail spec :clj-zig/unsupported-handle "A :handle must wrap a named type." {}))
    (check-element! spec type)
    (when (and (= :slice (:kind type))
               (not (:const? type))
               (= :named (:kind (:of type))))
      ;; A struct-element slice cannot propagate the body's in-place edits back
      ;; to the caller's immutable maps (a scalar slice can: the caller passes
      ;; a mutable primitive array). Require :const so the contract is honest.
      ;; Runs after check-element! so an invalid element (a buffer-carrying
      ;; struct) still reports :unsupported-element, not this.
      (fail spec :clj-zig/mutable-struct-slice
            (str "A struct-element slice argument must be :const; in-place "
                 "mutations the body makes cannot propagate back to Clojure's "
                 "immutable maps. Declare it [:slice :const " (:name (:of type)) "].")
            {:element (select-keys (:of type) [:kind :name])})))
  (when (and (= :optional (:kind ret))
             (not (or (= :ptr (:kind (:of ret)))
                      (and (= :scalar (:kind (:of ret)))
                           (type/has-carrier? (:name (:of ret)))))))
    (fail spec :clj-zig/unsupported-optional
          "An :optional return must wrap a single-item :ptr or a carrier scalar." {}))
  (when (and (= :error-union (:kind ret))
             (not (or (type/void-type? (:of ret))
                      (= :scalar (:kind (:of ret)))
                      (= :named (:kind (:of ret))))))
    (fail spec :clj-zig/unsupported-error-union
          "An :error-union return must wrap a scalar, :void, or a named type." {}))
  (when (and (contains? #{:owned :borrowed} (:kind ret))
             (not (or (= :slice (:kind (:of ret)))
                      (and (= :named (:kind (:of ret)))
                           (not (get-in (:of ret) [:layout :enum]))))))
    (fail spec :clj-zig/unsupported-ownership
          "An :owned or :borrowed return must wrap a slice or a named record." {}))
  (when (and (= :bytes (:kind ret))
             (not (and (= :slice (:kind (:of ret)))
                       (= :u8 (:name (:of (:of ret)))))))
    (fail spec :clj-zig/unsupported-bytes
          "A :bytes return must wrap a [:slice :u8]." {}))
  (when (and (= :handle (:kind ret)) (not= :named (:kind (:of ret))))
    (fail spec :clj-zig/unsupported-handle "A :handle must wrap a named type." {}))
  ;; Return position broadens the element gate: an owned slice of a buffer-
  ;; carrying struct is a valid return (the wrapper transforms the body's
  ;; nice-record slice into a wire slab and the free shim walks each
  ;; element's buffers). A borrowed slice of one is rejected because the
  ;; wrapper-allocated wire slab would leak with no free shim to release it.
  (check-element! spec ret true)
  (when (borrowed-buffer-slice? ret)
    (fail spec :clj-zig/unsupported-borrowed-buffer-slice
          (str "A :borrowed slice of a buffer-carrying struct is not supported;"
               " the wrapper's wire slab has no free shim. Use :owned so each"
               " element's buffers and the slab are freed.")
          {}))
  (let [ret-value     (if (= :error-union (:kind ret)) (:of ret) ret)
        ret-scalars   (if (type/void-type? ret-value) #{} (scalar-names ret-value))
        value-scalars (apply set/union ret-scalars (map (comp scalar-names :type) params))
        no-carrier    (remove type/has-carrier? value-scalars)]
    (when (seq no-carrier)
      (fail spec :clj-zig/unsupported-carrier
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
                           {:var (symbol (str (:ns spec)) (str (:name spec)))
                            :signature (:signature spec)})
                         extra))))

(comment
  (build-spec '{:ns app.core :name add :signature [x :i64 y :i64 :ret :i64]})
  ;; => {:ns app.core :name add :symbol "clj_zig_app_2e_core_add" :params [...] :ret {...} ...}

  (symbol-name 'app.core 'add)     ;; => "clj_zig_app_2e_core_add"

  ;; A 128-bit carrier is rejected at spec time.
  (try (build-spec '{:ns a :name f :signature [x :u128 :ret :i64]})
       (catch clojure.lang.ExceptionInfo e (:error/code (ex-data e)))))
