---
name: check-style
description: Review recipe for the style dimension (naming, idiom, comments, decomposition) against the codified Clojure and Zig standards. Invoked when reviewing a change.
user-invocable: false
---

# check-style

Review the assigned shard against the codified standards:

- Clojure: `references/clj-style.md` (in this skill)
- Zig (generated wrappers, bodies, fixtures): `references/zig-style.md`
  (in this skill)

Those files ARE the checklist. Apply them mechanically. Beyond them,
flag only:

1. **Inconsistency with the surrounding file.** A function that names,
   comments, or structures differently from its namespace without
   reason.
2. **Core/shell leakage.** IO, a `zig` invocation, a filesystem write,
   or a Var rebinding inside a function that belongs to the pure core
   (ADR 16), or pure decision logic buried in the shell where a test
   can't reach it. This is the highest-value style finding in clj-zig.
3. **Macro overreach.** A `z`-suffixed macro doing work that belongs in
   a data function, so the result is unreachable without the macro
   (docs/05 requires the decomposition).
4. **Comment debt.** A boundary ownership or lifetime constraint that
   is true but unstated where it matters; comments that narrate the
   next line instead of stating a constraint; stale comments
   contradicting the code.

When the shard includes prose (docs, DEC entries, docstrings), the
standard is `references/prose-style.md` in this skill. Apply it the
same way.

Do not flag: generated artifacts under `.clj-zig/cache/`, anything an
ADR (`docs/adr/`) records as deliberate, or a documented upstream
platform difference.

Style findings are `:level :style`, severity `:low` (or `:medium` when
a misleading comment could cause a future correctness mistake, or when
core/shell leakage will make a branch untestable). They land last,
after correctness and factoring, never block on them.
