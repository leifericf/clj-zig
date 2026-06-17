# 04 - REPL and Execution Model

## Goal

The workflow should feel like this:

```clojure
(defnz add
  [x :i64
   y :i64
   :ret :i64]
  "return x + y;")

(add 1 2)
;; => 3
```

Then edit and re-evaluate:

```clojure
(defnz add
  [x :i64
   y :i64
   :ret :i64]
  "return x + y + 10;")

(add 1 2)
;; => 13
```

From the user's perspective, this is normal Clojure function redefinition.

## Execution phases

```text
1. Evaluate Clojure form
2. Parse defnz shape
3. Normalize signature data
4. Validate boundary contract
5. Generate Zig source
6. Hash source/spec/options
7. Resolve a cached or baked library, or compile one
8. Load library
9. Bind native symbol
10. Rebind Clojure Var
11. Invoke through coercion layer
```

## Content-addressed artifacts

JVM native library unloading is awkward. Recompiled forms should produce fresh artifact names.

Example layout:

```text
.clj-zig/cache/
  macos-aarch64/
    app.core/add-83a1c0/
      source.zig
      libadd-83a1c0.dylib
      manifest.edn
```

The hash should include:

- normalized signature;
- Zig body;
- dependent `defz` declarations;
- type definitions;
- compile options;
- the pinned Zig version;
- target platform.

The Zig version in the hash is the pinned version, not a live `zig version`,
so every machine that pins the same toolchain produces the same hash for the
same form. Resolution at step 7 has three steps: a present library in the
filesystem cache, then a baked library on the classpath (extracted into the
cache and loaded, never compiling), then a fresh compile. A library shipped
in a jar lays its baked artifacts out under `clj-zig/native/` mirroring this
cache, so a consumer loads precompiled code without a toolchain. See
[ADR 31](adr/31-distribute-precompiled-artifacts.md) and
[ADR 30](adr/30-bootstrap-the-zig-toolchain.md).

## Failure behavior

On compile failure:

- report the error immediately;
- show the Clojure Var/form first;
- show the generated Zig file path;
- show Zig compiler output;
- keep the last good function bound if one exists;
- attach failed-attempt metadata for inspection.

This preserves exploratory REPL work. A failed experiment should not destroy the running system.

## Inspection helpers

Everything should hang off Vars.

```clojure
(zig/source #'add)
(zig/signature #'add)
(zig/spec #'add)
(zig/generated-source #'add)
(zig/library #'add)
(zig/symbol #'add)
(zig/status #'add)
(zig/recompile! #'add)
(zig/explain #'add)
(zig/clean!)
```

`zig/source` returns the Zig body as written. `zig/generated-source` returns the full wrapper clj-zig generates around it.

## Diagnostic shape

Diagnostics should be data.

```clojure
{:level :error
 :error/code :zig/compile-failed
 :message "Could not compile defnz app.core/add."
 :var 'app.core/add
 :signature '[x :i64 y :i64 :ret :i64]
 :zig/source-path ".clj-zig/cache/.../source.zig"
 :zig/stderr "..."
 :zig/exit-code 1}
```

Human rendering should start from Clojure:

```text
Could not compile defnz app.core/add

Signature:
  [x :i64
   y :i64
   :ret :i64]

Generated Zig:
  .clj-zig/cache/macos-aarch64/app.core/add-83a1c0/source.zig

Zig error:
  source.zig:7:12: error: expected type 'i64', found '[]const u8'
```

## Proof-of-concept implementation mode

The proof-of-concept mode should be:

```clojure
{:compile :on-eval
 :on-error :keep-last-good}
```

Future modes may include lazy compilation and ahead-of-time compilation.
