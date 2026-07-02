# ADR 45: Optional over a named struct

Date: 2026-07-02

## Context

The `:optional` wrapper lowered a pointer or a carrier scalar as a
nullable pointer (`?*const T`): nil crossed as NULL, a present value as
a one-element cell. A named struct could not appear under `:optional`,
so `[:optional Point]` was rejected. Real APIs return nil-or-struct:
"find a record, or report none."

## Decision

`[:optional StructType]` lowers to `?*const StructType`, the same
nullable-pointer shape `:optional` already uses for scalars and
pointers. Uniformity wins: nil is always NULL, a present value is always
a pointer, the Clojure-side value is always nil-or-a-value.

An argument: nil crosses as NULL; a present value is written into a
call-arena cell (struct-sized) and its address passed. The callee
dereferences `?*const T`. No free (the call arena owns the cell). For a
buffer-carrying struct the arena also owns the buffer copies the
marshaller threads into the cell; the wire-to-nice reconstruction (added
for single struct arguments) converts the wire pointer to a nice value
before the body uses it.

A return: the body returns NULL or a `c_allocator` pointer to the nice
struct (and its buffer fields). The FFM reads the struct through the
pointer, copies it to a Clojure map or record, and frees in a finally:
buffer fields first (the per-field free shim, recursing into nested
buffer-carrying structs), then the struct allocation itself via
`c_allocator.destroy`. A scalar-only struct runs a no-op buffer walk
then destroys the one allocation.

The heap allocation on every present return is acceptable: `:optional`
returns are not the scalar hot path (ADR 39), and the owned-record and
owned-slice returns already heap-allocate per call.

A `:manyptr` under `:optional` is accepted in argument position (it
reuses the existing optional-pointer lowering) but rejected in return
position: `deref-optional` has no length to read through a many-item
pointer. A `:ptr` and a carrier scalar are accepted in both positions.

## Consequences

The `:optional` lowering now has a dedicated ADR covering scalars,
pointers, and structs. The free shim for an optional-struct return is a
new shape: it takes the raw address (`usize`), null-checks, frees buffer
fields on the nice struct directly (not through `{ptr, len}` words as
the owned-record shim does), then destroys the allocation.

A buffer-carrying struct under `:optional` as an argument relies on the
wire-to-nice reconstruction to convert the wire pointer to a nice value
before the body dereferences it. This avoids the layout-coincidence
risk (a nice struct and a wire struct happen to have the same binary
layout on 64-bit targets, but Zig can reorder regular struct fields).

## Alternatives

Lowering `[:optional StructType]` to a union type or an error-union was
rejected: the nullable pointer is the simplest ABI shape and reuses the
existing `:optional` infrastructure. Returning the struct by value
through an out-pointer (like the struct-return path) with a separate
"present" flag was rejected: it doubles the out-params and complicates
the FFM dispatch.
