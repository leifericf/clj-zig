# 06 - Design Principles and Decisions

## Design principles

1. Clojure is the host experience.
2. Zig is the implementation language inside the native boundary.
3. The boundary contract is data.
4. Use exact Zig type names as Clojure keywords.
5. Preserve `defn` shape as much as possible.
6. Keep signatures decomposable and composable.
7. Macros should be thin over data functions.
8. Do not hide ownership or lifetime.
9. Keep Zig free inside the contract.
10. REPL redefinition is core, not an afterthought.
11. Generated source must be inspectable.
12. Diagnostics are structured data.
13. Prefer explicit boundary contracts over magical marshalling.
14. Start one-directional: Clojure calls Zig.
15. Functional core, imperative shell: normalize and generate as data;
    compile and load at the edge.

## Decisions

The decisions that shaped clj-zig are recorded as ADRs in
[`adr/`](adr/). ADR 01-17 are the founding decisions, migrated
from this document; later ADRs capture decisions made during
development.
