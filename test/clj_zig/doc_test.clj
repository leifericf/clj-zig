(ns clj-zig.doc-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clj-zig.core :refer [defnz]]
            [clj-zig.doc :as doc]))

(defnz doc-add
  "Adds two signed integers."
  [x :i64 y :i64 :ret :i64]
  "return x + y;")

(deftest emit-var-produces-markdown
  (let [md (doc/emit-var #'doc-add)]
    (is (str/includes? md "### doc-add"))
    (is (str/includes? md "Adds two signed integers."))
    (is (str/includes? md "| `x` | `i64` |"))
    (is (str/includes? md "| `y` | `i64` |"))
    (is (str/includes? md "**Returns:** `i64`"))))

(deftest emit-namespace-lists-all-defnz-vars
  (let [md (doc/emit-namespace 'clj-zig.doc-test)]
    (is (str/includes? md "# clj-zig.doc-test"))
    (is (str/includes? md "doc-add"))))

(deftest emit-var-on-non-defnz-var-returns-nil
  (is (nil? (doc/emit-var #'doc/emit-var))))
