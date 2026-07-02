# ADR 53: deftestz runs Zig test blocks

Date: 2026-07-02

## Context

Zig has a built-in test runner (`zig test`) that compiles and runs
`test` blocks. clj-zig users write both Clojure tests (for the
Clojure-side behavior) and Zig tests (for the native-side logic), but
the Zig tests had no bridge to the Clojure test runner.

## Decision

`clj-zig.zigtest/deftestz` is a macro that defines a Clojure test
(`deftest`) wrapping a `zig test` invocation. The Zig body is written
to a temp file and compiled with `zig test`; the exit code determines
pass or fail. On failure, the compiler output is included in the
assertion message.

## Consequences

A `deftestz` test integrates with `clojure.test` runners and CI. The
Zig test body is a standalone source file (no clj-zig wrappers); it
tests pure Zig logic, not the FFM boundary. The boundary is tested by
the existing Clojure-side integration tests.
