# ADR 17: Explicit boundary contracts over data marshalling

Date: 2026-06-16

## Context

Rich Clojure structures (maps, sets, nested collections) could cross
the boundary by serialization or a shared protocol, or the boundary
could stay narrow and explicit.

## Decision

Cross the boundary through explicit, per-function boundary contracts
rather than serializing whole Clojure structures (EDN, JSON, or a
custom binary format) or defining a general interop protocol that both
runtimes negotiate.

## Consequences

The seam stays small, inspectable, and composable. A serialization
format or richer protocol remains an open option for data that
genuinely needs it, deferred until a concrete need appears (docs/06
deferred directions).

## Alternatives

Serialization (EDN, JSON, or a custom binary format) and a negotiated
interop protocol were both considered. Each handles rich nested data
well, but both push arbitrary Clojure data across the seam,
reintroducing the marshalling cost and ambiguity Zigar exists to avoid.
