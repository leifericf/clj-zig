(ns zigar.normalization-prop-test
  "Properties of type and signature normalization. The example tests pin
  specific shapes; these assert the laws that must hold across the whole
  generated space: normalization is deterministic, stable under a
  render-and-renormalize round-trip, and preserves the argument bindings,
  arity, and return type a signature carries."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [zigar.gen :as g]
            [zigar.signature :as signature]
            [zigar.type :as type]))

;; --- Type normalization ------------------------------------------------

(defspec type-normalization-is-deterministic 200
  (prop/for-all [form (g/gen-type-form)]
    (= (type/normalize form) (type/normalize form))))

(defspec type-normalization-round-trips 200
  ;; Normalizing, rendering back to a form, and normalizing again is the
  ;; identity on the normalized value: the canonical shape is a fixpoint.
  (prop/for-all [form (g/gen-type-form)]
    (let [t (type/normalize form)]
      (= t (type/normalize (g/normalized->form t))))))

(defspec type-head-determines-kind 200
  (prop/for-all [form (g/gen-type-form)]
    (let [t (type/normalize form)]
      (cond
        (keyword? form) (= :scalar (:kind t))
        (symbol? form)  (= :named (:kind t))
        (vector? form)  (= (first form) (case (:kind t)
                                          (:slice :ptr :manyptr :array
                                           :optional :owned :borrowed
                                           :handle :error-union) (:kind t)
                                          (first form)))))))

;; --- Signature normalization -------------------------------------------

(defspec signature-normalization-is-deterministic 200
  (prop/for-all [sig g/gen-signature]
    (= (signature/normalize sig) (signature/normalize sig))))

(defspec signature-preserves-bindings-and-arity 200
  (prop/for-all [sig g/gen-signature]
    (let [{:keys [args ret]} (signature/normalize sig)
          pairs (partition 2 (subvec sig 0 (- (count sig) 2)))]
      (and (= (count pairs) (count args))
           (= (mapv first pairs) (mapv :binding args))
           (= (mapv second pairs) (mapv :type args))
           (= (peek sig) ret)))))

(defspec signature-round-trips 200
  ;; Rebuilding the signature vector from the normalized data and
  ;; normalizing again yields the same data.
  (prop/for-all [sig g/gen-signature]
    (let [n (signature/normalize sig)
          rebuilt (conj (vec (mapcat (fn [{:keys [binding type]}] [binding type])
                                     (:args n)))
                        :ret (:ret n))]
      (= n (signature/normalize rebuilt)))))
