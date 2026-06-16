# ADR 11: Keep the last good implementation after a compile failure

Date: 2026-06-16

## Context

Re-evaluating a `defnz` at the REPL can fail to compile. The running
system has to decide what happens to the previously bound function.

## Decision

If re-evaluating `defnz` fails to compile, preserve the previous
working function when one exists.

## Consequences

A failed experiment does not break the running system, which matches
the exploratory REPL workflow. The failure surfaces as a diagnostic
while the last good binding stays callable.

## Alternatives

Unbinding the Var on failure was considered; it destroys working state
mid-exploration for no benefit.
