(ns clj-zig.distribution-prop-test
  "Properties of the distribution layer. The classpath resource path a
  consumer looks a baked library up by must name the same file the cache
  stores it as; if the two diverge the loader misses every baked artifact.
  The property crosses the value axis (arbitrary target, namespace, name,
  and hash); the matrix test walks the real release targets as data."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clj-zig.bake :as bake]
            [clj-zig.cache :as cache]
            [clj-zig.cachestore :as cachestore]))

(defn- basename [path]
  (last (str/split path #"/")))

(defn- expected-ext
  "The shared-library suffix for a target, derived independently of the
  cache so it is a real oracle for the path functions."
  [target]
  (cond
    (str/includes? target "macos")   "dylib"
    (str/includes? target "windows") "dll"
    :else                            "so"))

(def ^:private gen-target
  (gen/fmap (fn [[os arch musl?]]
              (str os "-" arch (when (and musl? (= os "linux")) "-musl")))
            (gen/tuple (gen/elements ["linux" "macos" "windows"])
                       (gen/elements ["x86_64" "aarch64"])
                       gen/boolean)))

(def ^:private gen-ident
  (gen/fmap #(symbol (str "n" %)) (gen/choose 0 9999)))

(def ^:private gen-hash
  (gen/fmap (partial apply str)
            (gen/vector (gen/elements (seq "0123456789abcdef")) 12)))

(defn- coords [target ns name hash]
  {:target target :ns ns :name name :hash hash})

(defspec a-resource-path-names-the-cached-library 300
  (prop/for-all [target gen-target
                 ns     gen-ident
                 name   gen-ident
                 hash   gen-hash]
    (let [c   (coords target ns name hash)
          lib (:library-path (cache/artifact-paths (assoc c :root ".clj-zig/cache")))
          res (cache/bundled-resource-path c)]
      (and (= (basename lib) (basename res))
           (str/ends-with? res (str "." (expected-ext target)))))))

(deftest the-release-matrix-paths-agree-with-the-cache
  (testing "every default bake target, and the host, names its baked
  resource the same file the cache stores, with the right suffix"
    (doseq [{:keys [id]} (conj bake/default-targets {:id (cachestore/target-triple)})]
      (let [c   (coords id 'app.core 'add "83a1c0f9e1b2")
            lib (:library-path (cache/artifact-paths (assoc c :root ".clj-zig/cache")))
            res (cache/bundled-resource-path c)]
        (is (= (basename lib) (basename res)) (str "path mismatch for target " id))
        (is (str/ends-with? res (str "." (expected-ext id))))
        (is (str/starts-with? res "clj-zig/native/"))))))
