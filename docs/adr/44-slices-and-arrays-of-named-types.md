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
or array of a buffer-carrying struct in argument position is rejected
with `:clj-zig/unsupported-element`: the argument marshaller writes
extern slots at known offsets and cannot lay out a nice struct's slice
fields there. An `[:owned [:slice Buf]]` return of one is accepted (see
the amendment below); a `[:borrowed [:slice Buf]]` return is rejected
with `:clj-zig/unsupported-borrowed-buffer-slice`. A slice of an enum is
not added here either.

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
ADR 21 were written to remove, on both sides of the boundary, and the
two sides can silently disagree on the layout.

Lift the scalar-only gate to allow per-element buffer fields now.
Implemented as the owned-return amendment below: the free shim walks
every element and frees every buffer before freeing the slab, a distinct
ownership protocol with its own nice-to-wire transform. The scalar-
interior case shipped first; the buffer-carrying case follows.

Copy back mutations into the caller's maps. Rejected: maps are
immutable, so a copy-back would rebuild the whole collection, and the
common case is a read-only const slice. A caller that needs the body's
edits returns an owned slice and reads the result.

## Amendment: owned slices of buffer-carrying structs (Follow-up 4)

Date: 2026-07-02

An `[:owned [:slice Buf]]` return whose element carries buffer fields
(strings, byte slices, or scalar slices) is now accepted. The body
builds nice records (a regular Zig struct with real slice fields the FFM
reader cannot read at known offsets), so the wrapper transforms the
body's `[]Buf` into a wire (extern) slab:

1. The `__impl` fn returns `[]Buf` (nice records, `c_allocator`).
2. The wrapper allocates a `[]Buf__wire` slab, iterates the nice slice,
   and copies each field (scalars and enums direct, each buffer field
   decomposed to its pointer and length).
3. The wrapper frees the nice struct array (the slice of records, not
   the buffers the slice fields point to).
4. The wrapper writes the wire slab's pointer and length to the
   out-params.
5. The walking `__free` shim iterates the wire slab, frees each element's
   buffer fields (reinterpreting each `usize` pointer back to a slice of
   its element type), then frees the slab itself.

The FFM reader reads the wire slab at the C-ABI offsets the layout
descriptor computes, the same path the owned-record return (ADR 21)
uses for a single record. The cost is a transient double allocation
(the nice slab plus the wire slab) during the call; the nice slab is
freed before the call returns, so only the wire slab and its element
buffers live until the free shim runs.

Argument slices and arrays of buffer-carrying structs are now supported
(amendment, 2026-07-02): a `[:slice :const Buf]` or `[:array N Buf]`
argument where `Buf` carries buffer fields crosses as a slab of wire
(extern) structs. The FFM marshaller copies each caller value's buffer
fields into the call arena and writes the `{ptr, len}` pair into the
extern slot. The wrapper allocates a nice-record slab with
`c_allocator`, converts each wire element (scalars direct, each
`{ptr, len}` pair reinterpreted as a real slice), runs the body, and
frees the nice slab in a `defer`. The buffer contents live in the FFM
call arena; the nice slab's slice fields point at them for the call's
duration. A non-const buffer-carrying struct slice is still rejected
(`:mutable-struct-slice`): the caller's immutable maps have no mutable
container for copy-back. A borrowed slice of a buffer-carrying struct
remains rejected for returns (the wrapper-allocated wire slab would
leak with no free shim).
