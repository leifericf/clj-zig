# Zigar Clojure style: the checkable standard

Applies to everything written in Clojure for Zigar: `src/zigar/*.clj`,
`test/zigar/*_test.clj`, `dev/`, `examples/*.clj`, and any build or
tooling scripts. `check-style` applies this file; `write-clj` writes to
it. The dossier (`docs/`) is the design spec; this file is the coding
standard. It is normative: when a general Clojure source disagrees,
follow this file.

## What the standard optimizes for

- **Simple over easy.** Simple means unbraided: one concern, reasoned
  about on its own. Easy means familiar or near at hand. Prefer the
  simple artifact even when it is less familiar to write.
- **No complecting.** Do not interleave concerns that could stand apart:
  state with time, data with behavior, logic with effects, the domain
  with its storage or rendering. Code that braids them cannot be reasoned
  about in pieces.
- **Values, not mutation.** A value is immutable information. An identity
  is a named succession of values over time; its state is the value at a
  point in time. Model change as new values, never as in-place mutation.
- **Data-oriented.** Plain maps, vectors, and sets carry information at
  the edges and through the core. The boundary contract is data
  (docs/02); diagnostics are data (docs/04); generated Zig, specs, and
  signatures are data the core produces.

## Core vocabulary

Use these terms with the meanings below.

- **Simple / easy:** unbraided and objective, versus familiar and
  relative to a person. Choose simple.
- **Complect:** to interleave distinct concerns so they no longer
  separate. The thing to avoid.
- **Value / identity / state / time:** immutable information; a named
  series of values; the value now; the succession of values. Make time
  explicit by producing new values.
- **Code is data:** Clojure forms are Clojure data structures. This
  powers `defnz` and the `z`-forms, structural editing, and the
  data-driven boundary. Use it deliberately, not for cleverness.

## The spec is JVM Clojure

- Canonical JVM Clojure defines correct behavior. Follow the community
  Clojure style guide where a norm exists; do not invent house style
  where one already does.
- Zigar's public surface is ordinary Clojure: a `defnz` form defines a
  normal Var, returns Clojure values, and redefines like `defn`
  (docs/04). Match that expectation; surprising semantics at the Clojure
  surface are findings.

## Functional core, imperative shell (ADR 16)

Zigar's spine separates deciding from doing. This is the project's
load-bearing structure, not a preference.

- **Pure core:** signature parsing, type normalization, spec
  construction, source generation, hashing. These take data and return
  data, do no IO, never shell out, and hold no clock, thread, or atom.
  `zig/normalize-signature`, `zig/normalize-type`, and
  `zig/generate-source` are the canonical examples (docs/05).
- **Shell:** compiler invocation, filesystem writes to the artifact
  cache, library loading through FFM, Var rebinding, diagnostic
  rendering. The shell adapts inputs to data, calls the core, and applies
  the result as effects. It switches on values the core returns and
  carries no logic of its own.
- Most tests target the core directly, with data in and data out; shell
  tests are few and integration-style. A shell branch a test wants to
  reach is a decision that belongs in the core: move it, do not mock the
  shell. Do not push an effect into the core because a library makes it
  easy.

## Tooling and workflow

- `deps.edn` and the Clojure CLI for dependencies and configuration, not
  Leiningen.
- REPL-driven development is the primary loop: evaluate small forms,
  inspect values, adjust, repeat. Confirm affected paths in the REPL
  before treating work as done, including `defnz` redefinition, which is
  a core feature to exercise live rather than only in tests.
- Keep forms well-structured for structural editing: balanced parens,
  idiomatic indentation, trailing parens gathered.
- Prefer `tap>` over `println` for inspecting values during development;
  it reaches a tap listener without disturbing control flow.
- Dynamic namespace manipulation (`require`, `in-ns`, `refer`) is for the
  REPL, never inside functions or production paths.

## `ns` form and namespace structure

- kebab-case namespaces under the `zigar` root: `zigar.signature`,
  `zigar.source`. Exactly one namespace per file, one file per namespace.
  Public API at the top, `defn-` helpers below.
