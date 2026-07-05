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
(def ^:private axis1-flag "--axis1")
(def ^:private axis1-env "CLJ_ZIG_AXIS1")
(def ^:private jfr-flag "--jfr")
(def ^:private jfr-env "CLJ_ZIG_JFR")

(def ^:private max-attach-window-secs
  "The largest whole-second attach window whose millisecond conversion
  (* 1000 secs in attach-profiler!) stays within a long. A value above
  this overflows the multiplication, so parse-attach-window treats it as
  off (nil) for the same robustness reason as a non-positive value."
  (quot Long/MAX_VALUE 1000))

(defn- parse-attach-window
  "Parse `s` as the whole-second attach window, returning the integer
  only when it is a positive long that does not overflow the millisecond
  conversion. nil otherwise (absent, non-numeric, non-positive, or too
  large), so a malformed invocation is off rather than an exception."
  [s]
  (when-let [secs (parse-long s)]
    (when (<= 1 secs max-attach-window-secs) secs)))

(defn- parse-attach-window-flag
  "Walk `args` and return the integer value of the --attach-window flag,
  or nil when the flag is absent, its value does not parse as a positive
  long. A trailing flag with no value parses to nil, so a malformed
  invocation is off rather than an indefinite sleep."
  [args]
  (loop [[a & rest] args]
    (cond
      (nil? a)                 nil
      (= a attach-window-flag) (some-> (first rest) parse-attach-window)
      :else                    (recur rest))))

(defn- truthy-env?
  "True when `env` carries `key` set to a truthy value (1, true, yes, on,
  case-insensitive). Any other value or absence is false."
  [env key]
  (let [v (some-> (get env key) str/lower-case str/trim)]
    (boolean (and v (#{"1" "true" "yes" "on"} v)))))

(defn- flag-present?
  "True when `args` carries the bare switch `flag`."
  [args flag]
  (boolean (some #(= % flag) args)))

(defn- track-allocations-from-args?
  "True when `args` carries the --track-allocations flag (a bare switch,
  no value). Walks the arg list so the flag may appear in any position
  alongside the kind positional and the --attach-window value."
  [args]
  (flag-present? args track-allocations-flag))

(defn- track-allocations-from-env?
  "True when the CLJ_ZIG_TRACK_ALLOCATIONS env var is set to a truthy
  value (1, true, yes, on, case-insensitive). Any other value (0, empty,
  unset) keeps the profiling build off so the default library is built."
  [env]
  (truthy-env? env track-allocations-env))

(defn- axis1-from-args?
  "True when `args` carries the --axis1 bare switch. The switch selects
  the Axis-1 authoring-latency harness instead of the default per-call
  overhead run."
  [args]
  (flag-present? args axis1-flag))

(defn- axis1-from-env?
  "True when the CLJ_ZIG_AXIS1 env var is set to a truthy value (1, true,
  yes, on, case-insensitive). Any other value (0, empty, unset) keeps
  Axis-1 off so the default run is unchanged."
  [env]
  (truthy-env? env axis1-env))

(defn- parse-jfr-flag
  "Walk `args` and return the value of the --jfr flag (a path string),
  or nil when the flag is absent. A trailing flag with no value parses
  to nil, so a malformed invocation does not surprise jcmd with an
  empty filename."
  [args]
  (loop [[a & rest] args]
    (cond
      (nil? a)              nil
      (= a jfr-flag)        (first rest)
      :else                 (recur rest))))

(defn- positional-kind
  "The first positional arg in `args`: one that neither starts with `--`
  nor is consumed as a flag's value. nil when absent."
  [args]
  (loop [[a & rest] args skip-next? false]
    (cond
      (nil? a)                    nil
      skip-next?                  (recur rest false)
      (or (= a attach-window-flag) (= a jfr-flag)) (recur rest true)
      (str/starts-with? a "--")   (recur rest false)
      :else                       a)))

(defn parse-args
  "Parse bench `args` (the raw command-line seq) against `env` (a
  String->String map, the process environment). Returns a map:

    :kind                the optional shape-kind positional arg, nil absent
    :attach-window       the attach window in whole seconds, nil absent
    :track-allocations   truthy when the profiling build is on (see below)
    :axis1               truthy when the Axis-1 authoring-latency harness
                         is selected (see below)
    :jfr                 the path for the JFR recording, nil absent

  The attach window reads from the --attach-window <secs> flag or the
  CLJ_ZIG_ATTACH_WINDOW env var; the flag wins when both are set. :kind
  is the first positional arg.

  :track-allocations enables the profiling build: each shape's wrapper
  is compiled under :zig/track-allocations (a counting allocator over
  c_allocator), and the bench reads a per-shape native allocation count
  after each measured loop. OFF by default (no flag, no env, or a falsy
  env value), so a run without the option builds the default library and
  matches the baseline. Set via the --track-allocations bare switch or
  CLJ_ZIG_TRACK_ALLOCATIONS=1; the flag wins when both are set. Pure: no io.

  :axis1 selects the Axis-1 authoring-latency harness in place of the
  default per-call overhead run. Axis-1 times a defnz redefine across
  three cache tiers (cold, global-cache-hit, clj-zig-cache-hit) and
  separates the zig build-lib subprocess wall-clock from JVM-side time.
  OFF by default; set via the --axis1 bare switch or CLJ_ZIG_AXIS1=1.
  The optional :kind positional narrows the run to one shape, as in the
  default mode.

  :jfr names a JFR recording path. When set, the bench shell spawns jcmd
  JFR.start against its own pid (with a tightened 1 ms execution-sample
  period) after any attach window opens, and JFR.stop after the last
  shape measures. OFF by default (no flag and no env), so a run without
  the option writes no .jfr file and is byte-identical to a run without
  it. Reads from the --jfr <path> flag or the CLJ_ZIG_JFR env var; the
  flag wins when both are set."
  [args env]
  (let [from-flag (parse-attach-window-flag args)
        from-env  (some-> (get env attach-window-env) parse-attach-window)
        jfr-flag  (parse-jfr-flag args)
        jfr-env   (get env jfr-env)]
    (cond-> {:kind (positional-kind args)}
      (or from-flag from-env)        (assoc :attach-window (or from-flag from-env))
      (or (track-allocations-from-args? args)
          (track-allocations-from-env? env)) (assoc :track-allocations true)
      (or (axis1-from-args? args)
          (axis1-from-env? env)) (assoc :axis1 true)
      (or jfr-flag jfr-env)          (assoc :jfr (or jfr-flag jfr-env)))))
