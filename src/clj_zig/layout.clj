(ns clj-zig.layout
  "Describe the memory layout of a named boundary type (pure). A field
  list goes in, a layout descriptor comes out, or a diagnostic is thrown.

  The descriptor carries the fields in declaration order with their
  normalized types and C-ABI byte offsets, plus the whole struct's size
  and alignment:

      (describe 'Point '[x :f64 y :f64])
      => {:name Point
          :fields [{:name x :type {:kind :scalar :name :f64} :offset 0}
                   {:name y :type {:kind :scalar :name :f64} :offset 8}]
          :size 16 :align 8}

  A field may be a carrier scalar, an enum's i32 backing, or a buffer
  field (`:string`, `[:bytes [:slice :u8]]`, or a bare, owned, or
  borrowed slice of a carrier scalar). A buffer field has no single C
  type: it expands in the wire struct to two `usize` words, a pointer
  and a length, so the descriptor records both offsets and the
  marshalled Clojure target for each. The descriptor renders the
  `extern struct` the generated Zig uses and drives the field
  marshalling FFM performs; the layout matches Zig's `extern struct`
  (C ABI)."
  (:require [clojure.string :as str]
            [clj-zig.type :as type]))

(defn- field-bytes
  "The size and alignment in bytes of a scalar field. Primitive scalars
  align to their own width."
  [t]
  (let [{:keys [category bits]} (type/scalar-info (:name t))]
    (case category
      :bool 1
      (quot bits 8))))

(defn- wire-scalar-bytes
  "The size and alignment of a scalar or enum field's wire form. A scalar
  field is its carrier width; an enum field crosses as its `i32` backing,
  so its wire size comes from the backing, not the named type (which has
  no scalar-info entry)."
  [t]
  (let [backing (if (and (= :named (:kind t)) (get-in t [:layout :enum]))
                  (get-in t [:layout :backing])
                  t)]
    (field-bytes backing)))

(def ^:private pointer-word
  "The scalar form of one wire word: a buffer field's pointer or length
  crosses as a `usize`, which is target-width. Both halves of an
  expanded buffer field take their size and alignment from this scalar,
  so the layout recomputes if a future target changes usize's width; the
  descriptor's content hash then recomputes with it. The development
  target is 64-bit, so a word is eight bytes."
  {:kind :scalar :name :usize})

(defn- word-bytes
  "The size and alignment in bytes of one wire word (a `usize` pointer or
  length)."
  []
  (field-bytes pointer-word))

(def ^:private byte-array-target
  "The marshalled Clojure target for a `[:bytes [:slice :u8]]` field: a
  Java `byte[]`. Spelled as `(keyword \"byte[]\")` because the literal
  `:byte[]` splits at the bracket when read."
  (keyword "byte[]"))

(defn- round-up
  "Raise `n` to the next multiple of `a`."
  [n a]
  (* a (quot (+ n a -1) a)))

(defn- slice-field-element
  "The carrier scalar a slice-bearing buffer field carries, or nil when
  the field does not carry a slice of a carrier scalar. A bare `:slice`
  is its own slice; an `:owned`, `:borrowed`, or `:bytes` wrapper holds
  its slice under `:of`."
  [t]
  (let [slice (case (:kind t)
                :slice                    t
                (:owned :borrowed :bytes) (:of t)
                nil)]
    (when (and (map? slice) (= :slice (:kind slice)))
      (let [elem (:of slice)]
        (when (and (map? elem)
                   (= :scalar (:kind elem))
                   (type/has-carrier? (:name elem)))
          elem)))))

(defn- scalar-only-layout?
  "True when a struct layout's fields are all carrier scalars, directly
  or through a further nested struct whose own inner layout is scalar-
  only. A buffer field, an enum field, or any non-scalar field
  disqualifies. Used to gate which named types may nest inside another
  struct: a nested struct crosses by value (the inner extern struct is
  embedded), and only the scalar interior composes cleanly on both sides
  of the boundary."
  [layout]
  (and (not (:enum layout))
       (seq (:fields layout))
       (every? (fn [f]
                 (let [t (:type f)]
                   (or (and (= :scalar (:kind t)) (type/has-carrier? (:name t)))
                       (and (= :named (:kind t))
                            (get-in t [:layout])
                            (not (get-in t [:layout :enum]))
                            (scalar-only-layout? (get-in t [:layout]))))))
               (:fields layout))))

