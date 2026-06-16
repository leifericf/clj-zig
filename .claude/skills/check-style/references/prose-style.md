# clj-zig prose style: the writing standard

Applies to everything written for humans: the design dossier (`docs/`),
decision entries in `docs/07-design-principles-and-decisions.md`,
skill bodies, docstrings, code comments, and commit messages. The
existing dossier is the exemplar. Match its terse, plain voice.

## Voice

- Plain, direct, technical. No metaphors, no cleverness in names or
  headings. A reader six months from now gets the plain meaning on
  first read.
- Active voice; present tense for current behavior ("the macro
  normalizes the signature"), past tense for history ("the spike
  showed").
- State constraints and effects, not narrative. "JVM native library
  unloading is awkward, so artifacts are content-addressed" beats "we
  ran into an interesting problem with unloading".
- No marketing adjectives (powerful, robust, simple, seamless, blazing).
  If a property matters, state the measurement or the mechanism.
- No em dashes, no arrows (`->` in prose), no other AI tells. Never
  "hand-written" or "hand-rolled" in any public-facing text.

## Succinctness (Strunk and White, Zinsser)

- Cut anything the reader can derive; keep everything they would
  otherwise have to rediscover. The test: would deleting this sentence
  cost a future reader a wrong decision or a re-derivation? If not,
  delete it.
- One idea per sentence; one topic per paragraph. Lists for enumerable
  facts, prose for reasoning, not the reverse.
- Concrete beats general: name the file, the keyword, the type, the
  number. `[:slice :const :u8]` carries more than "a compound type".
- Don't restate what an adjacent artifact already says. Cite it by
  path and add only what is new here.

## Decision records (docs/adr/)

Architecture decisions are ADRs in `docs/adr/`, written when the
decision happens. `record-decision` writes them. (The design
principles they serve remain in `docs/07`.)

- Fields: Title, Date, Context, Decision, Consequences, Alternatives.
  No status field; a decision stands until a later record supersedes it
  by name.
- Title is the decision itself, readable in the index without opening
  the file ("Generated artifacts are content-addressed", not "Caching
  investigation").
- Context is neutral: the facts and constraints as they stood, no
  foreshadowing of the answer.
- Decision is one paragraph: what and how, present tense.
- Consequences include the costs; a record listing only upsides is
  advertising.
- Alternatives get their real strengths before the rejection; a
  strawman documents nothing.
- One screenful (~40-60 lines). If it can't fit, it is probably several
  decisions.

## Code comments

- Terse and sparse. Comment the why, never the what; clear names carry
  the meaning. Comment only what the code cannot say: an ownership or
  lifetime constraint at the boundary, why a branch is unreachable, a
  non-obvious design reason.
- No decorative banners, no commented-out code, no change narration
  (git holds history). A comment block longer than a few lines, or
  comments outweighing the code they sit in, is itself a finding.

## Commit messages

- Single line, category first: `Category: Imperative subject`. A
  capitalized noun names the area or effort (`Docs:`, `Tests:`,
  `Refactor:`, `Build:`), chosen so the log scans and related commits
  group. An occasional uncategorized commit is fine when no category
  fits; never invent a hollow one.
- Imperative, sentence-case subject describing the effect, not the
  diff. No trailing period, within 70 characters.
- No body paragraphs. No `Co-Authored-By` or any other AI/tool
  attribution trailer. No version numbers; versions are release
  metadata.
