(ns clj-zig.module-test
  "External Zig-module declarations: `zig-deps` may declare `:zig/modules`,
  each a name a body `@import`s mapped to a dev `:path` or a pinned
  `:git/sha`/`:root` reference (ADR 34). Normalization is pure and rejects
  each malformed shape with its own `:error/code`; registration stores the
  normalized modules per namespace alongside the C-interop options."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :as core]
            [clj-zig.toolchain :as toolchain]))

(defn- code-from
  "Run `thunk` and return the `:error/code` of the diagnostic it throws, or
  nil when it does not throw."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:error/code (ex-data e)))))

(deftest normalizes-valid-module-references
  (testing "no :zig/modules declares no modules"
    (is (nil? (core/zig-modules {})))
    (is (nil? (core/zig-modules {:c/link ["m"]}))))
  (testing "a dev :path reference normalizes to the module root path"
    (is (= {"clojo" {:path "../clojo/src/root.zig"}}
           (core/zig-modules {:zig/modules {"clojo" {:path "../clojo/src/root.zig"}}}))))
  (testing "a pinned :git/sha reference keeps the sha and root"
    (is (= {"clojo" {:git/sha "abc123" :root "src/root.zig"}}
           (core/zig-modules {:zig/modules {"clojo" {:git/sha "abc123"
                                                     :root "src/root.zig"}}}))))
  (testing "a pinned reference keeps an optional local :path (ADR 36)"
    (is (= {"clojo" {:git/sha "abc123" :root "src/root.zig" :path "/co/root.zig"}}
           (core/zig-modules {:zig/modules {"clojo" {:git/sha "abc123"
                                                     :root "src/root.zig"
                                                     :path "/co/root.zig"}}}))))
  (testing "several modules normalize together"
    (is (= {"a" {:path "a.zig"} "b" {:path "b.zig"}}
           (core/zig-modules {:zig/modules {"a" {:path "a.zig"}
                                            "b" {:path "b.zig"}}}))))
  (testing "a matching :zig/version is accepted"
    (is (= {"clojo" {:path "root.zig"}}
           (core/zig-modules {:zig/modules {"clojo" {:path "root.zig"
                                                     :zig/version toolchain/pinned-version}}})))))

(deftest rejects-malformed-module-declarations
  (testing ":zig/modules that is not a map"
    (is (= :clj-zig/bad-modules
           (code-from #(core/zig-modules {:zig/modules ["clojo"]})))))
  (testing "a non-string module name"
    (is (= :clj-zig/bad-module-name
           (code-from #(core/zig-modules {:zig/modules {:clojo {:path "r.zig"}}})))))
  (testing "a name the compiler reserves"
    (is (= :clj-zig/reserved-module-name
           (code-from #(core/zig-modules {:zig/modules {"std" {:path "r.zig"}}})))))
  (testing "a descriptor that is not a map"
    (is (= :clj-zig/bad-module-ref
           (code-from #(core/zig-modules {:zig/modules {"clojo" "r.zig"}})))))
  (testing "a descriptor with no root"
    (is (= :clj-zig/module-missing-root
           (code-from #(core/zig-modules {:zig/modules {"clojo" {}}}))))
    (is (= :clj-zig/module-missing-root
           (code-from #(core/zig-modules {:zig/modules {"clojo" {:git/sha "abc"}}})))))
  (testing "a :zig/version other than the pinned toolchain"
    (is (= :clj-zig/module-zig-version-mismatch
           (code-from #(core/zig-modules {:zig/modules {"clojo" {:path "r.zig"
                                                                 :zig/version "0.13.0"}}}))))))

(deftest register-deps-stores-modules-per-namespace
  (testing "modules-in returns the normalized modules a namespace declared"
    (core/register-deps! 'ns.mod.sample {:zig/modules {"clojo" {:path "root.zig"}}})
    (is (= {"clojo" {:path "root.zig"}} (core/modules-in 'ns.mod.sample))))
  (testing "C options and modules register side by side"
    (core/register-deps! 'ns.mod.both {:c/link ["m"]
                                       :zig/modules {"clojo" {:path "root.zig"}}})
    (is (= {:link ["m"]} (core/deps-in 'ns.mod.both)))
    (is (= {"clojo" {:path "root.zig"}} (core/modules-in 'ns.mod.both))))
  (testing "a namespace with no modules has none"
    (core/register-deps! 'ns.mod.bare {:c/link ["m"]})
    (is (nil? (core/modules-in 'ns.mod.bare)))))
