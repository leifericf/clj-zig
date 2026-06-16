# 03 - Boundary Contract

## Purpose

The boundary contract says how values cross between Clojure and Zig.

It defines:

- argument names;
- argument boundary types;
- return type;
- mutability;
- ownership;
- lifetime;
- error behavior.

It does not define how Zig computes internally.

## Data-oriented type vocabulary

Built-in Zig types use exact Zig names as Clojure keywords.

```clojure
:i8 :i16 :i32 :i64 :i128
:u8 :u16 :u32 :u64 :u128
:isize :usize
:f16 :f32 :f64 :f80 :f128
:bool
:void
:noreturn
```

This avoids ambiguity with JVM/Clojure names such as `long`, `double`, `boolean`, or `nil`.

## Compound types

Compound boundary types are vectors.

```clojure
[:ptr T]
[:ptr :const T]
[:manyptr T]
[:manyptr :const T]
[:slice T]
[:slice :const T]
[:array n T]
[:optional T]
[:error-union E T]
[:owned T]
[:borrowed T]
[:handle T]
```

Examples:

```clojure
[:slice :const :u8]
[:slice :f64]
[:ptr :const :i64]
[:array 4 :f32]
[:owned [:slice :u8]]
[:handle Parser]
```

The core contract should remain plain data. Helper functions may build these forms, but the forms themselves should not be opaque objects.

## Destructured argument shapes

Destructured arguments pair a map binding with a field-map type, such as `{:x :f64 :y :f64}`. The field-map describes Clojure-side fields and lowers to native scalars before the call. It is a Clojure-side input shape, not a Zig boundary type. See Interface Design for the destructuring form.

## Mutability

Mutability is explicit.

```clojure
[:slice :const :u8]
```

means Zig receives a read-only slice.

```clojure
[:slice :u8]
```

means Zig receives a mutable slice and may mutate elements during the call.

## Lifetime rules

Proof-of-concept lifetime rules should be conservative:

- scalars are copied;
- slices are valid only during the call;
- mutable slices may be mutated during the call;
- Zig must not retain Clojure-owned pointers after return;
- `:void` returns `nil`;
- returned native memory must be explicitly owned, copied, or wrapped.

## Zig freedom

The boundary contract belongs at the edge. Inside that edge, Zig is free.

Zig implementations may use:

- comptime;
- custom allocators;
- stack allocation;
- arena allocation;
- SIMD;
- raw pointers;
- packed structs;
- target-specific code;
- C imports;
- assembly;
- specialized data structures.

The Clojure side should not try to model these internals.

## Return conversion

Scalar returns are direct:

```clojure
:i64   ;; Clojure integer, usually Long when in range
:f64   ;; Double
:bool  ;; Boolean
:void  ;; nil
```

Unsigned integer overflow beyond signed JVM ranges needs an explicit policy before implementation.

Composite returns need layout and ownership metadata.

```clojure
:ret Point
```

may produce a Clojure record if `Point` was declared with `defrecordz`.

Owned returns are explicit:

```clojure
:ret [:owned [:slice :u8]]
```

They must define who frees memory and whether Clojure copies or wraps it.

## Normalized contract

The public signature:

```clojure
[xs [:slice :const :f64]
 :ret :f64]
```

should normalize to data such as:

```clojure
{:args [{:binding 'xs
         :type [:slice :const :f64]}]
 :ret :f64}
```

Type forms should also normalize:

```clojure
[:slice :const :u8]
```

to:

```clojure
{:kind :slice
 :const? true
 :of {:kind :scalar :name :u8}}
```

These normalized forms should be inspectable and reusable by advanced users.
