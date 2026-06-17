# ADR 31: Distribute precompiled artifacts, bake the cache

Date: 2026-06-17

## Context

A `defnz` compiles its Zig to a content-addressed native library and caches
it (ADR 12). On the author's machine that compile is part of the REPL loop.
But a consumer who adds a library built with clj-zig should not need Zig at
all: adding a dependency and calling a function must just work, with no
toolchain, no download, and no build step.

The cache already keys an artifact by the content hash of its spec, body,
source, options, Zig version, and target, and stores the library under
`.clj-zig/cache/<target>/<ns>/<name>-<hash>/`. That layout is reproducible
and platform-keyed. What it lacks for distribution is presence: the
consumer's machine has no such cache, and the consumer has no compiler to
fill it.

## Decision

clj-zig distributes precompiled artifacts. At release the author
cross-compiles each `defnz` for the target matrix and ships the resulting
libraries inside the library's own jar, laid out under a resource root that
mirrors the cache: `<target>/<ns>/<name>-<hash>/lib...`. The cache layout is
the distribution format.

The loader gains one additive step before compiling: resolve a bundled
library as a classpath resource keyed by the same target, namespace, name,
and hash. On a hit, extract it into the filesystem cache and load it,
invoking Zig never. On a miss, fall back to the existing host compile. The
content hash is the contract: a bundled library matches a function's hash
for a platform or it does not, so a stale or mismatched artifact is a clean
miss rather than a wrong load.

A function that links a third-party C library beyond libc and libm is baked
for the host only, with a clear log of which targets were skipped; pure-Zig,
libc, and libm functions cross-compile for the whole matrix.

## Consequences

A consumer adds a coordinate, sets the native-access flag, requires the
namespace, and calls native functions, with no Zig and no compile. The
author's release step is a bake that fills the resource tree, then an
ordinary jar and deploy.

The classpath branch is consulted only when a matching resource exists, so
existing host-compile development is unchanged and the REPL loop is intact.
Because the hash already encodes the target, a consumer on a platform the
author did not bake gets a clean miss; with no compiler that miss is a clear
error rather than a guess.

Baking every function for every target multiplies build time and jar size at
release. A single fat jar carrying all targets is the chosen trade for
"add one coordinate, no classifier"; if real sizes demand it, classifier
artifacts remain a later option. A third-party-C function narrows the matrix
to the host, which the bake reports rather than silently dropping.

## Alternatives

The consumer compiles from shipped source. Rejected: it breaks add-a-dep,
needs Zig downstream, and pushes compile latency onto every consumer.

Download artifacts at install or first run. Rejected: it adds a network
dependency and a supply-chain surface to every consumer for something the
author can bake once and ship in the jar the consumer already pulls.

Ship per-platform classifier jars and select one at resolution. Rejected
for now: it pushes target selection onto the consumer's `deps.edn` and
complicates the add-a-dep story. The fat jar keeps the consumer's step to a
single coordinate; classifiers stay available if sizes force the issue.
