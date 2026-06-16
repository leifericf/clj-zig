# ADR 03: defnz is the primary function form

Date: 2026-06-16

## Context

Zigar needs a primary form for defining a Clojure-callable, Zig-backed
function.

## Decision

Use `defnz` for Clojure-callable Zig-backed functions.

## Consequences

It reads as `defn` plus Zig and keeps the mental model anchored in
ordinary Clojure function definition: "I defined a Clojure function
whose implementation happens to be Zig."

## Alternatives

An explicit compile/load/bind sequence was the thing to avoid; it
exposes the native machinery the design wants hidden.
