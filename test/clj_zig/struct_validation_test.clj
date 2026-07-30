(ns clj-zig.struct-validation-test
  "Adversarial probes for struct argument validation at the boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :refer [defnz defrecordz]]))

(defrecordz Pt [x :f64 y :f64])

(defnz probe-midpoint
  [a Pt b Pt :ret Pt]
  "return .{ .x = (a.x + b.x) / 2.0, .y = (a.y + b.y) / 2.0 };")

(defn- error-code [e]
  (:error/code (ex-data e)))

(deftest nil-struct-arg-rejected
  (testing "nil for struct Pt"
    (is (= :clj-zig/nil-argument
           (error-code (try (probe-midpoint nil (->Pt 4.0 6.0))
                            (catch Exception e e)))))))

(deftest wrong-type-struct-arg-rejected
  (testing "long for struct Pt"
    (is (= :clj-zig/type-mismatch
           (error-code (try (probe-midpoint 42 (->Pt 4.0 6.0))
                            (catch Exception e e))))))
  (testing "string for struct Pt"
    (is (= :clj-zig/type-mismatch
           (error-code (try (probe-midpoint "bad" (->Pt 4.0 6.0))
                            (catch Exception e e)))))))

(deftest valid-struct-arg-still-works
  (is (= {:x 2.0 :y 3.0}
         (into {} (probe-midpoint (->Pt 0.0 0.0) (->Pt 4.0 6.0))))))
