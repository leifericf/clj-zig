# ADR 41: Per-function optimize mode

Date: 2026-07-01

## Context

Every compiled library was built `-O ReleaseSafe`, hardcoded at the one
spot the `zig build-lib` argv is assembled (`compile.clj`). The build
options map already threaded an `:optimize` key through the content hash
and the merge in `build-inputs`, but the argv read the closed-over
`optimize-mode` var instead of the options, so no caller could override
it. `ReleaseSafe` is the right default (it matches the safety-first
posture of ADR 24, where libc is linked unconditionally so
`c_allocator`'s free is always safe to call), but a developer with a
hot kernel wants `ReleaseFast`, a debug session wants `Debug`, and a
size-constrained target wants `ReleaseSmall`. The flag is a Zig-compile
concern, parallel to the C-interop flags a descriptor or `zig-deps`
already carries.

## Decision

A descriptor or `zig-deps` may name `:zig/optimize` as one of
`:Debug`, `:ReleaseSafe`, `:ReleaseFast`, `:ReleaseSmall`. The keyword
is canonical; it lowers to the string `zig build-lib -O` expects. A new
reader (`descriptor-options`) merges it with the C-interop flags, so the
per-function descriptor and the namespace-level `zig-deps` layer optimize
and link flags the same way. `build-arguments` reads the mode from the
options map, falling back to `ReleaseSafe` when nothing declares one.

A keyword outside the four allowed values throws
`:clj-zig/bad-optimize-mode` at registration time, before any compile, so
a typo (`:Release`, `:release-fast`, the string `"ReleaseFast"`) fails
with a named diagnostic rather than a confusing Zig error.

The mode enters the content hash through the options map (it already
did), so two functions identical except for their mode compile to two
libraries and bake to two artifacts. That is correct: the modes emit
different code.

## Consequences

A developer picks the optimize mode per function or per namespace,
overriding the safe default where the kernel earns it. `ReleaseSafe`
stays the default, so existing behavior and every existing cache key are
unaffected. The mode is inspectable through the build options a function
carries, and it flows through bake unchanged because bake reuses
`gen-from-info` and `build-inputs`.

## Alternatives

A `:zig/optimize` string accepted verbatim. Rejected: a typo would pass
through to `zig build-lib` as an unknown mode, surfacing as a generic
compile error instead of the named `:clj-zig/bad-optimize-mode` a
developer can branch on.

A separate optimize reader per path (one for the descriptor, one for
`zig-deps`). Rejected: `descriptor-options` already serves both, and a
single reader keeps the layering precedence -- namespace default, then
per-function override -- in one place.
