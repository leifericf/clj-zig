# ADR 38: Synchronous upcall stubs are a supported primitive

Date: 2026-06-24

## Context

ADR 10 started clj-zig with one-directional interop: Clojure calls Zig,
not Zig calling Clojure. Its rationale was to avoid the genuine cost of
full bidirectional interop -- an embedded JVM, and async callbacks whose
lifetimes outlive the call that registered them and fire into a runtime
that may be parked or torn down.

The foreign-function toolkit (ADR 37) binds prebuilt C libraries, and many
of them are callback-driven: a windowing library invokes an input handler,
a filesystem-change API invokes a stream callback, a sort takes a
comparator. Reaching these requires handing native code a function pointer
that calls back into Clojure -- an upcall.

But not every upcall carries ADR 10's cost. A SYNCHRONOUS upcall is fired
by native code on the calling thread, inside a downcall the JVM is already
in, and returns before that downcall returns. It needs no embedded JVM and
introduces no async-into-a-parked-runtime lifetime: the JVM is demonstrably
live for the whole call, because it is the thing that made the call. The
finalized FFM API exposes exactly this as `Linker.upcallStub`. This bounded
subset is one ADR 10's rationale never argued against; ADR 10 deferred the
hard, open-ended case and did not mean to forbid the easy, closed one.

## Decision

Synchronous upcall stubs are a supported primitive. `clj-zig.foreign`
publishes `upcall-stub`, which adapts a Clojure fn to a native function
pointer (`MemorySegment`) against a `FunctionDescriptor`, deriving the
callback arity from the descriptor so one builder serves every callback
shape.

The primitive takes its backing `Arena` as a parameter and documents the
lifetime rule rather than choosing for the caller: if native code RETAINS
the pointer (a registered callback fired later, from a run loop or a future
event), the arena MUST outlive every possible call -- the process-lifetime
global Arena -- because freeing the stub while native code may still call
through it faults the VM; only a stub used and discarded entirely within
one bounded scope may use a confined arena.

Out of scope, still, and still ADR 10's deferral: an embedded JVM, and
asynchronous callbacks into a JVM that is not already inside the call.

## Consequences

Callback-driven prebuilt libraries are reachable without a separate
embedding effort. The lifetime discipline is the one genuinely dangerous
part, so it lives in the primitive's docstring and this ADR, and the arena
is an explicit argument so a caller chooses the lifetime deliberately
rather than inheriting a hidden default.

This narrows, but does not reverse, ADR 10. clj-zig is no longer strictly
one-directional: it supports the synchronous, same-thread callback. The
open-ended bidirectional case ADR 10 named remains deferred.

## Alternatives

Keep ADR 10 absolute and route every callback need through a Zig
trampoline that polls instead of calling back. Rejected: it does not fit
libraries whose API IS a callback (a window cannot be made to deliver input
by polling a Zig shim), and it would push real complexity onto every
consumer to preserve a rule whose rationale the synchronous case does not
trigger.

Support fully asynchronous upcalls now. Rejected: that is the case ADR 10
deferred for real reasons (lifetimes outliving the registering call, a
runtime that may be parked or gone), and nothing here needs it. The
synchronous subset is the part that is both useful and safe today.
