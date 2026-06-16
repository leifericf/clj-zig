# ADR 20: Enum boundary semantics

Date: 2026-06-16

## Context

The interface design names `defenumz` and shows
`(defenumz ParseStatus [ok 0 invalid 1 eof 2])`, but it leaves the
Clojure representation, the backing integer type, and the handling of an
unmapped value open. An enum bridge has to fix all three before it can
carry a value across the edge.

## Decision

`(defenumz Name [member value ...])` declares a named boundary type, a
Zig `enum(i32)` with the given members and values, registered in the
namespace's named-type registry beside `deftypez` and `defrecordz`. The
generated Zig declares `const Name = enum(i32) { ok = 0, ... };` in the
preamble, and the user body uses the enum directly, both `switch (s) {
.ok => ... }` and `return .eof;`.

An enum value is a Clojure keyword named for the member, `:ok`,
`:invalid`, `:eof`, not a raw integer. It crosses the C ABI as its
backing `i32`, which a raw FFM call confirms passes as a plain `JAVA_INT`
in both argument and return position; the `export fn` carries the enum
type and Zig lowers it to the int, so no inner function or out-parameter
is needed.

On the Clojure side an argument keyword maps to its integer value through
the member table, and a keyword that names no member is rejected at call
time with `:zigar/unknown-enum-member`. A return integer maps back to its
member keyword; an integer with no matching member returns as the raw
integer, total and lossless. Both positions are supported because an enum
is scalar-sized.

## Consequences

An enum reads as an ordinary Clojure keyword on both sides, and the Zig
body keeps the enum's exhaustiveness and `switch` checking. The bridge is
simpler than the struct path: a scalar carrier, no out-parameter. The
cost is that the keyword set lives in the `defenumz` form rather than in
the type system, so a member rename is a source change in two languages.
The `i32` backing is also fixed, so an enum needing a wider or unsigned
tag is out of reach until the form grows a backing option.

## Alternatives

Carrying values as raw integers was rejected: the keyword preserves the
member name, which is the point of the bridge. Symbols were rejected
because a keyword is the Clojure idiom for an enumerated tag. Throwing on
an unmapped return value was rejected in favor of returning the raw
integer, which keeps the return total; an unknown argument keyword still
throws, because that is a caller error like a wrong arity or a missing
struct field. Lowering the enum to its backing int in the `export`
signature with `@enumFromInt`/`@intFromEnum`, mirroring the
struct-by-pointer scheme, was also considered. Raw FFM showed `enum(i32)`
is already ABI-stable as a plain int, so the indirection is unnecessary.
A configurable backing type was deferred as out of scope.
