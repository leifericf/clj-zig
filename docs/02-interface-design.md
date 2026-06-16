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
- `&` remains reserved for Clojure-style rest args if a future version supports them.
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

These forms are part of the ideal end-state, not necessarily the proof of concept.

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
