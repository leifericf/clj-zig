(ns clj-zig.foreign-test
  "The foreign-function toolkit, exercised against a real prebuilt library.
  A tiny native library is compiled once when this namespace loads -- a few
  C-ABI exports plus a function-pointer caller for the upcall path -- and
  the tests open it, bind downcalls, hand it an upcall, and read bounded
  strings, so every primitive round-trips through actual native code rather
  than a mock. The pure helpers (descriptor, resolve-library,
  read-utf8-bounded, join-then-close-arena) are asserted directly."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.compile :as compile]
            [clj-zig.foreign :as ff])
  (:import (java.lang.foreign Arena MemorySegment ValueLayout)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent CountDownLatch TimeUnit)))

;; --- compile-once native fixture ----------------------------------------

(def ^:private fixture-source
  "A handful of C-ABI exports: scalar echoes across carriers, and a
  function-pointer caller that invokes a callback so the upcall path has a
  native caller to fire it."
  (str "export fn echo_i64(x: i64) i64 { return x; }\n"
       "export fn add_i32(a: i32, b: i32) i32 { return a + b; }\n"
       "export fn add_f64(a: f64, b: f64) f64 { return a + b; }\n"
       "export fn apply1(f: *const fn (i32) callconv(.c) i32, x: i32) i32 {\n"
       "    return f(x);\n"
       "}\n"))

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-foreign" (make-array java.nio.file.attribute.FileAttribute 0))))

