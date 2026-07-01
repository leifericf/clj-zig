# Architecture Decision Records

Decisions made while building clj-zig, newest-numbered last. Each record
is one screenful: context, decision, consequences, alternatives. A
decision stands until a later record supersedes it by name.

ADR 01-17 are the founding decisions, migrated from the dossier; the
design principles they serve remain in
[`../07-design-principles-and-decisions.md`](../07-design-principles-and-decisions.md).
Later ADRs capture decisions made during development. Written via the
`record-decision` skill.

| ADR | Decision |
|-----|----------|
| [01](01-name-zigar.md) | Name the experiment Zigar |
| [02](02-clojure-library-not-language.md) | Build clj-zig as a Clojure library, not a new language |
| [03](03-defnz-primary-form.md) | defnz is the primary function form |
| [04](04-z-suffix-forms.md) | Zig-aware defining forms use a z suffix |
| [05](05-return-in-signature-vector.md) | Return type lives in the signature vector |
| [06](06-ret-required-and-final.md) | :ret is required and final |
| [07](07-zig-types-as-keywords.md) | Built-in Zig types are keywords |
| [08](08-compound-types-as-vectors.md) | Compound types are vector data |
| [09](09-no-zig-internals-in-contract.md) | The boundary contract does not model Zig internals |
| [10](10-one-directional-interop.md) | Start with one-directional interop |
| [11](11-keep-last-good.md) | Keep the last good implementation after a compile failure |
| [12](12-content-addressed-artifacts.md) | Generated artifacts are content-addressed |
| [13](13-destructuring-clojure-side.md) | Destructuring happens on the Clojure side |
| [14](14-records-need-bridge.md) | Records require explicit bridge definitions (scalar, buffer, and string fields) |
| [15](15-public-specs-are-data.md) | Public specs remain ordinary data |
| [16](16-functional-core-imperative-shell.md) | Functional core, imperative shell |
| [17](17-explicit-contracts-over-marshalling.md) | Explicit boundary contracts over data marshalling |
| [18](18-carrier-and-unsigned-policy.md) | Boundary carriers and the unsigned-return policy |
| [19](19-error-union-boundary-semantics.md) | Error-union boundary semantics (scalar, void, enum, or struct value) |
| [20](20-enum-boundary-semantics.md) | Enum boundary semantics |
| [21](21-owned-and-borrowed-returns.md) | Owned and borrowed returns copy into Clojure (slices and records) |
| [22](22-opaque-handles.md) | Handles are opaque tagged pointers the caller frees |
| [23](23-generative-test-strategy.md) | Generative and exhaustive testing over the example suite |
| [24](24-link-libc-in-every-library.md) | Every compiled library links libc |
| [25](25-rename-to-clj-zig.md) | Rename the project to clj-zig |
| [26](26-external-zig-source-files.md) | A defnz body may be sourced from a Zig file |
| [27](27-compile-options-c-interop.md) | Compile options carry C-interop flags |
| [28](28-namespace-as-zig-namespace.md) | A Clojure namespace is a Zig namespace |
| [29](29-multi-file-zig-imports.md) | Multi-file Zig imports copy the import closure into the build |
| [30](30-bootstrap-the-zig-toolchain.md) | Bootstrap the Zig toolchain |
| [31](31-distribute-precompiled-artifacts.md) | Distribute precompiled artifacts, bake the cache |
| [32](32-generate-zig-source-not-a-lower-ir.md) | Generate Zig source, not a lower IR |
| [33](33-compile-out-of-process-warm-not-embed.md) | Compile out of process, warm the compiler rather than embed it |
| [34](34-external-zig-module-dependencies.md) | Depend on external Zig modules by name |
| [35](35-persistent-zig-global-cache-dir.md) | Give Zig a persistent global cache directory |
| [36](36-pinned-module-local-checkout.md) | A pinned module may carry a local checkout path |
| [37](37-foreign-function-toolkit.md) | A foreign-function toolkit for prebuilt libraries |
| [38](38-synchronous-upcall-stubs.md) | Synchronous upcall stubs are a supported primitive |
| [39](39-scalar-hot-path-call-mode.md) | A scalar-only hot-path call mode for defnz |
| [40](40-namespaces-divide-by-domain.md) | Namespaces divide by domain |
| [41](41-per-function-optimize-mode.md) | A per-function optimize mode for defnz |
| [42](42-rest-arguments-lower-to-const-slice.md) | Rest arguments lower to a trailing const slice |
