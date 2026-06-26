# Design and plan: multi-value returns (result records with owned fields)

Status: proposal. This document is the analysis, the technical design, and
the implementation plan for letting a `defnz` function return several
values at once, including variable-length buffers, as a Clojure map rather
than a buffer the caller unpacks by hand.

## 1. The problem

A `defnz` boundary returns exactly one value. The supported return shapes
are a scalar, an enum, a `:void`, a fixed struct of scalars (a map), an
error union, or one owned/borrowed slice (a vector or a `byte[]`). There
is no shape for "a few scalars plus several variable-length byte buffers."

Real native calls want exactly that. A renderer returns a status, the
output dimensions, a media type, a diagnostics string, and the encoded
payload. A decoder returns a sample buffer plus its metadata. A parser
returns a value plus a list of warnings. Today the only way to carry all
of it across one call is to pack everything into a single owned `[]u8` and
unpack it on the Clojure side.

The public consumer `eido` does precisely this. Its `eido.phane/render-edn-raw`
returns one owned slice framing seven values:

    [status:u8][width:u32][height:u32][media-len:u32][diag-len:u32]
    [media][diagnostics][payload]

little-endian, packed in Zig with `writeInt`/`@memcpy` and unpacked in
Clojure with a `ByteBuffer`, header-int reads, and `Arrays/copyOfRange`.
That framing protocol is duplicated knowledge on both sides of the
boundary, and it is exactly the kind of marshalling ADR 17 says the
contract should describe instead of the caller performing by hand.

This is a clj-zig gap, not an eido quirk. Any consumer returning a result
plus metadata hits the same wall and invents the same protocol. The fix
belongs in clj-zig.

## 2. The Clojure-side shape: a map

