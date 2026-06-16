# ADR 06: :ret is required and final

Date: 2026-06-16

## Context

The signature vector needs a rule for where the return marker sits and
whether it is optional.

## Decision

Require `:ret` in the proof of concept and allow nothing after it.

## Consequences

Native boundaries always carry an explicit return contract, and a final
marker keeps parsing simple and signatures readable.

## Alternatives

An optional or inferred return type was considered; inference across a
native boundary is unreliable, and optionality removes the explicit
contract the boundary needs.