- Start each file with one `ns` form: `:require` before `:import`.
  `:require :as` over `:refer [...]` over `:refer :all`; avoid `:use`.
  Sort entries alphabetically.
- Use idiomatic aliases and the same alias for a namespace across the
  project: `str` for `clojure.string`, `set` for `clojure.set`, `io` for
  `clojure.java.io`.

## Formatting and layout

- 2-space indentation, no tabs. Indent form bodies by 2 spaces.
- Align `let` bindings and map values vertically:

  ```clj
  (let [thing1 "some"
        thing2 "other"]
    ...)

  {:name  "Bruce Wayne"
   :alias "Batman"}
  ```

- A space between a form and a preceding sibling, none after an opening
  bracket: `(foo (bar baz) quux)`.
- Keep lines short enough for side-by-side viewing; 80 columns is the
  target, 120 the ceiling.
- Gather trailing parens. One blank line between top-level forms, except
  tightly related `def`s. Avoid blank lines inside a `defn` body, except
  to group `cond` clauses.
- No commas in sequential literals: `[1 2 3]`, not `[1, 2, 3]`. Commas in
  maps are optional; keep them consistent within a map.

## Naming

- Predicates end in `?` (`valid?`, `enum-type?`). Effectful or mutating
  functions end in `!` (`recompile!`, `clean!`). Dynamic vars intended
  for rebinding wear earmuffs (`*config*`).
- Conversions use `->`, not `to`: `spec->source`, `user->row`. Unused
  bindings are `_` or `_`-prefixed (`_ctx`).
- Built-in Zig boundary types are keywords with exact Zig spelling
  (`:i64`, `:f64`, `:void`); compound types are vectors
  (`[:slice :const :u8]`). Keep that vocabulary exact (docs/03).
- Protocols, records, and types are CapitalCase.

## Data and domain modeling

- Default to plain maps with keyword keys for entities, options, and
  configuration. Reach for `defrecord` only for protocol dispatch, a Java
  interop boundary, or a measured hotspot, never to imitate a class.
  `defrecordz` is a separate thing, a boundary contract (docs/02).
- Model state as open data: accept and produce EDN-shaped values, keep
  the domain independent of any storage or rendering. Use persistent
  collections throughout the core; no mutable Java collections there.
- Use `clojure.spec` sparingly, at boundaries and for critical
  invariants, not across internal shapes that context already makes
  obvious. Zigar's specs carry the boundary contract.

## Collections and sequences

- Sequence library first: `map`, `filter`, `reduce`, `into`, `group-by`,
  `keep`, `frequencies`, `some`, `map-indexed` over manual `loop`/`recur`.
- `mapv`, `filterv`, `reduce-kv` when a vector is wanted or a map is
  iterated; `vec`, not `(into [] ...)`.
- Reach for transducers (`transduce`, `comp` of `map`/`filter`) when data
  volume is large or the transformation should be decoupled from its
  source and sink.
- Nil punning: `(when (seq coll) ...)`, not `(when-not (empty? coll)
  ...)`. Sets as predicates where natural: `(filter #{:a :e :i} xs)`.
- Vectors are the default collection in an API; maps carry entities and
  options; lists are for code and the rare data case.

## Functions and APIs

- Keep functions focused and readable. Factor a helper when one is doing
  too much, not to hit a line count.
- Avoid more than three or four positional parameters; carry the rest in
  an options map (`{:keys [...]}`). Use multi-arity for defaulting, with
  smaller arities calling the largest, ordered fewest to most.
- Use `:pre`/`:post` for critical invariants at public boundaries, not
  everywhere.
- Errors: throw `ex-info` with a rich data map (the Var, the signature,
  the offending slice) at boundaries; return explicit error values inside
  the pure core when callers branch on the outcome. Pick one per area; do
  not mix arbitrarily.
- Log at the edges, in the shell, with `clojure.tools.logging`; log
  readable Clojure (`pr-str`) so a line pastes back into the REPL. No
  logging in core pure functions, no test that depends on a log effect.
