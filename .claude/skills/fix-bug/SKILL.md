---
name: fix-bug
description: Fix one bug with the house discipline: reproduce, failing test, source-level fix, verify, one commit. Inline for small bugs; agents only when the fix fans out.
disable-model-invocation: true
---

# fix-bug

Input: a bug report (a failing `defnz`, a wrong return value, a compile
that should have succeeded, a description). Output: one commit (or a
small series) on a `fix/<slug>` branch with a regression test that
failed before and passes after.

The discipline, in order, do not skip steps:

1. **Reproduce.** Reduce to the smallest form that shows the wrong
   behavior: a `defnz` plus a call at the REPL, or a failing test. No
   fix lands without a confirmed repro; "can't reproduce" goes back to
   the reporter with what you tried.
2. **Failing test first.** Write the regression test in the right
   surface (write-tests: pure core → `test/clj_zig/*_test.clj`, native →
   a round-trip integration test). Run it; watch it fail for the
   expected reason. Commit it first (`Tests: ...`) so history proves
   fail→pass.
3. **Find the cause, not the symptom.** First check the decision
   records (`docs/adr/`) and the dossier. Behavior an existing ADR
   chose is not a bug; report that back instead of fixing it. Then fix at the
   source: the core function, the generator, or the shell defect, never
   a caller-side special case and never a test adjustment. Classify the
   gap: a real clj-zig defect (fix here), an upstream platform difference
   (document at the site), or harness debt (fix the harness).
4. **Fix smallest-sufficient.** If the cause is in another namespace
   than expected, follow it, but say so.
5. **Verify.** `clojure -M:test` always; when the fix touched the
   native path (generator, compile, FFM), also a round-trip test that
   compiles and calls real Zig, and `zig fmt --check` on any changed
   generated source.
6. **Commit.** `Category: Imperative subject`, single line (e.g.
   `Signature: Reject :ret in non-final position`).

Solo by default. Fan out only when the bug is actually several bugs, or
the repro hunt needs parallel hypotheses (dispatch reviewers with
explicit hypotheses).
