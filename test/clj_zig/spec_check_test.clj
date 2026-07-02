(ns clj-zig.spec-check-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :refer [defnz]]
            [clj-zig.spec-check :as sc]))

(deftest spec-for-type-maps-scalars
  (is (= 'int? (sc/spec-for-type {:kind :scalar :name :i64})))
  (is (= 'double? (sc/spec-for-type {:kind :scalar :name :f64})))
  (is (= 'boolean? (sc/spec-for-type {:kind :scalar :name :bool})))
  (is (= 'nil? (sc/spec-for-type {:kind :scalar :name :void}))))

(deftest spec-for-type-maps-string
  (is (= 'string? (sc/spec-for-type {:kind :string}))))

(deftest spec-for-type-maps-bytes
  (is (= 'bytes? (sc/spec-for-type {:kind :bytes}))))

(deftest spec-for-type-maps-slice
  (is (= '(clojure.spec.alpha/coll-of int?)
         (sc/spec-for-type {:kind :slice :const? true
                            :of {:kind :scalar :name :u8}}))))

(deftest spec-for-type-maps-optional
  (is (= '(clojure.spec.alpha/nilable int?)
         (sc/spec-for-type {:kind :optional
                            :of {:kind :scalar :name :i64}}))))

(is (= '(clojure.spec.alpha/nilable double?)
       (sc/spec-for-type {:kind :optional
                          :of {:kind :scalar :name :f64}})))

(deftest spec-for-type-maps-handle
  (is (= 'some? (sc/spec-for-type {:kind :handle
                                   :of {:kind :named :name 'Box}}))))

(deftest spec-for-type-maps-named-struct
  (is (= 'map? (sc/spec-for-type {:kind :named :name 'Point
                                  :layout {:fields []}}))))

(deftest spec-for-type-maps-named-enum
  (let [t {:kind :named :name 'Status
           :layout {:enum true
                    :values [{:name 'ok :value 0} {:name 'err :value 1}]}}
        spec (sc/spec-for-type t)]
    (is (set? spec))
    (is (= #{:ok :err} spec))))

(deftest spec-for-type-maps-owned-slice
  (is (= '(clojure.spec.alpha/coll-of int?)
         (sc/spec-for-type {:kind :owned
                            :of {:kind :slice
                                 :of {:kind :scalar :name :i64}}}))))

(deftest spec-for-type-maps-error-union
  (is (= 'int?
         (sc/spec-for-type {:kind :error-union
                            :error 'MyError
                            :of {:kind :scalar :name :i64}}))))

(deftest spec-for-param-maps-slice-arg
  (let [param {:binding 'xs :type {:kind :slice :const? true
                                   :of {:kind :scalar :name :i64}}}]
    (is (= '(clojure.spec.alpha/or :array array? :coll coll?)
           (sc/spec-for-param param)))))

(deftest spec-for-param-maps-scalar-arg
  (let [param {:binding 'x :type {:kind :scalar :name :i64}}]
    (is (= 'int? (sc/spec-for-param param)))))

(deftest opt-in-via-attr-map-does-not-error
  (testing ":clj-zig/spec true in the attr-map calls register! at definition time"
    (eval `(defnz ~'spec-optin-fn
             {:clj-zig/spec true}
             [~'x :i64 :ret :i64]
             "return x + 1;"))
    (is (= 6 ((resolve 'spec-optin-fn) 5)))))
