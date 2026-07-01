# ADR 42: Rest arguments lower to a trailing const slice

Date: 2026-07-01

## Context

`defnz` reserved `&` and threw `:clj-zig/reserved-rest-arg` at signature
parse time. Clojure developers reach for `&` to write variadic functions
reflexively, and the natural lowering already existed: a trailing const
slice is exactly the shape a variadic scalar call becomes. The slice
marshalling path, the source generator, and the spec validator needed no
change to accept the lowered form; only the signature parser and the
`defnz` macro needed to know about rest.

## Decision

A trailing `& binding type` in a signature is sugar for one final
argument of `[:slice :const type]`, flagged `:rest? true`. The signature
parser lowers it at normalization time; the rest of the pipeline (spec,
source generation, FFM marshalling) sees an ordinary const-slice
argument.

The element must be a carrier scalar keyword. `& rest Point`,
`& rest :i128`, and `& rest [:slice :i64]` throw
`:clj-zig/unsupported-rest-element` at parse time, before any spec or
compile. Rest of a named type stays out even after slice-of-named-type
support lands, because a variadic rest of a struct is a poor fit and the
scalar case covers the motivating uses (variadic sums, maxima, means).

The `&` marker must introduce the final argument: exactly `& binding
type` between the leading pairs and `:ret`. Anything else (`&` mid-
signature, a lone `&`, a second `&`) throws `:clj-zig/misplaced-rest`.

The `defnz` macro reads the rest flag off the raw signature's
normalization (the prepared signature flattens through a vector that
drops it), builds a variadic Clojure arglist `[x & rest]`, and emits a
wrapper that boxes the rest seq into the element's primitive array
(`long-array`, `double-array`, `int-array`, ...) before invoking. The
array constructor matches the FFM carrier the slice marshaller copies
from, so the rest path reuses the existing slice marshalling unchanged.

## Consequences

A developer writes `(defnz maximum [x :i64 & rest :i64 :ret :i64] ...)`
and calls `(maximum 1 2 3 4)`. The empty-rest case `(maximum 5)` produces
a zero-length slice the body iterates zero times, reusing the existing
empty-slice guard.

The cache key is unaffected: the lowered signature is identical to a
hand-written const-slice argument, so a rest function and its explicit-
slice twin hash alike when their bodies match.

## Alternatives

Keep `&` reserved. Rejected: the lowering is mechanical, the slice path
already exists, and the variadic ergonomics are what Clojure developers
expect. The reservation was a placeholder for exactly this.

Rest over an explicit slice argument (`(defnz maximum [x :i64 rest [:slice
:const :i64] ...]`) called with `(apply maximum 1 (long-array [2 3 4]))`.
Rejected as the only option: the caller must build the primitive array,
which defeats the variadic ergonomics. The explicit-slice form remains
available for the caller who already has an array in hand.
