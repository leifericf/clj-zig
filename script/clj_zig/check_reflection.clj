(ns clj-zig.check-reflection
  "A CI gate: fail when loading the src namespaces under
  *warn-on-reflection* emits any reflection warning.

  Reflection lowers to clojure.lang.Reflector at runtime, the defect behind
  the owned-slice call-path slowdown. clj-kondo's :warn-on-reflection linter
  is not the authority here: it both flags correctly hinted interop and
  misses real reflection the compiler catches, so the compiler is the gate.

  Loads every src file with load-file under a captured *err*, then fails the
  process if any Reflection warning was emitted. Run via the
  :lint-reflection alias: clojure -M:lint-reflection."
  (:require [clojure.string :as str]))

(defn- ^java.io.File src-root []
  (java.io.File. "src"))

(defn- clj-files
  "Every .clj file under src/, depth-first."
  []
  (->> (file-seq (src-root))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))))

(defn -main
  "Load each src file under *warn-on-reflection* and exit non-zero when any
  reflection warning surfaces. The gate runs in a fresh process, so every
  file compiles cold and every reflective form is caught."
  [& _args]
  (set! *warn-on-reflection* true)
  (let [files (clj-files)
        sw    (java.io.StringWriter.)]
    (binding [*err* sw]
      (doseq [^java.io.File f files]
        (load-file (.getPath f))))
    (let [out (str sw)]
      (if (str/includes? out "Reflection warning")
        (do (.write *err* out)
            (println "FAIL: reflective interop found in src")
            (System/exit 1))
        (do (println "OK: no reflection across" (count files) "src files")
            (System/exit 0))))))
