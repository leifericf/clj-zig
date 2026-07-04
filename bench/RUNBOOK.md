# Profiling runbook

How to capture a profile of the clj-zig bench and of a `defnz`
redefine. This is the diagnosis layer: it tells you WHERE time and
allocations go. It does not change anything.

## Prerequisites

- JDK 26 with finalized FFM. The `:test` and `:bench` aliases both set
  `--enable-native-access=ALL-UNNAMED`; every JVM that loads a clj-zig
  library needs that flag.
- The `zig` compiler on `PATH`. The bench compiles real Zig; a cold
  full run is minutes, so drive one shape when iterating.
- `jcmd` (ships with the JDK) for the zero-agent JFR fallback.
- async-profiler 4.4 or newer, optional. It is an external tool the
  contributor installs; no agent binary ships with the repo. On macOS
  arm64 download the `asprof` bundle and put `asprof` on `PATH`. When
  it is absent, use the JFR fallback below.

## Run the bench

Full run, every shape:

    clojure -M:bench

One shape (faster; the kind names are `scalar-passthrough`,
`struct-by-value`, `enum`, `slice-arg`, `string`, `owned-return`,
`handle`):

    clojure -M:bench scalar-passthrough

The run writes a numbers record to
`~/.agentic-sdk/clj-zig/artifacts/perf/perf-<millis>.edn` and prints one
line per shape. Records are gitignored; the repo never carries numbers.

## Open a profiler attach window

The bench accepts an opt-in attach window so an external profiler can
attach against a stable pid before the measured run. Pass it as a flag
or an env var; both are off by default, so a run without them is
byte-identical to a run without the option:

    clojure -M:bench --attach-window 60 scalar-passthrough
    CLJ_ZIG_ATTACH_WINDOW=60 clojure -M:bench scalar-passthrough

The bench prints `bench pid <pid> -- attach within <n> s`, sleeps that
many seconds, then starts the measured run. Start the profiler against
that pid during the sleep. The window is before the measurement; it
adds no time to the numbers.

## Profile with async-profiler

With `asprof` on `PATH` and a window open, capture from a second
shell. Capture CPU and allocation as separate runs; each is one
flamegraph. Output is an HTML flamegraph under the gitignored perf dir.

CPU (hot paths, native frames included):

    asprof -d 30 -e cpu -f ~/.agentic-sdk/clj-zig/artifacts/perf/scalar-cpu.html <pid>

Allocation (Java and native heap, sampled by allocation rate):

    asprof -d 30 -e alloc -f ~/.agentic-sdk/clj-zig/artifacts/perf/scalar-alloc.html <pid>

Native malloc is visible in the CPU profile where `malloc`/`mmap` is
hot, and in the allocation profile where the JVM's native callers
allocate. Confirm the exact event names against the installed version
with `asprof -h`; event names are stable across 4.x but a contributor
should verify on first use. The `-d 30` runs the capture for 30 seconds
then detaches; pick a duration shorter than the measured run.

### Symbol fidelity and optimize mode

Profile under a debug-info-retaining build or the frames are noise. The
bench builds under `ReleaseSafe` by default, which keeps safety checks
and resolves clj-zig's own frames. `Debug` resolves the most. The
stripping modes drop what you are here for:

- `Debug`, `ReleaseSafe`: clj-zig frames resolve. Profile here.
- `ReleaseFast`, `ReleaseSmall`: strip frames. A profile under these
  shows the FFM call but not the clj-zig body. Do not profile here.

The optimize mode is part of the cache key (ADR 12). To profile a
shape under `Debug`, set `:zig/optimize "Debug"` on that shape's
descriptor options; the build gets its own cache key and never pollutes
the default library.

## JFR (in-process, zero install)

The JDK's own flight recorder ships with the JVM and needs no second
shell. The bench drives it directly:

    clojure -M:bench --jfr ~/.agentic-sdk/clj-zig/artifacts/perf/bench.jfr
    clojure -M:bench --jfr ~/.agentic-sdk/clj-zig/artifacts/perf/<shape>.jfr <shape>

