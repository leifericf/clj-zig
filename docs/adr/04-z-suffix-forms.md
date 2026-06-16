# ADR 04: Zig-aware defining forms use a z suffix

Date: 2026-06-16

## Context

The family of Zig-aware defining forms needs a naming convention.

## Decision

Prefer `defnz`, `defrecordz`, `deftypez`, and `defenumz` over `defzig`,
`defzrecord`, or `defztype`.

## Consequences

The root Clojure concept stays recognizable (`defn`, `defrecord`), and
the trailing `z` marks Zig participation consistently across the family.

## Alternatives

A `defz`-prefix scheme (`defzrecord`, `defztype`) was considered; it
buries the familiar Clojure form behind the Zig marker, making the
concept harder to recognize at a glance.
