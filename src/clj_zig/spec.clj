(ns clj-zig.spec
  "Build the canonical boundary spec for a `defnz` function, and project
  it into `clojure.spec.alpha` predicates. The build is pure: an
  identity-plus-signature map goes in, a validated spec comes out, or a
  diagnostic is thrown. `spec-for-type` and `spec-for-param` derive the
  predicate forms; `register!` writes them to the registry.

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
            [clojure.spec.alpha :as s]
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
               (if (and (< (int c) 128) (Character/isLetterOrDigit (int c)))
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

    (= :stream (:kind t))
    (-> t
        (update :of (partial resolve-named ident types))
        (assoc :iter-layout (get types (:iter-type t))))

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
    :stream (scalar-names (:of t))
    (if-let [of (:of t)] (scalar-names of) #{})))

(defn- slice-carryable?
  "True when a slice/array element crosses the C ABI as a carrier scalar,
  a named enum, or a slice-element named struct."
  [elem]
  (and (map? elem)
       (or (type/carrier-scalar? elem)
           (and (= :named (:kind elem))
                (get-in elem [:layout])
                (or (get-in elem [:layout :enum])
                    (layout/slice-element-layout? (get-in elem [:layout])))))))

(defn- find-non-scalar-element
  "Return the first indirection node (`:slice`, `:array`, `:ptr`, or
  `:manyptr`) in `t` whose immediate `:of` element the marshaller cannot
  carry, or nil when every element is carryable. A scalar element is
  always carryable; a named enum element is carryable (it crosses as its
  backing integer); a named struct element is carryable when its layout
  is `slice-element-layout?` (scalar-only or buffer-carrying; the wrapper
  transforms the body's nice records into a wire slab in both argument
  and return positions). A pointer or many-pointer must still hold a
  scalar; a slice or array of any other element is rejected. The walk
  descends through single-element wrappers so an indirection nested
  under ownership is caught."
  [t]
  (when (map? t)
    (let [k (:kind t)]
      (cond
        (contains? #{:slice :array} k)
        (let [elem (:of t)]
          (if (slice-carryable? elem) nil t))

        (contains? #{:ptr :manyptr} k)
        (let [elem (:of t)]
          (if (and (map? elem) (= :scalar (:kind elem))) nil t))

        (contains? #{:optional :owned :borrowed :bytes :handle :error-union} k)
        (find-non-scalar-element (:of t))

        :else nil))))

(defn- article
  "`A` or `An` for a kind keyword, by the first letter of its name. The
  existing diagnostics read `An :optional` and `A :bytes`; this keeps a
  generated message on the same idiom."
  [kw]
  (if (#{\a \e \i \o \u} (first (name kw)))
    "An"
    "A"))

(defn- optional-inner-ok?
  "True when `t` is a shape an `:optional` may wrap: one of `ptr-kinds`
  (`#{:ptr :manyptr}` for arguments, `#{:ptr}` for returns), a carrier
  scalar (non-128-bit), or a named non-enum struct. A slice, array, an
  enum, a carrierless scalar, or a 128-bit integer is rejected."
  [ptr-kinds t]
  (or (contains? ptr-kinds (:kind t))
      (type/carrier-scalar? t)
      (and (= :named (:kind t))
           (get-in t [:layout])
           (not (get-in t [:layout :enum])))))

(defn- element-description
  "A short human label for a non-scalar element, for the diagnostic
  message. `elem` is the offending element of an indirection."
  [elem]
  (if (= :named (:kind elem))
    (str "the named type " (:name elem))
    (str "an element of kind " (name (:kind elem)))))

(defn- check-element!
  "Reject any indirection in `t` whose element is not carryable. The
  offending indirection kind and element are attached to the diagnostic
  so the caller can point at the bad element."
  [spec t]
  (when-let [bad (find-non-scalar-element t)]
    (let [elem (:of bad)]
      (fail spec :clj-zig/unsupported-element
            (str (article (:kind bad)) " :" (name (:kind bad))
                 " must hold a scalar element; "
                 (element-description elem) " is not supported as an element.")
            {:indirection (:kind bad)
             :element     (select-keys elem [:kind :name])}))))

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

(defn- validate-args!
  "Reject contracts FFM cannot honor in argument position: `:void`/`:noreturn`
  arguments, an `:optional` over anything but a pointer, a carrier scalar,
  or a named non-enum struct,
  `:error-union`/`:stream`/`:owned`/`:borrowed`/`:bytes` outside return
  position, a `:handle` not wrapping a named type, and any indirection whose
  element is not carryable. A struct-element slice must be `:const` so the
  contract is honest about not propagating in-place edits."
  [{:keys [params] :as spec}]
  (doseq [{:keys [binding type]} params]
    (when (str/starts-with? (name binding) "__")
      (fail spec :clj-zig/reserved-binding
            (str "Parameter " binding " uses a reserved __ prefix. "
                 "clj-zig generates __-prefixed names internally; "
                 "rename it without the leading __.")
            {:binding binding}))
    (when (type/void-type? type)
      (fail spec :clj-zig/void-argument
            (str (:name type) " is not a valid argument type.")
            {}))
    (when (and (= :optional (:kind type))
               (not (optional-inner-ok? #{:ptr :manyptr} (:of type))))
      (fail spec :clj-zig/unsupported-optional
            "An :optional argument must wrap a :ptr, :manyptr, a carrier scalar, or a named non-enum struct." {}))
    (when (and (= :optional (:kind type))
               (= :named (:kind (:of type)))
               (some :target (get-in (:of type) [:layout :fields])))
      ;; ADR 45 scopes :optional arguments to pointers and scalars. A buffer-
      ;; carrying named struct under :optional has no wire-to-nice
      ;; reconstruction in the generator, so reject it here rather than emit
      ;; code that cannot marshal it.
      (fail spec :clj-zig/unsupported-buffer-optional
            (str "An :optional argument cannot wrap a buffer-carrying struct;"
                 " the wrapper has no reconstruction for an optional buffer"
                 " field. Use a plain struct argument, or a scalar/:pointer"
                 " optional.")
            {:element (select-keys (:of type) [:kind :name])}))
    (when (= :error-union (:kind type))
      (fail spec :clj-zig/unsupported-error-union
            "An :error-union is supported in return position only." {}))
    (when (= :stream (:kind type))
      (fail spec :clj-zig/unsupported-stream-argument
            "A :stream type is supported in return position only." {}))
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
      (fail spec :clj-zig/mutable-struct-slice
            (str "A struct-element slice argument must be :const; in-place "
                 "mutations the body makes cannot propagate back to Clojure's "
                 "immutable maps. Declare it [:slice :const " (:name (:of type)) "].")
            {:element (select-keys (:of type) [:kind :name])}))))

(defn- validate-stream-return!
  "Validate a :stream return's element and iterator shape."
  [spec ret]
  (let [elem (:of ret)]
    (when-not (type/carrier-scalar? elem)
      (fail spec :clj-zig/unsupported-stream
            (str "A :stream return must hold a carrier scalar element (the "
                 "read path is scalar-only, no 128-bit); got "
                 (pr-str (:kind elem)) ".")
            {:element (select-keys elem [:kind :name])}))
    (let [iter (:iter-layout ret)]
      (when-not (and iter
                     (get-in iter [:clj-zig/iter :next])
                     (get-in iter [:clj-zig/iter :deinit]))
        (fail spec :clj-zig/unsupported-stream
              (str "A :stream's iterator type " (:iter-type ret)
                   " must be a deftypez carrying :clj-zig/iter {:next :deinit}.")
              {:iter-type (:iter-type ret)})))))

(defn- validate-return!
  "Reject contracts FFM cannot honor in return position: an `:optional`,
  `:error-union`, `:owned`/`:borrowed`, `:bytes`, or `:handle` return of a
  shape the boundary cannot carry; a `:borrowed` slice of a buffer-carrying
  struct (no free shim); a bare `:slice`/`:array`/`:ptr`/`:manyptr` return
  (no ownership policy); and any value-position scalar without an FFM
  carrier."
  [{:keys [ret] :as spec}]
  (when (and (= :scalar (:kind ret)) (= :noreturn (:name ret)))
    (fail spec :clj-zig/noreturn-return
          ":noreturn is not a valid return type." {}))
  (when (and (= :optional (:kind ret))
             (not (optional-inner-ok? #{:ptr} (:of ret))))
    (fail spec :clj-zig/unsupported-optional
          "An :optional return must wrap a :ptr, carrier scalar, or named struct." {}))
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
  (when (and (= :named (:kind ret))
             (some :target (get-in ret [:layout :fields])))
    ;; A plain named struct return with a buffer field has no free shim
    ;; (shims are emitted for owned/borrowed/optional/eu-struct/stream only),
    ;; so a body that c_allocator-allocates the buffer leaks it every call.
    ;; Require :owned so a free shim frees the body's buffers, or return a
    ;; scalar-only struct (which has no allocation to free).
    (fail spec :clj-zig/unsupported-buffer-struct-return
          (str "A plain named struct return with a buffer field is not"
               " supported: the wrapper copies the struct out but cannot free"
               " the body's buffer allocation. Wrap it in :owned, or return a"
               " scalar-only struct.")
          {}))
  (when (= :stream (:kind ret))
    (validate-stream-return! spec ret))
  (check-element! spec ret)
  (when (borrowed-buffer-slice? ret)
    (fail spec :clj-zig/unsupported-borrowed-buffer-slice
          (str "A :borrowed slice of a buffer-carrying struct is not supported;"
               " the wrapper's wire slab has no free shim. Use :owned so each"
               " element's buffers and the slab are freed.")
          {}))
  (when (contains? #{:slice :array :ptr :manyptr} (:kind ret))
    (fail spec :clj-zig/unsupported-return-kind
          (str "A :" (name (:kind ret)) " return is not supported; wrap it in"
               " :owned or :borrowed for a slice, or use :bytes or :string.")
          {}))
  (let [ret-value     (if (= :error-union (:kind ret)) (:of ret) ret)
        ret-scalars   (if (type/void-type? ret-value) #{} (scalar-names ret-value))
        value-scalars (apply set/union ret-scalars (map (comp scalar-names :type) (:params spec)))
        no-carrier    (remove type/has-carrier? value-scalars)]
    (when (seq no-carrier)
      (fail spec :clj-zig/unsupported-carrier
            (str "Types " (str/join ", " (sort no-carrier))
                 " have no FFM carrier and cannot cross the boundary.")
            {}))))

(defn- validate!
  "Reject contracts FFM cannot honor, delegating to the argument and return
  halves. Throws a diagnostic (`ex-info`) on the first violation."
  [spec]
  (validate-args! spec)
  (validate-return! spec))

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

;; clojure.spec.alpha projection
;; The boundary spec projected to spec.alpha predicates: `register!` writes
;; the args/ret specs to the registry for a defnz Var.

(defn spec-for-type
  "A clojure.spec predicate form for a normalized boundary type.
  Scalars map to their JVM predicate; enums map to the member keyword
  set; named structs map to `map?`; slices and collections map to
  `coll-of`; strings to `string?`; bytes to `bytes?`; optional wraps in
  `nilable`; handle to `some?`; void to `nil?`."
  [t]
  (case (:kind t)
    :scalar  (case (:category (type/scalar-info (:name t)))
               :int   'int?
               :float 'double?
               :bool  'boolean?
               :void  'nil?
               'some?)
    :string  'string?
    (:slice :stream) (list 'clojure.spec.alpha/coll-of (spec-for-type (:of t)))
    :array   (list 'clojure.spec.alpha/coll-of (spec-for-type (:of t)) :count (:length t))
    :optional (list 'clojure.spec.alpha/nilable (spec-for-type (:of t)))
    :handle  'some?
    :bytes   'bytes?
    :named   (if (get-in t [:layout :enum])
               (set (for [v (get-in t [:layout :values])]
                      (keyword (str (:name v)))))
               'map?)
    (:owned :borrowed) (case (get-in t [:of :kind])
                         :slice (list 'clojure.spec.alpha/coll-of (spec-for-type (get-in t [:of :of])))
                         :named 'map?
                         'some?)
    :error-union (spec-for-type (:of t))
    'some?))

(defn spec-for-param
  "The argument-side spec for a boundary param. A slice argument is
  driven by the caller, who passes a Java primitive array; so the spec
  is permissive (`array?` or `coll?`). A scalar is exact."
  [param]
  (let [t (:type param)]
    (case (:kind t)
      :slice  '(clojure.spec.alpha/or :array array? :coll coll?)
      :string '(clojure.spec.alpha/or :str string? :bytes bytes?)
      :named  (if (get-in t [:layout :enum])
                (spec-for-type t)
                'map?)
      :array  'array?
      (spec-for-type t))))

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

(comment
  (build-spec '{:ns app.core :name add :signature [x :i64 y :i64 :ret :i64]})
  ;; => {:ns app.core :name add :symbol "clj_zig_app_2e_core_add" :params [...] :ret {...} ...}

  (symbol-name 'app.core 'add)     ;; => "clj_zig_app_2e_core_add"

  ;; A 128-bit carrier is rejected at spec time.
  (try (build-spec '{:ns a :name f :signature [x :u128 :ret :i64]})
       (catch clojure.lang.ExceptionInfo e (:error/code (ex-data e)))))