- Polymorphism: protocols for closed, type-based dispatch; multimethods
  for open, data-driven dispatch (on `:kind` or `:op`). Avoid `class`
  plus `cond` where either expresses the intent better.

## State, identity, and concurrency

- Reference types model identities and their state over time. Keep them
  at the edges: the artifact cache, loaded libraries, the Var registry.
- Atoms for uncoordinated synchronous updates to one identity, with a
  pure function under `swap!`. Refs and `dosync` for coordinated updates
  across identities, with a side-effect-free transaction body. Agents for
  asynchronous ordered updates. Dynamic vars for genuine dynamic
  configuration only, never as general mutable state.
- Core functions neither capture nor mutate a reference; they take values
  and return values. Design so state can be inspected and reproduced.

## Macros

- Write the function first. A macro is for genuine syntactic abstraction,
  never for a single call site or to save characters. Prefer a
  higher-order function where one suffices.
- `defnz` and its `z`-suffixed relatives are the project's deliberate
  macros, and they stay thin over the data functions (principle 7,
  docs/05): the macro parses a form into data and calls the pure core, so
  a user reaches the same core without the macro.
- Keep macro bodies small and data-oriented; deep macro logic is hard to
  reason about.

## Control flow idioms

- `when` for a one-armed `if`; `if-let`/`when-let` over `let` plus
  `if`/`when`; `if-not`/`not=` over a wrapped `not`.
- `cond` with short paired clauses and `:else`; `condp` when only the
  argument varies; `case` for compile-time constants.
- Threading macros over deep nesting:

  ```clj
  (->> (range 1 10)
       (filter even?)
       (map #(* 2 %)))
  ```

## Diagnostics are data

- A compile or validation failure is a structured diagnostic map, not a
  string: `{:level :error :error/code :zig/compile-failed :var ...
  :signature ... :zig/source-path ... :zig/stderr ...}` (docs/04 fixes
  the shape). Cores return or throw diagnostics; only the shell renders
  them for humans.
- Human rendering starts from Clojure (the Var and signature first), then
  the generated Zig path, then the Zig compiler output.

## Tests

- `clojure.test` under `test/zigar/<area>_test.clj`, with `deftest` names
  that describe the behavior. Test the pure core directly; never mock.
  Effects run against real scratch state: a /tmp cache dir, a scratch Zig
  compile.
- Many tests for the core, few for the shell. Reach for `test.check`
  properties on non-trivial core logic; a property failure is a real bug,
  fixed at the source with the shrunk case pinned as a regression.
- Edge cases are the point: nil, empty, single, boundary sizes, unsigned
  ranges beyond signed JVM bounds, malformed signatures, misplaced
  `:ret`, recompile after a body change, keep-last-good after a compile
  failure.

## What to avoid

- Imperative index-walking where sequence functions suffice.
- `def` inside functions; vars as hidden mutable state; business state in
  a singleton instead of values passed through the call chain.
- Macros where functions suffice; tacit `comp`/`partial`/`#()` chains
  that obscure intent.
- Writing Java in Clojure: mutable Java collections in the core, heavy
  `new`/`set!`. FFM and native calls are the shell's job, kept behind the
  boundary; the core stays plain data.
- Heavy dependencies. Prefer small, well-understood libraries and
  explicit composition; add a dependency only when the project already
  uses it or a requirement clearly demands it.

## Before writing code

Work the problem in data terms first, then write:

1. State the problem as entities (maps, vectors, sets) and how they
   change over time as events.
2. Sketch the data shapes with example literals for input, state, and
   output.
3. Design the pure core: the functions, their inputs and outputs, how
   they compose.
4. Design the shell: the IO boundaries and which identities hold state.
5. Check this file: namespaces and aliases, formatting and naming, maps
   over records, error handling, control flow, sequence use.
6. Provide sample data and tests for the core functions.

Think it through, then write; do not narrate the checklist in the code.

## Public-facing text

- Never describe code as "hand-written" or "hand-rolled" in user-facing
  docs, docstrings, or commit and changelog lines.
