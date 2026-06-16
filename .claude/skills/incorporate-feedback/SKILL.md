---
name: incorporate-feedback
description: Promote captured guidance into the shared standards: reference files, check-*/write-* skills, or an ADR, and empty the inbox. Run periodically or before a big change.
disable-model-invocation: true
---

# incorporate-feedback

Input: `.claude/guidance/inbox.edn` (entries written by
capture-guidance). Output: each entry promoted to its durable home, the
inbox emptied, one commit.

Per entry, in inbox order:

1. **Pick the home by strength.** Strongest first:
   - **Lint rule** (clj-kondo or cljfmt config): when the rule is
     mechanically checkable, a rule that fails on violation beats prose.
     Add it and a test that the lint catches the case.
   - **Reference file** (`clj-style.md`, `zig-style.md`,
     `prose-style.md`): rules reviewers and writers must apply.
   - **check-* / write-* skill body**: when it changes what a
     dimension looks for or how a recipe proceeds.
   - **ADR** (`docs/adr/`, via record-decision): when the entry is a
     why (a choice between alternatives) rather than a how. Often
     paired: the ADR holds the reasoning, a reference holds the
     resulting rule.
2. **Resolve conflicts.** An entry with `:conflicts-with` (or one you
   discover contradicts current text): present both rules to the
   maintainer and apply the decision; never keep both. The resolution
   is a decision; record it via record-decision.
3. **Write it in place**, in the file's voice, a rule, not a
   changelog of the conversation. Delete the entry from inbox.edn in
   the same change.
4. **Cross-check.** If the new rule invalidates an example elsewhere (a
   now-banned idiom shown as good), fix those sites too.

Commit as one `Skills: Incorporate captured guidance (<n> entries)`.
Report a table: rule → home.
