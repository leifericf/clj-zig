# ADR 59: Perf numbers and the perf log live outside the repo

Date: 2026-07-03

## Context

The harness writes two kinds of artifacts. A numbers record per run (the
meta block plus seven shape entries with defnz, floor, and overhead) and
an append-only perf log that records before/after numbers across
optimization commits. Both are measurement data tied to a specific
machine, JDK, and commit, not source.

Putting them in the repo has two costs. Numbers churn: every bench run
would be a diff against tracked files, and the same commit measured
twice produces different numbers (Criterium sampling noise), so the diff
would be noise. And the repo would carry machine-specific data (a dev
mac, JDK 26) that is meaningless to a contributor on another machine,
which is exactly the kind of local state git handles badly.

## Decision

Numbers records and the perf log live under
`~/.agentic-sdk/clj-zig/artifacts/perf/`, outside the repo. The
canonical baseline is `baseline-<date>.edn`; per-run records are
`perf-<millis>.edn`; the append-only log is `perf-log.edn`. The harness
writes them there directly. The `docs/` tree stays ADRs only; planning
and operational notes live in the artifacts dir too.

Every perf-affecting commit cites before/after numbers from the perf
log (the FR-6 evidence rule, documented in the project guide under
`artifacts/`). The repo carries the harness code and the ADRs; the
numbers stay diffable artifacts outside git.

## Consequences

The repo stays free of measurement churn. A contributor clones the repo
and gets code and ADRs, not a dev mac's baseline. Numbers are still
diffable: a reader compares two `baseline-*.edn` files or two perf-log
entries with `diff` or by eye, since both are pretty-printed EDN.

The cost is that numbers are not version-controlled alongside the code
that produced them. The meta block in every record carries the commit
SHA, so a reader can check out the exact source that produced a given
record. The perf log entry cites the commit and the baseline it moves,
closing the loop between a code change and its measured effect without
putting the numbers in git.

## Alternatives

Commit numbers to the repo. Rejected: numbers churn on every run and
are machine-specific; the repo would carry noise and local state, and
the same commit measured twice would always show a diff.

Commit only the baseline, not the per-run records. Rejected: the
baseline and the per-run records share the same churn and
machine-specificity. Drawing the line between them is arbitrary, and the
perf log (the artifact the evidence rule cites) has the same problem.
