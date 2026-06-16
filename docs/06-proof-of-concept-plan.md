# 06 - Proof-of-Concept Plan

## Goal

Build the smallest useful proof of the Clojure developer experience.

```text
defnz scalar function -> compile Zig -> load native artifact -> call from Clojure
defnz slice function  -> pass primitive array/buffer -> read or mutate in Zig
```

## Non-goals

The proof of concept will not include:

- Zig calling Clojure;
- embedded JVM from Zig;
- arbitrary Clojure data marshalling;
- owned native memory returns;
- native resource handles;
- full `defrecordz`;
- full `defenumz`;
- production AOT packaging;
- cross-platform polish beyond one development target.

## Deferred directions

These ideas came out of the original Clojure/Zig discussion. They remain interesting but sit beyond the proof of concept. They are recorded so they are not lost, not scheduled.

- Bidirectional interop: Zig calling back into Clojure, making the relationship two-way rather than Clojure-to-Zig only.
- Embedded Clojure from Zig: Zig hosting a JVM and the Clojure runtime to invoke Clojure functions through JNI or a similar interface.
- Code as data across the boundary: passing Clojure code representations to an embedded Clojure runtime for evaluation, leaning on homoiconicity. Conceptually possible, but considerably more complex than ordinary function calls.
- Richer data protocol: a serialization format or a negotiated interop protocol for exchanging rich, nested Clojure structures, should a concrete need outgrow explicit per-function contracts. See DEC-017.

## Required interface

Authoring:

```clojure
defnz
defz
```

Inspection:

```clojure
zig/signature
zig/spec
zig/source
zig/generated-source
zig/library
zig/recompile!
zig/explain
```

Pure data functions:

```clojure
zig/normalize-type
zig/normalize-signature
zig/generate-source
```

## Implementation phases

1. Clojure project skeleton.
2. Signature parser and normalizer.
3. Type parser and normalizer.
4. `defnz` macro over normalized specs.
5. Zig source generation for scalar functions.
6. Zig compiler invocation.
7. Content-addressed artifact cache.
8. Native loading through JDK FFM/Panama.
9. Scalar invocation.
10. Const and mutable slice invocation.
11. REPL inspection helpers.
12. Structured diagnostics.

## Suggested repository layout

```text
zigar/
  deps.edn
  README.md
  src/
    zigar/
      core.clj
      signature.clj
      type.clj
      spec.clj
      source.clj
      compile.clj
      cache.clj
      ffm.clj
      diagnostics.clj
      inspect.clj
  test/
    zigar/
      signature_test.clj
      type_test.clj
      source_test.clj
      scalar_test.clj
      slice_test.clj
      diagnostics_test.clj
  examples/
    scalar.clj
    slices.clj
```

## Acceptance tests

Signature:

- Parses scalar signatures.
- Requires final `:ret`.
- Rejects misplaced `:ret`.
- Preserves argument binding names.

Types:

- Normalizes scalar Zig keyword types.
- Normalizes const slices.
- Normalizes mutable slices.
- Rejects unknown types.
- Rejects malformed compound vectors.

Generation:

- Generates readable Zig source.
- Generates stable symbol names.
- Generates pointer-plus-length wrappers for slices.

Compilation:

- Compiles a scalar function.
- Writes generated source and manifest.
- Reuses cached artifacts when unchanged.
- Rebuilds when the body changes.

Invocation:

- Calls `:i64` functions.
- Calls `:f64` functions.
- Converts `:void` to `nil`.
- Passes read-only primitive arrays as const slices.
- Passes mutable primitive arrays as mutable slices.

Diagnostics:

- Reports Zig compiler errors with generated source path.
- Keeps last good binding after failed redefinition.
- Exposes structured diagnostic data.

## Quality bar

Prioritize:

- Clojure-like UX;
- data-oriented contracts;
- decomposition and composability;
- REPL resilience;
- excellent errors;
- inspectability;
- narrow native boundary.

Avoid:

- hiding Zig types;
- generic object marshalling;
- premature bidirectional runtime features;
- macro complexity that belongs in pure functions;
- overfitting the first implementation to one example.
