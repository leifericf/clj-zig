# ADR 58: The clj-zig.foreign invoke floor is Clojure-reachable, not Java-direct

Date: 2026-07-03

## Context

The per-call overhead harness measures each contract shape twice: once
through the high-level `defnz` invoker, once through a floor, and
reports `overhead = defnz - floor`. The floor must isolate per-call
invoke cost so the overhead number attributes cost to clj-zig's
marshalling and call setup, not to the native body.

`clj-zig.foreign` (ADR 37) binds and caches a `MethodHandle` per
downcall. Its PERFORMANCE note describes the ideal Java-direct-invoke
floor: a caller invokes the cached handle with typed primitive
arguments, allocating nothing per call. On the scalar shape that ideal
floors near 1 to 2 ns.

But Clojure's compiler cannot emit that call site. A primitive-hinted
`defn` wrapping `(.invoke h x)` fails `AbstractMethodError`; an inline
`(.invoke h (long x))` fails `ClassCastException` because Clojure emits
the call against `invoke(Object[])`. The cheapest reliable Clojure
invoke of a cached `MethodHandle` is `invokeWithArguments` against a
reused object-array, the same discipline clj-zig's own ADR 39 scalar
hot path uses.

## Decision

The floor is the Clojure-reachable `invokeWithArguments` floor against a
reused object-array, not the Java-direct-invoke ideal. On JDK 26 the
scalar floor measures around 55 to 60 ns, against a `defnz` scalar
median around 190 ns. The floor allocates nothing per call (the carrier
array is reused); its cost is the per-call `invokeWithArguments`
dispatch plus the carrier boxing shared with the `defnz` path.

For the allocating ownership shapes (`:string`, `:owned-return`) the
floor invokes the wrapper's free shim after each call inside the timed
thunk so the loop leaks nothing. This is where the floor stops being a
clean isolate. The free shim takes two longs and returns void;
`invokeWithArguments` rebuilds an `asType`/`asSpreader` adapter per call
on that signature, and that adapter rebuild is markedly more expensive
than on the scalar shim (which takes and returns a single long). On
these two shapes the floor measures higher than the `defnz` median, so
the reported overhead is negative.

The harness reports this honestly rather than cleaning it up. The
`:string` and `:owned-return` entries carry `:body-leak-suspect true`
and a negative `overhead-ns`. Together those signals tell a future
reader exactly what happened: the raw-invoke floor for an allocating
ownership shape is dominated by `invokeWithArguments` adapter cost on
the free shim, not by clj-zig's marshalling. The `:handle` shape (its
free shim takes a single pointer and returns void) measures cleanly
because its shim avoids the expensive 2-arg adapter.

## Consequences

The overhead numbers for the four non-allocating shapes
(`:scalar-passthrough`, `:struct-by-value`, `:enum`, `:slice-arg`) and
for `:handle` isolate clj-zig's per-call wrapping cost above a clean
floor. The two flagged shapes do not, and the flag says so on the face
of the record.

The governing principle is measurement only: nothing is optimized to
make the floor lower. Forcing the two flagged floors lower with a
combined handle that chains main and free without the intermediate
adapter, or with a Clojure-emitted `invokeExact` site, would optimize
the measurement rather than report it.

## Alternatives

Use the Java-direct-invoke ideal as the floor. Rejected: Clojure cannot
reach it. The ideal measures a cost no Clojure caller incurs, so
overhead measured against it would overstate clj-zig's cost by the gap
between Java-direct and `invokeWithArguments`.

A tightly bounded manual loop without Criterium for the allocating
shapes. Rejected: those entries would be statistically incomparable to
the other four, and the body-leak ratio guard loses its meaning when
each shape is measured by a different method.

Clean up the two flagged shapes to force the floor below the `defnz`
median. Rejected: it optimizes the measurement and hides the
adapter-rebuild cost, which is the honest finding.
