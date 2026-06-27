# ADR 21: Owned and borrowed slice returns copy into Clojure

Date: 2026-06-16

## Context

The boundary contract names `[:owned T]` and `[:borrowed T]` for
returns and says returned native memory must be explicitly owned,
copied, or wrapped, and that the form must define who frees the memory
and whether Clojure copies or wraps it. A returned Zig slice is a
pointer and a length with no stable C-ABI return shape, and its memory
outlives the call, so the boundary needs a concrete protocol for getting
the bytes to Clojure and settling who frees them.

## Decision

`[:owned T]` and `[:borrowed T]` are supported in return position only;
an ownership wrapper in argument position is rejected when the spec is
built, with `:clj-zig/unsupported-ownership`. For now `T` must be a slice
of a carrier scalar, `[:slice S]` or `[:slice :const S]`; any other
wrapped type is rejected with `:clj-zig/unsupported-ownership`.

Both forms copy the slice into an immutable Clojure vector of the element
values at the boundary, never a native-backed wrapper. They differ only
in who frees the native memory.

For `:owned`, the body allocates the slice with `std.heap.c_allocator`,
and the generator provides `const std = @import("std");` and a free shim
`<symbol>__free`. The export wrapper carries two trailing out-parameters,
a `*usize` for the pointer and a `*usize` for the length, writes
`@intFromPtr(result.ptr)` and `result.len`, and returns `void`. clj-zig
reads the pointer and length, copies the elements into the vector, then
calls the free shim.

For `:borrowed`, the body returns a view it still owns, such as a
sub-slice of an argument. The wrapper uses the same two out-parameters,
clj-zig copies the elements, and nothing is freed. The view is read before
the call's confined arena closes, so its lifetime stays within the call.

The lifetime rules stay conservative: Clojure receives an immutable copy,
no native pointer escapes the call, and owned memory is freed the moment
the copy is taken.

## Consequences

A returned slice reads as ordinary Clojure data with no lifetime burden
on the caller, and `:owned` and `:borrowed` differ by exactly one freed
pointer. The costs are real. Every returned slice is copied, so a large
result pays a full copy rather than a borrow. `:owned` standardizes on
`std.heap.c_allocator`, so a body that allocates with a different
allocator leaks or corrupts, and a custom allocator is out of reach until
a later record threads one through. The wrapper carries two synthetic
out-parameters the boundary contract does not name, and an `:owned`
function compiles a second exported symbol for its free shim.

## Alternatives

Wrapping the native memory in a zero-copy Clojure sequence backed by an
FFM segment was rejected for now: tying native lifetime to JVM garbage
collection through a `Cleaner` or a shared `Arena` is intricate and easy
to get wrong, while a copy is sound and matches the conservative lifetime
rules. Handing the caller a free function to call by hand was rejected
because it pushes lifetime onto the caller and invites a leak or a double
free; copy-then-free keeps it safe and invisible. Returning the slice as
an `extern struct { ptr, len }` was rejected in favor of out-parameters,
which reuse the protocol the error-union and struct returns already use
and avoid the ABI fragility of returning an aggregate by value. Asking
the caller to pass a pre-sized buffer, as struct returns do, was rejected
because a slice length is not known before the call.

## Amendment (2026-06-27)

`[:owned T]` and `[:borrowed T]` now also cover a named record or struct
declared through `defrecordz` or `deftypez`, not only a bare slice. The
record's fields may be scalars, enums, or buffer-typed (`:string`,
`[:bytes [:slice :u8]]`, or a slice). A Zig `extern struct` cannot hold a
slice, so the wire form expands each buffer field to a `usize` pointer and
a `usize` length, and the marshalled target per field is a `String`, a
`byte[]`, or a vector.

Ownership is uniform across the record: the whole result is `:owned` or
`:borrowed`. For `:owned`, the generator emits one free shim that frees
every buffer field, reading each field's pointer and length back out of the
wire struct, and clj-zig calls it once in a `finally` after copying. Every
owned buffer field must come from `std.heap.c_allocator`, the same contract
a single owned slice carries.

This extends, and does not reverse, the slice-only decision above. The cost
is that a record with several buffer fields compiles one shim with one free
call per field, and ownership cannot vary per field in this version.
