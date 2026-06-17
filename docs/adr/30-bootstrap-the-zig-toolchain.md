# ADR 30: Bootstrap the Zig toolchain

Date: 2026-06-17

## Context

clj-zig shells out to `zig` to compile generated source. Today the
developer installs Zig and puts it on PATH before anything works, which is
friction against the goal that adding clj-zig to `deps.edn` and writing a
`defnz` just works. Zig is a single, dependency-free binary, so providing
it automatically is clean in a way LLVM or gcc would not be. The
`zig-version` string is part of the content hash, so the compiler version
must be consistent for a cached artifact to be reused across machines.

## Decision

clj-zig resolves the `zig` executable through one seam, in order: an
explicit override (environment variable or system property), then a `zig`
on PATH, then a pinned hermetic Zig fetched once into
`.clj-zig/zig/<version>/` and cached. The pinned version is 0.16.0,
matching what the generated wrappers assume. The fetch is opt-out.

Preferring PATH means a developer who already has Zig sees no download.
The pinned fallback means a developer with no Zig still has `defnz`
compile on first use, after a single logged download. An explicit override
forces a chosen compiler when reproducibility or a specific build matters.

## Consequences

An author adds clj-zig and writes a `defnz` with no manual toolchain step.
On a machine with a system Zig nothing downloads; on a bare machine the
first compile fetches the toolchain once and reuses it thereafter. When
the bootstrap supplies the compiler, the `zig-version` component of the
content hash is identical across machines, so cache entries and baked
artifacts line up.

A system Zig of a different version than the wrappers assume may produce a
different hash or a compile error; the pinned fallback and the override
exist for exactly that. The download is a network touch on first use only;
a machine that is offline and has no Zig cannot bootstrap and gets a clear
message naming the override.

## Alternatives

Require a manual install. Rejected: it is the friction this removes.

Vendor Zig binaries in the repository. Rejected: every host platform's
toolchain is tens of megabytes, the set goes stale, and it bloats every
checkout for a tool most contributors already have.

Always download the pinned Zig and ignore PATH. Rejected: it surprises
developers who already have Zig and wastes a download. Reproducibility is
better served by the explicit override, used only when it matters, than by
overriding everyone's existing toolchain.
