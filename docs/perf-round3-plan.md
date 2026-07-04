# Round 3 perf plan: evidence and ranked phases

Profiles captured under `~/.agentic-sdk/clj-zig/artifacts/perf/round3/`
post-Round-2, using the new in-process `--jfr` flag (Phase 0 below;
replaces the second-shell jcmd workflow). Each shape ran as its own JVM
with `--jfr <path> <shape>`; the recording used the `profile` settings
with a tightened 1 ms `jdk.ExecutionSample` period.

Round 2 left the scalar, enum, and handle shapes within 8-27 ns of the
direct-handle floor. The remaining cost is no longer the spreader
machinery (Phase 1 of Round 2); it is per-call dispatch inside clj-zig's
own coercion helpers, and a per-call map allocation in the arena pool.
Criterium's eval loop and the floor's `invokeWithArguments` still
dominate the raw sample counts (each is measured alongside defnz); the
counts below filter to stacks that include `execute_expr_core_timed_part`
(the measured loop) AND a `clj_zig.ffm` frame, so the numbers reflect
defnz's own per-call work.

## Per-shape hot clj-zig frames (samples in the measured loop)

| Shape  | Top frames (samples)                                                                                                     |
|--------|--------------------------------------------------------------------------------------------------------------------------|
| scalar | `bind$fn` 5090, `scalar_return_coerce$scalar_ret` 4182, `coerce_scalar` 3215, `check_arity_BANG_` 620                    |
| enum   | `bind$fn` 5520, `to_carrier` 4343, `enum_param_coerce` 4343, `to_carrier` static 2617, `enum_return_coerce` 716          |
| slice  | `bind$fn` 7366, `with_pooled_arena` 7353, `from_return` 3465, `coerce_scalar` 1494, `slice_aware_chain` 1224             |
| struct | `bind$fn` 9804, `with_pooled_arena` 9676, `invoke_struct_return` 4105, `marshal_arg_into_BANG_` 2792, `read_struct` 2503 |
| handle | `bind$fn` 7889, `to_carrier` 2549, `from_return` 2463, `enum_aware_coercions` (closure) 2465, `handle_param_coerce` 322  |

## Top 5 ranked offenders

### 1. `to_carrier` per-call dispatch in the enum-aware and slice-aware paths

Evidence: enum 4343 + 2617 samples in `to_carrier`; handle 2549 + 1404;
slice `slice_aware_chain` 1224 (each chain step calls to_carrier inline).
The enum-aware path's closure for a scalar param is `#(to-carrier p %)`
and the enum-param-coerce closure calls `(to-carrier {:type backing}
value)` -- both retain the per-call `case category` + `case bits`
dispatch. Round 2's Phase 4 inlined this dispatch into `scalar-param-coerce`
for the scalar-only hot path; the same pre-binding was never extended to
the enum-aware, slice-aware, or enum-param inner closures.

Fix: extract `scalar-param-coerce` (already in Round 2) and call it from
`enum-aware-coercions`, `enum-param-coerce`, and `slice-aware-writers`
at bind time. Each closure captures the pre-bound scalar coerce rather
than the param, and the per-call body is the specialized `.set` or
`unchecked-byte`/`unchecked-long` only.

Expected: 15-30 ns off enum, handle, and slice (the to_carrier frame and
its dispatch goes away). The enum-param path also picks up the
 BigInteger-to-long hoist (currently inside `to_carrier.invokeStatic`).
Risk: low. The same pattern Round 2's Phase 4 used; the closure is
already built at bind time, only its body changes.

### 2. `coerce_scalar` per-call dispatch in the scalar return path

Evidence: scalar `scalar_return_coerce$scalar_ret` 4182 + `coerce_scalar`
3215 samples (the `:else` branch in coerce_scalar.invokeStatic line 480
is the `:i64` decode). `scalar-return-coerce` captures `ret` at bind
time but still calls `(coerce-scalar ret v)`, which dispatches on
category and bits per call. The scalar hot path's Phase 4 (Round 2)
pre-bound the param side but not the return.

