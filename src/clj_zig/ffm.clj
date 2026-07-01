(ns clj-zig.ffm
  "Load a compiled library and bind a native symbol through the finalized
  Foreign Function & Memory API (Java 22+). Imperative shell: it
  turns a boundary spec into a downcall handle and wraps it in a Clojure
  fn that coerces scalars across the boundary.

  Coercion honors the unsigned-return policy: a value that fits
  the signed JVM range comes back as a `Long`; a `:u64`/`:usize` value
  beyond it is promoted to `BigInteger`, never truncated to a negative. A
  `:void` return is `nil`."
  (:require [clj-zig.foreign :as foreign]
            [clj-zig.type :as type])
  (:import (java.lang IllegalCallerException)
           (java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
                              MemoryLayout MemorySegment ValueLayout)
           (java.lang.invoke MethodHandle)
           (java.lang.reflect Array)
           (java.math BigInteger)
           (java.nio.charset StandardCharsets)))

(def ^:private two-to-64 (.shiftLeft BigInteger/ONE 64))

(declare marshal-struct read-struct-field read-bytes read-slice-values
         read-utf8-string write-scalar enum-member->value enum-value->member)

;; An opaque native resource handle: the symbol naming its Zig type and
;; the native pointer. The caller threads it back across calls and frees
;; it explicitly; it never inspects the pointer.
(defrecord Handle [type segment])

(defmethod print-method Handle [h ^java.io.Writer w]
  (.write w (str "#clj-zig/handle[" (:type h) "]")))

(defn- value-layout ^ValueLayout [t]
  (let [{:keys [category bits]} (type/scalar-info (:name t))]
    (case category
      :int   (case bits 8 ValueLayout/JAVA_BYTE 16 ValueLayout/JAVA_SHORT
                        32 ValueLayout/JAVA_INT 64 ValueLayout/JAVA_LONG)
      :float (case bits 32 ValueLayout/JAVA_FLOAT 64 ValueLayout/JAVA_DOUBLE)
      :bool  ValueLayout/JAVA_BOOLEAN)))

(defn- enum-type?
  "True when a resolved named type is a `defenumz` enum, which crosses as
  its backing scalar rather than a struct pointer."
  [t]
  (boolean (get-in t [:layout :enum])))

(defn- param-layouts
  "The native layouts one boundary param crosses as. A scalar is one
  layout; a pointer, array, or named struct is an address; an enum is its
  backing scalar; a slice or `:string` is an address and a `usize` length
  (a `:string` argument lowers to the same const-u8 wire shape as a slice)."
  [{:keys [type]}]
  (case (:kind type)
    (:slice :string)                          [ValueLayout/ADDRESS ValueLayout/JAVA_LONG]
    :named                                    (if (enum-type? type)
                                                 [(value-layout (-> type :layout :backing))]
                                                 [ValueLayout/ADDRESS])
    (:ptr :manyptr :array :optional :handle)  [ValueLayout/ADDRESS]
    [(value-layout type)]))

(defn- return-layout ^ValueLayout [ret]
  (cond
    (= :optional (:kind ret))                ValueLayout/ADDRESS
    (= :handle (:kind ret))                  ValueLayout/ADDRESS
    (and (= :named (:kind ret))
         (enum-type? ret))                   (value-layout (-> ret :layout :backing))
    :else                                    (value-layout ret)))

