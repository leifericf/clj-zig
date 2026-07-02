# ADR 54: Streaming iterator uses direct function calls

Date: 2026-07-02

## Context

ADR 50 specified a streaming return where the iterator type's `:next`
and `:deinit` methods are called via Zig dot syntax (`self.next()`).
The initial implementation generated `__self.counter_next()`, but Zig
0.16 requires functions to be in the struct's declaration scope for dot
syntax, and top-level functions are not found there.

## Decision

The generated `__next` and `__free` shims call the iterator functions
directly: `counter_next(__self)` instead of `__self.counter_next()`.
The `:clj-zig/iter` metadata names free functions whose first parameter
is `*IterType`, not struct methods. The init fn wraps the body in an
inner impl fn that returns `*IterType`, then converts to `usize` with
`@intFromPtr`.

## Consequences

The iterator functions are plain top-level functions, not struct
methods. They are declared via `defz` or inline in the body. The
generated shims are agnostic to where the functions live, as long as
they are in scope.
