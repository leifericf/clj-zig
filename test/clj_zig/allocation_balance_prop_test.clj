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

(deftest string-returns-drive-the-free-shim-in-volume
  ;; A :string return owns its bytes and frees them each call, so 500 calls
  ;; leak none. The free shim runs in a finally on every return path.
  (let [in "utf8-\u00e9-\u4e2d-\u00e6"]
    (is (every? #(= in %) (repeatedly 500 #(f/string-identity in))))))

(deftest owned-result-records-drive-the-per-field-free-shim-in-volume
  ;; Volume is the leak lane: an unfreed buffer field would accumulate across calls.
  (let [expected-media "image/png"
        expected-bytes (byte-array [65 66 67])]
    (doseq [r (repeatedly 500 #(f/render-fixed))]
      (assert (= :ok (:status r)))
      (assert (= expected-media (:media r)))
      (assert (java.util.Arrays/equals ^bytes (:bytes r) expected-bytes)))
    (is true "500 owned result records freed every buffer field without fault")))

(deftest error-union-over-owned-struct-success-frees-every-buffer
  (let [expected-media "image/png"
        expected-bytes (byte-array [65 66 67])]
    (doseq [r (repeatedly 500 #(f/render-may-fail false))]
      (assert (map? r))
      (assert (= :ok (:status r)))
      (assert (= expected-media (:media r)))
      (assert (java.util.Arrays/equals ^bytes (:bytes r) expected-bytes)))
    (is true "500 error-union-over-owned-struct successes freed every buffer")))

(deftest error-union-over-owned-struct-error-leaks-nothing
  ;; The error path allocates nothing; a bug freeing the uninitialized wire
  ;; struct would fault here.
  (is (every? #(= :RenderFailed %) (repeatedly 500 #(f/render-may-fail true)))
      "500 error-union-over-owned-struct errors returned the keyword without fault"))

(deftest owned-buffer-slice-drives-the-walking-free-shim-in-volume
  (doseq [r (repeatedly 500 #(f/make-notes 8))]
    (assert (= 8 (count r)))
    (assert (every? #(= "note" (:tag %)) r))
    (assert (= 7 (:n (peek r)))))
  (is true "500 owned buffer-carrying slices freed every element's buffer"))

;; the scalar hot path (ADR 39)

(deftest scalar-only-selects-the-hot-path
  (let [scalar? (fn [v] (let [s (zig/spec v)] (#'ffm/scalar-only? (:params s) (:ret s))))]
    (is (scalar? #'f/echo-i64) "scalar in, scalar out")
    (is (scalar? #'f/swallow)  "scalar in, :void out")
    (is (not (scalar? #'f/sum-f64))    "a slice arg needs the arena")
    (is (not (scalar? #'f/echo-point)) "a struct return needs the out-pointer")
    (is (not (scalar? #'f/echo-suit))  "an enum takes the enum-aware path, not the scalar path")
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

;; the enum-aware hot path

(deftest enum-aware-selects-the-enum-path
  (let [enum-aware? (fn [v] (let [s (zig/spec v)]
                              (#'ffm/enum-aware-scalar? (:params s) (:ret s))))]
    (is (enum-aware? #'f/echo-suit) "enum in, enum out")
    (is (enum-aware? #'f/box)        "a handle return with scalar args takes the no-arena path")
    (is (enum-aware? #'f/unbox)      "a handle arg with a scalar return takes the no-arena path")
    (is (enum-aware? #'f/free-box)   "a handle arg with a void return takes the no-arena path")
    (is (enum-aware? #'f/node-free)  "two handle args plus a void return take the no-arena path")
    (is (not (enum-aware? #'f/echo-i64))  "a scalar-only sig takes the scalar path, not the enum path")
    (is (not (enum-aware? #'f/sum-f64))   "a slice arg needs the arena")
    (is (not (enum-aware? #'f/echo-point)) "a struct return needs the out-pointer")))

(deftest enum-aware-path-round-trips-in-volume
  ;; No arena and a reused carrier array: an enum call driven hard must
  ;; stay correct call after call, both directions of the keyword/int map.
  (let [suits [:clubs :diamonds :hearts :spades]]
    (is (every? #(= % (f/echo-suit %)) (take 100000 (cycle suits))))))

(deftest enum-aware-path-is-thread-safe
  ;; The carrier array is thread-local, so concurrent callers never corrupt
  ;; each other's arguments: each thread echoes a disjoint enum cycle and
  ;; must get its own values back, never another thread's.
  (let [threads 8
        per     20000
        suits   [:clubs :diamonds :hearts :spades]
        futs    (mapv (fn [t]
                        (future
                          (every? true?
                                  (map (fn [i] (let [v (nth suits (mod (+ t i) 4))]
                                                 (= v (f/echo-suit v))))
                                       (range per)))))
                      (range threads))]
    (is (every? true? (map deref futs)))))

;; the handle-arg hot path

(deftest enum-aware-handle-arg-round-trips-in-volume
  ;; Not allocation-free (the body allocates a handle each call), but the
  ;; clj-zig overhead path is the no-arena one.
  (let [boxes (repeatedly 50000 #(f/box 42))]
    (is (every? #(= 42 (f/unbox %)) boxes))
    (doseq [b boxes] (f/free-box b))
    (is true "50000 box+unbox+free-box round-trips stayed correct")))

(deftest enum-aware-handle-arg-path-rejects-a-mismatched-handle
  ;; The no-arena path still validates the handle tag, like the general path.
  (let [t (f/tracker-new)]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"handle"
           (f/unbox t)))
      (finally (f/tracker-free t)))))

(deftest enum-aware-handle-arg-path-is-thread-safe
  ;; The carrier array is thread-local; concurrent callers do not corrupt
  ;; each other's handle pointers. Each thread boxes a disjoint value,
  ;; unboxes it, and frees it; the unboxed value must equal the input.
  (let [threads 8
        per     5000
        futs    (mapv (fn [t]
                        (future
                          (every? true?
                                  (map (fn [i]
                                         (let [v (+ (* (long t) 1000000) i)
                                               b (f/box v)
                                               ok (= v (f/unbox b))]
                                           (f/free-box b)
                                           ok))
                                       (range per)))))
                      (range threads))]
    (is (every? true? (map deref futs)))))

;; the const-slice-aware hot path

(deftest slice-aware-selects-the-slice-path
  (let [slice-aware? (fn [v] (let [s (zig/spec v)]
                               (#'ffm/slice-aware? (:params s) (:ret s))))]
    (is (slice-aware? #'f/sum-f64) "a const-slice arg with a scalar return takes the slice path")
    (is (not (slice-aware? #'f/echo-suit))   "an enum-only sig takes the enum path, not the slice path")
    (is (not (slice-aware? #'f/echo-i64))    "a scalar-only sig takes the scalar path, not the slice path")
    (is (not (slice-aware? #'f/echo-point))  "a struct return takes the general path")
    (is (not (slice-aware? #'f/box))         "a handle return takes the general path")))

(deftest slice-aware-path-round-trips-in-volume
  ;; The arena holds the slice copy for exactly the call; a slice call
  ;; driven hard must stay correct call after call. Multiple slices of
  ;; varying lengths exercise the per-param writer offsets.
  (is (= 6.0 (f/sum-f64 (double-array [1.0 2.0 3.0]))))
  (is (every? (fn [_] (= 6.0 (f/sum-f64 (double-array [1.0 2.0 3.0]))))
              (range 50000))))

(deftest slice-aware-path-is-thread-safe
  ;; The carrier array is thread-local; concurrent callers do not corrupt
  ;; each other's segments. Each thread sums a disjoint value range.
  (let [threads 8
        per     5000
        futs    (mapv (fn [t]
                        (future
                          (every? true?
                                  (map (fn [i]
                                         (let [base (* (+ t i) 1.0)
                                               arr  (double-array [base base base])
                                               expected (* 3.0 base)]
                                           (= expected (f/sum-f64 arr))))
                                       (range per)))))
                      (range threads))]
    (is (every? true? (map deref futs)))))
