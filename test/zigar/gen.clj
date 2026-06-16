(ns zigar.gen
  "Shared generators and enumerations for the boundary contract. A support
  namespace the property tests pull from, not a test namespace itself.

  Three layers, matching the testing engines:

  - Generators (`gen-scalar-type`, `gen-arg-type`, `gen-ret-type`,
    `gen-signature`, `gen-type-form`, `gen-field-list`, `gen-value-for`)
    feed `test.check` properties. The signature generators stay inside the
    end-to-end-supported subset, so every form they make builds a spec and
    generates source.
  - Edge-value vectors (`edge-values`) carry the boundary numbers for the
    value axis: minima, maxima, zero, the unsigned values beyond the signed
    range, the float specials.
  - Enumerations (`carrier-scalars`, `arg-forms`, `ret-forms`,
    `structural-cases`) walk the structural matrix as plain data, including
    named struct, enum, and handle types backed by `fixture-types`.

  `gen-junk-form` carries malformed type forms for the negative-space
  tests, where each form must be rejected."
  (:require [clojure.test.check.generators :as gen]
            [zigar.layout :as layout]
            [zigar.type :as type]))

;; --- Scalar vocabulary --------------------------------------------------

(def carrier-scalars
  "Scalar boundary types that cross the FFM boundary as a primitive value."
  (vec (sort (filter type/has-carrier? (keys type/scalars)))))

