# ADR 40: Namespaces divide by domain

Date: 2026-07-01

## Context

ADR 16 established functional core and imperative shell as the
project's load-bearing structure. An earlier reading split that
discipline at the namespace level: each domain had a pure namespace
and a shell namespace (for example `cache` held the pure content
address math and `cachestore` held the filesystem fingerprinting and
artifact resolution). The split fragmented a single concept across two
namespaces, forced callers to require both, and created naming friction
that led to the `cachestore` split in the first place.

## Decision

Namespaces divide by domain, not by the pure and effectful split. A
namespace is named for the single concept it owns (`cache`, `compiler`,
`source`) and holds both its pure functions and its effectful ones. The
functional core and imperative shell discipline is function-level, not
namespace-level: a pure function never reaches up into an effectful
one, but the two live side by side behind section comments in the same
file.

## Consequences

Three namespaces dissolved back into their domains: `cachestore` into
`cache`, `fileref` into `source`, and `toolchain` renamed to `compiler`
with `zig-version` folded in. The project went from 20 source
namespaces to 17. Each namespace is a single concept a caller can
require once; the pure and effectful functions within it are organized
under section comments (`--- Pure:` and `--- Effectful:`). ADR 16
stands: the discipline is still load-bearing, it just operates at the
function level now.

## Alternatives

Keep splitting each domain into a pure namespace and a shell namespace.
The naming friction and double-require cost are the cost; the benefit
(namespace-level purity) is not worth it when the function-level
discipline is already enforced by code review and the skill system.
