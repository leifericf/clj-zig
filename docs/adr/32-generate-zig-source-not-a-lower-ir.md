# ADR 32: Generate Zig source, not a lower IR

Date: 2026-06-17

## Context

clj-zig turns a boundary spec and a body into native code by generating Zig
source text, which `zig` then parses, analyzes, and compiles. The Zig
pipeline has lower intermediate forms clj-zig could target instead: ZIR,
the untyped IR that AstGen produces from the parsed AST, and AIR, the typed
IR that Sema produces from ZIR. Emitting one of those directly would skip
the parse and some of the front end, which is the part a content-addressed
cache pays for on a miss.

The temptation is throughput. Generating a lower IR looks like a shortcut
past lexing and parsing. The question is whether that shortcut is worth
coupling clj-zig to the compiler's internals.

## Decision

clj-zig generates Zig source and hands it to `zig`. It does not emit ZIR or
AIR.

Source is the stable, documented, inspectable contract. ZIR and AIR are
internal to the compiler: undocumented as a public interface, unstable
across Zig versions, and produced by AstGen and Sema, which clj-zig would
have to reimplement or drive to emit them faithfully. Generating source
keeps clj-zig on the language's actual surface, the same one a Zig
programmer reads and `zig fmt` canonicalizes.

This serves the project's commitments directly: specs and diagnostics are
data and stay inspectable (ADR 15), and the generated Zig, source path, and
compiler output are all human-readable and pasteable into a REPL or an
editor. A wrapper that is wrong is debuggable because it is text a person
can read.

## Consequences

Each cache miss pays for a full parse and front end, not just code
generation. That cost is bounded by the content-addressed cache (ADR 12),
which compiles a given form once, by baked artifacts that ship precompiled
(ADR 31), and by the warm-compiler direction (ADR 33). The generated source
remains the inspection surface: it is stored beside the artifact, named in
diagnostics, and formatted by `zig fmt`.

clj-zig tracks the Zig language, which evolves, but it tracks the public
language rather than private IR that changes without notice. The pinned
toolchain (ADR 30) fixes the language version a generator targets.

## Alternatives

Emit ZIR. Rejected: ZIR is an internal, unstable representation produced by
AstGen; targeting it means reproducing AstGen's output and chasing
compiler-internal changes, for a saving the cache already neutralizes.

Emit AIR. Rejected for the same reasons, more so: AIR is the typed output of
Sema, deeper in the pipeline and even more entangled with compiler state.

Drive the C backend or generate C. Rejected: it trades the Zig front end for
a C toolchain and loses Zig's type and safety story at the boundary, without
removing a compiler from the path.
