# ADR 48: Reserve descriptor key prefixes by domain

Date: 2026-07-02

## Context

A `defnz` descriptor (the attr-map or the body map) and a `zig-deps`
declaration accept a growing set of keys. Today the keys are flat:
`:zig/optimize`, `:zig/file`, `:zig/fn`, `:zig/raw`, `:zig/symbol`,
`:c/link`, `:c/include-path`, `:zig/modules`. As more build flags and
behavioral knobs are added (`:zig/panic-fn`, `:zig/single-threaded`,
`:zig/pic`, `:zig/stack-check`), the flat space risks typos that pass
silently: a misspelled `:zig/single-thread` (without the `d`) would be
ignored by `zig build-lib` with no error from clj-zig.

## Decision

Three key prefixes are reserved, each scoped to its domain:

- `:zig/*` keys pass through to `zig build-lib` as flags or configure the
  build. The macro validates each `:zig/*` key against a curated set and
  throws `:clj-zig/unknown-zig-option` for one it does not recognize.
  Recognized keys: `:zig/optimize`, `:zig/file`, `:zig/fn`, `:zig/raw`,
  `:zig/symbol`, `:zig/modules`, `:zig/panic-fn`, `:zig/single-threaded`,
  `:zig/pic`, `:zig/stack-check`.
- `:c/*` keys carry C-interop options (include paths, link paths, linked
  libraries). Already curated; extended the same way.
- `:clj-zig/*` keys configure clj-zig behavior that is not a Zig build
  flag (spec registration, streaming, comptime). These are outside the
  Zig compiler's vocabulary.

Unknown keys under any reserved prefix are rejected at macro expansion
time. A key outside all three prefixes (a user's own attribute-map key,
like `:doc` or `:tag`) passes through to the Var metadata as before; the
reservation is prefix-scoped, not a blanket validation.

## Consequences

A typo in a `:zig/*` or `:c/*` key fails immediately with a clear
diagnostic, not silently. Adding a new `:zig/*` flag requires registering
it in the curated set, which is one line in one place. The curated set is
the single source of truth for what clj-zig accepts.

## Alternatives

Passing every `:zig/*` key straight to `zig build-lib` was rejected: Zig
does not error on an unknown flag it does not parse (it may interpret it
as a root source argument), and the failure mode is a confusing compile
error, not a clear "this key is not recognized." A free-form map with no
validation was rejected for the same reason that drove this ADR.
