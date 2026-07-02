# ADR 51: Multi-arity defnz mirrors defn grammar

Date: 2026-07-02

## Context

A `defnz` accepts a single signature and body. Functions that make sense
at multiple arities (an accumulator that starts from zero, or from a
seed; a vector operation on one or two operands) must be split into
separate `defnz` forms with different names, or the user must pack
arguments into a single arity. Clojure's `defn` solves this with a
multi-arity grammar; `defnz` should follow.

## Decision

`defnz` gains a multi-arity grammar mirroring `defn`:

    (defnz add
      ([x :i64 :ret :i64] "return x;")
      ([x :i64 y :i64 :ret :i64] "return x + y;"))

Each arity is a `[signature] body` pair. The single-arity form is
unchanged:

    (defnz add [x :i64 y :i64 :ret :i64] "return x + y;")

The macro distinguishes the two shapes: a vector as the first tail
element is a single-arity signature; a list as the first tail element is
a multi-arity body. Each arity is compiled independently into its own
native library (each has its own boundary spec and its own content
hash). The Var's Clojure fn dispatches by argument count to the matching
arity's invoker; a wrong-count call throws `:clj-zig/arity`.

Each arity has its own inspection sub-map under `:lifecycle :arities`
(ADR 47), keyed by argument count. Keep-last-good (ADR 11) applies per
arity: a failed redefinition of one arity leaves the other arities
callable. `recompile!` rebuilds every arity; `explain` reports the last
failed arity.

A file-body descriptor (`:zig/file`) applies to a single-arity form only.
Multi-arity with file bodies is rejected: a file holds one `pub fn`, and
mapping multiple arities to multiple entry points in one file needs
explicit `:zig/fn` per arity, which is deferred until needed.

## Consequences

The `defnz` macro grows a multi-arity parser path, and the wrap fn
becomes a dispatch table over arity. The `establish-binding!` path is
called once per arity, and the results are merged into one Var. The
inspection metadata carries per-arity data under `:lifecycle :arities`.
The single-arity path is the common case and pays no overhead.

## Alternatives

Separate `defnz` forms with manually dispatched names was rejected: it
scatters one logical function across multiple Vars. Packing arguments
into a single arity (a variadic rest arg) was rejected: it loses the
type contract on the non-rest arguments. A new `defnz-multi` form was
rejected: the grammar mirrors `defn`, and Clojure developers expect the
same shape from `defnz`.
