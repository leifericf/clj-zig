(ns clj-zig.source-file
  "Resolve and read the external `.zig` files a file-mode body names, and
  derive the entry-fn name the generated wrapper calls. The pure path
  logic (candidate paths, the co-located namespace file, the declared
  namespace header, the entry name) plus the one filesystem/classpath read
  in `resolve-and-read`. Separated from `clj-zig.source` (the wrapper
  generator) so each concept has one home."
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
        on-disk  (first (filter #(.isFile (io/file %)) fs-paths))
        res      (io/resource rel)]
    (cond
      on-disk  {:text (slurp on-disk) :path on-disk}
      res      {:text (slurp res) :path (str res)}
      :else
      (throw (ex-info (str "Could not find the Zig source file " (pr-str rel) ".")
                      {:level :error
                       :error/code :clj-zig/zig-file-not-found
                       :clj-zig/file rel
                       :clj-zig/tried (conj fs-paths (str "classpath:" rel))})))))

(defn- valid-zig-ident?
  "True when `s` is a legal Zig identifier, so it can name the user fn the
  file-mode wrapper calls."
  [s]
  (boolean (re-matches #"[A-Za-z_][A-Za-z0-9_]*" s)))

(defn entry-name
  "The user fn name the file-mode wrapper calls: `:zig/fn` when given, else
  the Clojure fn name with hyphens as underscores, the way Clojure names
  munge for the JVM (`dot-product` becomes `dot_product`). A name still not a
  legal Zig identifier, such as `red?` or `saxpy!`, needs `:zig/fn`."
  [spec descriptor]
  (or (:zig/fn descriptor)
      (let [n (str/replace (name (:name spec)) "-" "_")]
        (if (valid-zig-ident? n)
          n
          (throw (ex-info (str "The Clojure name " (pr-str (:name spec))
                               " is not a legal Zig identifier; name the entry"
                               " fn with :zig/fn.")
                          {:level :error :error/code :clj-zig/entry-name-needed
                           :name (:name spec)}))))))
