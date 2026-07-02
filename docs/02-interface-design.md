# 02 - Interface Design

## Naming rule

Zig-aware defining forms append `z` to the Clojure defining form they mirror.

```clojure
defn       -> defnz
def        -> defz
defrecord  -> defrecordz
deftype    -> deftypez
defenum    -> defenumz
```

This keeps the Clojure concept recognizable and marks Zig participation consistently.

## Primary form: `defnz`

`defnz` defines a normal Clojure Var/function backed by Zig.

```clojure
(defnz add
  "Adds two signed integers."
  [x :i64
   y :i64
   :ret :i64]
  "return x + y;")
```

It should preserve the familiar `defn` rhythm:

```clojure
(defnz name
  docstring?
  attr-map?
  signature-vector
  zig-body)
```

The signature vector is the full boundary contract.

## Signature vector

The signature vector contains argument binding/type pairs and a final return marker.

```clojure
[arg type
 arg type
 :ret return-type]
```

Example:

```clojure
[x :i64
 y :i64
 :ret :i64]
```

Rules:

- `:ret` is required.
- `:ret` must be at the end.
- Nothing appears after the return type.
- `&` introduces rest arguments, lowering to a trailing const slice (ADR 42).
- Types are data, not resolved symbols.

The `:ret` marker deliberately resembles keyword markers in Clojure binding forms, such as `:as` in destructuring.

## Zig body

The body is real Zig source, not a DSL.

```clojure
(defnz sum-f64
  [xs [:slice :const :f64]
   :ret :f64]
  "
  var total: f64 = 0;
  for (xs) |x| {
      total += x;
  }
  return total;
  ")
```

The generated wrapper provides ergonomic Zig locals such as `xs: []const f64`. Inside the body, Zig should be free to use normal Zig features.

A body may instead live in a real `.zig` file, named with a `{:zig/file "name.zig"}` descriptor in place of the string. The file holds a complete Zig function the generated wrapper calls, keeping editor and `zig fmt` support; the same descriptor can link C libraries so the body may `@cImport` a C header. See [ADR 26](adr/26-external-zig-source-files.md) and [ADR 27](adr/27-compile-options-c-interop.md).

## Bodyless functions and the namespace file

The body may be omitted entirely. A `defnz` with a signature but no body takes its body from the `pub fn` of the same name in the `.zig` file co-located with the namespace's source, the same stem as the `.clj`:

```clojure
;; body: the pub fn hypotenuse in app/geometry.zig, beside app/geometry.clj
(defnz hypotenuse
  [a :f64
   b :f64
   :ret :f64])
```

The Clojure namespace is the Zig namespace: shared imports, helpers, and types live once in that file, and `zig-deps` declares the namespace's C link flags. A kebab-case name maps to its snake_case `pub fn`. An optional `//! clj-zig: <ns>` first-line header asserts the file belongs to the namespace. A body file may `@import` sibling and subdirectory `.zig` files. See [ADR 28](adr/28-namespace-as-zig-namespace.md) and [ADR 29](adr/29-multi-file-zig-imports.md).

The signature may be omitted as well:

```clojure
;; signature inferred from app/geometry.zig's pub fn hypotenuse
(defnz hypotenuse)
```

A Zig type fixes the shape of each argument and the return, so the boundary contract is read straight from the `pub fn` prototype. Policy that a Zig type cannot express is the exception: a returned `[]T` carries no ownership rule and a returned `*T` is a pointer or an opaque handle by the caller's choice, so a function returning either needs an explicit signature stating `[:owned ...]` or `[:handle ...]`. Inferring one reports `:clj-zig/contract-policy-needed` instead of guessing.

## `defz`

`defz` defines Zig-side declarations that are not directly Clojure-callable.

```clojure
(defz helpers
  "
  fn clamp(x: f64, lo: f64, hi: f64) f64 {
      return if (x < lo) lo else if (x > hi) hi else x;
  }
  ")
```

Mental model:

```text
defz   stays inside Zig
defnz  crosses the Clojure/Zig boundary
```

## Shared type forms

`deftypez` defines a Zig-compatible boundary type.

```clojure
(deftypez Point
  [x :f64
   y :f64])
```

`defrecordz` defines both a Clojure record and a Zig-compatible layout contract.

```clojure
(defrecordz Point
  "A 2D point shared between Clojure and Zig."
  [x :f64
   y :f64])
```

`defenumz` defines an enum bridge.

```clojure
(defenumz ParseStatus
  [ok 0
   invalid 1
   eof 2])
```

## Return values

The return type is part of the signature contract.

```clojure
:ret :i64   ;; returns a Clojure integer
:ret :f64   ;; returns a Double
:ret :bool  ;; returns a Boolean
:ret :void  ;; returns nil
```

Composite returns require explicit layout and ownership rules.

```clojure
(defrecordz Point
  [x :f64
   y :f64])

(defnz midpoint
  [a Point
   b Point
   :ret Point]
  "...")
```

If `Point` is a `defrecordz`, returning `Point` should produce a normal Clojure record.

## Destructuring

Clojure destructuring is relevant, but should happen on the Clojure side before the native call.

```clojure
(defnz distance
  [{x1 :x y1 :y} {:x :f64 :y :f64}
   {x2 :x y2 :y} {:x :f64 :y :f64}
   :ret :f64]
  "
  const dx = x1 - x2;
  const dy = y1 - y2;
  return @sqrt(dx * dx + dy * dy);
  ")
```

This lets Clojure accept map-shaped values while Zig receives native scalar values.
