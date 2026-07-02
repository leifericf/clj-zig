# ADR 55: Comptime parameters splice at call time

Date: 2026-07-02

## Context

Zig `comptime` parameters enable specialization: the compiler optimizes
for the specific value, producing a faster binary. clj-zig's boundary
model compiles each function once and caches by content hash. A comptime
parameter changes the function per distinct value, so the compile-once
model does not apply.

## Decision

A parameter tagged `^:comptime` in the signature is a comptime
parameter. The `defnz` macro detects it and routes to
`establish-comptime-binding!`, which binds a factory function instead of
a single invoker. At each call, the comptime values are spliced into the
body as `const` declarations (`const lanes: i32 = 4;`), the modified
body is compiled or reused from the content-addressed cache, and the
non-comptime arguments are passed to the invoker.

Comptime parameters are the last parameters before `:ret` in the calling
convention. The arglist includes them (for documentation and dispatch);
the wrap fn's arglist excludes them (only non-comptime args reach the
invoker).

## Consequences

Each distinct comptime value set compiles a new library, cached by
content hash. The first call with a new value pays the compile cost;
subsequent calls reuse the cached artifact. The factory function holds
an in-memory cache of comptime-value-to-invoker mappings. The comptime
value must be a literal at call time (it is rendered as a Zig source
literal).
