(ns clj-zig.async-upcall-test
  "Async upcalls: the dispatch envelope, dispatch-map validation,
  convenience constructors, segment-copy helpers, and the full
  native-thread fire path. The pure-core tests need no Zig; the shell
  tests compile a tiny fixture with a worker thread."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.compile :as compile]
            [clj-zig.foreign :as ff])
  (:import (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.util.concurrent Executors TimeUnit CountDownLatch)))

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

;;; Shell: async-upcall-stub build, route, and release

(def ^:private fixture-source
  "C-ABI exports exercising the async routing path through real native
  code: synchronous callers for the build-and-release lane, and a
  detached std.Thread caller for the lifecycle lane."
  (str "const std = @import(\"std\");\n\n"
       "export fn fire_cb(cb: *const fn () callconv(.c) void) void {\n"
       "    cb();\n"
       "}\n"
       "export fn fire_cb_i64(cb: *const fn (i64) callconv(.c) void, x: i64) void {\n"
       "    cb(x);\n"
       "}\n"
       "export fn fire_async(cb: *const fn () callconv(.c) void) void {\n"
       "    _ = std.Thread.spawn(.{}, worker, .{cb}) catch return;\n"
       "}\n"
       "fn worker(cb: *const fn () callconv(.c) void) void {\n"
       "    cb();\n"
       "}\n"))

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-async" (make-array java.nio.file.attribute.FileAttribute 0))))

(def ^:private fixture-lib
  (delay
    (let [dir (scratch-dir)]
      (:library (compile/compile!
                 {:source       (apply str fixture-source)
                  :source-path  (str dir "/fixture.zig")
                  :library-path (str dir "/libasync." (compile/dynamic-library-extension))
                  :ctx          {:var 'clj-zig.async-upcall-test/fixture}})))))

(defn- lookup [] (ff/library-lookup @fixture-lib))

(deftest async-upcall-stub-rejects-a-non-void-descriptor
  (let [exec (Executors/newSingleThreadExecutor)
        desc  (ff/descriptor ff/c-int [ff/c-int])]
    (try
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"void descriptor"
           (ff/async-upcall-stub (fn [_]) desc (Arena/global) (ff/onto-executor exec))))
      (finally (.shutdown exec)))))

(deftest async-upcall-stub-rejects-a-confined-arena
  (let [exec (Executors/newSingleThreadExecutor)
        desc  (ff/descriptor :void [])]
    (try
      (with-open [arena (Arena/ofConfined)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"global Arena"
             (ff/async-upcall-stub (fn []) desc arena (ff/onto-executor exec)))))
      (finally (.shutdown exec)))))

(deftest async-stub-routes-to-an-executor
  (let [exec   (Executors/newSingleThreadExecutor)
        desc   (ff/descriptor :void [ff/c-long])
        latch  (CountDownLatch. 1)
        result (atom nil)
        stub   (ff/async-upcall-stub
                (fn [x]
                  (reset! result [(Thread/currentThread) (long x)])
                  (.countDown latch))
                desc (Arena/global) (ff/onto-executor exec))
        fire   (ff/downcall (lookup) "fire_cb_i64" :void [ff/c-ptr ff/c-long])]
    (try
      (ff/call fire stub (long 42))
      (is (.await latch 5 TimeUnit/SECONDS))
      (is (= 42 (second @result)) "the arg round-trips through the routing path")
      (is (not (identical? (Thread/currentThread) (first @result)))
          "the fn ran on the executor thread, not the calling thread")
      (finally (.shutdown exec)))))

(deftest async-stub-routes-to-an-agent
  (let [agnt   (agent nil)
        desc   (ff/descriptor :void [ff/c-long])
        latch  (CountDownLatch. 1)
        result (atom nil)
        stub   (ff/async-upcall-stub
                (fn [x]
                  (reset! result (long x))
                  (.countDown latch))
                desc (Arena/global) (ff/onto-agent agnt))
        fire   (ff/downcall (lookup) "fire_cb_i64" :void [ff/c-ptr ff/c-long])]
    (ff/call fire stub (long 99))
    (is (.await latch 5 TimeUnit/SECONDS))
    (is (= 99 @result) "the arg round-trips through the agent dispatch")))

(deftest release-stub-stops-dispatch
  (let [exec   (Executors/newSingleThreadExecutor)
        desc   (ff/descriptor :void [])
        latch  (CountDownLatch. 1)
        fired  (atom 0)
        stub   (ff/async-upcall-stub
                (fn [] (swap! fired inc) (.countDown latch))
                desc (Arena/global) (ff/onto-executor exec))
        fire   (ff/downcall (lookup) "fire_cb" :void [ff/c-ptr])]
    (try
      (ff/call fire stub)
      (.await latch 5 TimeUnit/SECONDS)
      (is (= 1 @fired) "the pre-release invocation fired")
      (ff/release-stub! stub)
      (ff/call fire stub)
      (Thread/sleep 200)
      (is (= 1 @fired) "no invocation after release")
      (finally (.shutdown exec)))))

(deftest release-removes-the-stub-from-the-registry
  (let [before (ff/registered-stub-count)
        exec   (Executors/newSingleThreadExecutor)
        desc   (ff/descriptor :void [])
        stub   (ff/async-upcall-stub (fn [])
                                     desc (Arena/global) (ff/onto-executor exec))]
    (try
      (is (= (inc before) (ff/registered-stub-count)))
      (ff/release-stub! stub)
      (is (= before (ff/registered-stub-count)))
      (finally (.shutdown exec)))))

;;; Lifecycle: fire from a native worker thread

(deftest async-stub-fires-from-a-native-thread
  (let [exec    (Executors/newSingleThreadExecutor)
        desc    (ff/descriptor :void [])
        latch   (CountDownLatch. 1)
        caller  (Thread/currentThread)
        result  (atom nil)
        stub    (ff/async-upcall-stub
                 (fn []
                   (reset! result (Thread/currentThread))
                   (.countDown latch))
                 desc (Arena/global) (ff/onto-executor exec))
        fire    (ff/downcall (lookup) "fire_async" :void [ff/c-ptr])]
    (try
      (ff/call fire stub)
      (is (.await latch 5 TimeUnit/SECONDS)
          "the callback landed from the native worker thread")
      (is (not (identical? caller @result))
          "the fn ran on the executor thread, not the registering caller")
      (finally (.shutdown exec)))))

(deftest async-stub-survives-after-the-registering-call-returns
  (let [exec   (Executors/newSingleThreadExecutor)
        desc   (ff/descriptor :void [])
        latch  (CountDownLatch. 1)
        fired? (atom false)
        stub   (ff/async-upcall-stub
                (fn [] (reset! fired? true) (.countDown latch))
                desc (Arena/global) (ff/onto-executor exec))
        fire   (ff/downcall (lookup) "fire_async" :void [ff/c-ptr])]
    (try
      (ff/call fire stub)
      (is (.await latch 5 TimeUnit/SECONDS))
      (is @fired? "the global Arena kept the stub valid after the downcall returned")
      (finally (.shutdown exec)))))
