# 08 - Test Strategy

## Goal

Exhaust as many code paths and realistic input permutations as the
boundary admits, and prove the reach with coverage rather than assert it.
The example and acceptance tests stay as the intent and regression
backbone; generative and exhaustive testing layers on top. The decision
and its alternatives are ADR 23.

## What the architecture asks for

Zigar is a fat pure core and a thin real shell (ADR 16). Parsing,
normalization, spec construction, layout, source generation, and hashing
are data in and data out; compiling, loading, and rebinding are the edge.
The core is where correctness is decided, so most of the testing weight
sits there, where inputs are free and infinite. A thin marshalling band
sits at the FFM seam. A small but fully real end-to-end cap compiles and
calls actual native code. Orthogonal lanes cover leaks and the
redefinition lifecycle.

The toolchain is never mocked. The tests run a real `zig`, load through
real FFM, and use a real content-addressed cache under a scratch path.
A shell branch that feels untestable is a factoring finding: move the
decision into the core, do not build a mock.

## The four engines

1. **Bounded-exhaustive structural matrix.** Enumerate the cross-product
   of valid signatures, type by element type by position by constness by
   defining form, as data, and drive each through the pipeline. This lights
   every arm of `source/generate`, `zig-type`, the parameter declarations,
   and the FFM marshalling, layout, and descriptor code. It is the most
   realistic engine, because each case is an actual signature a user could
   write.

2. **Edge-value vectors crossed pairwise with structure.** Per type, the
   boundary values: minimum and maximum, `0`, `-1`, `2^63`, `2^64-1`, an
   empty and a single-element and a large slice, null, `NaN` and infinity,
   a missing field, a wrong enum or handle tag. Crossed pairwise with the
   structural axis, these light the width-by-sign coercion arms and the
   unsigned-promotion path where a `u64` crosses above the signed range.

3. **Negative-space enumeration.** Enumerate the invalid permutations,
   malformed type and compound forms, a misplaced or missing `:ret`, a
   void argument, an optional of a non-pointer, an error-union argument, an
   owned of a non-slice, a handle of a non-named type, a scalar with no
   carrier, an odd field list. Each must yield its specific `:error/code`.
   The error code is the oracle, and this exhausts the `validate!` and
   `normalize` rejection arms.

4. **Model-based stateful sequences.** Generated command sequences over
   define, call, redefine successfully, redefine into a compile failure,
   `recompile!`, and `clean!`, run through `stateful-check`. The
   invariants are keep-last-good after a failed redefinition, cache hit
   and miss, failed-attempt metadata, and arity errors. This lights the
   shell state machine that no single-shot test reaches.

## The leak lane

Owned and handle returns promise that native memory is freed. A `defz`
global allocation counter, incremented on allocate and decremented on
free, turns that promise into a property: after any random sequence of
create, use, and free operations, the counter returns to zero. Leak
freedom becomes a checked invariant rather than a hope.

## Closing the loop with coverage

`cloverage` is the feedback signal, not a vanity number. The target is
near-total coverage of the pure core; a named allowlist carries the few
genuinely hard shell arms, such as recovering from a zero-byte poisoned
library. A coverage gap is a missing permutation: it names the next case
to add to the matrix.

## Tooling

`test.check` is the primary engine, driven through `defspec`.
`clojure.spec` carries the boundary-contract schemas. `stateful-check`
runs the lifecycle model. `cloverage` is the proof. `core.logic` is held
in reserve for an optional spike on adversarial struct layouts, where
cross-field alignment constraints might outgrow hand-written generators.
A Zig-native fuzzer is parked: it is immature in Zig 0.16 and blind to
the Clojure-to-FFM seam where the risk concentrates.

## Discipline

- Properties use `test.check` `defspec` with bounded trials, so the
  default run stays fast; a deeper sweep raises the trial count behind an
  alias.
- A property that fails has found a real bug. Fix it as its own change and
  pin the shrunk minimal case as a regression example beside the property.
- The generators are real code under the same core-and-shell discipline as
  the library; they live in `test/zigar/gen.clj`.
- Reuse the content-addressed cache across the end-to-end matrix, so
  identical shapes compile once and the suite stays affordable.
