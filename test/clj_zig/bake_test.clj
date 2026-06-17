(ns clj-zig.bake-test
  "Baking a namespace produces loadable native libraries in the resource
  tree the classpath loader resolves from."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.bake :as bake]
            [clj-zig.cache :as cache]
            [clj-zig.ffm :as ffm]))

(defn- scratch-root []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-bake-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest third-party-c-links-narrow-to-the-host
  (testing "libc and libm cross-compile; any other library is host-only"
    (is (false? (#'bake/third-party-c? nil)))
    (is (false? (#'bake/third-party-c? {:link ["m"]})))
    (is (false? (#'bake/third-party-c? {:link ["c" "m"]})))
    (is (true? (#'bake/third-party-c? {:link ["sqlite3"]})))
    (is (true? (#'bake/third-party-c? {:link ["m" "z"]})))))

(deftest bakes-a-namespace-for-the-host
  (let [out     (scratch-root)
        results (bake/bake! {:ns 'clj-zig.bake-fixture :out out :targets :host})]
    (testing "every function in the namespace is baked"
      (is (= #{'clj-zig.bake-fixture/add 'clj-zig.bake-fixture/mul}
             (set (map :var results)))))
    (testing "each artifact lands at its resource path under the out dir"
      (doseq [{:keys [resource library]} results]
        (is (.exists (io/file library)))
        (is (= (.getCanonicalPath (io/file out resource))
               (.getCanonicalPath (io/file library))))))
    (testing "a baked library is a working native library"
      (let [add-info (:clj-zig/info (meta (resolve 'clj-zig.bake-fixture/add)))
            add-lib  (:library (first (filter #(= 'clj-zig.bake-fixture/add (:var %)) results)))
            add      (ffm/bind (:spec add-info) add-lib)]
        (is (= 42 (add 20 22)))))
    (testing "the resource path matches what the loader will look up"
      (let [add-info (:clj-zig/info (meta (resolve 'clj-zig.bake-fixture/add)))
            inputs   (#'bake/function-inputs add-info)
            coords   {:target (cache/target-triple) :ns 'clj-zig.bake-fixture
                      :name 'add :hash (cache/cache-key (assoc inputs :target (cache/target-triple)))}]
        (is (some #(= (cache/bundled-resource-path coords) (:resource %)) results))))))
