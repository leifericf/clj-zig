# ADR 35: Give Zig a persistent global cache directory

Date: 2026-06-17

## Context

clj-zig compiles each wrapper by shelling out to `zig build-lib` (ADR 33),
passing no `--global-cache-dir`. Zig then falls back to its per-user default
cache, outside the project and shared across every project on the machine.
clj-zig's own content-addressed cache (ADR 12) removes the *whole* compile for
an unchanged form, but says nothing about the work *inside* a compile that does
run: when a wrapper misses, Zig recompiles everything that wrapper's root pulls
in.

That inner cost was negligible while a body imported only itself and a few
sibling files. With external Zig-module dependencies (ADR 34) it is not: a
wrapper that `@import`s a large package would recompile the whole package on
every wrapper miss unless Zig can reuse its prior compilation of it. Zig's
global cache is exactly the mechanism that memoizes those intermediate build
artifacts by content — but only if it points at a stable location that
survives across compiles.

## Decision

clj-zig passes `--global-cache-dir` to every `zig build-lib` invocation,
pointing at a stable project-local directory (under `.clj-zig/`). Zig then
content-addresses its intermediate artifacts there and reuses them across
wrapper recompiles, so a wrapper miss that depends on an unchanged module
recompiles only the small wrapper and relinks the already-built module rather
than rebuilding it from source.

The directory is project-local rather than the per-user default so the cache
travels with the project, is cleared with the project's other `.clj-zig/`
state, and never leaks one project's build artifacts into another's. It is
build-tool state, not a clj-zig artifact: clj-zig's own cache (ADR 12) remains
the authority on which library answers a `defnz`; the Zig global cache only
speeds the compiles that clj-zig decides to run.

## Consequences

The compile-once-link-many sharing that ADR 34 needs becomes real: an edited
wrapper over an unchanged module relinks in a fraction of a from-scratch
compile, observable as an unchanged module-artifact hash rather than as wall
time. Every clj-zig compile benefits, not only module-dependent ones, because
Zig now reuses standard-library and wrapper-preamble compilation across forms.

The cost is a directory that grows with build activity and lives in the
project tree; it is disposable (deleting it only forces recompiles) and shares
the `.clj-zig/` lifecycle the toolchain and cache already own. The cache dir is
not part of any content hash — it is a speed mechanism, and correctness still
rests on clj-zig's content-addressed library names (ADR 12).

## Alternatives

Leave `--global-cache-dir` unset and use Zig's per-user default. Rejected: it
works, but it scatters one project's intermediate artifacts into a shared
per-user location, cannot be cleared with the project, and ties cache behavior
to each developer's environment rather than to the project — and module reuse
(ADR 34) should be a property of the project, not the machine.

A throwaway cache directory per compile. Rejected: it defeats the entire point,
since Zig could never reuse a prior module compilation, leaving every wrapper
miss to rebuild the module from source.
