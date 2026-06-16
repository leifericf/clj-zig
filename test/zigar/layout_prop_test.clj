(ns zigar.layout-prop-test
  "Properties of struct and enum layout. The example tests pin Point and a
  packed Pixel; these assert the C-ABI invariants across every generated
  field list: offsets are increasing and each is aligned to its field, the
  struct size and alignment follow the widest field, and the fields fit.
  For enums, the descriptor preserves the declared members and the
  member-to-value bridge round-trips."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [zigar.ffm :as ffm]
            [zigar.gen :as g]
            [zigar.layout :as layout]
            [zigar.type :as type]))

(defn- field-size [{t :type}]
  (let [{:keys [category bits]} (type/scalars (:name t))]
    (if (= :bool category) 1 (quot bits 8))))

;; --- Struct layout invariants ------------------------------------------

(defspec offsets-strictly-increase 200
  (prop/for-all [fields g/gen-field-list]
    (let [offsets (mapv :offset (:fields (layout/describe 'T fields)))]
      (apply < -1 offsets))))

(defspec each-offset-is-aligned-to-its-field 200
  (prop/for-all [fields g/gen-field-list]
    (every? (fn [f] (zero? (mod (:offset f) (field-size f))))
            (:fields (layout/describe 'T fields)))))

(defspec size-is-a-multiple-of-alignment 200
  (prop/for-all [fields g/gen-field-list]
    (let [{:keys [size align]} (layout/describe 'T fields)]
      (zero? (mod size align)))))

(defspec alignment-is-the-widest-field 200
  (prop/for-all [fields g/gen-field-list]
    (let [{:keys [fields align]} (layout/describe 'T fields)]
      (= align (apply max (map field-size fields))))))

(defspec every-field-fits-within-the-struct 200
  (prop/for-all [fields g/gen-field-list]
    (let [{desc-fields :fields size :size} (layout/describe 'T fields)]
      (every? (fn [f] (<= (+ (:offset f) (field-size f)) size)) desc-fields))))

(defspec fields-preserve-declaration-order 200
  (prop/for-all [fields g/gen-field-list]
    (let [pairs (partition 2 fields)
          desc  (layout/describe 'T fields)]
      (and (= (count pairs) (count (:fields desc)))
           (= (map first pairs) (map :name (:fields desc)))
           (= (map (comp type/normalize second) pairs)
              (map :type (:fields desc)))))))

;; --- Enum descriptor and bridge ----------------------------------------

(defspec enum-descriptor-preserves-members 200
  (prop/for-all [members g/gen-enum-members]
    (let [pairs (partition 2 members)
          {:keys [values backing enum]} (layout/describe-enum 'E members)]
      (and enum
           (= {:kind :scalar :name :i32} backing)
           (= (map first pairs) (map :name values))
           (= (map (comp long second) pairs) (map :value values))))))

(defspec enum-member-value-bridge-round-trips 200
  (prop/for-all [members g/gen-enum-members]
    (let [desc (layout/describe-enum 'E members)]
      (every? (fn [{:keys [name value]}]
                (let [kw (keyword (str name))]
                  (and (= value (#'ffm/enum-member->value desc kw))
                       (= kw (#'ffm/enum-value->member desc value)))))
              (:values desc)))))

(defspec enum-unknown-value-passes-through 200
  (prop/for-all [members g/gen-enum-members]
    (let [desc    (layout/describe-enum 'E members)
          taken   (set (map :value (:values desc)))
          unknown (first (remove taken (range)))]
      ;; An integer no member carries comes back as the raw integer.
      (= unknown (#'ffm/enum-value->member desc unknown)))))
