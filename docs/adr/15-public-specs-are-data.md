# ADR 15: Public specs remain ordinary data

Date: 2026-06-16

## Context

Public type contracts could be opaque objects or plain data.

## Decision

Do not require opaque type objects for public type contracts; the
canonical representation is plain data.

## Consequences

Plain data enables composition, macro generation, validation,
visualization, and user-defined builders. Helper functions may build
the data, but the data is the contract.

## Alternatives

Opaque constructor objects such as `(zig.types/slice ...)` as the only
public representation were considered; they block the composition and
inspection that data allows.
