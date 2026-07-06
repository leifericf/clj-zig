(ns clj-zig.async-upcall-test
  "Async upcalls: the dispatch envelope, dispatch-map validation,
  convenience constructors, segment-copy helpers, and the full
  native-thread fire path. The pure-core tests need no Zig; the shell
  tests compile a tiny fixture with a worker thread."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.compile :as compile]
            [clj-zig.foreign :as ff])
  (:import (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.util.concurrent Executors CountDownLatch TimeUnit)))

;;; Pure core: dispatch map validation and constructors

(deftest onto-executor-builds-a-defaulted-dispatch-map
  (let [exec (Executors/newSingleThreadExecutor)
        dm   (ff/onto-executor exec)]
    (is (= :executor (:mode dm)))
    (is (identical? exec (:target dm)))
    (is (ifn? (:error-handler dm)))
    (.shutdown exec)))

(deftest onto-executor-accepts-an-error-handler-override
  (let [exec (Executors/newSingleThreadExecutor)
        eh   (fn [_ _])
        dm   (ff/onto-executor exec {:error-handler eh})]
    (is (identical? eh (:error-handler dm)))
    (.shutdown exec)))

(deftest onto-agent-builds-a-defaulted-dispatch-map
  (let [agnt (agent nil)
        dm   (ff/onto-agent agnt)]
    (is (= :agent (:mode dm)))
    (is (identical? agnt (:target dm)))
    (is (ifn? (:error-handler dm)))))

(deftest validate-dispatch-map-rejects-an-unknown-mode
  (let [ex (try (ff/validate-dispatch-map {:mode :bogus :target nil})
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :invalid-dispatch-map (:foreign/error (ex-data ex))))
    (is (= :bogus (:mode (ex-data ex))))))

(deftest validate-dispatch-map-rejects-executor-without-target
  (let [ex (try (ff/validate-dispatch-map {:mode :executor :target nil})
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :invalid-dispatch-map (:foreign/error (ex-data ex))))))

(deftest validate-dispatch-map-rejects-a-non-executor-target
  (let [ex (try (ff/validate-dispatch-map {:mode :executor :target "not an executor"})
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :invalid-dispatch-map (:foreign/error (ex-data ex))))))

(deftest validate-dispatch-map-rejects-a-non-agent-target
  (let [ex (try (ff/validate-dispatch-map {:mode :agent :target "not an agent"})
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :invalid-dispatch-map (:foreign/error (ex-data ex))))))

