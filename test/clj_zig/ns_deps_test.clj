(ns clj-zig.ns-deps-test
  "Namespace-level C-interop options: `zig-deps` declares link and include
  flags once for a namespace, every `defnz` in it inherits them, and a
  function descriptor still overrides. The round-trip links libm through a
  namespace declaration rather than a per-function descriptor."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :as core]
            [clj-zig :as zig]))

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

(deftest namespace-link-resolves-a-c-call
  (testing "zig-deps links libm so a body calling c.sqrt round-trips"
    (define! `(core/zig-deps {:c/link ["m"]}))
    (define! `(core/defnz ~'hyp [~'a :f64 ~'b :f64 :ret :f64]
                "const c = @cImport({ @cInclude(\"math.h\"); }); return c.sqrt(a * a + b * b);"))
    (is (= 5.0 ((the-fn 'hyp) 3.0 4.0)))))
