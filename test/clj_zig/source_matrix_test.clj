(ns clj-zig.source-matrix-test
  "Drive the bounded-exhaustive structural matrix through source generation.
  Every supported signature across the type, position, constness, and
  defining-form axes must generate Zig that `zig fmt` accepts unchanged, and
  the wrapper must carry the shape each type demands: a slice's pointer and
  length, an array's pointer, an optional's `?`, an error union's error
  buffer, an owned slice's out-params and free shim, a struct's out-pointer.
  This is the big coverage driver for source generation, with no compile."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.gen :as g]
            [clj-zig.source :as source]
            [clj-zig.spec :as spec]
            [clj-zig.type :as type]))

(defn- zig-fmt-clean? [src]
  (let [f (java.io.File/createTempFile "clj-zig-matrix" ".zig")]
    (try
      (spit f src)
      (zero? (:exit (sh/sh "zig" "fmt" "--check" (.getPath f))))
      (finally (.delete f)))))

(defn- enum-named? [t] (boolean (get-in t [:layout :enum])))

(defn- eu-struct? [ret]
  (and (= :error-union (:kind ret))
       (= :named (get-in ret [:of :kind]))
       (not (enum-named? (:of ret)))))

(defn- param-required
  "The substrings the wrapper must contain for one boundary param."
  [{:keys [binding type]}]
  (let [b (str binding)]
    (case (:kind type)
      :slice    [(str b "_ptr: [*]") (str b "_len: usize")]
      :ptr      [(str b ": *")]
      :manyptr  [(str b ": [*]")]
      :array    [(str b "_ptr: *const [" (:length type))]
      :optional [(str b ": ?")]
      :named    (if (enum-named? type)
                  [(str b ": " (:name type))]
                  [(str b "_ptr: *const " (:name type))])
      :handle   [(str b ": *" (:name (:of type)))]
      [(str b ": " (name (:name type)))])))

(defn- ret-required
  "The substrings the wrapper must contain for the return position."
  [{:keys [ret] sym :symbol}]
  (cond
    (eu-struct? ret)
    ;; An error-union over a struct combines the error-union out-params with
    ;; the struct out-pointer; the per-field free shim always appears. The
    ;; allocator call appears only when the record carries buffer fields; a
    ;; scalar-only record emits a no-op shim body.
    (let [layout (:layout (:of ret))
          has-buffers? (some :target (:fields layout))]
      (cond-> ["__err: [*]u8" "__errlen: *usize"
               (str "__ret: *" (:name (:of ret)) "__wire")
               (str sym "__free(")]
        has-buffers? (conj "std.heap.c_allocator.free")))

    :else
    (case (:kind ret)
      :optional    [") ?*"]
      :error-union ["__err: [*]u8" "__errlen: *usize"]
      :owned       ["__ptr: *usize, __len: *usize" (str sym "__free(")
                    "std.heap.c_allocator.free"]
      :borrowed    ["__ptr: *usize, __len: *usize"]
      :named       (if (enum-named? ret)
                     [(str ") " (:name ret) " {")]
                     [(str "__ret: *" (:name ret))])
      :handle      [(str ") *" (:name (:of ret)) " {")]
      (if (type/void-type? ret) [") void {"] [(str ") " (name (:name ret)) " {")]))))

(deftest source-matrix-is-fmt-clean-and-well-shaped
  (let [cases (g/structural-cases)]
    (is (< 80 (count cases)) "the matrix spans the cross-product, not a sample")
    (doseq [case cases]
      (let [s   (spec/build-spec case)
            src (source/generate s "return undefined;")
            required (concat (mapcat param-required (:params s))
                             (ret-required s))]
        (testing (pr-str (:signature case))
          (is (zig-fmt-clean? src) "generated source is canonical Zig")
          (doseq [needle required]
            (is (str/includes? src needle)
                (str "wrapper must contain " (pr-str needle)))))))))
