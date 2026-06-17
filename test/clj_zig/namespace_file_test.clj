(ns clj-zig.namespace-file-test
  "A bodyless defnz sources its body from the `.zig` co-located with the
  namespace's Clojure source: same stem, sibling path. The signature is
  the contract; the matching `pub fn` is the body. Fixtures write a
  `<stem>.clj` defining path and a sibling `<stem>.zig`, and bind `*file*`
  to the defining path so resolution matches a file-loaded namespace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.core :as core]
            [clj-zig :as zig]))

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-ns-file" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-zig [dir name content]
  (let [f (io/file dir name)]
    (spit f content)
    (.getPath f)))

(defn- define! [file form]
  (binding [*ns* (the-ns 'clj-zig.namespace-file-test)
            *file* file]
    (eval form)))

(defn- the-fn [sym]
  (ns-resolve 'clj-zig.namespace-file-test sym))

(deftest namespace-zig-file-swaps-the-extension
  (testing "the co-located .zig sits beside the namespace source"
    (is (= "/proj/src/app/geometry.zig"
           (core/namespace-zig-file "/proj/src/app/geometry.clj")))
    (is (= "/proj/src/app/geometry.zig"
           (core/namespace-zig-file "/proj/src/app/geometry.cljc")))))

(deftest namespace-zig-file-rejects-a-fileless-namespace
  (testing "a bodyless defnz needs a defining file"
    (let [ex (try (core/namespace-zig-file "NO_SOURCE_PATH")
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :clj-zig/no-namespace-file (:error/code (ex-data ex)))))))

(deftest bodyless-defnz-sources-the-co-located-pub-fn
  (let [dir (scratch-dir)
        clj (str (io/file dir "geometry.clj"))]
    (write-zig dir "geometry.zig"
               (str "pub fn hypotenuse(a: f64, b: f64) f64 {\n"
                    "    return @sqrt(a * a + b * b);\n"
                    "}\n"
                    "pub fn circle_area(r: f64) f64 {\n"
                    "    return 3.141592653589793 * r * r;\n"
                    "}\n"))
    (testing "the signature contract drives the matching pub fn"
      (define! clj `(core/defnz ~'hypotenuse [~'a :f64 ~'b :f64 :ret :f64]))
      (is (= 5.0 ((the-fn 'hypotenuse) 3.0 4.0))))
    (testing "a kebab-case name maps to the snake_case pub fn"
      (define! clj `(core/defnz ~'circle-area [~'r :f64 :ret :f64]))
      (is (= 12.566370614359172 ((the-fn 'circle-area) 2.0))))
    (testing "the source file and file mode are recorded for inspection"
      (let [v (the-fn 'hypotenuse)]
        (is (= :file (get-in (meta v) [:clj-zig/info :source-mode])))
        (is (= (str (io/file dir "geometry.zig"))
               (get-in (meta v) [:clj-zig/info :source-file])))
        (is (str/includes? (zig/source v) "pub fn hypotenuse"))))))

(deftest bodyless-body-may-call-a-namespace-defz-helper
  (testing "a private defz declaration is in scope for the co-located body"
    (let [dir (scratch-dir)
          clj (str (io/file dir "helpers.clj"))]
      (write-zig dir "helpers.zig" "pub fn tripled(x: f64) f64 {\n    return triple(x);\n}\n")
      (define! clj `(core/defz ~'helpers "fn triple(x: f64) f64 {\n    return x * 3.0;\n}\n"))
      (define! clj `(core/defnz ~'tripled [~'x :f64 :ret :f64]))
      (is (= 9.0 ((the-fn 'tripled) 3.0))))))

(deftest editing-the-co-located-file-recompiles
  (let [dir (scratch-dir)
        clj (str (io/file dir "step.clj"))]
    (write-zig dir "step.zig" "pub fn bump(x: i64) i64 {\n    return x + 1;\n}\n")
    (define! clj `(core/defnz ~'bump [~'x :i64 :ret :i64]))
    (is (= 6 ((the-fn 'bump) 5)))
    (testing "re-evaluating after an edit picks up the new body"
      (write-zig dir "step.zig" "pub fn bump(x: i64) i64 {\n    return x + 100;\n}\n")
      (define! clj `(core/defnz ~'bump [~'x :i64 :ret :i64]))
      (is (= 105 ((the-fn 'bump) 5))))
    (testing "a broken edit keeps the last good binding callable"
      (write-zig dir "step.zig" "pub fn bump(x: i64) i64 {\n    return nonsense;\n}\n")
      (is (thrown? clojure.lang.ExceptionInfo
                   (define! clj `(core/defnz ~'bump [~'x :i64 :ret :i64]))))
      (is (= 105 ((the-fn 'bump) 5))))))
