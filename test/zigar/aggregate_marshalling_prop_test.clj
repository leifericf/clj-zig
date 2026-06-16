(ns zigar.aggregate-marshalling-prop-test
  "Properties of aggregate and enum marshalling, with a scratch arena and no
  compile. A struct written field by field at its C-ABI offsets reads back
  equal across alignment-stressing field orders, and a missing field is
  rejected. A native slice reads into the right vector. An enum member
  crosses to its backing integer and back to the same keyword; an unknown
  member keyword is rejected, and an integer no member carries comes back
  as the raw integer."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [zigar.ffm :as ffm]
            [zigar.gen :as g]
            [zigar.layout :as layout]
            [zigar.type :as type])
  (:import (java.lang.foreign Arena)))

(defn- code-from [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:error/code (ex-data e)))))

(defn- value=?
  "Numeric equality honoring NaN and the bool and unsigned coercions."
  [t-name a b]
  (case (:category (type/scalars t-name))
    :bool  (= (boolean a) b)
    :float (if (Double/isNaN (double a)) (Double/isNaN (double b)) (== a b))
    (== a b)))

;; --- Structs ------------------------------------------------------------

(defn- gen-struct-map [fields]
  (apply gen/hash-map
         (mapcat (fn [[n t]] [(keyword n) (g/gen-scalar-value t)])
                 (partition 2 fields))))

(def gen-fields+map
  (gen/let [fields g/gen-field-list
            m      (gen-struct-map fields)]
    [fields m]))

(defspec struct-marshalling-round-trips 300
  (prop/for-all [[fields m] gen-fields+map]
    (let [desc (layout/describe 'T fields)]
      (with-open [arena (Arena/ofConfined)]
        (let [out (#'ffm/read-struct (#'ffm/marshal-struct arena desc m) desc)]
          (every? (fn [{:keys [name type]}]
                    (value=? (:name type) (get m (keyword name)) (get out (keyword name))))
                  (:fields desc)))))))

(defspec struct-missing-a-field-is-rejected 200
  (prop/for-all [[fields m] gen-fields+map]
    (let [desc (layout/describe 'T fields)
          gone (dissoc m (keyword (first fields)))]
      (= :zigar/missing-field
         (code-from #(with-open [arena (Arena/ofConfined)]
                       (#'ffm/marshal-struct arena desc gone)))))))

;; --- Slices -------------------------------------------------------------

(defspec slice-reads-the-right-vector 300
  (prop/for-all [[elem vals] (gen/let [elem g/gen-scalar-type
                                       vals (gen/vector (g/gen-scalar-value elem) 0 8)]
                               [elem vals])]
    (let [param {:type {:kind :slice :const? true :of {:kind :scalar :name elem}}}
          arr   (g/primitive-array elem vals)]
      (with-open [arena (Arena/ofConfined)]
        (let [{:keys [address length]} (#'ffm/marshal-array arena param arr)
              out (#'ffm/read-slice-values (.address ^java.lang.foreign.MemorySegment address)
                                           length {:kind :scalar :name elem})]
          (and (= (count vals) (count out))
               (every? true? (map #(value=? elem %1 %2) vals out))))))))

;; --- Enums --------------------------------------------------------------

(defn- enum-ret [desc] {:kind :named :layout desc})

(defspec enum-member-marshals-to-int-and-back 200
  (prop/for-all [members g/gen-enum-members]
    (let [desc (layout/describe-enum 'E members)]
      (every? (fn [{:keys [name]}]
                (let [kw (keyword (str name))]
                  (with-open [arena (Arena/ofConfined)]
                    (let [carrier (first (:carriers (#'ffm/marshal-arg
                                                     arena {:type (enum-ret desc)} kw)))]
                      (= kw (#'ffm/from-return (enum-ret desc) (long carrier)))))))
              (:values desc)))))

(defspec enum-unknown-member-is-rejected 200
  (prop/for-all [members g/gen-enum-members]
    (let [desc (layout/describe-enum 'E members)]
      (= :zigar/unknown-enum-member
         (code-from #(with-open [arena (Arena/ofConfined)]
                       (#'ffm/marshal-arg arena {:type (enum-ret desc)} :zzz-not-a-member)))))))

(defspec enum-unknown-int-returns-the-raw-int 200
  (prop/for-all [members g/gen-enum-members]
    (let [desc    (layout/describe-enum 'E members)
          unknown (inc (apply max (map :value (:values desc))))]
      (= unknown (#'ffm/from-return (enum-ret desc) (long unknown))))))