The flag fires `jcmd <pid> JFR.start` against the bench's own pid after
any attach window opens (the two flags compose) and `JFR.stop` after
the last shape measures. The recording uses the `profile` settings with
a tightened 1 ms execution-sample period so the clj-zig frames
accumulate sample time within a 30 s run. The `.jfr` file opens in Java
Mission Control, IntelliJ's profiler, or any JFR viewer; `jfr summary
<file>` prints the event counts from the shell. The recording is OFF
when the flag is absent, so a run without `--jfr` is byte-identical to
a run without the option.

For per-shape isolation, run the bench once per shape with a distinct
`--jfr` path; each shape gets its own JVM and its own recording. One
multi-shape run with one `--jfr` produces a single recording that
covers every shape in canonical order, useful for an overview but
less precise for shape-specific root-causing.

Use the attach window + async-profiler path above when you need
allocation flamegraphs or native CPU frames at finer resolution than
JFR's `jdk.ObjectAllocationSample` provides; JFR is the right default
for the common Java-frame CPU investigation.

## Profile a defnz redefine (authoring pipeline)

The bench measures the per-call cost after a wrapper exists. A separate
question is what a redefine costs: the cache lookup, the Zig source
generation, and the `zig build-lib` subprocess. Those frames live in
`clj-zig.cache`, `clj-zig.source`, and `clj-zig.toolchain` (driven by
`clj-zig.compile`).

A single cold redefine is subprocess-dominated. The clj-zig authoring
code runs for milliseconds; the `zig build-lib` wait takes the bulk of
the roughly one-second wall-clock. JFR at its default sampling period
captures zero authoring frames from one redefine. To see the pipeline
you must amplify: loop cold redefines so the authoring code accumulates
sample time, and tighten the execution-sample period.

Start a REPL with native access:

    clojure -M:repl

In the REPL, print the pid, then loop cold redefines (distinct bodies
force cache misses, ADR 12, so each redefine exercises the whole
pipeline) while a recording runs against that pid:

    (require '[clj-zig.core :as clj-zig])
    (printf "repl pid %d%n" (.pid (java.lang.ProcessHandle/current)))
    (dotimes [i 15]
      (let [body (format "return x + %d;" i)
            sym  (symbol (str "amp" i))]
        (eval `(clj-zig/defnz ~sym [x :i64 :ret :i64] ~body))))

From a second shell, start a JFR recording against that pid with a
tightened execution-sample period (the `jdk.ExecutionSample#period=1ms`
override raises the Java-frame sample rate twentyfold over the profile
default):

    jcmd <pid> JFR.start name=redefine settings=profile filename=~/.agentic-sdk/clj-zig/artifacts/perf/redefine.jfr duration=300s jdk.ExecutionSample#period=1ms

Run the redefine loop in the REPL, then stop the recording from the
second shell (optional: the recording flushes to the filename on JVM
exit, so a `JFR.stop` that races the exit is harmless):

    jcmd <pid> JFR.stop name=redefine

The recording shows the authoring-pipeline frames: `clj_zig.cache` for
the content-addressed lookup, `clj_zig.compile` driving the
`zig build-lib` subprocess, and `clj_zig.toolchain` for the zig binary
hand-off. A cache hit skips `compile` and `toolchain`; a cold redefine
exercises all three.

`clj_zig.source` does not appear as a distinct frame in the loop. The
JIT inlines its small generation functions into `clj_zig.core` once the
loop is hot, so the stack reports `core`, not `source`. To capture
`source` frames specifically, run with `-XX:-Inline` on the REPL JVM,
or use async-profiler at a higher sample rate than JFR.

## Where artifacts land

Every profile, recording, and numbers record writes under
`~/.agentic-sdk/clj-zig/artifacts/perf/`, which is gitignored. The repo
carries this runbook and the ADRs; it never carries numbers or
captures. When a profile motivates a change, cite the artifact path in
the change description and keep the file local.
