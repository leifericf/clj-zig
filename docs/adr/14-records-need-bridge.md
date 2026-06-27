# ADR 14: Records require explicit bridge definitions

Date: 2026-06-16

## Context

Accepting or returning a record across the boundary needs a known
memory layout.

## Decision

Clojure records participate through `defrecordz`, not automatic
conversion of arbitrary records.

## Consequences

A bridged record gives Zig a known field order, field types, alignment,
layout, and ownership rules; an arbitrary Clojure record carries none
of these.

## Alternatives

Automatic record-to-struct conversion was considered; without a
declared layout it cannot produce a sound Zig struct.

## Amendment (2026-06-27)

The record bridge now carries buffer and `:string` fields in addition to
scalars and enums. A field may be `[:bytes [:slice :u8]]` (to a `byte[]`),
`[:owned [:slice T]]` or `[:slice T]` (to a vector), or `:string` (an owned
UTF-8 buffer marshalled to a `String`). Each buffer field lowers to a
`usize` pointer and a `usize` length in the wire `extern struct`, since a
Zig slice is not a C type, so a record can model a multi-value result. The
ownership and free protocol for those fields is ADR 21; the bridge itself
stays explicit through `defrecordz` and `deftypez`.
