# clj-zig Zig style: the checkable standard

The checkable Zig standard for clj-zig, grounded in the official language
reference, the Zig standard library, and conventions from major Zig
codebases (TigerBeetle, Bun, Mach, the std lib). `check-style` applies
this file; `write-zig` writes to it.

The Zig in clj-zig is not a hand-maintained source tree. It is two
things: the wrapper source clj-zig **generates** from a boundary contract
(emitted by `clj-zig.source`), and the **body** a user writes inside a
`defnz` form. Both must read as ordinary, clean Zig. Acceptance
requires "readable Zig source" and "pointer-plus-length wrappers for
slices" (docs/06); this file is what "readable" means. Hand-written Zig
test fixtures follow it too.

Sources of truth that override this file when they disagree: `zig
build` (errors on unused locals and shadowing, the floor), `zig fmt
--check`, and the boundary contract in `docs/03`. When a tool and this
file disagree, the tool wins; fix this file.

Run `zig fmt` on generated and hand-written Zig before it lands; it
owns indentation, brace placement, and trailing commas, so most of this
guide covers what `zig fmt` cannot.

## 1. Naming

| What `x` is | Convention | Example |
|---|---|---|
| A type (struct, enum, union, opaque) | `TitleCase` | `Point`, `ParseError` |
| A callable that returns a `type` | `TitleCase` | `ArrayList(T)` |
| Any other callable | `camelCase` | `sumF64`, `appendSlice` |
| Variables, fields, parameters | `snake_case` | `byte_count`, `total` |
| True constants / comptime non-types | `SCREAMING_SNAKE` or `snake_case` | `MAX_DEPTH` |

- Acronyms are ordinary words: `HttpServer`, not `HTTPServer`.
- First method parameter is `self`; bind `@This()` once
  (`const Self = @This();`).
- Avoid filler names (`Value`, `Data`, `Context`, `utils`, `misc`).
  `i` is a fine loop index. Units last, descending significance:
  `latency_ms_min`.
- Generated symbol names are derived from the Var, stable, and
  collision-free (e.g. `clj_zig_app_core_add`); the scheme lives in
  `clj-zig.source` and must stay deterministic so the cache key is stable
  (docs/04).

## 2. Formatting

- `zig fmt` is authoritative: 4-space indent, braces on the same line,
  trailing commas split lists one per line. Touched files pass `zig fmt
  --check`. Generated Zig is emitted already-formatted.
- Line length 100 columns; let a trailing comma wrap long lists.
- `if` gets braces unless it fits on one line (`if (ok) return;`); no
  brace-less multi-line `if`.
- Prefer struct and decl literals (`const p: Point = .{ .x = 1 };`,
  `const list: ArrayList(u8) = .init(allocator);`) over `Type{}` /
  `Type.init()`.
- Let context coerce: `const len: u32 = @intCast(slice.len);`, not
  `@as(u32, @intCast(...))`.
- Minimize nesting: early-return guards over `else`-wrapping; success
  path in the `if`; prefer `==` over `!=`.

## 3. `const` vs `var`

Prefer `const` everywhere possible. It states intent and enables
optimization. A `const` pointer still mutates its pointee. Apply
judgment; the compiler catches only some needless `var`.

## 4. Imports and file structure

- Group imports, blank line between groups, alphabetical within: std,
  third-party, local.
- Container declaration order: fields, type aliases, `init`, `deinit`,
  other methods (`pub` before private, related grouped).
- Tests live at the bottom of the file they cover (see §11).

## 5. Error handling

- Prefer explicit named error sets on public functions; inferred `!T`
  is fine for private helpers. Never `anyerror` in a public API.
- `try` to propagate; `catch |err|` to handle, always capture, never
  discard. `.?` over `orelse unreachable`; `orelse` only for a real
  fallback.
- Acquire then immediately `defer`/`errdefer` release (see §6).

### Diagnostics at the boundary

Zig errors carry no payload. When the user's body or a generated
wrapper fails to compile, the failure surfaces through clj-zig's
structured diagnostic (docs/04), assembled in the Clojure shell: the
Var and signature first, then the generated `source.zig` path, then the
Zig compiler's stderr mapped under `:zig/stderr`. The Zig side does not
print; it fails, and the shell renders. A diagnostic message names the
failing operation, never a bare "error".

## 6. Memory and the boundary

Zig has no hidden allocations; every allocation is explicit and paired
with a free. At clj-zig's boundary the lifetime rules are conservative
(docs/03) and the generated wrapper must honor them:

