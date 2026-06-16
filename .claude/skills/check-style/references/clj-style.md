# Zigar Clojure style: the checkable standard

Applies to everything written in Clojure for Zigar: `src/zigar/*.clj`,
`test/zigar/*_test.clj`, `dev/`, `examples/*.clj`, and any build or
tooling scripts. `check-style` applies this file; `write-clj` writes
to it. The dossier (`docs/`) is the design spec; this file is the
coding standard.

## The spec is JVM Clojure

- Canonical JVM Clojure defines correct behavior. Follow the community
  Clojure style guide where a norm exists; don't invent house style
  where one already does.
- Zigar's public surface is ordinary Clojure: a `defnz` form defines a
  normal Var, returns Clojure values, and redefines like `defn`
  (docs/04). Match that expectation; surprising semantics at the
  Clojure surface are findings.

## Simplicity (design filter)

- Prefer simple over easy: don't intertwine unrelated concerns, such as
  state with time, data with behavior, logic with effects. Choose the design
  with fewer braided concerns even when it is less familiar.
- Values are immutable facts; model change as a succession of new
  values, not in-place mutation. Mutable references (atoms) live at the
  edges; pure functions take values and return values; `swap!` takes a
  pure function.
- Data first: plain maps with keyword keys for entities, options, and
  configuration. `defrecord` only for protocol dispatch or a measured
  hotspot, never to imitate classes. (`defrecordz` is a boundary
  contract, a separate thing; see docs/02.)

## Functional core, imperative shell (ADR 16)

Zigar's spine separates deciding from doing. This is the project's
load-bearing structure, not a preference.

- **Pure core:** signature parsing, type normalization, spec
  construction, source generation, hashing. These take data and return
  data, do no IO, and never shell out. `zig/normalize-signature`,
  `zig/normalize-type`, and `zig/generate-source` are the canonical
  examples (docs/05).
- **Shell:** compiler invocation, filesystem writes to the artifact
  cache, library loading through FFM, Var rebinding, diagnostic
  rendering. The shell switches on values the core returns; it carries
  no logic of its own.
- Test the core directly with data in and data out. A shell branch a
  test would want to reach is a decision that belongs in the core.
  move it, don't mock the shell.

## Idiom checklist

- Sequence library first: `map`/`filter`/`reduce`/`into`/`group-by`/
  `keep` over manual `loop`/`recur`; `mapv`/`filterv`/`reduce-kv` when
  a vector is wanted; `vec`, not `(into [] ...)`.
- Nil punning: `(when (seq coll) ...)`, not
  `(when-not (empty? coll) ...)`; sets as predicates where natural.
- `when` for one-armed `if`; `if-let`/`when-let` over `let` + `if`;
  `if-not`/`not=` over wrapped `not`; `cond` with `:else`; `case` for
  compile-time constants; threading macros over deep nesting.
- Multi-arity for defaulting (small arities call the largest, ordered
  fewest-to-most); an options map instead of more than 3-4 positional
  parameters.
- Errors: `ex-info` with rich data maps (the Var, the signature, the
  offending slice) at boundaries; explicit error values inside pure
  cores when callers branch on outcome. Pick one per area; don't mix
  arbitrarily.

## Diagnostics are data

- A compile or validation failure is a structured diagnostic map, not a
  string: `{:level :error :error/code :zig/compile-failed :var ...
  :signature ... :zig/source-path ... :zig/stderr ...}` (docs/04 fixes
  the shape). Cores return or throw diagnostics; only the shell renders
  them for humans.
- Human rendering starts from Clojure (the Var and signature first),
  then the generated Zig path, then the Zig compiler output.

## Macros

- Write the function first; a macro is for genuine syntactic
  abstraction, never for a single call site or to save characters.
- `defnz` and its `z`-suffixed relatives are the project's deliberate
  macros, and they must stay thin over the data functions (principle 7,
  docs/05): the macro parses a form into data and calls the pure core,
  so users can reach the same core without the macro.

## Naming and formatting

- Predicates end in `?`; effectful or mutating functions end in `!`
  (`recompile!`, `clean!`); dynamic vars wear earmuffs; conversions use
  `->` (`spec->source`); unused bindings are `_` or `_`-prefixed.
- Built-in Zig boundary types are keywords with exact Zig spelling
  (`:i64`, `:f64`, `:void`); compound types are vectors
  (`[:slice :const :u8]`). Keep that vocabulary exact (docs/03).
- 2-space indent, no tabs; align `let` bindings and map values; no
  commas in sequential literals; gather trailing parens; one blank line
  between top-level forms.

## `ns` form

- kebab-case namespaces under the `zigar` root: `zigar.signature`,
  `zigar.source`. Exactly one namespace per file, one file per
  namespace. Public API at the top, `defn-` helpers below.
- `:require :as` over `:refer [...]` over `:refer :all`; avoid `:use`.
  Sort entries alphabetically; use idiomatic aliases (`str`, `set`,
  `io`) and the same alias for a namespace across the project.
- Dynamic namespace manipulation (`require`, `in-ns`) is for the REPL,
  never inside functions or production paths.

## What to avoid

- Imperative index-walking where sequence functions suffice.
- `def` inside functions; vars as hidden mutable state.
- Macros where functions suffice; tacit overuse of `comp`/`partial`/
  `#()` chains that obscure intent.
- Writing Java in Clojure: mutable Java collections in the core, heavy
  `new`/`set!`. The FFM and native calls are the shell's job, kept
  behind the boundary; the core stays plain data.

## Tests

- `clojure.test` under `test/zigar/<area>_test.clj`. Test the pure core
  directly; never mock. Effects run against real scratch state (a /tmp
  cache dir, a scratch Zig compile).
- Edge cases are the point: nil, empty, single, boundary sizes,
  unsigned ranges beyond signed JVM bounds, malformed signatures,
  misplaced `:ret`.

## Public-facing text

- Never describe code as "hand-written" or "hand-rolled" in
  user-facing docs, docstrings, or commit and changelog lines.
