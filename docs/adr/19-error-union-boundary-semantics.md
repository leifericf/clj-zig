# ADR 19: Error-union boundary semantics

Date: 2026-06-16

## Context

The boundary contract names `[:error-union E T]` and says a Zig error
should reach Clojure as data, but it leaves the mapping open. A Zig
error union is a tagged value with no stable C-ABI representation, so an
`export fn` cannot return `E!T` directly. The boundary needs a concrete
protocol for carrying the success value or the failure across the edge.

## Decision

`[:error-union E T]` is supported in return position only. `E` is a Zig
error set named by symbol, including the builtin `anyerror`; `T` is a
value-carrying scalar or `:void`. The user body returns `E!T` and may
either `return error.X` or return a value.

clj-zig generates an inner function holding the user body and an export
wrapper that calls it with `catch`. On success the wrapper returns the
value (or nothing, for `:void`) and reports no error. On failure it
copies `@errorName(e)` into a caller-provided byte buffer and writes the
name's length to an out-parameter.

The Clojure side returns the value `T` on success and the error as a
keyword on failure, the error name keywordized. The error
`error.InvalidCharacter` becomes `:InvalidCharacter`. The error crosses
as data, not a thrown exception, so a caller branches on the result: a
keyword is the error, anything else is the value.

An error union in argument position is rejected when the spec is built,
with `:clj-zig/unsupported-error-union`, as is a value type that is
neither a scalar nor `:void`.

## Consequences

A failing native operation reads as ordinary Clojure data that composes
in `if-let` and `cond`, with no exception handling forced on the caller. The cost is that the result is a union the caller must
discriminate, and a `T` that could itself be a keyword would be
ambiguous; since `T` is a numeric or boolean scalar (or `:void`), this
cannot arise in the proof of concept. The out-parameter protocol also
means an error-union wrapper carries two synthetic parameters the
boundary contract does not name.

## Alternatives

Throwing an `ex-info` on error was rejected because the contract calls
for error-as-data, and a keyword result composes more naturally in the
dynamic Clojure surface. Encoding the error as its `@intFromError`
integer was rejected because Clojure cannot map a code back to a name
without the compiled error table, whereas `@errorName` is portable.
Supporting error unions as arguments was rejected; the proof of concept
has no use for them, and the contract frames them as a return shape.
