# clj-zig project notes

Operational specifics for working on clj-zig. The design lives in `docs/`
(start at `README.md`); principles in
`docs/07-design-principles-and-decisions.md`; decisions are ADRs in
`docs/adr/`.

## What shapes the code

- Clojure-first: clj-zig is a Clojure library. The Zig is generated
  wrapper source plus the user's body.
- Functional core, imperative shell (ADR 16): parsing, normalization,
  spec construction, and source generation are pure; compiling, caching,
  loading, and Var rebinding are the shell.
- The boundary contract is data; generated Zig, specs, and diagnostics
  stay inspectable.

## Tooling

- Clojure CLI and `deps.edn`, not Leiningen. Tests are `clojure.test`
  under the project's `:test` alias.
- REPL-driven development is the primary loop. `defnz` redefinition is a
  core feature; exercise it at the REPL, not only in tests.
- The native side shells out to `zig`; `zig fmt` owns Zig formatting.
