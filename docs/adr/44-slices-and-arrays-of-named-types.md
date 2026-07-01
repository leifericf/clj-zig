# ADR 44: Slices and arrays of named types cross in bulk

Date: 2026-07-01

## Context

A `:slice`, `:array`, `:ptr`, or `:manyptr` had to hold a scalar
element. A parser could not return `[]Token`, a renderer `[]Vertex`, a
simulator `[]Entity`: the natural return of almost every native API was
out of reach, forced instead into a single owned `[]u8` the caller
unpacked by hand. The boundary already returned one struct by value and
nested structs inside one (ADR 43); the bulk case was the remaining
gap.

## Decision

A `:slice` or `:array` may hold a named struct element whose layout is
scalar-only (the same gate ADR 43 uses for a nested field, exposed as
`layout/scalar-only-layout?`). The element crosses by value in bulk:
the slice's wire form is still `{ptr, len}`, and the marshaller walks
the slice at the element's stride, reading or writing one struct per
element.

- An argument `[:slice :const Point]` accepts a Clojure collection of
  maps; the marshaller allocates `n*stride` bytes and writes each map
  via the recursive struct writer. There is no copy-back: the caller
  supplied immutable maps, so a mutable struct slice does not propagate
  in-place edits back to Clojure. The const slice is the natural shape.
- An `[:array N Point]` argument is the fixed-length form, length-
  checked against the declared `N`.
- An `[:owned [:slice Point]]` return is copied out as a vector of maps
  and the one slab allocation is freed through the existing owned-slice
  shim; `[:borrowed [:slice Point]]` copies without freeing.

A pointer (`:ptr`, `:manyptr`) must still hold a scalar element. A slice
or array of a buffer-carrying struct (each instance owning its own
string or byte buffer) is rejected with `:clj-zig/unsupported-element`;
the per-element buffer free is a separate protocol deferred to a follow-
up. A slice of an enum is not added here either.

## Consequences

A developer returns `[]Vertex`, `[]Token`, `[]Entity` directly as
vectors of maps, and accepts const slices of them as arguments. The
scalar-only gate keeps the bulk path single-allocator: the slab is one
`c_allocator` allocation the free shim releases, with no per-element
buffers to walk. Nested scalar structs compose: a slice of a `Rect`
whose fields are `Point`s round-trips through the same element reader.

The cost is that a struct-element slice must be declared `:const`. A
scalar slice propagates the body's in-place edits back to the caller's
mutable primitive array; a struct slice cannot, because the caller
supplies immutable maps. Rather than silently drop the edits, the spec
rejects a non-const struct-element slice with
`:clj-zig/mutable-struct-slice`. (An `:array` of structs is unaffected:
arrays never copy back, for scalar elements either, so the behavior is
uniform.) A slice element may not carry a buffer field. Both are
deliberate scope limits; the motivating cases (vertices, tokens,
entities, particles) fit the scalar interior.

## Alternatives

Pack a collection of structs into one owned `[]u8` and unpack on the
Clojure side. Rejected: it duplicates the byte-framing tax ADR 17 and
doc 10 were written to remove, on both sides of the boundary, and the
two sides can silently disagree on the layout.

Lift the scalar-only gate to allow per-element buffer fields now.
Rejected: the free shim would have to walk every element and free every
buffer before freeing the slab, a distinct ownership protocol that
deserves its own design. The scalar-interior case covers the motivating
uses and ships first.

Copy back mutations into the caller's maps. Rejected: maps are
immutable, so a copy-back would rebuild the whole collection, and the
common case is a read-only const slice. A caller that needs the body's
edits returns an owned slice and reads the result.
