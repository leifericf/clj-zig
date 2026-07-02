# ADR 49: Spec registration is opt-in initially, default-on at 1.0

Date: 2026-07-02

## Context

clj-zig can generate a `clojure.spec` for every boundary type and every
`defnz` function, validating that caller arguments match the boundary
contract. Today no spec is registered. Turning it on by default changes
runtime behavior for every existing user: a call that passed a wrong type
and happened to work (because the native side coerced or ignored it) now
fails at the spec guard.

## Decision

Spec registration is opt-in via `:clj-zig/spec true` in the attr-map:

    (defnz add {:clj-zig/spec true}
      [x :i64 y :i64 :ret :i64]
      "return x + y;")

When opt-in, `clj-zig.spec.check/register!` is called at definition time,
registering both the argument spec and the return spec under the
function's qualified symbol. The spec checks argument types before the
native call and the return type after, failing fast with a spec error
instead of a native crash.

The opt-in phase is temporary. At the 1.0 release, `:clj-zig/spec`
defaults to `true` and `:clj-zig/spec false` is the opt-out. This gives
early adopters a migration window where they can test their code with
specs on, fix the violations, and be ready for the default-on switch.

## Consequences

The spec-checking layer is built and tested now, alongside the
`clj-zig.spec.check` namespace and the `clj-zig.gen` generator library.
Users who opt in get immediate boundary validation. The default-on switch
at 1.0 is a one-line change (the default in the macro), not a new
feature. Until then, the overhead of spec registration is paid only by
those who ask for it.

## Alternatives

Default-on immediately was rejected: it changes runtime behavior for
every existing user in a way that can surface as a regression in working
code. Never turning it on was rejected: boundary validation is a core
value proposition of a typed bridge. A separate `defnz-spec` form was
rejected: it duplicates the signature and diverges from the "one defining
form" principle (ADR 3).
