(ns clj-zig.compile
  "Compile generated Zig into a dynamic library (imperative shell). This
  namespace owns the only `zig` invocations: it writes the source,
  canonicalizes it with `zig fmt`, and builds a shared library.

  On success it returns the library and source paths. On failure it
  throws the structured diagnostic doc 04 fixes: the Var and signature
  first, then the `source.zig` path, then the compiler's stderr and exit
  code. The core shell catches it to keep the last good binding."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

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

(defn compile!
  "Compile `source` into a dynamic library at `library-path`, writing the
  canonical source to `source-path` first. Returns
  `{:library <path> :source-path <path>}` on success; throws a
  structured diagnostic on failure. `ctx` adds `:var` and `:signature`
  to that diagnostic. `options` carries C-interop include and link flags."
  [{:keys [source source-path library-path ctx options]}]
  (let [src-file (io/file source-path)
        lib-file (io/file library-path)
        src-abs  (.getAbsolutePath src-file)
        lib-abs  (.getAbsolutePath lib-file)]
    (io/make-parents src-file)
    (spit src-file source)
    ;; zig fmt owns formatting. A syntax error here leaves the file
    ;; untouched and resurfaces as the authoritative build error
    ;; below, so this exit code is deliberately ignored.
    (sh/sh "zig" "fmt" src-abs :dir (.getParent src-file))
    ;; Link libc: owned and handle returns back their memory with
    ;; `std.heap.c_allocator`, whose free is the one deallocation that is
    ;; safe to call across the boundary. macOS links libc implicitly;
    ;; Linux needs it requested, and a body may reach for libc anywhere.
    ;; C-interop include and link flags from `options` follow `-lc`.
    (let [build-args (concat ["zig" "build-lib" "-dynamic" "-O" optimize-mode "-lc"]
                             (options->flags options)
                             [(str "-femit-bin=" lib-abs) src-abs
                              :dir (.getParent src-file)])
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
               :ctx {:var 'app.core/add :signature (:signature s)}})))
