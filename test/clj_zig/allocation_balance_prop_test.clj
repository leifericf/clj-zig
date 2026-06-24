(ns clj-zig.allocation-balance-prop-test
  "The leak lane. A native allocation tracker, threaded as a handle so all
  the libraries share one heap counter, turns leak-freedom into a property:
  after any random sequence of node creations and frees, the live count
  matches the model and returns to zero once everything is freed. A
  deterministic check confirms the counter actually counts, so the property
  is not vacuous. Owned returns are exercised in volume to drive the free
  shim repeatedly."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clj-zig :as zig]
            [clj-zig.ffm :as ffm]
            [clj-zig.fixtures :as f]
            [clj-zig.gen :as g]))

(def gen-ops
  (gen/vector (gen/one-of [(gen/fmap (fn [v] [:new v]) (g/gen-scalar-value :i64))
                           (gen/return [:free])])
              0 30))

(defspec live-count-balances-after-random-create-and-free 200
  (prop/for-all [ops gen-ops]
    (let [t (f/tracker-new)]
      (try
        (let [live (atom [])]
          (doseq [op ops]
            (case (first op)
              :new  (swap! live conj (f/node-new t (second op)))
              :free (when (seq @live)
                      (f/node-free t (peek @live))
                      (swap! live pop))))
          (let [tracks-model (= (count @live) (f/tracker-live t))]
            (doseq [n @live] (f/node-free t n))
            (and tracks-model (zero? (f/tracker-live t)))))
        (finally (f/tracker-free t))))))

(defspec created-nodes-carry-their-value 200
  (prop/for-all [v (g/gen-scalar-value :i64)]
    (let [t (f/tracker-new)
          n (f/node-new t v)]
      (try (== v (f/node-get n))
           (finally (f/node-free t n) (f/tracker-free t))))))

(deftest the-counter-actually-counts
  (let [t (f/tracker-new)
        a (f/node-new t 1)
        b (f/node-new t 2)]
    (is (= 2 (f/tracker-live t)))
    (f/node-free t a)
    (is (= 1 (f/tracker-live t)))
    (f/node-free t b)
    (is (= 0 (f/tracker-live t)))
    (f/tracker-free t)))

(deftest owned-returns-drive-the-free-shim-in-volume
  (is (every? #(= [2.0 4.0 6.0] %)
              (repeatedly 500 #(f/owned-double (double-array [1.0 2.0 3.0]))))))

(deftest bytes-returns-drive-the-free-shim-in-volume
  ;; A :bytes return frees its native buffer each call, so 500 calls leak none.
  (let [in (byte-array [10 20 30])]
    (is (every? #(java.util.Arrays/equals ^bytes % in)
                (repeatedly 500 #(f/bytes-echo in))))))

;; --- the scalar hot path (ADR 39) ---------------------------------------
;; A scalar-only signature skips the per-call confined arena and reuses a
;; thread-local carrier array. The selection is exact, the reuse stays
;; correct under volume, and the thread-local array makes concurrent
;; callers safe.

(deftest scalar-only-selects-the-hot-path
  (let [scalar? (fn [v] (let [s (zig/spec v)] (#'ffm/scalar-only? (:params s) (:ret s))))]
    (is (scalar? #'f/echo-i64) "scalar in, scalar out")
    (is (scalar? #'f/swallow)  "scalar in, :void out")
    (is (not (scalar? #'f/sum-f64))    "a slice arg needs the arena")
    (is (not (scalar? #'f/echo-point)) "a struct return needs the out-pointer")
    (is (not (scalar? #'f/echo-suit))  "an enum crosses through the named path")
    (is (not (scalar? #'f/box))        "a handle return takes the general path")))

(deftest scalar-hot-path-round-trips-in-volume
  ;; No arena and a reused carrier array: a scalar call driven hard must
  ;; stay correct call after call.
  (is (every? true? (map #(= % (f/echo-i64 %)) (range 100000)))))

(deftest scalar-hot-path-is-thread-safe
  ;; The carrier array is thread-local, so concurrent callers never corrupt
  ;; each other's arguments: each thread echoes a disjoint value range and
  ;; must get its own values back, never another thread's.
  (let [threads 8
        per     20000
        futs    (mapv (fn [t]
                        (future
                          (every? true?
                                  (map (fn [i] (let [v (+ (* (long t) 1000000) i)]
                                                 (= v (f/echo-i64 v))))
                                       (range per)))))
                      (range threads))]
    (is (every? true? (map deref futs)))))
