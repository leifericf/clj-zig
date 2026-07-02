# ADR 56: Data-oriented node model for Zig source generation

Date: 2026-07-02

## Context

clj-zig generates Zig source text from a boundary spec and a user body.
Before this decision, every generator in `source.clj` and `layout.clj`
produced source text by string concatenation: `str` calls joined
fragments with hardcoded four-space indentation (`"    "`) and newline
characters, and `indent-body` re-indented multi-line blocks. The
formatting concerns (braces, semicolons, indentation, newlines) were
spread across roughly thirty generator functions.

The string-concatenation approach had three costs. First, changing the
output format required touching every generator. Second, testing meant
asserting on exact rendered strings, which was fragile. Third, the
generators mixed content (what to emit) with formatting (how to emit
it), violating the functional-core principle that data in should
produce data out.

ADR 32 settled that the compilation target is Zig source text, not a
lower IR. The question this decision answers is different: not what to
target, but how to generate it. The generators should produce data, and
a single renderer should turn that data into text.

## Decision

Generators produce declaration, statement, and expression nodes as
plain data maps. A single `render` function in `clj-zig.zig` turns
nodes into Zig source text. The four-space indentation literal appears
only in the renderer's `indent` function.

The node model has three levels, each with a `:raw` escape hatch:

- **Declarations**: `:fn`, `:struct`, `:enum`, `:const`, `:raw`.
  A generator returns a vector of declaration nodes.
- **Statements**: `:const`, `:assign`, `:return`, `:if`, `:for`,
  `:defer`, `:expr-stmt`, `:raw`. A function body is a vector of
  statement nodes.
- **Expressions**: `:ref`, `:field`, `:deref`, `:call`, `:lit`, `:as`,
  `:slice`, `:raw`. The expression vocabulary covers the common cases;
  `:raw` handles one-off complex expressions.

The user body is always a single `:raw` statement node. Type strings
(like `"i64"` or `"[]const f64"`) remain opaque strings passed through
from `zig-type`; they are tokens, not source fragments needing
formatting.

Generators compose nodes with constructor functions (`zig/fn-decl`,
`zig/const-stmt`, `zig/assign-stmt`, etc.), never with `str` to build
multi-line source. The `source/generate` function renders nodes to a
string for backward compatibility. `core/build-inputs` composes
preamble, body, and wrapper nodes and calls `render` once.

## Consequences

The renderer is the single source of formatting truth. Changing
indentation, brace style, or spacing requires editing one function, not
thirty. Tests can assert on node data for structure and on rendered
strings for compile correctness.

The `:raw` node at each level is the pragmatic escape hatch. Complex
one-off expressions (catch clauses, payload captures, @as/@ptrFromInt
chains) use `:raw` with `str` to build the expression text. This is
acceptable: the goal is centralizing formatting (indentation, braces,
semicolons), not eliminating every use of `str`. Building a type string
from `(str "[*]const " elem)` is the same as what `pointer-type`
already does.

The node model is a pruned view of Zig's own AST decomposition, not a
different paradigm. It covers the subset clj-zig generates. When a new
Zig construct needs generation, add a node type or use `:raw`; the
renderer and existing nodes are unaffected.

## Alternatives

Target Zig's ZIR or AIR directly. Rejected: `zig build-lib` accepts
source text, not IR (ADR 32). There is no compiler input path for ZIR.
Targeting IR means writing a Zig compiler.

Full Zig AST (every node type the parser produces). Rejected: the full
AST covers constructs clj-zig never generates (switch, while, inline,
async). The pruned model covers what generators emit with roughly
twenty render methods.

Keep string concatenation. Rejected: formatting concerns scattered
across thirty functions made every format change expensive and testing
fragile.
