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

## Amendment (2026-07-01): the FFM carrier limit is an ABI limit, not
just a width limit

An attempt to ship `:f16` as a `Double`-facing scalar (compress on the
way in via `Float/floatToFloat16`, expand on the way out via
`Float/float16ToFloat`, carried over the wire as `JAVA_SHORT`) produced
silent wrong answers: a body that doubled or squared its `f16` argument
returned the argument unchanged. The cause is an ABI mismatch, not a
width problem. `f16` is a floating-point C-ABI type: on aarch64 and
x86_64-SSE it is passed and returned in an FP register (s0 / xmm0). FFM
has no half-float layout, so the only available carrier, `JAVA_SHORT`,
tells the linker to route the value through a general-purpose register.
Zig reads its argument from the FP register and writes its result there,
so the GPR the JVM reads still holds the input bits. The identity call
"worked" only because nothing overwrote that GPR.

The consequence is that `:f16` cannot cross as a scalar argument or
return on stable FFM, regardless of any bit-cast on the Clojure side.
The same constraint shapes the other carrierless types:

- `:f16` is blocked until FFM ships a half-float layout that uses the FP
  ABI. A struct field of `:f16` reads correctly (the field lives in
  memory, not a register), but a partial field-only support is a
  confusion trap and is deliberately not added.
- `:f80` and `:f128` are blocked for the same reason plus an additional
  one: the JVM has no value type for extended or quad precision, so the
  Clojure-side representation is unresolved too.
- `:i128` and `:u128` are delivered (2026-07-01). A probe confirmed the
  integer ABI matches: FFM passes and returns a 128-bit value correctly
  as a struct of two `JAVA_LONG`s (the C `__int128` ABI), with a
  `SegmentAllocator` prepended to the downcall handle for the by-value
  return. The Clojure side sees a `BigInteger`; the marshaller writes the
  little-endian two's-complement halves and reassembles them, applying
  the unsigned policy for `:u128`. They take the general call path
  (not the scalar hot path of ADR 39), since a 16-byte segment carrier
  and the prepended allocator need the call's arena. A 128-bit integer is
  a carrier for a top-level argument or return only; a struct field, a
  slice/array element, an `:optional` cell, a rest argument, or an enum
  backing of one is rejected (`:unsupported-field`/`:unsupported-element`/
  `:unsupported-optional`/`:unsupported-rest-element`/`:bad-enum-backing`)
  until those positions are wired.

The rejection at spec time stands for all five, now with the recorded
reason: it is not marshalling complexity the project chose to skip, but
a carrier FFM does not provide.
