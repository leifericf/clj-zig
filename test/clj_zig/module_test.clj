(ns clj-zig.module-test
  "External Zig-module declarations: `zig-deps` may declare `:zig/modules`,
  each a name a body `@import`s mapped to a dev `:path` or a pinned
  `:git/sha`/`:root` reference (ADR 34). Normalization is pure and rejects
  each malformed shape with its own `:error/code`; registration stores the
  normalized modules per namespace alongside the C-interop options."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :as core]
            [clj-zig.toolchain :as toolchain]
            [clj-zig.descriptor :as descriptor]))

(def ^:private pv toolchain/pinned-version)

(defn- code-from
  "Run `thunk` and return the `:error/code` of the diagnostic it throws, or
  nil when it does not throw."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:error/code (ex-data e)))))

(deftest normalizes-valid-module-references
  (testing "no :zig/modules declares no modules"
    (is (nil? (descriptor/zig-modules {} pv)))
    (is (nil? (descriptor/zig-modules {:c/link ["m"]} pv))))
  (testing "a dev :path reference normalizes to the module root path"
    (is (= {"phane" {:path "../phane/src/root.zig"}}
           (descriptor/zig-modules {:zig/modules {"phane" {:path "../phane/src/root.zig"}}} pv))))
  (testing "a pinned :git/sha reference keeps the sha and root"
    (is (= {"phane" {:git/sha "abc123" :root "src/root.zig"}}
           (descriptor/zig-modules {:zig/modules {"phane" {:git/sha "abc123"
                                                     :root "src/root.zig"}}} pv))))
  (testing "a pinned reference keeps an optional local :path (ADR 36)"
    (is (= {"phane" {:git/sha "abc123" :root "src/root.zig" :path "/co/root.zig"}}
           (descriptor/zig-modules {:zig/modules {"phane" {:git/sha "abc123"
                                                     :root "src/root.zig"
                                                     :path "/co/root.zig"}}} pv))))
  (testing "several modules normalize together"
    (is (= {"a" {:path "a.zig"} "b" {:path "b.zig"}}
           (descriptor/zig-modules {:zig/modules {"a" {:path "a.zig"}
                                            "b" {:path "b.zig"}}} pv))))
  (testing "a matching :zig/version is accepted"
    (is (= {"phane" {:path "root.zig"}}
           (descriptor/zig-modules {:zig/modules {"phane" {:path "root.zig"
                                                     :zig/version pv}}} pv)))))

(deftest rejects-malformed-module-declarations
  (testing ":zig/modules that is not a map"
    (is (= :clj-zig/bad-modules
           (code-from #(descriptor/zig-modules {:zig/modules ["phane"]} pv)))))
  (testing "a non-string module name"
    (is (= :clj-zig/bad-module-name
           (code-from #(descriptor/zig-modules {:zig/modules {:phane {:path "r.zig"}}} pv)))))
  (testing "a name the compiler reserves"
    (is (= :clj-zig/reserved-module-name
           (code-from #(descriptor/zig-modules {:zig/modules {"std" {:path "r.zig"}}} pv)))))
  (testing "a descriptor that is not a map"
    (is (= :clj-zig/bad-module-ref
           (code-from #(descriptor/zig-modules {:zig/modules {"phane" "r.zig"}} pv)))))
  (testing "a descriptor with no root"
    (is (= :clj-zig/module-missing-root
           (code-from #(descriptor/zig-modules {:zig/modules {"phane" {}}} pv))))
    (is (= :clj-zig/module-missing-root
           (code-from #(descriptor/zig-modules {:zig/modules {"phane" {:git/sha "abc"}}} pv)))))
  (testing "a :zig/version other than the pinned compiler"
    (is (= :clj-zig/module-zig-version-mismatch
           (code-from #(descriptor/zig-modules {:zig/modules {"phane" {:path "r.zig"
                                                                 :zig/version "0.13.0"}}} pv))))))

(deftest register-deps-stores-modules-per-namespace
  (testing "modules-in returns the normalized modules a namespace declared"
    (core/register-deps! 'ns.mod.sample {:zig/modules {"phane" {:path "root.zig"}}})
    (is (= {"phane" {:path "root.zig"}} (core/modules-in 'ns.mod.sample))))
  (testing "C options and modules register side by side"
    (core/register-deps! 'ns.mod.both {:c/link ["m"]
                                       :zig/modules {"phane" {:path "root.zig"}}})
    (is (= {:link ["m"]} (core/deps-in 'ns.mod.both)))
    (is (= {"phane" {:path "root.zig"}} (core/modules-in 'ns.mod.both))))
  (testing "a namespace with no modules has none"
    (core/register-deps! 'ns.mod.bare {:c/link ["m"]})
    (is (nil? (core/modules-in 'ns.mod.bare)))))