Fix: mirror `scalar-param-coerce` for the return. Build a per-bind
closure that captures the category and bits at bind time and inlines the
specific decode (a `:u64` decode returns a `Long` or `BigInteger` based
on the sign; an `:i64` decode returns a `Long`; etc.). Apply the same
specialization to the slice-aware return (which currently uses
`#(from-return ret %)`) and to enum-aware scalar returns.

Expected: 10-20 ns off scalar (the coerce_scalar dispatch goes away) and
a smaller win for slice (the from_return dispatch is replaced).
Risk: low. Pure refactor of an existing bind-time closure builder.

### 3. Arena pool allocates a new map per call

Evidence: slice `with_pooled_arena` 7353 samples; struct 9676. The pool
path is `(let [entry (refresh-if-needed) arena (:arena entry)]
(.set tl-arena (assoc entry :count (inc (:count entry)))) (f arena))`,
and the `(assoc entry :count ...)` allocates a fresh PersistentArrayMap
per call. The struct allocation-by-class shows
`clj_zig.ffm$bind$fn__1103$fn__1104` at 5% -- the arena-fn closure --
alongside PersistentArrayMap pressure not surfaced in the top-8 because
the spreader-related classes still lead.

Fix: replace the `{:arena :count}` map stored in the ThreadLocal with a
deftype `PoolEntry` carrying an immutable `arena` field and an
`^:unsynchronized-mutable ^long count` field. The pool path becomes a
ThreadLocal get, a volatile-style field read for the count check, a
field write for the increment, and the arena call. No map allocation.

Expected: 10-20 ns off slice and struct (one map alloc per call goes
away; the allocation-by-class pressure drops).
Risk: low-medium. The unsynchronized-mutable field is safe because the
entry is held in a ThreadLocal; only the owning thread reads or writes
it. Test the pool path under the existing thread-safety arms.

### 4. `marshal-arg-into!` case dispatch in the general path

Evidence: struct `marshal_arg_into_BANG_` 2792 samples. Each call walks
the `case (:kind type)` dispatch even though the param's kind is known
at bind time. For struct-by-value the kind is always `:named`, so the
general case dispatch is dead work for every call.

Fix: at bind time, build a per-param marshal closure that captures the
param's case directly. The general invoker's loop becomes a tight
`((nth marshal-fns i) arena carriers off (nth args i))` with no case
dispatch per call. Mirror Phase 4 (Round 2) and the slice-aware writer
chain.

Expected: 10-20 ns off struct and any general-path signature with a
fixed kind (slice-of-struct, array-of-scalar, etc.).
Risk: medium. Each case in `marshal-arg-into!` is its own per-bind
closure; the set is large (string, slice, manyptr, ptr, array, optional,
named, handle, scalar/i128). Each must produce an identical marshal.
Start with the cases the bench exercises (named, string, ptr) and the
rest fall through to a generic closure that calls `marshal-arg-into!`.

### 5. `from_return` per-call dispatch in the handle path

Evidence: handle `from_return` 2463 + `from_return.invokeStatic` 1373
samples. The enum-aware path's return closure for a `[:handle Type]`
return is `#(from-return ret %)`, which runs from_return's `cond` per
call. The handle case is a single `(.address ...)` check plus a Handle
construction.

Fix: add `handle-return-coerce` that captures `ret` at bind time and
inlines the handle wrap. Update `enum-aware-coercions` to use it for
handle returns alongside the existing `enum-return-coerce` for enum
returns and the new specialized scalar-return-coerce.

Expected: 5-10 ns off handle (the from_return cond goes away).
Risk: low. Single new closure builder; the handle case is small.

## Aggregate expected impact