(defn- nested-field?
  "True when a normalized field is a nested struct: a named, non-enum
  field whose inner layout is resolved."
  [f]
  (let [t (:type f)]
    (and (= :named (:kind t))
         (get-in t [:layout])
         (not (get-in t [:layout :enum])))))

(defn- classify-field
  "The wire shape of a normalized field type: `:scalar` for a carrier
  scalar, `:nested` for a struct field whose inner type is a scalar-only
  struct (crossed by value, the inner extern struct embedded in the wire
  struct), or `:buffer` for a field that expands to a `{ptr, len}` pair,
  carrying its marshalled Clojure `:target` (`:string`, `:byte[]`, or
  `:vector`). Throws a diagnostic for any field the wire struct cannot
  carry: a named struct whose inner is not scalar-only, an unresolved
  named type, an unbounded pointer, a carrierless scalar, or a wrapper
  the layout does not lower. A named enum field is a wire scalar (its
  integer backing)."
  [type-name fname ftype t]
  (cond
    (and (= :scalar (:kind t)) (type/has-carrier? (:name t)))
    {:wire :scalar}

    (= :string (:kind t))
    {:wire :buffer :target :string}

    (and (= :bytes (:kind t))
         (= :slice (get-in t [:of :kind]))
         (= :u8 (get-in t [:of :of :name])))
    {:wire :buffer :target byte-array-target}

    (and (contains? #{:slice :owned :borrowed} (:kind t))
         (some? (slice-field-element t)))
    {:wire :buffer :target :vector}

    (= :named (:kind t))
    (cond
      (nil? (get-in t [:layout]))
      (throw (ex-info (str "Field " fname " of " type-name
                           " names an undeclared type " (:name t) ".")
                      {:level :error :error/code :clj-zig/unknown-field
                       :type type-name :field fname :clj-zig/type-form ftype}))
      (get-in t [:layout :enum])
      {:wire :scalar}
      (scalar-only-layout? (get-in t [:layout]))
      {:wire :nested}
      :else
      (throw (ex-info (str "Field " fname " of " type-name
                           " nests " (:name t) ", whose fields are not all"
                           " carrier scalars; a nested struct must be scalar-only.")
                      {:level :error :error/code :clj-zig/unsupported-field
                       :type type-name :field fname :clj-zig/type-form ftype})))

    :else
    (throw (ex-info (str "Field " fname " of " type-name
                         " must be a carrier scalar, an enum, a nested scalar struct,"
                         " or a buffer field ([:bytes [:slice :u8]], a slice, or :string).")
                    {:level         :error
                     :error/code    :clj-zig/unsupported-field
                     :type          type-name
                     :field         fname
                     :clj-zig/type-form ftype}))))

(defn- normalize-field
  "Normalize one `[name type]` field pair. A carrier scalar or a named
  enum returns as `{:name :type}`; a buffer field returns with its
  marshalled Clojure `:target`; a nested struct field returns flagged
  `:nested`. A named field's layout is attached from `types` (the
  registry of named types already declared in the namespace) so an enum
  field is recognized as a wire scalar, a nested struct is recognized,
  and an undeclared name is rejected."
  [type-name types [fname ftype]]
  (let [t (type/normalize ftype)
        t (if (= :named (:kind t))
            (if-let [layout (get types (:name t))]
              (assoc t :layout layout)
              t)
            t)]
    (let [classified (classify-field type-name fname ftype t)]
      (cond-> (merge {:name fname :type t}
                     (select-keys classified [:target]))
        (= :nested (:wire classified)) (assoc :nested true)))))

