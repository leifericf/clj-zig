(ns clj-zig.ns-deps-test
  "Namespace-level C-interop options: `zig-deps` declares link and include
  flags once for a namespace, every `defnz` in it inherits them, and a
  function descriptor still overrides. The round-trip links libm through a
  namespace declaration rather than a per-function descriptor."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.cache :as cache]
            [clj-zig.core :as core]
            [clj-zig :as zig]))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-mod" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- define! [form]
  (binding [*ns* (the-ns 'clj-zig.ns-deps-test)]
    (eval form)))

(defn- the-fn [sym]
  (ns-resolve 'clj-zig.ns-deps-test sym))

(deftest namespace-deps-layer-into-build-options
  (let [spec (zig/build-spec '{:ns ns.deps.sample :name f :signature [a :f64 :ret :f64]})]
    (core/register-deps! 'ns.deps.sample {:c/link ["m"]})
    (testing "a function inherits its namespace's link flags"
      (is (= {:optimize "ReleaseSafe" :link ["m"]}
             (:options (core/build-inputs spec "return a;")))))
    (testing "a descriptor option overrides the namespace's"
      (is (= {:optimize "ReleaseSafe" :link ["z"]}
             (:options (core/build-inputs spec "return a;"
                                          {:mode :inline :options-extra {:link ["z"]}})))))
    (testing "a namespace with no zig-deps carries only the optimize default"
      (is (= {:optimize "ReleaseSafe"}
             (:options (core/build-inputs
                        (zig/build-spec '{:ns ns.deps.bare :name g :signature [a :f64 :ret :f64]})
                        "return a;")))))))

(deftest module-deps-register-without-touching-build-options
  (let [spec (zig/build-spec '{:ns ns.deps.mods :name f :signature [a :f64 :ret :f64]})]
    (core/register-deps! 'ns.deps.mods {:c/link ["m"]
                                        :zig/modules {"clojo" {:path "root.zig"}}})
    (testing "a namespace's declared modules are retrievable"
      (is (= {"clojo" {:path "root.zig"}} (core/modules-in 'ns.deps.mods))))
    (testing "modules do not leak into the content-hashed build options"
      (is (= {:optimize "ReleaseSafe" :link ["m"]}
             (:options (core/build-inputs spec "return a;")))))))

(deftest module-fingerprint-enters-build-inputs
  (let [dir  (temp-dir)
        root (str dir "/root.zig")]
    (spit root "pub fn render() void {}")
    (core/register-deps! 'ns.deps.modfp {:zig/modules {"pkg" {:path root}}})
    (let [spec (zig/build-spec '{:ns ns.deps.modfp :name f :signature [a :f64 :ret :f64]})
          in1  (core/build-inputs spec "return a;")
          in2  (core/build-inputs spec "return a + 1.0;")]
      (testing "the namespace's module contributes a fingerprint to the inputs"
        (is (re-matches #"[0-9a-f]{12}" (get (:modules in1) "pkg"))))
      (testing "a wrapper-body edit leaves the module fingerprint untouched"
        (is (= (:modules in1) (:modules in2))))
      (testing "the module fingerprint enters the content-hashed key"
        (is (not= (cache/cache-key (dissoc in1 :modules))
                  (cache/cache-key in1))))
      (testing "the modules stay out of the build options"
        (is (not (contains? (:options in1) :modules)))
        (is (not (contains? (:options in1) :zig/modules)))))))

(deftest namespace-link-resolves-a-c-call
  (testing "zig-deps links libm so a body calling c.sqrt round-trips"
    (define! `(core/zig-deps {:c/link ["m"]}))
    (define! `(core/defnz ~'hyp [~'a :f64 ~'b :f64 :ret :f64]
                "const c = @cImport({ @cInclude(\"math.h\"); }); return c.sqrt(a * a + b * b);"))
    (is (= 5.0 ((the-fn 'hyp) 3.0 4.0)))))
