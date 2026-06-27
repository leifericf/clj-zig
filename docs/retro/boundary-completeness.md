# Retrospective: boundary-completeness

Slice: `boundary-completeness` (the boundary-completeness campaign).
Landed: 13 commits on `main`, tip `cc65de5`, marker
`strings-and-result-records`.

## What shipped

Closed four gaps between what Clojure developers expect and what the
boundary handled: `:string` as a first-class type (argument and
return), result records with owned buffer/`:string` fields (doc 10,
phases 1 through 4), `[:optional :scalar]` nil-or-value, and
`:error-union` over enum and struct. The `[:slice Struct]` silent trap
was closed at spec time. ADRs 21, 14, and 19 were amended.

## Defect classes: none

All seven reviewer passes (a2, b4, c2, d2, e2, g2, h4) returned clean.
There were zero fix commits from review. The slice reached the tip
review-dry on every phase. No recipe is hardened, because no class of
defect recurred to harden against. The skill's guidance holds: a clean
slice is a valid result, and inventing waste to act on would bloat the
recipes.

## Why the slice stayed clean

Three orchestration moves earned the clean pass, and are worth keeping:

- The planner front-loaded the load-bearing native-edge decisions as
  settled choices in the plan's notes (the `:string` arg accepting both
  `String` and `byte[]`; `[:owned RecordType]` delivered inside p2c;
  `[:optional :scalar]` lowering to a nullable pointer). Writers did
  not guess at the ABI; they executed a recorded choice.
- The campaign-context note carried the memory and security invariants
  (free-in-finally, len-bounded reads, the empty-string no-deref guard)
  once, and every dispatch cited it instead of rediscovering them.
  The c8a822b free-in-finally fix was referenced explicitly and held.
- Tests-first in every writer task pinned the contract before the
  codegen, so the codegen was verified against a failing test, not by
  inspection.

## Cost findings

- Per-phase lane runs (eight total) were correct rigor here, not waste.
  The critical path was serial (`p0` through `p3b`), and the
  linear-stack discipline verifies a phase before its dependent starts.
  Running the lane per-deliverable instead of per-phase would have
  stacked unverified code.
- `p3a` and `p3b` were run sequentially rather than fanned out in
  parallel, deliberately. Both edited `spec.clj` arms; parallel
  fan-out would have conflicted on land. Correctness over wall-clock.
- The lane is slow: `clojure -M:test` compiles real Zig per test and
  exceeds the 120s default timeout. Each change-runner used a generous
  timeout and the suite held at 395 tests and 1977 assertions. The
  slowness is inherent to compiling real native code per case.

## Recommended, not landed

One design-level cost cut is the maintainer's call, not a recipe edit:

- A persistent or amortized test runner (one JVM that compiles and
  caches across cases, or a warm compile cache shared across the suite)
  would cut the per-phase wall-clock substantially. This is a
  toolchain investment in `maintain-toolchain`, out of scope for a
  retrospective that edits only recipes and lanes.

## Improvements landed

None. The slice was review-dry; nothing recurred to prevent.

## Could not have been prevented

Nothing arose that prevention would have caught. The one runtime-only
risk the suite cannot fully exhaust (target-width ABI under a future
32-bit target) is covered by a layout test that asserts the descriptor
is `usize`-width-aware, and the wire offsets participate in the content
hash so a new target recomputes them. That is already in place from
p2a.
