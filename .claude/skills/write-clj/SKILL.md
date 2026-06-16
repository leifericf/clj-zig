---
name: write-clj
description: Recipe for writing clj-zig's Clojure code: the pure core (signature, type, spec, source, cache) and the imperative shell (compile, ffm, inspect). Invoked when writing clj-zig's Clojure code.
user-invocable: false
---

# write-clj

Write Clojure for clj-zig. The standard is
`.claude/skills/check-style/references/clj-style.md` (read it first);
the design it implements is the dossier (`docs/`), and the decisions
constraining it are the ADRs in `docs/adr/`.

Placement follows the functional-core / imperative-shell split
(ADR 16), which is the project's load-bearing structure. The suggested
namespaces (docs/06):

1. **Pure core**: `clj-zig.signature`, `clj-zig.type`, `clj-zig.spec`,
   `clj-zig.source`, `clj-zig.cache` (hashing only). Data in, data out; no
   IO, no shelling out. JVM Clojure semantics are the spec for the
   Clojure surface; check real Clojure behavior for every edge (nil,
   empty, arity, unsigned ranges) before writing.
2. **Shell**: `clj-zig.compile` (invoke `zig`), `clj-zig.ffm` (load the
   library, bind the symbol via JDK FFM/Panama), `clj-zig.inspect`
   (render diagnostics and inspection helpers), and `clj-zig.core` (the
   `defnz` macro and Var rebinding). The shell switches on values the
   core returns; it holds no logic of its own.

Always:

- `defnz` and the `z`-suffixed forms stay thin over the core: parse the
  form into data, call the pure functions, rebind the Var. A user must
  be able to reach the same result through the data functions without
  the macro (`zig/normalize-signature`, `zig/generate-source`,
  `zig/build-spec`). That decomposition is a requirement (docs/05),
  not a nicety.
- Diagnostics are data (docs/04 fixes the shape); the core returns or
  throws them, the shell renders.
- Tests in `test/clj_zig/<area>_test.clj`. TDD: a failing test against
  the intended behavior first, then the implementation.
- Prefer Clojure for orchestration and tooling; reach for the `zig`
  compiler only at the native edge, from the shell.
