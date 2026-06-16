(ns zigar.inspect-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [zigar :as zig]
            [zigar.core :refer [defnz]]))

(defnz add
  "Adds two signed integers."
  [x :i64
   y :i64
   :ret :i64]
  "return x + y;")

;; --- Inspection helpers hang off the Var --------------------------------

(deftest inspects-a-defined-function-through-its-var
  (testing "the body is returned as written"
    (is (= "return x + y;" (zig/source #'add))))
  (testing "the signature is the vector the function was defined with"
    (is (= '[x :i64 y :i64 :ret :i64] (zig/signature #'add))))
  (testing "the spec is the normalized boundary data"
    (let [spec (zig/spec #'add)]
      (is (= 'add (:name spec)))
      (is (= 2 (count (:params spec))))
      (is (= {:kind :scalar :name :i64} (:ret spec)))))
  (testing "the generated source is the full export wrapper"
    (let [src (zig/generated-source #'add)]
      (is (str/includes? src "export fn"))
      (is (str/includes? src "return x + y;"))))
  (testing "the symbol is the stable C name"
    (is (= (:symbol (zig/spec #'add)) (zig/symbol #'add))))
  (testing "the library path points at a built shared library"
    (let [lib (zig/library #'add)]
      (is (some #(str/ends-with? lib %) [".dylib" ".so" ".dll"]))))
  (testing "the status is a build outcome"
    (is (contains? #{:compiled :cached} (zig/status #'add)))))

;; --- Pure data functions compose the same pipeline ----------------------

(deftest exposes-the-pure-pipeline-functions
  (testing "normalize-type"
    (is (= {:kind :slice :const? true :of {:kind :scalar :name :u8}}
           (zig/normalize-type [:slice :const :u8]))))
  (testing "normalize-signature"
    (is (= '{:args [{:binding x :type :i64} {:binding y :type :i64}] :ret :i64}
           (zig/normalize-signature '[x :i64 y :i64 :ret :i64]))))
  (testing "build-spec"
    (let [spec (zig/build-spec '{:ns app.core :name twice :signature [x :i64 :ret :i64]})]
      (is (= "zigar_app_2e_core_twice" (:symbol spec)))))
  (testing "generate-source emits a wrapper from a spec and body"
    (let [spec (zig/build-spec '{:ns app.core :name twice :signature [x :i64 :ret :i64]})
          src  (zig/generate-source spec "return x * 2;")]
      (is (str/includes? src "export fn zigar_app_2e_core_twice(x: i64) i64"))
      (is (str/includes? src "return x * 2;")))))

(deftest fn-builds-a-callable-from-a-spec-and-body
  (let [spec (zig/build-spec '{:ns app.core :name mul :signature [x :i64 y :i64 :ret :i64]})
        mul  (zig/fn spec "return x * y;")]
    (is (= 12 (mul 3 4))))
  (testing "compile! then load! is the same pipeline in two steps"
    (let [spec     (zig/build-spec '{:ns app.core :name sub :signature [x :i64 y :i64 :ret :i64]})
          artifact (zig/compile! spec "return x - y;")
          sub      (zig/load! artifact)]
      (is (contains? #{:compiled :cached} (:status artifact)))
      (is (= 5 (sub 8 3))))))

;; --- recompile! and explain ---------------------------------------------

(deftest recompile-rebuilds-and-rebinds
  (let [define (fn [body] (eval `(defnz ~'rc [~'x :i64 :ret :i64] ~body)))]
    (define "return x + 1;")
    (is (= 6 ((resolve 'rc) 5)))
    (testing "recompile! forces a fresh build and keeps the function working"
      (zig/recompile! (resolve 'rc))
      (is (= :compiled (zig/status (resolve 'rc))))
      (is (= 6 ((resolve 'rc) 5))))))

(deftest explain-renders-the-last-failed-attempt
  (let [define (fn [body] (eval `(defnz ~'ex [~'x :i64 :ret :i64] ~body)))]
    (define "return x + 1;")
    (testing "a healthy Var has nothing to explain"
      (is (nil? (zig/explain (resolve 'ex)))))
    (testing "after a failed redefinition explain renders the diagnostic"
      (is (thrown? clojure.lang.ExceptionInfo (define "return x + ;")))
      (let [out (zig/explain (resolve 'ex))]
        (is (string? out))
        (is (str/includes? out "Could not compile defnz"))
        (is (str/includes? out "Zig error:"))))))

;; --- clean! --------------------------------------------------------------

(deftest clean-removes-the-cache-root
  (let [root ".zigar/cache-inspect-test"]
    (zig/build-spec '{:ns t :name f :signature [x :i64 :ret :i64]})
    (spit (doto (java.io.File. (str root "/marker")) (-> .getParentFile .mkdirs)) "x")
    (is (.exists (java.io.File. root)))
    (zig/clean! root)
    (is (not (.exists (java.io.File. root))))))
