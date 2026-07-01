(ns clj-zig.fs-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.fs :as fs]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "clj-zig-fs" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest deletes-a-nested-tree
  (let [root (temp-dir)
        nested (io/file root "a" "b")]
    (.mkdirs nested)
    (spit (io/file nested "leaf.txt") "x")
    (spit (io/file root "top.txt") "y")
    (is (.exists nested))
    (fs/delete-recursively! root)
    (is (not (.exists root)) "the whole tree is gone")))

(deftest a-missing-file-is-a-no-op
  (testing "deleting a path that does not exist neither throws nor errors"
    (let [gone (io/file (temp-dir) "never-created")]
      (is (not (.exists gone)))
      (is (false? (fs/delete-recursively! gone))
          "delete returns false for a file that was not there"))))

(deftest a-symlink-to-a-directory-is-removed-not-descended
  (testing "deleting a symlink does not follow it into the target's tree"
    (let [target (temp-dir)
          leaf   (io/file target "kept.txt")]
      (spit leaf "x")
      (let [link-root (temp-dir)
            link      (java.nio.file.Files/createSymbolicLink
                       (.toPath (io/file link-root "link"))
                       (.toPath target) (make-array java.nio.file.attribute.FileAttribute 0))]
        (is (.exists (.toFile link)))
        (fs/delete-recursively! (.toFile link))
        (is (not (.exists (.toFile link))) "the symlink itself is gone")
        (is (.exists leaf) "the target's contents survive")
        (fs/delete-recursively! target)))))
