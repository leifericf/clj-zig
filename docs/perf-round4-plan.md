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

## Order and dependencies

Phase 1 (arena-fn hoist) is independent and the bigger structural
change. Phase 2 (compiled writer/reader capture) is contained to the
struct path and the marshal-arg-fn / invoke-ctx plumbing. Recommended
order: 1, 2.

## Captures that motivated this plan

- Profiles: `~/.agentic-sdk/clj-zig/artifacts/perf/round4/{scalar-passthrough,enum,slice-arg,struct-by-value,handle}.jfr`.
- Post-Round-3 bench numbers: `~/.agentic-sdk/clj-zig/artifacts/perf/perf-1783{149979484,150029769,15081458,15132620,15184112}.edn`.
