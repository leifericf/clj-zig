# ADR 26: A defnz body may be sourced from a Zig file

Date: 2026-06-17

## Context

A `defnz` body is a Clojure string spliced into the generated wrapper.
Bodies that contain Zig string literals, such as inline `asm`,
`@import("...")`, `@compileError("...")`, and `@cImport`, force the author
to escape every `"` as `\"`, which is hard to read and hard to write. A
string body also has no real Zig tooling: an editor, `zig fmt`, and the
Zig language server cannot parse a loose fragment of statements, so the
author loses formatting, completion, and type checking exactly where the
Zig is most intricate.

## Decision

A `defnz` (or `defz`) body may be a `{:zig/file "name.zig"}` descriptor
instead of a string. The path resolves relative to the defining Clojure
source file, then to the current directory, then to a classpath resource,
matching what a Clojure developer expects from a sibling file and from
`io/resource`.

The file is a complete, valid Zig file holding a `pub fn`. clj-zig still
generates the boundary export wrapper, but the wrapper reconstructs its
arguments and **calls** the user's function rather than splicing text. The
wrapper passes idiomatic Zig values: a slice arrives as `[]const T`, a
struct by value, a scalar or enum directly. The entry fn is named after
the Clojure fn with hyphens as underscores (`dot-product` ->
`dot_product`), overridable with `:zig/fn` for names that are not legal
Zig identifiers.

A `:zig/raw` escape hatch skips wrapper generation entirely: the file owns
the complete `export fn` and its C ABI, and `:zig/symbol` names the symbol
to bind. The descriptor map also carries C-interop options (ADR 27).

The file content, not its path, enters the content hash, so re-evaluating
the form re-reads the file and an edited `.zig` recompiles. Inline string
bodies are unchanged; the file body is purely additive.

In file mode clj-zig never injects a top-level `const std`, since the user
file owns its imports; the one generated declaration that needs `std`, the
owned-return free shim, imports it inline so it cannot collide.

## Consequences

Inline asm, `@cImport`, and other quote-heavy or large bodies move into
real `.zig` files with full editor and `zig fmt` support, and a C-interop
unit keeps its `@cImport` and function together in one file. The
`zig/source` inspection returns the file text; `zig/generated-source`
shows the wrapper that calls it; `zig/source-file` and `zig/source-mode`
record where it came from.

The author restates the parameter types in Zig, which is the cost of a
standalone-valid file; a mismatch with the boundary contract surfaces as a
Zig compile error. Compiler diagnostics point into the concatenated
`source.zig`, not the original file, but `zig/source-file` names the file
to open. A body typed directly at the REPL has no defining file, so a
relative `:zig/file` there resolves only against the current directory or
the classpath.

## Alternatives

Splice a body fragment from the file, identical to the string today.
Rejected: a fragment is not a valid Zig file, so it gives none of the
tooling that motivates a file, and it cannot hold a top-level `@cImport`.

Require the file to hold the full `export fn` always (raw only). Rejected
as the default: it discards the boundary wrapper that is clj-zig's reason
to exist and makes the author own the pointer-plus-length and out-param
ABI. Kept as the `:zig/raw` escape hatch for full C-ABI control.

A dedicated `defnz<-file` form. Rejected: a data descriptor in the body
position matches clj-zig's data-oriented surface and the deps.edn
coordinate maps a Clojure developer already knows, and it extends to carry
the C-interop options without a second mechanism.
