# ADR 50: Streaming return names an iterator type and generates shims

Date: 2026-07-02

## Context

A function that returns a large or unbounded sequence cannot return a
full slice: the body allocates the whole thing, the wrapper copies it
out, and the caller holds it all in memory. Real native producers (a
parser yielding tokens, a database yielding rows, a sensor yielding
samples) need a streaming return: the body yields one element at a time
through an iterator protocol, and the caller drives the pull loop from
Clojure.

## Decision

A `[:stream <element-type> of <IterType>]` return declares a streaming
result. The named `<IterType>` is a `deftypez` carrying
`:clj-zig/iter {:next "next_fn_name" :deinit "deinit_fn_name"}`
metadata, naming the two `pub fn`s on the type that implement the
iterator protocol:

    (deftypez Counter
      [state :i64]
      {:clj-zig/iter {:next "counter_next" :deinit "counter_deinit"}})

    (defnz up-to
      [n :i64 :ret [:stream :i64 of Counter]]
      "var c = std.heap.c_allocator.create(Counter) catch @panic(\"oom\");\n
       c.state = 0;\nreturn c;")

The body returns `*IterType` (a handle to the initialized iterator). The
generated wrapper emits three exported symbols:

- `__iter_init`: calls the body, returns the iterator handle address.
- `__iter_next`: calls the type's `:next` fn, which returns an optional
  element (`?T`); nil means exhaustion.
- `__iter_free`: calls the type's `:deinit` fn to release the iterator's
  resources.

On the Clojure side, `invoke-stream` returns a `reduced`-aware
`IReduceInit`: a lazy driver that calls `__iter_next` in a loop, calling
`__iter_free` in a finally. The result integrates with `into`,
`sequence`, `transduce`, `comp`, and every transducer pipeline, so the
transducer surface (#10) is folded into streaming rather than a separate
form.

A `[:stream ...]` return is rejected in argument position: the boundary
is one-directional (ADR 10), and a stream argument would require the
Zig side to pull from a Clojure seq, which crosses the boundary in the
wrong direction.

## Consequences

A streaming return adds three exported symbols per function and one new
return kind in the spec validator. The FFM side gains a new invoke path
that owns its own arena lifetime (the iterator outlives any single call,
so the arena-pool feature pools confined arenas rather than scoping them
to the call). The transducer surface is a natural consequence of
implementing `IReduceInit`, not a separate form.

## Alternatives

A callback-based design (pass a Clojure fn as an upcall that the Zig side
calls per element) was rejected: it requires synchronous upcalls (ADR 38)
on the hot path, and the upcall overhead per element defeats the purpose
of streaming. A `defiterz` form separate from `deftypez` was rejected:
an iterator is a type with two methods, and a separate form duplicates
the type declaration. Lazy Clojure seqs wrapping a cursor were rejected:
`IReduceInit` is the idiomatic Clojure interface for a reducible source
and integrates with transducers for free.