(def ^:private fixture-lib
  (delay
    (let [dir (scratch-dir)]
      (:library (compile/compile!
                 {:source       fixture-source
                  :source-path  (str dir "/fixture.zig")
                  :library-path (str dir "/libfixture." (compile/dynamic-library-extension))
                  :ctx          {:var 'clj-zig.foreign-test/fixture}})))))

(defn- lookup [] (ff/library-lookup @fixture-lib))

;; --- opening a library --------------------------------------------------

(deftest library-lookup-opens-a-real-library
  (is (instance? java.lang.foreign.SymbolLookup (lookup))))

(deftest library-lookup-degrades-a-bad-path-as-data
  (testing "a missing library throws a tagged ex-info, not a raw FFM error"
    (let [ex (try (ff/library-lookup "/no/such/libnope.dylib")
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :library-open-failed (:foreign/error (ex-data ex))))
      (is (= "/no/such/libnope.dylib" (:path (ex-data ex)))))))

;; --- symbols ------------------------------------------------------------

(deftest symbol-presence-is-data
  (let [lk (lookup)]
    (is (ff/symbol-present? lk "echo_i64"))
    (is (not (ff/symbol-present? lk "no_such_symbol")))))

(deftest find-symbol-throws-a-tagged-error-when-absent
  (let [ex (try (ff/find-symbol (lookup) "no_such_symbol")
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :symbol-not-found (:foreign/error (ex-data ex))))
    (is (= "no_such_symbol" (:symbol (ex-data ex))))))

;; --- downcalls ----------------------------------------------------------

(deftest downcall-round-trips-scalars
  (let [lk (lookup)]
    (testing "i64 echo"
      (is (= 42 (ff/call (ff/downcall lk "echo_i64" ff/c-long [ff/c-long]) (long 42)))))
    (testing "i32 add"
      (is (= 7 (ff/call (ff/downcall lk "add_i32" ff/c-int [ff/c-int ff/c-int])
                        (int 3) (int 4)))))
    (testing "f64 add"
      (is (= 5.0 (ff/call (ff/downcall lk "add_f64" ff/c-double [ff/c-double ff/c-double])
                          (double 2.0) (double 3.0)))))))

(deftest downcall-caches-the-handle-per-distinct-call
  (let [lk (lookup)
        a  (ff/downcall lk "echo_i64" ff/c-long [ff/c-long])
        b  (ff/downcall lk "echo_i64" ff/c-long [ff/c-long])
        c  (ff/downcall lk "add_i32" ff/c-int [ff/c-int ff/c-int])]
    (testing "the same [lookup name ret args] returns the identical handle"
      (is (identical? a b)))
    (testing "a different signature is a different handle"
      (is (not (identical? a c))))))

(deftest downcall-does-no-linker-work-on-the-cached-path
  ;; The hot-path guarantee: once bound, re-resolving the same call neither
  ;; re-links nor allocates a new handle, so a per-frame caller pays the
  ;; bind cost at most once.
  (let [lk    (lookup)
        first (ff/downcall lk "echo_i64" ff/c-long [ff/c-long])
        again (repeatedly 1000 #(ff/downcall lk "echo_i64" ff/c-long [ff/c-long]))]
    (is (every? #(identical? first %) again))))

;; --- upcalls ------------------------------------------------------------

(deftest upcall-stub-lets-native-code-call-a-clojure-fn
  (let [lk     (lookup)
        apply1 (ff/downcall lk "apply1" ff/c-int [ff/c-ptr ff/c-int])
        desc   (ff/descriptor ff/c-int [ff/c-int])]
    (with-open [arena (Arena/ofConfined)]
      (testing "the native caller invokes the Clojure callback and returns its result"
        (let [stub (ff/upcall-stub (fn [x] (int (* 10 (long x)))) desc arena)]
          (is (= 70 (ff/call apply1 stub (int 7))))))
      (testing "a different callback flows through the same stub shape"
        (let [stub (ff/upcall-stub (fn [x] (int (+ 1 (long x)))) desc arena)]
          (is (= 8 (ff/call apply1 stub (int 7)))))))))

;; --- descriptor ---------------------------------------------------------

(deftest descriptor-builds-void-and-valued-signatures
  (testing ":void yields a void descriptor with the given args"
    (let [d (ff/descriptor :void [ff/c-ptr ff/c-int])]
      (is (false? (.isPresent (.returnLayout d))))
      (is (= 2 (count (.argumentLayouts d))))))
  (testing "a value layout yields a returning descriptor"
    (let [d (ff/descriptor ff/c-int [ff/c-int ff/c-int])]
      (is (.isPresent (.returnLayout d)))
      (is (= 2 (count (.argumentLayouts d)))))))

;; --- resolve-library ----------------------------------------------------

(deftest resolve-library-prefers-env-then-existing-candidate-then-default
  (testing "a set env var wins"
    ;; PATH is always set; assert the env branch returns its value.
    (is (= (System/getenv "PATH")
           (ff/resolve-library {:env ["PATH"] :candidates ["/no/such"] :default "/fallback"}))))
  (testing "with no env, the first existing candidate wins"
    (is (= @fixture-lib
           (ff/resolve-library {:env ["CLJ_ZIG_NO_SUCH_VAR"]
                                :candidates ["/no/such/a" @fixture-lib]
                                :default "/fallback"}))))
  (testing "with no env and no existing candidate, the default is used"
    (is (= "/fallback"
           (ff/resolve-library {:env ["CLJ_ZIG_NO_SUCH_VAR"]
                                :candidates ["/no/such/a" "/no/such/b"]
                                :default "/fallback"}))))
  (testing "a default of nil is a valid (degraded) result"
    (is (nil? (ff/resolve-library {:env [] :candidates ["/no/such"] :default nil})))))

;; --- read-utf8-bounded --------------------------------------------------

(defn- nul-terminated
  "Allocate `s` as UTF-8 bytes plus a NUL terminator in `arena`."
  ^MemorySegment [^Arena arena ^String s]
  (let [bs  (.getBytes s StandardCharsets/UTF_8)
        seg (.allocate arena (long (inc (alength bs))))]
    (MemorySegment/copy bs (int 0) seg ValueLayout/JAVA_BYTE (long 0) (int (alength bs)))
    (.set seg ValueLayout/JAVA_BYTE (long (alength bs)) (byte 0))
    seg))

(deftest read-utf8-bounded-reads-a-terminated-string
  (with-open [arena (Arena/ofConfined)]
    (testing "a plain ascii string"
      (is (= "hello" (ff/read-utf8-bounded (nul-terminated arena "hello") 4096 arena))))
    (testing "a multi-byte UTF-8 string round-trips by bytes, not chars"
      (is (= "héllo wörld" (ff/read-utf8-bounded (nul-terminated arena "héllo wörld") 4096 arena))))
    (testing "an empty string is the empty result, not nil"
      (is (= "" (ff/read-utf8-bounded (nul-terminated arena "") 4096 arena))))))

(deftest read-utf8-bounded-degrades-null-and-unterminated-as-nil
  (with-open [arena (Arena/ofConfined)]
    (testing "a NULL pointer is nil"
      (is (nil? (ff/read-utf8-bounded MemorySegment/NULL 4096 arena))))
    (testing "no NUL within the cap is nil, never an unbounded read"
      (let [seg (.allocate arena (long 16))]
        (dotimes [i 16] (.set seg ValueLayout/JAVA_BYTE (long i) (byte (int \A))))
        (is (nil? (ff/read-utf8-bounded seg 8 arena)))))
    (testing "the cap is inclusive: a NUL exactly at the cap is read"
      (let [seg (.allocate arena (long 5))]
        (dotimes [i 4] (.set seg ValueLayout/JAVA_BYTE (long i) (byte (int \x))))
        (.set seg ValueLayout/JAVA_BYTE (long 4) (byte 0))
        (is (= "xxxx" (ff/read-utf8-bounded seg 4 arena)))))))

;; --- join-then-close-arena ----------------------------------------------

(deftest join-then-close-arena-closes-only-after-the-worker-dies
  (testing "a worker that has exited lets the arena close"
    (let [arena  (Arena/ofShared)
          done   (CountDownLatch. 1)
          worker (doto (Thread. (fn [] (.countDown done))) (.start))]
      (.await done 2 TimeUnit/SECONDS)
      (ff/join-then-close-arena worker arena 2000)
      (is (thrown? IllegalStateException (.allocate arena (long 8)))
          "the arena is closed: allocation now throws")))
  (testing "a still-running worker leaves the arena open (the close is gated)"
    (let [arena   (Arena/ofShared)
          release (CountDownLatch. 1)
          worker  (doto (Thread. (fn [] (.await release 5 TimeUnit/SECONDS))) (.start))]
      ;; The worker is parked; a short-timeout join must NOT close the arena.
      (ff/join-then-close-arena worker arena 100)
      (is (instance? MemorySegment (.allocate arena (long 8)))
          "the arena is still usable while the worker is alive")
      (.countDown release)
      (.join worker 2000)
      ;; Now the worker is dead; the tail closes the arena.
      (ff/join-then-close-arena worker arena 2000)
      (is (thrown? IllegalStateException (.allocate arena (long 8)))))))
