(ns clj-zig.cache-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.cache :as cache]
            [clj-zig.compile :as compile]
            [clj-zig.ffm :as ffm]
            [clj-zig.source :as source]
            [clj-zig.spec :as spec]))

(defn- scratch-root []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-cache" (make-array java.nio.file.attribute.FileAttribute 0))))

(def add-spec
  (spec/build-spec '{:ns app.core :name add
                     :signature [x :i64 y :i64 :ret :i64]}))

(defn- inputs [root body]
  {:spec add-spec
   :body body
   :source (source/generate add-spec body)
   :deps nil
   :options {:optimize "ReleaseSafe"}
   :zig-version "0.16.0"
   :target "macos-aarch64"
   :root root})

(deftest cache-key-is-deterministic
  (is (= (cache/cache-key (inputs nil "return x + y;"))
         (cache/cache-key (inputs nil "return x + y;"))))
  (testing "the key is a stable-length hex digest"
    (is (re-matches #"[0-9a-f]{12}" (cache/cache-key (inputs nil "return x + y;"))))))

(deftest cache-key-is-change-sensitive
  (let [base (cache/cache-key (inputs nil "return x + y;"))]
    (testing "body change"
      (is (not= base (cache/cache-key (inputs nil "return x + y + 10;")))))
    (testing "target change"
      (is (not= base (cache/cache-key (assoc (inputs nil "return x + y;")
                                             :target "linux-x86_64")))))
    (testing "zig version change"
      (is (not= base (cache/cache-key (assoc (inputs nil "return x + y;")
                                             :zig-version "0.17.0")))))
    (testing "options change"
      (is (not= base (cache/cache-key (assoc (inputs nil "return x + y;")
                                             :options {:optimize "Debug"})))))))

(deftest artifact-paths-follow-the-documented-layout
  (let [p (cache/artifact-paths {:root "/tmp/c" :target "macos-aarch64"
                                 :ns 'app.core :name 'add :hash "83a1c0f9e1b2"})]
    (is (= "/tmp/c/macos-aarch64/app.core/add-83a1c0f9e1b2" (:dir p)))
    (is (str/ends-with? (:source-path p) "/source.zig"))
    (is (str/ends-with? (:manifest-path p) "/manifest.edn"))
    (is (str/includes? (:library-path p) "/libadd-83a1c0f9e1b2."))))

(deftest reuses-when-unchanged-rebuilds-when-changed
  (let [root     (scratch-root)
        compiles (atom 0)
        stub     (fn [{:keys [library-path]}]
                   (swap! compiles inc)
                   (io/make-parents (io/file library-path))
                   (spit library-path "stub"))]
    (testing "first build compiles"
      (let [r (cache/ensure-library! (inputs root "return x + y;") stub)]
        (is (false? (:cached? r)))
        (is (= 1 @compiles))))
    (testing "an identical build reuses the cached library"
      (let [r (cache/ensure-library! (inputs root "return x + y;") stub)]
        (is (true? (:cached? r)))
        (is (= 1 @compiles))))
    (testing "a changed body rebuilds under a fresh path"
      (let [r (cache/ensure-library! (inputs root "return x + y + 10;") stub)]
        (is (false? (:cached? r)))
        (is (= 2 @compiles))))))

(deftest writes-and-reads-the-manifest
  (let [root  (scratch-root)
        stub  (fn [{:keys [library-path]}]
                (io/make-parents (io/file library-path))
                (spit library-path "stub"))
        r     (cache/ensure-library! (inputs root "return x + y;") stub)
        manifest (cache/read-manifest r)]
    (is (= 'app.core/add (:var manifest)))
    (is (= '[x :i64 y :i64 :ret :i64] (:signature manifest)))
    (is (= (:hash r) (:hash manifest)))
    (is (= "macos-aarch64" (:target manifest)))))

(deftest clean-removes-the-tree
  (let [root (scratch-root)
        stub (fn [{:keys [library-path]}]
               (io/make-parents (io/file library-path))
               (spit library-path "stub"))]
    (cache/ensure-library! (inputs root "return x + y;") stub)
    (is (.exists (io/file root)))
    (cache/clean! root)
    (is (not (.exists (io/file root))))))

(deftest bundled-resource-path-mirrors-the-cache-layout
  (testing "a baked library is keyed by target, namespace, name, and hash
  under the resource root"
    (is (= "clj-zig/native/macos-aarch64/app.core/add-83a1c0f9e1b2/libadd-83a1c0f9e1b2.dylib"
           (cache/bundled-resource-path {:target "macos-aarch64" :ns 'app.core
                                         :name 'add :hash "83a1c0f9e1b2"})))
    (is (= "clj-zig/native/linux-x86_64/app.core/add-83a1c0f9e1b2/libadd-83a1c0f9e1b2.so"
           (cache/bundled-resource-path {:target "linux-x86_64" :ns 'app.core
                                         :name 'add :hash "83a1c0f9e1b2"})))))

(deftest a-bundled-library-loads-without-compiling
  (testing "a baked library on the classpath is extracted and loaded, and
  the compiler is never invoked"
    (let [in        (assoc (inputs nil "return x + y;")
                           :target (cache/target-triple)
                           :zig-version (cache/zig-version))
          key       (cache/cache-key in)
          coords    {:target (:target in) :ns 'app.core :name 'add :hash key}
          res-root  (scratch-root)
          res-file  (io/file res-root (cache/bundled-resource-path coords))
          build-dir (scratch-root)
          built     (compile/compile!
                     {:source       (:source in)
                      :source-path  (str build-dir "/source.zig")
                      :library-path (str build-dir "/libadd." (compile/dynamic-library-extension))
                      :ctx          {:var 'app.core/add :signature (:signature add-spec)}})
          prev      (.getContextClassLoader (Thread/currentThread))
          loader    (java.net.URLClassLoader.
                     (into-array java.net.URL [(.toURL (.toURI (io/file res-root)))])
                     prev)
          compiled? (atom false)]
      (io/make-parents res-file)
      (io/copy (io/file (:library built)) res-file)
      (try
        (.setContextClassLoader (Thread/currentThread) loader)
        (let [r (cache/ensure-library! (assoc in :root (scratch-root))
                                       (fn [_] (reset! compiled? true)))]
          (is (true? (:bundled? r)) "the library resolves from the classpath")
          (is (false? @compiled?) "the bundled path never invokes the compiler")
          (is (.exists (io/file (:library-path r))) "it is extracted into the cache")
          (testing "the extracted library binds and runs"
            (let [add (ffm/bind add-spec (:library-path r))]
              (is (= 42 (add 20 22))))))
        (finally
          (.setContextClassLoader (Thread/currentThread) prev))))))

(deftest a-mismatched-hash-does-not-load-a-baked-library
  (testing "a baked resource under a different hash is a clean miss; the
  loader compiles rather than loading the wrong library"
    (let [in       (assoc (inputs nil "return x + y;")
                          :target (cache/target-triple)
                          :zig-version (cache/zig-version))
          coords   {:target (:target in) :ns 'app.core :name 'add :hash "000000000000"}
          res-root (scratch-root)
          res-file (io/file res-root (cache/bundled-resource-path coords))
          prev     (.getContextClassLoader (Thread/currentThread))
          loader   (java.net.URLClassLoader.
                    (into-array java.net.URL [(.toURL (.toURI (io/file res-root)))])
                    prev)
          compiled (atom 0)
          stub     (fn [{:keys [library-path]}]
                     (swap! compiled inc)
                     (io/make-parents (io/file library-path))
                     (spit library-path "stub"))]
      (io/make-parents res-file)
      (spit res-file "not the right library")
      (try
        (.setContextClassLoader (Thread/currentThread) loader)
        (let [r (cache/ensure-library! (assoc in :root (scratch-root)) stub)]
          (is (not (:bundled? r)) "the mismatched resource is ignored")
          (is (= 1 @compiled) "the loader compiles instead of loading it"))
        (finally
          (.setContextClassLoader (Thread/currentThread) prev))))))

(deftest real-compile-reuses-on-second-call
  (testing "content addressing over a genuine zig build"
    (let [root (scratch-root)
          in   (assoc (inputs root "return x + y;")
                      :target (cache/target-triple)
                      :zig-version (cache/zig-version))
          r1   (cache/ensure-library! in compile/compile!)
          r2   (cache/ensure-library! in compile/compile!)]
      (is (false? (:cached? r1)))
      (is (true? (:cached? r2)))
      (is (= (:library-path r1) (:library-path r2)))
      (let [{:keys [out]} (sh/sh "nm" "-gU" (:library-path r1))]
        (is (str/includes? out "clj_zig_app_2e_core_add"))))))
