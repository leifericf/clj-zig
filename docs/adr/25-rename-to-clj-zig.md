# ADR 25: Rename the project to clj-zig

Date: 2026-06-16

Supersedes ADR 01.

## Context

ADR 01 named the experiment Zigar before any code existed. Two
established projects already hold that name: `chung-leong/zigar`, a
JavaScript-to-Zig toolkit, and `GuneshRaj/zigar`, a Zig web framework.
A Clojure library sharing their name would collide in search, in
package coordinates, and in conversation, and the JavaScript toolkit
occupies the same Zig-interop niche, so the clash is substantive rather
than incidental.

The project is not published anywhere yet: no Clojars release, no
external dependants. Renaming now costs only an internal sweep; renaming
after publication would break coordinates other people depend on.

`clj-zig` is free on Clojars and on GitHub, and it states what the
library is without a pun: Clojure functions backed by compiled Zig.

## Decision

Rename the project from Zigar to `clj-zig`. The namespace root `zigar`
becomes `clj-zig` (`clj-zig.core`, `clj-zig.signature`, and the rest);
the main namespace is `clj-zig`, aliased `zig`. Error-code keywords
`:zigar/...` become `:clj-zig/...`. The on-disk cache directory
`.zigar/cache` becomes `.clj-zig/cache`.

The generated C symbol prefix `zigar_` becomes `clj_zig_`, with an
underscore, because a Zig identifier cannot contain a hyphen.

What does not change: `:zig/...` diagnostic codes that name the Zig
compiler, the `zig` require alias, and the word "Zig" wherever it names
the language. No backwards-compatibility shims are kept, since nothing
public depends on the old names.

## Consequences

Repository, package-coordinate, and badge URLs move to `leifericf/clj-zig`
and `io.github.leifericf/clj-zig`. Renaming the GitHub repository is a
manual step taken outside the codebase; GitHub redirects the old URLs.

The Clojure namespace `clj-zig` carries a hyphen, so its munged forms
diverge from the source word in two places that bare prose does not show:
the on-disk path is `clj_zig` (Clojure munges the hyphen to an
underscore), and the symbol fragment for the namespace inside a generated
C symbol is `clj_2d_zig` (the symbol munger escapes the hyphen as its hex
code). Both are deterministic, so the content-addressed cache key stays
stable.

The Zigar name jokes recorded in ADR 01 retire with the name; `clj-zig`
is plainly descriptive and carries no pun.

## Alternatives

Keep Zigar. Rejected: the two existing projects make the name ambiguous,
and the cost of changing it only rises after publication.

`cljzig`, with no separator. Rejected: it reads as one opaque token and
loses the immediate `clj` plus `zig` decomposition that makes `clj-zig`
self-describing.

A generic `zig.*` namespace root. Rejected: it would collide with the
`zig` alias the public API already uses and would claim a namespace broad
enough to clash with any other Clojure-Zig effort.
