# ADR 09: The boundary contract does not model Zig internals

Date: 2026-06-16

## Context

The contract could try to describe how Zig computes, or only what
crosses the boundary.

## Decision

The Clojure signature describes only inputs, outputs, ownership, and
lifetime at the boundary.

## Consequences

Zig stays free to use allocators, comptime, pointers, packed structs,
SIMD, C imports, and target-specific optimization internally; the
Clojure side never tries to model these.

## Alternatives

Modeling Zig internals on the Clojure side was the thing to avoid; it
would couple the two languages and weaken Zig's freedom for no gain.
