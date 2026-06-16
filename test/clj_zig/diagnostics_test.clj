(ns clj-zig.diagnostics-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.core :refer [defnz]]
            [clj-zig.diagnostics :as diagnostics]))

(def sample
  {:level :error
   :error/code :zig/compile-failed
   :message "Could not compile defnz app.core/add."
   :var 'app.core/add
   :signature '[x :i64 y :i64 :ret :i64]
   :zig/source-path ".clj-zig/cache/macos-aarch64/app.core/add-83a1c0/source.zig"
   :zig/stderr "source.zig:2:14: error: expected expression, found ';'"
   :zig/exit-code 1})

(deftest renders-starting-from-clojure
  (let [out (diagnostics/render sample)]
    (testing "the Var and signature come first"
      (is (str/starts-with? out "Could not compile defnz app.core/add."))
      (is (str/includes? out "Signature:"))
      (is (str/includes? out "  [x :i64\n   y :i64\n   :ret :i64]")))
    (testing "then the generated source path"
      (is (str/includes? out "Generated Zig:"))
      (is (str/includes? out "add-83a1c0/source.zig")))
    (testing "then the compiler output"
      (is (str/includes? out "Zig error:"))
      (is (str/includes? out "expected expression")))
    (testing "the Clojure header precedes the Zig error"
      (is (< (str/index-of out "Could not compile")
             (str/index-of out "Zig error:"))))))

(deftest renders-validation-diagnostic-without-zig-sections
  (let [out (diagnostics/render {:message "A signature must end with :ret <return-type>."
                                 :signature '[x :i64 y :i64]})]
    (is (str/starts-with? out "A signature must end with :ret"))
    (is (str/includes? out "Signature:"))
    (is (not (str/includes? out "Zig error:")))))

(deftest renders-malformed-signature-defensively
  (testing "a signature that is not the usual shape still renders"
    (is (str/includes? (diagnostics/render {:message "x" :signature '[oops]})
                       "[oops]"))))

(deftest failed-redefinition-records-the-attempt-and-keeps-last-good
  (let [define (fn [body] (eval `(defnz ~'doomed [~'x :i64 :ret :i64] ~body)))]
    (define "return x + 1;")
    (is (= 3 ((resolve 'doomed) 2)))
    (testing "a failed recompile throws the rendered diagnostic"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Could not compile defnz"
                            (define "return x + ;"))))
    (testing "the failed attempt is recorded on the Var for inspection"
      (let [attempt (:clj-zig/failed-attempt (meta (resolve 'doomed)))]
        (is (= :zig/compile-failed (:error/code attempt)))
        (is (= "return x + ;" (:body attempt)))
        (is (string? (:zig/stderr attempt)))))
    (testing "the last good binding still works"
      (is (= 3 ((resolve 'doomed) 2))))))
