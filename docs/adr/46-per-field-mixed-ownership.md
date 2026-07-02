# ADR 46: Per-field mixed ownership

Date: 2026-07-02

## Context

Every buffer field in a record was treated uniformly: the free shim
freed them all. A record with one owned buffer beside one borrowed
buffer could not express "free this one, leave that one" (ADR 21
non-goal). Real records mix ownership: a result carries owned payload
beside a borrowed reference to caller-owned data.

## Decision

The ownership marker is the existing `:borrowed` wrapper, already
accepted by the field validator (`classify-field` includes `:borrowed`
in its kind set). A buffer field whose type kind is `:borrowed` is
borrowed; every other buffer field (`:owned`, bare `:slice`, `:bytes`,
`:string`) is owned. The free shim skips `:borrowed` fields and frees
every owned field as before.

No new syntax, no new marker, no layout change. The wire form is
unchanged (every buffer field is still `{ptr, len}`); only the free
discipline differs. The body's contract for a `:borrowed` field is the
same as for a `:borrowed` record return: keep the storage alive past
the read (static storage or a long-lived arena).

A borrowed string is not directly expressible (`:string` is not
wrappable). Model it as `[:borrowed [:slice :u8]]`, read as a byte
vector. If a decoded borrowed string is ever needed, add
`[:borrowed :string]` support then.

The `:borrowed` skip applies in every free context: the owned-record
return shim, the error-union-over-struct shim, the owned-slice walking
shim, the optional-struct shim, and their file-mode equivalents. A
nested buffer-carrying struct's borrowed fields are also skipped (the
recursive free walk checks `:borrowed` at each level).

## Consequences

The `owned-buffer-field?` predicate (true when a field has a `:target`
and is not `:borrowed`) drives every free-shim field selection. When
all buffer fields are borrowed, the free body is a no-op `_ = __ret;`
(owned-record) or just the slab free (owned-slice), and the borrowed
data is left for the caller to manage.

This supersedes the ADR 21 non-goal "per-field ownership is not
supported." The `:borrowed` wrapper, introduced for record returns,
now serves double duty as a per-field marker.

## Alternatives

Introducing a new `:static` or `:unowned` keyword was rejected: the
`:borrowed` wrapper already carries the right semantics and is already
parsed by the validator. Making the free shim always free everything
and relying on the caller to pass copies was rejected: it defeats the
purpose of borrowed fields (avoiding a copy for data the caller owns).
