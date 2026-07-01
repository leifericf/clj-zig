# ADR 43: Nested struct fields cross by value

Date: 2026-07-01

## Context

A `deftypez` or `defrecordz` field had to be a carrier scalar, an enum,
or a buffer (`:string`, `:bytes`, or a slice). A named non-enum field
was rejected at layout time, so a struct could not compose another
struct: no `Rect` of two `Point`s, no `Sprite` carrying a position and
a velocity. Real records nest, and the natural representation on both
sides is a struct embedded by value.

## Decision

A named field whose type resolves to a scalar-only struct is a nested
field, crossed by value. The inner `extern struct` is embedded in the
outer wire struct (`origin: Point`), which is a C-ABI-correct by-value
embedding: Zig lays the inner struct's fields inline, and the outer
layout's offsets reflect that.

The outer layout's offset walk treats a nested field as the inner type's
size and alignment. The wire `extern struct` and the nice struct both
emit `name: InnerType` for a nested field (no `:target`, no expansion to
`{ptr, len}` words); the wrapper writes a nested field as a direct
assignment, which Zig lowers to a struct copy. The FFM reader slices the
out-segment at the nested field's offset and recurses with the inner
layout, building a sub-map; the marshaller mirrors that for an argument.

A nested field is gated on the inner type being scalar-only: every
field, recursively, is a carrier scalar or a further nested scalar
struct. A nested inner type with a buffer field (an owned string inside
each instance) is rejected with `:clj-zig/unsupported-field`, and a
named field whose type is undeclared is rejected with
`:clj-zig/unknown-field`. The scalar-only gate keeps the by-value
embedding and the free shim single-allocator: no per-field buffer lives
inside a nested value, so an owned outer struct still frees only its
own (outer) buffer fields.

## Consequences

A developer writes `(deftypez Rect [origin Point size Point])` and a
function returning `Rect` comes back as `{:origin {:x ... :y ...}
:size {...}}`. A nested struct argument reads inner fields in the body
(`r.size.x`). The composition is recursive: a `Scene` carrying a `Rect`
carrying a `Point` lays out and round-trips through the same machinery.

A nested field returns as a map even when the inner type is a
`defrecordz`; the record rebuild the top-level return path performs
does not yet recurse into fields. A caller that needs a record literal
calls the inner map factory. Per-field record rebuild is a follow-up if
real use asks for it.

## Alternatives

Flatten a nested struct into individually-named scalar fields
(`origin_x`, `origin_y`). Rejected: it loses the structural grouping on
both sides, forces a name-mangling convention, and makes the field count
and order a hidden coupling between the contract and the body.

Pass a nested struct by pointer rather than by value. Rejected: the
boundary passes top-level structs by value already, and a nested field
is data inside that value, not a separately-owned allocation. By-value
embedding matches the C ABI and needs no lifetime management.

Defer until nested buffer-carrying inners are worked out. Rejected: the
scalar-only case covers the motivating uses (points, rectangles,
vectors, transforms), and the gate keeps the buffer case from
complicating the free shim. Nested buffers remain a follow-up.