(defn describe
  "Build the layout descriptor for a named type from its `fields`, a
  vector of `name type` pairs. A carrier scalar or an enum keeps its
  carrier size and alignment (an enum crosses as its `i32` backing); a
  buffer field (`:string`, `[:bytes [:slice :u8]]`, or a slice) expands
  to two `usize` words aligned to the word width, and the field records
  the pointer offset as `:offset`, the length offset as `:len-offset`,
  and the marshalled target. Throws a diagnostic for an odd field list
  or a field the wire struct cannot carry. The optional `types` map
  resolves named enum fields against the registry of named types already
  declared in the namespace."
  ([type-name fields] (describe type-name fields {}))
  ([type-name fields types]
   (when (odd? (count fields))
     (throw (ex-info (str type-name " needs a type for every field.")
                     {:level :error :error/code :clj-zig/malformed-fields
                      :type type-name})))
   (let [word  (word-bytes)
          placed (reduce (fn [{:keys [fields offset align]} pair]
                           (let [{:keys [target type] :as f} (normalize-field type-name types pair)]
                             (cond
                               target
                               ;; A buffer field: ptr then len, each a usize word.
                               (let [ptr-off (round-up offset word)
                                     len-off (+ ptr-off word)]
                                 {:fields (conj fields (assoc f
                                                              :offset ptr-off
                                                              :len-offset len-off))
                                  :offset (+ len-off word)
                                  :align  (max align word)})

                               (nested-field? f)
                               ;; A nested struct field: the inner extern struct is
                               ;; embedded by value, so its size and alignment are
                               ;; the inner layout's, and the field carries the inner
                               ;; layout for the FFM reader to recurse into.
                               (let [inner (:size (get-in type [:layout]))
                                     ialign (:align (get-in type [:layout]))
                                     off    (round-up offset ialign)]
                                 {:fields (conj fields (assoc f :offset off))
                                  :offset (+ off inner)
                                  :align  (max align ialign)})

                               :else
                               ;; A scalar or enum field: its wire carrier size
                               ;; and alignment (an enum is its integer backing).
                               (let [size (wire-scalar-bytes type)
                                     off  (round-up offset size)]
                                 {:fields (conj fields (assoc f :offset off))
                                  :offset (+ off size)
                                  :align  (max align size)}))))
                         {:fields [] :offset 0 :align 1}
                         (partition 2 fields))]
     {:name   type-name
      :fields (:fields placed)
      :size   (round-up (:offset placed) (:align placed))
      :align  (:align placed)})))

(defn describe-record
  "The layout descriptor for a `defrecordz` type: the struct layout of
  `describe`, plus the qualified map-factory symbol the boundary resolves
  to rebuild the record from its fields on a return."
  ([type-name fields record-ns] (describe-record type-name fields record-ns {}))
  ([type-name fields record-ns types]
   (assoc (describe type-name fields types)
          :record (symbol (str record-ns) (str "map->" type-name)))))

(defn- validate-enum-backing
  "The validated integer carrier scalar keyword for an enum's `:backing`
  option, defaulting to `:i32`. Throws `:clj-zig/bad-enum-backing` for a
  non-integer or carrierless scalar (an enum tag is an integer width)."
  [type-name backing-kw]
  (let [info (type/scalar-info backing-kw)]
    (when-not (and info (= :int (:category info)) (type/has-carrier? backing-kw))
      (throw (ex-info (str "The enum " type-name " backing must be an integer scalar "
                           "with an FFM carrier; got " (pr-str backing-kw) ".")
                      {:level :error :error/code :clj-zig/bad-enum-backing
                       :type type-name :backing backing-kw}))))
  backing-kw)

(defn- enum-range
  "The inclusive `[lo hi]` range of integer values that fit a signed or
  unsigned scalar of `bits` width."
  [signed? bits]
  (let [half (bit-shift-left 1 (dec bits))]
    (if signed?
      [(- half) (dec half)]
      [0 (dec (* 2 half))])))

(defn describe-enum
  "The descriptor for a `defenumz` type: an enum whose members cross as
  keywords, backed by an integer scalar (default `:i32`). The optional
  `opts` map may carry `:backing` to widen or narrow the tag (`:u8`,
  `:u32`, ...). Throws for an odd member list, a non-integer member, a
  non-integer or carrierless backing, or a member value that does not fit
  the backing's range."
  ([type-name members] (describe-enum type-name members nil))
  ([type-name members opts]
   (when (odd? (count members))
     (throw (ex-info (str type-name " needs a value for every member.")
                     {:level :error :error/code :clj-zig/malformed-members
                      :type type-name})))
   (let [backing-kw   (validate-enum-backing type-name (or (:backing opts) :i32))
         {:keys [signed? bits]} (type/scalar-info backing-kw)
         [lo hi]      (enum-range signed? bits)]
     {:name    type-name
      :enum    true
      :backing {:kind :scalar :name backing-kw}
      :values  (mapv (fn [[mname value]]
                       (when-not (integer? value)
                         (throw (ex-info (str "Member " mname " of " type-name
                                              " needs an integer value.")
                                         {:level :error
                                          :error/code :clj-zig/non-integer-member
                                          :type type-name :member mname})))
                       (when (or (< value lo) (> value hi))
                         (throw (ex-info (str "Member " mname " of " type-name
                                              " has value " value " which does not fit "
                                              "the " backing-kw " backing range "
                                              "[" lo ", " hi "].")
                                         {:level :error
                                          :error/code :clj-zig/enum-value-overflow
                                          :type type-name :member mname
                                          :value value :backing backing-kw
                                          :lo lo :hi hi})))
                       {:name mname :value (long value)})
                     (partition 2 members))})))

