# ADR 33: Compile out of process, warm the compiler rather than embed it

Date: 2026-06-17

## Context

clj-zig compiles by shelling out to `zig` as a subprocess (the imperative
shell, ADR 16). Spawning a process per compile has latency, and in a
REPL-driven loop that latency is felt on every redefinition. One way to cut
it is to embed the compiler in the JVM process: link the Zig compiler as a
library and call into it, paying no spawn cost. The alternative is to keep
the compiler in its own process and remove the latency by other means.

The cost worth optimizing is real, but so is the blast radius of running a
large C++ compiler inside the JVM that holds the user's REPL session, loaded
native libraries, and application state.

## Decision

clj-zig keeps compilation out of process and pursues speed by warming the
compiler, not by embedding it.

Out of process means a `zig` crash, panic, or out-of-memory is isolated: it
fails one compile and returns a structured diagnostic, and the JVM, the
REPL, and every already-loaded library survive. The subprocess boundary is
a clean data interface, argv and streams in, exit code and stderr out, which
is exactly what the imperative shell switches on. Zig also exposes no stable
embedding API, so embedding would couple clj-zig to compiler internals
through an FFI into a foreign memory model.

The latency is addressed where it actually lives. The levers are a warm,
persistent `zig` process driven incrementally rather than a fresh spawn per
compile, and keeping non-essential work such as a separate `zig fmt` pass
off the hot path. The content-addressed cache (ADR 12) already removes the
compile entirely for an unchanged form, and baked artifacts (ADR 31) remove
it for consumers.

## Consequences

The compiler can never take the JVM down with it; the worst case of a bad
body is a diagnostic, consistent with keep-last-good (ADR 11). The shell
stays simple and testable against a real `zig`. A cold compile pays process
startup, which the warm-process direction and the caches are there to
amortize; the warm process is an optimization the out-of-process design
admits without changing the boundary.

Embedding is not foreclosed by accident but by reasoning: should Zig one day
offer a stable embedding interface, the trade between spawn latency and
crash isolation could be revisited, but the isolation has standing value a
faster path does not erase.

## Alternatives

Embed the Zig compiler in the JVM process. Rejected: Zig offers no stable
embedding API, an in-process compiler crash or OOM would destroy the REPL
session and loaded libraries, and the FFI into a C++ compiler's memory model
is a large, fragile surface for a latency win the caches and a warm process
already deliver.

Spawn a fresh subprocess per compile and accept the latency. This is the
current baseline; the decision keeps its isolation while adding the warm
process so the latency stops being inherent.
