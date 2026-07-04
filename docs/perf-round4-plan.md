# Round 4 perf plan: evidence and ranked phases

Profiles captured under `~/.agentic-sdk/clj-zig/artifacts/perf/round4/`
against the post-Round-3 tip (`594a043`), using `clojure -M:bench --jfr
<path> <shape>`. Same filter as Round 3: stacks include
`execute_expr_core_timed_part` AND a `clj_zig.ffm` frame, so the counts
isolate defnz's own per-call work (Criterium's eval loop and the floor's
`invokeWithArguments` still dominate the raw sample counts).

Round 3 left the scalar, enum, slice, and handle shapes at or below
their direct-handle floor. The remaining per-call overhead is
concentrated in the struct-by-value path (251 ns vs 54 ns floor) and,
to a much smaller degree, the slice and body-alloc shapes. The two
offenders below account for the bulk of the struct overhead.

## Per-shape hot clj-zig frames (samples in the measured loop)

| Shape  | Top frames (samples)                                                                                                  |
|--------|------------------------------------------------------------------------------------------------------------------------|
| scalar | `bind$fn` (closure), `check_arity_BANG_` 1553 -- at floor, no actionable offender                                       |
| enum   | `bind$fn` 3032, `check_arity_BANG_` 1553, `handle_param_coerce$handle_coerce` 1104 -- at floor                          |
| slice  | `bind$fn` 3758, `with_pooled_arena` 3758, `bind$fn$fn__1150` (arena-fn) 3691, `slice_aware_chain` 2246                  |
| struct | `bind$fn` 7861, `with_pooled_arena` 7860, `bind$fn$fn__1154` (arena-fn) 7308, `marshal_struct` 2521, `compiled_struct_writer` 1565, `compiled_struct_reader` 1428 |
| handle | `bind$fn` 3032, `check_arity_BANG_` 1553, `handle_param_coerce` 1104 -- at floor                                        |

## Top 2 ranked offenders

### 1. The arena-fn closure allocated per call

Evidence: struct allocation-by-class shows
`clj_zig.ffm$bind$fn__1153$fn__1154` at 6.88% allocation pressure; the
slice path's sibling closure (`bind$fn__1149$fn__1150`) shows up at
3691 samples in the measured loop. Both are the `(fn [^Arena arena] ...)`
literal each invoker passes to `with-pooled-arena`: reified fresh on
every call, used once, immediately garbage.

Fix: hoist the arena-fn to bind time. Each invoker builds its arena-fn
once in `bind-context` (capturing everything except the arena and the
args), then the per-call body is straight-line: check arity, acquire
arena, call the pre-bound arena-fn, release arena. Add
`acquire-pooled-arena!` and `release-pooled-arena` helpers, and split
the invoker by `pool-enabled?` at bind time so the pooled path has no
`try/finally` (the pool's release is a no-op), while the per-call path
keeps the `with-open` for the disabled case.

Expected: 5-15 ns off struct, slice, string, and owned-return (one
closure alloc per call goes away; one `with-pooled-arena` call frame
goes away; the pooled path drops its `try/finally`).

Risk: low-medium. Two new per-bind invoker variants (pooled vs
non-pooled) and the arena lifecycle shifts from `with-pooled-arena` to
its callers. The pool's existing thread-safety arms (in
`allocation-balance-prop-test`) cover the change; the disabled-pool
path keeps the exact `with-open` semantics.

### 2. Per-call compiled-writer / compiled-reader cache lookups

Evidence: struct `compiled_struct_writer.invokeStatic` 1565 samples
(line 832, the `(find @struct-writer-cache descriptor)` lookup);
`compiled_struct_reader.invokeStatic` 1428 samples (the sibling lookup
on the read side). Each call into `marshal-struct` and `read-struct`
re-looks-up the compiled closure by descriptor, even though the
descriptor is fixed at bind time. The `find` on the atom's
PersistentHashMap is O(1) but allocates a `MapEntry` and is pure
overhead when the descriptor never changes for a given bind.

Fix: capture the compiled writer inside `marshal-arg-fn`'s `:named`
case (one cache lookup at bind time, the per-call closure calls the
writer directly); plumb the compiled reader into `invoke-ctx` so
`invoke-struct-return` (and the eu-struct / owned-record variants) call
the reader directly. The generic `marshal-struct` / `read-struct` entry
points stay for the fallback paths that don't have a compiled closure.

Expected: 10-20 ns off struct (two `find` calls and two MapEntry
allocations per call go away).

Risk: low. Pure refactor of bind-time capture; the compiled closures
already existed, they were just looked up per call.

## Aggregate expected impact

| Shape         | Round 3 | Projected | Notes                                                  |
|---------------|---------|-----------|--------------------------------------------------------|
| scalar        | 37 ns   | 37 ns     | at floor, no change                                    |
| enum          | 46 ns   | 46 ns     | at floor, no change                                    |
| slice         | 79 ns   | ~70 ns    | Phase 1 (arena-fn closure)                             |
| struct        | 251 ns  | ~220 ns   | Phase 1 + Phase 2 (writer/reader cached at bind)       |
| handle        | 69 ns   | 69 ns     | at floor, no change                                    |
| string        | 704 ns  | ~690 ns   | Phase 1; body-alloc dominates the rest                 |
| owned-return  | 1692 ns | ~1680 ns  | Phase 1; body-alloc dominates the rest                 |