The return is a Clojure map keyed by field name, one entry per field. This
is not a new idea at the boundary; it generalizes what clj-zig already
does. A `deftypez` struct return is already marshalled to a map, a
`defrecordz` to a record (also a map), an enum to a keyword, an owned
`[]u8` to a `byte[]`, and an owned slice to a vector. A multi-value return
is just a struct whose fields are allowed to be variable-length, marshalled
with the per-field rules that already exist:

    ;; what eido unpacks by hand today, returned natively instead:
    {:status      :ok           ; enum   -> keyword
     :width       800           ; u32    -> long
     :height      600           ; u32    -> long
     :media-type  "image/png"   ; string -> String   (see :string, below)
     :diagnostics ""            ; string -> String
     :bytes       #<byte[]>}    ; bytes  -> byte[]

A map is chosen over a vector or a list deliberately. A positional vector
(`[:ok 800 600 ...]`) reintroduces the "everyone agrees on the order"
coupling the byte framing already suffers, one layer up. A map is
self-describing, matches the project principle that the boundary contract
is data, and reuses the existing `deftypez`/`defrecordz` machinery and the
record bridge of ADR 14.

## 3. The return form

The result type is an ordinary named type whose fields may now include
owned buffers, declared and returned through the existing ownership
vocabulary of ADR 21:

    (defenumz Status [ok 0 invalid 1 no-output 2 oom 3])

    (defrecordz RenderResult
      [status      Status
       width       :u32
       height      :u32
       media-type  :string                ; owned UTF-8, marshalled to a String
       diagnostics :string
       bytes        [:bytes [:slice :u8]]]) ; owned []u8, marshalled to a byte[]

    (defnz render-result
      [graph-edn [:slice :const :u8]
       base-dir  [:slice :const :u8]
       :ret [:owned RenderResult]]
      "...returns a RenderResult by value...")

`[:owned RenderResult]` says the record's buffer-typed fields are
`c_allocator`-owned and clj-zig frees them after copying their bytes out,
exactly as `[:owned [:slice T]]` does for a single buffer today.
`[:borrowed RenderResult]` is the non-freeing variant for fields backed by
memory the boundary must not free (static data, caller-managed buffers).

Scalar and enum fields keep their current by-value behavior. The new field
types a result record may carry are `[:bytes [:slice :u8]]` (to a `byte[]`),
`[:owned [:slice T]]`/`[:slice T]` (to a vector), and a new `:string` (an
owned UTF-8 `[]u8` marshalled to a `String`, sized 16 bytes, the
ergonomics fix for text fields so a consumer does not decode every one by
hand).

## 4. The wire layout (the load-bearing constraint)

A Zig `extern struct` is C-ABI and cannot hold a Zig slice: `[]const u8` is
a fat pointer `{ptr, len}`, not a C type. So the struct that crosses the
boundary is not the nice `RenderResult`; it is a wire struct in which every
buffer field expands to two `usize` words, a pointer and a length, and
scalars and enums keep their carrier. This is the same lowering a slice
*parameter* already gets (`xs` becomes `xs_ptr: [*]T, xs_len: usize`),
applied to a struct *field*.

    // generated wire struct
    const RenderResult__wire = extern struct {
        status: i32,                  // enum backing
        width: u32,
        height: u32,
        media_type_ptr: usize, media_type_len: usize,
        diagnostics_ptr: usize, diagnostics_len: usize,
        bytes_ptr: usize, bytes_len: usize,
    };

The layout descriptor computes C-ABI offsets over these expanded wire
fields. The Clojure side allocates `@sizeOf(RenderResult__wire)`, passes a
pointer, and reads each field at its offset: scalars and enums directly,
each buffer as a `{ptr, len}` pair it reinterprets and copies out. The
struct value itself lives in the caller's confined arena, so only the
buffer fields are native memory needing a free.

## 5. The ownership and free protocol

This is the substantive ABI work; the marshalling around it is mechanical.

For `[:owned RecordType]` the generator emits a free shim that frees every
buffer field, reading each field's pointer and length back out of the wire
struct:

    export fn <sym>__free(__ret: *const RenderResult__wire) void {
        const a = @import("std").heap.c_allocator;
        a.free(@as([*]u8, @ptrFromInt(__ret.media_type_ptr))[0..__ret.media_type_len]);
        a.free(@as([*]u8, @ptrFromInt(__ret.diagnostics_ptr))[0..__ret.diagnostics_len]);
        a.free(@as([*]u8, @ptrFromInt(__ret.bytes_ptr))[0..__ret.bytes_len]);
    }

The contract on the body is the same as a single owned slice today: every
owned buffer field is allocated with `std.heap.c_allocator`, the one
deallocator safe to call across the boundary. clj-zig copies each field's
bytes into the JVM first, then calls `__free` once with the wire-struct
pointer it still holds, then returns the map. `[:borrowed RecordType]`
emits no free shim and copies the bytes without freeing.

Ownership is uniform across the record for now: the whole result is either
`:owned` or `:borrowed`. Per-field ownership (one owned buffer beside one
borrowed one) is a deliberate non-goal of the first version; the uniform
rule covers the motivating cases and keeps the free shim a single decision.

## 6. clj-zig implementation plan

Five phases. Each lands with tests on the tip before the next starts.
Phases 1 through 4 are clj-zig; phase 5 is the eido migration that proves
the feature on a real consumer.

### Phase 1: the type system and layout

- `clj-zig.layout/normalize-field` currently rejects any non-scalar field.
  Allow buffer fields (`[:bytes ...]`, `[:owned/:borrowed [:slice T]]`,
  `:string`) alongside scalars and enums.
- Compute the wire layout: a scalar/enum field keeps its size and
  alignment; a buffer field expands to two `usize` words (16 bytes, align
  8) and records both sub-offsets. The descriptor gains, per buffer field,
  the pointer offset, the length offset, and the marshalled target
  (`:byte[]`, `:vector`, `:string`).
- `zig-struct` emits the expanded `extern struct`.
- Tests: layout descriptors for a mixed scalar/buffer/string record;
  offsets and total size against hand-computed C-ABI values; the rejection
  matrix still rejects genuinely unsupported field types (nested structs,
  unbounded pointers).

### Phase 2: source generation

- A `generate-owned-struct-return` (and its file-mode twin) emits the wire
  `extern struct`, the inner `__impl` returning the nice record by value,
  an export wrapper that writes each wire field (scalars directly, buffers
  as `@intFromPtr(field.ptr)` and `field.len`), and the per-field `__free`
  shim for `:owned`.
- Reuse `needs-std?` so an owned result pulls in `std` for the free shim.
- Tests: golden generated Zig for an owned and a borrowed result record,
  inline and file mode; a compile smoke test that the emitted Zig builds.

### Phase 3: marshalling

- Extend `clj-zig.ffm/read-struct` to dispatch per field: a scalar/enum
  reads as today; a buffer reads its `{ptr, len}` at the field's two
  offsets, reinterprets, and copies out as a `byte[]`, a vector, or a
  `String` per the field's target.
- Add an owned-struct return arm to `bind`, mirroring the existing owned
  arm: allocate the wire-struct out-segment, invoke, read the struct into a
  map (or rebuild the record via its factory), call `__free` for an owned
  result, return.
- Tests: round-trip a real fixture that returns scalars, an enum, a
  `:string`, and a `byte[]` field; drive it in volume against the
  allocation-balance tracker to prove every owned field is freed and the
  native live-count returns to zero.

### Phase 4: the decisions

- Record the ADRs, framed generally (the feature is general; eido is one
  public consumer, not the rationale): a result record may carry owned
  buffer fields and is marshalled to a map; buffer fields lower to a
  `{ptr, len}` wire pair; the free protocol frees each owned field; and the
  `:string` field type. Extend ADR 21 (owned now covers a record, not only
  a bare slice) and ADR 14 (the record bridge now carries buffers).

### Phase 5: eido

Once phases 1 through 4 land and a new clj-zig alpha is cut, eido bumps to
it and migrates:

- Replace `render-edn-raw` (one owned `[:bytes ...]` return packing the
  frame) with `render-result` returning `[:owned RenderResult]`, where the
  body copies phane's `media_type`, `diagnostics_text`, and `bytes` into
  three `c_allocator` buffers and returns the record (it must copy, because
  `r.deinit` frees phane's originals, the same copy it does into one buffer
  today, now into three named fields with no offset arithmetic).
- Delete the framing protocol: the `ByteBuffer` reads, the header offsets,
  `slice`, and `status->kw` all go. `render-edn` becomes a thin call that
  returns the record map straight through, decoding nothing (`:media-type`
  and `:diagnostics` arrive as `String`s).
- Re-bake the boundary for the published targets and run the eido unit and
  phane integration lanes.

Net effect in eido: roughly forty lines of framing and unframing deleted,
the wire format becomes a declared record, and the two sides can no longer
silently disagree on the byte layout.

## 7. Risks and open questions

- **Cross-target ABI.** The bake cross-compiles the boundary for several
  targets (ADR 36). The wire struct stores pointers as `usize`, which is
  target-width; the current published targets are 64-bit, so a pointer and
  a length are 8 bytes each. A future 32-bit target would change the wire
  offsets, which is fine because the layout is recomputed per target and
  participates in the content hash, but it is worth a test that the
  descriptor is target-width-aware rather than assuming 64-bit.
- **Allocator discipline.** Every owned buffer field must come from
  `std.heap.c_allocator`, the existing owned-return rule. A field allocated
  from another allocator and freed by the shim is a fault; the contract and
  a diagnostic should state it as plainly as the single-slice case does.
- **String validity.** `:string` decodes UTF-8. Invalid bytes should
  decode with the JVM's replacement behavior rather than throwing across
  the boundary; the field is still untrusted native memory and inherits the
  bounded-read discipline.
- **Per-field ownership** (mixed owned and borrowed fields) is deferred; if
  a real consumer needs it, it is an additive follow-up, not a redesign.
- **Empty and null fields.** A zero-length buffer field marshals to an
  empty `byte[]`/`String`/vector without dereferencing the pointer, the
  same guard the single-slice path already applies.

## 8. Effort and sequencing

The clj-zig work is medium-sized and self-contained: the type-system and
layout change, one new code-generation path, one new marshalling arm, and
the ADRs. The wire lowering and the free protocol are the only genuinely
new mechanisms; everything else reuses the struct-return and owned-slice
paths that already exist. The eido migration is small and waits on a
published clj-zig release. The order is strict: clj-zig phases 1 through 4
land and ship, then eido phase 5 bumps the dependency and deletes its
framing.
