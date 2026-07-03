(ns clj-zig.perf.opts
  "Pure parse of the bench command-line and environment. Kept JVM-free
  so the Tier-0 test lane exercises it without Criterium on the
  classpath: the bench shell clj-zig.perf.run requires Criterium and is
  never loaded under :test, but this namespace is.

  The shell applies the parsed map as effects. A non-nil :attach-window
  prints the process id and sleeps that many seconds before the measured
  run, giving an external profiler (async-profiler's asprof attach, or
  jcmd JFR.start) time to attach against a stable pid. The default is
  nil, so a run without the option is byte-identical to the bench
  without it: no extra output, no sleep, no change to the numbers."
  (:require [clojure.string :as str]))

(def ^:private attach-window-flag "--attach-window")
(def ^:private attach-window-env "CLJ_ZIG_ATTACH_WINDOW")

(defn- parse-attach-window-flag
  "Walk `args` and return the integer value of the --attach-window flag,
  or nil when the flag is absent or its value does not parse as a long.
  A trailing flag with no value parses to nil, so a malformed invocation
  is off rather than an indefinite sleep."
  [args]
  (loop [[a & rest] args]
    (cond
      (nil? a)                 nil
      (= a attach-window-flag) (some-> (first rest) parse-long)
      :else                    (recur rest))))

(defn- positional-kind
  "The first positional arg in `args`: one that neither starts with `--`
  nor is consumed as a flag's value. nil when absent."
  [args]
  (loop [[a & rest] args skip-next? false]
    (cond
      (nil? a)                    nil
      skip-next?                  (recur rest false)
      (= a attach-window-flag)    (recur rest true)
      (str/starts-with? a "--")   (recur rest false)
      :else                       a)))

(defn parse-args
  "Parse bench `args` (the raw command-line seq) against `env` (a
  String->String map, the process environment). Returns a map:

    :kind           the optional shape-kind positional arg, nil absent
    :attach-window  the attach window in whole seconds, nil absent

  The attach window reads from the --attach-window <secs> flag or the
  CLJ_ZIG_ATTACH_WINDOW env var; the flag wins when both are set. :kind
  is the first positional arg. Pure: no io."
  [args env]
  (let [from-flag (parse-attach-window-flag args)
        from-env  (some-> (get env attach-window-env) parse-long)]
    (cond-> {:kind (positional-kind args)}
      (or from-flag from-env) (assoc :attach-window (or from-flag from-env)))))
