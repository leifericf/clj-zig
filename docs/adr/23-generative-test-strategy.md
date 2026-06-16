# ADR 23: Generative and exhaustive testing over the example suite

Date: 2026-06-16

## Context

The suite is 134 example and acceptance tests: one or a few hand-written
cases per feature. They state intent and guard against regressions, but
they cannot exhaust the input space the boundary actually spans. A
signature is a cross-product of type, element type, argument position,
constness, and defining form; a value is anything from `0` to `2^64-1`,
an empty or a large slice, a `NaN`, a missing field, a wrong tag. The
pure core decides correctness across all of it, and no fixed list of
examples reaches every arm of `source/generate`, `ffm/marshal-arg`, or
`spec/validate!`. The maintainer asked to exhaust as many code paths and
realistic permutations as possible, beyond the examples, in the idiomatic
Clojure way, and to prove the reach rather than assert it.

## Decision

Layer generative and exhaustive testing on top of the example suite,
which stays as the intent, acceptance, and regression backbone. The
primary engine is `test.check`, driven through `defspec` with bounded
trials. `clojure.spec` carries the boundary-contract schemas. Four
exhaustiveness engines cover the space: a bounded-exhaustive structural
matrix enumerates the cross-product of valid signatures and drives each
through the whole pipeline; edge-value vectors crossed pairwise with
structure cover the value axis; negative-space enumeration walks the
invalid permutations with the `:error/code` as the oracle; and
`stateful-check` command sequences cover the define, redefine, cache, and
keep-last-good lifecycle. A `defz` allocation-counter oracle turns
owned and handle leak-freedom into a checkable property. `cloverage` is
the feedback loop, targeting near-total coverage of the pure core with a
named allowlist for the few genuinely hard shell arms. The toolchain is
never mocked: real `zig`, real FFM, a real cache under a scratch path.

## Consequences

Coverage of the pure core becomes a measured number, not a hope, and
each new boundary type is exercised across its whole value and position
range the day it lands. The cost is real: a property failure shrinks to a
minimal case that has to be diagnosed and fixed like any bug, generators
are themselves code to maintain, and the end-to-end matrix spends compile
time, bounded by reusing the content-addressed cache so identical shapes
compile once. Bounded trials keep the default run fast, which means a
rare permutation can still slip a release until a deeper sweep finds it.

## Alternatives

Malli instead of `clojure.spec` was weighed: it is the more modern schema
library with better generator ergonomics, but `spec` is already on the
classpath and expressive enough here, so Malli is noted for later, not
adopted now. A Zig-native fuzzer was considered as a primary engine: it
would stress the native side hard, but it is immature in Zig 0.16 and
blind to the Clojure-to-FFM seam where most of the risk lives, so it is
parked. Staying with example tests only was rejected because they cannot
exhaust permutations or prove coverage. Using `core.logic` as the main
generator was rejected: `test.check` generators meet nearly every need,
and constraint solving only earns its place for adversarial struct
layouts with cross-field constraints, so `core.logic` is scoped to an
optional spike rather than the spine of the strategy.
