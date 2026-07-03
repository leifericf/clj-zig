# Round 2 perf plan: evidence and ranked phases

Profiles captured under `~/.agentic-sdk/clj-zig/artifacts/perf/round2/`
post-Phase-5. Each shape ran with a 30 s JFR recording at 1 ms
ExecutionSample period. Sample counts below are per 30 s run.

## Headline evidence: every shape is spreader-bound

Across all five shapes (scalar, enum, slice, struct, handle), the top
CPU frames are the same `MethodHandle.invokeWithArguments` spreader
machinery, and the top allocation classes are the spreader's internal
`MethodType` + `Class[]`:

| Shape   | invokeWithArguments | asSpreader | MethodType.equals | MethodType allocs | Class[] allocs |
|---------|---------------------|------------|-------------------|-------------------|----------------|
| scalar  | 3624                | 3671       | 3153              | 2580              | 3056           |
| enum    | 3770                | 3790       | 3007              | 3678              | 2201           |
| slice   | 4212                | 3488       | 2810              | 3094              | 2083           |
| struct  | 3219                | 3142       | 2606              | 1724              | 1660           |
| handle  | 3741                | 4071       | 2922              | 2061              | 1804           |

`invokeWithArguments` re-derives the spreader handle every call. This
is the floor + defnz both paying. Caching the spreader once per bind
is the single biggest lever.

## Per-shape clj-zig-only hot frames (defnz delta over floor)

| Shape  | Hot clj-zig frames (samples)                                                       |
|--------|------------------------------------------------------------------------------------|
| scalar | `bind$fn` 1347+1132 (count + loop), `from_return` 1236, `to_carrier` 963           |
| enum   | `bind$fn` 1593+1374, `to_carrier` 1290, `enum_param_coerce` 1044, `enum_index` 851|
| slice  | `with_pooled_arena` 1907+1791, `from_return` 1366, `bind$fn` 1150+650              |
| struct | `with_pooled_arena` 1279+642, `invoke_struct_return` 1033, `read_struct` 827       |
| handle | `bind$fn__1055$fn__1056` 1153 (general!), `with_pooled_arena` 1036, `marshal_arg_into!` 679 |

The `handle` shape is on the **general path** because the bench's
`free-box` (`[b [:handle Box] :ret :void]`) takes a `:handle` ARG,
which `enum-aware-scalar?` rejects. The `box` half is already on the
enum-aware path; `free-box` is not.

## Top 10 ranked offenders

### 1. invokeWithArguments spreader (per-call re-derivation)

Evidence: 9000-10000 CPU samples + 3400-5900 alloc samples per shape
(see table above).

Fix: at bind time, cache `(.asSpreader handle Object/[] arity)`. Call
site becomes `(.invokeExact ^MethodHandle spreader-handle ^objects
carriers)`. The spreader lookup runs once per bind, never per call.

Expected: 30-60 ns off every shape (floor and defnz).
Risk: medium. Per-bind asSpreader cache is straightforward;
`.invokeExact` call site needs `^MethodHandle` + `^objects` hints.
ADR 39 deferred arity-specialized invokers; this is the lighter
"cached spreader" version.

### 2. with_pooled_arena default-off

Evidence: slice 1907+1791 samples, struct 1279+642, handle 1036+642
samples. Allocations: slice 465 ConfinedSession + 466 SegmentFactories.

Fix: default the arena pool ON. The earlier "pool doesn't help"
measurement was pre-Phase-1 when the spreader dominated; with #1
landed the arena open/close is a larger fraction.

Expected: 20-30 ns off slice/struct/handle.
Risk: low. Pool is implemented, tested, and gated behind a flag; this
flips the default.

### 3. Handle-arg signatures on the general path

Evidence: handle bench measures box (handle RET, enum-aware path) +
free-box (handle ARG, general path). The general-path frames
(`marshal_arg_into_BANG_` 679, `with_pooled_arena` 1036, general
invoker 1153) are entirely from free-box.

Fix: widen `enum-aware-scalar?` to accept `[:handle Type]` args
(a handle arg is just an `aset` of the segment pointer; no arena).

Expected: ~150 ns off the handle bench (cuts free-box to the no-arena
path). Real-world impact on any handle-threading workload.
Risk: low. Handle arg marshalling is a single aset; one new case in
the enum-aware coercion builder.

### 4. to_carrier / from_return dispatch in the scalar hot path

Evidence: scalar 963 (to_carrier) + 1236 (from_return); enum 1290
(to_carrier). Each is a `case category :int (case bits ...)` dispatch
with a `type/scalar-info` lookup per call. The enum-aware path already
pre-binds coercions; the scalar path does not.

Fix: at bind time, build per-param `(fn [arg] (to-carrier param arg))`
closures and a per-bind return-coerce closure for the scalar hot path.
Same pattern as enum-aware-coercions.

