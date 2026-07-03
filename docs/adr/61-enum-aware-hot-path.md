# ADR 61: An enum- and handle-aware hot-path call mode for defnz

Date: 2026-07-03

## Context

ADR 39 carved a hot path for scalar-only signatures: no confined arena,
no per-arg marshalling map, just a thread-local carrier array filled with
`to-carrier` and a `from-return` read-back. A signature with any non-scalar
param or return -- including an enum or a `[:handle Type]` return -- took
the general arena-backed path, which allocates a `mapv` of marshalled
maps, a `mapcat :carriers` lazy seq, a `copy-back!` closure, and a
`concat`+`object-array` per invoke-* helper.

But an enum crosses the C ABI as its backing scalar (`i32` by default),
and a handle return is a pointer with no out-seg. A signature like
`[s Suit :ret Suit]` or `[v :i64 :ret [:handle Box]]` lowers to the same
`(int|long) -> (int|long|ptr)` ABI the scalar hot path already serves. The
bench measured the cost of sending them through the general path:
`:enum` ran at ~965 ns per call against a ~55 ns floor (a ~17x overhead),
and `:handle` ran at ~1716 ns against a ~120 ns floor. The scalar
hot path (`:scalar-passthrough`) ran at ~199 ns. The gap was the
general-path bookkeeping applied to shapes that did not need it.

The keyword-to-backing-int and int-to-keyword translations an enum adds
are O(1) map lookups through the cached `enum-index`. The Handle record
wrap a handle return adds is one record allocation. Both are cheap to
fold into the scalar hot path's carrier-array choreography.

## Decision

`bind` detects an enum-aware signature -- every param a plain scalar or
a named enum, the return a plain scalar, a named enum, or a `[:handle
Type]`, and the signature not pure-scalar -- and returns a third invoker
alongside the scalar and general ones. The enum-aware invoker:

- Opens NO confined arena, like the scalar hot path. An enum crosses as
  a primitive and a handle return is a pointer; neither needs a segment.
- Reuses a thread-local carrier array of size `arity`, one slot per
  param's backing scalar.
- Coerces each arg through a per-param closure built once at bind time:
  `to-carrier` for a scalar, the cached `enum-member->value` lookup plus
  backing-scalar `to-carrier` for an enum.
- Reads the return through a per-bind closure: `from-return` for a
  scalar, `enum-value->member` for an enum, the `->Handle` wrap for a
  `[:handle Type]` (nil for a null pointer).

The predicate `enum-aware-scalar?` is true only when `scalar-only?` is
false, so the scalar hot path stays single-shape and the enum-aware path
is reached only for sigs that mix in an enum or carry a handle return.

## Consequences

An enum-only, enum-and-scalar, or scalar-args/handle-return signature
called in a loop no longer allocates and closes a confined arena per
call, nor builds the per-arg marshalling maps. The bench measures
`:enum` at ~185 ns per call against a ~60 ns floor (parity with the
scalar hot path's shape), and `:handle` at ~330 ns against a ~120 ns
floor (the residual is the body's `c_allocator.create` plus the Handle
record allocation).

The hot path reuses the existing enum cache, `to-carrier`, and
`from-return`, so its results are identical to the general path by
construction; the existing enum round-trip, handle round-trip, and
volume suites cover it, plus an enum-aware-selects-the-enum-path arm
and a thread-safety arm mirroring the scalar hot path's.

## Alternatives

Leave enum and handle on the general path. Rejected: it is measurable,
avoidable overhead on signature shapes that are, at the C ABI,
indistinguishable from a scalar (enum) or a plain pointer (handle).

Extend the scalar hot path to inline enum and handle coercion. Rejected:
keeping the scalar hot path single-shape preserves its single-type fast
path and avoids per-arg type dispatch in the tightest loop. The
enum-aware invoker carries its own per-arg closures, dispatch-free per
call.

## Updates ADR 39

ADR 39's "Any signature with a slice, pointer, array, struct, enum,
handle, optional, or an error-union/owned/struct return keeps the
general arena-backed path unchanged" no longer holds for enum (args or
return) or for a `[:handle Type]` return with scalar/enum args: those
signatures now take this hot path. ADR 39's scalar-only predicate and
invoker are unchanged; this ADR adds a sibling alongside.

