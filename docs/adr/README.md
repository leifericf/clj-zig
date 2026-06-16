# Architecture Decision Records

Decisions made while building Zigar, newest-numbered last. Each record
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
| [02](02-clojure-library-not-language.md) | Build Zigar as a Clojure library, not a new language |
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
| [14](14-records-need-bridge.md) | Records require explicit bridge definitions |
| [15](15-public-specs-are-data.md) | Public specs remain ordinary data |
| [16](16-functional-core-imperative-shell.md) | Functional core, imperative shell |
| [17](17-explicit-contracts-over-marshalling.md) | Explicit boundary contracts over data marshalling |
| [18](18-carrier-and-unsigned-policy.md) | Boundary carriers and the unsigned-return policy |
| [19](19-error-union-boundary-semantics.md) | Error-union boundary semantics |
| [20](20-enum-boundary-semantics.md) | Enum boundary semantics |
