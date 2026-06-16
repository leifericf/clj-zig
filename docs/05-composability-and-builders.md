# 05 - Composability and Builders

## Core requirement

Everything should be properly decomposed so the end result is composable.

`defnz` is the user-friendly surface, but it must not be a monolith. It should be syntax over smaller reusable pieces.

```text
form
  ↓
signature data
  ↓
normalized type data
  ↓
boundary spec
  ↓
generated source
  ↓
compiled artifact
  ↓
callable function
```

## Why data matters

The signature is ordinary Clojure data:

```clojure
[xs [:slice :const :f64]
 :ret :f64]
```

Because it is data, users can compose it with ordinary Clojure functions.

## Type builders

Users should be able to write builders like:

```clojure
(defn slice-of [t]
  [:slice t])

(defn const-slice-of [t]
  [:slice :const t])

(def bytes
  (const-slice-of :u8))
```

Then use them:

```clojure
(defnz count-byte
  [input bytes
   needle :u8
   :ret :usize]
  "...")
```

The core API should accept this because it accepts data, not opaque type objects.

## Macro generation

Users should be able to generate functions:

```clojure
(defmacro defbinaryz [name op t]
  `(defnz ~name
     [x ~t
      y ~t
      :ret ~t]
     ~(str "return x " op " y;")))
```

Usage:

```clojure
(defbinaryz add-f64 "+" :f64)
(defbinaryz mul-i64 "*" :i64)
```

## Lower-level functions

The macro layer should sit over data-level functions.

Possible functions:

```clojure
(zig/normalize-type [:slice :const :u8])
(zig/normalize-signature '[xs [:slice :const :u8] :ret :usize])
(zig/build-spec {...})
(zig/generate-source spec)
(zig/compile! spec)
(zig/load! artifact)
(zig/fn spec source)
```

Most users should not need these, but library authors and macro authors will.

## Destructuring as composition

Destructuring is a way to compose Clojure-shaped inputs with Zig-friendly native arguments.

```clojure
(defnz distance
  [{x1 :x y1 :y} {:x :f64 :y :f64}
   {x2 :x y2 :y} {:x :f64 :y :f64}
   :ret :f64]
  "...")
```

This composes three ideas:

- Clojure map-shaped inputs;
- signature data describing field types;
- generated native scalar arguments.

## Public data should stay public

Avoid making users construct internal classes or opaque type objects for public contracts.

Good:

```clojure
[:slice :const :u8]
```

Less good as the only public representation:

```clojure
(zig.types/slice (zig.types/const zig.types/u8))
```

Helpers may exist, but the canonical representation should remain data.
