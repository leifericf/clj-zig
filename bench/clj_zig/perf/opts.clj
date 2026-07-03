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
(def ^:private track-allocations-flag "--track-allocations")
(def ^:private track-allocations-env "CLJ_ZIG_TRACK_ALLOCATIONS")

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

(defn- track-allocations-from-args?
  "True when `args` carries the --track-allocations flag (a bare switch,
  no value). Walks the arg list so the flag may appear in any position
  alongside the kind positional and the --attach-window value."
  [args]
  (boolean (some #(= % track-allocations-flag) args)))

(defn- track-allocations-from-env?
  "True when the CLJ_ZIG_TRACK_ALLOCATIONS env var is set to a truthy
  value (1, true, yes, on, case-insensitive). Any other value (0, empty,
  unset) keeps the profiling build off so the default library is built."
  [env]
  (let [v (some-> (get env track-allocations-env) str/lower-case str/trim)]
    (boolean (and v (#{ "1" "true" "yes" "on"} v)))))

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

    :kind                the optional shape-kind positional arg, nil absent
    :attach-window       the attach window in whole seconds, nil absent
    :track-allocations   truthy when the profiling build is on (see below)

  The attach window reads from the --attach-window <secs> flag or the
  CLJ_ZIG_ATTACH_WINDOW env var; the flag wins when both are set. :kind
  is the first positional arg.

  :track-allocations enables the profiling build: each shape's wrapper
  is compiled under :zig/track-allocations (a counting allocator over
  c_allocator), and the bench reads a per-shape native allocation count
  after each measured loop. OFF by default (no flag, no env, or a falsy
  env value), so a run without the option builds the default library and
  matches the baseline. Set via the --track-allocations bare switch or
  CLJ_ZIG_TRACK_ALLOCATIONS=1; the flag wins when both are set. Pure: no io."
  [args env]
  (let [from-flag (parse-attach-window-flag args)
        from-env  (some-> (get env attach-window-env) parse-long)]
    (cond-> {:kind (positional-kind args)}
      (or from-flag from-env)        (assoc :attach-window (or from-flag from-env))
      (or (track-allocations-from-args? args)
          (track-allocations-from-env? env)) (assoc :track-allocations true))))
