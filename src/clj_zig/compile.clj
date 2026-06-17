(ns clj-zig.compile
  "Compile generated Zig into a dynamic library (imperative shell). This
  namespace owns the only `zig` invocations: it writes the source,
  canonicalizes it with `zig fmt`, and builds a shared library.

  On success it returns the library and source paths. On failure it
  throws a structured diagnostic: the Var and signature first, then the
  `source.zig` path, then the compiler's stderr and exit code. The core
  shell catches it to keep the last good binding."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clj-zig.toolchain :as toolchain]))

(def ^:private optimize-mode
  "Safety checks stay on; this is part of the cache key."
  "ReleaseSafe")

(defn dynamic-library-extension
  "The platform's shared-library suffix, without the dot."
  []
  (let [os (str/lower-case (System/getProperty "os.name"))]
    (cond
      (str/includes? os "mac") "dylib"
      (str/includes? os "win") "dll"
      :else                    "so")))

(defn options->flags
  "The extra `zig build-lib` flags carried in `options` for C interop:
  include paths (`-I`), system include paths (`-isystem`), library search
  paths (`-L`), and linked libraries (`-l`). Returns a flat vector of argv
  tokens, empty when there is nothing to add. Pure."
  [{:keys [include-path system-include-path link-path link]}]
  (vec (concat (mapcat (fn [p] ["-I" p]) include-path)
               (mapcat (fn [p] ["-isystem" p]) system-include-path)
               (mapcat (fn [p] ["-L" p]) link-path)
               (map (fn [lib] (str "-l" lib)) link))))

(defn global-cache-dir
  "The project-local directory Zig memoizes its intermediate build artifacts
  in across compiles (ADR 35). Keeping it stable lets a wrapper miss over an
  unchanged module relink the already-built module rather than rebuild it,
  and it shares the `.clj-zig/` lifecycle the toolchain and artifact cache
  already own. It is build-tool state, not part of any content hash."
  []
  (.getAbsolutePath (io/file ".clj-zig" "global-cache")))

(defn- module-args
  "The `--dep`/`-M` arguments that make the source the main module and link
  each external module by name (ADR 34): a `--dep name` populates the main
  module's import table, the per-module `link-flags` and `-Mroot=<source>`
  define it, and a `-M<name>=<root>` defines each imported module. Pure."
  [module-roots source-abs link-flags]
  (concat (mapcat (fn [[name _]] ["--dep" name]) module-roots)
          link-flags
          [(str "-Mroot=" source-abs)]
          (map (fn [[name root]] (str "-M" name "=" root)) module-roots)))

(defn build-arguments
  "The full `zig build-lib` argument vector for a compile (pure). Without
  `:module-roots`, the source is the positional root and the per-module link
  flags precede it, exactly as a single-file build. With modules, the source
  becomes the main module and each module is declared and defined by name
  (ADR 34). `--global-cache-dir` is always present so Zig memoizes
  intermediate artifacts across compiles (ADR 35). A `:target` triple
  cross-compiles for another platform."
  [zig {:keys [source-abs library-abs options target module-roots global-cache-dir]}]
  (let [link-flags (into ["-lc"] (options->flags options))]
    (vec (concat [zig "build-lib" "-dynamic" "-O" optimize-mode]
                 (when target ["-target" target])
                 ["--global-cache-dir" global-cache-dir
                  (str "-femit-bin=" library-abs)]
                 (if (seq module-roots)
                   (module-args module-roots source-abs link-flags)
                   (concat link-flags [source-abs]))))))

(defn compile!
  "Compile `source` into a dynamic library at `library-path`, writing the
  canonical source to `source-path` first. Returns
  `{:library <path> :source-path <path>}` on success; throws a
  structured diagnostic on failure. `ctx` adds `:var` and `:signature`
  to that diagnostic. `options` carries C-interop include and link flags;
  `aux-files` are imported Zig files to write beside the source first, each
  `{:path <absolute> :text <content>}`. `module-roots` names external Zig
  modules the body may `@import`, each mapped to its root source path.
  `target` is a Zig target triple to cross-compile for (e.g.
  `x86_64-linux-musl`); omit it to build for the host."
  [{:keys [source source-path library-path ctx options aux-files target module-roots]}]
  (let [zig      (toolchain/zig-exe)
        src-file (io/file source-path)
        lib-file (io/file library-path)
        src-abs  (.getAbsolutePath src-file)
        lib-abs  (.getAbsolutePath lib-file)]
    (io/make-parents src-file)
    ;; Reproduce the body's imported files at their resolved relative
    ;; positions so the body's `@import` statements resolve from
    ;; `source.zig` the way they resolve from the original file.
    (doseq [{:keys [path text]} aux-files]
      (let [f (io/file path)]
        (io/make-parents f)
        (spit f text)))
    (spit src-file source)
    ;; zig fmt owns formatting. A syntax error here leaves the file
    ;; untouched and resurfaces as the authoritative build error
    ;; below, so this exit code is deliberately ignored.
    (sh/sh zig "fmt" src-abs :dir (.getParent src-file))
    ;; Link libc: owned and handle returns back their memory with
    ;; `std.heap.c_allocator`, whose free is the one deallocation that is
    ;; safe to call across the boundary. macOS links libc implicitly;
    ;; Linux needs it requested, and a body may reach for libc anywhere.
    ;; build-arguments adds the cross-compile target, C-interop flags, the
    ;; external-module imports, and the persistent global cache dir.
    (let [build-args (conj (build-arguments zig {:source-abs src-abs
                                                 :library-abs lib-abs
                                                 :options options
                                                 :target target
                                                 :module-roots module-roots
                                                 :global-cache-dir (global-cache-dir)})
                           :dir (.getParent src-file))
          {:keys [exit err]} (apply sh/sh build-args)]
      (if (zero? exit)
        {:library lib-abs :source-path src-abs}
        (let [message (str "Could not compile defnz " (:var ctx) ".")]
          ;; A failed build leaves a zero-byte library at the emit path;
          ;; remove it so the cache is not poisoned and a retry recompiles.
          (.delete lib-file)
          (throw (ex-info message
                          (merge {:level :error
                                  :error/code :zig/compile-failed
                                  :message message
                                  :zig/source-path src-abs
                                  :zig/stderr err
                                  :zig/exit-code exit}
                                 ctx))))))))

(comment
  (require '[clj-zig.spec :as spec] '[clj-zig.source :as source])
  (let [s   (spec/build-spec '{:ns app.core :name add :signature [x :i64 y :i64 :ret :i64]})
        dir (str (java.nio.file.Files/createTempDirectory
                  "clj-zig" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (compile! {:source (source/generate s "return x + y;")
               :source-path (str dir "/source.zig")
               :library-path (str dir "/libadd." (dynamic-library-extension))
               :ctx {:var 'app.core/add :signature (:signature s)}}))

  ;; Cross-compile the same function for another platform. The artifact is
  ;; produced even on a host that cannot load it.
  (let [s   (spec/build-spec '{:ns app.core :name add :signature [x :i64 y :i64 :ret :i64]})
        dir (str (java.nio.file.Files/createTempDirectory
                  "clj-zig" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (compile! {:source (source/generate s "return x + y;")
               :source-path (str dir "/source.zig")
               :library-path (str dir "/libadd.so")
               :target "x86_64-linux-musl"
               :ctx {:var 'app.core/add :signature (:signature s)}})))
