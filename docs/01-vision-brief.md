# 01 - Vision Brief

## One-sentence description

Zigar is an experiment/demo/proof-of-concept for a Clojure-first, data-oriented interface to ordinary Clojure functions backed by real Zig implementations.

## What Zigar is

Zigar is, for now:

- a proof-of-concept Clojure library;
- a REPL-native Zig function bridge;
- a data model for Clojure-to-Zig boundary contracts;
- a way to expose real Zig from Clojure without making users manage shared libraries directly;
- a foundation for exploring Clojure-side builders that compose Zig boundary types as data.

## What Zigar is not

Zigar is not:

- a new language;
- a Zig-like DSL;
- a Clojure implementation;
- a Zig implementation;
- a JVM replacement;
- a generic object bridge;
- a serializer for arbitrary Clojure data;
- a bidirectional runtime system in the proof of concept.

## Name

The working name is `Zigar`.

It comes from `Zigarette`: to a Clojure programmer, writing low-level native Zig inside a high-level Lisp workflow may feel a little dirty and unhealthy, like smoking.

It also plays on "close, but no cigar." The intended joke is inverted: Zigar is not about narrowly missing success, but narrowly achieving it. The demo should be small, sharp, and successful enough to prove the UX.

## Target users

Primary users:

- Clojure programmers who need selected native-speed functions;
- Clojure library authors building high-performance kernels;
- data-oriented Clojure programmers who want native contracts as data;
- Zig programmers who want a Clojure-hosted REPL workflow;
- tool builders who want to generate native kernels from Clojure data.

## Core thesis

Clojure and Zig have complementary strengths:

```text
Clojure: data, macros, homoiconicity, REPL, composition, host orchestration
Zig: explicit types, layout, allocators, comptime, C interop, performance
```

Zigar's experimental bet is that the seam between them should be a small, explicit, Clojure-data boundary contract.

## Product stance

The experience should not be:

```clojure
(compile-zig ...)
(load-shared-library ...)
(lookup-symbol ...)
(invoke-native ...)
```

The experience should be:

```clojure
(defnz add
  [x :i64
   y :i64
   :ret :i64]
  "return x + y;")

(add 1 2)
```

The user should think:

```text
I defined a Clojure function whose implementation happens to be Zig.
```

Not:

```text
I manually compiled and loaded a native library.
```

## Proof-of-concept success criteria

Zigar succeeds if:

1. `defnz` feels like a natural relative of `defn`.
2. Zig types are explicit where the native boundary needs them.
3. The signature is ordinary Clojure data.
4. Zig remains real Zig, not a weakened DSL.
5. Re-evaluating forms at the REPL feels like redefining functions.
6. Generated Zig and normalized specs are inspectable.
7. The system is decomposed enough for users to build higher-level Clojure abstractions on top.
