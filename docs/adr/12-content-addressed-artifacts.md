# ADR 12: Generated artifacts are content-addressed

Date: 2026-06-16

## Context

Recompiling a `defnz` produces a new shared library, and the JVM does
not unload native libraries cleanly.

## Decision

Hash the normalized spec, source, options, target, and Zig version into
the artifact path.

## Consequences

Fresh artifact names make redefinition reliable and cacheable: an
unchanged form reuses its cached library, a changed form gets a new
one, and stale libraries are never reloaded.

## Alternatives

Reusing a fixed library name and reloading it was considered; it runs
into the JVM's awkward native-unloading semantics.
