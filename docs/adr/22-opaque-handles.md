# ADR 22: Handles are opaque tagged pointers the caller frees

Date: 2026-06-16

## Context

The boundary contract names `[:handle T]` for a native resource, with
`[:handle Parser]` as the example, and the proof of concept lists native
resource handles as a goal. A handle is a native object, a parser or a
connection, that lives across several calls and whose layout Clojure has
no reason to know. Such a resource must reach Clojure as a pointer the
caller can hold between calls and pass back, and the boundary must carry
it across without copying the resource or exposing its memory.

## Decision

`[:handle T]` is an opaque pointer to a native resource. `T` is a symbol
naming the Zig type, usually a `struct` the namespace declares with
`defz`; its layout is never modeled on the Clojure side. A handle crosses
the C ABI as `*T`, a pointer, and is allowed in both argument and return
position.

Clojure represents a handle as a tagged value carrying `T`'s name and the
native pointer. The caller never dereferences or inspects it; it holds
the handle and passes it back to functions that take `[:handle T]`.
A call rejects a handle whose tag is not `T` with
`:zigar/handle-type-mismatch`, and the spec builder rejects `[:handle T]`
over a `T` that is not a named-type symbol with `:zigar/unsupported-handle`.
`T` need not be a `deftypez`; the resource is opaque, so no layout is
registered.

Lifetime belongs to the caller. Handles are not freed automatically. A
constructor returning `[:handle T]` allocates the resource, typically with
`std.heap.c_allocator.create`, and the caller frees it by calling a
destroy function that takes the handle. The proof of concept relies on
explicit caller cleanup rather than a finalizer or a garbage-collection
hook, so a handle never freed leaks, the same as in Zig.

## Consequences

A native resource lives across calls as ordinary Clojure data the caller
threads through, and the wrong handle type is caught before the call. The
costs are the ones the project accepts elsewhere for explicitness. The
caller must call the destroy function, and a dropped handle leaks. Zigar
checks the tag but trusts the pointer, so a handle used after its resource
is freed is a use-after-free the boundary cannot catch. The resource's Zig type
lives in a `defz` declaration the boundary contract does not otherwise
name.

## Alternatives

Freeing handles automatically through a JVM `Cleaner` or finalizer was
rejected. It ties native lifetime to garbage-collection timing, which is
nondeterministic, and hides the ownership the project keeps explicit.
Handing the caller a bare `MemorySegment` or a raw address was rejected
because it exposes the FFM type, invites dereferencing, and carries no
type tag for the mismatch check. Requiring `T` to be a `deftypez` with a
known layout was rejected because a handle is opaque by design and the
resource may be an internal Zig type Clojure should never model. Copying
the resource into Clojure data, as owned slices do, was rejected because a
handle exists precisely to keep one live native resource across calls.
