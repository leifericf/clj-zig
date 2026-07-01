(ns clj-zig.bake-test
  "Baking a namespace produces loadable native libraries in the resource
  tree the classpath loader resolves from."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.bake :as bake]
             [clj-zig.cache :as cache]
             [clj-zig.ffm :as ffm]
            [clj-zig.spec :as spec]))

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

(deftest third-party-skipped-target-message-excludes-the-host-id
  ;; The host is baked (chosen), so its id must NOT appear in the skipped
  ;; list. Compared by :id, not by whole-map equality (the host entry carries
  ;; a nil triple while matrix entries carry a real triple).
  (let [out  (scratch-root)
        spec (spec/build-spec '{:ns clj-zig.bake-test :name f
                                :signature [n :i64 :ret :i64]})
        info {:spec spec :body "return n;" :source-mode :inline
              :options-extra {:link ["sqlite3"]}}
        err  (java.io.StringWriter.)]
    (with-redefs [bake/bake-target! (fn [_ _ t] {:target (:id t)})]
      (binding [*err* err]
        (#'bake/bake-function! out info bake/default-targets)))
    (let [msg (.toString err)]
      (is (str/includes? msg "host-only"))
      (is (not (str/includes? msg (pr-str (cache/target-triple))))
          "the host id is baked, not listed as skipped"))))

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

(deftest bakes-a-module-dependent-function
  (let [out     (scratch-root)
        results (bake/bake! {:ns 'clj-zig.bake-module-fixture :out out :targets :host})
        info    (:clj-zig/info (meta (resolve 'clj-zig.bake-module-fixture/ask)))
        inputs  (#'bake/function-inputs info)]
    (testing "the function's build inputs carry the external module"
      (is (seq (:modules inputs)) "the module fingerprint enters the hash")
      (is (seq (:module-roots inputs)) "the module root is resolved for compile"))
    (testing "the module-dependent function bakes into a loadable library"
      (let [{:keys [library]} (first results)]
        (is (.exists (io/file library)))
        (is (= 42 ((ffm/bind (:spec info) library))))))
    (testing "the baked hash equals the in-place hash, so it is reproducible"
      (let [coords {:target (cache/target-triple) :ns 'clj-zig.bake-module-fixture
                    :name 'ask :hash (cache/cache-key (assoc inputs :target (cache/target-triple)))}]
        (is (= (cache/bundled-resource-path coords) (:resource (first results))))))))

(deftest bakes-a-pinned-module-and-a-consumer-resolves-without-the-checkout
  ;; ADR 36: a pinned module with a local :path bakes from the checkout; a
  ;; consumer declaring the same pinned reference with NO :path reproduces the
  ;; hash, so it resolves the bundled library with no zig and no module source.
  (let [out     (scratch-root)
        results (bake/bake! {:ns 'clj-zig.bake-pinned-fixture :out out :targets :host})
        info    (:clj-zig/info (meta (resolve 'clj-zig.bake-pinned-fixture/ask-pinned)))
        inputs  (#'bake/function-inputs info)]
    (testing "bake resolves the pinned module's local checkout for compilation"
      (is (seq (:module-roots inputs))))
    (testing "the pinned module-dependent function bakes into a loadable library"
      (let [{:keys [library]} (first results)]
        (is (.exists (io/file library)))
        (is (= 42 ((ffm/bind (:spec info) library))))))
    (testing "a consumer reproduces the key from the pin alone, with no fs read"
      (let [boom             {:stat (fn [_] (throw (AssertionError. "no fs read")))
                              :read (fn [_] (throw (AssertionError. "no fs read")))}
            consumer-modules {"answers" (cache/module-fingerprint
                                         {:git/sha "0000000000000000000000000000000000000000"
                                          :root    "src/root.zig"}
                                         boom)}
            consumer-inputs  (assoc inputs :modules consumer-modules
                                    :target (cache/target-triple))
            coords           {:target (cache/target-triple)
                              :ns 'clj-zig.bake-pinned-fixture :name 'ask-pinned
                              :hash (cache/cache-key consumer-inputs)}]
        (is (= (cache/bundled-resource-path coords) (:resource (first results))))))))
