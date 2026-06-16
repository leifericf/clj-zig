# ADR 18: Boundary carriers and the unsigned-return policy

Date: 2026-06-16

## Context

The boundary contract lists scalar types the proof of concept must
carry across FFM, but two questions were left open before
implementation. First, an unsigned integer can hold a value outside the
JVM's signed range, and the contract said this needs an explicit policy.
Second, the finalized FFM value layouts (Java 22) cover only 8/16/32/64
bit integers, 32/64 bit floats, and `bool`; several listed scalar types
have no carrier at all.

## Decision

An unsigned integer return is a `Long` when its value fits the signed
64-bit range and a `BigInteger` when it overflows, so the result is
always the exact non-negative value. An unsigned argument crosses as the
two's-complement low bits of its signed carrier, so it is never
truncated. `:void` and `:noreturn` carry no value, and a `:void` return
is `nil`.

A scalar with no FFM carrier is rejected when the spec is built, with
error code `:clj-zig/unsupported-carrier`: the 128-bit integers `:i128`
and `:u128`, and the 16, 80, and 128 bit floats `:f16`, `:f80`, `:f128`.

## Consequences

Values cross losslessly and a programmer never sees a surprise negative.
The cost is that an unsigned 64-bit result may be a `BigInteger`, so
callers cannot assume `Long`. Rejecting the carrierless types early
gives a clear contract error instead of a confusing native failure, at
the cost of not supporting those types in the proof of concept even
though Zig defines them.

## Alternatives

Truncating or letting an unsigned value wrap to a negative `Long` was
rejected as a correctness trap that hides data loss. Synthesizing the
128-bit integers and the half and extended floats from several carriers
or a byte array was rejected as marshalling complexity beyond the proof
of concept; the types can be added later behind the same contract.
