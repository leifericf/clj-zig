# Installation and Distribution

clj-zig serves two roles: the author who writes native functions, and the
consumer who depends on a library built with them. Each adds a dependency
and runs. This page covers both, plus the toolchain bootstrap and the one
JVM flag native access requires.

## The one irreducible step: native access

The finalized Foreign Function & Memory API (JEP 454) is a restricted API.
Calling native code needs the JVM option
`--enable-native-access=ALL-UNNAMED`. A running JVM cannot grant itself
native access, so clj-zig cannot remove this step; it is one line in an
alias. Without it, clj-zig reports `:clj-zig/native-access-disabled` naming
the flag, in place of the raw FFM error.

```clojure
{:aliases {:dev {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

## Consumer: depend on a library and call it

A library built with clj-zig ships its native code precompiled, as
classpath resources in its jar (ADR 31). A consumer needs no Zig toolchain
and no build step.

1. Add the library coordinate to `deps.edn`.
2. Add `--enable-native-access=ALL-UNNAMED` to the run alias.
3. Require the namespace and call its functions.

At load, each function resolves its baked library from the classpath by
target, namespace, name, and content hash, extracts it into the local
cache, and binds it. Zig is never invoked. The content hash uses the pinned
Zig version, not a live `zig version`, so the consumer reproduces the hash
the author baked under without a toolchain. A platform the author did not
bake is a clean miss: with a toolchain present it compiles locally,
otherwise it reports the miss rather than loading the wrong library.

## Author: write functions, then bake and publish

An author develops in the REPL, where each `defnz` compiles for the host
and redefines live. Releasing turns those functions into the resources a
consumer loads.

1. Write a namespace of `defnz` functions.
2. Bake: cross-compile each function for the target matrix into the
   resource tree.
3. Jar the sources and the baked resources, and deploy.

`clj-zig.bake/bake!` is the bake step, callable from tools.build or
`clj -X`:

```clojure
(clj-zig.bake/bake! {:ns 'com.example.widgets/native :out "resources"})
```

It loads the namespace, enumerates its established functions, and compiles
each for the matrix into `resources/clj-zig/native/<target>/<ns>/<name>-<hash>/`.
`examples/build.clj` shows bake, jar, and deploy with tools.build.

The default matrix is seven targets: Linux x86_64 and aarch64 (glibc),
Linux x86_64 (musl), macOS x86_64 and aarch64, and Windows x86_64 and
aarch64. A function that links a third-party C library beyond libc and libm
is baked for the host only, logging which targets it skipped; pure Zig,
libc, and libm functions cross-compile for the whole matrix.

## The Zig toolchain bootstrap

An author needs a `zig` compiler; clj-zig resolves it through one seam, in
order (ADR 30):

1. An explicit override: the `clj-zig.zig` system property or the
   `CLJ_ZIG_ZIG` environment variable, pointing at a `zig` executable.
2. A `zig` on the PATH.
3. A pinned Zig fetched once into `.clj-zig/zig/<version>/` and reused.

A developer who already has Zig sees no download. On a machine with no Zig,
the first compile fetches the pinned release for the host, verifies it
against the checksum the Zig release index publishes, and caches it. The
pinned version is the one the generated wrappers assume, so the
`zig-version` in every content hash is identical across machines.

## Depending on clj-zig

Until clj-zig is published to Clojars, depend on it from git, pinning a
commit:

```clojure
{:deps {io.github.leifericf/clj-zig {:git/sha "<commit-sha>"}}
 :aliases {:dev {:jvm-opts ["--enable-native-access=ALL-UNNAMED"]}}}
```

The git-dep path stays available for contributors after a release. A
published release is built and deployed with the project's `build.clj` to
Clojars under the coordinate `com.leifericf/clj-zig`. Releases are dated: the
version and the git tag are both `YYYY.MM.DD`, with a `-alphaN` qualifier
while the project is a proof of concept. The git coordinate is
`io.github.leifericf/clj-zig`; the Clojars coordinate is
`com.leifericf/clj-zig`.

## Requirements summary

- Java 22 or newer, for the finalized FFM API. The only JVM flag is
  `--enable-native-access=ALL-UNNAMED`.
- The Clojure CLI and `deps.edn`.
- Zig 0.16.0 for an author. A consumer of a baked library needs no Zig.
