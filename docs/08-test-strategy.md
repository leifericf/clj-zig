# 08 - Test Strategy

## Goal

Exhaust as many code paths and realistic input permutations as the
boundary admits, and prove the reach with coverage rather than assert it.
The example and acceptance tests carry intent and guard against
regressions; generative and exhaustive testing exhausts the space around
them.

## The shape of the suite

clj-zig is a fat pure core and a thin real shell. Parsing, normalization,
spec construction, layout, source generation, and hashing take data and
return data; compiling, loading, and rebinding are the edge. The core is
where correctness is decided, so most of the testing weight sits there,
where inputs are free and unbounded. A thin band of tests covers the
marshalling at the FFM seam. A smaller end-to-end set compiles and calls
real native code. Two further lanes cover memory leaks and the
redefinition lifecycle.

The toolchain is never mocked. The tests run a real `zig`, load through
FFM, and use a content-addressed cache on disk. A shell branch that
resists testing is a sign of misplaced logic. The decision moves into the
core rather than behind a mock.

## The four engines

1. **Bounded-exhaustive structural matrix.** The cross-product of valid
   signatures, type by element type by position by constness by defining
   form, is enumerated as data and driven through the pipeline. Each case
   is an actual signature a user could write, so this exercises every arm
   of source generation and of the FFM marshalling, layout, and descriptor
   code.

2. **Edge-value vectors crossed with structure.** Each type carries its
   boundary values: minimum and maximum, `0`, `-1`, `2^63`, `2^64-1`, an
   empty, single, and large slice, null, `NaN`, and infinity, a missing
   field, a wrong enum or handle tag. Crossed with the structural axis,
   these reach the width-by-sign coercion arms and the unsigned-promotion
   path where a `u64` crosses above the signed range.

3. **Negative-space enumeration.** The invalid permutations are
   enumerated too: malformed type and compound forms, a misplaced or
   missing `:ret`, a void argument, an optional of a non-pointer, an
   error-union argument, an owned of a non-slice, a handle of a non-named
   type, a scalar with no carrier, an odd field list. Each yields its
   specific `:error/code`, and the code is the oracle, so this covers the
   rejection arms of normalization and validation.

4. **Model-based stateful sequences.** Generated command sequences over
   define, call, redefine successfully, redefine into a compile failure,
   recompile, and clean run through `stateful-check`. They check
   keep-last-good after a failed redefinition, cache hit and miss, the
   failed-attempt record, and arity errors. They reach the shell state
   machine that no single-shot test does.

## The leak lane

Owned and handle returns promise that native memory is freed. A native
allocation counter lives in heap memory addressed by a handle, so every
library shares one counter through the same pointer. Allocation
increments it and freeing decrements it. After any sequence of create,
use, and free operations, the counter returns to zero.

## The coverage loop

`cloverage` measures how much of the pure core the tests reach and floors
it, so a gap fails the build. A named allowlist carries the shell, whose
compile, FFM, rebinding, and diagnostic code is covered by the native,
end-to-end, and lifecycle suites rather than measured here. A gap in the
pure core is a missing permutation, and it names the next case to add to
the matrix.

## Tools

`test.check` is the engine for the properties, driven through `defspec`.
`clojure.spec` carries the boundary-contract schemas. `stateful-check`
runs the lifecycle model. `cloverage` measures and floors the pure core.

## Discipline

- Properties run through `defspec` with bounded trials, so the default
  run stays fast; a deeper sweep raises the trial count behind an alias.
- A failing property has found a real bug. Fix it in a dedicated change,
  and pin the shrunk minimal case as a regression beside the property.
- The generators are ordinary code under the same core-and-shell
  discipline as the library, in `test/clj_zig/gen.clj`.
- The end-to-end matrix reuses the content-addressed cache, so an
  identical shape compiles once and the suite stays affordable.
