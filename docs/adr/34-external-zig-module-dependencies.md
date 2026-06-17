# ADR 34: Depend on external Zig modules by name

Date: 2026-06-17

## Context

ADR 29 lets a file-mode body `@import` sibling `.zig` files by copying the
relative-import closure into the build, and it explicitly deferred named-module
dependencies as a separate capability: module flags address `@import("mylib")`,
not the bare `@import("util.zig")` between siblings. That capability is now
needed. A wrapper body wants to `@import("clojo")` and call into Clojo, a large
external multi-file Zig package in its own repository, with clj-zig's rich
typed boundary rather than a hand-rolled C ABI.

The closure mechanism of ADR 29 is the wrong tool here. Treating an external
package as a sibling closure (`:aux-files`) would, on every redefine, read and
hash every file of the package before the cache is even consulted — turning an
instant cache *hit* into seconds; on every wrapper *miss* it would recompile
the whole package from source; and `bake!` (ADR 31) would embed a full copy of
the package per function per target. The package must instead be a
separately-fingerprinted, separately-compiled, shared input — declared once,
hashed cheaply, compiled once and linked into many wrappers.

## Decision

A namespace declares external Zig modules through `zig-deps` (ADR 27's
namespace-level mechanism), under a `:zig/modules` map keyed by the name the
body imports:

    (zig-deps {:zig/modules {"clojo" {:path "../clojo/src/root.zig"}}})   ; dev
    (zig-deps {:zig/modules {"clojo" {:git/sha "…" :root "src/root.zig"}}}) ; pinned

The compile shell passes each module to `zig` as `-M name=<root>` (ADR 33's
argv-and-streams boundary), so `@import("clojo")` resolves to the package root.
The module must build under the pinned Zig (ADR 30; `0.16.0`, which matches
Clojo); a mismatch is a declaration-time error.

A module enters the content hash (ADR 12) as a single twelve-character
*module fingerprint* — a content hash of the module's file closure — never the
package's whole tree inlined. The fingerprint is memoized behind a cheap
recursive mtime-and-size directory signature: an untouched redefine reuses the
memoized fingerprint, so the cache key is unchanged and the hit stays
instant; editing the package flips the signature, the fingerprint changes, and
dependent wrappers relink. Fingerprint and signature computation are pure core
over a shell-gathered file list (ADR 16).

The module is compiled once and shared: Zig memoizes it in a persistent global
cache (ADR 35) across wrapper recompiles, so a wrapper miss recompiles only the
small wrapper and relinks, not the whole package. `bake!` forwards the modules
per target so each baked library statically includes the package's compiled
code; the consumer needs no Zig and no module path.

## Consequences

A wrapper imports a large external package by name and calls it across the
typed boundary, and the REPL loop stays fast: an unchanged module-dependent
`defnz` is a cache hit at today's latency, a wrapper-body edit recompiles only
the wrapper, an edited package is picked up with no stale binding, and a broken
package edit keeps the last good wrapper bound with a module-attributed
diagnostic (ADR 11). The fingerprint is one hash input, so two wrappers over
the same module share a fingerprint and the cache stays content-addressed.

The cost is new surface: a module-fingerprint computation with a memoization
cache, module flags threaded through compile and bake, and a declaration shape
to validate. The directory signature is a heuristic for "did the tree change";
it keys on mtime and size, so a content-preserving touch recomputes a
fingerprint that comes out identical — correct, just not free that once.

## Alternatives

Treat the package as a sibling-import closure (`:aux-files`, ADR 29). Rejected:
it would hash and recompile the entire package on every redefine and bake a
copy per function per target, destroying the REPL loop and the artifact size
the cache exists to protect. The closure model is right for a vendored handful
of files, wrong for a large shared package.

A thin C ABI between clj-zig and the package instead of a Zig-module import.
Rejected: it flattens the rich typed boundary clj-zig is built to provide
(ADR 17) and forces a second hand-marshalled surface that drifts from the
package's real signatures; importing the package as a Zig module keeps the
full type vocabulary.

Per-function module declarations rather than namespace-level. Rejected: a
module is a shared dependency of a namespace's wrappers, so declaring it once
in `zig-deps` matches its scope; repeating it per function would fight the
compile-once-link-many sharing this ADR is built around. This is the opposite
trade from ADR 27's per-function link flags, and deliberately so: a link flag
is small and function-specific, a module is large and namespace-wide.
