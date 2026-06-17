# ADR 29: Multi-file Zig imports copy the import closure into the build

Date: 2026-06-17

## Context

A file-mode body (ADR 26) is read as text and concatenated with the
namespace preamble and the generated wrapper into one `source.zig`, written
into the content-addressed cache directory and compiled there. Zig resolves
`@import("util.zig")` relative to the importing file's own path, which is
now `source.zig` in the cache directory, so a sibling import of a file in
the user's source tree does not resolve. Compiler-provided modules such as
`@import("std")` are unaffected, since the compiler supplies them.

The namespace preamble (ADR 28: a co-located `.zig` plus `defz` and
`deftypez`) already shares helpers, `@cImport` blocks, and types across the
functions of a namespace, which is the common reason to reach for an
import. What it does not cover is a body split across several `.zig` files
or a vendored multi-file Zig library that uses relative `@import` between
its own files.

This record fixes the approach; the capability is planned, not yet built.

## Decision

A file-mode body may `@import` other `.zig` files by relative path.
clj-zig resolves the transitive closure of relative `@import` targets
starting from the body file, copies those files into the cache directory
alongside the generated source preserving their relative layout, and
includes each copied file's content in the content hash. An edited
imported file therefore changes the hash and recompiles. Absolute imports
and compiler-provided modules (`std`, `builtin`) are left untouched.

## Consequences

A body can be organized across several files, and a vendored Zig library
whose files import each other by relative path compiles unchanged. The
cache stays content-addressed: the artifact records the exact contents of
every file that produced it, so keep-last-good and per-function
recompilation hold across the import graph.

clj-zig gains a small Zig-import scanner and transitive resolution, which
is new surface to keep correct. The scan reads `@import` string literals;
an import computed at comptime is out of scope and will not be copied. A
cycle in the relative-import graph is the file author's error, surfaced as
a Zig compile error rather than detected up front.

## Alternatives

Compile the generated source in place in the user's source tree so sibling
imports resolve directly. Rejected: it abandons the content-addressed
cache directory that per-function recompilation and keep-last-good depend
on, and it litters the project with generated artifacts.

Register the user's directory as a named Zig module with `-M`/`--dep`.
Rejected as the primary mechanism: module flags address named imports
(`@import("mylib")`), not the bare relative `@import("util.zig")` a Zig
author writes between sibling files. Named-module dependencies can be added
later as a separate capability without conflicting with this one.

Require all sharing to go through the namespace preamble and forbid file
imports. Rejected: the preamble covers in-namespace helpers but cannot
absorb a multi-file vendored library that imports its own files.