- Scalars are copied across the boundary.
- A slice handed in from Clojure is valid only for the duration of the
  call; a mutable slice may be mutated during the call. The Zig side
  must not retain a Clojure-owned pointer after return.
- Returned native memory must be explicitly owned, copied, or wrapped;
  an `[:owned ...]` return defines who frees it.
- Pass `std.mem.Allocator` explicitly, never a global. Pair every
  `alloc`/`create`/`init` with `defer` (success) or `errdefer`
  (partial construction before ownership transfers). Write the
  `errdefer` for the first allocation before making the second.
- Prefer slices (`[]T`) over many-item pointers (`[*]T`): slices carry
  a length and bounds-check. The generated slice wrapper is
  pointer-plus-length (docs/06).
- `std.testing.allocator` in every allocating test; a leak fails it.

## 7. Type system

- Tagged unions for mutually exclusive state; make invalid
  combinations unrepresentable.
- `enum(BackingInt)` for distinct domain ids.
- `?T` for expected absence, `E!T` for unexpected failure. Don't model
  absence with an error union.

## 8. Functions

- Many parameters, or two same-typed adjacent ones, become an options
  struct. No bare `bool` for a behavioral mode; use a named enum.
- Prefer returning values to out-pointers, except for large in-place
  initialization.
- Keep functions short. Approaching a size limit means two
  responsibilities; split by phase or domain, don't shave lines.

## 9. Comptime

- Type-returning functions are `TitleCase` and return `type`.
- `comptime { assert(@sizeOf(Header) == 128); }` documents and enforces
  layout invariants at zero runtime cost. Useful where a `defrecordz`
  layout must match a known Zig struct.
- `@compileError("message")` over `unreachable` for failed comptime
  constraints.

## 10. Comments

Terse and sparse; the Zig standard library is the benchmark. Clear
names and small functions carry the meaning.

- Comment the why, never the what. Comment only what the code cannot
  say: which allocator owns a slice, why a branch is `unreachable`, an
  endianness or layout invariant.
- No decorative banners, no commented-out code, no per-line annotation,
  no change narration. A comment block longer than a few lines is
  itself a finding.
- `//!` file-top: one or two lines naming the file's responsibility.
  `///` doc comments on public declarations whose contract is not
  obvious from the signature.
- A well-placed `assert` documents an invariant more strongly than a
  comment; it is enforced in Debug and ReleaseSafe.

## 11. Testing

- Co-locate `test` blocks at the bottom of the file they cover.
  Test-first: write the failing test, watch it fail for the right
  reason, then implement.
- `std.testing` asserts (`expectEqual`, `expectEqualStrings`,
  `expectError`); don't hand-roll `if (x != y) return error...`.
- `std.testing.allocator` in every allocating test. Cover edges: nil
  via optionals, empty and single-element slices, boundary sizes,
  overflow and negatives on sizes that cross the boundary, unsigned
  values beyond signed ranges.

## 12. Bytes, targets, and numbers

- Multi-byte integers to/from raw bytes use explicit byte order
  (`std.mem.readInt`/`writeInt`), never a host-order reinterpret.
- Sizes and counts are `usize`; signed only where negative means
  something. No silent narrowing: `@intCast` only with a justified
  bound. Size and index arithmetic on values crossing the boundary uses
  checked ops (`std.math.add`/`mul`) guarding the access; `+%`/`*%`
  only where wrap is intended and bounded.
- Unsigned integers beyond signed JVM ranges need clj-zig's explicit
  return policy before they cross back (docs/03); the wrapper does not
  silently truncate.
- Platform branches are comptime-gated on `builtin.target.os.tag` /
  `builtin.cpu.arch`. The target is part of the cache key (docs/04).

## 13. Safety and assertions

- Assert preconditions, postconditions, and invariants; verify inputs
  before operating. Split compound assertions: `assert(a); assert(b);`.
- Handle every error; most catastrophic failures come from a mishandled
  non-fatal one. Untrusted input must never reach an `unreachable` or
  an unchecked size cast.

## 14. Change scoping

- One logical change per commit; no drive-by renames or style churn in
  untouched code.
- Follow the surrounding file's conventions; in-file consistency beats
  abstract preference.
- Abstraction earns its place: a helper reduces mistakes or preserves
  an invariant across call sites, not hides a few repeated lines.

---

*Grounded in the Zig Language Reference style guide, the Bun Zig style
guide, TigerBeetle's TigerStyle, and the Zig standard library.*
