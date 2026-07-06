(ns async-callbacks
  "Fire-and-forget callbacks from a native worker thread. A tiny Zig
  library spawns std.Thread and fires a void callback pointer; the
  Clojure side builds an async-upcall-stub that routes the invocation
  onto a caller-supplied executor. The fn runs on the executor thread,
  never on the native thread. Start a REPL with `clojure -M:repl`, load
  this file, and evaluate the comment block."
  (:require [clj-zig.compile :as compile]
            [clj-zig.foreign :as ff])
  (:import (java.lang.foreign Arena)
           (java.util.concurrent Executors)))

(def ^:private source
  "const std = @import(\"std\");

export fn start_counter(cb: *const fn (i64) callconv(.c) void) void {
    _ = std.Thread.spawn(.{}, run, .{cb}) catch return;
}

fn run(cb: *const fn (i64) callconv(.c) void) void {
    var i: i64 = 0;
    while (i < 5) : (i += 1) {
        cb(i);
    }
}")

(defn- build-lib []
  (let [dir (str (java.nio.file.Files/createTempDirectory
                  "clj-zig-async-example"
                  (make-array java.nio.file.attribute.FileAttribute 0)))]
    (:library (compile/compile!
               {:source       source
                :source-path  (str dir "/counter.zig")
                :library-path (str dir "/libcounter." (compile/dynamic-library-extension))
                :ctx          {:var 'async-callbacks/example}}))))

(comment
  ;; Build the library and bind the downcall.
  (def lib (ff/library-lookup (build-lib)))
  (def start-counter (ff/downcall lib "start_counter" :void [ff/c-ptr]))

  ;; The fn runs on the executor thread. The native worker thread
  ;; only reads the arg, enqueues, and returns void.
  (def exec (Executors/newSingleThreadExecutor))

  (def stub
    (ff/async-upcall-stub
     (fn [i]
       (println (str "count " i " on " (.getName (Thread/currentThread)))))
     (ff/descriptor :void [ff/c-long])
     (Arena/global)
     (ff/onto-executor exec)))

  ;; Hand the stub to native code. The downcall returns immediately;
  ;; the worker thread fires five times from a native thread.
  (ff/call start-counter stub)

  ;; Give the worker thread time to fire.
  (Thread/sleep 500)

  ;; Output (thread name varies):
  ;;   count 0 on pool-1-thread-1
  ;;   count 1 on pool-1-thread-1
  ;;   count 2 on pool-1-thread-1
  ;;   count 3 on pool-1-thread-1
  ;;   count 4 on pool-1-thread-1

  ;; The native thread never ran the fn; the executor thread did.

  ;; Stop native code from firing, then release the stub.
  (ff/release-stub! stub)
  (.shutdown exec))
