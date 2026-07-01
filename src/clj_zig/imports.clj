(ns clj-zig.imports
  "Resolve the multi-file Zig import graph of a file-mode body so it
  compiles inside the content-addressed cache directory.

  A file-mode body is inlined into the generated `source.zig`, so its
  `@import(\"util.zig\")` statements resolve relative to `source.zig`'s
  location. To make a body's relative imports resolve, the imported files
  are reproduced inside the cache entry at their paths relative to the body
  file. `source.zig` is the module root, so its directory is the module
  path; sibling and subdirectory imports resolve and may themselves use
  `..` as long as they stay within it. An import that escapes the body's
  directory hits Zig's own \"import outside module path\" rule, exactly as
  it would were the body compiled directly as a root file.

  `scan` is pure; `closure` reads the filesystem."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private import-pattern
  #"@import\(\s*\"([^\"]+)\"\s*\)")

(defn scan
  "The relative `.zig` files a body imports by path: every
  `@import(\"...\")` target that names a relative `.zig` file. Compiler
  modules (`std`, `builtin`), absolute paths, and non-`.zig` targets are
  not files to reproduce and are skipped. Pure."
  [zig-text]
  (->> (re-seq import-pattern zig-text)
       (map second)
       (filter (fn [t] (and (str/ends-with? t ".zig")
                            (not (.isAbsolute (io/file t))))))
       distinct
       vec))

(defn- canonical-file ^java.io.File [f]
  (.getCanonicalFile (io/file f)))

(defn- imported-files
  "The canonical files `from-file`'s text imports by relative path,
  resolved against `from-file`'s directory."
  [^java.io.File from-file text]
  (let [dir (.getParentFile from-file)]
    (map #(canonical-file (io/file dir %)) (scan text))))

(defn- to-slash [s]
  (str/replace s java.io.File/separator "/"))

(defn- relativize [^java.io.File dir ^java.io.File f]
  (to-slash (str (.relativize (.toPath dir) (.toPath f)))))

(defn- escapes?
  "True when `rel` (a path relative to the body directory, in slash form)
  climbs above its base: it is exactly `..`, or begins with `../` (or
  `..\\` on Windows). A name that merely starts with two dots -- say
  `..drafts/util.zig`, a real sibling -- is NOT an escape and must be
  kept, so the check is by path segment, not by raw character prefix."
  [rel]
  (or (= rel "..")
      (str/starts-with? rel "../")
      (str/starts-with? rel "..\\")))

(defn- within?
  "True when `f` is at or below `dir`, so it is inside the module path
  rooted there. A file above `dir` relativizes to a `..`-prefixed path."
  [^java.io.File dir ^java.io.File f]
  (not (escapes? (relativize dir f))))

(defn closure
  "The relative `.zig` files a file-mode body imports, reproduced as paths
  relative to the body's directory. `body-path` is the resolved body file
  and `body-text` its content. Returns `{:files [{:rel <path under the
  body's directory> :text <content>}...]}` for every relative `.zig`
  transitively imported within the body's directory, sorted by path. The
  body itself is not included; it is inlined into `source.zig`. An import
  that does not resolve to a file, or that escapes the body's directory, is
  left out for the Zig compiler to report against the import line. Shell:
  reads the filesystem."
  [body-path body-text]
  (let [body     (canonical-file body-path)
        body-dir (.getParentFile body)
        graph    (loop [acc   {}
                        queue (vec (imported-files body body-text))]
                   (if (empty? queue)
                     acc
                     (let [^java.io.File f (first queue)
                           rest-q (subvec queue 1)]
                       (if (or (= f body)
                               (contains? acc f)
                               (not (.isFile f))
                               (not (within? body-dir f)))
                         (recur acc rest-q)
                         (let [t (slurp f)]
                           (recur (assoc acc f t)
                                  (into rest-q (imported-files f t))))))))]
    {:files (->> graph
                 (map (fn [[^java.io.File f t]] {:rel (relativize body-dir f) :text t}))
                 (sort-by :rel)
                 vec)}))

(comment
  (scan "const u = @import(\"util.zig\"); const s = @import(\"std\");")
  ;; => ["util.zig"]
  (closure "/home/me/app/geometry.zig"
           "const u = @import(\"util.zig\"); pub fn f() void {}"))