(defn enum?
  "True when a layout descriptor describes a `defenumz` enum rather than a
  struct or record."
  [descriptor]
  (boolean (:enum descriptor)))

(defn- zig-field-decls
  "The Zig declaration line(s) for one layout field. A scalar field is
  one line of its carrier type; a buffer field expands to a `<name>_ptr`
  and a `<name>_len`, both `usize` (the C-ABI `{ptr, len}` pair a slice
  parameter already lowers to, applied to a struct field)."
  [{fname :name t :type :keys [target]}]
  (if target
    [(str "    " fname "_ptr: usize,")
     (str "    " fname "_len: usize,")]
    [(str "    " fname ": " (name (:name t)) ",")]))

(defn zig-struct
  "The `extern struct` declaration the generated Zig uses for a layout.
  Each scalar field is declared by its carrier; each buffer field
  expands to a `usize` pointer and length pair, the wire form a slice
  parameter already takes."
  [{type-name :name :keys [fields]}]
  (str "const " type-name " = extern struct {\n"
       (str/join "\n" (mapcat zig-field-decls fields))
       "\n};\n"))

(defn- nice-field-type
  "The Zig type name for one field in the nice record the body constructs.
  A scalar field is its carrier; an enum field is its enum type name; a
  buffer field is a slice of its element (a `:string` or `:bytes` field
  is `[]u8`). This is the field type the body builds, not the wire form
  the boundary reads."
  [{:keys [type]}]
  (case (:kind type)
    :scalar (name (:name type))
    :named  (str (:name type))
    :string "[]u8"
    (let [slice (case (:kind type)
                  :slice type
                  (:owned :borrowed :bytes) (:of type))
          elem (:of slice)]
      (str "[]" (name (:name elem))))))

(defn zig-nice-struct
  "The nice record struct the body constructs and returns: a regular
  `struct` (not `extern`) whose buffer fields are real Zig slices, not
  the `usize` ptr/len words of the wire struct. Emitted for a
  buffer-carrying record so the body can build the nice type the wrapper
  then decomposes field by field into the wire `extern struct`. A
  scalar-only record keeps its `extern struct` (the nice and wire
  layouts coincide, and the body builds it directly)."
  [{type-name :name :keys [fields]}]
  (str "const " type-name " = struct {\n"
       (str/join "\n" (map (fn [f] (str "    " (:name f) ": " (nice-field-type f) ","))
                           fields))
       "\n};\n"))

(defn zig-enum
  "The `enum(<backing>)` declaration the generated Zig uses for an enum
  layout. The backing width defaults to `i32` when a legacy descriptor
  carries none."
  [{type-name :name :keys [backing values]}]
  (let [backing-name (or (some-> backing :name name) "i32")]
    (str "const " type-name " = enum(" backing-name ") {\n"
         (str/join "\n" (map (fn [{mname :name value :value}]
                               (str "    " mname " = " value ","))
                             values))
         "\n};\n")))

(defn zig-decl
  "The Zig declaration for a named type: an `enum` for a `defenumz`, a
  regular `struct` (the nice record the body constructs) for a
  buffer-carrying `deftypez`/`defrecordz`, and an `extern struct` (the
  wire layout, which is also the nice layout) for a scalar-only struct."
  [descriptor]
  (cond
    (enum? descriptor) (zig-enum descriptor)
    (some :target (:fields descriptor)) (zig-nice-struct descriptor)
    :else (zig-struct descriptor)))

(comment
  (describe 'Point '[x :f64 y :f64])
  (describe 'Pixel '[r :u8 g :u8 b :u8])
  (print (zig-struct (describe 'Point '[x :f64 y :f64]))))
