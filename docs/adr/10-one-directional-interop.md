# ADR 10: Start with one-directional interop

Date: 2026-06-16

## Context

Interop could be one-directional (Clojure calls Zig) or bidirectional
(Zig also calls Clojure).

## Decision

The proof of concept supports Clojure calling Zig, not Zig calling
Clojure.

## Consequences

One direction is enough to prove the user experience and avoids
callbacks, embedded JVMs, and cross-runtime lifecycle issues.
Bidirectional interop is recorded as a deferred direction (docs/06).

## Alternatives

Bidirectional interop was considered and is genuinely interesting, but
it multiplies complexity (an embedded JVM, callback lifetimes) before
the basic UX is proven.
