(ns clj-zig.imports
  "Resolve the multi-file Zig import graph of a file-mode body so it
  compiles inside the content-addressed cache directory.

  A file-mode body is inlined into the generated `source.zig`, so its
  `@import(\"util.zig\")` statements resolve relative to `source.zig`'s
  location. To make a body's relative imports resolve, the importing
  files are reproduced inside the cache entry: the import graph's common
  ancestor directory becomes the entry directory, and `source.zig` sits at
  the body's position within it. Subdirectory imports, `../` imports, and
  cycles then resolve exactly as Zig resolves them from the original tree.

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

(defn- common-ancestor
  "The deepest directory containing every file in `files`."
  [files]
  (let [sep        (re-pattern (java.util.regex.Pattern/quote java.io.File/separator))
        dir-parts  (map (fn [^java.io.File f]
                          (str/split (.getPath (.getParentFile f)) sep))
                        files)
        prefix     (->> (apply map list dir-parts)
                        (take-while (fn [col] (apply = col)))
                        (map first))]
    (str/join java.io.File/separator prefix)))

(defn- relativize [root ^java.io.File f]
  (to-slash (str (.relativize (.toPath (io/file root)) (.toPath f)))))

(defn closure
  "The file-mode body's import graph, reproduced as paths relative to the
  graph's common ancestor. `body-path` is the resolved body file and
  `body-text` its content. Returns `{:source-reldir <body's directory
  relative to the common ancestor> :files [{:rel <path under the ancestor>
  :text <content>}...]}` covering the body and every relative `.zig` it
  transitively imports, the body first. An import that does not resolve to
  a file is left out, for the Zig compiler to report against the import
  line. Shell: reads the filesystem."
  [body-path body-text]
  (let [body (canonical-file body-path)
        graph (loop [acc   {body body-text}
                     queue (vec (imported-files body body-text))]
                (if (empty? queue)
                  acc
                  (let [f (first queue)]
                    (cond
                      (contains? acc f) (recur acc (subvec queue 1))
                      (not (.isFile f)) (recur acc (subvec queue 1))
                      :else             (let [t (slurp f)]
                                          (recur (assoc acc f t)
                                                 (into (subvec queue 1)
                                                       (imported-files f t))))))))
        root  (common-ancestor (keys graph))]
    {:source-reldir (relativize root (.getParentFile body))
     :files (->> graph
                 (map (fn [[^java.io.File f t]] {:rel (relativize root f) :text t}))
                 (sort-by :rel)
                 vec)}))

(comment
  (scan "const u = @import(\"util.zig\"); const s = @import(\"std\");")
  ;; => ["util.zig"]
  (closure "/home/me/app/geometry.zig"
           "const u = @import(\"util.zig\"); pub fn f() void {}"))
