(ns clj-zig.munge-cache-prop-test
  "Properties of symbol munging and content-addressed cache keys. A munged
  symbol must always be a legal C identifier and must never collapse two
  distinct Vars onto the same name. A cache key must be deterministic,
  independent of map and set iteration order, and sensitive to a change in
  any single input field."
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clj-zig.cache :as cache]
            [clj-zig.spec :as spec]))

;; --- Symbol munging -----------------------------------------------------

(def gen-name-string
  "A namespace or name fragment with the awkward characters munging must
  escape: dots, dashes, slashes, punctuation, spaces, and non-ASCII."
  (gen/fmap (partial apply str)
            (gen/not-empty
             (gen/vector (gen/one-of [gen/char-alphanumeric
                                      (gen/elements [\. \- \_ \/ \! \? \* \+ \< \>
                                                     \space \é \ñ \λ \Ω])])))))

(def c-identifier #"[A-Za-z_][A-Za-z0-9_]*")

(defspec munged-symbol-is-a-c-identifier 300
  (prop/for-all [ns-s gen-name-string
                 nm-s gen-name-string]
    (boolean (re-matches c-identifier (spec/symbol-name ns-s nm-s)))))

(defspec munged-symbols-are-injective 200
  (prop/for-all [pairs (gen/vector-distinct (gen/tuple gen-name-string gen-name-string)
                                            {:max-elements 16})]
    ;; Distinct (ns, name) pairs never collide on the same C symbol.
    (let [syms (map (fn [[n m]] (spec/symbol-name n m)) pairs)]
      (= (count pairs) (count (distinct syms))))))

(defspec munging-is-deterministic 200
  (prop/for-all [ns-s gen-name-string
                 nm-s gen-name-string]
    (= (spec/symbol-name ns-s nm-s) (spec/symbol-name ns-s nm-s))))

;; --- Cache keys ---------------------------------------------------------

(def gen-cache-input
  (gen/hash-map
   :spec        (gen/map gen/keyword gen/small-integer)
   :body        gen/string-ascii
   :source      gen/string-ascii
   :deps        (gen/set gen/keyword)
   :options     (gen/map gen/keyword gen/string-ascii)
   :zig-version gen/string-ascii
   :target      (gen/elements ["macos-aarch64" "linux-x86_64" "windows-x86_64"])))

(defn- reordered
  "Rebuild every nested map and set in a fresh, shuffled iteration order, so
  an order-sensitive hash would diverge."
  [x]
  (cond
    (map? x) (into {} (shuffle (mapv (fn [[k v]] [k (reordered v)]) x)))
    (set? x) (into #{} (shuffle (vec x)))
    :else    x))

(defspec cache-key-is-deterministic 200
  (prop/for-all [in gen-cache-input]
    (= (cache/cache-key in) (cache/cache-key in))))

(defspec cache-key-ignores-iteration-order 200
  (prop/for-all [in gen-cache-input]
    (= (cache/cache-key in) (cache/cache-key (reordered in)))))

(defspec cache-key-is-sensitive-to-every-field 300
  (prop/for-all [in gen-cache-input
                 k  (gen/elements [:spec :body :source :deps :options
                                   :zig-version :target])]
    ;; The sentinel is a value the generators never produce, so the input
    ;; genuinely changes and the key must change with it.
    (not= (cache/cache-key in) (cache/cache-key (assoc in k ::changed)))))
