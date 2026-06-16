# 07 - Design Principles and Decisions

## Design principles

1. Clojure is the host experience.
2. Zig is the implementation language inside the native boundary.
3. The boundary contract is data.
4. Use exact Zig type names as Clojure keywords.
5. Preserve `defn` shape as much as possible.
6. Keep signatures decomposable and composable.
7. Macros should be thin over data functions.
8. Do not hide ownership or lifetime.
9. Keep Zig free inside the contract.
10. REPL redefinition is core, not an afterthought.
11. Generated source must be inspectable.
12. Diagnostics are structured data.
13. Prefer explicit boundary contracts over magical marshalling.
14. Start one-directional: Clojure calls Zig.
15. Functional core, imperative shell: normalize and generate as data; compile and load at the edge.

## DEC-001 - The experiment is named Zigar

Decision: Use `Zigar` as the working experiment name.

Rationale: `Zigarette` captures the joke that low-level native code may feel dirty and unhealthy to a Clojure programmer, but `Zigar` is shorter and cleaner. It also plays on "close, but no cigar," inverted as narrowly achieved success: the demo should prove a small UX slice rather than claim a whole platform.

## DEC-002 - Zigar is not a new language

Decision: Build Zigar as a Clojure library and boundary-contract system, not a new Lisp or Zig dialect.

Rationale: The value is in Clojure-native authoring plus real Zig execution. A new language would weaken both sides.

## DEC-003 - `defnz` is the primary function form

Decision: Use `defnz` for Clojure-callable Zig-backed functions.

Rationale: It reads as `defn` plus Zig and keeps the mental model anchored in normal Clojure function definition.

## DEC-004 - Zig-aware defining forms use a `z` suffix

Decision: Prefer `defnz`, `defrecordz`, `deftypez`, and `defenumz` over `defzig`, `defzrecord`, or `defztype`.

Rationale: The root Clojure concept stays recognizable, and the suffix marks Zig participation consistently.

## DEC-005 - Return type lives in the signature vector

Decision: Put return contracts at the end of the signature vector with `:ret`.

Rationale: The whole boundary contract stays in one Clojure data structure, while `:ret` resembles keyword markers already used in binding/destructuring forms.

## DEC-006 - `:ret` is required and final

Decision: Require `:ret` in the proof of concept and allow nothing after it.

Rationale: Native boundaries need explicit return contracts. Making the marker final keeps parsing simple and signatures readable.

## DEC-007 - Built-in Zig types are keywords

Decision: Represent built-in Zig types as keywords using exact Zig spelling, such as `:i64`, `:f64`, and `:void`.

Rationale: Keywords are idiomatic Clojure data and avoid symbol resolution ambiguity, while exact Zig names avoid type confusion.

## DEC-008 - Compound types are vector data

Decision: Represent compound boundary types as vectors, such as `[:slice :const :u8]`.

Rationale: Vector forms are plain data, easy to compose, easy to validate, and friendly to Clojure-side type builders.

## DEC-009 - The boundary contract does not model Zig internals

Decision: The Clojure signature describes only inputs, outputs, ownership, and lifetime at the boundary.

Rationale: Zig should remain free to use allocators, comptime, pointers, packed structs, SIMD, C imports, and target-specific optimization internally.

## DEC-010 - Start with one-directional interop

Decision: The proof of concept supports Clojure calling Zig, not Zig calling Clojure.

Rationale: One-directional interop is enough to prove the user experience and avoids the complexity of callbacks, embedded JVMs, and cross-runtime lifecycle issues.

## DEC-011 - Keep last good implementation after compile failure

Decision: If re-evaluating `defnz` fails to compile, preserve the previous working function when available.

Rationale: This matches the exploratory REPL workflow. A failed experiment should not unnecessarily break the running system.

## DEC-012 - Generated artifacts are content-addressed

Decision: Hash normalized specs, source, options, target, and Zig version into artifact paths.

Rationale: JVM native library unloading is difficult. Fresh artifact names make redefinition reliable and cacheable.

## DEC-013 - Destructuring happens on the Clojure side

Decision: Clojure destructuring may be used in signatures, but it lowers values before crossing the native boundary.

Rationale: This preserves Clojure ergonomics without forcing Zig to understand arbitrary Clojure data.

## DEC-014 - Records require explicit bridge definitions

Decision: Clojure records participate through `defrecordz`, not automatic conversion of arbitrary records.

Rationale: Zig structs need known field order, field types, alignment, layout, and ownership rules.

## DEC-015 - Public specs remain ordinary data

Decision: Do not require opaque type objects for public type contracts.

Rationale: Plain data enables composition, macro generation, validation, visualization, and user-defined builders.

## DEC-016 - Functional core, imperative shell

Decision: Signature parsing, type normalization, spec construction, and source generation are pure data transformations. Compiler invocation, filesystem writes, library loading, and Var rebinding are shell operations.

Rationale: Pure cores are easier to test, compose, reuse, and inspect. The shell contains unavoidable side effects.

## DEC-017 - Explicit boundary contracts over data marshalling

Decision: Cross the boundary through explicit, per-function boundary contracts rather than serializing whole Clojure structures (EDN, JSON, or a custom binary format) or defining a general interop protocol that both runtimes negotiate.

Rationale: Serialization and a general protocol both push rich, arbitrary Clojure data across the seam, reintroducing the marshalling cost and ambiguity Zigar exists to avoid. Explicit contracts keep the seam small, inspectable, and composable. A serialization format or richer protocol remains an open option for data that genuinely needs it, deferred until a concrete need appears.
