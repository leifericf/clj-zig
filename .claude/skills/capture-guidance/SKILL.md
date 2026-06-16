---
name: capture-guidance
description: Record a correction or ruling from the maintainer, mid-session, at near-zero cost: one EDN entry now, promotion into the standards later via incorporate-feedback.
disable-model-invocation: true
---

# capture-guidance

When the maintainer corrects course ("don't do X", "always Y here",
"that pattern is banned"), capture it immediately and keep working.
Promotion into the standards happens later, in batch.

1. Append one entry to `.claude/guidance/inbox.edn` (the file holds a
   vector; start from `[]`):

   ```edn
   {:date "2026-06-16"
    :rule "one imperative sentence, the way a reviewer would state it"
    :context "what happened that triggered the correction"
    :applies-to :clj | :zig | :tests | :tooling | :process
    :suggested-home "check-style/references/clj-style.md"}
   ```

   `:suggested-home` is your best guess: one of the reference files, a
   check-* or write-* skill, an ADR in `docs/adr/`, or a clj-kondo /
   cljfmt rule when the rule is mechanically checkable.

2. Confirm back in one line: `captured: <rule>`, then return to the
   interrupted work. Do not refactor skills mid-task.

3. If the correction contradicts an existing rule in a skill or
   reference, say so in the entry (`:conflicts-with "<file>"`). The
   incorporate-feedback pass resolves it with the maintainer.

The inbox is append-only between incorporation passes; never edit or
delete entries here.
