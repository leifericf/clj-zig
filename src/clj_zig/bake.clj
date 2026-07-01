(ns clj-zig.bake
  "Bake a namespace's `defnz` functions into precompiled native libraries
  laid out as classpath resources, so a consumer loads them without a
  compiler. This is the release-time counterpart to the REPL loop: it
  enumerates the established functions in a namespace, re-derives each
  function's build inputs, and cross-compiles it for a target matrix into
  the resource tree the loader resolves from.

  A build tool entry: `bake!` is the `clj -X` and tools.build function. It
  loads the target namespace, which establishes each `defnz` for the host;
  baking then re-derives and compiles each for every target."
  (:require [clojure.java.io :as io]
            [clj-zig.cache :as cache]
            [clj-zig.compile :as compile]
            [clj-zig.core :as core]))

(def default-targets
  "The release target matrix: a clj-zig target id (which keys the content
  hash and the resource path) paired with the Zig target triple to compile
  for. The musl ids carry a libc-portable build alongside the glibc one; a
  host whose resolved id is `linux-<arch>` selects the glibc build, so the
  musl artifacts await a musl-aware target resolver."
  [{:id "linux-x86_64"       :triple "x86_64-linux-gnu"}
   {:id "linux-aarch64"      :triple "aarch64-linux-gnu"}
   {:id "linux-x86_64-musl"  :triple "x86_64-linux-musl"}
   {:id "macos-x86_64"       :triple "x86_64-macos"}
   {:id "macos-aarch64"      :triple "aarch64-macos"}
   {:id "windows-x86_64"     :triple "x86_64-windows"}
   {:id "windows-aarch64"    :triple "aarch64-windows"}])

(defn- host-target
  "The host as a matrix entry: the resolved clj-zig id and a nil triple,
  which compiles natively without a `-target` flag."
  []
  {:id (cache/target-triple) :triple nil})

(defn- third-party-c?
  "True when a function links a C library beyond libc and libm. Such a
  function cannot cross-compile freely, so it is baked for the host only."
  [options]
  (boolean (some (fn [lib] (not (#{"c" "m"} lib))) (:link options))))

(defn- function-inputs
  "The build inputs for an established `defnz`, from the inspection data its
  Var carries. Shares `core/gen-from-info` with `recompile!`, so a baked
  artifact hashes identically to the same function built in place."
  [info]
  (core/build-inputs (:spec info) (:body info) (core/gen-from-info info)))

(defn- baked-functions
  "The inspection data of every successfully established `defnz` in
  `ns-sym`. A function whose last build failed carries no info and is
  skipped."
  [ns-sym]
  (->> (ns-interns (the-ns ns-sym))
       vals
       (keep #(:clj-zig/info (meta %)))
       (filter :spec)))

(defn- bake-target!
  "Cross-compile one function for one target into `out-dir`'s resource tree
  and return what was produced. The source and any imported files live in a
  throwaway build directory; only the library lands in the resource tree,
  so the jar carries native code and no source."
  [out-dir inputs {:keys [id triple]}]
  (let [in       (assoc inputs :target id)
        spec     (:spec in)
        hash     (cache/cache-key in)
        coords   {:target id :ns (:ns spec) :name (:name spec) :hash hash}
        resource (cache/bundled-resource-path coords)
        lib      (io/file out-dir resource)
        build    (.toFile (java.nio.file.Files/createTempDirectory
                           "clj-zig-bake" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (io/make-parents lib)
    (compile/compile!
     {:source       (:source in)
      :source-path  (.getPath (io/file build "source.zig"))
      :library-path (.getPath lib)
      :options      (:options in)
      :target       triple
      :module-roots (:module-roots in)
      :aux-files    (mapv (fn [{:keys [rel text]}]
                            {:path (.getPath (io/file build rel)) :text text})
                          (:aux-files in))
      :ctx          {:var (symbol (str (:ns spec)) (str (:name spec)))
                     :signature (:signature spec)}})
    {:var      (symbol (str (:ns spec)) (str (:name spec)))
     :target   id
     :resource resource
     :library  (.getPath lib)}))

(defn- bake-function!
  "Bake one function for the applicable targets. A function linking a
  third-party C library is narrowed to the host, naming the skipped targets
  so the narrowing is never silent."
  [out-dir info targets]
  (let [inputs (function-inputs info)
        third? (third-party-c? (:options inputs))
        chosen (if third? [(host-target)] targets)]
    (when third?
      (binding [*out* *err*]
        (println (str "clj-zig: " (-> info :spec :ns) "/" (-> info :spec :name)
                      " links a third-party C library; baking host-only and skipping "
                      (mapv :id (remove (set chosen) targets)) "."))))
    (mapv #(bake-target! out-dir inputs %) chosen)))

(defn bake!
  "Compile every `defnz` in namespace `:ns` for `:targets` into the resource
  tree under `:out`, returning the baked artifacts. `:targets` is `:all`
  (the default matrix), `:host`, or a vector of `{:id :triple}` entries;
  `:out` defaults to `resources`. The `clj -X` and tools.build entry:

      clojure -X:bake clj-zig.bake/bake! :ns my.app/native :out '\"resources\"'

  Loading `:ns` establishes its functions for the host; baking then
  re-derives and compiles each for every target."
  [{:keys [ns out targets] :or {out "resources" targets :all}}]
  (require ns)
  (let [chosen  (case targets
                  :all  default-targets
                  :host [(host-target)]
                  targets)
        fns     (baked-functions ns)
        results (vec (mapcat #(bake-function! out % chosen) fns))]
    (binding [*out* *err*]
      (println (str "clj-zig: baked " (count fns) " function(s) over "
                    (count chosen) " target(s) into " out "/clj-zig/native")))
    results))

(comment
  ;; Bake an example namespace for the host into a temp resource tree.
  (bake! {:ns 'clj-zig.bake-fixture :out "/tmp/baked" :targets :host})
  ;; The full release matrix into the project's resources.
  (bake! {:ns 'my.app/native :out "resources"}))
