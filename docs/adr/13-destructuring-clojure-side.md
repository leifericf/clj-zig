# ADR 13: Destructuring happens on the Clojure side

Date: 2026-06-16

## Context

Clojure callers may want to pass map-shaped values, while Zig needs
native scalars.

## Decision

Clojure destructuring may be used in signatures, but it lowers values
to native arguments before crossing the boundary.

## Consequences

Callers keep Clojure ergonomics (map-shaped inputs) without forcing Zig
to understand arbitrary Clojure data; the field-map is a Clojure-side
input shape, not a Zig boundary type (docs/03).

## Alternatives

Passing whole maps into Zig was considered; it would push arbitrary
Clojure data across the seam, the marshalling the design avoids
(ADR 17).
