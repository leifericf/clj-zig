(ns clj-zig.scalar-validation-test
  "Adversarial probes for scalar argument validation at the boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.ffm :as ffm]))

(defn- coerce-int [v]
  (#'ffm/to-carrier {:binding 'x :type {:kind :scalar :name :i64}} v))

(defn- coerce-float [v]
  (#'ffm/to-carrier {:binding 'x :type {:kind :scalar :name :f64}} v))

(defn- coerce-bool [v]
  (#'ffm/to-carrier {:binding 'b :type {:kind :scalar :name :bool}} v))

(defn- coerce-via-hot-path [type-name v]
  (let [coerce (#'ffm/scalar-param-coerce
                {:binding 'x :type {:kind :scalar :name type-name}})]
    (coerce v)))

(defn- error-code [e]
  (:error/code (ex-data e)))

(deftest nil-scalar-rejected
  (testing "nil for :i64 via to-carrier"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"cannot be nil"
         (coerce-int nil))))
  (is (= :clj-zig/nil-argument
         (error-code (try (coerce-int nil) (catch Exception e e)))))
  (testing "nil for :f64 via to-carrier"
    (is (= :clj-zig/nil-argument
           (error-code (try (coerce-float nil) (catch Exception e e))))))
  (testing "nil for :bool via to-carrier"
    (is (= :clj-zig/nil-argument
           (error-code (try (coerce-bool nil) (catch Exception e e))))))
  (testing "nil for :i64 via scalar-param-coerce hot path"
    (is (= :clj-zig/nil-argument
           (error-code (try (coerce-via-hot-path :i64 nil) (catch Exception e e))))))
  (testing "nil for :f64 via scalar-param-coerce hot path"
    (is (= :clj-zig/nil-argument
           (error-code (try (coerce-via-hot-path :f64 nil) (catch Exception e e)))))))

(deftest wrong-type-scalar-rejected
  (testing "string for :i64 via to-carrier"
    (is (= :clj-zig/type-mismatch
           (error-code (try (coerce-int "foo") (catch Exception e e))))))
  (testing "string for :f64 via to-carrier"
    (is (= :clj-zig/type-mismatch
           (error-code (try (coerce-float "hello") (catch Exception e e))))))
  (testing "string for :i64 via scalar-param-coerce hot path"
    (is (= :clj-zig/type-mismatch
           (error-code (try (coerce-via-hot-path :i64 "foo") (catch Exception e e))))))
  (testing "string for :f64 via scalar-param-coerce hot path"
    (is (= :clj-zig/type-mismatch
           (error-code (try (coerce-via-hot-path :f64 "hello") (catch Exception e e)))))))
