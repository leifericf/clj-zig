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
            [clj-zig.layout :as layout]))

;; --- External-file resolution -------------------------------------------

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
        on-disk  (first (filter #(.isFile (io/file %)) fs-paths))]
    (cond
      on-disk            {:text (slurp on-disk) :path on-disk}
      (io/resource rel)  (let [res (io/resource rel)]
                           {:text (slurp res) :path (str res)})
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
  "The Zig parameter declarations for one boundary param. A scalar,
  pointer, or optional pointer is one declaration; a fixed-size array
  crosses as a pointer to the array; a slice is two declarations, a
  many-item pointer and a `usize` length. A `:string` argument lowers to
  the same wire shape as `[:slice :const :u8]` (a `[*]const u8` pointer
  and a `usize` length), since a string argument is always const bytes."
  [{:keys [binding type]}]
  (case (:kind type)
    :string          [(str binding "_ptr: [*]const u8")
                      (str binding "_len: usize")]
    :slice           (if (buffer-carrying-slice-element? (:of type))
                       (let [wire-t (wire-struct-name (:name (:layout (:of type))))]
                         [(str binding "_ptr: [*]const " wire-t)
                          (str binding "_len: usize")])
                       [(str binding "_ptr: [*]" (pointee type))
                        (str binding "_len: usize")])
    (:ptr :manyptr)  [(str binding ": " (pointer-type type))]
    :optional        [(str binding ": " (zig-type type))]
    :array           (if (buffer-carrying-slice-element? (:of type))
                       (let [wire-t (wire-struct-name (:name (:layout (:of type))))]
                         [(str binding "_ptr: *const [" (:length type) "]" wire-t)])
                       [(str binding "_ptr: *const [" (:length type) "]" (zig-type (:of type)))])
    :named           (if (enum-type? type)
                       [(str binding ": " (zig-type type))]
                       [(str binding "_ptr: *const " (zig-type type))])
    [(str binding ": " (zig-type type))]))

(defn- wire-to-nice-copy-stmts
  "The statements converting one wire element `__src` into the nice record
  `__dst`, used inside a reconstruction loop. Inverse of
  `buffer-slice-copy-stmts`: a scalar field is assigned directly; a buffer
  field reinterprets the `{ptr, len}` pair as a real slice."
  [layout]
  (mapcat (fn [{:keys [name type] :as f}]
            (if (:target f)
              (let [elem (buffer-element type)]
                [(str "    __dst." name " = @as([*]" elem
                      ", @ptrFromInt(__src." name "_ptr))[0..__src." name "_len];")])
              [(str "    __dst." name " = __src." name ";")]))
          (:fields layout)))

(defn- reconstruction
  "The statement that rebuilds a binding the body uses by name: a slice or
  string from its pointer and length, an array value from its pointer, or
  a wire-to-nice conversion for a buffer-carrying struct element. The
  optional `file-mode?` flag selects between a top-level std import
  (inline mode) and an inline @import of std (file mode)."
  ([param] (reconstruction param false))
  ([{:keys [binding type]} file-mode?]
   (let [alloc (if file-mode?
                 "@import(\"std\").heap.c_allocator"
                 "std.heap.c_allocator")]
   (case (:kind type)
    :slice (if (buffer-carrying-slice-element? (:of type))
             (let [layout   (:layout (:of type))
                   type-name (:name layout)
                   copies   (wire-to-nice-copy-stmts layout)]
               (str "const __nice_" binding " = " alloc ".alloc("
                    type-name ", " binding "_len) catch @panic(\"oom\");\n"
                    "for (__nice_" binding ", 0..) |*__dst, __i| {\n"
                    "    const __src = " binding "_ptr[__i];\n"
                    (str/join "\n" copies) "\n"
                    "}\n"
                    "defer " alloc ".free(__nice_" binding ");\n"
                    "const " binding " = __nice_" binding ";"))
             (str "const " binding " = " binding "_ptr[0.." binding "_len];"))
    :string (str "const " binding " = " binding "_ptr[0.." binding "_len];")
    :array  (if (buffer-carrying-slice-element? (:of type))
              (let [layout    (:layout (:of type))
                    type-name  (:name layout)
                    n          (:length type)
                    copies     (wire-to-nice-copy-stmts layout)]
                (str "var __nice_" binding ": [" n "]" type-name " = undefined;\n"
                     "for (0.." n ") |__i| {\n"
                     "    const __src = " binding "_ptr.*[__i];\n"
                     "    var __dst = &__nice_" binding "[__i];\n"
                     (str/join "\n" copies) "\n"
                     "}\n"
                     "const " binding " = __nice_" binding ";"))
              (str "const " binding " = " binding "_ptr.*;"))
    :named  (when-not (enum-type? type)
              (str "const " binding " = " binding "_ptr.*;"))
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

(defn- indent-body
  "Trim the body and indent every non-blank line by four spaces, the Zig
  function-body level."
  [body]
  (->> (str/split-lines (str/trim body))
       (map (fn [line] (if (str/blank? line) "" (str "    " line))))
       (str/join "\n")))

(def ^:private error-name-cap
  "The byte length the error-name buffer is clamped to before copy-out."
  255)

(defn- impl-body
  "The user body with any slice or array bindings reconstructed first."
  [params body]
  (let [prelude (str/join "\n" (keep #(reconstruction % false) params))]
    (if (str/blank? prelude) body (str prelude "\n" body))))

(defn- generate-plain
  "A direct `export fn`: the body runs in the exported function itself."
  [{:keys [params ret] sym :symbol} body]
  (str "export fn " sym "(" (str/join ", " (mapcat param-decls params)) ") "
       (zig-type ret) " {\n"
       (indent-body (impl-body params body)) "\n"
       "}\n"))

(defn- generate-error-union
  "An inner impl fn holding the user body returns `E!T`. The exported
  wrapper calls it, writes the error name to the caller's buffer on
  failure, and returns the value (or void)."
  [{:keys [params ret] sym :symbol} body]
  (let [params-str (str/join ", " (mapcat param-decls params))
        args-str   (str/join ", " (mapcat param-args params))
        err-set    (str (:error ret))
        value-t    (zig-type (:of ret))
        void?      (= "void" value-t)
        out-params (str (when (seq params-str) ", ")
                        "__err: [*]u8, __errlen: *usize")
        call       (str sym "__impl(" args-str ")")
        on-error   (str "catch |__err_value| {\n"
                        "        const __name = @errorName(__err_value);\n"
                        "        const __n = @min(__name.len, " error-name-cap ");\n"
                        "        @memcpy(__err[0..__n], __name[0..__n]);\n"
                        "        __errlen.* = __n;\n"
                        "        return" (when-not void? " undefined") ";\n"
                        "    };")]
    (str "fn " sym "__impl(" params-str ") " err-set "!" value-t " {\n"
         (indent-body (impl-body params body)) "\n"
         "}\n\n"
         "export fn " sym "(" params-str out-params ") " value-t " {\n"
         (if void?
           (str "    " call " " on-error "\n"
                "    __errlen.* = 0;\n")
           (str "    const __value = " call " " on-error "\n"
                "    __errlen.* = 0;\n"
                "    return __value;\n"))
         "}\n")))

(defn- generate-struct-return
  "An inner impl fn holding the user body returns the struct by value; the
  exported wrapper calls it and writes the result through an out-pointer.
  The aggregate crosses the C ABI by reference."
  [{:keys [params ret] sym :symbol} body]
  (let [params-str (str/join ", " (mapcat param-decls params))
        args-str   (str/join ", " (mapcat param-args params))
        ret-t      (zig-type ret)
        out-params (str (when (seq params-str) ", ") "__ret: *" ret-t)]
    (str "fn " sym "__impl(" params-str ") " ret-t " {\n"
         (indent-body (impl-body params body)) "\n"
         "}\n\n"
         "export fn " sym "(" params-str out-params ") void {\n"
         "    __ret.* = " sym "__impl(" args-str ");\n"
         "}\n")))

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
  `:owned` return also emits a free shim and a `std` import: the body
  allocates with `std.heap.c_allocator` and the shim frees it after the
  caller copies the elements out. A `:string` return lowers to the same
  `[]u8` shape and always carries the free shim. Used for scalar and
  scalar-only-struct elements; buffer-carrying struct elements take the
  transform path."
  [{:keys [params ret] sym :symbol} body]
  (let [params-str (str/join ", " (mapcat param-decls params))
        args-str   (str/join ", " (mapcat param-args params))
        slice      (ownership-slice ret)
        elem-t     (zig-type (:of slice))
        ret-t      (str "[]" (when (:const? slice) "const ") elem-t)
        owned?     (contains? #{:owned :bytes :string} (:kind ret))
        out-params (str (when (seq params-str) ", ") "__ptr: *usize, __len: *usize")]
    (str "fn " sym "__impl(" params-str ") " ret-t " {\n"
         (indent-body (impl-body params body)) "\n"
         "}\n\n"
         "export fn " sym "(" params-str out-params ") void {\n"
         "    const __r = " sym "__impl(" args-str ");\n"
         "    __ptr.* = @intFromPtr(__r.ptr);\n"
         "    __len.* = __r.len;\n"
         "}\n"
         (when owned?
           (str "\nexport fn " sym "__free(__ptr: usize, __len: usize) void {\n"
                "    const __p: [*]" elem-t " = @ptrFromInt(__ptr);\n"
                "    std.heap.c_allocator.free(__p[0..__len]);\n"
                "}\n")))))

(defn- buffer-slice-copy-stmts
  "The statements copying one nice record `__src` into the wire element
  `__wire[__i]`, used inside the transform loop. A scalar or enum field
  is assigned directly; a buffer field decomposes into its pointer and
  length."
  [layout]
  (mapcat (fn [{:keys [name] :as f}]
            (if (:target f)
              [(str "        __wire[__i]." name "_ptr = @intFromPtr(__src." name ".ptr);")
               (str "        __wire[__i]." name "_len = __src." name ".len;")]
              [(str "        __wire[__i]." name " = __src." name ";")]))
          (:fields layout)))

(defn- buffer-slice-free-stmts
  "The statements freeing one wire element's buffer fields, used inside
  the walking free shim's loop body over the wire slab."
  [layout]
  (for [{fname :name t :type} (filter :target (:fields layout))]
    (let [elem (buffer-element t)]
      (str "        std.heap.c_allocator.free(@as([*]" elem
           ", @ptrFromInt(__e." fname "_ptr))[0..__e." fname "_len]);"))))

(defn- generate-owned-buffer-slice
  "An owned slice whose element is a buffer-carrying struct. The body
  returns `[]NiceRecord` (c_allocator); the wrapper allocates a `[]Wire`
  slab, copies each nice record's fields (scalars direct, buffers as
  ptr+len), frees the nice struct array, and writes the wire slab's
  pointer and length to the out-params. A walking free shim iterates the
  wire slab, frees each element's buffer fields, then frees the slab
  itself. The wire slab holds extern structs the FFM reader reads at
  known C-ABI offsets; the nice struct (with real slice fields) lives
  only inside the body and is freed once the transform is done."
  [{:keys [params ret] sym :symbol} body]
  (let [layout     (get-in ret [:of :of :layout])
        type-name  (:name layout)
        wire-t     (wire-struct-name type-name)
        params-str (str/join ", " (mapcat param-decls params))
        args-str   (str/join ", " (mapcat param-args params))
        out-params (str (when (seq params-str) ", ") "__ptr: *usize, __len: *usize")
        copies     (buffer-slice-copy-stmts layout)
        frees      (buffer-slice-free-stmts layout)]
    (str "fn " sym "__impl(" params-str ") []" type-name " {\n"
         (indent-body (impl-body params body)) "\n"
         "}\n\n"
         "export fn " sym "(" params-str out-params ") void {\n"
         "    const __nice = " sym "__impl(" args-str ");\n"
         "    const __wire = std.heap.c_allocator.alloc(" wire-t ", __nice.len) catch @panic(\"oom\");\n"
         "    for (__nice, 0..) |__src, __i| {\n"
         (str/join "\n" copies) "\n"
         "    }\n"
         "    std.heap.c_allocator.free(__nice);\n"
         "    __ptr.* = @intFromPtr(__wire.ptr);\n"
         "    __len.* = __wire.len;\n"
         "}\n"
         "\nexport fn " sym "__free(__ptr: usize, __len: usize) void {\n"
         "    const __p: [*]" wire-t " = @ptrFromInt(__ptr);\n"
         "    const __slice = __p[0..__len];\n"
         "    for (__slice) |__e| {\n"
         (str/join "\n" frees) "\n"
         "    }\n"
         "    std.heap.c_allocator.free(__slice);\n"
         "}\n")))

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
      (error-union-struct-return? ret)
      (opt-struct-return? ret)
      (= :handle (:kind ret))
      (some #(= :handle (:kind (:type %))) params)
      (some #(and (contains? #{:slice :array} (:kind (:type %)))
                  (buffer-carrying-slice-element? (:of (:type %))))
            params)))

(defn- owned-record-return?
  "True when a return is an `:owned` or `:borrowed` wrapper around a named
  record, the doc 10 result-record shape. Distinguished from an owned or
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
  field to a `usize` pointer and length (doc 10 section 4)."
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
  "The wrapper statement(s) writing one field of the nice record `__r` into
  the wire out-struct `__ret`. A scalar or enum field is assigned directly;
  a buffer field writes its pointer and length into the two wire slots."
  [{fname :name :keys [target]}]
  (if target
    [(str "    __ret.*." fname "_ptr = @intFromPtr(__r." fname ".ptr);")
     (str "    __ret.*." fname "_len = __r." fname ".len;")]
    [(str "    __ret.*." fname " = __r." fname ";")]))

(defn- free-field-stmt
  "The free-shim statement freeing one owned buffer field, reading its
  pointer and length back out of the wire struct and reinterpreting the
  pointer to a slice of the field's element type."
  [{fname :name t :type}]
  (let [elem (buffer-element t)]
    (str "    std.heap.c_allocator.free(@as([*]" elem
         ", @ptrFromInt(__ret." fname "_ptr))[0..__ret." fname "_len]);")))

(defn- file-free-field-stmt
  "The file-mode free-shim statement: imports `std` inline so a user file's
  own imports never collide, then frees one owned buffer field."
  [{fname :name t :type}]
  (let [elem (buffer-element t)]
    (str "    @import(\"std\").heap.c_allocator.free(@as([*]" elem
         ", @ptrFromInt(__ret." fname "_ptr))[0..__ret." fname "_len]);")))

(defn- generate-owned-struct-return
  "An inner impl fn holding the user body returns the nice record by value;
  the exported wrapper writes each field into the wire extern struct through
  an out-pointer (scalars and enums directly, each buffer field as its
  pointer and length). An `:owned` result also emits a per-field `__free`
  shim that frees every buffer field, reading each pointer and length back
  out of the wire struct; a `:borrowed` result emits no shim. The wire
  struct is emitted here under its `__wire` name; the nice record type is
  declared alongside the body, not by the wrapper (doc 10 section 5)."
  [{:keys [params ret] sym :symbol} body]
  (let [layout     (get-in ret [:of :layout])
        type-name  (:name layout)
        wire-t     (wire-struct-name type-name)
        params-str (str/join ", " (mapcat param-decls params))
        args-str   (str/join ", " (mapcat param-args params))
        out-params (str (when (seq params-str) ", ") "__ret: *" wire-t)
        writes     (mapcat wire-write-stmts (:fields layout))
        owned?     (= :owned (:kind ret))
        buf-fields (filter :target (:fields layout))
        free-body  (if (seq buf-fields)
                     (str/join "\n" (map free-field-stmt buf-fields))
                     "    _ = __ret;")]
    (str "fn " sym "__impl(" params-str ") " type-name " {\n"
         (indent-body (impl-body params body)) "\n"
         "}\n\n"
         "export fn " sym "(" params-str out-params ") void {\n"
         "    const __r = " sym "__impl(" args-str ");\n"
         (str/join "\n" writes) "\n"
         "}\n"
         (when owned?
           (str "\nexport fn " sym "__free(__ret: *const " wire-t ") void {\n"
                free-body "\n}\n")))))

(defn- generate-error-union-struct-return
  "An inner impl fn holding the user body returns `E!NiceRecord`; the export
  wrapper `catch`-es the error, writes the error name to the caller's buffer
  and returns WITHOUT writing the struct on failure, and on success writes
  each field of the nice record into the wire extern struct through `__ret`
  and sets `__errlen` to 0. The combined wire shape carries the existing
  error-union out-params (`__err`, `__errlen`) PLUS the struct out-pointer
  (`__ret`). A per-field `__free` shim is emitted unconditionally and runs
  on the SUCCESS path only: the error path wrote no struct, so there is
  nothing to free and no leak (a scalar-only record yields a no-op shim,
  uniform with the `:owned ScalarRecord` path)."
  [{:keys [params ret] sym :symbol} body]
  (let [layout     (get-in ret [:of :layout])
        type-name  (:name layout)
        wire-t     (wire-struct-name type-name)
        params-str (str/join ", " (mapcat param-decls params))
        args-str   (str/join ", " (mapcat param-args params))
        err-set    (str (:error ret))
        out-params (str (when (seq params-str) ", ")
                        "__err: [*]u8, __errlen: *usize, __ret: *" wire-t)
        writes     (mapcat wire-write-stmts (:fields layout))
        buf-fields (filter :target (:fields layout))
        free-body  (if (seq buf-fields)
                     (str/join "\n" (map free-field-stmt buf-fields))
                     "    _ = __ret;")
        on-error   (str "catch |__err_value| {\n"
                        "        const __name = @errorName(__err_value);\n"
                        "        const __n = @min(__name.len, " error-name-cap ");\n"
                        "        @memcpy(__err[0..__n], __name[0..__n]);\n"
                        "        __errlen.* = __n;\n"
                        "        return;\n"
                        "    };")]
    (str "fn " sym "__impl(" params-str ") " err-set "!" type-name " {\n"
         (indent-body (impl-body params body)) "\n"
         "}\n\n"
         "export fn " sym "(" params-str out-params ") void {\n"
         "    const __r = " sym "__impl(" args-str ") " on-error "\n"
         "    __errlen.* = 0;\n"
         (str/join "\n" writes) "\n"
         "}\n"
         "\nexport fn " sym "__free(__ret: *const " wire-t ") void {\n"
         free-body "\n}\n")))

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
  "Free statements for each buffer field of a nice struct (with real slice
  fields), used in the optional-struct free shim. Each buffer field is freed
  directly (the nice struct holds a real `[]T` slice, not `{ptr, len}`
  words)."
  [layout]
  (for [{fname :name} (filter :target (:fields layout))]
    (str "    std.heap.c_allocator.free(__p." fname ");")))

(defn- optional-struct-free-shim
  "The `__free` shim for an optional-struct return. The body returns null or
  a c_allocator pointer to the nice struct; the shim null-checks, frees each
  buffer field, then destroys the struct allocation."
  [{:keys [ret] sym :symbol}]
  (let [layout     (get-in ret [:of :layout])
        type-name  (:name layout)
        buf-fields (filter :target (:fields layout))
        free-body  (if (seq buf-fields)
                     (str (str/join "\n" (nice-struct-free-stmts layout)) "\n")
                     "")]
    (str "\nexport fn " sym "__free(__ptr: usize) void {\n"
         "    if (__ptr == 0) return;\n"
         "    const __p: *" type-name " = @ptrFromInt(__ptr);\n"
         free-body
         "    std.heap.c_allocator.destroy(__p);\n"
         "}\n")))

(defn- generate-optional-struct-return
  "A plain `export fn` returning `?*const Type`, plus a `__free` shim that
  null-checks, frees buffer fields, and destroys the struct allocation."
  [spec body]
  (str (generate-plain spec body)
       (optional-struct-free-shim spec)))

(defn- file-optional-struct-return
  "A file-mode `export fn` returning `?*const Type`, plus a `__free` shim
  using inline @import of std so the user file's imports are untouched."
  [{:keys [ret] sym :symbol :as spec} entry]
  (let [layout     (get-in ret [:of :layout])
        type-name  (:name layout)
        buf-fields (filter :target (:fields layout))
        free-body  (if (seq buf-fields)
                     (str (str/join "\n" (for [{fname :name} buf-fields]
                                            (str "    @import(\"std\").heap.c_allocator.free(__p." fname ");")))
                          "\n")
                     "")]
    (str (file-plain spec entry)
         "\nexport fn " sym "__free(__ptr: usize) void {\n"
         "    if (__ptr == 0) return;\n"
         "    const __p: *" type-name " = @ptrFromInt(__ptr);\n"
         free-body
         "    @import(\"std\").heap.c_allocator.destroy(__p);\n"
         "}\n")))

(defn- buffer-wire-decls
  "Wire struct declarations for every struct type the spec references in
  a wire position: buffer-carrying struct elements in argument slices or
  arrays, owned or borrowed record returns (any struct, scalar-only or
  buffer-carrying), error-union-over-struct returns, and owned slices of
  buffer-carrying structs. Deduplicated by name and emitted once at the
  top level."
  [spec]
  (let [param-layouts (keep (fn [p]
                              (let [t (:type p)]
                                (when (contains? #{:slice :array} (:kind t))
                                  (when (buffer-carrying-slice-element? (:of t))
                                    (:layout (:of t))))))
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
        all-layouts    (distinct (concat param-layouts ret-layouts))]
    (str/join "\n" (map wire-struct all-layouts))))

(defn- generate-inline
  "The inline-mode wrapper: the user's body string is spliced into the
  exported function (or its inner impl fn). Wire struct declarations and
  the std import are emitted at the top level."
  [{:keys [ret] :as spec} body]
  (let [core      (cond
                    (error-union-struct-return? ret)                 (generate-error-union-struct-return spec body)
                    (= :error-union (:kind ret))                       (generate-error-union spec body)
                    (owned-record-return? ret)                         (generate-owned-struct-return spec body)
                    (contains? #{:owned :borrowed :bytes :string} (:kind ret)) (generate-ownership spec body)
                    (opt-struct-return? ret)                           (generate-optional-struct-return spec body)
                    (and (= :named (:kind ret)) (enum-type? ret))      (generate-plain spec body)
                    (= :named (:kind ret))                             (generate-struct-return spec body)
                    :else                                              (generate-plain spec body))
        wire-decls (buffer-wire-decls spec)
        std?       (needs-std? spec)
        parts      (cond-> []
                      std?                          (conj "const std = @import(\"std\");")
                      (not (str/blank? wire-decls)) (conj (str/trim wire-decls)))
        preamble   (str/join "\n\n" parts)]
    (if (str/blank? preamble)
      core
      (str preamble "\n\n" core))))

;; --- File mode: the wrapper calls a user-written `pub fn` ----------------

(defn- user-call
  "The Zig expression calling the user's `entry` fn with each binding by
  its name. Slices, arrays, and structs arrive reconstructed; enums and
  scalars arrive by value."
  [entry params]
  (str entry "(" (str/join ", " (map #(str (:binding %)) params)) ")"))

(defn- wrapper-prelude
  "Reconstruction statements run inside the export wrapper before it calls
  the user fn, indented to the function-body level. A reconstruction may
  span multiple lines (a wire-to-nice conversion); each line is indented
  individually. Empty when no param needs rebuilding."
  [params]
  (let [stmts (keep #(reconstruction % true) params)]
    (when (seq stmts)
      (->> (str/join "\n" stmts)
           (str/split-lines)
           (map #(if (str/blank? %) "" (str "    " %)))
           (str/join "\n")
           (#(str % "\n"))))))

(defn- file-plain
  "A file-mode `export fn` that reconstructs its slice and struct args and
  returns the user fn's result directly (scalar, enum, or void)."
  [{:keys [params ret] sym :symbol} entry]
  (str "export fn " sym "(" (str/join ", " (mapcat param-decls params)) ") "
       (zig-type ret) " {\n"
       (wrapper-prelude params)
       "    return " (user-call entry params) ";\n"
       "}\n"))

(defn- file-error-union
  "A file-mode `export fn` that calls the user fn and translates a returned
  error into the caller's error-name buffer."
  [{:keys [params ret] sym :symbol} entry]
  (let [params-str (str/join ", " (mapcat param-decls params))
        value-t    (zig-type (:of ret))
        void?      (= "void" value-t)
        out-params (str (when (seq params-str) ", ")
                        "__err: [*]u8, __errlen: *usize")
        call       (user-call entry params)
        on-error   (str "catch |__err_value| {\n"
                        "        const __name = @errorName(__err_value);\n"
                        "        const __n = @min(__name.len, " error-name-cap ");\n"
                        "        @memcpy(__err[0..__n], __name[0..__n]);\n"
                        "        __errlen.* = __n;\n"
                        "        return" (when-not void? " undefined") ";\n"
                        "    };")]
    (str "export fn " sym "(" params-str out-params ") " value-t " {\n"
         (wrapper-prelude params)
         (if void?
           (str "    " call " " on-error "\n"
                "    __errlen.* = 0;\n")
           (str "    const __value = " call " " on-error "\n"
                "    __errlen.* = 0;\n"
                "    return __value;\n"))
         "}\n")))

(defn- file-struct-return
  "A file-mode `export fn` that calls the user fn and writes the returned
  struct through an out-pointer (the aggregate crosses by reference)."
  [{:keys [params ret] sym :symbol} entry]
  (let [params-str (str/join ", " (mapcat param-decls params))
        ret-t      (zig-type ret)
        out-params (str (when (seq params-str) ", ") "__ret: *" ret-t)]
    (str "export fn " sym "(" params-str out-params ") void {\n"
         (wrapper-prelude params)
         "    __ret.* = " (user-call entry params) ";\n"
         "}\n")))

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
  return also emits a free shim; it imports `std` inline so the user
  file's own imports never collide."
  [{:keys [params ret] sym :symbol} entry]
  (let [params-str (str/join ", " (mapcat param-decls params))
        slice      (ownership-slice ret)
        elem-t     (zig-type (:of slice))
        owned?     (contains? #{:owned :bytes :string} (:kind ret))
        out-params (str (when (seq params-str) ", ") "__ptr: *usize, __len: *usize")]
    (str "export fn " sym "(" params-str out-params ") void {\n"
         (wrapper-prelude params)
         "    const __r = " (user-call entry params) ";\n"
         "    __ptr.* = @intFromPtr(__r.ptr);\n"
         "    __len.* = __r.len;\n"
         "}\n"
         (when owned?
           (str "\nexport fn " sym "__free(__ptr: usize, __len: usize) void {\n"
                "    const __p: [*]" elem-t " = @ptrFromInt(__ptr);\n"
                "    @import(\"std\").heap.c_allocator.free(__p[0..__len]);\n"
                "}\n")))))

(defn- file-buffer-slice-free-stmts
  "The file-mode free statements for one wire element's buffer fields:
  `std` is imported inline so a user file's own imports never collide."
  [layout]
  (for [{fname :name t :type} (filter :target (:fields layout))]
    (let [elem (buffer-element t)]
      (str "        @import(\"std\").heap.c_allocator.free(@as([*]" elem
           ", @ptrFromInt(__e." fname "_ptr))[0..__e." fname "_len]);"))))

(defn- file-owned-buffer-slice
  "File-mode owned slice of buffer-carrying structs: calls the user's
  `pub fn` (which returns `[]NiceRecord`), transforms it into a wire
  slab, and emits a walking free shim. `std` is imported inline so a
  user file's own imports never collide."
  [{:keys [params ret] sym :symbol} entry]
  (let [layout     (get-in ret [:of :of :layout])
        type-name  (:name layout)
        wire-t     (wire-struct-name type-name)
        params-str (str/join ", " (mapcat param-decls params))
        out-params (str (when (seq params-str) ", ") "__ptr: *usize, __len: *usize")
        copies     (buffer-slice-copy-stmts layout)
        frees      (file-buffer-slice-free-stmts layout)]
    (str "export fn " sym "(" params-str out-params ") void {\n"
         (wrapper-prelude params)
         "    const __nice = " (user-call entry params) ";\n"
         "    const __wire = @import(\"std\").heap.c_allocator.alloc(" wire-t ", __nice.len) catch @panic(\"oom\");\n"
         "    for (__nice, 0..) |__src, __i| {\n"
         (str/join "\n" copies) "\n"
         "    }\n"
         "    @import(\"std\").heap.c_allocator.free(__nice);\n"
         "    __ptr.* = @intFromPtr(__wire.ptr);\n"
         "    __len.* = __wire.len;\n"
         "}\n"
         "\nexport fn " sym "__free(__ptr: usize, __len: usize) void {\n"
         "    const __p: [*]" wire-t " = @ptrFromInt(__ptr);\n"
         "    const __slice = __p[0..__len];\n"
         "    for (__slice) |__e| {\n"
         (str/join "\n" frees) "\n"
         "    }\n"
         "    @import(\"std\").heap.c_allocator.free(__slice);\n"
         "}\n")))

(defn- file-owned-struct-return
  "A file-mode `export fn` that calls the user fn and writes the returned
  nice record field by field into the wire extern struct through an
  out-pointer. An `:owned` result also emits a per-field `__free` shim that
  imports `std` inline (so a user file's own imports never collide) and
  frees every buffer field; a `:borrowed` result emits no shim. The wire
  struct is emitted here under its `__wire` name (doc 10 section 5)."
  [{:keys [params ret] sym :symbol} entry]
  (let [layout     (get-in ret [:of :layout])
        type-name  (:name layout)
        wire-t     (wire-struct-name type-name)
        params-str (str/join ", " (mapcat param-decls params))
        out-params (str (when (seq params-str) ", ") "__ret: *" wire-t)
        writes     (mapcat wire-write-stmts (:fields layout))
        owned?     (= :owned (:kind ret))
        buf-fields (filter :target (:fields layout))
        free-body  (if (seq buf-fields)
                     (str/join "\n" (map file-free-field-stmt buf-fields))
                     "    _ = __ret;")]
    (str "export fn " sym "(" params-str out-params ") void {\n"
         (wrapper-prelude params)
         "    const __r = " (user-call entry params) ";\n"
         (str/join "\n" writes) "\n"
         "}\n"
         (when owned?
           (str "\nexport fn " sym "__free(__ret: *const " wire-t ") void {\n"
                free-body "\n}\n")))))

(defn- file-error-union-struct-return
  "A file-mode `export fn` that calls the user fn and translates a returned
  `E!NiceRecord` error union into the caller's error-name buffer and a wire
  struct out-pointer. On error the wrapper writes the error name and returns
  WITHOUT writing the struct; on success it writes each field through
  `__ret` and sets `__errlen` to 0. A per-field `__free` shim imports `std`
  inline (so a user file's imports never collide) and runs on the success
  path only; the error path wrote no struct, so nothing to free, no leak."
  [{:keys [params ret] sym :symbol} entry]
  (let [layout     (get-in ret [:of :layout])
        type-name  (:name layout)
        wire-t     (wire-struct-name type-name)
        params-str (str/join ", " (mapcat param-decls params))
        out-params (str (when (seq params-str) ", ")
                        "__err: [*]u8, __errlen: *usize, __ret: *" wire-t)
        writes     (mapcat wire-write-stmts (:fields layout))
        buf-fields (filter :target (:fields layout))
        free-body  (if (seq buf-fields)
                     (str/join "\n" (map file-free-field-stmt buf-fields))
                     "    _ = __ret;")
        on-error   (str "catch |__err_value| {\n"
                        "        const __name = @errorName(__err_value);\n"
                        "        const __n = @min(__name.len, " error-name-cap ");\n"
                        "        @memcpy(__err[0..__n], __name[0..__n]);\n"
                        "        __errlen.* = __n;\n"
                        "        return;\n"
                        "    };")]
    (str "export fn " sym "(" params-str out-params ") void {\n"
         (wrapper-prelude params)
         "    const __r = " (user-call entry params) " " on-error "\n"
         "    __errlen.* = 0;\n"
         (str/join "\n" writes) "\n"
         "}\n"
         "\nexport fn " sym "__free(__ret: *const " wire-t ") void {\n"
         free-body "\n}\n")))

(defn- generate-file
  "The file-mode wrapper: an `export fn` that reconstructs args and calls
  the user's `pub fn`. Wire struct declarations are emitted for any buffer-
  carrying struct type in the spec; `std` is imported inline by the
  reconstruction and free shims so the user's file owns its own imports."
  [{:keys [ret] :as spec} entry]
  (let [wire-decls (buffer-wire-decls spec)
        core       (cond
                    (error-union-struct-return? ret)                   (file-error-union-struct-return spec entry)
                    (= :error-union (:kind ret))                       (file-error-union spec entry)
                    (owned-record-return? ret)                         (file-owned-struct-return spec entry)
                    (contains? #{:owned :borrowed :bytes :string} (:kind ret)) (file-ownership spec entry)
                    (opt-struct-return? ret)                           (file-optional-struct-return spec entry)
                    (and (= :named (:kind ret)) (enum-type? ret))      (file-plain spec entry)
                    (= :named (:kind ret))                             (file-struct-return spec entry)
                    :else                                              (file-plain spec entry))]
    (if (str/blank? wire-decls)
      core
      (str (str/trim wire-decls) "\n\n" core))))

(defn generate
  "Emit the Zig wrapper for `spec`. In the default inline mode the user's
  `body` string is spliced in; an error-union return generates an inner
  impl fn and a translating wrapper, an owned or borrowed slice return
  writes its pointer and length to out-params, a struct return writes
  through an out-pointer, and every other return is a direct `export fn`.
  In file mode (`opts {:mode :file :entry \"name\"}`) the wrapper instead
  reconstructs its arguments and calls the user's `pub fn`; `body` is not
  spliced and is concatenated separately."
  ([spec body] (generate spec body {:mode :inline}))
  ([spec body {:keys [mode entry]}]
   (if (= :file mode)
     (generate-file spec entry)
     (generate-inline spec body))))

(comment
  (require '[clj-zig.spec :as spec])
  (let [s (spec/build-spec '{:ns app.core :name add :signature [x :i64 y :i64 :ret :i64]})]
    (print (generate s "return x + y;")))
  ;; export fn clj_zig_app_2e_core_add(x: i64, y: i64) i64 {
  ;;     return x + y;
  ;; }

  ;; A slice crosses as a pointer and a length, rebuilt before the body.
  (let [s (spec/build-spec '{:ns app.core :name sum :signature [xs [:slice :const :f64] :ret :f64]})]
    (print (generate s "var t: f64 = 0; for (xs) |x| t += x; return t;"))))
  ;; export fn clj_zig_app_2e_core_sum(xs_ptr: [*]const f64, xs_len: usize) f64 {
  ;;     const xs = xs_ptr[0..xs_len];
  ;;     ...
  ;; }