Expected: 20-40 ns off scalar; 10-20 ns off enum (where it still
calls to_carrier for scalar args).
Risk: low. Identical pattern to Phase 2's enum-aware-coercions.

### 5. enum_index atom deref per call

Evidence: enum 851 (enum_index.invoke) + 705 (invokeStatic). The
`enum-index-cache` atom is deref'd every call to fetch the cached
{kw→value, value→kw} map. Volatile read.

Fix: capture the index map directly in the per-bind coercion closure
at bind time. The atom deref happens once per bind.

Expected: 10-20 ns off every enum arg/return.
Risk: low. Pure refactor of enum_param_coerce and enum_return_coerce
to take the index map instead of the descriptor.

### 6. Compiled struct reader still loops

Evidence: struct 827 (read_struct.invoke + invokeStatic). The
Phase-4 compiled reader uses `(loop [i ...] ((nth field-readers i)
seg acc) (recur ...))` -- `(nth field-readers i)` per iteration.

Fix: unroll the field-readers for the known field count. Per-bind
eval of `(fn [seg] ((nth field-readers 0) seg ...)
                       ((nth field-readers 1) seg ...))`.

Expected: 10-30 ns off struct (depends on field count).
Risk: medium. Requires per-bind eval (or a macro).

### 7. Slice-aware invoker body loops

Evidence: slice 1150 + 650 + 584 (bind$fn__1051$fn__1052 across three
lines). Same `(loop [i ...] ((nth writers i) ...) (recur ...))`
pattern as #6.

Fix: unroll the writer calls for the known arity.

Expected: 10-30 ns off slice.
Risk: medium. Same eval machinery as #6; share the implementation.

### 8. copy-back! closure allocated per call when no mutable args exist

Evidence: handle bench's general path allocates
`clj_zig.ffm$bind$fn__1055$fn__1056$fn__1057` (the copy-back closure)
360 times even though free-box has no mutable args.

Fix: at bind time, scan params for mutable-slice/ptr/array; if none,
install a singleton `noop-copy-back!` and skip the per-call loop
build.

Expected: 5-10 ns off general-path calls with no mutable args.
Risk: low. Static analysis at bind time.

### 9. Handle is a defrecord (heavy alloc per return)

Evidence: handle 397 samples of `clj_zig.ffm.Handle` allocation per
30 s run. A defrecord allocates a record instance + a
PersistentArrayMap backing for its field map.

Fix: convert Handle to a deftype with `^Object type` and
`^MemorySegment segment` fields. A deftype is a single instance with
named fields; no extra map. Print-method is redefined to keep the
existing `#clj-zig/handle[...]` representation.

Expected: 10-20 ns off every handle return (lighter alloc, less GC
pressure).
Risk: low. Handle has 6 call sites; `(:type h)` and `(:segment h)`
access works the same on a deftype with `:get` methods.

### 10. (count args) arity check walks the seq

Evidence: scalar `bind$fn__1042.doInvoke` line 1592 (the count check)
1347 samples. `(count args)` walks the args seq, O(arity) per call.

Fix: tighter arity check. The cleanest is to drop the explicit check
and rely on the invoker's fixed-arity `(fn [& args] ...)` body
raising naturally when `(first as)` runs out, but that loses the
clear arity diagnostic. The pragmatic fix is to hint `args` as
`^clojure.lang.ArraySeq` and use `.length` (constant time on the
underlying array) instead of `count`. The deeper fix is per-arity
generated invokers; out of scope here.

Expected: 5-10 ns off everything.
Risk: low for the `.length` hint; medium for per-arity generation
(deferred).

## Aggregate expected impact

| Shape  | Current | Projected | Notes                                              |
|--------|---------|-----------|----------------------------------------------------|
| scalar | 194 ns  | ~110 ns   | Phase 1 + 4 + 5 + 10                               |
| enum   | 197 ns  | ~110 ns   | Phase 1 + 4 + 5 + 10                               |
| slice  | 232 ns  | ~140 ns   | Phase 1 + 2 + 7 + 10                               |
| struct | 388 ns  | ~260 ns   | Phase 1 + 2 + 6 + 10                               |
| handle | 347 ns  | ~150 ns   | Phase 1 + 2 + 3 + 8 + 9 + 10 (free-box joins hot)  |

Floor is bounded by Phase 1; once the spreader is cached the floor
itself drops to ~20-30 ns. Phases 6 and 7 are field/arity-count
dependent; gains vary.

## Order and dependencies

Phase 1 is foundational (touches every call). Phases 2, 3, 4, 5, 8, 9,
10 are independent of each other and could land in any order. Phases 6
and 7 share per-bind eval machinery; land together.

Recommended order: 1, 3, 4, 2, 5, 6+7, 8, 9, 10.
