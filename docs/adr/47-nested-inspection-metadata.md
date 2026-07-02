# ADR 47: Nest inspection metadata under domain keys

Date: 2026-07-02

## Context

The `:clj-zig/info` metadata map is flat. A Var carries spec, body,
generated source, library path, symbol, status, modules, source-mode,
source-file, entry, options-extra, and aux-files as sibling keys. As the
inspection surface grows (streaming shims, per-arity descriptors,
spec-registration state, comptime specialization), the flat map becomes
hard to read and hard to extend without collisions.

## Decision

The inspection metadata restructures into four nested keys:

- `:contract` holds the boundary contract: `:spec`, `:signature`,
  `:symbol`.
- `:source` holds the source provenance: `:body`, `:generated-source`,
  `:mode` (inline, file, raw), `:file` (the `.zig` path), `:entry`,
  `:options-extra`, `:aux-files`.
- `:build` holds the build identity: `:library`, `:status`, `:modules`,
  `:zig-version`, `:target`.
- `:lifecycle` holds the runtime state: `:arities` (a map from arg-count
  to its own `{:contract :source :build :lifecycle}` sub-map for a
  multi-arity function), `:failed-attempt` (per arity for multi-arity).

A backward-compat shim in `clj-zig.inspect` reads both the nested and the
flat key for one release, so external consumers that reach into
`:clj-zig/info` directly keep working while they migrate. The public
`clj-zig.inspect` API (the `zig/source`, `zig/spec`, etc. helpers) is the
stable surface; consumers should use those rather than reaching into the
metadata map.

## Consequences

The metadata map is deeper but self-documenting. A new inspection concern
adds a key under its domain, not another top-level sibling. The
multi-arity feature (ADR 51) uses `:lifecycle :arities` to carry one
sub-map per arity, so `recompile!` and `explain` can target a single
arity. The compat shim costs a double-read per accessor for one release,
then is removed.

## Alternatives

Keeping the flat map and prefixing new keys was rejected: the prefixes
reconstruct the nesting the structure should express, and a flat map of
fifteen keys is already at the edge of readability. A separate metadata
slot per concern (separate `:clj-zig/contract`, `:clj-zig/source`, etc.
keys on the Var meta) was rejected: it scatters the inspection data across
the metadata map and makes "get everything about this fn" a enumeration
instead of one read.