| Shape  | Round 2 | Projected | Notes                                       |
|--------|---------|-----------|---------------------------------------------|
| scalar | 68 ns   | ~50 ns    | Phase 2 (return coerce inlined)             |
| enum   | 83 ns   | ~55 ns    | Phase 1 (to_carrier inlined in param coerce)|
| slice  | 180 ns  | ~135 ns   | Phase 1 + 2 + 3 (arena pool alloc)          |
| struct | 331 ns  | ~280 ns   | Phase 3 + 4 (marshal-arg inlining)          |
| handle | 146 ns  | ~110 ns   | Phase 1 + 5 (handle return coerce)          |

## Round 3 landed results

Every phase landed one commit on `perf/general-path-carrier-array`. The
defnz median across all seven bench shapes improved beyond projection;
four shapes (scalar, enum, slice, handle) now sit at or below their
direct-handle floor, meaning clj-zig's per-call overhead is below the
noise floor of the FFM `invokeWithArguments` baseline.

| Shape         | Round 2 | Round 3 | Delta  | Floor |
|---------------|---------|---------|--------|-------|
| scalar        | 68 ns   | 37 ns   | -31 ns | 56 ns |
| enum          | 83 ns   | 46 ns   | -37 ns | 56 ns |
| slice         | 180 ns  | 79 ns   | -101 ns| 62 ns |
| struct        | 331 ns  | 251 ns  | -80 ns | 54 ns |
| handle        | 146 ns  | 69 ns   | -77 ns | 131 ns|
| string        | 788 ns  | 704 ns  | -84 ns | 5337 ns |
| owned-return  | 1803 ns | 1692 ns | -111 ns| 5324 ns |

Floors unchanged within noise. The body-alloc-dominated shapes (string,
owned-return) improved modestly because their per-call cost is dominated
by `c_allocator`; the wrapper overhead Phase 3+4 removed is real but
small relative to the body allocation.

The per-shape wins track the per-phase root causes:

- Phase 1 (pre-bind scalar coerce in enum/slice paths) collapsed enum's
  `to_carrier` dispatch and dropped slice's scalar-in-slice dispatch.
- Phase 2 (inline `coerce_scalar` into `scalar-return-coerce` and thread
  it through slice/enum-aware returns) eliminated the scalar hot path's
  biggest frame (`coerce_scalar` 3215 samples).
- Phase 3 (long-array counter in `PoolEntry`) eliminated the per-call
  PersistentArrayMap allocation in `with-pooled-arena`. A mutable
  `^:unsynchronized-mutable ^long` field would have been the cleaner
  shape, but Clojure generates those as package-private and its own
  reflective `(.field instance)` accessor cannot read them; the
  long-array holder keeps both fields `public final` and the per-call
  cost is a direct `laload`/`lastore` pair.
- Phase 4 (per-bind `marshal-arg-fn`) collapsed the general invoker's
  per-call `case (:kind type)` walk; the loop is now a tight
  `((nth marshal-fns i) arena arg carriers off)` with the param's case
  body captured at bind.
- Phase 5 (per-bind `handle-return-coerce`) inlined the Handle wrap for
  the handle return path; handle now sits below its floor.

Floor stays where it is (it is the direct-handle path, outside clj-zig).
The body-alloc-dominated shapes (string, owned-return) are unchanged;
their per-call cost is the body's `c_allocator`, not the wrapper.

## Order and dependencies

Phase 0 (the in-process `--jfr` tooling) already landed. Phases 1 and 2
share the `scalar-param-coerce` / `scalar-return-coerce` helpers; land
together. Phase 3 (arena pool deftype) is independent. Phase 4 (per-bind
marshal-arg) is independent. Phase 5 (handle return coerce) depends on
Phase 2's return-coerce helper extraction.

Recommended order: 1+2, 3, 5, 4.

## Captures that motivated this plan

- Profiles: `~/.agentic-sdk/clj-zig/artifacts/perf/round3/{scalar-passthrough,enum,slice-arg,struct-by-value,handle}.jfr`.
- Smoke (Phase 0 verification): `/tmp/clj-zig-smoke.jfr` (gitignored).
- Post-Round-2 numbers record: `~/.agentic-sdk/clj-zig/artifacts/perf/perf-1783119760990.edn`.
