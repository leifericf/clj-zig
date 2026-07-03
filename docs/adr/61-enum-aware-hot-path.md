# ADR 61: An enum-aware hot-path call mode for defnz

Date: 2026-07-03

## Context

ADR 39 carved a hot path for scalar-only signatures: no confined arena,
no per-arg marshalling map, just a thread-local carrier array filled with
`to-carrier` and a `from-return` read-back. A signature with any non-scalar
param or return -- including an enum -- took the general arena-backed
path, which allocates a `mapv` of marshalled maps, a `mapcat :carriers`
lazy seq, a `copy-back!` closure, and a `concat`+`object-array` per
invoke-* helper.

But an enum crosses the C ABI as its backing scalar (`i32` by default).
A signature like `[s Suit :ret Suit]` lowers to the same `(int) -> int`
ABI the scalar hot path already serves. The bench measured the cost of
sending it through the general path: `:enum` ran at ~965 ns per call
against a ~55 ns floor (a ~17x overhead), while `:scalar-passthrough`
ran at ~199 ns. The gap was the general-path bookkeeping applied to a
shape that did not need it.

The keyword-to-backing-int and int-to-keyword translations an enum adds
are O(1) map lookups through the cached `enum-index`, so they are cheap
to fold into the scalar hot path's carrier-array choreography.

## Decision

`bind` detects an enum-aware signature -- every param and the return a
plain scalar or a named enum, and the signature not pure-scalar -- and
returns a third invoker alongside the scalar and general ones. The
enum-aware invoker:

- Opens NO confined arena, like the scalar hot path. An enum crosses as
  a primitive, not a segment.
- Reuses a thread-local carrier array of size `arity`, one slot per
  param's backing scalar.
- Coerces each arg through a per-param closure built once at bind time:
  `to-carrier` for a scalar, the cached `enum-member->value` lookup plus
  backing-scalar `to-carrier` for an enum.
- Reads the return through a per-bind closure: `from-return` for a
  scalar return, `enum-value->member` for an enum return.

The predicate `enum-aware-scalar?` is true only when `scalar-only?` is
false, so the scalar hot path stays single-shape and the enum-aware path
is reached only for sigs that mix in an enum.

## Consequences

An enum-only or enum-and-scalar signature called in a loop no longer
allocates and closes a confined arena per call, nor builds the per-arg
marshalling maps. The bench measures `:enum` at ~175 ns per call against
a ~60 ns floor -- a ~3x overhead, parity with the scalar hot path's
shape and well below the general path's ~17x.

The hot path reuses the existing enum cache and `to-carrier`/coercion,
so its results are identical to the general path by construction; the
existing enum round-trip and volume suites cover it, plus an
enum-aware-selects-the-enum-path arm and a thread-safety arm mirroring
the scalar hot path's.

## Alternatives

Leave enum on the general path. Rejected: it is measurable, avoidable
overhead on a signature shape that is, at the C ABI, indistinguishable
from a scalar.

Extend the scalar hot path to inline enum coercion. Rejected: keeping
the scalar hot path single-shape preserves its single-type fast path
and avoids per-arg type dispatch in the tightest loop. The enum-aware
invoker carries its own per-arg closures, dispatch-free per call.

## Updates ADR 39

ADR 39's "Any signature with a slice, pointer, array, struct, enum,
handle, optional, or an error-union/owned/struct return keeps the
general arena-backed path unchanged" no longer holds for enum: an
enum-aware signature now takes this hot path. ADR 39's scalar-only
predicate and invoker are unchanged; this ADR adds a sibling alongside.
