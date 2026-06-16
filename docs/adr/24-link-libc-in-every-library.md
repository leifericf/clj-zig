# ADR 24: Every compiled library links libc

Date: 2026-06-16

## Context

Owned slice returns (ADR 21) and opaque handles (ADR 22) back their
native memory with `std.heap.c_allocator`. That allocator is libc
`malloc`/`free`, chosen because its `free` is the one deallocation safe
to call across the FFM boundary without threading a Zig allocator vtable
through the call: the generated free shim and the caller's free function
both reach the same process-wide libc heap.

`zig build-lib` does not link libc unless asked. On macOS every binary
links libSystem, which provides libc, so `c_allocator` resolves with no
flag. On Linux it does not: a library that calls `c_allocator` without
`-lc` fails to compile. The suite was developed on macOS, so the gap
stayed invisible until the Linux CI runner compiled `owned-double` and a
handle fixture and the build failed.

User bodies are freeform Zig spliced into the wrapper. A body may reach
for libc anywhere, not only through the allocator, so the set of
libc-needing programs cannot be read off the signature.

## Decision

The compile shell passes `-lc` to `zig build-lib` for every library. The
flag is unconditional; no signature or source inspection gates it.

## Consequences

Owned returns, handles, and any user body that touches libc compile on
Linux as they already did on macOS. The platform difference disappears
from the contract.

A pure-arithmetic library now links libc it does not use. The cost is
negligible: these are dynamic libraries loaded into a JVM process that
already links libc, so the dependency is always present at load time.

`-lc` is not part of the cache key. It is constant across every compile,
so every cached artifact is built with it and the content-addressed key
stays correct.

## Alternatives

Link libc only when the generated source needs it, detected by scanning
for `c_allocator`. This keeps arithmetic libraries libc-free, but a
substring scan is fragile and blind to other libc use in a freeform user
body, so it would reintroduce the same platform gap for a body that calls
libc by another route.

Switch owned and handle memory to a non-libc allocator such as
`page_allocator`. This avoids the link flag but loses the property that
makes the boundary free safe: a page-allocator free needs the original
length and allocator state, which the caller does not hold, so
cross-boundary freeing would break (ADR 21, ADR 22).
