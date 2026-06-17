(ns clj-zig.source
  "Generate the Zig wrapper source from a boundary spec (pure). A spec
  and the user's body go in, a string of readable Zig comes out.

  For a scalar function the wrapper is an `export fn` (C ABI, so FFM can
  bind it) whose parameters are the native params and whose body is the
  user's Zig spliced in verbatim:

      export fn clj_zig_app_2e_core_add(x: i64, y: i64) i64 {
          return x + y;
      }

  `zig fmt` owns final formatting; the generator emits already-canonical
  Zig for the structure it controls, and the compile shell runs `zig
  fmt` over the whole file to normalize the body."
  (:require [clojure.string :as str]))

(declare zig-type pointee pointer-type)

(defn- zig-type
  "The Zig type name for a normalized boundary type. Handles scalars,
  named types, and optional pointers; `param-decls` lowers slices."
  [t]
  (case (:kind t)
    :scalar   (name (:name t))
    :named    (str (:name t))
    :optional (str "?" (pointer-type (:of t)))
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
  many-item pointer and a `usize` length."
  [{:keys [binding type]}]
  (case (:kind type)
    :slice            [(str binding "_ptr: [*]" (pointee type))
                       (str binding "_len: usize")]
    (:ptr :manyptr)   [(str binding ": " (pointer-type type))]
    :optional         [(str binding ": " (zig-type type))]
    :array            [(str binding "_ptr: *const [" (:length type) "]" (zig-type (:of type)))]
    :named            (if (enum-type? type)
                        [(str binding ": " (zig-type type))]
                        [(str binding "_ptr: *const " (zig-type type))])
    [(str binding ": " (zig-type type))]))

(defn- reconstruction
  "The statement that rebuilds a binding the body uses by name: a slice
  from its pointer and length, or an array value from its pointer."
  [{:keys [binding type]}]
  (case (:kind type)
    :slice          (str "const " binding " = " binding "_ptr[0.." binding "_len];")
    :array          (str "const " binding " = " binding "_ptr.*;")
    :named          (when-not (enum-type? type)
                      (str "const " binding " = " binding "_ptr.*;"))
    nil))

(defn- param-args
  "The argument names the wrapper passes to an inner function, mirroring
  `param-decls`."
  [{:keys [binding type]}]
  (case (:kind type)
    :slice          [(str binding "_ptr") (str binding "_len")]
    :array          [(str binding "_ptr")]
    :named          (if (enum-type? type) [(str binding)] [(str binding "_ptr")])
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
  (let [prelude (str/join "\n" (keep reconstruction params))]
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

(defn- generate-ownership
  "An inner impl fn holding the user body returns a slice; the exported
  wrapper writes the slice's pointer and length to two out-params. An
  `:owned` return also emits a free shim and a `std` import: the body
  allocates with `std.heap.c_allocator` and the shim frees it after the
  caller copies the elements out."
  [{:keys [params ret] sym :symbol} body]
  (let [params-str (str/join ", " (mapcat param-decls params))
        args-str   (str/join ", " (mapcat param-args params))
        slice      (:of ret)
        elem-t     (zig-type (:of slice))
        ret-t      (str "[]" (when (:const? slice) "const ") elem-t)
        owned?     (contains? #{:owned :bytes} (:kind ret))
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

(defn- needs-std?
  "True when a function's generated wrapper needs `std` in scope: an owned
  return uses `std.heap.c_allocator` in its free shim, and a handle in any
  position is allocated or freed with `std`."
  [{:keys [params ret]}]
  (or (contains? #{:owned :bytes} (:kind ret))
      (= :handle (:kind ret))
      (some #(= :handle (:kind (:type %))) params)))

(defn- generate-inline
  "The inline-mode wrapper: the user's body string is spliced into the
  exported function (or its inner impl fn). A wrapper that allocates gets
  `std` in scope."
  [{:keys [ret] :as spec} body]
  (let [core (cond
               (= :error-union (:kind ret))                    (generate-error-union spec body)
               (contains? #{:owned :borrowed :bytes} (:kind ret)) (generate-ownership spec body)
               (and (= :named (:kind ret)) (enum-type? ret)) (generate-plain spec body)
               (= :named (:kind ret))                        (generate-struct-return spec body)
               :else                                         (generate-plain spec body))]
    (if (needs-std? spec)
      (str "const std = @import(\"std\");\n\n" core)
      core)))

;; --- File mode: the wrapper calls a user-written `pub fn` ----------------

(defn- user-call
  "The Zig expression calling the user's `entry` fn with each binding by
  its name. Slices, arrays, and structs arrive reconstructed; enums and
  scalars arrive by value."
  [entry params]
  (str entry "(" (str/join ", " (map #(str (:binding %)) params)) ")"))

(defn- wrapper-prelude
  "Reconstruction statements run inside the export wrapper before it calls
  the user fn, indented to the function-body level. Empty when no param
  needs rebuilding."
  [params]
  (let [stmts (keep reconstruction params)]
    (when (seq stmts)
      (str (str/join "\n" (map #(str "    " %) stmts)) "\n"))))

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
  "A file-mode `export fn` that calls the user fn and writes the returned
  slice's pointer and length to two out-params. An `:owned` return also
  emits a free shim; it imports `std` inline so the user file's own
  imports never collide."
  [{:keys [params ret] sym :symbol} entry]
  (let [params-str (str/join ", " (mapcat param-decls params))
        slice      (:of ret)
        elem-t     (zig-type (:of slice))
        owned?     (contains? #{:owned :bytes} (:kind ret))
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

(defn- generate-file
  "The file-mode wrapper: an `export fn` that reconstructs args and calls
  the user's `entry` fn. No impl fn and no top-level `std` are emitted; the
  user's file owns its declarations and imports."
  [{:keys [ret] :as spec} entry]
  (cond
    (= :error-union (:kind ret))                    (file-error-union spec entry)
    (contains? #{:owned :borrowed :bytes} (:kind ret)) (file-ownership spec entry)
    (and (= :named (:kind ret)) (enum-type? ret)) (file-plain spec entry)
    (= :named (:kind ret))                        (file-struct-return spec entry)
    :else                                         (file-plain spec entry)))

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
