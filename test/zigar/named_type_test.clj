(ns zigar.named-type-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zigar :as zig]
            [zigar.core :refer [defnz deftypez]]))

(deftypez Point
  "A 2D point shared between Clojure and Zig."
  [x :f64
   y :f64])

(deftest deftypez-registers-a-layout-descriptor
  (testing "the Var holds the layout descriptor"
    (is (= 'Point (:name Point)))
    (is (= [0 8] (mapv :offset (:fields Point))))
    (is (= 16 (:size Point))))
  (testing "the descriptor is marked on the Var"
    (is (:zigar/type-layout (meta #'Point)))
    (is (= "A 2D point shared between Clojure and Zig." (:doc (meta #'Point))))))

;; A scalar function defined in the same namespace carries the struct
;; declaration in its preamble, so a later struct-using function can rely
;; on it being in scope.
(defnz scalar-here
  [n :i64
   :ret :i64]
  "return n;")

(deftest a-namespace-type-appears-in-the-generated-preamble
  (is (str/includes? (zig/generated-source #'scalar-here)
                     "const Point = extern struct {")))

(deftest build-spec-resolves-named-references
  (testing "a declared type resolves to its layout"
    (let [spec (zig/build-spec {:ns 'app :name 'mid
                                :signature '[p Point :ret :f64]
                                :types {'Point Point}})]
      (is (= 'Point (-> spec :params first :type :name)))
      (is (map? (-> spec :params first :type :layout)))))
  (testing "an undeclared type is a clear diagnostic"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no deftypez"
                          (zig/build-spec '{:ns app :name f
                                            :signature [p Nope :ret :f64]})))))
