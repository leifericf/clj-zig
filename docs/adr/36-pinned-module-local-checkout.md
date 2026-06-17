# ADR 36: A pinned module reference may carry a local checkout

Date: 2026-06-17

## Context

ADR 34 gives an external Zig module two reference shapes: a dev `:path`
to a local root, and a pinned `{:git/sha :root}` whose fingerprint is
derived from the sha and root alone, with no filesystem read. The pinned
shape exists so a dependent function's content hash is reproducible
without the module's source — exactly what lets a *consumer* of a baked
library recompute the same hash and resolve the bundled artifact with no
Zig toolchain and no module checkout.

But a pinned reference could not be compiled: `module-root` threw
`:clj-zig/module-not-checked-out`, since `{:git/sha :root}` names no local
source. So baking a module-dependent function — which must compile it for
every target — was possible only with a dev `:path` reference, whose
fingerprint reads the local tree and is therefore *not* reproducible by a
consumer. The two requirements pulled apart: a reproducible hash needed
the pinned shape, and compilation needed a local path.

## Decision

A pinned reference may carry an optional local `:path`:
`{:git/sha … :root … :path …}`. Identity and source split cleanly:

- The **fingerprint** is the pinned identity (sha and root), with no
  filesystem read, whether or not a `:path` is present. A consumer that
  declares the same `{:git/sha :root}` reproduces the hash.
- The **compile source** is the `:path` when present. Bake and the dev
  loop compile a pinned module from its local checkout.

A pinned reference *without* a `:path` has no local source in the current
environment. `module-roots` omits it rather than throwing; it resolves a
bundled or already-built library, and `:clj-zig/module-not-checked-out` is
raised only if a fresh compile is actually required — never when a baked
artifact resolves.

## Consequences

A library publisher declares a pinned module with a local `:path`
supplied out of band (an environment override, a sibling checkout), bakes
the reproducible per-target artifacts, and ships them. A consumer
declares the same pinned reference with no `:path`, reproduces the hash,
and loads the bundled library with no Zig and no module source. The dev
loop is unchanged: a bare `:path` reference still fingerprints its tree
and recompiles on edit. Provenance reporting reads the compile source, so
a pinned-with-path build reports `:local` (it compiled from a checkout)
and a pinned-without-path build reports `:pinned`.

## Alternatives

A separate bake-time map of module name to local root, decoupled from the
descriptor, was rejected: it splits one module's identity and source
across two declarations a reader must reconcile, where one descriptor
carries both. Teaching bake to fetch a `:git/sha` into a checkout was
rejected as out of scope — clj-zig drives a local toolchain and does not
manage source mirrors; supplying the checkout out of band is simpler and
keeps fetching the caller's concern.