(defn- descriptor ^FunctionDescriptor [spec]
  (let [ret         (:ret spec)
        ;; An error-union wrapper carries two trailing out-params, the
        ;; error-name buffer and its length; a struct return carries one
        ;; out-pointer the result is written through; an owned-slice,
        ;; :bytes, or :string return carries two out-params for the slice's
        ;; pointer and length; an owned or borrowed record carries one
        ;; out-pointer to its wire struct. An error-union over a struct
        ;; combines all three: the error-name buffer, its length, AND the
        ;; struct out-pointer. All four export `void`.
        eu?          (= :error-union (:kind ret))
        eu-struct?   (and eu?
                          (= :named (get-in ret [:of :kind]))
                          (not (enum-type? (:of ret))))
        owned-rec?   (and (contains? #{:owned :borrowed} (:kind ret))
                          (= :named (get-in ret [:of :kind])))
        owned-slice? (and (contains? #{:owned :borrowed :bytes :string} (:kind ret))
                          (not owned-rec?))
        struct-ret?  (and (= :named (:kind ret)) (not (enum-type? ret)))
        extra        (cond eu-struct?                     [ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/ADDRESS]
                           eu?                            [ValueLayout/ADDRESS ValueLayout/ADDRESS]
                           owned-slice?                   [ValueLayout/ADDRESS ValueLayout/ADDRESS]
                           (or struct-ret? owned-rec?)    [ValueLayout/ADDRESS]
                           :else                          [])
        arg-layouts  (into-array MemoryLayout (concat (mapcat param-layouts (:params spec))
                                                      extra))
        ret-value    (if eu? (:of ret) ret)]
    (if (or eu-struct? struct-ret? owned-rec? owned-slice? (type/void-type? ret-value))
      (FunctionDescriptor/ofVoid arg-layouts)
      (FunctionDescriptor/of (return-layout ret-value) arg-layouts))))

(defn- to-carrier
  "Coerce a Clojure value to the param's native carrier. Integers cross
  as their low `bits` two's-complement bits, so an unsigned value passes
  through the signed carrier without truncation."
  [param v]
  (let [{:keys [category bits]} (type/scalar-info (-> param :type :name))]
    (case category
      :int   (let [l (.longValue (biginteger v))]
               (case bits 8 (unchecked-byte l) 16 (unchecked-short l)
                          32 (unchecked-int l) 64 l))
      :float (case bits 32 (unchecked-float v) 64 (double v))
      :bool  (boolean v))))

(declare marshal-struct-into!)

(defn- marshal-struct-collection
  "Copy a Clojure collection of maps into a fresh native segment, one
  struct element per stride, for a slice or array argument whose
  element is a named struct. The element layout is the wire extern
  struct, embedded in bulk (each element at `i*stride`). There is no
  copy-back: the caller supplied immutable maps, so mutations the body
  makes in place are not propagated. A const slice is the natural shape;
  a mutable struct slice compiles but does not return its edits."
  [arena {:keys [type]} coll]
  (let [inner  (get-in type [:of :layout])
        stride (long (:size inner))
        n      (count coll)
        seg    ^MemorySegment (.allocate ^Arena arena (* n stride) (long (:align inner)))]
    (dotimes [i n]
      (marshal-struct-into! (.asSlice seg (* (long i) stride) stride) inner (nth coll i)))
    {:address seg :length n :copy-back nil}))

(defn- marshal-array
  "Copy a caller-supplied sequence into a fresh native segment from
  `arena` and pass its address. A scalar element is a Java primitive
  array, bulk-copied in one move (with `bool` crossing element by
  element, the FFM API having no boolean bulk copy); a mutable pointee
  copies the segment back into the array after the call. A named-struct
  element is a Clojure collection of maps, marshaled one struct per
  stride via `marshal-struct-collection`."
  [arena {:keys [type]} arr]
  (if (= :named (:kind (:of type)))
    (marshal-struct-collection arena {:type type} arr)
    (let [elem  (value-layout (:of type))
          bytes (.byteSize elem)
          len   (Array/getLength arr)
          seg   ^MemorySegment (.allocate ^Arena arena (* len bytes) bytes)
          bool? (= :bool (:category (type/scalar-info (:name (:of type)))))]
      (if bool?
        (dotimes [i len] (.set seg ValueLayout/JAVA_BOOLEAN (long i) (boolean (Array/get arr i))))
        (MemorySegment/copy arr (int 0) seg elem (long 0) (int len)))
      {:address   seg
       :length    len
       :copy-back (when-not (:const? type)
                    (if bool?
                      (fn [] (dotimes [i len]
                               (Array/set arr i (.get seg ValueLayout/JAVA_BOOLEAN (long i)))))
                      (fn [] (MemorySegment/copy seg elem (long 0) arr (int 0) (int len)))))})))

(defn- marshal-arg
  "Coerce one boundary argument to its native carriers. Pointers and slices
  pass as a native segment address; a single-item pointer demands a
  one-element array, guarding against a read past the end."
  [arena param arg]
  (case (-> param :type :kind)
    :string  (let [bs (if (string? arg)
                        (.getBytes ^String arg StandardCharsets/UTF_8)
                        (do (when-not (bytes? arg)
                              (throw (ex-info (str "A :string argument must be a String or a byte[]"
                                                   " of UTF-8; got " (pr-str (type arg)) ".")
                                              {:level :error
                                               :error/code :clj-zig/string-argument
                                               :actual (type arg)})))
                            arg))
                   len (alength ^bytes bs)
                   seg ^MemorySegment (.allocate ^Arena arena (long len) 1)]
               ;; A string argument is const UTF-8 bytes copied into the call
               ;; arena; there is no copy-back. A zero-length string allocates
               ;; a zero-size segment the Zig side reconstructs as [0..0].
               (when (pos? len)
                  (MemorySegment/copy bs (long 0) seg ValueLayout/JAVA_BYTE (long 0) (long len)))
               {:carriers [seg (long len)]})
    :slice   (let [{:keys [address length copy-back]} (marshal-array arena param arg)]
               {:carriers [address (long length)] :copy-back copy-back})
    :manyptr (let [{:keys [address copy-back]} (marshal-array arena param arg)]
               {:carriers [address] :copy-back copy-back})
    :ptr     (do (when (not= 1 (Array/getLength arg))
                   (throw (ex-info "A :ptr argument must be a one-element array."
                                   {:level :error
                                    :error/code :clj-zig/pointer-arity
                                    :expected 1
                                    :actual (Array/getLength arg)})))
                 (let [{:keys [address copy-back]} (marshal-array arena param arg)]
                   {:carriers [address] :copy-back copy-back}))
     :array   (let [n (-> param :type :length)
                    actual (if (= :named (:kind (:of (:type param))))
                             (count arg)
                             (Array/getLength arg))]
                (when (not= n actual)
                  (throw (ex-info (str "An :array argument must have length " n ".")
                                  {:level :error
                                   :error/code :clj-zig/array-length
                                   :expected n
                                   :actual actual})))
                {:carriers [(:address (marshal-array arena param arg))]})
    :optional (let [pointed (-> param :type :of)]
                 (cond
                   (nil? arg) {:carriers [MemorySegment/NULL]}
                   ;; A carrier scalar lowers to a nullable pointer-to-const
                   ;; one-element cell: nil is NULL, a present value is copied
                   ;; into a fresh cell in the call arena and its address is
                   ;; passed. The cell is const (`?*const T`), so there is no
                   ;; copy-back; the arena owns it for the duration of the call.
                   (= :scalar (:kind pointed))
                   (let [layout (value-layout pointed)
                         seg    ^MemorySegment (.allocate ^Arena arena
                                                          (.byteSize layout)
                                                          (.byteSize layout))]
                     (write-scalar seg pointed 0 (to-carrier {:type pointed} arg))
                     {:carriers [seg]})
                   :else (marshal-arg arena (update param :type :of) arg)))
    :named    (let [layout (-> param :type :layout)]
                (if (:enum layout)
                  (let [value (enum-member->value layout arg)]
                    (when (nil? value)
                      (throw (ex-info (str arg " is not a member of enum " (:name layout) ".")
                                      {:level :error
                                       :error/code :clj-zig/unknown-enum-member
                                       :type (:name layout) :member arg})))
                    ;; An enum crosses as its backing scalar, so coerce to the
                    ;; backing's carrier width (byte for u8, int for i32, ...).
                    {:carriers [(to-carrier {:type (:backing layout)} value)]})
                  {:carriers [(marshal-struct arena layout arg)]}))
    :handle   (let [expected (-> param :type :of :name)]
                (when-not (and (instance? Handle arg) (= expected (:type arg)))
                  (throw (ex-info (str "Expected a :handle of " expected
                                       " but got " (pr-str arg) ".")
                                  {:level :error
                                   :error/code :clj-zig/handle-type-mismatch
                                   :expected expected :actual arg})))
                {:carriers [(:segment arg)]})
    {:carriers [(to-carrier param arg)]}))

(defn- coerce-scalar
  "Coerce a native scalar value of type `t` to Clojure, applying the
  unsigned-return policy for `:u64`/`:usize`."
  [t v]
  (let [{:keys [category signed? bits]} (type/scalar-info (:name t))]
    (case category
      :bool  (boolean v)
      :float (double v)
      :int   (if signed?
               (long v)
               (case bits
                 8  (bit-and (long v) 0xff)
                 16 (bit-and (long v) 0xffff)
                 32 (bit-and (long v) 0xffffffff)
                 64 (let [l (long v)]
                      (if (neg? l) (.add (biginteger l) two-to-64) l)))))))

(defn- read-scalar
  "Read one scalar of type `t` from native segment `seg` at byte `offset`."
  [^MemorySegment seg t offset]
  (let [{:keys [category bits]} (type/scalar-info (:name t))
        off (long offset)]
    (case category
      :int   (case bits
               8  (.get seg ValueLayout/JAVA_BYTE off)
               16 (.get seg ValueLayout/JAVA_SHORT off)
               32 (.get seg ValueLayout/JAVA_INT off)
               64 (.get seg ValueLayout/JAVA_LONG off))
      :float (case bits
               32 (.get seg ValueLayout/JAVA_FLOAT off)
               64 (.get seg ValueLayout/JAVA_DOUBLE off))
      :bool  (.get seg ValueLayout/JAVA_BOOLEAN off))))

(defn- write-scalar
  "Write the coerced scalar `cv` of type `t` into `seg` at byte `offset`."
  [^MemorySegment seg t offset cv]
  (let [{:keys [category bits]} (type/scalar-info (:name t))
        off (long offset)]
    (case category
      :int   (case bits
               8  (.set seg ValueLayout/JAVA_BYTE off (byte cv))
               16 (.set seg ValueLayout/JAVA_SHORT off (short cv))
               32 (.set seg ValueLayout/JAVA_INT off (int cv))
               64 (.set seg ValueLayout/JAVA_LONG off (long cv)))
      :float (case bits
               32 (.set seg ValueLayout/JAVA_FLOAT off (unchecked-float cv))
               64 (.set seg ValueLayout/JAVA_DOUBLE off (double cv)))
      :bool  (.set seg ValueLayout/JAVA_BOOLEAN off (boolean cv)))))

(defn- marshal-struct
  "Write the fields of Clojure map `m` into a fresh native segment for the
  struct `descriptor`, each at its C-ABI offset. A scalar field is
  written directly; a nested struct field recurses into a sub-segment at
  the field's offset (the inner extern struct is embedded by value)."
  [arena descriptor m]
  (let [seg ^MemorySegment (.allocate ^Arena arena (long (:size descriptor))
                                      (long (:align descriptor)))]
    (marshal-struct-into! seg descriptor m)
    seg))

(defn- marshal-struct-into!
  "Write the fields of Clojure map `m` into the existing segment `seg`
  for the struct `descriptor`, each at its C-ABI offset. Used both by
  `marshal-struct` (for a top-level struct argument) and by the nested-
  field recursion (writing into a sub-slice of the outer struct)."
  [^MemorySegment seg descriptor m]
  (doseq [{:keys [name type offset nested]} (:fields descriptor)]
    (let [v (get m (keyword name))]
      (when (nil? v)
        (throw (ex-info (str "Struct " (:name descriptor) " is missing field " name ".")
                        {:level :error :error/code :clj-zig/missing-field
                         :type (:name descriptor) :field name})))
      (if nested
        (let [inner (:layout type)
              sub   (.asSlice seg (long offset) (long (:size inner)))]
          (marshal-struct-into! sub inner v))
        (write-scalar seg type offset (to-carrier {:type type} v))))))

(defn- buffer-field-element
  "The scalar element a `:vector` buffer field carries, for the bulk copy.
  A bare slice holds its element under `:of`; an owned, borrowed, or bytes
  wrapper holds its slice under `:of`. (A `:string` or `:bytes` field never
  reaches here: its `:target` selects the byte[]/String reader, not the
  vector reader.)"
  [t]
  (let [slice (case (:kind t)
                :slice                    t
                (:owned :borrowed :bytes) (:of t))]
    (:of slice)))

(defn- read-struct
  "Read a native struct segment into a Clojure map keyed by field name. A
  scalar field reads its carrier directly at its offset; an enum field
  reads its `i32` backing and maps it to the member keyword; a buffer
  field reads its `{ptr, len}` at the field's two wire offsets and copies
  out as a `byte[]`, a vector, or a `String` per its `:target`. Each
  buffer read copies exactly `len` bytes (the bound), so a corrupt length
  never drives an unbounded dereference; a zero-length buffer copies
  nothing and never dereferences the pointer."
  [^MemorySegment seg descriptor]
  (reduce (fn [acc field]
            (assoc acc (keyword (:name field)) (read-struct-field seg field)))
          {} (:fields descriptor)))

(defn- read-struct-field
  "Read one field of a wire struct segment. Dispatches on the field's
  shape: a buffer field (it carries a `:target`) reads `{ptr, len}` and
  copies out; a nested struct field recurses into a sub-segment at the
  field's offset; an enum field reads its integer backing and maps to
  keyword; a scalar field reads its carrier."
  [^MemorySegment seg {:keys [type offset target len-offset nested]}]
  (cond
    target
    (let [ptr (.get seg ValueLayout/JAVA_LONG (long offset))
          len (.get seg ValueLayout/JAVA_LONG (long len-offset))]
      ;; The byte[] target is `(keyword "byte[]")`; the literal `:byte[]`
      ;; splits at the bracket when read, so it is compared explicitly.
      (condp = target
        :string             (read-utf8-string ptr len)
        (keyword "byte[]")  (read-bytes ptr len)
        :vector             (read-slice-values ptr len (buffer-field-element type))))

    nested
    (let [inner (:layout type)]
      (read-struct (.asSlice seg (long offset) (long (:size inner))) inner))

    (get-in type [:layout :enum])
    (enum-value->member (:layout type)
                        (long (read-scalar seg (:backing (:layout type)) offset)))

    :else
    (coerce-scalar type (read-scalar seg type offset))))

(defn- fill-array
  "Bulk-copy `n` carrier elements of `layout` from `seg` (at offset 0) into a
  freshly allocated primitive `arr`, returning `arr`. The array's component
  type must match the layout's carrier. One native move replaces a
  per-element `.get` loop, mirroring `read-bytes`."
  [^MemorySegment seg layout ^long n arr]
  (MemorySegment/copy seg layout (long 0) arr (int 0) (int n))
  arr)

(defn- read-slice-values
  "Copy `len` elements from the native address `addr` into an immutable
  Clojure vector. A scalar element bulk-copies into a typed primitive
  array (one native move, `bool` element by element) and is coerced with
  the unsigned-return policy (ADR 18). A named-struct element reads one
  struct per stride via `read-struct`, producing a vector of maps. A
  zero length reads nothing, so the address is never dereferenced for an
  empty slice."
  [addr len elem]
  (if (zero? len)
    []
    (let [n (long len)]
      (if (= :named (:kind elem))
        (let [inner  (:layout elem)
              stride (long (:size inner))
              base   (.reinterpret (MemorySegment/ofAddress addr) (* n stride))]
          (mapv #(read-struct (.asSlice base (* (long %) stride) stride) inner)
                (range n)))
        (let [layout (value-layout elem)
              seg    (.reinterpret (MemorySegment/ofAddress addr) (* n (.byteSize layout)))
              {:keys [category bits]} (type/scalar-info (:name elem))]
          (if (= :bool category)
            (mapv #(coerce-scalar elem (read-scalar seg elem (* (long %) (.byteSize layout))))
                  (range n))
            (let [arr (case category
                        :int   (case bits
                                 8  (fill-array seg layout n (byte-array n))
                                 16 (fill-array seg layout n (short-array n))
                                 32 (fill-array seg layout n (int-array n))
                                 64 (fill-array seg layout n (long-array n)))
                        :float (case bits
                                 32 (fill-array seg layout n (float-array n))
                                 64 (fill-array seg layout n (double-array n))))]
              (mapv #(coerce-scalar elem %) arr))))))))

(defn- read-bytes
  "Copy `len` bytes from native address `addr` into a Java `byte[]` in one
  bulk move, so a `:bytes` return crosses as a single array rather than a
  boxed per-element vector. A zero length yields an empty array without
  dereferencing the address."
  [addr len]
  (let [out (byte-array len)]
    (when (pos? len)
      (let [seg (.reinterpret (MemorySegment/ofAddress addr) (long len))]
        (MemorySegment/copy seg ValueLayout/JAVA_BYTE (long 0) out (int 0) (int len))))
    out))

(defn- read-utf8-string
  "Copy `len` bytes from native address `addr` and decode them as UTF-8 into
  a Java `String`, using the JVM's replacement action so a malformed
  sequence becomes U+FFFD instead of throwing across the boundary. The
  field is untrusted native memory; the decode never faults. A zero length
  yields an empty String without dereferencing the address (the same
  single-slice guard `read-bytes` uses)."
  ^String [addr len]
  (String. (read-bytes addr len) StandardCharsets/UTF_8))

(defn- enum-member->value
  "The backing integer for an enum member keyword, or nil when no member
  of `descriptor` carries that name."
  [descriptor kw]
  (some (fn [{:keys [name value]}] (when (= kw (keyword (str name))) value))
        (:values descriptor)))

(defn- enum-value->member
  "The member keyword for an enum backing integer, or the raw integer when
  no member of `descriptor` carries that value."
  [descriptor v]
  (or (some (fn [{:keys [name value]}] (when (= value v) (keyword (str name))))
            (:values descriptor))
      v))

(defn- deref-optional
  "Read the pointee of an `:optional` return: nil when the address is null,
  else the coerced scalar the pointer addresses. A scalar return (`[:optional
  :i64]`) points at its own one-element cell, so the scalar is `(:of ret)`;
  a pointer return (`[:optional [:ptr :const :i64]]`) points through the
  pointer, so the scalar is the pointer's `:of`."
  [ret ^MemorySegment seg]
  (when-not (zero? (.address seg))
    (let [pointed (:of ret)
          scalar  (if (= :scalar (:kind pointed)) pointed (:of pointed))
          sized   (.reinterpret seg (.byteSize (value-layout scalar)))]
      (coerce-scalar scalar (read-scalar sized scalar 0)))))

(defn- from-return
  "Coerce a native return to a Clojure value: nil for `:void`, the pointee
  or nil for an `:optional` pointer, and the unsigned-aware scalar value
  otherwise."
  [ret v]
  (cond
    (type/void-type? ret)     nil
    (= :optional (:kind ret)) (deref-optional ret v)
    (= :handle (:kind ret))   (when-not (zero? (.address ^MemorySegment v))
                                (->Handle (-> ret :of :name) v))
    (and (= :named (:kind ret))
         (enum-type? ret))    (enum-value->member (:layout ret) (long v))
    :else                     (coerce-scalar ret v)))

(def ^:private error-buffer-bytes
  "The size of the error-name buffer the caller hands an error-union
  wrapper; error names are short, so 256 bytes is ample."
  256)

(defn- read-error-name
  "Read `n` bytes of an error name from `buf` and return them as a keyword."
  [^MemorySegment buf n]
  (let [bytes (byte-array n)]
    (MemorySegment/copy buf ValueLayout/JAVA_BYTE 0 bytes (int 0) (int n))
    (keyword (String. bytes StandardCharsets/UTF_8))))

(defn- native-access-disabled
  "The diagnostic for a JVM that denied native access, naming the flag and
  the ready-made aliases instead of the raw FFM `IllegalCallerException`.
  Calling compiled Zig is a restricted operation the JVM grants only with
  the flag; clj-zig cannot grant it from inside a running JVM."
  [cause]
  (ex-info (str "clj-zig needs native access to call compiled Zig, but this JVM denied it. "
                "Add the JVM option --enable-native-access=ALL-UNNAMED "
                "(the :repl and :test aliases in deps.edn already set it).")
           {:level :error
            :error/code :clj-zig/native-access-disabled
            :clj-zig/jvm-option "--enable-native-access=ALL-UNNAMED"}
           cause))

(defn- with-native-access
  "Run `thunk`, translating a denied-native-access failure into the clear
  diagnostic. A restricted FFM call throws `IllegalCallerException` when
  the JVM denies native access; every other outcome passes through."
  [thunk]
  (try
    (thunk)
    (catch IllegalCallerException e
      (throw (native-access-disabled e)))))

(defn- wrong-arity-ex
  "The diagnostic for calling a bound fn with the wrong argument count."
  [var-sym arity actual]
  (ex-info (str "Wrong number of arguments to " var-sym
                ": expected " arity ", got " actual)
           {:level :error
            :error/code :clj-zig/arity
            :var var-sym
            :expected arity
            :actual actual}))

(defn- scalar-only?
  "True when every param and the return cross as a plain scalar carrier, so
  the call needs no confined arena. A scalar param coerces straight to its
  carrier with `to-carrier` (no native segment is allocated), and a scalar
  or `:void` return reads back with no out-pointer. Anything that touches
  the arena -- a slice, pointer, array, struct, enum, handle, optional, or
  an error-union/owned/struct return -- is excluded and takes the general
  path. (`:void` normalizes to `{:kind :scalar :name :void}`, so the return
  test covers it.)"
  [params ret]
  (and (every? (fn [p] (= :scalar (-> p :type :kind))) params)
       (= :scalar (:kind ret))))

;; --- general-path return dispatch ----------------------------------------
;; The non-scalar return shapes each need their own downcall choreography:
;; where to write out-params, how to read the result, and whether a free
;; shim runs in a finally. Each helper below takes the per-bind return
;; context (`ctx`) plus the per-call arena, marshalled carriers, and
;; copy-back thunk, and runs exactly one shape. `bind` reduces to a
;; dispatch table over them.

(defn- invoke-eu-struct
  "Run the error-union-over-a-struct downcall. The union combines its
  out-params (errbuf, errlen) with a struct out-pointer (`out`). On success
  (errlen 0) read the struct and free owned buffers in a finally so a read
  fault cannot leak (mirror of c8a822b and the owned-record path); on
  failure read the error keyword. The error path wrote no struct, so the
  free shim does not run and there is nothing to free. The result rebuilds
  as a record via its map-> factory when the named type is a defrecordz,
  else a plain map."
  [{:keys [handle ret record-factory free-handle error-buffer-bytes]}
   ^Arena arena base-carriers copy-back!]
  (let [desc   (-> ret :of :layout)
        out    ^MemorySegment (.allocate arena (long (:size desc)) (long (:align desc)))
        errbuf ^MemorySegment (.allocate arena error-buffer-bytes 1)
        errlen ^MemorySegment (.allocate arena 8 8)]
    (->> (concat base-carriers [errbuf errlen out])
         (object-array)
         (.invokeWithArguments handle))
    (let [n (.get errlen ValueLayout/JAVA_LONG 0)]
      (if (zero? n)
        (try
          (copy-back!)
          (let [m (read-struct out desc)]
            (if record-factory (record-factory m) m))
          (finally
            (when free-handle
              (.invokeWithArguments free-handle (object-array [out])))))
        (do
          (copy-back!)
          (read-error-name errbuf n))))))

(defn- invoke-error-union
  "Run a non-struct error-union downcall. The value type is a scalar
  (coerced with the unsigned policy), `:void` (nil), or a named enum whose
  backing int maps to its member keyword (an unknown int returns the raw
  int, total per ADR 20). On failure read the error keyword."
  [{:keys [handle ret error-buffer-bytes]} ^Arena arena base-carriers copy-back!]
  (let [errbuf ^MemorySegment (.allocate arena error-buffer-bytes 1)
        errlen ^MemorySegment (.allocate arena 8 8)
        result (->> (concat base-carriers [errbuf errlen])
                    (object-array)
                    (.invokeWithArguments handle))
        n      (do (copy-back!) (.get errlen ValueLayout/JAVA_LONG 0))]
    (if (zero? n)
      (let [value-t (:of ret)]
        (cond
          (type/void-type? value-t) nil
          (enum-type? value-t)      (enum-value->member (:layout value-t) (long result))
          :else                     (coerce-scalar value-t result)))
      (read-error-name errbuf n))))

(defn- invoke-owned-record
  "Run an owned or borrowed record downcall. The result writes its fields
  through a caller-allocated wire-struct out-segment; clj-zig reads each
  field, then frees owned memory through the per-field shim. The free runs
  in a finally so a read fault cannot leak any buffer the body allocated
  (mirror of c8a822b). A borrowed record has no shim. The result rebuilds
  as a record via its map-> factory when the named type is a defrecordz,
  else a plain map."
  [{:keys [handle ret record-factory free-handle]} ^Arena arena base-carriers copy-back!]
  (let [desc (-> ret :of :layout)
        out  ^MemorySegment (.allocate arena (long (:size desc)) (long (:align desc)))]
    (->> (concat base-carriers [out])
         (object-array)
         (.invokeWithArguments handle))
    (try
      (copy-back!)
      (let [m (read-struct out desc)]
        (if record-factory (record-factory m) m))
      (finally
        (when free-handle
          (.invokeWithArguments free-handle (object-array [out])))))))

(defn- invoke-owned-slice
  "Run an owned or borrowed slice / :bytes / :string downcall. The result
  writes its pointer and length to two out-params; clj-zig copies the
  elements out (a :bytes return as one byte[], a :string return decoded as
  UTF-8 with replacement, any other slice as a vector), then frees owned
  memory through the shim. The free runs in a finally so a read fault (a
  wild pointer, or an OOM on a huge length) cannot leak the slice the body
  allocated (ADR 21, mirror of c8a822b). copy-back! runs inside the same
  try: the native call already allocated the owned slice, so a copy-back
  fault must still free. A borrowed return has no shim."
  [{:keys [handle ret free-handle]} ^Arena arena base-carriers copy-back!]
  (let [pout ^MemorySegment (.allocate arena 8 8)
        lout ^MemorySegment (.allocate arena 8 8)]
    (->> (concat base-carriers [pout lout])
         (object-array)
         (.invokeWithArguments handle))
    (let [addr (.get pout ValueLayout/JAVA_LONG 0)
          len  (.get lout ValueLayout/JAVA_LONG 0)]
      (try
        (copy-back!)
        (case (:kind ret)
          :bytes  (read-bytes addr len)
          :string (read-utf8-string addr len)
          (read-slice-values addr len (-> ret :of :of)))
        (finally
          (when free-handle
            (.invokeWithArguments free-handle (object-array [addr len]))))))))

(defn- invoke-struct-return
  "Run a plain struct-return downcall. The result is written through a
  caller-allocated out-segment, then read back into a Clojure map (rebuilt
  as a record via its map-> factory when the named type is a defrecordz).
  An enum return crosses as its backing int and takes the scalar path, not
  this one."
  [{:keys [handle ret record-factory]} ^Arena arena base-carriers copy-back!]
  (let [desc (:layout ret)
        out  ^MemorySegment (.allocate arena (long (:size desc)) (long (:align desc)))]
    (->> (concat base-carriers [out])
         (object-array)
         (.invokeWithArguments handle))
    (copy-back!)
    (let [m (read-struct out desc)]
      (if record-factory (record-factory m) m))))

(defn- invoke-scalar
  "Run a plain scalar, enum, or void downcall: invoke, copy mutable args
  back, and read the return with the unsigned policy. The arena is held by
  the caller for slice arguments; a scalar return reads nothing through a
  segment, so it is unused here."
  [{:keys [handle ret]} _arena base-carriers copy-back!]
  (let [result (->> base-carriers (object-array) (.invokeWithArguments handle))]
    (copy-back!)
    (from-return ret result)))

(defn bind
  "Load `library-path`, look up the spec's symbol, and return a Clojure
  fn that calls it with scalar coercion. The library is held by the
  global arena for the JVM lifetime; redefinition produces a fresh
  content-addressed library rather than reloading.

  A scalar-only signature (every param and the return a plain scalar) takes
  a hot path that opens NO confined arena and does no per-arg marshalling
  bookkeeping -- it coerces directly into a thread-local carrier array and
  invokes -- because the arena is dead weight when nothing crosses as a
  native segment. Every other signature keeps the general path, whose
  confined arena holds the native copies of slice/pointer/struct args for
  the call (ADR 37/39)."
  [spec library-path]
  (let [linker (Linker/nativeLinker)
        ;; Loading a native library is a restricted operation; a JVM that
        ;; denies native access fails here, so translate it into a
        ;; diagnostic that names the flag rather than the raw FFM error.
        ;; `foreign/library-lookup` opens the file and degrades a bad path
        ;; as data; `with-native-access` layers the native-access
        ;; diagnostic on top, so both failure modes read clearly.
        lookup (with-native-access #(foreign/library-lookup library-path))
        sym    (foreign/find-symbol lookup (:symbol spec))
        handle ^MethodHandle (.downcallHandle linker sym (descriptor spec)
                                              (into-array Linker$Option []))
        params (:params spec)
        ret    (:ret spec)
        arity  (count params)
        ;; An owned/borrowed record and an error-union over a struct both
        ;; wrap the value's named type under :of; everything else (a plain
        ;; struct return, an enum, a scalar, a scalar/void/enum error-union)
        ;; names the value type directly on ret. Unwrapping consistently is
        ;; safe: the record-factory lookup below returns nil for any
        ;; non-named or enum-named value.
        ret-value    (if (contains? #{:owned :borrowed :error-union} (:kind ret))
                       (:of ret)
                       ret)
        ;; A record return names the map-factory that rebuilds it; a plain
        ;; struct return has none and stays a map.
        record-factory (when (and (= :named (:kind ret-value)) (:record (:layout ret-value)))
                         (requiring-resolve (:record (:layout ret-value))))
        eu-struct?   (and (= :error-union (:kind ret))
                          (= :named (get-in ret [:of :kind]))
                          (not (enum-type? (:of ret))))
        owned-rec?   (and (contains? #{:owned :borrowed} (:kind ret))
                          (= :named (get-in ret [:of :kind])))
        owned-slice? (and (contains? #{:owned :borrowed :bytes :string} (:kind ret))
                          (not owned-rec?))
        struct-return? (and (= :named (:kind ret)) (not (enum-type? ret)))
        ;; An owned slice (or :bytes buffer, or :string) return carries a
        ;; free shim taking the slice's pointer and length; an owned record
        ;; and an error-union over a struct both carry a per-field free shim
        ;; taking a pointer to the wire struct (the eu-struct shim runs on
        ;; the success path only); a borrowed return frees nothing. A
        ;; :string return always owns its bytes (allocated by the body,
        ;; decoded on read).
        struct-free? (or eu-struct? (and owned-rec? (= :owned (:kind ret))))
        free-handle (cond
                      struct-free?
                      (.downcallHandle linker
                                       (foreign/find-symbol lookup (str (:symbol spec) "__free"))
                                       (FunctionDescriptor/ofVoid
                                        (into-array MemoryLayout [ValueLayout/ADDRESS]))
                                       (into-array Linker$Option []))
                       (contains? #{:owned :bytes :string} (:kind ret))
                       (.downcallHandle linker
                                        (foreign/find-symbol lookup (str (:symbol spec) "__free"))
                                        (FunctionDescriptor/ofVoid
                                         (into-array MemoryLayout [ValueLayout/JAVA_LONG
                                                                   ValueLayout/JAVA_LONG]))
                                        (into-array Linker$Option []))
                       :else nil)
        ;; The per-bind return context the general-path dispatch threads into
        ;; each `invoke-*` helper: the bound handle and the return shape's
        ;; once-computed metadata. Built once per bind, reused every call.
        invoke-ctx {:handle handle :ret ret :record-factory record-factory
                    :free-handle free-handle :error-buffer-bytes error-buffer-bytes}
        var-sym (symbol (str (:ns spec)) (str (:name spec)))
        ;; The hot path for a scalar-only signature: no per-call arena, and
        ;; a thread-local carrier array reused across calls on the same
        ;; thread (each thread gets its own, so concurrent callers never
        ;; share one). The native call does not retain the array, and
        ;; one-directional interop (ADR 10) means a call cannot re-enter
        ;; itself on the same thread, so reuse is safe.
        scalar?     (scalar-only? params ret)
        carriers-tl (when scalar?
                      (ThreadLocal/withInitial
                       (reify java.util.function.Supplier
                         (get [_] (object-array arity)))))]
    (if scalar?
      (fn [& args]
        (when (not= (count args) arity)
          (throw (wrong-arity-ex var-sym arity (count args))))
        (let [^objects carriers (.get ^ThreadLocal carriers-tl)]
          (loop [i 0 as args]
            (when (< i (long arity))
              (aset carriers i (to-carrier (nth params i) (first as)))
              (recur (inc i) (next as))))
          (from-return ret (.invokeWithArguments ^MethodHandle handle carriers))))
      (fn [& args]
       (when (not= (count args) arity)
         (throw (wrong-arity-ex var-sym arity (count args))))
       ;; A confined arena holds the native copies of any slice arguments
       ;; for exactly the duration of the call. Mutable slices are copied
       ;; back before it closes, keeping the lifetime to the call.
       (with-open [arena (Arena/ofConfined)]
        (let [marshalled    (mapv #(marshal-arg arena %1 %2) params args)
              base-carriers (mapcat :carriers marshalled)
              copy-back!    #(run! (fn [m] (when-let [back (:copy-back m)] (back)))
                                   marshalled)]
           (cond
              eu-struct?                   (invoke-eu-struct   invoke-ctx arena base-carriers copy-back!)
              (= :error-union (:kind ret)) (invoke-error-union invoke-ctx arena base-carriers copy-back!)
              owned-rec?                   (invoke-owned-record invoke-ctx arena base-carriers copy-back!)
              owned-slice?                 (invoke-owned-slice  invoke-ctx arena base-carriers copy-back!)
              struct-return?               (invoke-struct-return invoke-ctx arena base-carriers copy-back!)
              :else                        (invoke-scalar       invoke-ctx arena base-carriers copy-back!))))))))

(comment
  ;; A whole small pipeline: build, compile, bind, call.
  (require '[clj-zig.spec :as spec] '[clj-zig.source :as source] '[clj-zig.compile :as compile])
  (let [s   (spec/build-spec '{:ns app.core :name add :signature [x :i64 y :i64 :ret :i64]})
        dir (str (java.nio.file.Files/createTempDirectory
                  "clj-zig" (make-array java.nio.file.attribute.FileAttribute 0)))
        lib (compile/compile! {:source (source/generate s "return x + y;")
                               :source-path (str dir "/source.zig")
                               :library-path (str dir "/libadd." (compile/dynamic-library-extension))
                               :ctx {:var 'app.core/add :signature (:signature s)}})
        add (bind s (:library lib))]
    (add 20 22)))  ;; => 42
