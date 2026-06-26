(ns clj-zig.type
  "Normalize boundary type forms into plain data. Pure: a type
  form goes in, a normalized map comes out, or a diagnostic is thrown.

  Scalar types are Zig keywords; compound types are vectors; a symbol
  names a type declared elsewhere (`deftypez`, `defrecordz`, `defenumz`)
  and resolves against the named-type registry later. For example:

      :i64                  => {:kind :scalar :name :i64}
      [:slice :const :u8]   => {:kind :slice :const? true
                                :of {:kind :scalar :name :u8}}
      :string               => {:kind :string}
      Point                 => {:kind :named :name Point}

  The normalized form is the inspectable contract; it carries only
  structure. Scalar classification (signedness, bit width) lives in the
  `scalars` table and feeds the unsigned-return policy and the spec-time
  rejection of carriers FFM cannot express (`:i128`/`:u128`).")

(declare normalize normalize-scalar normalize-compound normalize-indirection
         normalize-array normalize-wrapper normalize-error-union fail)

(def scalars
  "The scalar boundary types and their classification.
  `:bits` for `:isize`/`:usize` is the 64-bit value for the development
  target; it is platform-dependent in Zig."
  {:i8       {:category :int :signed? true  :bits 8}
   :i16      {:category :int :signed? true  :bits 16}
   :i32      {:category :int :signed? true  :bits 32}
   :i64      {:category :int :signed? true  :bits 64}
   :i128     {:category :int :signed? true  :bits 128}
   :u8       {:category :int :signed? false :bits 8}
   :u16      {:category :int :signed? false :bits 16}
   :u32      {:category :int :signed? false :bits 32}
   :u64      {:category :int :signed? false :bits 64}
   :u128     {:category :int :signed? false :bits 128}
   :isize    {:category :int :signed? true  :bits 64}
   :usize    {:category :int :signed? false :bits 64}
   :f16      {:category :float :bits 16}
   :f32      {:category :float :bits 32}
   :f64      {:category :float :bits 64}
   :f80      {:category :float :bits 80}
   :f128     {:category :float :bits 128}
   :bool     {:category :bool}
   :void     {:category :void}
   :noreturn {:category :noreturn}})

(defn scalar-info
  "Classification map for a scalar keyword, or nil if it is not one."
  [name]
  (get scalars name))

(defn unsigned-int?
  "True when `name` is an unsigned integer scalar."
  [name]
  (let [{:keys [category signed?]} (scalars name)]
    (and (= :int category) (false? signed?))))

(defn void-type?
  "True when a normalized type is a `:void` or `:noreturn` scalar, which
  carries no value across the boundary."
  [t]
  (and (= :scalar (:kind t))
       (contains? #{:void :noreturn} (:name t))))

(defn has-carrier?
  "True when a scalar crosses the FFM boundary as a primitive value.
  Stable FFM (Java 22) carries 8/16/32/64-bit integers and
  32/64-bit floats and `bool`; 128-bit integers and 16/80/128-bit floats
  have no carrier, so they are rejected at spec time. `:void` and
  `:noreturn` are not value carriers (a `:void` return carries nothing)."
  [name]
  (let [{:keys [category bits]} (scalars name)]
    (case category
      :int    (<= bits 64)
      :float  (boolean (#{32 64} bits))
      :bool   true
      false)))

(defn normalize
  "Normalize a boundary type form to its canonical data shape. Throws a
  diagnostic (`ex-info`) for unknown or malformed types.

  `:string` is a first-class buffer type, intercepted before
  `normalize-scalar` so it never enters the scalar table or the
  `has-carrier?` check: it crosses the boundary as a UTF-8 byte buffer,
  not a primitive carrier."
  [form]
  (cond
    (= :string form) {:kind :string}
    (keyword? form)  (normalize-scalar form)
    (symbol? form)   {:kind :named :name form}
    (vector? form)   (normalize-compound form)
    :else            (fail form :clj-zig/unknown-type
                          "A boundary type must be a scalar keyword, a compound vector, or a named-type symbol."
                          {:found (type form)})))

(defn- normalize-scalar [kw]
  (if (contains? scalars kw)
    {:kind :scalar :name kw}
    (fail kw :clj-zig/unknown-scalar (str "Unknown scalar type " kw ".") {})))

(defn- normalize-compound [v]
  (if-let [head (first v)]
    (case head
      (:ptr :manyptr :slice)                (normalize-indirection head v)
      :array                                (normalize-array v)
      (:optional :owned :borrowed :handle :bytes)  (normalize-wrapper head v)
      :error-union                          (normalize-error-union v)
      (fail v :clj-zig/malformed-compound
            (str "Unknown compound type head " (pr-str head) ".") {}))
    (fail v :clj-zig/malformed-compound "A compound type vector cannot be empty." {})))

(defn- normalize-indirection
  "Pointer-like types (`:ptr`, `:manyptr`, `:slice`) take an optional
  `:const` qualifier and one element type."
  [kind v]
  (let [shape (str (name kind) " takes an optional :const and one element type.")]
    (case (count v)
      2 {:kind kind :const? false :of (normalize (nth v 1))}
      3 (if (= :const (nth v 1))
          {:kind kind :const? true :of (normalize (nth v 2))}
          (fail v :clj-zig/malformed-compound shape {}))
      (fail v :clj-zig/malformed-compound shape {}))))

(defn- normalize-array [v]
  (let [length (when (= 3 (count v)) (nth v 1))]
    (if (nat-int? length)
      {:kind :array :length length :of (normalize (nth v 2))}
      (fail v :clj-zig/malformed-compound
            "[:array n T] takes a non-negative integer length and an element type." {}))))

(defn- normalize-wrapper
  "Single-element wrappers (`:optional`, `:owned`, `:borrowed`, `:handle`,
  `:bytes`) wrap one type."
  [kind v]
  (if (= 2 (count v))
    {:kind kind :of (normalize (nth v 1))}
    (fail v :clj-zig/malformed-compound
          (str (name kind) " takes one wrapped type.") {})))

(defn- normalize-error-union
  "`[:error-union E T]` pairs an error set with a value type. The error
  set is preserved as written; its precise mapping is settled by ADR
  before the marshalling lands."
  [v]
  (if (= 3 (count v))
    {:kind :error-union :error (nth v 1) :of (normalize (nth v 2))}
    (fail v :clj-zig/malformed-compound
          "[:error-union E T] takes an error set and a value type." {})))

(defn- fail
  "Throw a structured diagnostic carrying the offending type form."
  [form code message extra]
  (throw (ex-info message
                  (merge {:level :error
                          :error/code code
                          :message message
                          :clj-zig/type-form form}
                         extra))))

(comment
  (normalize :i64)                 ;; => {:kind :scalar :name :i64}
  (normalize [:slice :const :u8])  ;; => {:kind :slice :const? true :of {:kind :scalar :name :u8}}
  (normalize [:optional [:array 4 :f32]])
  (scalar-info :u64)               ;; => {:category :int :signed? false :bits 64}
  (has-carrier? :u128))            ;; => false
