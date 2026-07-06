# ADR 62: Asynchronous upcalls route rather than run

Date: 2026-07-07

## Context

ADR 10 deferred asynchronous callbacks citing real cost: callback lifetimes
that outlive the registering call, and a runtime that may be parked or torn
down when the callback fires. ADR 38 narrowed ADR 10 to permit synchronous
upcalls (the same-thread, same-call case where the JVM is demonstrably live).
The open-ended async case remained deferred.

But many callback-driven APIs fire from threads the JVM does not own: a
library's worker thread, an OS run loop, a real-time audio callback. These
are fire-and-forget: the native caller does not need a return value, only
notification that an event happened. Reaching these requires handing native
code a function pointer that a thread the JVM has never seen can call.

The question is whether async upcalls can be made safe enough to support.
The danger ADR 10 named is genuine: a callback firing into a runtime that
is not ready for it, or a lifetime that escapes every scope that could
manage it.

## Decision

Asynchronous upcall stubs are a supported primitive, with a governing
principle that makes them safe:

**The native thread never runs the user's fn.**

The native thread that fires an async stub does the minimum: read the
args, enqueue an invocation on a caller-chosen dispatch target, return
void. The user's fn runs on the dispatch target's thread(s), which the
caller controls. This is what makes async safe where it would otherwise
not be: a real-time thread, an OS run loop, a GC-sensitive path all stay
native-side-cheap because none of them execute Clojure.

Two invariants are enforced at stub build time:

- **Void return descriptor.** The native caller cannot consume a value
  it will never synchronously receive. A callback whose return the native
  side needs is synchronous (ADR 38), not async.

- **Global Arena.** A confined arena closes when the registering call
  returns; async fires later, from anywhere. Only the global Arena
  survives every possible fire.

The dispatch target is caller-supplied. `clj-zig.foreign` publishes
`async-upcall-stub`, which builds a routing stub bound to a dispatch map.
The dispatch map names the target (`java.util.concurrent.Executor` or a
Clojure agent), the overflow policy, the queue bound, and an optional
error handler. A bounded queue per stub provides back-pressure with three
overflow policies: drop-oldest, drop-current, and block-timeout. There is
no caller-runs policy: the caller is the native thread, and running on it
is what the governing principle forbids.

A stub registry and a JVM shutdown hook ensure pending work quiesces
before the JVM exits. `release-stub!` marks a stub quiesced after the
caller signals native code to stop firing.

## Consequences

Callback-driven APIs that fire from native threads are reachable without
an embedded JVM and without executing Clojure on a thread the caller does
not control. The cost is the caller's: they supply the dispatch target and
own its lifecycle. The governing principle is the contract: the native
thread reads, copies, enqueues, and returns; it never calls the fn.

This narrows ADR 10 further. The permanent non-goals stay: no embedded
JVM, no arbitrary object marshalling, no async stub with a non-void
return. What is new is the fire-and-forget routing stub: void-only, global
Arena, route not run.

The threading contract is documented in the primitive's docstring, in the
convenience constructors, and in this ADR. The caller's fn runs on the
dispatch target's thread(s), not the native thread.

## Alternatives

Support fully synchronous upcalls only (ADR 38) and leave async to the
caller. Rejected: a caller cannot safely attach a native thread to the JVM
from Clojure; FFM's transparent attach is the mechanism that makes this
possible, and clj-zig is the right place to own the routing, the lifetime,
and the back-pressure rather than pushing that complexity onto every
consumer.

Use core.async channels as the dispatch primitive. Rejected for the
initial implementation: it would add a dependency for a feature that
executor and agent dispatch cover. A channel adapter can land later as an
additive namespace without breaking the core.

Run the fn on the native thread with a try/catch. Rejected: the
governing principle exists because native threads that fire callbacks
(audio threads, real-time threads, GC-critical paths) cannot safely enter
Clojure. The principle is correctness, not a preference.
