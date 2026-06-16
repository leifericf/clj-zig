(ns zigar.layout
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

  The same descriptor renders the `extern struct` the generated Zig uses
  and drives the field marshalling FFM performs. Fields are scalars with
  an FFM carrier; the layout matches Zig's `extern struct` (C ABI)."
  (:require [clojure.string :as str]
            [zigar.type :as type]))

(defn- field-bytes
  "The size and alignment in bytes of a scalar field. Primitive scalars
  align to their own width."
  [t]
  (let [{:keys [category bits]} (type/scalar-info (:name t))]
    (case category
      :bool 1
      (quot bits 8))))

(defn- round-up
  "Raise `n` to the next multiple of `a`."
  [n a]
  (* a (quot (+ n a -1) a)))

(defn- normalize-field
  "Normalize one `[name type]` field pair, rejecting anything but a
  carrier-bearing scalar."
  [type-name [fname ftype]]
  (let [t (type/normalize ftype)]
    (when-not (and (= :scalar (:kind t)) (type/has-carrier? (:name t)))
      (throw (ex-info (str "Field " fname " of " type-name
                           " must be a scalar with an FFM carrier.")
                      {:level :error
                       :error/code :zigar/unsupported-field
                       :type type-name
                       :field fname
                       :zigar/type-form ftype})))
    {:name fname :type t}))

(defn describe
  "Build the layout descriptor for a named type from its `fields`, a
  vector of `name type` pairs. Throws a diagnostic for an odd field list
  or a field that has no FFM carrier."
  [type-name fields]
  (when (odd? (count fields))
    (throw (ex-info (str type-name " needs a type for every field.")
                    {:level :error :error/code :zigar/malformed-fields
                     :type type-name})))
  (let [placed (reduce (fn [{:keys [fields offset align]} pair]
                         (let [{:keys [type] :as f} (normalize-field type-name pair)
                               size (field-bytes type)
                               off  (round-up offset size)]
                           {:fields (conj fields (assoc f :offset off))
                            :offset (+ off size)
                            :align  (max align size)}))
                       {:fields [] :offset 0 :align 1}
                       (partition 2 fields))]
    {:name   type-name
     :fields (:fields placed)
     :size   (round-up (:offset placed) (:align placed))
     :align  (:align placed)}))

(defn zig-struct
  "The `extern struct` declaration the generated Zig uses for a layout."
  [{type-name :name :keys [fields]}]
  (str "const " type-name " = extern struct {\n"
       (str/join "\n" (map (fn [{fname :name t :type}]
                             (str "    " fname ": " (name (:name t)) ","))
                           fields))
       "\n};\n"))

(comment
  (describe 'Point '[x :f64 y :f64])
  (describe 'Pixel '[r :u8 g :u8 b :u8])
  (print (zig-struct (describe 'Point '[x :f64 y :f64]))))
