# ADR 39: A scalar-only hot-path call mode for defnz

Date: 2026-06-24

## Context

A bound `defnz` fn is a general boundary crosser: its body in `ffm/bind`
opens a confined `Arena` for every call, marshals each argument through
`marshal-arg` (building a per-arg carrier/copy-back map), concatenates the
carriers, and dispatches on the return kind. That machinery exists because
a slice, pointer, array, or struct argument must be COPIED into native
memory the arena owns for exactly the duration of the call, and an
error-union, owned, or struct return must be written through a
caller-allocated out-pointer.

But many `defnz` functions cross only scalars: a numeric kernel, a DSP
step, a per-frame or per-sample call in a tight loop. For those, nothing
is copied into the arena and nothing is written through an out-pointer --
yet every call still paid for `Arena/ofConfined` (allocate a confined
session, then close it) and the per-arg marshalling bookkeeping. On a hot
loop that per-call arena is pure overhead: dead weight a real-time caller
pays thousands of times a second for memory it never uses.

## Decision

`bind` detects a scalar-only signature -- every parameter and the return a
plain scalar (`{:kind :scalar}`, which includes `:void`) -- and returns a
fast-path invoker for it. The fast path opens NO arena and does no
marshalling-map bookkeeping: it coerces each argument straight to its
carrier with `to-carrier` into a thread-local carrier array, invokes the
cached downcall handle, and coerces the return with `from-return`.

The carrier array is reused per thread (`ThreadLocal`), so concurrent
callers never share one and a steady caller does not reallocate it each
call. Reuse is sound because the native call does not retain the array and
one-directional interop (ADR 10) means a call cannot re-enter itself on the
same thread mid-call.

Any signature with a slice, pointer, array, struct, enum, handle,
optional, or an error-union/owned/struct return keeps the general
arena-backed path unchanged.

## Consequences

A scalar `defnz` called in a loop no longer allocates and closes a confined
arena per call, nor builds the per-arg marshalling maps -- the dominant
per-call costs for that shape. The fast path reuses the existing
`to-carrier`/`from-return` coercion, so its results are identical to the
general path by construction; the leak lane and the scalar round-trip
suites cover it as before, plus a hot-path arm that drives a scalar call in
volume.

The remaining per-call cost is the boxing of scalar carriers and the
`& args` sequence, both inherent to invoking a `MethodHandle` through
`invokeWithArguments` from Clojure; eliminating them would need
arity-specialized invokers, which is out of scope here. A caller that needs
a strictly allocation-free per-frame call into a prebuilt library should
use `clj-zig.foreign` and invoke the cached handle directly with typed
arguments (ADR 37).

## Alternatives

Always open the arena. Rejected: it is measurable, avoidable overhead on
exactly the calls (tight scalar loops) where overhead matters most, and the
scalar case is trivially and safely separable.

Arity-specialized invokers for a truly zero-allocation scalar path.
Rejected for now: it is a much larger change (generating a fn per arity to
avoid the `& args` seq and the carrier boxing) for a marginal gain over
dropping the arena, and `clj-zig.foreign` already serves the caller who
needs the last allocation gone.
