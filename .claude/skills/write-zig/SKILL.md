---
name: write-zig
description: Recipe for the Zig clj-zig generates and the Zig it compiles: wrapper source emitted by clj-zig.source, the user's defnz body, and hand-written Zig test fixtures. Invoked when writing or generating Zig.
user-invocable: false
---

# write-zig

clj-zig has no hand-maintained Zig source tree. "Writing Zig" here means
one of three things, and the standard for all of them is
`.claude/skills/check-style/references/zig-style.md` (read it first):

1. **Wrapper generation** (`clj-zig.source`). The Clojure code that
   emits Zig. The emitted source must be readable Zig that passes `zig
   fmt --check` and follows zig-style.md: deterministic symbol names
   (so the cache key is stable, docs/04), pointer-plus-length wrappers
   for slices (docs/06), and the boundary lifetime rules honored
   (scalars copied, slices valid only during the call, no retained
   Clojure-owned pointers, docs/03). Write the generator so a human can
   read its output and see ordinary Zig.
2. **The user's body.** Real Zig inside `defnz`, never a weakened DSL
   (ADR 02). The generated wrapper provides ergonomic locals
   (`xs: []const f64`); inside the body, normal Zig is free: comptime,
   allocators, SIMD, C imports, packed structs. clj-zig does not model or
   constrain these internals (ADR 09).
3. **Test fixtures.** Hand-written `.zig` used by tests. Follow
   zig-style.md fully; allocating tests use `std.testing.allocator`.

Procedure for the generator:

1. **Decide the boundary, not the internals.** The generator reads a
   normalized spec (kind/const?/of) and emits the function signature,
   calling convention, and slice marshalling. It never tries to shape
   what the user's body does.
2. **Allocator and lifetime discipline.** A generated wrapper that
   allocates pairs every `alloc` with `defer`/`errdefer` on every path.
   A returned slice or pointer never aliases memory the call is about
   to free; an `[:owned ...]` return defines who frees it.
3. **Errors become diagnostics.** A compile failure is mapped into the
   structured diagnostic in the Clojure shell (docs/04), Var and
   signature first, then the `source.zig` path, then `:zig/stderr`.
4. **Verify.** `zig fmt --check` on emitted and fixture source; `zig
   build` (the floor); for slice or pointer code, exercise it through a
   Clojure test that compiles, loads, and calls it.

Comment discipline: terse and sparse, like the Zig standard library.
Comment only an ownership, lifetime, or unreachable-branch invariant;
no narration, no banners.

Public-facing text rule: never "hand-written" or "hand-rolled" in doc
comments, docs, or commit lines.
