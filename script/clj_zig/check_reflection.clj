(ns clj-zig.check-reflection
  "A CI gate: fail when loading the src namespaces under
  *warn-on-reflection* emits any reflection warning.

  Reflection lowers to clojure.lang.Reflector at runtime, the defect behind
  the owned-slice call-path slowdown. clj-kondo's :warn-on-reflection linter
  is not the authority here: it both flags correctly hinted interop and
  misses real reflection the compiler catches, so the compiler is the gate.

  Loads every src file with load-file under a captured *err*, then fails the
  process if any Reflection warning was emitted -- or if no src files were
  found at all, since a silent pass on an empty src tree would hide a
  misconfiguration. Run via the :lint-reflection alias:
  clojure -M:lint-reflection."
  (:require [clojure.string :as str]))

(defn- src-root
  ^java.io.File []
  (java.io.File. "src"))

(defn- clj-files
  "Every .clj file under src/, depth-first."
  []
  (->> (file-seq (src-root))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))))

(defn gate-verdict
  "Pure gate decision given the count of src files loaded and the *err*
  text captured while loading them. Returns one of:

    :fail  a Reflection warning was emitted -- reflective interop found
    :empty no src files were loaded (src/ missing or empty); a silent
           pass here would hide a misconfiguration, so it is a failure
    :ok    files were loaded and no reflection surfaced

  Pure so a unit test can pin each branch without spawning the gate."
  [file-count captured]
  (cond
    (str/includes? captured "Reflection warning") :fail
    (zero? file-count)                            :empty
    :else                                         :ok))

(defn -main
  "Load each src file under *warn-on-reflection* and exit non-zero when any
  reflection warning surfaces, or when no src files were found (a
  misconfiguration that would otherwise silently pass). The gate runs in a
  fresh process, so every file compiles cold and every reflective form is
  caught."
  [& _args]
  (set! *warn-on-reflection* true)
  (let [files (clj-files)
        sw    (java.io.StringWriter.)]
    (binding [*err* sw]
      (doseq [^java.io.File f files]
        (load-file (.getPath f))))
    (let [captured (str sw)]
      (case (gate-verdict (count files) captured)
        :fail  (do (.write ^java.io.Writer *err* captured)
                   (println "FAIL: reflective interop found in src")
                   (System/exit 1))
        :empty (do (println "FAIL: no .clj files found under src/ --"
                            "is the gate running from the repo root?")
                   (System/exit 1))
        :ok    (do (println "OK: no reflection across" (count files) "src files")
                   (System/exit 0))))))
