# ADR 14: Records require explicit bridge definitions

Date: 2026-06-16

## Context

Accepting or returning a record across the boundary needs a known
memory layout.

## Decision

Clojure records participate through `defrecordz`, not automatic
conversion of arbitrary records.

## Consequences

A bridged record gives Zig a known field order, field types, alignment,
layout, and ownership rules; an arbitrary Clojure record carries none
of these.

## Alternatives

Automatic record-to-struct conversion was considered; without a
declared layout it cannot produce a sound Zig struct.