(deftest validate-dispatch-map-rejects-unknown-keys
  (let [ex (try (ff/validate-dispatch-map {:mode  :executor
                                            :target (Executors/newSingleThreadExecutor)
                                            :overflo :drop-oldest})
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :invalid-dispatch-map (:foreign/error (ex-data ex))))
    (is (= #{:overflo} (:unknown-keys (ex-data ex))))))

(deftest validate-dispatch-map-rejects-a-non-fn-error-handler
  (let [ex (try (ff/validate-dispatch-map {:mode  :executor
                                            :target (Executors/newSingleThreadExecutor)
                                            :error-handler "not a fn"})
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :invalid-dispatch-map (:foreign/error (ex-data ex))))))

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
  code: synchronous callers for the build lane, a detached std.Thread
  caller for the lifecycle lane, and a tight loop for the drain lane."
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
       "}\n"
       "export fn fire_loop(cb: *const fn () callconv(.c) void, n: i64) void {\n"
       "    var i: i64 = 0;\n"
       "    while (i < n) : (i += 1) {\n"
       "        cb();\n"
       "    }\n"
       "}\n"))

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-async" (make-array java.nio.file.attribute.FileAttribute 0))))

(def ^:private fixture-lib
  (delay
    (let [dir (scratch-dir)]
      (:library (compile/compile!
                 {:source       fixture-source
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
      (is (= 1 @fired) "no invocation after release")
      (finally (.shutdown exec)))))

(deftest release-removes-the-stub-from-the-registry
  (let [before (@#'clj-zig.foreign/registered-stub-count)
        exec   (Executors/newSingleThreadExecutor)
        desc   (ff/descriptor :void [])
        stub   (ff/async-upcall-stub (fn [])
                                     desc (Arena/global) (ff/onto-executor exec))]
    (try
      (is (= (inc before) (@#'clj-zig.foreign/registered-stub-count)))
      (ff/release-stub! stub)
      (is (= before (@#'clj-zig.foreign/registered-stub-count)))
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

;;; Error routing

(deftest async-fn-error-routes-to-the-error-handler
  (let [exec   (Executors/newSingleThreadExecutor)
        desc   (ff/descriptor :void [])
        latch  (CountDownLatch. 1)
        errs   (atom nil)
        eh     (fn [t inv]
                 (reset! errs {:throwable t :invocation inv})
                 (.countDown latch))
        stub   (ff/async-upcall-stub
                (fn [] (throw (ex-info "boom" {:where :inside})))
                desc (Arena/global)
                (ff/onto-executor exec {:error-handler eh}))
        fire   (ff/downcall (lookup) "fire_cb" :void [ff/c-ptr])]
    (try
      (ff/call fire stub)
      (is (.await latch 5 TimeUnit/SECONDS)
          "the error handler was called within the timeout")
      (is (instance? clojure.lang.ExceptionInfo (:throwable @errs)))
      (is (= "boom" (ex-message (:throwable @errs))))
      (is (keyword? (:stub (:invocation @errs)))
          "the invocation carries the stub id")
      (finally (.shutdown exec)))))

(deftest async-error-does-not-escape-into-the-native-caller
  (let [exec   (Executors/newSingleThreadExecutor)
        desc   (ff/descriptor :void [ff/c-long])
        latch  (CountDownLatch. 2)
        fired  (atom 0)
        stub   (ff/async-upcall-stub
                (fn [_]
                  (.countDown latch)
                  (swap! fired inc)
                  (throw (ex-info "boom" {})))
                desc (Arena/global)
                (ff/onto-executor exec {:error-handler (fn [_ _])}))
        fire   (ff/downcall (lookup) "fire_cb_i64" :void [ff/c-ptr ff/c-long])]
    (try
      (ff/call fire stub (long 1))
      (ff/call fire stub (long 2))
      (is (.await latch 5 TimeUnit/SECONDS)
          "both invocations fired despite the error")
      (is (= 2 @fired)
          "the native caller survived the first error and fired again")
      (finally (.shutdown exec)))))

(deftest rejected-execution-routes-to-the-error-handler
  (let [exec   (Executors/newSingleThreadExecutor)
        desc   (ff/descriptor :void [])
        latch  (CountDownLatch. 1)
        errs   (atom nil)
        eh     (fn [t inv]
                 (reset! errs {:throwable t :invocation inv})
                 (.countDown latch))
        stub   (ff/async-upcall-stub
                (fn [])
                desc (Arena/global)
                (ff/onto-executor exec {:error-handler eh}))
        fire   (ff/downcall (lookup) "fire_cb" :void [ff/c-ptr])]
    (.shutdownNow exec)
    (ff/call fire stub)
    (is (.await latch 5 TimeUnit/SECONDS)
        "a rejected execution reached the error handler, not the native caller")
    (is (instance? java.util.concurrent.RejectedExecutionException
                   (:throwable @errs)))))

(deftest error-handler-that-throws-does-not-propagate
  (let [exec  (Executors/newSingleThreadExecutor)
        desc  (ff/descriptor :void [])
        latch (CountDownLatch. 1)
        stub  (ff/async-upcall-stub
               (fn [] (throw (ex-info "fn boom" {})))
               desc (Arena/global)
               (ff/onto-executor exec
                 {:error-handler (fn [_ _] (.countDown latch)
                                   (throw (ex-info "eh boom" {})))}))
        fire  (ff/downcall (lookup) "fire_cb" :void [ff/c-ptr])]
    (try
      (ff/call fire stub)
      (is (.await latch 5 TimeUnit/SECONDS)
          "the error handler was called")
      (let [latch2 (CountDownLatch. 1)
            stub2  (ff/async-upcall-stub
                    (fn [] (.countDown latch2))
                    desc (Arena/global)
                    (ff/onto-executor exec))]
        (ff/call fire stub2)
        (is (.await latch2 5 TimeUnit/SECONDS)
            "the executor survived the error handler failure"))
      (finally (.shutdown exec)))))

(deftest agent-survives-an-error-handler-that-throws
  (let [agnt  (agent {:ok true})
        desc  (ff/descriptor :void [])
        latch (CountDownLatch. 1)
        stub  (ff/async-upcall-stub
               (fn [] (throw (ex-info "fn boom" {})))
               desc (Arena/global)
               (ff/onto-agent agnt
                 {:error-handler (fn [_ _] (.countDown latch)
                                   (throw (ex-info "eh boom" {})))}))
        fire  (ff/downcall (lookup) "fire_cb" :void [ff/c-ptr])]
    (ff/call fire stub)
    (is (.await latch 5 TimeUnit/SECONDS))
    (await-for 5000 agnt)
    (is (nil? (agent-error agnt))
        "the agent is not in an error state")
    (is (= {:ok true} @agnt)
        "the agent value is preserved after the handler failure")
    (let [latch2 (CountDownLatch. 1)
          stub2  (ff/async-upcall-stub
                  (fn [] (.countDown latch2))
                  desc (Arena/global)
                  (ff/onto-agent agnt))]
      (ff/call fire stub2)
      (is (.await latch2 5 TimeUnit/SECONDS)
          "the agent processed a new send after the handler failure"))))

(deftest multi-threaded-executor-runs-invocations-concurrently
  (let [pool  (Executors/newFixedThreadPool 4)
        desc  (ff/descriptor :void [])
        gate  (CountDownLatch. 4)
        stub  (ff/async-upcall-stub
               (fn []
                 (.countDown gate)
                 (.await gate 5 TimeUnit/SECONDS))
               desc (Arena/global) (ff/onto-executor pool))
        fire  (ff/downcall (lookup) "fire_loop" :void [ff/c-ptr ff/c-long])]
    (try
      (ff/call fire stub (long 4))
      (is (.await gate 10 TimeUnit/SECONDS)
          "four invocations ran concurrently on the thread pool")
      (finally (.shutdown pool)))))

;;; Shutdown drain

(deftest shutdown-async-stubs-marks-all-stubs-quiesced
  (let [exec  (Executors/newSingleThreadExecutor)
        desc  (ff/descriptor :void [])
        fired (atom 0)
        stub1 (ff/async-upcall-stub (fn [] (swap! fired inc))
                                    desc (Arena/global) (ff/onto-executor exec))
        stub2 (ff/async-upcall-stub (fn [] (swap! fired inc))
                                    desc (Arena/global) (ff/onto-executor exec))
        fire  (ff/downcall (lookup) "fire_cb" :void [ff/c-ptr])]
    (try
      (ff/shutdown-async-stubs)
      (is (zero? (@#'clj-zig.foreign/registered-stub-count)) "registry cleared")
      (ff/call fire stub1)
      (ff/call fire stub2)
      (is (zero? @fired) "quiesced stubs do not dispatch")
      (finally (.shutdown exec)))))

;;; Agent dispatch from a native thread

(deftest agent-dispatch-fires-from-a-native-thread
  (let [agnt   (agent nil)
        desc   (ff/descriptor :void [ff/c-long])
        latch  (CountDownLatch. 1)
        result (atom nil)
        stub   (ff/async-upcall-stub
                (fn [x] (reset! result (long x)) (.countDown latch))
                desc (Arena/global) (ff/onto-agent agnt))
        fire   (ff/downcall (lookup) "fire_cb_i64" :void [ff/c-ptr ff/c-long])]
    (ff/call fire stub (long 7))
    (is (.await latch 5 TimeUnit/SECONDS))
    (is (= 7 @result))))

(deftest agent-dispatch-routes-errors-to-the-handler
  (let [agnt   (agent nil)
        desc   (ff/descriptor :void [])
        latch  (CountDownLatch. 1)
        errs   (atom nil)
        stub   (ff/async-upcall-stub
                (fn [] (throw (ex-info "agent boom" {})))
                desc (Arena/global)
                (ff/onto-agent agnt {:error-handler
                                     (fn [t _] (reset! errs t) (.countDown latch))}))
        fire   (ff/downcall (lookup) "fire_cb" :void [ff/c-ptr])]
    (ff/call fire stub)
    (is (.await latch 5 TimeUnit/SECONDS))
    (is (= "agent boom" (ex-message @errs)))))

(deftest agent-dispatch-preserves-the-agent-value
  (let [agnt   (agent {:count 0})
        desc   (ff/descriptor :void [])
        latch  (CountDownLatch. 1)
        stub   (ff/async-upcall-stub
                (fn [] (.countDown latch))
                desc (Arena/global)
                (ff/onto-agent agnt))
        fire   (ff/downcall (lookup) "fire_cb" :void [ff/c-ptr])]
    (ff/call fire stub)
    (.await latch 5 TimeUnit/SECONDS)
    (await-for 5000 agnt)
    (is (= {:count 0} @agnt) "the agent value is preserved after dispatch")))

;;; Drain lane: high-volume fire drains completely

(deftest high-volume-fire-drains-completely
  (let [exec   (Executors/newSingleThreadExecutor)
        total  5000
        desc   (ff/descriptor :void [])
        latch  (CountDownLatch. total)
        stub   (ff/async-upcall-stub
                (fn [] (.countDown latch))
                desc (Arena/global)
                (ff/onto-executor exec))
        fire   (ff/downcall (lookup) "fire_loop" :void [ff/c-ptr ff/c-long])]
    (try
      (ff/call fire stub (long total))
      (is (.await latch 10 TimeUnit/SECONDS)
          "all invocations drained within the timeout")
      (finally (.shutdown exec)))))

;;; Routing latency smoke test

(deftest async-routing-latency-is-bounded
  (testing "the end-to-end routing path completes within a stated bound"
    (let [exec   (Executors/newSingleThreadExecutor)
          desc   (ff/descriptor :void [])
          latch  (CountDownLatch. 1)
          stamp  (volatile! 0)
          stub   (ff/async-upcall-stub
                  (fn [] (vreset! stamp (System/nanoTime)) (.countDown latch))
                  desc (Arena/global) (ff/onto-executor exec))
          fire   (ff/downcall (lookup) "fire_cb" :void [ff/c-ptr])]
      (try
        (let [start (System/nanoTime)]
          (ff/call fire stub)
          (.await latch 5 TimeUnit/SECONDS)
          (let [latency-ms (/ (double (- @stamp start)) 1000000.0)]
            (is (< latency-ms 100)
                (str "routing latency under 100ms, got " latency-ms "ms"))))
        (finally (.shutdown exec))))))
