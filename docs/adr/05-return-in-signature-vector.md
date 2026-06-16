# ADR 05: Return type lives in the signature vector

Date: 2026-06-16

## Context

The boundary contract needs to express the return type somewhere.

## Decision

Put the return contract at the end of the signature vector, marked with
`:ret`.

## Consequences

The whole boundary contract stays in one Clojure data structure, and
`:ret` resembles keyword markers already used in binding and
destructuring forms (such as `:as`).

## Alternatives

A separate return-type argument or an attribute map was considered;
splitting the contract across two places makes it harder to compose and
inspect as a single value.
