# ADR 28: A Clojure namespace is a Zig namespace

Date: 2026-06-17

## Context

Related native functions want to share imports, `@cImport`s, helpers, and
types. Zig expresses this with a file: a file is implicitly a struct, a
namespace of its `pub` members, and `clojure.core` is the Clojure
precedent for one namespace assembled from several source files. clj-zig
already scopes `defz` and `deftypez` to the namespace and splices them as
a shared preamble (the `:deps` input), so a grouping mechanism is half
present, but every `defnz` body still stands alone: a string or a
per-function `{:zig/file ...}` (ADR 26) with its own C flags (ADR 27).

The REPL-driven workflow is the constraint. Each `defnz` compiles to its
own content-addressed library so a redefinition recompiles one function
and a failed compile keeps the last good binding (ADR 11, ADR 12). Any
grouping must not coarsen that.

## Decision

A Clojure namespace is a Zig namespace: a shared source scope, compiled
per function. Scope is shared at the source level through the existing
`:deps` preamble and `:options`, both already in the content hash;
compilation stays isolated at the function level, one library per `defnz`.
Per-function redefinition and keep-last-good are preserved even when a
shared declaration changes, because changing it changes every dependent
function's hash and recompiles each independently.

A `.zig` file co-located with the namespace source holds the bodies: path
binds them, `app/geometry.clj` to `app/geometry.zig`, the same stem rule
Clojure already uses for filename and namespace. The `.clj` stays
authoritative for the `ns` form, requires, and visible surface. A bodyless
`defnz` defaults its body to the co-located file's `pub fn` of the same
name. A `zig-deps` form declares C-interop options once for the namespace
instead of per function. An optional `//! clj-zig: <ns>` header in the
`.zig` asserts which namespace the file belongs to; the path remains the
binder, the header only catches a mismatch.

Contracts stay Clojure-side data (ADR 17): the SHAPE is inferred from Zig
types, but POLICY a Zig type cannot express, ownership of a returned
`[]T`, `:handle` versus `:ptr` for a `*T`, requires an explicit signature.

## Consequences

A namespace of native functions reads like an idiomatic Clojure namespace:
one `.clj` of `defnz`s over one `.zig` of bodies, shared imports and
helpers declared once. Namespaced keys group concerns: `:zig/*` for source
directives, `:c/*` for C interop (ADR 27 keys move under `:c`).

The shared scope rides a preamble, so a change to a shared `defz` or to
`zig-deps` recompiles every function in the namespace, not just the edited
one. This is the price of sharing and it keeps the cache correct.

## Alternatives

Compile the namespace as one unit, which would let one exported function
call another natively. Rejected: a single unit recompiles and reloads the
whole namespace on any edit, coarsening the per-function REPL loop that
keep-last-good depends on. The REPL-safe substitute for a shared routine
is a `defz` helper both bodies call, which is Zig-idiomatic anyway.

Bind the `.zig` to the namespace by a declared name rather than by path.
Rejected as the binder: path co-location matches Clojure's own
filename-to-namespace rule and needs no registry. The declared name is
kept as the optional assertion header.
