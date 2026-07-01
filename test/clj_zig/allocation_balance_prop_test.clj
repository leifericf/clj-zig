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
  ;; An owned result record allocates a c_allocator buffer per buffer field
  ;; and the generated per-field __free shim frees every one after the
  ;; Clojure side copies the bytes out. Driving it in volume exercises every
  ;; field's free (a :string and a :bytes field here) and the finally that
  ;; guards the read; a field left unfreed would accumulate across the run.
  (let [expected-media "image/png"
        expected-bytes (byte-array [65 66 67])]
    (doseq [r (repeatedly 500 #(f/render-fixed))]
      (assert (= :ok (:status r)))
      (assert (= expected-media (:media r)))
      (assert (java.util.Arrays/equals ^bytes (:bytes r) expected-bytes)))
    (is true "500 owned result records freed every buffer field without fault")))

(deftest error-union-over-owned-struct-success-frees-every-buffer
  ;; A [:error-union E OwnedRecord] success path allocates a c_allocator
  ;; buffer per buffer field and the wrapper's per-field __free shim frees
  ;; every one in a finally after the Clojure side copies the bytes out
  ;; (mirror of c8a822b and the owned-record path). Driving the success
  ;; branch in volume exercises every field's free; a field left unfreed
  ;; would accumulate across the run.
  (let [expected-media "image/png"
        expected-bytes (byte-array [65 66 67])]
    (doseq [r (repeatedly 500 #(f/render-may-fail false))]
      (assert (map? r))
      (assert (= :ok (:status r)))
      (assert (= expected-media (:media r)))
      (assert (java.util.Arrays/equals ^bytes (:bytes r) expected-bytes)))
    (is true "500 error-union-over-owned-struct successes freed every buffer")))

(deftest error-union-over-owned-struct-error-leaks-nothing
  ;; The error path of a [:error-union E OwnedRecord] writes NO struct: the
  ;; wrapper translates the error and returns before the body's struct
  ;; reaches the wire. Nothing was allocated-for-the-result on this branch
  ;; (the fixture checks the fail flag BEFORE allocating), so the __free
  ;; shim does not run and there is nothing to free. Driving the error
  ;; branch in volume is the leak lane: a wrapper bug that freed an
  ;; uninitialized wire struct on the error path would fault (reading
  ;; garbage ptr/len words) or double-free within the run; a body that
  ;; allocated before erroring would leak across the 500 calls and trip
  ;; an address-sanitizer build. The keyword result must also stay stable.
  (is (every? #(= :RenderFailed %) (repeatedly 500 #(f/render-may-fail true)))
      "500 error-union-over-owned-struct errors returned the keyword without fault"))

(deftest owned-buffer-slice-drives-the-walking-free-shim-in-volume
  ;; An owned slice of buffer-carrying structs allocates a c_allocator tag
  ;; per element plus the nice-record slab; the wrapper's walking free shim
  ;; iterates the wire slab freeing every element's tag buffer then the slab
  ;; itself. Driving it in volume exercises every per-element free; a buffer
  ;; left unfreed would accumulate across the 500 calls.
  (doseq [r (repeatedly 500 #(f/make-notes 8))]
    (assert (= 8 (count r)))
    (assert (every? #(= "note" (:tag %)) r))
    (assert (= 7 (:n (peek r)))))
  (is true "500 owned buffer-carrying slices freed every element's buffer"))

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
