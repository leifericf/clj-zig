(ns clj-zig.async-spike-test
  "Spike: confirm FFM attaches an unmanaged native thread transparently.
  A Zig body spawns std.Thread that fires a void callback pointer after a
  short sleep. The Clojure side builds an upcall stub against the global
  Arena, passes the pointer, and asserts the callback lands from a
  different thread than the caller. If this is green, the routing stub
  can rely on FFM's transparent attach across the supported JDK range."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.compile :as compile]
            [clj-zig.foreign :as ff])
  (:import (java.lang.foreign Arena)))

(def ^:private spike-source
  "const std = @import(\"std\");

export fn fire_async(cb: *const fn () callconv(.c) void) void {
    _ = std.Thread.spawn(.{}, worker, .{cb}) catch return;
}

fn worker(cb: *const fn () callconv(.c) void) void {
    cb();
}
")

(defn- scratch-dir []
  (str (java.nio.file.Files/createTempDirectory
        "clj-zig-async-spike" (make-array java.nio.file.attribute.FileAttribute 0))))

(def ^:private spike-lib
  (delay
    (let [dir (scratch-dir)]
      (:library (compile/compile!
                 {:source       spike-source
                  :source-path  (str dir "/spike.zig")
                  :library-path (str dir "/libspike." (compile/dynamic-library-extension))
                  :ctx          {:var 'clj-zig.async-spike-test/spike}})))))

(defn- lookup [] (ff/library-lookup @spike-lib))

(deftest native-thread-fires-an-async-upcall-stub
  (testing "FFM attaches the unmanaged native thread transparently"
    (let [fire-async (ff/downcall (lookup) "fire_async" :void [ff/c-ptr])
          called     (promise)
          stub       (ff/upcall-stub (fn [] (deliver called (Thread/currentThread)))
                                     (ff/descriptor :void [])
                                     (Arena/global))]
      (ff/call fire-async stub)
      (let [result (deref called 5000 ::timeout)]
        (is (not= ::timeout result)
            "the callback fired within the timeout")
        (is (instance? Thread result)
            "the callback delivered a real thread")
        (is (not (identical? (Thread/currentThread) result))
            "the callback ran on a different thread than the registering caller")))))
