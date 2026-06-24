# ADR 37: A foreign-function toolkit for prebuilt libraries

Date: 2026-06-24

## Context

clj-zig's everyday path compiles a Zig body and derives the boundary
carriers from a signature vector (`defnz`, ADR 3/5). A C library that
clj-zig itself compiled can already be reached from a body via `@cImport`
and `:c/link` (ADR 27).

But a real program also calls libraries it did NOT compile and that have
no Zig body to wrap: the platform's windowing or input library, a system
framework, libc, the graphics loader. These expose a flat C ABI, with no
Zig source and no signature spec to derive carriers from. The work to
bind one is always the same shape -- open the library, describe a
signature, bind a downcall handle, occasionally hand native code a
callback, read a bounded C string out of foreign memory, tear a
worker-driven native resource down in the right order. `clj-zig.ffm` does
this internally for the `defnz` case, but it is spec-shaped and private:
its `descriptor`/`bind` take a normalized boundary spec, not raw
`ValueLayout`s, so a consumer binding a prebuilt library cannot use it and
is left to re-derive the FFM plumbing by hand.

Re-deriving it by hand is both wasted work and a hazard: the per-frame
caching discipline, the upcall-stub lifetime rules, and the bounded-read
guard against untrusted foreign memory are easy to get subtly wrong, and
the same near-identical glue ends up copied into every consumer.

## Decision

Publish a small, data-in/data-out foreign-function toolkit as a new public
namespace, `clj-zig.foreign`, for binding a prebuilt native library
alongside compiled Zig:

- `descriptor`, `linker`, and `c-byte`/`c-short`/`c-int`/`c-long`/
  `c-float`/`c-double`/`c-ptr` layout shorthands, so a caller describes a C
  signature as data without importing the FFM classes.
- `library-lookup` (open by path or name, bound to the global Arena) and
  `resolve-library` (a config map of env vars, candidate paths, and a
  default that resolves which path to open, as data).
- `find-symbol` and `symbol-present?`, which degrade a missing symbol as a
  tagged ex-info or a boolean rather than faulting on a null segment.
- `downcall`, which binds and CACHES a `MethodHandle` per distinct
  `[lookup name ret arg-layouts]`, so a per-frame caller does the symbol
  lookup, descriptor build, and link at most once; and `call`, the
  cold-path invoker for setup and teardown.
- `upcall-stub`, a synchronous callback primitive (ADR 38).
- `read-utf8-bounded`, a capped NUL-terminated read from untrusted foreign
  memory.
- `join-then-close-arena`, the load-bearing teardown order for a native
  resource driven on a worker thread.

The toolkit is imperative shell (ADR 16): it carries no domain knowledge,
and the values it returns are opaque handles (ADR 22) the caller threads
back into native calls, never dereferences.

## Consequences

A consumer binds a prebuilt library through one documented surface, with
the caching, lifetime, and bounded-read disciplines built in, instead of
re-deriving FFM glue per library. The `defnz` pipeline is unchanged;
`clj-zig.ffm` stays the spec-shaped internal binder for compiled bodies,
and `clj-zig.foreign` is the raw-signature toolkit for prebuilt ones. The
two share the finalized FFM API and the native-access requirement but not
code, because their inputs differ (a normalized spec versus raw
`ValueLayout`s).

The toolkit widens clj-zig's public surface and its compatibility
promise: these functions are now API. The surface was kept deliberately
small -- the primitives a consumer cannot avoid -- and each carries its
discipline in its docstring so the easy-to-get-wrong parts (per-frame
caching, stub lifetime, the untrusted-read cap) are not left to the
caller's memory.

## Alternatives

Generalize `clj-zig.ffm/bind` to also accept a raw signature. Rejected:
`bind` is shaped end to end around a normalized boundary spec (unsigned
return policy, error-union/owned/struct returns, handle records); bending
it to also take raw layouts would complect the compiled-body binder with
the prebuilt-library binder and serve neither cleanly.

Leave the plumbing to each consumer. Rejected: it is the same code every
time, and the disciplines it encodes are exactly the ones a hand-rolled
copy gets wrong.

A heavier binding generator (parse C headers, emit typed wrappers).
Rejected as far more than the problem needs: the consumers here bind a
handful of symbols with known signatures, for which a thin toolkit is
enough.
