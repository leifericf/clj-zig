(ns zigar.spec-test
  (:require [clojure.test :refer [deftest is testing]]
            [zigar.spec :as spec]))

(defn- error-code [f]
  (try
    (f)
    ::no-throw
    (catch clojure.lang.ExceptionInfo e
      (:error/code (ex-data e)))))

(def add-spec
  (spec/build-spec '{:ns app.core :name add
                     :signature [x :i64 y :i64 :ret :i64]}))

(deftest builds-scalar-spec
  (testing "params carry normalized types in order"
    (is (= '[{:binding x :type {:kind :scalar :name :i64}}
             {:binding y :type {:kind :scalar :name :i64}}]
           (:params add-spec))))
  (testing "the return type is normalized"
    (is (= {:kind :scalar :name :i64} (:ret add-spec))))
  (testing "identity and the original signature are preserved for inspection"
    (is (= 'app.core (:ns add-spec)))
    (is (= 'add (:name add-spec)))
    (is (= '[x :i64 y :i64 :ret :i64] (:signature add-spec)))))

(deftest computes-stable-collision-free-symbol
  (is (= "zigar_app_2e_core_add" (:symbol add-spec)))
  (testing "the same identity always yields the same symbol"
    (is (= (:symbol add-spec)
           (:symbol (spec/build-spec '{:ns app.core :name add
                                       :signature [a :i64 :ret :i64]})))))
  (testing "characters that are illegal in C identifiers are escaped, not collapsed"
    (is (= "zigar_my_2d_ns_do_2d_thing"
           (spec/symbol-name 'my-ns 'do-thing)))
    (is (not= (spec/symbol-name 'a-b 'c)
              (spec/symbol-name 'a_b 'c)))))

(deftest normalizes-compound-argument-types
  (let [s (spec/build-spec '{:ns app.core :name sum
                             :signature [xs [:slice :const :f64] :ret :f64]})]
    (is (= {:kind :slice :const? true :of {:kind :scalar :name :f64}}
           (-> s :params first :type)))))

(deftest void-return-is-allowed
  (is (= {:kind :scalar :name :void}
         (:ret (spec/build-spec '{:ns app.core :name noop
                                  :signature [:ret :void]})))))

(deftest rejects-unsupported-128-bit-carriers
  (testing "as an argument"
    (is (= :zigar/unsupported-carrier
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [x :u128 :ret :i64]})))))
  (testing "as a return"
    (is (= :zigar/unsupported-carrier
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [x :i64 :ret :i128]})))))
  (testing "nested inside a compound"
    (is (= :zigar/unsupported-carrier
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [xs [:slice :u128] :ret :i64]}))))))

(deftest rejects-floats-without-an-ffm-carrier
  (testing "stable FFM carries only 32- and 64-bit floats"
    (is (= :zigar/unsupported-carrier
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [x :f16 :ret :f64]}))))
    (is (= :zigar/unsupported-carrier
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [x :f64 :ret :f128]}))))
    (is (= :zigar/unsupported-carrier
           (error-code #(spec/build-spec '{:ns app.core :name f
                                           :signature [x :f80 :ret :f64]}))))))

(deftest rejects-void-argument
  (is (= :zigar/void-argument
         (error-code #(spec/build-spec '{:ns app.core :name f
                                         :signature [x :void :ret :i64]})))))

(deftest expands-clojure-side-destructuring-into-native-params
  (testing "each destructured local becomes a native scalar param (ADR 13)"
    (let [s (spec/build-spec
             '{:ns app.core :name distance
               :signature [{x1 :x y1 :y} {:x :f64 :y :f64}
                           {x2 :x y2 :y} {:x :f64 :y :f64}
                           :ret :f64]})]
      (testing "one native param per destructured local"
        (is (= 4 (count (:params s))))
        (is (= '#{x1 y1 x2 y2} (set (map :binding (:params s))))))
      (is (every? #(= {:kind :scalar :name :f64} (:type %)) (:params s)))
      (testing "each param records which argument and field it came from"
        (is (= #{{:arg 0 :field :x} {:arg 0 :field :y}
                 {:arg 1 :field :x} {:arg 1 :field :y}}
               (set (map :destructured-from (:params s)))))))))

(deftest rejects-unknown-destructuring-field
  (is (= :zigar/unknown-field
         (error-code #(spec/build-spec
                       '{:ns app.core :name f
                         :signature [{a :x} {:y :f64} :ret :f64]})))))

(deftest diagnostic-carries-the-var-and-signature
  (try
    (spec/build-spec '{:ns app.core :name f :signature [x :u128 :ret :i64]})
    (is false "expected a diagnostic")
    (catch clojure.lang.ExceptionInfo e
      (is (= 'app.core/f (:var (ex-data e))))
      (is (= '[x :u128 :ret :i64] (:signature (ex-data e)))))))