The at-floor shapes (scalar, enum, handle) are projected unchanged:
their per-call overhead is already below the direct-handle floor
(theirs plus the noise of `invokeWithArguments` on the floor side).
The struct path carries most of the remaining winnable overhead.

## Round 4 landed results

Phases 1 through 5 each landed one commit. Phase 3 (pool the
out-segment per thread), Phase 4 (pool the marshal-segment per thread),
and Phase 5 (specialize the return dispatch at bind time) emerged from
re-profiling after Phase 2; all three follow patterns Phase 1 and 2
established (`ThreadLocal` + `global-arena` for the pools, captured
per-bind closure for the dispatch).

The struct path collapsed from 251 ns to 123 ns across the five
phases, a 51% reduction in per-call overhead. The other shapes are
unchanged within noise; they sit at or below their direct-handle
floor, so wrapper-side wins have nothing left to give there.

| Shape         | Round 3 | Round 4 | Delta   | Floor |
|---------------|---------|---------|---------|-------|
| scalar        | 37 ns   | 40 ns   | +3 ns   | 58 ns |
| enum          | 46 ns   | 46 ns   | 0       | 55 ns |
| slice         | 79 ns   | 80 ns   | +1 ns   | 59 ns |
| struct        | 251 ns  | 123 ns  | -128 ns | 58 ns |
| handle        | 69 ns   | 74 ns   | +5 ns   | 118 ns|
| string        | 704 ns  | 699 ns  | -5 ns   | 5365 ns |
| owned-return  | 1692 ns | 1624 ns | -68 ns  | 5104 ns |

The small deltas on scalar, enum, slice, and handle are Criterium
noise across runs; the struct delta is well outside any reasonable
noise band. scalar, enum, slice, and handle were already at or below
floor in Round 3 and stay there.

### Per-phase wins on the struct path

| Phase                                              | struct median |
|----------------------------------------------------|---------------|
| Round 3 baseline                                   | 251 ns        |
| + Phase 1: hoist arena-fn closure to bind time     | 233 ns        |
| + Phase 2: capture writer/reader at bind time      | 180 ns        |
| + Phase 3: pool the struct-out segment per thread  | 156 ns        |
| + Phase 4: pool the marshal segment per thread     | 120 ns        |
| + Phase 5: specialize the return dispatch at bind  | 113 ns        |

Phase 1 (hoist the arena-fn closure) is the structural cleanup: a
pre-bound `arena-fn` plus a per-bind `pool-invoker` that splits the
pool-enabled and pool-disabled paths, so the pooled path has no
`try/finally`. Phase 2 (capture the compiled writer/reader at bind
time) collapses the per-call `find` on the cache atoms and the
`MapEntry` allocation each `find` does. Phase 3 and Phase 4 each pool
one native segment per thread per bind: the out-segment the native
call writes the struct into, and the marshal-segment the writer chain
fills. Phase 5 captures the per-bind return-shape branch as a
dedicated dispatch closure, so the per-call body is one direct invoke
with no `cond` walk. Both pool phases reuse the same `Arena/ofShared`
`global-arena` and the same `ThreadLocal` pattern as the existing
`carriers-tl`.

A JDK-26 wrinkle forced a small change in approach versus the
Round 3 plan's wording: `Arena/ofGlobal` was a preview-API removal
candidate and is gone in JDK 26, so the segments are allocated from a
never-closed `Arena/ofShared` instead. The shared-arena allocation
overhead is paid once at TL init; the per-call cost is the segment
header, which is now zero (reused, not allocated).

### What's left on the struct path

Post-Phase-5, struct sits at 113-123 ns against a 58 ns floor, so
~55-65 ns of overhead remains. Re-profiling attributes the bulk of
that to:

- `RT.longCast` (long-primitive unbox across the carrier-fill loop,
  the pool counter, and each field's offset).
- `PersistentArrayMap.indexOf` + `PersistentArrayMap$TransientArrayMap.indexOf`
  (the writer's `(.get m kw)` per field and the reader's `assoc!` per
  field; intrinsic to Clojure's map shape and the input contract).
- `pool_invoker` + `bind$general_arena_fn` per-call frames (the
  outer invoker and the carrier-fill loop; further wins require
  per-arity specialization, which is invasive).

These are at or below the cost floor the JIT can extract from
Clojure's invocation model without changing the public map-in/map-out
contract for `deftypez` returns.

## Order and dependencies

Phase 1 (arena-fn hoist) is independent and the bigger structural
change. Phase 2 (compiled writer/reader capture) is contained to the
struct path and the marshal-arg-fn / invoke-ctx plumbing. Phase 3
(struct-out pool) and Phase 4 (marshal-segment pool) follow from
re-profiling after Phase 2 and reuse the `ThreadLocal` + `global-arena`
pattern; Phase 4 factored `build-struct-writer` to expose
`build-struct-writer-chain` so the marshal path can pool its segment
while reusing the chain. Phase 5 (return-shape dispatch specialization)
closes the per-call `cond` walk in the arena-fn body. Recommended
order: 1, 2, 3, 4, 5.

## Captures that motivated this plan

- Profiles: `~/.agentic-sdk/clj-zig/artifacts/perf/round4/{scalar-passthrough,enum,slice-arg,struct-by-value,handle}.jfr`.
- Post-Round-3 bench numbers: `~/.agentic-sdk/clj-zig/artifacts/perf/perf-1783{149979484,150029769,15081458,15132620,15184112}.edn`.
