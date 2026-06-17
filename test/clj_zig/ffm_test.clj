(ns clj-zig.ffm-test
  "The native edge translates a denied native-access attempt into a clear,
  actionable diagnostic instead of the raw FFM error."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.ffm :as ffm])
  (:import (java.lang IllegalCallerException)))

(deftest a-native-access-denial-names-the-flag-and-alias
  (let [ex (#'ffm/native-access-disabled (IllegalCallerException. "denied"))]
    (is (= :clj-zig/native-access-disabled (:error/code (ex-data ex))))
    (is (str/includes? (ex-message ex) "--enable-native-access=ALL-UNNAMED")
        "the message names the exact JVM flag")
    (is (str/includes? (ex-message ex) ":repl")
        "and points at the ready-made aliases")
    (is (= "--enable-native-access=ALL-UNNAMED" (:clj-zig/jvm-option (ex-data ex))))
    (is (instance? IllegalCallerException (ex-cause ex))
        "the raw FFM error is preserved as the cause")))

(deftest restricted-calls-translate-only-a-native-access-denial
  (testing "a denied restricted call becomes the clear diagnostic"
    (let [ex (try (#'ffm/with-native-access
                   (fn [] (throw (IllegalCallerException. "restricted method called"))))
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :clj-zig/native-access-disabled (:error/code (ex-data ex))))))
  (testing "a successful call passes its value through untouched"
    (is (= 42 (#'ffm/with-native-access (constantly 42)))))
  (testing "an unrelated failure is not mistaken for a native-access denial"
    (is (thrown? IllegalStateException
                 (#'ffm/with-native-access
                  (fn [] (throw (IllegalStateException. "something else"))))))))
