# ADR 52: Spec registration is opt-in

Date: 2026-07-02

Supersedes the spec-registration portion of ADR 49.

## Context

ADR 49 established that spec registration is opt-in via
`:clj-zig/spec true`, defaulting on at 1.0. The implementation landed in
`clj-zig.spec-check`, which maps every boundary type to a
`clojure.spec.alpha` predicate.

## Decision

The `spec-for-type` function maps each normalized boundary type to a
spec form: scalars to `int?`/`double?`/`boolean?`; enums to the member
keyword set; named structs to `map?`; slices to `coll-of`; optional to
`nilable`; handle to `some?`; stream to the element spec. Argument
specs are permissive (a slice arg accepts any array or collection);
return specs are precise.

The `register!` function evaluates `s/def` and `s/fdef` forms to
register both the argument and return specs. The `:clj-zig/spec true`
attr-map triggers `register!` at definition time via
`requiring-resolve`, so `clojure.spec.alpha` is not a hard dependency
of `clj-zig.core`.

## Consequences

Users who opt in get immediate boundary validation. The spec forms use
fully-qualified names (`clojure.spec.alpha/coll-of`) so they resolve
correctly in any namespace. The default-on switch at 1.0 is a one-line
change.
