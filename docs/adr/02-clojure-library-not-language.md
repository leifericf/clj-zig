# ADR 02: Build Zigar as a Clojure library, not a new language

Date: 2026-06-16

## Context

Combining Clojure and Zig could mean inventing a new Lisp or a Zig
dialect, or building a library that bridges the two existing languages.

## Decision

Build Zigar as a Clojure library and boundary-contract system, not a
new Lisp or Zig dialect.

## Consequences

The value stays in Clojure-native authoring plus real Zig execution.
Users keep both languages whole; nothing about either is weakened.

## Alternatives

A new language unifying both was considered. It would let the syntax be
purpose-built, but it would weaken both sides: not real Clojure, not
real Zig, and a far larger surface to implement and learn.
