---
name: record-decision
description: Write an ADR in docs/adr/ when an architecture decision is made: in conversation with the maintainer, or when work settles a real choice between alternatives. Lightweight by design.
---

# record-decision

Architecture decisions are recorded as ADRs in `docs/adr/`, written
when the decision happens, not reconstructed later. Style and field
rules: the "Decision records" section of
`.claude/skills/check-style/references/prose-style.md`. (ADR 01-17 are
the founding decisions, migrated from
`docs/07-design-principles-and-decisions.md`, which now holds the
design principles.)

## When to invoke

Humans: whenever a discussion ends in "we'll do X, not Y" and X
constrains future work.

Agents: when your work settles a real choice between alternatives where
the rejected option would have been reasonable, or when you reject a
review finding as deliberate and no existing record covers it. The
rejection rationale IS the record.

Do not record: choices with one reasonable option, reversible
implementation details, anything an existing ADR or DEC already covers
(supersede it instead if the answer changed).

## Procedure

1. **Next number.** Read `docs/adr/README.md`, take the highest number,
   add one (two digits).
2. **Write `docs/adr/NN-slug.md`:**

   ```markdown
   # ADR NN: <the decision, readable in the index>

   Date: YYYY-MM-DD

   ## Context
   ## Decision
   ## Consequences
   ## Alternatives
   ```

   One screenful. Consequences include the costs. Alternatives get
   their real strengths before the rejection. Superseding: say
   "Supersedes ADR NN" in the Decision; never edit the old record.
3. **Add the one-line row** to the `docs/adr/README.md` index.
4. **Route the rule too.** If the decision changes how code is written
   (a banned idiom, a new constraint), also add the rule to the right
   reference or skill. The ADR holds the why, the reference holds the
   how. Cross-cite.
5. **Commit.** `Docs: Record ADR NN, <short title>` (or fold into the
   change's commit series when recorded mid-change).
