(ns clj-zig.fileref
  "Resolve and read an external `.zig` source file named by a `{:zig/file
  ...}` descriptor (imperative shell). Resolution prefers a path next to
  the defining Clojure source file, then the current directory, then a
  classpath resource, matching what a Clojure developer expects from a
  sibling file and from `io/resource`.

  `candidate-paths` is pure path arithmetic; `resolve-and-read` touches
  the filesystem and the classpath."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private no-source
  "The value of `*file*` when a form is evaluated with no source file, as
  at the REPL. There is no defining directory to resolve against."
  "NO_SOURCE_PATH")

(defn candidate-paths
  "The ordered filesystem paths to try for `rel`, given the defining
  Clojure source file (its `*file*`, or nil / \"NO_SOURCE_PATH\" at the
  REPL). An absolute `rel` is used as-is; a relative `rel` resolves first
  against the defining file's directory, then against the current
  directory. Pure: builds path strings, reads no filesystem."
  [defining-file rel]
  (let [f (io/file rel)]
    (if (.isAbsolute f)
      [(.getPath f)]
      (->> [(when (and defining-file (not= defining-file no-source))
              (.getPath (io/file (.getParent (io/file defining-file)) rel)))
            rel]
           (remove nil?)
           vec))))

(defn namespace-zig-file
  "The `.zig` file co-located with a namespace's Clojure source: the
  defining file's path with its `.clj`/`.cljc` extension replaced by
  `.zig`. A bodyless `defnz` sources its body from this file's matching
  `pub fn`. Pure; the filesystem and classpath resolution happens in
  `establish-binding-from!`. Throws when there is no defining file, as at
  the REPL, where a bodyless `defnz` has no co-located file to read."
  [defining-file]
  (when (or (nil? defining-file) (= defining-file no-source))
    (throw (ex-info (str "A bodyless defnz needs a file-loaded namespace with"
                         " a co-located .zig; give an explicit {:zig/file ...}"
                         " body when there is no defining file.")
                    {:level :error :error/code :clj-zig/no-namespace-file})))
  (str/replace defining-file #"\.cljc?$" ".zig"))

(defn declared-namespace
  "The namespace a `.zig` file asserts it belongs to via a leading
  `//! clj-zig: <ns>` doc-comment line, or nil when it makes no such
  assertion. Pure."
  [zig-text]
  (some (fn [line]
          (second (re-matches #"\s*//!\s*clj-zig:\s*(\S+)\s*" line)))
        (str/split-lines zig-text)))

(defn resolve-and-read
  "Read the text of the Zig source file `rel`, trying the filesystem
  candidates first, then the classpath. Returns `{:text <content> :path
  <resolved>}`. Throws a `:clj-zig/zig-file-not-found` diagnostic listing
  what was tried."
  [defining-file rel]
  (let [fs-paths (candidate-paths defining-file rel)
        on-disk  (first (filter #(.isFile (io/file %)) fs-paths))]
    (cond
      on-disk            {:text (slurp on-disk) :path on-disk}
      (io/resource rel)  (let [res (io/resource rel)]
                           {:text (slurp res) :path (str res)})
      :else
      (throw (ex-info (str "Could not find the Zig source file " (pr-str rel) ".")
                      {:level :error
                       :error/code :clj-zig/zig-file-not-found
                       :clj-zig/file rel
                       :clj-zig/tried (conj fs-paths (str "classpath:" rel))})))))

(comment
  (candidate-paths "/home/me/src/app/core.clj" "dot.zig")
  ;; => ["/home/me/src/app/dot.zig" "dot.zig"]
  (candidate-paths nil "dot.zig")
  ;; => ["dot.zig"]
  (resolve-and-read *file* "fileref.clj"))
