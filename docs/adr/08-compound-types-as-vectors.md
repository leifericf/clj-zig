# ADR 08: Compound types are vector data

Date: 2026-06-16

## Context

Compound boundary types (slices, pointers, arrays, optionals) need a
representation.

## Decision

Represent compound boundary types as vectors, such as
`[:slice :const :u8]`.

## Consequences

Vector forms are plain data: easy to compose, validate, and build with
Clojure-side helpers and macros.

## Alternatives

Opaque type objects or constructor calls were considered; they block
composition and make the public contract harder to read and manipulate
(see ADR 15).
