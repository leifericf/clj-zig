(ns clj-zig.source
  "Generate the Zig wrapper source from a boundary spec, and resolve
  external `.zig` source files named by a descriptor. A spec and the
  user's body go in, a string of readable Zig comes out; a `{:zig/file
  ...}` descriptor resolves a sibling or classpath `.zig` file.

  For a scalar function the wrapper is an `export fn` (C ABI, so FFM can
  bind it) whose parameters are the native params and whose body is the
  user's Zig spliced in verbatim:

      export fn clj_zig_app_2e_core_add(x: i64, y: i64) i64 {
          return x + y;
      }

  `zig fmt` owns final formatting; the generator emits already-canonical
  Zig for the structure it controls, and the compile shell runs `zig
  fmt` over the whole file to normalize the body."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-zig.layout :as layout]
            [clj-zig.zig :as zig]))

;; External `.zig` file resolution

(def ^:private no-source
  "The value of `*file*` when a form is evaluated with no source file, as
  at the REPL. There is no defining directory to resolve against."
  "NO_SOURCE_PATH")

(defn candidate-paths
  "The ordered filesystem paths to try for `rel`, given the defining
  Clojure source file (its `*file*`, or nil / \"NO_SOURCE_PATH\" at the
  REPL). An absolute `rel` is used as-is; a relative `rel` resolves first
  against the defining file's directory, then against the current
  directory. Pure: builds path strings, reads no filesystem."
  [defining-file rel]
  (let [f (io/file rel)]
    (if (.isAbsolute f)
      [(.getPath f)]
      (->> [(when (and defining-file (not= defining-file no-source))
              (.getPath (io/file (.getParent (io/file defining-file)) rel)))
            rel]
           (remove nil?)
           vec))))

(defn namespace-zig-file
  "The `.zig` file co-located with a namespace's Clojure source: the
  defining file's path with its `.clj`/`.cljc` extension replaced by
  `.zig`. A bodyless `defnz` sources its body from this file's matching
  `pub fn`. Pure; the filesystem and classpath resolution happens in
  `establish-binding-from!`. Throws when there is no defining file, as at
  the REPL, where a bodyless `defnz` has no co-located file to read."
  [defining-file]
  (when (or (nil? defining-file) (= defining-file no-source))
    (throw (ex-info (str "A bodyless defnz needs a file-loaded namespace with"
                         " a co-located .zig; give an explicit {:zig/file ...}"
                         " body when there is no defining file.")
                    {:level :error :error/code :clj-zig/no-namespace-file})))
  (str/replace defining-file #"\.cljc?$" ".zig"))

(defn declared-namespace
  "The namespace a `.zig` file asserts it belongs to via a leading
  `//! clj-zig: <ns>` doc-comment line, or nil when it makes no such
  assertion. Pure."
  [zig-text]
  (some (fn [line]
          (second (re-matches #"\s*//!\s*clj-zig:\s*(\S+)\s*" line)))
        (str/split-lines zig-text)))

(defn resolve-and-read
  "Read the text of the Zig source file `rel`, trying the filesystem
  candidates first, then the classpath. Returns `{:text <content> :path
  <resolved>}`. Throws a `:clj-zig/zig-file-not-found` diagnostic listing
  what was tried."
  [defining-file rel]
  (let [fs-paths (candidate-paths defining-file rel)
        on-disk  (first (filter #(.isFile (io/file %)) fs-paths))
        res      (io/resource rel)]
    (cond
      on-disk  {:text (slurp on-disk) :path on-disk}
      res      {:text (slurp res) :path (str res)}
      :else
      (throw (ex-info (str "Could not find the Zig source file " (pr-str rel) ".")
                      {:level :error
                       :error/code :clj-zig/zig-file-not-found
                       :clj-zig/file rel
                       :clj-zig/tried (conj fs-paths (str "classpath:" rel))})))))

(defn- valid-zig-ident?
  "True when `s` is a legal Zig identifier, so it can name the user fn the
  file-mode wrapper calls."
  [s]
  (boolean (re-matches #"[A-Za-z_][A-Za-z0-9_]*" s)))

(defn entry-name
  "The user fn name the file-mode wrapper calls: `:zig/fn` when given, else
  the Clojure fn name with hyphens as underscores, the way Clojure names
  munge for the JVM (`dot-product` becomes `dot_product`). A name still not a
  legal Zig identifier, such as `red?` or `saxpy!`, needs `:zig/fn`."
  [spec descriptor]
  (or (:zig/fn descriptor)
      (let [n (str/replace (name (:name spec)) "-" "_")]
        (if (valid-zig-ident? n)
          n
          (throw (ex-info (str "The Clojure name " (pr-str (:name spec))
                               " is not a legal Zig identifier; name the entry"
                               " fn with :zig/fn.")
                          {:level :error :error/code :clj-zig/entry-name-needed
                           :name (:name spec)}))))))

(declare zig-type pointee pointer-type error-union-struct-return?
         buffer-element wire-struct-name wire-struct
         wire-write-stmts wire-struct-free-stmts
         file-owned-buffer-slice file-simple-slice-ownership
         generate-owned-buffer-slice wire-to-nice-copy-stmts
         buffer-wire-decls buffer-carrying-slice-element?
         opt-struct-return? file-plain)

(defn- zig-type
  "The Zig type name for a normalized boundary type. Handles scalars,
  named types, and optional pointers; `param-decls` lowers slices.

  An `:optional` over a carrier scalar or a named struct lowers to a
  nullable pointer to a const value (`?*const T`), the same wire shape
  as `[:optional [:ptr :const T]]`: nil crosses as NULL, a present value
  as a native cell the callee dereferences."
  [t]
  (case (:kind t)
    :scalar   (name (:name t))
    :named    (str (:name t))
    :optional (let [pointed (:of t)]
                (cond
                  (= :scalar (:kind pointed)) (str "?*const " (zig-type pointed))
                  (= :named  (:kind pointed)) (str "?*const " (zig-type pointed))
                  :else                       (str "?" (pointer-type pointed))))
    :handle   (str "*" (zig-type (:of t)))
    (throw (ex-info "Source generation does not yet support this boundary type."
                    {:level :error
                     :error/code :clj-zig/unsupported-source-type
                     :kind (:kind t)
                     :clj-zig/type-form t}))))

(defn- pointee
  "The Zig pointee type of a slice or pointer, with its `const` qualifier."
  [{:keys [const? of]}]
  (str (when const? "const ") (zig-type of)))

(defn- pointer-type
  "The Zig type of a single- or many-item pointer."
  [{:keys [kind] :as t}]
  (str (case kind :ptr "*" :manyptr "[*]") (pointee t)))

(defn- enum-type?
  "True when a resolved named type is a `defenumz` enum, which crosses as
  a scalar backing int rather than a struct pointer."
  [t]
  (boolean (get-in t [:layout :enum])))

(defn- param-decls
  "The param data for one boundary param. A scalar, pointer, or optional
  pointer is one entry; a fixed-size array crosses as a pointer to the
  array; a slice is two entries, a many-item pointer and a `usize`
  length. A `:string` argument lowers to the same wire shape as
  `[:slice :const :u8]` (a `[*]const u8` pointer and a `usize` length)."
  [{:keys [binding type]}]
  (case (:kind type)
    :string          [(zig/param (str binding "_ptr") "[*]const u8")
                      (zig/param (str binding "_len") "usize")]
    :slice           (if (buffer-carrying-slice-element? (:of type))
                       (let [wire-t (str (wire-struct-name (:name (:layout (:of type)))))]
                         [(zig/param (str binding "_ptr") (str "[*]const " wire-t))
                          (zig/param (str binding "_len") "usize")])
                       [(zig/param (str binding "_ptr") (str "[*]" (pointee type)))
                        (zig/param (str binding "_len") "usize")])
    (:ptr :manyptr)  [(zig/param (str binding) (pointer-type type))]
    :optional        [(zig/param (str binding) (zig-type type))]
    :array           (if (buffer-carrying-slice-element? (:of type))
                       (let [wire-t (wire-struct-name (:name (:layout (:of type))))]
                         [(zig/param (str binding "_ptr") (str "*const [" (:length type) "]" wire-t))])
                       [(zig/param (str binding "_ptr") (str "*const [" (:length type) "]" (zig-type (:of type))))])
    :named           (if (enum-type? type)
                       [(zig/param (str binding) (zig-type type))]
                       (if (some :target (get-in type [:layout :fields]))
                         [(zig/param (str binding "_ptr") (str "*const " (wire-struct-name (:name (:layout type)))))]
                         [(zig/param (str binding "_ptr") (str "*const " (zig-type type)))]))
    [(zig/param (str binding) (zig-type type))]))

(defn- wire-to-nice-copy-stmts
  "Statement nodes converting one wire element into a nice record, used
  inside a reconstruction loop. Inverse of `wire-write-stmts`:
  a scalar field is assigned directly; a buffer field reinterprets the
  `{ptr, len}` pair as a real slice; a nested buffer-carrying struct
  field recurses into the inner layout."
  ([layout] (wire-to-nice-copy-stmts layout "__dst" "__src"))
  ([layout dst-path src-path]
   (mapcat (fn [{:keys [name type] :as f}]
             (cond
               (:target f)
               (let [elem (buffer-element type)]
                 [(zig/assign-stmt
                   (zig/raw-expr (str dst-path "." name))
                   (zig/raw-expr (str "@as([*]" elem
                    ", @ptrFromInt(" src-path "." name "_ptr))[0.." src-path "." name "_len]")))])

               (and (:nested f) (some :target (get-in type [:layout :fields])))
               (wire-to-nice-copy-stmts (get-in type [:layout])
                                        (str dst-path "." name)
                                        (str src-path "." name))

               :else
               [(zig/assign-stmt
                 (zig/raw-expr (str dst-path "." name))
                 (zig/raw-expr (str src-path "." name)))]))
           (:fields layout))))

(defn- reconstruction
  "Statement nodes rebuilding a binding the body uses by name: a slice or
  string from its pointer and length, an array value from its pointer, or
  a wire-to-nice conversion for a buffer-carrying struct element. The
  optional `file-mode?` flag selects between a top-level std import
  (inline mode) and an inline @import of std (file mode). Returns nil
  when no reconstruction is needed (e.g. an enum argument)."
  ([param] (reconstruction param false))
  ([{:keys [binding type]} file-mode?]
   (let [alloc (if file-mode?
                 "@import(\"std\").heap.c_allocator"
                 "std.heap.c_allocator")]
     (case (:kind type)
       :slice (if (buffer-carrying-slice-element? (:of type))
                (let [layout    (:layout (:of type))
                      type-name (:name layout)
                      copies    (wire-to-nice-copy-stmts layout)]
                  [(zig/const-stmt
                    (str "__nice_" binding)
                    (zig/raw-expr (str alloc ".alloc(" type-name ", " binding "_len) catch @panic(\"oom\")")))
                   (zig/for-stmt
                    (str "(__nice_" binding ", 0..) |*__dst, __i|")
                    (vec (concat [(zig/const-stmt "__src" (zig/raw-expr (str binding "_ptr[__i]")))]
                                 copies)))
                   (zig/defer-stmt
                    (zig/call (str alloc ".free") [(zig/ref (str "__nice_" binding))]))
                   (zig/const-stmt binding (zig/ref (str "__nice_" binding)))])
                [(zig/const-stmt binding
                  (zig/slice (zig/ref (str binding "_ptr"))
                             (zig/lit "0")
                             (zig/ref (str binding "_len"))))])
       :string [(zig/const-stmt binding
                 (zig/slice (zig/ref (str binding "_ptr"))
                            (zig/lit "0")
                            (zig/ref (str binding "_len"))))]
       :array  (if (buffer-carrying-slice-element? (:of type))
                 (let [layout    (:layout (:of type))
                       type-name (:name layout)
                       n         (:length type)
                       copies    (wire-to-nice-copy-stmts layout)]
                   [(zig/raw-stmt (str "var __nice_" binding ": [" n "]" type-name " = undefined;"))
                    (zig/for-stmt
                     (str "(0.." n ") |__i|")
                     (vec (concat [(zig/const-stmt "__src" (zig/raw-expr (str binding "_ptr.*[__i]")))
                                   (zig/raw-stmt (str "var __dst = &__nice_" binding "[__i];"))]
                                  copies)))
                    (zig/const-stmt binding (zig/ref (str "__nice_" binding)))])
                 [(zig/const-stmt binding (zig/deref (zig/ref (str binding "_ptr"))))])
       :named  (cond
                 (enum-type? type) nil
                 (some :target (get-in type [:layout :fields]))
                 (let [layout    (:layout type)
                       type-name (:name layout)
                       copies    (wire-to-nice-copy-stmts layout
                                                          (str "__nice_" binding)
                                                          (str "__src_" binding))]
                   (vec (concat
                         [(zig/const-stmt (str "__src_" binding) (zig/deref (zig/ref (str binding "_ptr"))))
                          (zig/raw-stmt (str "var __nice_" binding ": " type-name " = undefined;"))]
                         copies
                         [(zig/const-stmt binding (zig/ref (str "__nice_" binding)))])))
                 :else [(zig/const-stmt binding (zig/deref (zig/ref (str binding "_ptr"))))])
       nil))))

(defn- param-args
  "The argument names the wrapper passes to an inner function, mirroring
  `param-decls`."
  [{:keys [binding type]}]
  (case (:kind type)
    (:slice :string) [(str binding "_ptr") (str binding "_len")]
    :array           [(str binding "_ptr")]
    :named           (if (enum-type? type) [(str binding)] [(str binding "_ptr")])
    [(str binding)]))

(def ^:private error-name-cap
  "The byte length the error-name buffer is clamped to before copy-out."
  255)

(defn- error-catch-clause
  "The `catch` clause that writes the caught error's name to the caller's
  buffer (clamped to `error-name-cap`) and returns. `void?` picks `return;`
  (a void wrapper return) over `return undefined;` (a value return); a
  struct return is always void from the wrapper's side."
  [void?]
  (str "catch |__err_value| {\n"
       "    const __name = @errorName(__err_value);\n"
       "    const __n = @min(__name.len, " error-name-cap ");\n"
       "    @memcpy(__err[0..__n], __name[0..__n]);\n"
       "    __errlen.* = __n;\n"
       "    return" (when-not void? " undefined") ";\n"
       "}"))

(defn- free-body-or-noop
  "The free-shim body: the free statements, or a no-op `_ = __ret;` when the
  struct has no buffer field to free (a bare struct must still consume its
  sole arg so Zig does not warn it is unused)."
  [free-stmts]
  (if (seq free-stmts) (vec free-stmts) [(zig/raw-stmt "_ = __ret;")]))

(defn- impl-body
  "The statement nodes for a function body: reconstruction prelude
  statements followed by the user body as a single raw statement."
  [params body]
  (vec (concat (mapcat #(reconstruction % false) params)
               [(zig/raw-stmt body)])))

(defn- generate-plain
  "A direct `export fn`: the body runs in the exported function itself."
  [{:keys [params ret] sym :symbol} body]
  [(zig/export-fn-decl
    sym
    (mapcat param-decls params)
    (zig-type ret)
    (impl-body params body))])

(defn- generate-error-union
  "An inner impl fn holding the user body returns `E!T`. The exported
  wrapper calls it, writes the error name to the caller's buffer on
  failure, and returns the value (or void)."
  [{:keys [params ret] sym :symbol} body]
  (let [params-data (mapcat param-decls params)
        args-str    (str/join ", " (mapcat param-args params))
        err-set     (str (:error ret))
        value-t     (zig-type (:of ret))
        void?       (= "void" value-t)
        out-params  [(zig/param "__err" "[*]u8") (zig/param "__errlen" "*usize")]
        on-error-text (error-catch-clause void?)]
    [(zig/fn-decl
      (str sym "__impl")
      params-data
      (str err-set "!" value-t)
      (impl-body params body))
     (zig/export-fn-decl
      sym
      (vec (concat params-data out-params))
      value-t
      (if void?
        [(zig/raw-stmt (str sym "__impl(" args-str ") " on-error-text ";"))
         (zig/assign-stmt (zig/deref (zig/ref "__errlen")) (zig/lit "0"))]
        [(zig/raw-stmt (str "const __value = " sym "__impl(" args-str ") " on-error-text ";"))
         (zig/assign-stmt (zig/deref (zig/ref "__errlen")) (zig/lit "0"))
         (zig/return-stmt (zig/ref "__value"))]))]))

(defn- generate-struct-return
  "An inner impl fn holding the user body returns the struct by value; the
  exported wrapper calls it and writes the result through an out-pointer.
  The aggregate crosses the C ABI by reference."
  [{:keys [params ret] sym :symbol} body]
  (let [params-data (mapcat param-decls params)
        args-str    (str/join ", " (mapcat param-args params))
        ret-t       (zig-type ret)]
    [(zig/fn-decl
      (str sym "__impl")
      params-data
      ret-t
      (impl-body params body))
     (zig/export-fn-decl
      sym
      (conj (vec params-data) (zig/param "__ret" (str "*" ret-t)))
      "void"
      [(zig/assign-stmt
        (zig/deref (zig/ref "__ret"))
        (zig/raw-expr (str sym "__impl(" args-str ")")))])]))

(defn- ownership-slice
  "The conceptual element-slice an ownership return writes out as a pointer
  and length. A `:string` return lowers to `[]u8` exactly like a `:bytes`
  return over a `[:slice :u8]`, so its inner `__impl` returns `[]u8` and the
  wrapper writes its pointer and length to two out-params and emits a free
  shim; the only difference from `:bytes` is the Clojure-side read (UTF-8
  decode with replacement instead of a raw byte[])."
  [ret]
  (case (:kind ret)
    :string {:const? false :of {:kind :scalar :name :u8}}
    (:of ret)))

(defn- buffer-carrying-slice-element?
  "True when a slice element is a named struct with at least one buffer
  field. Such an element needs the nice-to-wire transform: the body
  builds nice records (with real slice fields the FFM reader cannot read
  at known offsets), so the wrapper copies each into a wire (extern) slab
  and a walking free shim releases each element's buffers then the slab."
  [elem]
  (and (= :named (:kind elem))
       (let [layout (:layout elem)]
         (and layout
              (not (:enum layout))
              (some :target (:fields layout))))))

(defn- generate-simple-slice-ownership
  "An inner impl fn holding the user body returns a slice; the exported
  wrapper writes the slice's pointer and length to two out-params. An
  `:owned` return also emits a free shim: the body allocates with
  `std.heap.c_allocator` and the shim frees it after the caller copies
  the elements out. A `:string` return lowers to the same `[]u8` shape
  and always carries the free shim."
  [{:keys [params ret] sym :symbol} body]
  (let [params-data (mapcat param-decls params)
        args-str    (str/join ", " (mapcat param-args params))
        slice       (ownership-slice ret)
        elem-t      (zig-type (:of slice))
        ret-t       (str "[]" (when (:const? slice) "const ") elem-t)
        owned?      (contains? #{:owned :bytes :string} (:kind ret))
        all-params  (conj (vec params-data)
                          (zig/param "__ptr" "*usize")
                          (zig/param "__len" "*usize"))]
    (vec (concat
          [(zig/fn-decl
            (str sym "__impl")
            params-data
            ret-t
            (impl-body params body))
           (zig/export-fn-decl
            sym
            all-params
            "void"
            [(zig/const-stmt "__r" (zig/raw-expr (str sym "__impl(" args-str ")")))
             (zig/assign-stmt (zig/deref (zig/ref "__ptr"))
                              (zig/call "@intFromPtr" [(zig/field (zig/ref "__r") "ptr")]))
             (zig/assign-stmt (zig/deref (zig/ref "__len"))
                              (zig/field (zig/ref "__r") "len"))])]
          (when owned?
            [(zig/export-fn-decl
              (str sym "__free")
              [(zig/param "__ptr" "usize") (zig/param "__len" "usize")]
              "void"
              [(zig/raw-stmt
                (str "const __p: [*]" elem-t " = @ptrFromInt(__ptr);\n"
                     "std.heap.c_allocator.free(__p[0..__len]);"))])])))))

(defn- generate-owned-buffer-slice
  "An owned slice whose element is a buffer-carrying struct. The body
  returns `[]NiceRecord` (c_allocator); the wrapper allocates a `[]Wire`
  slab, copies each nice record's fields (scalars direct, buffers as
  ptr+len), frees the nice struct array, and writes the wire slab's
  pointer and length to the out-params. A walking free shim iterates the
  wire slab, frees each element's buffer fields, then frees the slab
  itself."
  [{:keys [params ret] sym :symbol} body]
  (let [layout      (get-in ret [:of :of :layout])
        type-name   (:name layout)
        wire-t      (str (wire-struct-name type-name))
        params-data (mapcat param-decls params)
        args-str    (str/join ", " (mapcat param-args params))
        all-params  (conj (vec params-data)
                          (zig/param "__ptr" "*usize")
                          (zig/param "__len" "*usize"))
        copies      (wire-write-stmts layout "__wire[__i]" "__src")
        frees       (wire-struct-free-stmts layout "__e" "std.heap.c_allocator")]
    [(zig/fn-decl
      (str sym "__impl")
      params-data
      (str "[]" type-name)
      (impl-body params body))
     (zig/export-fn-decl
      sym
      all-params
      "void"
      (vec (concat
            [(zig/const-stmt "__nice" (zig/raw-expr (str sym "__impl(" args-str ")")))
             (zig/raw-stmt
              (str "const __wire = std.heap.c_allocator.alloc(" wire-t ", __nice.len) catch @panic(\"oom\");"))]
            [(zig/for-stmt "(__nice, 0..) |__src, __i|" (vec copies))]
            [(zig/raw-stmt "std.heap.c_allocator.free(__nice);")
             (zig/assign-stmt (zig/deref (zig/ref "__ptr"))
                              (zig/call "@intFromPtr" [(zig/field (zig/ref "__wire") "ptr")]))
             (zig/assign-stmt (zig/deref (zig/ref "__len"))
                              (zig/field (zig/ref "__wire") "len"))])))
     (zig/export-fn-decl
      (str sym "__free")
      [(zig/param "__ptr" "usize") (zig/param "__len" "usize")]
      "void"
      (vec (concat
            [(zig/raw-stmt (str "const __p: [*]" wire-t " = @ptrFromInt(__ptr);"))
             (zig/const-stmt "__slice" (zig/raw-expr "__p[0..__len]"))]
            [(zig/for-stmt "(__slice) |__e|" (vec frees))]
             [(zig/raw-stmt "std.heap.c_allocator.free(__slice);")])))]))

(defn- generate-ownership
  "Dispatch an owned, borrowed, bytes, or string slice return: the simple
  one-slab path for a scalar or scalar-only-struct element, or the
  nice-to-wire transform path for a buffer-carrying struct element."
  [spec body]
  (let [slice (ownership-slice (:ret spec))]
    (if (buffer-carrying-slice-element? (:of slice))
      (generate-owned-buffer-slice spec body)
      (generate-simple-slice-ownership spec body))))

(defn- needs-std?
  "True when a function's generated wrapper needs `std` in scope: an owned
  or `:string` return uses `std.heap.c_allocator` in its free shim, a handle
  in any position is allocated or freed with `std`, an error-union over a
  struct emits a per-field free shim, and a const slice or array of
  buffer-carrying struct arguments allocates a nice-record slab with
  `c_allocator` in its reconstruction."
  [{:keys [params ret]}]
  (or (contains? #{:owned :bytes :string} (:kind ret))
      (= :stream (:kind ret))
      (error-union-struct-return? ret)
      (opt-struct-return? ret)
      (= :handle (:kind ret))
      (some #(= :handle (:kind (:type %))) params)
      (some #(and (contains? #{:slice :array} (:kind (:type %)))
                  (buffer-carrying-slice-element? (:of (:type %))))
            params)))

(defn- owned-record-return?
  "True when a return is an `:owned` or `:borrowed` wrapper around a named
  record, the ADR 21 result-record shape. Distinguished from an owned or
  borrowed slice return, which the existing `generate-ownership` path owns."
  [ret]
  (and (contains? #{:owned :borrowed} (:kind ret))
       (= :named (get-in ret [:of :kind]))))

(defn- opt-struct-return?
  "True when a return is an `:optional` wrapper around a named struct (not
  an enum). The body returns null or a c_allocator pointer to the struct;
  the wrapper is a plain return plus a free shim that frees buffer fields
  then destroys the allocation."
  [ret]
  (and (= :optional (:kind ret))
       (= :named (get-in ret [:of :kind]))
       (not (enum-type? (:of ret)))))

(defn- wire-struct-name
  "The C-ABI wire struct name for a result record: the nice record type with
  a `__wire` suffix. The nice record (with real slice fields) and the wire
  struct (with `usize` ptr/len words) are distinct types; the wrapper writes
  the former into the latter field by field."
  [type-name]
  (symbol (str type-name "__wire")))

(defn- wire-struct
  "The wire `extern struct` declaration for a record layout, named with the
  `__wire` suffix. Reuses `layout/zig-struct`, which expands each buffer
  field to a `usize` pointer and length (ADR 21 section 4)."
  [record-layout]
  (layout/zig-struct (assoc record-layout :name (wire-struct-name (:name record-layout)))))

(defn- buffer-element
  "The Zig element type name a buffer field carries, for the free shim's
  pointer cast. A `:string` or `:bytes` field carries `u8` bytes; a slice
  field carries its scalar element."
  [t]
  (case (:kind t)
    :string "u8"
    (:owned :borrowed :bytes) (name (get-in t [:of :of :name]))
    :slice (name (get-in t [:of :name]))))

(defn- wire-write-stmts
  "Statement nodes writing the nice record `__r` into the wire out-struct
  `__ret`. A scalar or enum field is assigned directly; a buffer field
  writes its pointer and length into the two wire slots; a nested
  buffer-carrying struct field recurses into the inner layout."
  ([layout] (wire-write-stmts layout "__ret.*" "__r"))
  ([layout wire-path src-path]
   (mapcat (fn [{:keys [name type] :as f}]
             (cond
               (:target f)
               [(zig/assign-stmt
                 (zig/raw-expr (str wire-path "." name "_ptr"))
                 (zig/call "@intFromPtr" [(zig/raw-expr (str src-path "." name ".ptr"))]))
                (zig/assign-stmt
                 (zig/raw-expr (str wire-path "." name "_len"))
                 (zig/raw-expr (str src-path "." name ".len")))]

               (and (:nested f) (some :target (get-in type [:layout :fields])))
               (wire-write-stmts (get-in type [:layout])
                                 (str wire-path "." name)
                                 (str src-path "." name))

               :else
               [(zig/assign-stmt
                 (zig/raw-expr (str wire-path "." name))
                 (zig/raw-expr (str src-path "." name)))]))
           (:fields layout))))

(defn- wire-struct-free-stmts
  "Statement nodes for freeing a wire struct's buffer fields, reading
  `{ptr, len}` back from each owned buffer field and freeing it. Recurses
  into nested buffer-carrying struct wire fields. Skips borrowed fields.
  The `alloc` argument is the allocator expression (`std.heap.c_allocator`
  for inline mode, `@import(\"std\").heap.c_allocator` for file mode)."
  ([layout] (wire-struct-free-stmts layout "__ret" "std.heap.c_allocator"))
  ([layout elem-path alloc]
   (mapcat (fn [{:keys [name type] :as f}]
             (cond
               (and (:target f) (= :borrowed (:kind type)))
               nil

               (:target f)
               (let [elem (buffer-element type)]
                 [(zig/raw-stmt
                   (str alloc ".free(@as([*]" elem
                    ", @ptrFromInt(" elem-path "." name "_ptr))[0.." elem-path "." name "_len]);"))])

               (and (:nested f) (some :target (get-in type [:layout :fields])))
               (wire-struct-free-stmts (get-in type [:layout])
                                       (str elem-path "." name) alloc)

               :else nil))
           (:fields layout))))

(defn- generate-owned-struct-return
  "An inner impl fn holding the user body returns the nice record by value;
  the exported wrapper writes each field into the wire extern struct through
  an out-pointer. An `:owned` result also emits a per-field `__free` shim
  that frees every buffer field; a `:borrowed` result emits no shim."
  [{:keys [params ret] sym :symbol} body]
  (let [layout     (get-in ret [:of :layout])
        type-name  (:name layout)
        wire-t     (str (wire-struct-name type-name))
        params-data (mapcat param-decls params)
        args-str   (str/join ", " (mapcat param-args params))
        writes     (wire-write-stmts layout)
        owned?     (= :owned (:kind ret))
        free-stmts (wire-struct-free-stmts layout)
        free-body  (free-body-or-noop free-stmts)]
    (vec (concat
          [(zig/fn-decl
            (str sym "__impl")
            params-data
            (str type-name)
            (impl-body params body))
           (zig/export-fn-decl
            sym
            (conj (vec params-data) (zig/param "__ret" (str "*" wire-t)))
            "void"
            (vec (concat
                  [(zig/const-stmt "__r" (zig/raw-expr (str sym "__impl(" args-str ")")))]
                  writes)))]
          (when owned?
            [(zig/export-fn-decl
              (str sym "__free")
              [(zig/param "__ret" (str "*const " wire-t))]
              "void"
              free-body)])))))

(defn- generate-error-union-struct-return
  "An inner impl fn holding the user body returns `E!NiceRecord`; the export
  wrapper `catch`-es the error, writes the error name to the caller's buffer
  and returns WITHOUT writing the struct on failure, and on success writes
  each field of the nice record into the wire extern struct through `__ret`
  and sets `__errlen` to 0."
  [{:keys [params ret] sym :symbol} body]
  (let [layout      (get-in ret [:of :layout])
        type-name   (:name layout)
        wire-t      (str (wire-struct-name type-name))
        params-data (mapcat param-decls params)
        args-str    (str/join ", " (mapcat param-args params))
        err-set     (str (:error ret))
        out-params  [(zig/param "__err" "[*]u8")
                     (zig/param "__errlen" "*usize")
                     (zig/param "__ret" (str "*" wire-t))]
        writes      (wire-write-stmts layout)
        free-stmts  (wire-struct-free-stmts layout)
        free-body   (free-body-or-noop free-stmts)
        on-error    (error-catch-clause true)]
    [(zig/fn-decl
      (str sym "__impl")
      params-data
      (str err-set "!" type-name)
      (impl-body params body))
     (zig/export-fn-decl
      sym
      (vec (concat params-data out-params))
      "void"
      (vec (concat
            [(zig/raw-stmt (str "const __r = " sym "__impl(" args-str ") " on-error ";"))
             (zig/assign-stmt (zig/deref (zig/ref "__errlen")) (zig/lit "0"))]
            writes)))
     (zig/export-fn-decl
      (str sym "__free")
      [(zig/param "__ret" (str "*const " wire-t))]
      "void"
      free-body)]))

(defn- error-union-struct-return?
  "True when a return is an `:error-union` over a named non-enum (a struct
  record). The enum arm crosses as its i32 backing and reuses the plain
  error-union path; only a struct combines the error-union out-params with
  the struct out-pointer."
  [ret]
  (and (= :error-union (:kind ret))
       (= :named (get-in ret [:of :kind]))
       (not (enum-type? (:of ret)))))

(defn- nice-struct-free-stmts
  "Statement nodes for freeing a nice struct's buffer fields (with real
  slice fields), used in the optional-struct free shim. Each owned buffer
  field is freed directly; nested buffer-carrying struct fields recurse."
  ([layout] (nice-struct-free-stmts layout "__p" "std.heap.c_allocator"))
  ([layout ptr-path alloc]
   (mapcat (fn [{:keys [name type] :as f}]
             (cond
               (and (:target f) (= :borrowed (:kind type)))
               nil

               (:target f)
               [(zig/raw-stmt (str alloc ".free(" ptr-path "." name ");"))]

               (and (:nested f) (some :target (get-in type [:layout :fields])))
               (nice-struct-free-stmts (get-in type [:layout])
                                       (str ptr-path "." name) alloc)

               :else nil))
           (:fields layout))))

(defn- optional-struct-free-shim
  "The `__free` shim declaration node for an optional-struct return. The
  body returns null or a c_allocator pointer to the nice struct; the shim
  null-checks, frees each buffer field, then destroys the struct
  allocation."
  [{:keys [ret] sym :symbol}]
  (let [layout     (get-in ret [:of :layout])
        type-name  (:name layout)
        free-stmts (nice-struct-free-stmts layout)]
    (zig/export-fn-decl
     (str sym "__free")
     [(zig/param "__ptr" "usize")]
     "void"
     (vec (concat
           [(zig/raw-stmt "if (__ptr == 0) return;")
            (zig/raw-stmt (str "const __p: *" type-name " = @ptrFromInt(__ptr);"))]
           free-stmts
           [(zig/raw-stmt "std.heap.c_allocator.destroy(__p);")])))))

(defn- generate-optional-struct-return
  "A plain `export fn` returning `?*const Type`, plus a `__free` shim."
  [spec body]
  (vec (concat (generate-plain spec body)
               [(optional-struct-free-shim spec)])))

(defn- file-optional-struct-return
  "A file-mode `export fn` returning `?*const Type`, plus a `__free` shim
  using inline @import of std so the user file's imports are untouched."
  [{:keys [ret] sym :symbol :as spec} entry]
  (let [layout     (get-in ret [:of :layout])
        type-name  (:name layout)
        free-stmts (nice-struct-free-stmts layout "__p" "@import(\"std\").heap.c_allocator")]
    (vec (concat (file-plain spec entry)
                 [(zig/export-fn-decl
                   (str sym "__free")
                   [(zig/param "__ptr" "usize")]
                   "void"
                   (vec (concat
                         [(zig/raw-stmt "if (__ptr == 0) return;")
                          (zig/raw-stmt (str "const __p: *" type-name " = @ptrFromInt(__ptr);"))]
                         free-stmts
                         [(zig/raw-stmt "@import(\"std\").heap.c_allocator.destroy(__p);")])))]))))

(defn- buffer-wire-decls
  "Wire struct declaration nodes for every struct type the spec references
  in a wire position. Deduplicated by name and emitted once at the top
  level."
  [spec]
  (let [collect-wire (fn collect-wire [layout]
                       (let [nested (->> (:fields layout)
                                         (keep (fn [{:keys [type nested]}]
                                                 (when (and nested
                                                            (some :target (get-in type [:layout :fields])))
                                                   (collect-wire (get-in type [:layout])))))
                                         (apply concat))]
                         (distinct (concat nested [layout]))))
        param-layouts (keep (fn [p]
                              (let [t (:type p)]
                                (cond
                                  (and (contains? #{:slice :array} (:kind t))
                                       (buffer-carrying-slice-element? (:of t)))
                                  (:layout (:of t))

                                  (and (= :named (:kind t))
                                       (not (enum-type? t))
                                       (some :target (get-in t [:layout :fields])))
                                  (:layout t)

                                  :else nil)))
                            (:params spec))
        ret            (:ret spec)
        ret-layouts    (cond
                         (and (contains? #{:owned :borrowed} (:kind ret))
                              (= :slice (get-in ret [:of :kind]))
                              (buffer-carrying-slice-element? (get-in ret [:of :of])))
                         [(get-in ret [:of :of :layout])]

                         (and (contains? #{:owned :borrowed} (:kind ret))
                              (= :named (get-in ret [:of :kind]))
                              (not (enum-type? (:of ret))))
                         [(get-in ret [:of :layout])]

                         (and (= :error-union (:kind ret))
                              (= :named (get-in ret [:of :kind]))
                              (not (enum-type? (:of ret))))
                         [(get-in ret [:of :layout])]

                         :else [])
        all-layouts    (distinct (mapcat collect-wire (concat param-layouts ret-layouts)))]
    (map wire-struct all-layouts)))

(defn- generate-stream-return
  "A streaming return (ADR 50). Three exported symbols: the init fn wraps
  the body in an inner impl returning the pointer as usize; the next fn
  calls the iterator's `:next` function directly and writes the element
  to an out-pointer; the free fn calls the `:deinit` function directly."
  [{:keys [params ret] sym :symbol} body]
  (let [elem-t    (zig-type (:of ret))
        iter-name (str (:iter-type ret))
        next-fn   (get-in ret [:iter-layout :clj-zig/iter :next])
        deinit-fn (get-in ret [:iter-layout :clj-zig/iter :deinit])
        params-data (mapcat param-decls params)
        args-str   (str/join ", " (mapcat param-args params))]
    [(zig/fn-decl
      (str sym "__impl")
      params-data
      (str "*" iter-name)
      (impl-body params body))
     (zig/export-fn-decl
      sym
      params-data
      "usize"
      [(zig/return-stmt
        (zig/call "@intFromPtr" [(zig/raw-expr (str sym "__impl(" args-str ")"))]))])
     (zig/export-fn-decl
      (str sym "__next")
      [(zig/param "__iter" "usize") (zig/param "__out" (str "*" elem-t))]
      "bool"
      [(zig/raw-stmt (str "const __self: *" iter-name " = @ptrFromInt(__iter);"))
       (zig/raw-stmt
        (str "if (" next-fn "(__self)) |__val| {\n"
             "    __out.* = __val;\n"
             "    return true;\n"
             "}"))
       (zig/return-stmt (zig/lit "false"))])
     (zig/export-fn-decl
      (str sym "__free")
      [(zig/param "__iter" "usize")]
      "void"
      [(zig/raw-stmt (str "const __self: *" iter-name " = @ptrFromInt(__iter);"))
       (zig/raw-stmt (str deinit-fn "(__self);"))])]))

(defn- return-shape
  "Classify a return type into one dispatch keyword the inline and file
  generators share, so the shape predicates live in one place and each
  mode dispatches from a keyword table. Mirrors ffm's `classify-return`:
  the two modes never drift apart when a shape is added or adjusted."
  [ret]
  (cond
    (= :stream (:kind ret))                                    :stream
    (error-union-struct-return? ret)                           :error-union-struct
    (= :error-union (:kind ret))                               :error-union
    (owned-record-return? ret)                                 :owned-record
    (contains? #{:owned :borrowed :bytes :string} (:kind ret)) :ownership
    (opt-struct-return? ret)                                   :optional-struct
    (and (= :named (:kind ret)) (enum-type? ret))              :named-enum
    (= :named (:kind ret))                                     :named-struct
    :else                                                      :plain))

(defn- generate-inline
  "The inline-mode declarations: the user's body string is spliced into
  the function body. Wire struct declarations and the std import are
  emitted at the top level. Returns a vector of declaration nodes."
  [{:keys [ret] :as spec} body]
  (let [core      (case (return-shape ret)
                    :stream             (generate-stream-return spec body)
                    :error-union-struct (generate-error-union-struct-return spec body)
                    :error-union        (generate-error-union spec body)
                    :owned-record       (generate-owned-struct-return spec body)
                    :ownership          (generate-ownership spec body)
                    :optional-struct    (generate-optional-struct-return spec body)
                    :named-enum         (generate-plain spec body)
                    :named-struct       (generate-struct-return spec body)
                    :plain              (generate-plain spec body))
        wire-decls (buffer-wire-decls spec)
        std?       (needs-std? spec)]
    (vec (concat
          (when std? [(zig/const-decl "std" (zig/raw-expr "@import(\"std\")"))])
          wire-decls
          core))))

;; File mode: the wrapper calls a user-written pub fn

(defn- user-call
  "The Zig expression calling the user's `entry` fn with each binding by
  its name. Slices, arrays, and structs arrive reconstructed; enums and
  scalars arrive by value."
  [entry params]
  (str entry "(" (str/join ", " (map #(str (:binding %)) params)) ")"))

(defn- wrapper-prelude
  "Reconstruction statement nodes for a file-mode wrapper: the statements
  that rebuild slice, array, and struct arguments before calling the user
  fn. Returns an empty vector when no param needs rebuilding."
  [params]
  (vec (mapcat #(reconstruction % true) params)))

(defn- file-plain
  "A file-mode `export fn` that reconstructs its slice and struct args and
  returns the user fn's result directly (scalar, enum, or void)."
  [{:keys [params ret] sym :symbol} entry]
  [(zig/export-fn-decl
    sym
    (mapcat param-decls params)
    (zig-type ret)
    (vec (concat (wrapper-prelude params)
                 [(zig/return-stmt (zig/raw-expr (user-call entry params)))])))])

(defn- file-error-union
  "A file-mode `export fn` that calls the user fn and translates a returned
  error into the caller's error-name buffer."
  [{:keys [params ret] sym :symbol} entry]
  (let [params-data (mapcat param-decls params)
        value-t     (zig-type (:of ret))
        void?       (= "void" value-t)
        out-params  [(zig/param "__err" "[*]u8") (zig/param "__errlen" "*usize")]
        call        (user-call entry params)
        on-error    (error-catch-clause void?)]
    [(zig/export-fn-decl
      sym
      (vec (concat params-data out-params))
      value-t
      (vec (concat
            (wrapper-prelude params)
            (if void?
              [(zig/raw-stmt (str call " " on-error ";"))
               (zig/assign-stmt (zig/deref (zig/ref "__errlen")) (zig/lit "0"))]
              [(zig/raw-stmt (str "const __value = " call " " on-error ";"))
               (zig/assign-stmt (zig/deref (zig/ref "__errlen")) (zig/lit "0"))
               (zig/return-stmt (zig/ref "__value"))]))))]))

(defn- file-struct-return
  "A file-mode `export fn` that calls the user fn and writes the returned
  struct through an out-pointer (the aggregate crosses by reference)."
  [{:keys [params ret] sym :symbol} entry]
  (let [params-data (mapcat param-decls params)
        ret-t       (zig-type ret)]
    [(zig/export-fn-decl
      sym
      (conj (vec params-data) (zig/param "__ret" (str "*" ret-t)))
      "void"
      (vec (concat (wrapper-prelude params)
                   [(zig/assign-stmt
                     (zig/deref (zig/ref "__ret"))
                     (zig/raw-expr (user-call entry params)))])))]))

(defn- file-ownership
  "Dispatch a file-mode owned, borrowed, bytes, or string slice return:
  the simple one-slab path, or the nice-to-wire transform path for a
  buffer-carrying struct element."
  [{:keys [ret] :as spec} entry]
  (let [slice (ownership-slice ret)]
    (if (buffer-carrying-slice-element? (:of slice))
      (file-owned-buffer-slice spec entry)
      (file-simple-slice-ownership spec entry))))

(defn- file-simple-slice-ownership
  "A file-mode `export fn` that calls the user fn and writes the returned
  slice's pointer and length to two out-params. An `:owned` or `:string`
  return also emits a free shim importing `std` inline."
  [{:keys [params ret] sym :symbol} entry]
  (let [params-data (mapcat param-decls params)
        slice       (ownership-slice ret)
        elem-t      (zig-type (:of slice))
        owned?      (contains? #{:owned :bytes :string} (:kind ret))
        all-params  (conj (vec params-data)
                          (zig/param "__ptr" "*usize")
                          (zig/param "__len" "*usize"))]
    (vec (concat
          [(zig/export-fn-decl
            sym
            all-params
            "void"
            (vec (concat
                  (wrapper-prelude params)
                  [(zig/const-stmt "__r" (zig/raw-expr (user-call entry params)))
                   (zig/assign-stmt (zig/deref (zig/ref "__ptr"))
                                    (zig/call "@intFromPtr" [(zig/field (zig/ref "__r") "ptr")]))
                   (zig/assign-stmt (zig/deref (zig/ref "__len"))
                                    (zig/field (zig/ref "__r") "len"))])))]
          (when owned?
            [(zig/export-fn-decl
              (str sym "__free")
              [(zig/param "__ptr" "usize") (zig/param "__len" "usize")]
              "void"
              [(zig/raw-stmt
                (str "const __p: [*]" elem-t " = @ptrFromInt(__ptr);\n"
                     "@import(\"std\").heap.c_allocator.free(__p[0..__len]);"))])])))))

(defn- file-owned-buffer-slice
  "File-mode owned slice of buffer-carrying structs: calls the user's
  `pub fn`, transforms it into a wire slab, and emits a walking free shim.
  `std` is imported inline so a user file's own imports never collide."
  [{:keys [params ret] sym :symbol} entry]
  (let [layout      (get-in ret [:of :of :layout])
        type-name   (:name layout)
        wire-t      (str (wire-struct-name type-name))
        params-data (mapcat param-decls params)
        all-params  (conj (vec params-data)
                          (zig/param "__ptr" "*usize")
                          (zig/param "__len" "*usize"))
        copies      (wire-write-stmts layout "__wire[__i]" "__src")
        frees       (wire-struct-free-stmts layout "__e" "@import(\"std\").heap.c_allocator")]
    [(zig/export-fn-decl
      sym
      all-params
      "void"
      (vec (concat
            (wrapper-prelude params)
            [(zig/const-stmt "__nice" (zig/raw-expr (user-call entry params)))
             (zig/raw-stmt
              (str "const __wire = @import(\"std\").heap.c_allocator.alloc(" wire-t ", __nice.len) catch @panic(\"oom\");"))]
            [(zig/for-stmt "(__nice, 0..) |__src, __i|" (vec copies))]
            [(zig/raw-stmt "@import(\"std\").heap.c_allocator.free(__nice);")
             (zig/assign-stmt (zig/deref (zig/ref "__ptr"))
                              (zig/call "@intFromPtr" [(zig/field (zig/ref "__wire") "ptr")]))
             (zig/assign-stmt (zig/deref (zig/ref "__len"))
                              (zig/field (zig/ref "__wire") "len"))])))
     (zig/export-fn-decl
      (str sym "__free")
      [(zig/param "__ptr" "usize") (zig/param "__len" "usize")]
      "void"
      (vec (concat
            [(zig/raw-stmt (str "const __p: [*]" wire-t " = @ptrFromInt(__ptr);"))
             (zig/const-stmt "__slice" (zig/raw-expr "__p[0..__len]"))]
            [(zig/for-stmt "(__slice) |__e|" (vec frees))]
            [(zig/raw-stmt "@import(\"std\").heap.c_allocator.free(__slice);")])))]))

(defn- file-owned-struct-return
  "A file-mode `export fn` that calls the user fn and writes the returned
  nice record field by field into the wire extern struct through an
  out-pointer. An `:owned` result also emits a per-field `__free` shim
  importing `std` inline."
  [{:keys [params ret] sym :symbol} entry]
  (let [layout     (get-in ret [:of :layout])
        type-name  (:name layout)
        wire-t     (str (wire-struct-name type-name))
        params-data (mapcat param-decls params)
        writes     (wire-write-stmts layout)
        owned?     (= :owned (:kind ret))
        free-stmts (wire-struct-free-stmts layout "__ret" "@import(\"std\").heap.c_allocator")
        free-body  (free-body-or-noop free-stmts)]
    (vec (concat
          [(zig/export-fn-decl
            sym
            (conj (vec params-data) (zig/param "__ret" (str "*" wire-t)))
            "void"
            (vec (concat
                  (wrapper-prelude params)
                  [(zig/const-stmt "__r" (zig/raw-expr (user-call entry params)))]
                  writes)))]
          (when owned?
            [(zig/export-fn-decl
              (str sym "__free")
              [(zig/param "__ret" (str "*const " wire-t))]
              "void"
              free-body)])))))

(defn- file-error-union-struct-return
  "A file-mode `export fn` that calls the user fn and translates a
  returned `E!NiceRecord` error union into the caller's error-name buffer
  and a wire struct out-pointer."
  [{:keys [params ret] sym :symbol} entry]
  (let [layout      (get-in ret [:of :layout])
        type-name   (:name layout)
        wire-t      (str (wire-struct-name type-name))
        params-data (mapcat param-decls params)
        out-params  [(zig/param "__err" "[*]u8")
                     (zig/param "__errlen" "*usize")
                     (zig/param "__ret" (str "*" wire-t))]
        writes      (wire-write-stmts layout)
        free-stmts (wire-struct-free-stmts layout "__ret" "@import(\"std\").heap.c_allocator")
        free-body   (free-body-or-noop free-stmts)
        on-error    (error-catch-clause true)]
    [(zig/export-fn-decl
      sym
      (vec (concat params-data out-params))
      "void"
      (vec (concat
            (wrapper-prelude params)
            [(zig/raw-stmt (str "const __r = " (user-call entry params) " " on-error ";"))
             (zig/assign-stmt (zig/deref (zig/ref "__errlen")) (zig/lit "0"))]
            writes)))
     (zig/export-fn-decl
      (str sym "__free")
      [(zig/param "__ret" (str "*const " wire-t))]
      "void"
      free-body)]))

(defn- generate-file
  "The file-mode declarations: an `export fn` that reconstructs args and
  calls the user's `pub fn`. Wire struct declarations are emitted for any
  buffer-carrying struct type in the spec. Returns a vector of declaration
  nodes."
  [{:keys [ret] :as spec} entry]
  (let [wire-decls (buffer-wire-decls spec)
        ;; A :stream return has no file-mode generator; it falls through to
        ;; the plain wrapper, as the inline-only stream path does.
        core       (case (return-shape ret)
                     :error-union-struct (file-error-union-struct-return spec entry)
                     :error-union        (file-error-union spec entry)
                     :owned-record       (file-owned-struct-return spec entry)
                     :ownership          (file-ownership spec entry)
                     :optional-struct    (file-optional-struct-return spec entry)
                     :named-enum         (file-plain spec entry)
                     :named-struct       (file-struct-return spec entry)
                     (:stream :plain)    (file-plain spec entry))]
    (vec (concat wire-decls core))))

(defn inline-nodes
  "The declaration nodes for the inline-mode wrapper of `spec` with `body`.
  The user's body string is spliced into the function body. Returns a
  vector of declaration nodes."
  [spec body]
  (generate-inline spec body))

(defn file-nodes
  "The declaration nodes for the file-mode wrapper of `spec` calling the
  user's `entry` fn. Returns a vector of declaration nodes."
  [spec entry]
  (generate-file spec entry))

(defn generate
  "Render the Zig wrapper source for `spec` as text. In the default inline
  mode the user's `body` string is spliced into the function body. In file
  mode (`opts {:mode :file :entry \"name\"}`) the wrapper reconstructs its
  arguments and calls the user's `pub fn`."
  ([spec body] (generate spec body {:mode :inline}))
  ([spec body {:keys [mode entry]}]
   (str (zig/render (if (= :file mode)
                      (generate-file spec entry)
                      (generate-inline spec body)))
        "\n")))

;; track-allocations profiling build (ADR 12, ADR 41)

(def ^:private tracking-allocator-block
  "The counting allocator the profiling build routes c_allocator through
  (ADR 12, ADR 41). Zig 0.16.0 has no std.heap.TrackingAllocator, so this
  vtable wrapper over c_allocator is the fallback; it increments on alloc
  and on a resize/remap that grows."
  (str "const __clj_zig_tstd = @import(\"std\");\n"
       "var __clj_zig_alloc_count: usize = 0;\n"
       "\n"
       "const __clj_zig_Track = struct {\n"
       "    fn alloc(_: *anyopaque, len: usize, alignment: __clj_zig_tstd.mem.Alignment, ret_addr: usize) ?[*]u8 {\n"
       "        __clj_zig_alloc_count += 1;\n"
       "        return __clj_zig_tstd.heap.c_allocator.rawAlloc(len, alignment, ret_addr);\n"
       "    }\n"
       "    fn resize(_: *anyopaque, memory: []u8, alignment: __clj_zig_tstd.mem.Alignment, new_len: usize, ret_addr: usize) bool {\n"
       "        const ok = __clj_zig_tstd.heap.c_allocator.rawResize(memory, alignment, new_len, ret_addr);\n"
       "        if (ok and new_len > memory.len) __clj_zig_alloc_count += 1;\n"
       "        return ok;\n"
       "    }\n"
       "    fn remap(_: *anyopaque, memory: []u8, alignment: __clj_zig_tstd.mem.Alignment, new_len: usize, ret_addr: usize) ?[*]u8 {\n"
       "        const grew = new_len > memory.len;\n"
       "        const r = __clj_zig_tstd.heap.c_allocator.rawRemap(memory, alignment, new_len, ret_addr);\n"
       "        if (r != null) {\n"
       "            if (grew or r.? != memory.ptr) __clj_zig_alloc_count += 1;\n"
       "        }\n"
       "        return r;\n"
       "    }\n"
       "    fn free(_: *anyopaque, memory: []u8, alignment: __clj_zig_tstd.mem.Alignment, ret_addr: usize) void {\n"
       "        __clj_zig_tstd.heap.c_allocator.rawFree(memory, alignment, ret_addr);\n"
       "    }\n"
       "};\n"
       "\n"
       "const __clj_zig_alloc_vtable: __clj_zig_tstd.mem.Allocator.VTable = .{\n"
       "    .alloc = __clj_zig_Track.alloc,\n"
       "    .resize = __clj_zig_Track.resize,\n"
       "    .remap = __clj_zig_Track.remap,\n"
       "    .free = __clj_zig_Track.free,\n"
       "};\n"
       "\n"
       "const __clj_zig_alloc: __clj_zig_tstd.mem.Allocator = .{\n"
       "    .ptr = undefined,\n"
       "    .vtable = &__clj_zig_alloc_vtable,\n"
       "};"))

(defn- count-fn-block
  "The per-symbol exported get/reset fns the bench binds to read the
  per-shape native allocation count. `sym` is the wrapper's export
  symbol; the fns name themselves `<sym>__alloc_count_get` and
  `<sym>__alloc_count_reset` so the bench derives them from the symbol
  it already holds."
  [sym]
  (str "export fn " sym "__alloc_count_get() usize {\n"
       "    return __clj_zig_alloc_count;\n"
       "}\n"
       "\n"
       "export fn " sym "__alloc_count_reset() void {\n"
       "    __clj_zig_alloc_count = 0;\n"
       "}"))

(defn tracking-wrap
  "Returns `src` wrapped for the profiling build: the counting allocator
  prepended, both c_allocator spellings rewritten through it, and
  per-symbol count fns appended. The :track-allocations flag enters the
  cache key (ADR 12), so the default codegen path is never touched."
  [src symbol]
  (let [rewritten (-> src
                      (str/replace "@import(\"std\").heap.c_allocator" "__clj_zig_alloc")
                      (str/replace "std.heap.c_allocator" "__clj_zig_alloc"))]
    (str tracking-allocator-block "\n\n" rewritten "\n\n" (count-fn-block symbol) "\n")))

(comment
  (require '[clj-zig.spec :as spec])
  (let [s (spec/build-spec '{:ns app.core :name add :signature [x :i64 y :i64 :ret :i64]})]
    (print (generate s "return x + y;")))
  ;; export fn clj_zig_app_2e_core_add(x: i64, y: i64) i64 {
  ;;     return x + y;
  ;; }

  ;; The inline-nodes function returns declaration nodes directly:
  (let [s (spec/build-spec '{:ns app.core :name add :signature [x :i64 y :i64 :ret :i64]})]
    (inline-nodes s "return x + y;")))
