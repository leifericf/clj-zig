---
name: write-tests
description: Recipe for writing clj-zig tests: surface selection (Clojure core vs native round-trip), clojure.test conventions, spec-first tests that land before implementation. Invoked when writing tests.
user-invocable: false
---

# write-tests

Write tests for clj-zig. Pick the surface first:

- **Pure core behavior** (parsing, normalization, spec construction,
  source generation, hashing) → `test/clj_zig/<area>_test.clj`, exercised
  directly with data in and data out. These are the bulk of the suite,
  because the core is where correctness is decided.
- **Native round-trip** (compile, load, call) → an integration test
  that runs a real `zig` compile against a /tmp cache, loads through
  FFM, and asserts the returned Clojure value. Scalar in/out, `:void`
  to `nil`, const slice read, mutable slice mutate (docs/06 acceptance
  tests are the checklist).
- **Generated source** → assert the emitted Zig as a string: readable,
  stable symbol names, pointer-plus-length slice wrappers.

Rules of the suite:

1. **`clojure.test`.** `deftest`/`is`, namespaces
   `clj-zig.<area>-test`, one per area under `test/clj_zig/`.
2. **Self-cleaning fixtures.** Scratch state (cache dirs, compiled
   libraries) lives under a test-owned /tmp path, recreated at the top
   of each `deftest`. Tests pass in any order and on rerun.
3. **Spec-first.** When the implementation doesn't exist yet, write the
   test against the intended behavior (JVM Clojure's, or the dossier's),
   land it first, and let it fail. The integrate order proves
   fail→pass. Mark nothing as skipped.
4. **Behavior, not implementation.** Assert observable results: the
   returned value, the error code (`:zig/compile-failed`), the printed
   form, the normalized data shape, not internals that factoring may
   change. Test the core directly; never mock. A shell branch that
   feels untestable is a factoring finding. Move the decision into the
   core, don't build a mock.
5. **Edge cases are the point.** nil, empty, single, boundary sizes,
   misplaced or missing `:ret`, unknown types, malformed compound
   vectors, unsigned values beyond signed JVM ranges, recompile after a
   body change, keep-last-good after a compile failure.

No workarounds: a test that can't pass reveals a real gap (file it, fix
the source), an upstream platform difference (document at the site), or
harness debt (fix the harness). Skip-lists and weakened assertions are
never the fix.