(def int-carriers
  (vec (filter #(= :int (:category (type/scalars %))) carrier-scalars)))

(def float-carriers
  (vec (filter #(= :float (:category (type/scalars %))) carrier-scalars)))

(defn- pow2 [n]
  (bigint (.shiftLeft (biginteger 1) (int n))))

(defn edge-values
  "The boundary values worth probing for a scalar keyword: the extremes of
  its range, the small neighbourhood around zero, and the float specials."
  [kw]
  (let [{:keys [category signed? bits]} (type/scalars kw)]
    (case category
      :bool  [true false]
      :float (let [mx (if (= 32 bits) (float Float/MAX_VALUE) Double/MAX_VALUE)
                   mn (if (= 32 bits) (float Float/MIN_VALUE) Double/MIN_VALUE)]
               [0.0 -0.0 1.0 -1.0 mn mx (- mx)
                Double/NaN Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY])
      :int   (if signed?
               (let [hi (pow2 (dec bits))]
                 [(- hi) (+ (- hi) 1) -1 0 1 (- hi 1)])
               (let [hi (pow2 bits)]
                 [0 1 2 (- hi 2) (- hi 1)])))))

;; --- Scalar and value generators ---------------------------------------

(def gen-scalar-type
  "A scalar boundary type with an FFM carrier."
  (gen/elements carrier-scalars))

(defn- to-long [v] (.longValue (biginteger v)))

(defn- random-scalar
  "A within-range generator for a scalar keyword, clamped to the safe long
  window for the random draw; `edge-values` reaches past it."
  [kw]
  (let [{:keys [category signed? bits]} (type/scalars kw)]
    (case category
      :bool  gen/boolean
      :float (if (= 32 bits)
               (gen/fmap float (gen/double* {:infinite? false :NaN? false
                                             :min (double (- Float/MAX_VALUE))
                                             :max (double Float/MAX_VALUE)}))
               (gen/double* {:infinite? false :NaN? false}))
      :int   (let [lo (if signed?
                        (long (max (bigint Long/MIN_VALUE) (- (pow2 (dec bits)))))
                        0)
                   hi (long (min (bigint Long/MAX_VALUE)
                                 (if signed? (- (pow2 (dec bits)) 1) (- (pow2 bits) 1))))]
               (gen/large-integer* {:min lo :max hi})))))

(defn gen-scalar-value
  "A value for a scalar keyword, weighted toward the boundary cases."
  [kw]
  (gen/frequency [[2 (gen/elements (edge-values kw))]
                  [1 (random-scalar kw)]]))

(defn primitive-array
  "Pack Clojure numbers into the primitive array a scalar element crosses
  as, narrowing each integer to the element's width."
  [elem-kw vs]
  (let [{:keys [category bits]} (type/scalars elem-kw)]
    (case category
      :bool  (boolean-array (map boolean vs))
      :float (if (= 32 bits)
               (float-array (map unchecked-float vs))
               (double-array (map double vs)))
      :int   (case bits
               8  (byte-array (map (comp unchecked-byte to-long) vs))
               16 (short-array (map (comp unchecked-short to-long) vs))
               32 (int-array (map (comp unchecked-int to-long) vs))
               64 (long-array (map to-long vs))))))

(defn- gen-array-of
  "A primitive array of `elem-kw` values with a length in `[lo, hi]`."
  [elem-kw lo hi]
  (gen/let [n  (gen/choose lo hi)
            vs (gen/vector (gen-scalar-value elem-kw) n)]
    (primitive-array elem-kw vs)))

(defn gen-value-for
  "A Clojure argument value for a normalized boundary type: a scalar, a
  primitive array for a slice or pointer, a fixed-length array, or nil or a
  value for an optional."
  [t]
  (case (:kind t)
    :scalar   (gen-scalar-value (:name t))
    :slice    (gen-array-of (:name (:of t)) 0 8)
    :manyptr  (gen-array-of (:name (:of t)) 1 8)
    :ptr      (gen-array-of (:name (:of t)) 1 1)
    :array    (gen-array-of (:name (:of t)) (:length t) (:length t))
    :optional (gen/one-of [(gen/return nil) (gen-value-for (:of t))])
    (gen-scalar-value :i64)))

;; --- Type-form generators ----------------------------------------------

(defn normalized->form
  "Render a normalized boundary type back to a type form, the inverse of
  `type/normalize`. A normalize-then-render-then-normalize round-trip is
  the identity, so this drives the stability properties."
  [t]
  (case (:kind t)
    :scalar                (:name t)
    :named                 (:name t)
    (:slice :ptr :manyptr) (if (:const? t)
                             [(:kind t) :const (normalized->form (:of t))]
                             [(:kind t) (normalized->form (:of t))])
    :array                 [:array (:length t) (normalized->form (:of t))]
    (:optional :owned
     :borrowed :handle)    [(:kind t) (normalized->form (:of t))]
    :error-union           [:error-union (:error t) (normalized->form (:of t))]))

(def type-name-symbols ['Point 'Pixel 'Color 'Status])

(def gen-named-type (gen/elements type-name-symbols))

(defn gen-type-form
  "A normalizable boundary type form, position-agnostic, nested to `depth`.
  Every form it makes normalizes; positional validity is not promised, so
  it drives `normalize` rather than `build-spec`."
  ([] (gen-type-form 2))
  ([depth]
   (if (zero? depth)
     (gen/one-of [gen-scalar-type gen-named-type])
     (gen/let [choice (gen/elements [:scalar :named :slice :ptr :manyptr
                                     :array :optional :owned :borrowed
                                     :handle :error-union])
               const? gen/boolean
               len    (gen/choose 0 8)
               s      gen-scalar-type
               nm     gen-named-type
               sub    (gen-type-form (dec depth))]
       (case choice
         :scalar      s
         :named       nm
         :slice       (if const? [:slice :const sub] [:slice sub])
         :ptr         (if const? [:ptr :const sub] [:ptr sub])
         :manyptr     (if const? [:manyptr :const sub] [:manyptr sub])
         :array       [:array len sub]
         :optional    [:optional sub]
         :owned       [:owned sub]
         :borrowed    [:borrowed sub]
         :handle      [:handle sub]
         :error-union [:error-union 'Error sub])))))

;; --- Signature generators (the supported subset) -----------------------

(def gen-arg-type
  "A boundary type valid in argument position and supported end to end."
  (gen/let [elem  gen-scalar-type
            len   (gen/choose 1 8)
            shape (gen/elements [:scalar :slice :slice-const :ptr :ptr-const
                                 :manyptr :manyptr-const :array
                                 :optional-ptr :optional-manyptr])]
    (case shape
      :scalar           elem
      :slice            [:slice elem]
      :slice-const      [:slice :const elem]
      :ptr              [:ptr elem]
      :ptr-const        [:ptr :const elem]
      :manyptr          [:manyptr elem]
      :manyptr-const    [:manyptr :const elem]
      :array            [:array len elem]
      :optional-ptr     [:optional [:ptr elem]]
      :optional-manyptr [:optional [:manyptr elem]])))

(def gen-ret-type
  "A boundary type valid in return position and supported end to end."
  (gen/let [elem  gen-scalar-type
            shape (gen/elements [:scalar :void :optional-ptr :error-scalar
                                 :error-void :owned :borrowed])]
    (case shape
      :scalar       elem
      :void         :void
      :optional-ptr [:optional [:ptr elem]]
      :error-scalar [:error-union 'Error elem]
      :error-void   [:error-union 'Error :void]
      :owned        [:owned [:slice elem]]
      :borrowed     [:borrowed [:slice elem]])))

(def gen-signature
  "A `defnz` signature vector over the supported subset: zero to three
  typed arguments and a required final `:ret`. Every signature builds a
  spec with no named types."
  (gen/let [n         (gen/choose 0 3)
            arg-types (gen/vector gen-arg-type n)
            ret       gen-ret-type]
    (conj (vec (mapcat (fn [i t] [(symbol (str "a" i)) t])
                       (range) arg-types))
          :ret ret)))

(def gen-field-list
  "A `deftypez` field list: one to six `name type` pairs over carrier
  scalars."
  (gen/let [n     (gen/choose 1 6)
            types (gen/vector gen-scalar-type n)]
    (vec (mapcat (fn [i t] [(symbol (str "f" i)) t]) (range) types))))

(def gen-enum-members
  "A `defenumz` member list: one to six `name value` pairs with distinct
  integer values, so the value-to-member mapping is unambiguous."
  (gen/let [n      (gen/choose 1 6)
            values (gen/vector-distinct gen/nat {:num-elements n})]
    (vec (mapcat (fn [i v] [(symbol (str "m" i)) v]) (range) values))))

;; --- Negative space ----------------------------------------------------

(def junk-forms
  "Malformed type forms; `type/normalize` must reject every one."
  [[]
   [:bogus :u8]
   :not-a-scalar
   [:slice]
   [:array -1 :u8]
   [:array :u8]
   [:ptr :const :u8 :u8]
   [:optional]
   [:error-union :u8]
   [:handle]
   42
   "u8"])

(def gen-junk-form (gen/elements junk-forms))

;; --- Structural enumeration --------------------------------------------

(def matrix-scalars
  "A representative scalar per category and signedness for the structural
  matrix: a signed int, a wide signed int, an unsigned int past the signed
  range, a float, and a bool."
  [:i32 :i64 :u64 :f64 :bool])

(defn arg-forms
  "The supported argument type forms over one element scalar `e`, spanning
  the indirection, constness, and wrapper axes."
  [e]
  [e
   [:slice e] [:slice :const e]
   [:ptr e] [:ptr :const e]
   [:manyptr e] [:manyptr :const e]
   [:array 3 e]
   [:optional [:ptr e]] [:optional [:manyptr e]]])

(defn ret-forms
  "The supported return type forms over one element scalar `e`."
  [e]
  [e :void
   [:optional [:ptr e]]
   [:error-union 'Error e] [:error-union 'Error :void]
   [:owned [:slice e]] [:borrowed [:slice e]]])

(def fixture-types
  "Named-type descriptors the structural matrix references: two structs and
  an enum, registered the way a namespace's `deftypez`/`defenumz` would."
  {'Point  (layout/describe 'Point '[x :f64 y :f64])
   'Pixel  (layout/describe 'Pixel '[r :u8 g :u8 b :u8])
   'Status (layout/describe-enum 'Status '[ok 0 busy 1 done 2])})

(defn structural-cases
  "A bounded enumeration of supported signatures as `build-spec` inputs,
  spanning the scalar matrix in argument and return position plus the named
  struct, enum, and handle types. Each case is `{:ns :name :signature
  :types}`."
  []
  (let [mk (fn [sig types] {:ns 'zigar.matrix :name 'f :signature sig :types types})]
    (concat
     (for [e matrix-scalars af (arg-forms e)] (mk [(symbol "a") af :ret e] {}))
     (for [e matrix-scalars rf (ret-forms e)] (mk [:ret rf] {}))
     [(mk ['p 'Point :ret 'Point] fixture-types)
      (mk ['p 'Point :ret :f64] fixture-types)
      (mk ['c 'Pixel :ret 'Pixel] fixture-types)
      (mk ['s 'Status :ret 'Status] fixture-types)
      (mk ['h [:handle 'Point] :ret [:handle 'Point]] fixture-types)
      (mk [:ret [:handle 'Point]] fixture-types)])))

(comment
  (require '[zigar.spec :as spec] '[clojure.test.check.generators :as g])
  (g/sample gen-signature 5)
  (count (structural-cases))
  (map #(spec/build-spec %) (take 3 (structural-cases))))
