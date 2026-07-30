(ns clj-zig.fs
  "Filesystem helpers shared by the shell namespaces. Effects on files live
  here so the cache and the compiler bootstrap reuse one implementation."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn absolute-path?
  "True for a path that is absolute on any host: a POSIX leading `/` or a
  Windows drive letter (`C:/`/`C:\\`). Does not defer to the host's
  `java.io.File` absolute rule, which calls POSIX `/opt/x` relative on
  Windows."
  [s]
  (let [s (str/replace s "\\" "/")]
    (boolean (or (str/starts-with? s "/")
                 (re-find #"^[A-Za-z]:/" s)))))

(defn delete-recursively!
  "Delete `file`, removing a directory's contents before the directory
  itself. A missing file is a no-op. A symbolic link is deleted directly,
  never followed: `isDirectory` follows links, so descending a symlink to
  a directory would delete the target's tree. The link itself is what the
  caller asked to remove."
  [^java.io.File file]
  (when (and (.isDirectory file)
             (not (java.nio.file.Files/isSymbolicLink (.toPath file))))
    (run! delete-recursively! (.listFiles file)))
  (.delete file))

(comment
  (delete-recursively! (io/file "/tmp/clj-zig-scratch")))
