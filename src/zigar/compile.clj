(ns zigar.compile
  "Compile generated Zig into a dynamic library (imperative shell). This
  namespace owns the only `zig` invocations: it writes the source,
  canonicalizes it with `zig fmt`, and builds a shared library.

  On success it returns the library and source paths. On failure it
  throws the structured diagnostic doc 04 fixes: the Var and signature
  first, then the `source.zig` path, then the compiler's stderr and exit
  code. The core shell catches it to keep the last good binding (ADR 11)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]))

(def ^:private optimize-mode
  "Safety checks stay on; this is part of the cache key (docs/04)."
  "ReleaseSafe")

(defn dynamic-library-extension
  "The platform's shared-library suffix, without the dot."
  []
  (let [os (str/lower-case (System/getProperty "os.name"))]
    (cond
      (str/includes? os "mac") "dylib"
      (str/includes? os "win") "dll"
      :else                    "so")))

(defn compile!
  "Compile `source` into a dynamic library at `library-path`, writing the
  canonical source to `source-path` first. Returns
  `{:library <path> :source-path <path>}` on success; throws a
  structured diagnostic on failure. `ctx` adds `:var` and `:signature`
  to that diagnostic."
  [{:keys [source source-path library-path ctx]}]
  (let [src-file (io/file source-path)
        lib-file (io/file library-path)
        src-abs  (.getAbsolutePath src-file)
        lib-abs  (.getAbsolutePath lib-file)]
    (io/make-parents src-file)
    (spit src-file source)
    ;; zig fmt owns formatting (CLAUDE.md). A syntax error here leaves the
    ;; file untouched and resurfaces as the authoritative build error
    ;; below, so this exit code is deliberately ignored.
    (sh/sh "zig" "fmt" src-abs :dir (.getParent src-file))
    (let [{:keys [exit err]} (sh/sh "zig" "build-lib" "-dynamic"
                                    "-O" optimize-mode
                                    (str "-femit-bin=" lib-abs)
                                    src-abs
                                    :dir (.getParent src-file))]
      (if (zero? exit)
        {:library lib-abs :source-path src-abs}
        (let [message (str "Could not compile defnz " (:var ctx) ".")]
          (throw (ex-info message
                          (merge {:level :error
                                  :error/code :zig/compile-failed
                                  :message message
                                  :zig/source-path src-abs
                                  :zig/stderr err
                                  :zig/exit-code exit}
                                 ctx))))))))
