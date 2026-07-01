(ns clj-zig.module-cache-prop-test
  "Module fingerprint properties (ADR 34). The directory signature is a
  cheap, order-independent function of file stats; the content fingerprint
  is an order-independent function of file contents; the fingerprint is
  memoized behind the signature and recomputed only when the signature
  changes; and the fingerprint enters a dependent function's cache key,
  order-independently across the modules a namespace declares."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clj-zig.cache :as cache]
            [clj-zig.gen :as g]))

;; --- Directory signature: cheap, order-independent, stat-sensitive ------

(defspec dir-signature-is-order-independent 200
  (prop/for-all [tree g/gen-module-tree]
    (let [stats (g/tree->stats tree)]
      (= (cache/dir-signature stats)
         (cache/dir-signature (shuffle stats))))))

(defspec dir-signature-changes-when-a-stat-changes 200
  (prop/for-all [tree g/gen-module-tree]
    (let [stats (g/tree->stats tree)]
      (not= (cache/dir-signature stats)
            (cache/dir-signature (update-in stats [0 :size] inc))))))

;; --- Content fingerprint: sixteen hex, order-independent, content-sensitive ---

(defspec content-fingerprint-is-sixteen-hex-and-order-independent 200
  (prop/for-all [tree g/gen-module-tree]
    (let [contents (g/tree->contents tree)]
      (and (re-matches #"[0-9a-f]{16}" (cache/content-fingerprint contents))
           (= (cache/content-fingerprint contents)
              (cache/content-fingerprint (shuffle contents)))))))

(defspec content-fingerprint-changes-when-content-changes 200
  (prop/for-all [tree g/gen-module-tree]
    (let [contents (g/tree->contents tree)]
      (not= (cache/content-fingerprint contents)
            (cache/content-fingerprint (update-in contents [0 :content] str "x"))))))

;; --- Memoization: reuse on an unchanged signature, recompute otherwise --

(defspec memoized-fingerprint-reuses-without-recomputing 200
  (prop/for-all [sig gen/string-alphanumeric
                 fp  gen/string-alphanumeric]
    (let [calls   (atom 0)
          [out _] (cache/memoized-fingerprint {:signature sig :fingerprint fp} sig
                                              (fn [] (swap! calls inc) "recomputed"))]
      (and (= fp out) (zero? @calls)))))

(defspec memoized-fingerprint-recomputes-on-a-changed-signature 200
  (prop/for-all [old-sig gen/string-alphanumeric
                 new-sig gen/string-alphanumeric
                 old-fp  gen/string-alphanumeric]
    (let [calls       (atom 0)
          [out entry] (cache/memoized-fingerprint {:signature old-sig :fingerprint old-fp}
                                                  new-sig
                                                  (fn [] (swap! calls inc) "fresh"))]
      (if (= old-sig new-sig)
        (and (= old-fp out) (zero? @calls))
        (and (= "fresh" out) (= 1 @calls)
             (= {:signature new-sig :fingerprint "fresh"} entry))))))

;; --- Cache-key participation: the fingerprint enters the key ------------

(def ^:private gen-fingerprint
  (gen/fmap #(apply str %) (gen/vector gen/char-alphanumeric 16 16)))

(def ^:private gen-modules-map
  (gen/map (gen/fmap #(str "m" %) (gen/choose 0 20)) gen-fingerprint {:max-elements 4}))

(defn- inputs [body modules]
  {:spec 'app.core/f :body body :source (str "src:" body) :deps nil
   :options {:optimize "ReleaseSafe"} :zig-version "0.16.0"
   :target "macos-aarch64" :modules modules})

(defspec module-fingerprints-enter-the-cache-key 200
  (prop/for-all [body    gen/string-alphanumeric
                 modules (gen/not-empty gen-modules-map)]
    (not= (cache/cache-key (inputs body nil))
          (cache/cache-key (inputs body modules)))))

(defspec cache-key-is-independent-of-module-entry-order 200
  (prop/for-all [body    gen/string-alphanumeric
                 modules gen-modules-map]
    (= (cache/cache-key (inputs body modules))
       (cache/cache-key (inputs body (into (sorted-map-by #(compare %2 %1)) modules))))))
