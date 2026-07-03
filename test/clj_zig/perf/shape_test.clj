(ns clj-zig.perf.shape-test
  "Tier-0 unit tests for the pure perf shape data. Asserts the seven
  canonical contract kinds are present, each record is well-formed pure
  data with the required keys, the bodies are trivial, the floor
  descriptors carry C ABI layouts in the clj-zig.foreign c-* shorthand
  vocabulary, and the namespace source requires neither Criterium nor
  any clj-zig native namespace (ADR 16)."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.perf.shape :as shape]))

(def ^:private expected-kinds
  "The seven canonical contract kinds (confirmed against
  test/clj_zig/fixtures.clj). error-union is treated as a variant of
  owned/struct return, NOT a separate canonical shape."
  #{:scalar-passthrough :struct-by-value :enum :slice-arg
    :string :owned-return :handle})

(def ^:private c-layout-vocabulary
  "The set of layout keywords a floor descriptor may use. Each one is
  the keyword spelling of a clj-zig.foreign c-* shorthand (or :void);
  the bench shell resolves the keyword to the ValueLayout var at bind
  time, keeping the pure core free of clj-zig.foreign."
  #{:c-byte :c-short :c-int :c-long :c-float :c-double :c-ptr :void})

(deftest seven-canonical-kinds-present
  (is (= expected-kinds (set (keys shape/shapes)))
      "shapes keyed by canonical kind")
  (is (= expected-kinds (set (map :kind (shape/shape-list))))
      "every shape's :kind matches a canonical kind"))

(deftest every-shape-carries-required-keys
  (doseq [s (shape/shape-list)]
    (testing (str "shape " (:kind s))
      (doseq [k shape/required-shape-keys]
        (is (contains? s k) (str "shape " (:kind s) " missing " k)))
      (doseq [k shape/required-floor-keys]
        (is (contains? (:floor s) k)
            (str "shape " (:kind s) " floor missing " k))))))

(deftest kind-matches-map-key
  (doseq [[k s] shape/shapes]
    (is (= k (:kind s))
        (str "the " k " entry's :kind field must match its key"))))

(deftest shape-list-has-canonical-order
  (is (= shape/shape-order (mapv :kind (shape/shape-list)))))

(deftest names-and-signatures-are-pure-data
  (doseq [s (shape/shape-list)]
    (testing (str "shape " (:kind s))
      (is (string? (:name s)) "name is a string")
      (is (vector? (:signature s)) "signature is a vector (defnz form)")
      (is (= :ret (last (butlast (:signature s))))
          "signature has shape [<param>... :ret <type>]")
      (is (string? (:body s)) "body is a string of Zig source"))))

(deftest bodies-are-trivial
  ;; The body-leak guard in clj-zig.perf.stats detects a heavy floor at
  ;; measurement time. Here we assert only that the source is short and
  ;; free of algorithmic work: the slice sum's reduction loop is the
  ;; most the body does. The string, owned, and handle bodies allocate
  ;; because their CONTRACTS require a buffer or a Box; the c_allocator
  ;; calls in those bodies are the minimal contract-required allocation
  ;; and not a defect -- the body-leak guard will flag those shapes'
  ;; measurements as :body-leak-suspect, which is the expected outcome.
  (doseq [s (shape/shape-list)]
    (testing (str "body of " (:kind s))
      (is (< (count (:body s)) 240))
      ;; No nested function definitions, no structs declared inside the
      ;; body, no while loops. The slice sum's for-loop is the only
      ;; iteration permitted.
      (is (not (re-find #"\bfn\b" (:body s)))
          "body declares no nested fns")
      (is (not (re-find #"\bwhile\b" (:body s)))
          "body has no while loops"))))

(deftest arg-fn-arity-matches-signature
  (doseq [s (shape/shape-list)]
    (testing (str "arg-fn of " (:kind s))
      (let [args  ((:arg-fn s))
            sig   (:signature s)
            ;; The defnz form is [binding type binding type ... :ret type]
            ;; where a type may itself be a symbol (a named type like
            ;; Point/Suit/Box). Bindings are the first of each pair
            ;; before :ret.
            forms   (take-while #(not= % :ret) sig)
            binding-count (-> forms count (quot 2))]
        (is (= binding-count (count args))
            (str "arg-fn returned " (count args) " args for a "
                 binding-count "-param signature"))))))

(deftest floor-descriptors-use-c-shorthand-vocabulary
  (doseq [s (shape/shape-list)
          :let [floor (:floor s)]]
    (testing (str "floor of " (:kind s))
      (is (string? (:name floor)) "floor :name is a string")
      (is (contains? c-layout-vocabulary (:ret floor))
          (str "floor :ret " (:ret floor) " is not in the c-* vocabulary"))
      (is (seq (:args floor)) "floor :args is non-empty")
      (doseq [a (:args floor)]
        (is (contains? c-layout-vocabulary a)
            (str "floor arg " a " is not in the c-* vocabulary")))
      (when (contains? floor :free-shim)
        (is (string? (:free-shim floor)))))))

(deftest setup-declarations-are-pure-data
  (doseq [s (shape/shape-list)
          decl (:setup s)]
    (testing (str "setup of " (:kind s))
      (is (contains? #{:deftypez :defrecordz :defenumz :defz} (:kind decl)))
      (is (symbol? (:name decl)) "setup decl names a symbol")
      (case (:kind decl)
        :deftypez (is (vector? (:fields decl)))
        :defrecordz (is (vector? (:fields decl)))
        :defenumz (is (vector? (:members decl)))
        :defz (is (string? (:body decl)))))))

(deftest shape-source-is-pure
  ;; Source-level purity check: the pure core must not require Criterium
  ;; or any clj-zig native namespace. The matchers target the
  ;; `:require` vector syntax (`[clj-zig.X :as ...]`, `[criterium.X ..]`)
  ;; so docstring prose mentioning the namespaces by name does not trip
  ;; them. The runtime classpath check that Criterium itself is not
  ;; resolvable under :test lands in p1-t3.
  (let [src (slurp "bench/clj_zig/perf/shape.clj")]
    (is (not (re-find #"\[\s*clj-zig\.core\b" src))
        "shape.clj must not require clj-zig.core")
    (is (not (re-find #"\[\s*clj-zig\.ffm\b" src))
        "shape.clj must not require clj-zig.ffm")
    (is (not (re-find #"\[\s*clj-zig\.foreign\b" src))
        "shape.clj must not require clj-zig.foreign")
    (is (not (re-find #"\[\s*criterium" src))
        "shape.clj must not require Criterium")))
