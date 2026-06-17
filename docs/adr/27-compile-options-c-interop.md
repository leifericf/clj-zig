# ADR 27: Compile options carry C-interop flags

Date: 2026-06-17

## Context

A Zig body may `@cImport` a C header and call into a C library. System
headers on the default search path link through libc, which every library
already gets (ADR 24), so `@cInclude("math.h")` and `c.sqrt` need only the
linker to be told to link libm. A third-party library needs its header
search path, its library search path, and the library itself passed to the
compiler. clj-zig's `zig build-lib` invocation hard-coded its flags and
passed nothing of the sort, so C interop beyond default-path libc was not
expressible.

The build inputs already carry an `:options` map into the content hash,
but the compile shell ignored it.

## Decision

A `{:zig/file ...}` descriptor may carry C-interop options that become
`zig build-lib` flags: `:c/include-path` -> `-I`,
`:c/system-include-path` -> `-isystem`, `:c/link-path` -> `-L`, and
`:c/link` -> `-l`. The pure core collects them into the `:options` map;
the compile shell maps `:options` to flag tokens and appends them after
`-lc`, before the output and source arguments. `-lc` stays unconditional.

`:options` already participates in the content hash, so two functions that
differ only in their link flags get distinct cache entries and neither
reuses the other's library.

## Consequences

A file-mode body can `@cImport` a header and declare its link needs in the
same descriptor:

    (defnz hyp
      [a :f64 b :f64 :ret :f64]
      {:zig/file "hyp.zig" :c/link ["m"]})

The flags are per function, which matches the content-addressed cache: a
function records exactly the build that produced its library. A library
linked across many functions is named in each descriptor that needs it,
rather than once for a namespace or project; a coarser scope can come
later if the repetition warrants it.

## Alternatives

A namespace- or project-level link configuration. Rejected for now:
per-function options keep the build descriptor next to the function and
keep each cached artifact self-describing, at the cost of repeating a
shared library across descriptors. Revisit if real use shows the
repetition is a burden.

Detect C usage by scanning the source and linking automatically. Rejected:
a substring scan is fragile and cannot know which third-party libraries a
body intends, the same reasoning that made libc linking unconditional in
ADR 24.
