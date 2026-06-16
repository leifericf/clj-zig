# ADR 07: Built-in Zig types are keywords

Date: 2026-06-16

## Context

The boundary vocabulary needs a representation for built-in Zig scalar
types.

## Decision

Represent built-in Zig types as keywords using exact Zig spelling, such
as `:i64`, `:f64`, and `:void`.

## Consequences

Keywords are idiomatic Clojure data and avoid symbol-resolution
ambiguity; exact Zig names avoid confusion with JVM and Clojure names
like `long`, `double`, or `nil`.

## Alternatives

Resolved symbols or JVM type names were considered; symbols invite
resolution ambiguity, and JVM names misdescribe the native types.
