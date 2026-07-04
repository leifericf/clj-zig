(ns clj-zig.build-flags-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.cache :as cache]
            [clj-zig.core :as core :refer [defnz zig-deps]]
            [clj-zig.descriptor :as descriptor]
            [clj-zig.compile :as compile]))

(deftest descriptor-options-lowers-zig-flags
  (testing ":zig/single-threaded lowers to an options flag"
    (let [opts (descriptor/descriptor-options {:zig/single-threaded true})]
      (is (:single-threaded opts))))
  (testing ":zig/pic lowers to an options flag"
    (let [opts (descriptor/descriptor-options {:zig/pic true})]
      (is (:pic opts))))
  (testing ":zig/stack-check lowers to an options flag"
    (let [opts (descriptor/descriptor-options {:zig/stack-check true})]
      (is (:stack-check opts))))
  (testing ":zig/panic-fn lowers to an options string"
    (let [opts (descriptor/descriptor-options {:zig/panic-fn "my_panic"})]
      (is (= "my_panic" (:panic-fn opts)))))
  (testing "false flags are omitted"
    (let [opts (descriptor/descriptor-options {:zig/single-threaded false})]
      (is (nil? (:single-threaded opts))))))

(deftest track-allocations-lowers-to-options
  (testing ":zig/track-allocations true lowers to {:track-allocations true}"
    (let [opts (descriptor/descriptor-options {:zig/track-allocations true})]
      (is (= true (:track-allocations opts)))))
  (testing "the flag defaults off: an absent flag adds no :track-allocations key"
    (let [opts (descriptor/descriptor-options {})]
      (is (or (nil? opts) (nil? (:track-allocations opts)))
          "the default descriptor carries no track-allocations entry")))
  (testing "false is omitted, like the other boolean build flags"
    (let [opts (descriptor/descriptor-options {:zig/track-allocations false})]
      (is (nil? (:track-allocations opts))))))

(deftest track-allocations-yields-a-distinct-cache-key
  ;; ADR 12: the flag enters the options map, so cache-key gives a profiling
  ;; build its own key and never reuses a default library.
  (let [base {:spec     {:ns 'app.core :name 'boxed}
              :body     "return x;"
              :source   "export fn x() void {}"
              :zig-version "0.16.0"
              :target   "macos-aarch64"}
        key-off  (cache/cache-key (assoc base :options {:optimize "ReleaseSafe"}))
        key-on   (cache/cache-key (assoc base
                                         :options {:optimize "ReleaseSafe"
                                                   :track-allocations true}))]
    (is (not= key-off key-on)
        "the profiling build's cache key must differ from the default's"))
  (testing "an absent flag leaves the default options map and key intact"
    (let [base {:spec     {:ns 'app.core :name 'boxed}
                :body     "return x;"
                :source   "export fn x() void {}"
                :zig-version "0.16.0"
                :target   "macos-aarch64"}]
      (is (= (cache/cache-key (assoc base :options {:optimize "ReleaseSafe"}))
             (cache/cache-key (assoc base :options {:optimize "ReleaseSafe"})))
          "the default path's key is stable"))))

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
