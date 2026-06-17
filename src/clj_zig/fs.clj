(ns clj-zig.fs
  "Filesystem helpers shared by the shell namespaces. Effects on files live
  here so the cache and the toolchain bootstrap reuse one implementation."
  (:require [clojure.java.io :as io]))

(defn delete-recursively!
  "Delete `file`, removing a directory's contents before the directory
  itself. A missing file is a no-op."
  [^java.io.File file]
  (when (.isDirectory file)
    (run! delete-recursively! (.listFiles file)))
  (.delete file))

(comment
  (delete-recursively! (io/file "/tmp/clj-zig-scratch")))
