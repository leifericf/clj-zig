(ns clj-zig.async-upcall-test
  "Async upcalls: the dispatch envelope, dispatch-map validation,
  convenience constructors, segment-copy helpers, and the full
  native-thread fire path. The pure-core tests need no Zig; the shell
  tests compile a tiny fixture with a worker thread."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.foreign :as ff])
  (:import (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.util.concurrent Executors ThreadPoolExecutor TimeUnit)))

;;; Pure core: dispatch map validation and constructors

(deftest onto-executor-builds-a-defaulted-dispatch-map
  (let [exec (Executors/newSingleThreadExecutor)
        dm   (ff/onto-executor exec)]
    (is (= :executor (:mode dm)))
    (is (identical? exec (:target dm)))
    (is (= :drop-oldest (:overflow dm)))
    (is (= 1024 (:bound dm)))
    (is (true? (:copy-segments? dm)))
    (is (ifn? (:error-handler dm)))))

(deftest onto-executor-honors-overrides
  (let [exec (Executors/newSingleThreadExecutor)
        eh    (fn [_ _])
        dm    (ff/onto-executor exec {:overflow       :block-timeout
                                       :bound          512
                                       :copy-segments? false
                                       :error-handler  eh})]
    (is (= :block-timeout (:overflow dm)))
    (is (= 512 (:bound dm)))
    (is (false? (:copy-segments? dm)))
    (is (identical? eh (:error-handler dm)))))

(deftest onto-agent-builds-a-defaulted-dispatch-map
  (let [agnt (agent nil)
        dm   (ff/onto-agent agnt)]
    (is (= :agent (:mode dm)))
    (is (identical? agnt (:target dm)))
    (is (= :drop-oldest (:overflow dm)))))

(deftest validate-dispatch-map-rejects-an-unknown-mode
  (let [ex (try (ff/validate-dispatch-map {:mode :bogus :target nil})
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :invalid-dispatch-map (:foreign/error (ex-data ex))))
    (is (= :bogus (:mode (ex-data ex))))))

(deftest validate-dispatch-map-rejects-executor-without-target
  (let [ex (try (ff/validate-dispatch-map {:mode :executor :target nil})
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :invalid-dispatch-map (:foreign/error (ex-data ex))))))

(deftest validate-dispatch-map-rejects-custom-without-fn
  (let [ex (try (ff/validate-dispatch-map {:mode :custom :fn nil})
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :invalid-dispatch-map (:foreign/error (ex-data ex))))))

(deftest validate-dispatch-map-rejects-bad-overflow
  (let [ex (try (ff/onto-executor (Executors/newSingleThreadExecutor)
                                  {:overflow :caller-runs})
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :invalid-dispatch-map (:foreign/error (ex-data ex))))))

(deftest validate-dispatch-map-rejects-non-positive-bound
  (doseq [bad [0 -1 nil]]
    (let [ex (try (ff/onto-executor (Executors/newSingleThreadExecutor)
                                    {:bound bad})
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :invalid-dispatch-map (:foreign/error (ex-data ex)))
          (str "bound " (pr-str bad) " is rejected")))))

(deftest custom-mode-accepts-a-fn
  (let [sink (fn [_])
        dm   (ff/validate-dispatch-map {:mode :custom :fn sink})]
    (is (= :custom (:mode dm)))
    (is (identical? sink (:fn dm)))))

;;; Pure core: segment copy helper

(deftest read-bytes-bounded-copies-up-to-the-cap
  (with-open [arena (Arena/ofShared)]
    (letfn [(alloc [bs]
              (let [seg (.allocate arena ValueLayout/JAVA_BYTE (long (alength bs)))]
                (MemorySegment/copy bs (int 0) seg ValueLayout/JAVA_BYTE (long 0) (int (alength bs)))
                seg))]
      (testing "copies all bytes when the segment fits the cap"
        (let [src  (byte-array (map byte (range 10)))
              out  (ff/read-bytes-bounded (alloc src) 100)]
          (is (= (seq src) (seq out)))
          (is (= 10 (alength out)))))
      (testing "copies only up to the cap when the segment is larger"
        (let [seg  (alloc (byte-array (repeat 100 (byte 0x41))))
              out  (ff/read-bytes-bounded seg 10)]
          (is (= 10 (alength out)))
          (is (every? #(= 0x41 (int %)) out))))
      (testing "returns nil for NULL"
        (is (nil? (ff/read-bytes-bounded MemorySegment/NULL 100)))))))

;;; Pure core: envelope and error handler (private, via @#' var)

(deftest make-envelope-carries-stub-args-and-stamp
  (let [envelope (@#'clj-zig.foreign/make-envelope :my-stub [1 2 3])]
    (is (= :my-stub (:stub envelope)))
    (is (= [1 2 3] (:args envelope)))
    (is (pos-int? (:stamp envelope)))))

(deftest default-error-handler-writes-to-err-and-returns-nil
  (let [sw (java.io.StringWriter.)]
    (binding [*err* sw]
      (let [result (@#'clj-zig.foreign/default-error-handler
                    (ex-info "boom" {})
                    {:stub :s :args [1] :stamp 0})]
        (is (nil? result))
        (is (re-find #"boom" (.toString sw)))
        (is (re-find #":s" (.toString sw)))))))
