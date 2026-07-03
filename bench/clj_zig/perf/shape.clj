(ns clj-zig.perf.shape
  "Pure data: the seven canonical clj-zig contract shapes the per-call
  overhead harness measures.

  Each shape is a map carrying the defnz signature form, a trivial Zig
  body, the type declarations the bench shell must establish before
  compile, a floor descriptor (the C ABI the clj-zig.foreign
  direct-handle path binds), and an arg-fn that yields per-call
  arguments for the defnz invoker. The bench shell
  (clj-zig.perf.run, landed in a later phase) consumes these records:
  it compiles the defnz side via clj-zig.core, binds the floor via
  clj-zig.foreign, drives both under Criterium, and shapes the result
  via clj-zig.perf.stats.

  This namespace is PURE DATA. It does not require clj-zig.core,
  clj-zig.ffm, clj-zig.foreign, or Criterium (ADR 16). The floor
  descriptor's layouts are keyword spellings of the clj-zig.foreign
  c-* shorthands (:c-long, :c-double, :c-ptr, :void); the shell
  resolves the keyword to the ValueLayout var at bind time, which keeps
  this namespace off the FFM classpath.

  The seven kinds exhaust the canonical contract surface exemplified in
  test/clj_zig/fixtures.clj. error-union is treated as a variant of
  owned/struct return, NOT a separate canonical shape; that keeps the
  count at seven.")

;; --- shared structure -----------------------------------------------------

(def required-shape-keys
  "The keys every shape record carries. Asserted by the Tier-0 unit
  tests and relied on by the bench shell."
  [:kind :name :signature :body :setup :floor :arg-fn])

(def required-floor-keys
  "The keys every floor descriptor carries. The bench shell resolves
  the descriptor's :name to a C ABI symbol via clj-zig.core's spec
  symbol-munging, then binds a cached MethodHandle via clj-zig.foreign
  with the :ret and :args layouts."
  [:name :ret :args])

(def shape-order
  "The canonical enumeration order. Stable across runs so two
  measurement records report shapes in the same sequence and a diff
  between them is straightforward."
  [:scalar-passthrough :struct-by-value :enum :slice-arg
   :string :owned-return :handle])

;; --- the seven shapes -----------------------------------------------------
;;
;; Bodies are TRIVIAL by design: this feature measures per-call
;; OVERHEAD, not user computation (the governing principle: MEASUREMENT
;; ONLY). Each body is the shortest form that exercises its contract
;; kind. The scalar, struct, enum, and slice bodies do not allocate.
;; The string, owned, and handle bodies DO allocate because their
;; contracts require a buffer or a Box; the c_allocator calls in those
;; bodies are the minimal contract-required allocation, and the
;; body-leak guard in clj-zig.perf.stats will flag their floor
;; measurements as :body-leak-suspect. That flag is the expected
;; outcome on those three shapes, not a defect.

(def ^:private scalar-passthrough
  "Echo an i64 straight back. The body does no work and allocates
  nothing; the defnz path takes the ADR 39 scalar hot path (no arena,
  no per-arg marshalling map) and the floor is a single-scalar C ABI
  round-trip. This is the shape where overhead is the largest fraction
  of the defnz median."
  {:kind      :scalar-passthrough
   :name      "echo-i64"
   :signature '[x :i64 :ret :i64]
   :body      "return x;"
   :setup     []
   :floor     {:name "echo-i64" :ret :c-long :args [:c-long]}
   :arg-fn    (fn [] [42])})

(def ^:private struct-by-value
  "Round-trip a Point {x, y: f64} by value. clj-zig lowers a
  struct-by-value return to an out-pointer written through by the
  wrapper (source.clj), so the C ABI is void with one address arg; the
  shell allocates the out-segment once outside the timed loop and
  reuses it per call so the floor stays alloc-free per call. The
  :struct-layout key tells the shell how to lay out that out-segment."
  {:kind      :struct-by-value
   :name      "echo-point"
   :signature '[p Point :ret Point]
   :body      "return p;"
   :setup     [{:kind   :deftypez
                :name   'Point
                :fields ['x :f64 'y :f64]}]
   :floor     {:name          "echo-point"
               :ret           :void
               :args          [:c-ptr]
               :struct-layout {:fields [:c-double :c-double]}}
   :arg-fn    (fn [] [{:x 1.5 :y 2.5}])})

(def ^:private enum-shape
  "Echo a Suit enum. An enum crosses the C ABI as its backing scalar
  (i32 by default), so the floor is a scalar round-trip and the defnz
  side runs the general arena-backed path (an enum arg is not a scalar
  in the ADR 39 sense)."
  {:kind      :enum
   :name      "echo-suit"
   :signature '[s Suit :ret Suit]
   :body      "return s;"
   :setup     [{:kind    :defenumz
                :name    'Suit
                :members ['clubs 0 'diamonds 1 'hearts 2 'spades 3]}]
   :floor     {:name "echo-suit" :ret :c-int :args [:c-int]}
   :arg-fn    (fn [] [:clubs])})

(def ^:private slice-arg
  "Sum a const slice of f64. The slice arg lowers to a (ptr, len) pair
  at the C ABI; the body's reduction loop is the most work any shape's
  body does, and it still allocates nothing. The shell pre-builds the
  native f64 segment once and threads its pointer and length per call
  so the floor measures pure invoke cost."
  {:kind      :slice-arg
   :name      "sum-f64"
   :signature '[xs [:slice :const :f64] :ret :f64]
   :body      "var t: f64 = 0; for (xs) |v| t += v; return t;"
   :setup     []
   :floor     {:name "sum-f64" :ret :c-double :args [:c-ptr :c-long]}
   :arg-fn    (fn [] [(double-array [1.0 2.0 3.0])])})

(def ^:private string-shape
  "Identity a :string. A :string return allocates a fresh []u8 the
  wrapper's __free shim releases after the Clojure side decodes it, so
  the body necessarily allocates a buffer the size of the input. The
  body-leak guard flags this shape's floor as :body-leak-suspect
  (expected). The C ABI is void with (out-ptr, out-len, in-ptr,
  in-len); the floor descriptor's :free-shim names the release symbol
  the shell must call between iterations."
  {:kind      :string
   :name      "string-identity"
   :signature '[s :string :ret :string]
   :body      (str "const out = std.heap.c_allocator.alloc(u8, s.len)"
                   " catch @panic(\"oom\");\n"
                   "@memcpy(out, s);\n"
                   "return out;")
   :setup     []
   :floor     {:name      "string-identity"
               :ret       :void
               :args      [:c-ptr :c-ptr :c-ptr :c-long]
               :free-shim "string-identity__free"}
   :arg-fn    (fn [] ["hello"])})

(def ^:private owned-return
  "Double a const slice into an owned slice. Like :string, the return
  allocates a fresh buffer the __free shim releases, so the body
  allocates and the body-leak guard flags this shape (expected)."
  {:kind      :owned-return
   :name      "owned-double"
   :signature '[xs [:slice :const :f64] :ret [:owned [:slice :f64]]]
   :body      (str "const out = std.heap.c_allocator.alloc(f64, xs.len)"
                   " catch @panic(\"oom\");\n"
                   "for (xs, 0..) |v, i| out[i] = v * 2.0;\n"
                   "return out;")
   :setup     []
   :floor     {:name      "owned-double"
               :ret       :void
               :args      [:c-ptr :c-ptr :c-ptr :c-long]
               :free-shim "owned-double__free"}
   :arg-fn    (fn [] [(double-array [1.0 2.0])])})

(def ^:private handle
  "Box an i64 into an opaque handle (ADR 22). The body allocates a Box
  via c_allocator each call, so the floor allocates too; the body-leak
  guard flags this shape (expected). The handle is a process-lifetime
  pointer the Clojure side threads back into native calls; the floor
  returns the raw pointer, the defnz path wraps it as an opaque Handle
  record. A complete handle round-trip (box then unbox then free) is
  out of scope for the floor measurement, which isolates one call."
  {:kind      :handle
   :name      "box"
   :signature '[v :i64 :ret [:handle Box]]
   :body      (str "const b = std.heap.c_allocator.create(Box)"
                   " catch @panic(\"oom\");\n"
                   "b.* = .{ .v = v };\n"
                   "return b;")
   :setup     [{:kind :defz
                :name 'Box
                :body "const Box = struct { v: i64 };"}]
   :floor     {:name "box" :ret :c-ptr :args [:c-long]}
   :arg-fn    (fn [] [42])})

(def shapes
  "The seven canonical contract shapes, keyed by kind. The bench shell
  iterates this map and pairs a defnz measurement against its
  clj-zig.foreign direct-handle floor for each entry."
  {:scalar-passthrough scalar-passthrough
   :struct-by-value    struct-by-value
   :enum               enum-shape
   :slice-arg          slice-arg
   :string             string-shape
   :owned-return       owned-return
   :handle             handle})

(defn shape-list
  "The seven shapes in canonical order, as a vector of maps. Stable
  across calls so the bench shell emits shapes in the same order every
  run."
  []
  (mapv shapes shape-order))
