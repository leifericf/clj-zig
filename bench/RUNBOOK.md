# Profiling runbook

How to capture a profile of the clj-zig bench and of a `defnz`
redefine. This is the diagnosis layer: it tells you WHERE time and
allocations go. It does not change anything. The first optimization is
a separate backlog item, pickable once a profile points at it.

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

## JFR fallback (zero agent)

When `asprof` is unavailable, the JDK's own flight recorder works
against the same pid and needs no extra install. Start the bench with a
window, then from a second shell:

    jcmd <pid> JFR.start name=bench settings=profile filename=~/.agentic-sdk/clj-zig/artifacts/perf/bench.jfr

Run the measurement, then stop the recording:

    jcmd <pid> JFR.stop name=bench

The `.jfr` file opens in Java Mission Control or any JFR viewer. The
`profile` settings sample at higher frequency than the default; it is
heavier but captures the native and FFM frames a clj-zig investigation
needs. A recording with no duration and a manual `JFR.stop` is the form
above because the bench's measured window is what you want to capture,
not a fixed duration.

## Profile a defnz redefine (authoring pipeline)

The bench measures the per-call cost after a wrapper exists. A separate
question is what a redefine costs: the cache lookup, the Zig source
generation, and the `zig build-lib` subprocess. Those frames live in
`clj-zig.cache`, `clj-zig.source`, and `clj-zig.toolchain`.

Start a REPL with native access:

    clojure -M:repl

In the REPL, print the pid, then redefine a `defnz` form while a
recording runs against that pid:

    (clojure.main/repl)
    ;; => evaluate, in order:
    (require '[clj-zig.core :as clj-zig])
    (printf "repl pid %d%n" (.pid (java.lang.ProcessHandle/current)))

From a second shell, start a JFR recording against that pid:

    jcmd <pid> JFR.start name=redefine settings=profile filename=~/.agentic-sdk/clj-zig/artifacts/perf/redefine.jfr

Back in the REPL, evaluate the redefine (the form a user would re-send
on a cache miss):

    (clj-zig/defnz echo [x :i64 :ret :i64] "return x;")

Then stop the recording from the second shell:

    jcmd <pid> JFR.stop name=redefine

The recording shows the authoring-pipeline frames: `cache.clj` for the
content-addressed lookup, `source.clj` for the generated wrapper, and
`toolchain.clj` driving the `zig build-lib` subprocess. A cache hit
skips `source` and `toolchain`; a cold redefine exercises all three.

## Where artifacts land

Every profile, recording, and numbers record writes under
`~/.agentic-sdk/clj-zig/artifacts/perf/`, which is gitignored. The repo
carries this runbook and the ADRs; it never carries numbers or
captures. When a profile motivates a change, cite the artifact path in
the change description and keep the file local.
