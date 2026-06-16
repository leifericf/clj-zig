# ADR 16: Functional core, imperative shell

Date: 2026-06-16

## Context

clj-zig's pipeline mixes pure transformation (parse, normalize, generate)
with side effects (compile, write, load, rebind).

## Decision

Signature parsing, type normalization, spec construction, and source
generation are pure data transformations. Compiler invocation,
filesystem writes, library loading, and Var rebinding are shell
operations.

## Consequences

Pure cores are easy to test, compose, reuse, and inspect; the shell
holds the unavoidable side effects and switches on values the core
returns. This is the project's load-bearing structure.

## Alternatives

Interleaving effects through the pipeline was the thing to avoid; it
makes the core untestable without mocks and hides decisions in
effectful loops.
