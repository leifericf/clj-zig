(ns clj-zig.build-flags-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.core :as core :refer [defnz zig-deps]]
            [clj-zig.compile :as compile]))

(deftest descriptor-options-lowers-zig-flags
  (testing ":zig/single-threaded lowers to an options flag"
    (let [opts (core/descriptor-options {:zig/single-threaded true})]
      (is (:single-threaded opts))))
  (testing ":zig/pic lowers to an options flag"
    (let [opts (core/descriptor-options {:zig/pic true})]
      (is (:pic opts))))
  (testing ":zig/stack-check lowers to an options flag"
    (let [opts (core/descriptor-options {:zig/stack-check true})]
      (is (:stack-check opts))))
  (testing ":zig/panic-fn lowers to an options string"
    (let [opts (core/descriptor-options {:zig/panic-fn "my_panic"})]
      (is (= "my_panic" (:panic-fn opts)))))
  (testing "false flags are omitted"
    (let [opts (core/descriptor-options {:zig/single-threaded false})]
      (is (nil? (:single-threaded opts))))))

(deftest build-arguments-includes-zig-flags
  (let [args (compile/build-arguments "zig"
               {:source-abs "src.zig"
                :library-abs "lib.so"
                :options {:single-threaded true :pic true}
                :global-cache-dir ".cache"})]
    (is (some #(= "-fsingle-threaded" %) args))
    (is (some #(= "-fPIC" %) args))))

(deftest build-arguments-includes-panic-fn
  (let [args (compile/build-arguments "zig"
               {:source-abs "src.zig"
                :library-abs "lib.so"
                :options {:panic-fn "my_panic"}
                :global-cache-dir ".cache"})]
    (is (some #(= "-fpanic-fn=my_panic" %) args))))

(deftest unknown-zig-key-is-rejected
  (testing "an unrecognized :zig/* key throws at macro time"
    (is (thrown? Exception
                 (macroexpand
                  `(defnz ~'bad-fn
                     {:zig/bogus true}
                     [~'x :i64 :ret :i64]
                     "return x;"))))))

(deftest unknown-zig-key-in-zig-deps-is-rejected
  (is (thrown? Exception
               (macroexpand
                `(zig-deps {:zig/bogus true})))))

(deftest zig-deps-accepts-panic-fn
  (testing ":zig/panic-fn in zig-deps registers without error"
    (is (some? (macroexpand `(zig-deps {:zig/panic-fn "my_panic"}))))))
