(ns clj-zig.module-e2e-test
  "End to end over a real external Zig module (ADR 34). A namespace declares
  a `:zig/modules` dependency on a small package living at a local path; a
  function whose body `@import`s it by name compiles through `zig
  build-lib`, links the module, loads, and calls into it. The build is
  content-addressed, so an identical build is a cache hit, and the persistent
  global cache dir (ADR 35) is populated. Needs a real `zig` and JDK 22+."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.compile :as compile]
            [clj-zig.core :as core]
            [clj-zig.ffm :as ffm]
            [clj-zig :as zig]))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-modpkg" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest a-module-dependent-build-compiles-loads-and-caches
  (let [pkg   (temp-dir)
        root  (str pkg "/root.zig")
        ;; A nonce keeps the module content (and so its fingerprint and the
        ;; dependent function's cache key) unique to this run, so the first
        ;; build is a genuine compile rather than a hit left by a prior run.
        nonce (System/nanoTime)
        ns    'mod.e2e.pkg]
    (spit root (str "// fixture " nonce "\npub fn answer() i32 {\n    return 42;\n}\n"))
    (core/register-deps! ns {:zig/modules {"mymod" {:path root}}})
    (let [spec (zig/build-spec {:ns ns :name 'ask :signature [:ret :i32]})
          body "const m = @import(\"mymod\");\n    return m.answer();"
          a1   (core/artifact spec body)
          a2   (core/artifact spec body)]
      (testing "the wrapper imports the external module, links it, and builds"
        (is (= :compiled (:status a1)))
        (is (.exists (io/file (:library a1)))))
      (testing "an identical build is a content-addressed cache hit"
        (is (= :cached (:status a2)))
        (is (= (:library a1) (:library a2))))
      (testing "the bound function calls into the module"
        (is (= 42 ((ffm/bind spec (:library a1))))))
      (testing "the build records the module it linked, with its fingerprint"
        (is (= [{:name "mymod" :status :local}]
               (mapv #(dissoc % :fingerprint) (:modules a1))))
        (is (re-matches #"[0-9a-f]{12}" (:fingerprint (first (:modules a1))))))
      (testing "Zig's persistent global cache is populated (ADR 35)"
        (let [g (io/file (compile/global-cache-dir))]
          (is (.exists g))
          (is (seq (.listFiles g))))))))

(deftest a-module-dependent-defnz-surfaces-its-modules-on-the-var
  (let [pkg   (temp-dir)
        root  (str pkg "/root.zig")
        nonce (System/nanoTime)]
    (spit root (str "// fixture " nonce "\npub fn answer() i32 {\n    return 7;\n}\n"))
    (binding [*ns* (the-ns 'clj-zig.module-e2e-test)]
      (eval `(core/zig-deps {:zig/modules {"mymod" {:path ~root}}}))
      (eval `(core/defnz ~'ask7 [:ret :i32]
               "const m = @import(\"mymod\");\n    return m.answer();")))
    (let [v (ns-resolve 'clj-zig.module-e2e-test 'ask7)]
      (testing "the function calls into the module"
        (is (= 7 (v))))
      (testing "zig/modules reads the module info off the Var"
        (let [m (first (zig/modules v))]
          (is (= "mymod" (:name m)))
          (is (= :local (:status m)))
          (is (re-matches #"[0-9a-f]{12}" (:fingerprint m))))))))

(deftest a-module-compile-error-is-attributed-to-the-module
  (let [pkg   (temp-dir)
        root  (str pkg "/root.zig")
        nonce (System/nanoTime)
        ns    'mod.e2e.brokenmod]
    (spit root (str "// fixture " nonce "\npub fn answer() i32 {\n    return notdefined;\n}\n"))
    (core/register-deps! ns {:zig/modules {"mymod" {:path root}}})
    (let [spec (zig/build-spec {:ns ns :name 'ask :signature [:ret :i32]})
          body "const m = @import(\"mymod\");\n    return m.answer();"]
      (try
        (core/artifact spec body)
        (is false "expected a module compile failure")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (= :zig/compile-failed (:error/code d)))
            (is (= :module (:zig/origin d)))
            (is (= "mymod" (:zig/module d)))))))))

(deftest a-wrapper-compile-error-is-attributed-to-the-wrapper
  (let [pkg   (temp-dir)
        root  (str pkg "/root.zig")
        nonce (System/nanoTime)
        ns    'mod.e2e.brokenwrap]
    (spit root (str "// fixture " nonce "\npub fn answer() i32 {\n    return 3;\n}\n"))
    (core/register-deps! ns {:zig/modules {"mymod" {:path root}}})
    (let [spec (zig/build-spec {:ns ns :name 'ask :signature [:ret :i32]})
          body "const m = @import(\"mymod\");\n    return ;"]
      (try
        (core/artifact spec body)
        (is false "expected a wrapper compile failure")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (= :zig/compile-failed (:error/code d)))
            (is (= :wrapper (:zig/origin d)))
            (is (nil? (:zig/module d)))))))))

(deftest a-changed-module-relinks-the-dependent-function
  (testing "editing the module flips its fingerprint, so the dependent
  function gets a fresh content-addressed artifact"
    (let [pkg   (temp-dir)
          root  (str pkg "/root.zig")
          nonce (System/nanoTime)
          ns    'mod.e2e.edit]
      (spit root (str "// fixture " nonce "\npub fn answer() i32 {\n    return 1;\n}\n"))
      (core/register-deps! ns {:zig/modules {"mymod" {:path root}}})
      (let [spec (zig/build-spec {:ns ns :name 'ask :signature [:ret :i32]})
            body "const m = @import(\"mymod\");\n    return m.answer();"
            a1   (core/artifact spec body)
            v1   ((ffm/bind spec (:library a1)))]
        (is (= 1 v1))
        ;; Edit the module; the wrapper body is untouched.
        (spit root (str "// fixture " nonce "\npub fn answer() i32 {\n    return 2;\n}\n"))
        (let [a2 (core/artifact spec body)]
          (is (= :compiled (:status a2)) "a changed module recompiles the dependent")
          (is (not= (:library a1) (:library a2)) "under a fresh content-addressed path")
          (is (= 2 ((ffm/bind spec (:library a2)))) "with the new module behavior"))))))
