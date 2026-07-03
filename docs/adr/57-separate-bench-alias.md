# ADR 57: A separate :bench alias for the perf harness, never in :test

Date: 2026-07-03

## Context

clj-zig needs a way to measure per-call overhead: how much a `defnz`
boundary cross costs above a foreign-invoke floor, per contract shape.
Criterium is the measurement library; it carries its own dependencies
and a measurement shell that compiles real Zig and runs sampling loops.
The measurement is tracking data for future optimization work, not a
correctness gate.

The green test lane (`:test`) already compiles real Zig and runs
hundreds of assertions. Two design pressures intersect. First, anything
on the `:test` classpath that cognitect-test-runner discovers gets
required at gate time, so a measurement dependency pulled into `:test`
becomes a gate dependency. Second, Criterium is a runtime measurement
tool, not a test assertion library; coupling it to the gate would slow
the gate and bind gate correctness to a measurement library.

The pure-core half of the harness (the shape records and the stats
shaper) is genuinely testable and lives under `bench/`, which `:test`
adds to its classpath so its unit tests run in the gate. The question is
where the imperative measurement shell and Criterium itself live.

## Decision

The perf harness lives behind a separate `:bench` alias, additive over
`:test`. `:bench` adds Criterium as an extra dependency, adds `bench/`
to its paths, sets the native-access JVM opt, and points main-opts at
`clj-zig.perf.run`, the only namespace that requires Criterium. `:test`
never pulls Criterium. The bench shell carries no `deftest`, so
cognitect-test-runner never requires it even though `bench/` is on the
`:test` classpath for the pure-core unit tests.

Perf is tracking, not gating. `:bench` is not in the project's `:lanes`;
the green lane is `:test`.

## Consequences

The gate stays fast and free of a measurement dependency. Criterium
resolves only under `:bench`. A missing Criterium under `:test` is the
load-bearing assertion: a `:test` run that required `clj-zig.perf.run`
would throw on the Criterium require and fail the gate, so the
separation is self-checking.

The cost is two surfaces to maintain. The pure core (shape and stats) is
shared: its unit tests run under `:test`, the shell consumes it under
`:bench`. The split keeps the testable, dependency-free core on the gate
and the Criterium-bound shell off it. A future harness addition that
pulls a new runtime dependency stays in `:bench` by default.

## Alternatives

Put Criterium in `:test` and run the harness as a test. Rejected: it
would make a measurement library a gate dependency, slow the gate with
sampling loops, and bind gate correctness to tracking tooling. The
tracking versus gating distinction is the whole point.

A separate sourceset the test runner is configured to exclude.
Rejected: `bench/` is already that sourceset. The runner's default
namespace pattern matches `.*-test$`, so `clj-zig.perf.run` is parsed
for discovery but never required. No exclusion config is needed.
