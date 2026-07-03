# ADR 60: Native allocation profiling under an opt-in flag

Date: 2026-07-03

## Context

The perf overlay measures per-call wall-clock (the harness, ADRs 57 and 58)
but not native allocation. To optimize responsibly the maintainer needs a
per-shape native allocation count: which shapes allocate, and how a free-shim
change moves the count. The count must never reach a production library.

The plan's first lever was `std.heap.TrackingAllocator`. The pinned compiler
(Zig 0.16.0) ships no such allocator (it was removed after 0.13; nothing
named tracking exists under `std/`), so a hand-rolled counter is mandatory.

## Decision

A `:zig/track-allocations` codegen flag, parallel to `:zig/optimize` (ADR 41),
wraps the generated wrapper's allocator in a counting `std.mem.Allocator`
vtable over `c_allocator`. Under the flag, `source.clj` rewrites every literal
`std.heap.c_allocator` in the rendered wrapper source to the counter symbol,
so both wrapper-generated calls and bench-body calls are counted. The counter
is a per-library process-global exposed to the bench through two exported
zero-arg functions, `<sym>__alloc_count_get` and `<sym>__alloc_count_reset`,
bound via the cached-MethodHandle FFM path the floor handle already uses.

The count is allocation EVENTS: it increments on each alloc (and a resize or
remap that grows) and does NOT decrement on free. A net-live count collapses
toward zero for any shape that frees correctly, defeating the 0/non-0 split
that is the count's reason to exist.

The flag defaults OFF. It enters the descriptor `:options` map, so by ADR 12
(content-addressed caching) a profiling build gets its own cache key and never
pollutes a default library; the default path's source and cache key are
byte-for-byte unchanged.

## Consequences

A profiling build is fully isolated: a distinct cache key, a distinct library,
and no effect on production codegen. The bench reads a per-shape
allocation-event count (0 for non-allocating shapes, >0 for allocating ones)
that moves when the free shim changes, giving optimization a stable allocation
signal alongside the wall-clock baseline.

The cost is a source-level rewrite of `c_allocator` references under the flag,
which couples the counter to the rendered source text rather than to a
parameter threaded through every node constructor. That coupling is acceptable
for an opt-in profiling flag that defaults off; it would be the wrong shape
for a production allocator abstraction.

## Alternatives

`std.heap.TrackingAllocator`. Rejected: unavailable in Zig 0.16.0 (the pinned
compiler); the hand-rolled counter is the plan's sanctioned fallback, pulled
forward when the standard library lacked the API.

Thread an allocator-identifier parameter through every node constructor in
`source.clj`. Rejected: high churn across reconstruction, free shims, and
ownership paths for a flag that defaults off.

Net-live count (alloc minus free). Rejected: collapses toward zero for shapes
that free correctly, failing the 0/non-0 split that distinguishes allocating
from non-allocating shapes.

Expose the counter as an `export var` read and written via symbol-address
deref. Rejected: more FFM machinery (MemorySegment reinterpret, address write)
for no gain over two zero-arg downcall functions that reuse the existing
cached-MethodHandle path.
