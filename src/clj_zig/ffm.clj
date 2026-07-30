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
           (java.lang.foreign Arena FunctionDescriptor Linker$Option
                              MemoryLayout MemorySegment ValueLayout)
           (java.lang.invoke MethodHandle MethodType)
           (java.lang.reflect Array)
           (java.math BigInteger)
           (java.nio.charset StandardCharsets)
           (clojure.lang PersistentArrayMap)))

(def ^:private two-to-64 (.shiftLeft BigInteger/ONE 64))
(def ^:private two-to-64-minus-1 (.subtract two-to-64 BigInteger/ONE))
(def ^:private two-to-128 (.shiftLeft BigInteger/ONE 128))

;; The C `__int128` ABI: a 16-byte value passed and returned as two 64-bit
;; halves. FFM carries it as a struct of two JAVA_LONGs in a MemorySegment;
;; a by-value return makes FFM prepend a SegmentAllocator to the handle.
(def ^:private i128-layout
  (MemoryLayout/structLayout (into-array MemoryLayout [ValueLayout/JAVA_LONG ValueLayout/JAVA_LONG])))

(defn- i128-type?
  "True when a normalized type is one of the 128-bit integer scalars."
  [t]
  (and (= :scalar (:kind t)) (type/i128-type? (:name t))))

(defn- bigint->i128-segment
  "Write `b` as the C `__int128` two's-complement pattern (two host-order
  longs) into a fresh 16-byte segment allocated from `arena`. Every target
  clj-zig supports is little-endian, so host order matches the LE `__int128`
  ABI; the longs are written in host order, not byte-swapped."
  [^Arena arena ^BigInteger b]
  (let [pattern (.mod (.add b two-to-128) two-to-128)  ; the 128-bit pattern, 0..2^128-1
        lo (.longValue (.mod pattern two-to-64))
        hi (.longValue (.shiftRight pattern 64))
        seg (.allocate arena i128-layout)]
    (.set seg ValueLayout/JAVA_LONG 0 lo)
    (.set seg ValueLayout/JAVA_LONG 8 hi)
    seg))

(defn- i128-segment->bigint
  "Read the C `__int128` pattern (two host-order longs) from `seg` as a
  BigInteger, applying the unsigned-or-signed policy of `t` (`:u128` keeps
  the full unsigned pattern; `:i128` subtracts 2^128 when the sign bit is
  set). See `bigint->i128-segment` for the endianness assumption."
  [t ^MemorySegment seg]
  (let [lo (.get seg ValueLayout/JAVA_LONG 0)
        hi (.get seg ValueLayout/JAVA_LONG 8)
        ;; Reassemble the unsigned 128-bit pattern from the two longs.
        lo-big (.and (BigInteger/valueOf lo) two-to-64-minus-1)
        hi-big (.and (BigInteger/valueOf hi) two-to-64-minus-1)
        pattern (.add (.shiftLeft hi-big 64) lo-big)]
    (if (and (= :i128 (:name t)) (.testBit pattern 127))
      (.subtract pattern two-to-128)
      pattern)))

(declare marshal-struct read-struct-field read-bytes read-slice-values
         read-utf8-string write-scalar enum-member->value enum-value->member
         enum-index coerce-scalar
         pool-enabled global-arena
         compiled-struct-writer-chain
         throw-unknown-enum-member throw-handle-mismatch)

;; An opaque native resource handle: the symbol naming its Zig type and
;; the native pointer. The caller threads it back across calls and frees
;; it explicitly; it never inspects the pointer.
(deftype Handle [^Object type ^MemorySegment segment])

(defmethod print-method Handle [h ^java.io.Writer w]
  (.write w (str "#clj-zig/handle[" (.type h) "]")))

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
  layout (a 128-bit integer is a 16-byte struct of two longs); a pointer,
  array, or named struct is an address; an enum is its backing scalar; a
  slice or `:string` is an address and a `usize` length (a `:string`
  argument lowers to the same const-u8 wire shape as a slice)."
  [{:keys [type]}]
  (case (:kind type)
    (:slice :string)                          [ValueLayout/ADDRESS ValueLayout/JAVA_LONG]
    :named                                    (if (enum-type? type)
                                                 [(value-layout (-> type :layout :backing))]
                                                 [ValueLayout/ADDRESS])
    (:ptr :manyptr :array :optional :handle)  [ValueLayout/ADDRESS]
    [(if (i128-type? type) ValueLayout/ADDRESS (value-layout type))]))

(defn- param-carrier-count
  "The number of carrier slots `param` writes into the invoke array. Mirrors
  `param-layouts` length so the general invoker's loop can advance its write
  offset without realizing a layout vector per call."
  [param]
  (case (-> param :type :kind)
    (:slice :string) 2
    1))

(defn- return-layout ^MemoryLayout [ret]
  (cond
    (= :optional (:kind ret))                ValueLayout/ADDRESS
    (= :handle (:kind ret))                  ValueLayout/ADDRESS
    (and (= :named (:kind ret))
         (enum-type? ret))                   (value-layout (-> ret :layout :backing))
    (i128-type? ret)                         i128-layout
    :else                                    (value-layout ret)))

(defn- classify-return
  "The return-shape classification shared by `descriptor` (the ABI layout)
  and `bind` (the call choreography): a tagged map of the shape predicates
  over `ret`. Computing it once here keeps the ABI and the dispatch in
  sync -- a shape added or adjusted in one but not the other would silently
  break the native boundary."
  [ret]
  (let [eu?           (= :error-union (:kind ret))
        owned-rec?    (and (contains? #{:owned :borrowed} (:kind ret))
                           (= :named (get-in ret [:of :kind])))
        struct-ret?   (and (= :named (:kind ret)) (not (enum-type? ret)))
        i128-ret?     (i128-type? ret)]
    {:eu?          eu?
      :eu-struct?   (and eu?
                         (= :named (get-in ret [:of :kind]))
                         (not (enum-type? (:of ret))))
      :owned-rec?   owned-rec?
      :owned-slice? (and (contains? #{:owned :borrowed :bytes :string} (:kind ret))
                         (not owned-rec?))
      :struct-ret?  struct-ret?
      :i128-ret?    i128-ret?
      :opt-struct?  (and (= :optional (:kind ret))
                         (= :named (get-in ret [:of :kind]))
                         (not (enum-type? (:of ret))))
      :stream?      (= :stream (:kind ret))}))

(defn- descriptor ^FunctionDescriptor [spec]
  (let [ret (:ret spec)
        {:keys [eu? eu-struct? owned-rec? owned-slice? struct-ret? i128-ret? stream?]}
        (classify-return ret)
        ;; Trailing out-params per ABI return shape (all export void):
        ;; error-union adds errbuf+errlen; struct-return, owned-record, and a
        ;; 128-bit integer return add one out-pointer; owned-slice/:bytes/:string
        ;; add ptr+len.
        extra        (cond eu-struct?                     [ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/ADDRESS]
                           eu?                            [ValueLayout/ADDRESS ValueLayout/ADDRESS]
                           owned-slice?                   [ValueLayout/ADDRESS ValueLayout/ADDRESS]
                           (or struct-ret? owned-rec? i128-ret?) [ValueLayout/ADDRESS]
                           :else                          [])
        arg-layouts  (into-array MemoryLayout (concat (mapcat param-layouts (:params spec))
                                                      extra))
        ret-value    (if eu? (:of ret) ret)]
    (cond
      stream?    (FunctionDescriptor/of ValueLayout/JAVA_LONG arg-layouts)
      (or eu-struct? struct-ret? owned-rec? owned-slice? i128-ret? (type/void-type? ret-value))
      (FunctionDescriptor/ofVoid arg-layouts)
      :else      (FunctionDescriptor/of (return-layout ret-value) arg-layouts))))

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

(defn- scalar-param-coerce
  "Build a per-call coercion fn for one scalar param, hoisting the
  `type/scalar-info` lookup to bind time so the per-call body is only the
  category-and-bits case dispatch with no map lookup. The scalar hot path
  calls the returned fn per arg per call."
  [param]
  (let [{:keys [category bits]} (type/scalar-info (-> param :type :name))]
    (case category
      :int   (case (long bits)
               8  (fn int8-coerce [v] (let [l (.longValue (biginteger v))]
                                        (unchecked-byte l)))
               16 (fn int16-coerce [v] (let [l (.longValue (biginteger v))]
                                         (unchecked-short l)))
               32 (fn int32-coerce [v] (let [l (.longValue (biginteger v))]
                                         (unchecked-int l)))
               64 (fn int64-coerce [v] (.longValue (biginteger v))))
      :float (case (long bits)
               32 (fn f32-coerce [v] (unchecked-float v))
               64 (fn f64-coerce [v] (double v)))
      :bool  (fn bool-coerce [v] (boolean v)))))

(defn- scalar-return-coerce
  "Build a per-call return coercion fn for a scalar-or-void return. The
  void case returns a constant-nil fn; the scalar case captures the
  return's category, signedness, and bit width at bind time and inlines
  the specific decode, eliminating the per-call `coerce-scalar` `case`
  dispatch. Mirrors `scalar-param-coerce`'s per-bind specialization."
  [ret]
  (if (type/void-type? ret)
    (fn void-ret [_] nil)
    (let [{:keys [category signed? bits]} (type/scalar-info (:name ret))]
      (case category
        :bool  (fn bool-ret [v] (boolean v))
        :float (fn float-ret [v] (double v))
        :int   (if signed?
                 (fn signed-int-ret [v] (long v))
                 (case (long bits)
                   8  (fn u8-ret  [v] (bit-and (long v) 0xff))
                   16 (fn u16-ret [v] (bit-and (long v) 0xffff))
                   32 (fn u32-ret [v] (bit-and (long v) 0xffffffff))
                   64 (fn u64-ret [v] (let [l (long v)]
                                        (if (neg? l)
                                          (.add (biginteger l) two-to-64)
                                          l)))))))))

(declare marshal-struct-into! marshal-buffer-field buffer-field-element marshal-array
         compiled-struct-reader)

(defn- marshal-arg-fn
  "Build a per-param marshal closure that captures the param's kind, type,
  and any inner bindings at bind time. The returned fn takes
  `[arena arg carriers off]` and writes the param's carrier(s) at offset,
  returning the copy-back thunk or nil. Each closure captures one case's
  body, eliminating the per-call `case (:kind type)` walk."
  [param]
  (let [type (:type param)]
    (case (:kind type)
      :string
      (fn string-marshal [^Arena arena arg ^objects cs ^long off]
        (let [bs (if (string? arg)
                   (.getBytes ^String arg StandardCharsets/UTF_8)
                   (do (when-not (bytes? arg)
                         (throw (ex-info (str "A :string argument must be a String or a byte[]"
                                              " of UTF-8; got " (pr-str (type arg)) ".")
                                         {:level :error
                                          :error/code :clj-zig/string-argument
                                          :actual (type arg)})))
                       arg))
              len (alength ^bytes bs)
              seg ^MemorySegment (.allocate arena (long len) 1)]
          (when (pos? len)
            (MemorySegment/copy bs (long 0) seg ValueLayout/JAVA_BYTE (long 0) (long len)))
          (aset cs off seg)
          (aset cs (inc off) (Long/valueOf (long len)))
          nil))

      :slice
      (fn slice-marshal [^Arena arena arg ^objects cs ^long off]
        (let [{:keys [address length copy-back]} (marshal-array arena param arg)]
          (aset cs off address)
          (aset cs (inc off) length)
          copy-back))

      :manyptr
      (fn manyptr-marshal [^Arena arena arg ^objects cs ^long off]
        (let [{:keys [address copy-back]} (marshal-array arena param arg)]
          (aset cs off address)
          copy-back))

      :ptr
      (fn ptr-marshal [^Arena arena arg ^objects cs ^long off]
        (when (not= 1 (Array/getLength arg))
          (throw (ex-info "A :ptr argument must be a one-element array."
                          {:level :error
                           :error/code :clj-zig/pointer-arity
                           :expected 1
                           :actual (Array/getLength arg)})))
        (let [{:keys [address copy-back]} (marshal-array arena param arg)]
          (aset cs off address)
          copy-back))

      :array
      (let [n      (-> type :length)
            named? (= :named (:kind (:of type)))]
        (fn array-marshal [^Arena arena arg ^objects cs ^long off]
          (let [actual (if named? (count arg) (Array/getLength arg))]
            (when (not= (long n) actual)
              (throw (ex-info (str "An :array argument must have length " (long n) ".")
                              {:level :error
                               :error/code :clj-zig/array-length
                               :expected (long n)
                               :actual actual})))
            (aset cs off (:address (marshal-array arena param arg)))
            nil)))

      :optional
      (let [pointed (:of type)]
        (cond
          (= :scalar (:kind pointed))
          (let [layout (value-layout pointed)
                bb     (.byteSize layout)]
            (fn opt-scalar-marshal [^Arena arena arg ^objects cs ^long off]
              (if (nil? arg)
                (do (aset cs off MemorySegment/NULL) nil)
                (let [seg ^MemorySegment (.allocate arena bb bb)]
                  (write-scalar seg pointed 0 (to-carrier {:type pointed} arg))
                  (aset cs off seg)
                  nil))))
          :else
          (let [inner-fn (marshal-arg-fn (update param :type :of))]
            (fn opt-nested-marshal [^Arena arena arg ^objects cs ^long off]
              (if (nil? arg)
                (do (aset cs off MemorySegment/NULL) nil)
                (let [{:keys [copy-back]} (inner-fn arena arg cs off)]
                  copy-back))))))

      :named
      (let [layout (:layout type)]
        (if (:enum layout)
          (let [kw->val (:kw->value (enum-index layout))
                coerce  (scalar-param-coerce {:type (:backing layout)})]
            (fn enum-marshal [_arena arg ^objects cs ^long off]
              (let [value (get kw->val arg)]
                (when (nil? value)
                  (throw-unknown-enum-member layout arg))
                (aset cs off (coerce value))
                nil)))
          (let [[msize malign chain] (or (compiled-struct-writer-chain layout)
                                         [(:size layout) (:align layout) nil])
                marshal-seg-tl (when (and pool-enabled chain)
                                 (ThreadLocal/withInitial
                                  (reify java.util.function.Supplier
                                    (get [_] (.allocate ^Arena global-arena msize malign)))))]
            (if chain
              (fn struct-marshal [^Arena arena arg ^objects cs ^long off]
                (let [seg (if marshal-seg-tl
                            (.get ^ThreadLocal marshal-seg-tl)
                            (.allocate ^Arena arena msize malign))]
                  (chain seg arg)
                  (aset cs off seg)
                  nil))
              (fn struct-marshal-fallback [^Arena arena arg ^objects cs ^long off]
                (let [seg ^MemorySegment (.allocate ^Arena arena msize malign)]
                  (marshal-struct-into! arena seg layout arg)
                  (aset cs off seg)
                  nil))))))

      :handle
      (let [expected (-> type :of :name)]
        (fn handle-marshal [_arena arg ^objects cs ^long off]
          (when-not (and (instance? Handle arg) (= expected (.type ^Handle arg)))
            (throw-handle-mismatch expected arg))
          (aset cs off (.segment ^Handle arg))
          nil))

      (if (i128-type? type)
        (fn i128-marshal [^Arena arena arg ^objects cs ^long off]
          (aset cs off (bigint->i128-segment arena (biginteger arg)))
          nil)
        (let [coerce (scalar-param-coerce param)]
          (fn scalar-marshal [_arena arg ^objects cs ^long off]
            (aset cs off (coerce arg))
            nil))))))

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
      (marshal-struct-into! arena (.asSlice seg (* (long i) stride) stride) inner (nth coll i)))
    {:address seg :length n :copy-back nil}))

(defn- marshal-array
  "Copy a caller-supplied sequence into a fresh native segment from
  `arena` and pass its address. A scalar element is a Java primitive
  array, bulk-copied in one move (with `bool` crossing element by
  element, the FFM API having no boolean bulk copy); a mutable pointee
  copies the segment back into the array after the call. A named-struct
  element is a Clojure collection of maps, marshaled one struct per
  stride via `marshal-struct-collection`."
  [^Arena arena {:keys [type]} arr]
  (let [elem (:of type)]
    (cond
      (and (= :named (:kind elem)) (enum-type? elem))
      (let [layout  (:layout elem)
            backing (:backing layout)
            bl      (value-layout backing)
            bb      (.byteSize bl)
            len     (count arr)
            seg     ^MemorySegment (.allocate ^Arena arena (* len bb) bb)]
        (dotimes [i len]
          (let [v (enum-member->value layout (nth arr i))]
            (when (nil? v)
              (throw-unknown-enum-member layout (nth arr i)))
            (write-scalar seg backing (* (long i) bb) (to-carrier {:type backing} v))))
        {:address seg :length len :copy-back nil})

      (= :named (:kind elem))
      (marshal-struct-collection arena {:type type} arr)

      :else
      (let [bl    (value-layout elem)
            bytes (.byteSize bl)
            len   (Array/getLength arr)
            seg   ^MemorySegment (.allocate ^Arena arena (* len bytes) bytes)
            bool? (= :bool (:category (type/scalar-info (:name elem))))]
        (if bool?
          (dotimes [i len] (.set seg ValueLayout/JAVA_BOOLEAN (long i) (boolean (Array/get arr i))))
          (MemorySegment/copy arr (int 0) seg bl (long 0) (int len)))
        {:address   seg
          :length    (long len)
          :copy-back (when-not (:const? type)
                      (if bool?
                        (fn [] (dotimes [i len]
                                 (Array/set arr i (.get seg ValueLayout/JAVA_BOOLEAN (long i)))))
                         (fn [] (MemorySegment/copy seg bl (long 0) arr (int 0) (int len)))))}))))


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

(def ^:private struct-reader-cache (atom {}))

(defn- throw-missing-field
  "Throw the `:clj-zig/missing-field` diagnostic for a nil field value,
  matching the generic path's message."
  [descriptor-name field-name]
  (throw (ex-info (str "Struct " descriptor-name " is missing field " field-name ".")
                  {:level :error :error/code :clj-zig/missing-field
                   :type descriptor-name :field field-name})))

(defn- throw-unknown-enum-member
  "Throw the `:clj-zig/unknown-enum-member` diagnostic for a keyword the
  enum layout does not carry. Cold validation path; the hot lookup stays
  inline at each call site."
  [layout member]
  (throw (ex-info (str member " is not a member of enum " (:name layout) ".")
                  {:level :error
                   :error/code :clj-zig/unknown-enum-member
                   :type (:name layout) :member member})))

(defn- throw-handle-mismatch
  "Throw the `:clj-zig/handle-type-mismatch` diagnostic for a wrong-typed
  Handle argument. Cold validation path."
  [expected actual]
  (throw (ex-info (str "Expected a :handle of " expected
                       " but got " (pr-str actual) ".")
                  {:level :error
                   :error/code :clj-zig/handle-type-mismatch
                   :expected expected :actual actual})))

(defn- build-struct-writer-chain
  "Build `[size align chain]` for an all-scalar struct, or nil if any
  field is a buffer field, a nested struct, or an enum field (those need
  the generic path's per-field dispatch). The chain takes `(seg, m)`
  and writes the fields directly; it does NOT allocate. Each chain step
  captures its keyword, byte offset, and a scalar-specialized `.set`
  call so the per-call body is a tight scalar write with no `case`
  dispatch and no `to-carrier` call. Honors the unsigned-int range via
  `unchecked-byte`/`unchecked-short`/etc., and throws
  `:clj-zig/missing-field` for a nil field value, mirroring the generic
  path. Exposed separately from `build-struct-writer` so the marshal
  path can pool its segment while reusing the chain."
  [descriptor]
  (when (every? (fn [f]
                  (and (not (:target f))
                       (not (:nested f))
                       (= :scalar (-> f :type :kind))
                       (not (get-in f [:type :layout :enum]))))
                (:fields descriptor))
    (let [size   (:size descriptor)
          align  (:align descriptor)
          descriptor-name (:name descriptor)
          field-writers
          (vec
           (for [f (:fields descriptor)]
             (let [t        (:type f)
                   {:keys [category bits]} (type/scalar-info (:name t))
                   off      (:offset f)
                   kw       (keyword (:name f))
                   field-name (:name f)]
               (case category
                 :int   (case bits
                          8  (fn field-set [^MemorySegment seg ^java.util.Map m]
                               (let [v (.get m kw)]
                                 (when (nil? v) (throw-missing-field descriptor-name field-name))
                                 (.set seg ValueLayout/JAVA_BYTE    (long off) (unchecked-byte (.longValue (biginteger v))))))
                          16 (fn field-set [^MemorySegment seg ^java.util.Map m]
                               (let [v (.get m kw)]
                                 (when (nil? v) (throw-missing-field descriptor-name field-name))
                                 (.set seg ValueLayout/JAVA_SHORT   (long off) (unchecked-short (.longValue (biginteger v))))))
                          32 (fn field-set [^MemorySegment seg ^java.util.Map m]
                               (let [v (.get m kw)]
                                 (when (nil? v) (throw-missing-field descriptor-name field-name))
                                 (.set seg ValueLayout/JAVA_INT     (long off) (unchecked-int (.longValue (biginteger v))))))
                          64 (fn field-set [^MemorySegment seg ^java.util.Map m]
                               (let [v (.get m kw)]
                                 (when (nil? v) (throw-missing-field descriptor-name field-name))
                                 (.set seg ValueLayout/JAVA_LONG    (long off) (.longValue (biginteger v))))))
                 :float (case bits
                          32 (fn field-set [^MemorySegment seg ^java.util.Map m]
                               (let [v (.get m kw)]
                                 (when (nil? v) (throw-missing-field descriptor-name field-name))
                                 (.set seg ValueLayout/JAVA_FLOAT   (long off) (unchecked-float v))))
                          64 (fn field-set [^MemorySegment seg ^java.util.Map m]
                               (let [v (.get m kw)]
                                 (when (nil? v) (throw-missing-field descriptor-name field-name))
                                 (.set seg ValueLayout/JAVA_DOUBLE  (long off) (double v)))))
                 :bool  (fn field-set [^MemorySegment seg ^java.util.Map m]
                          (let [v (.get m kw)]
                            (when (nil? v) (throw-missing-field descriptor-name field-name))
                            (.set seg ValueLayout/JAVA_BOOLEAN (long off) (boolean v))))))))]
       [(long size) (long align)
        (reduce (fn [next-k fw]
                  (fn [seg m]
                    (fw seg m)
                    (next-k seg m)))
                (fn [_seg _m] nil)
                (reverse field-writers))])))

(defn- build-struct-reader
  "Build a tight reader closure for an all-scalar struct, or nil if any
  field is a buffer field, a nested struct, or an enum field. Each field's
  keyword, byte offset, and scalar-specialized `.get` are captured at
  build time."
  [descriptor]
  (when (every? (fn [f]
                  (and (not (:target f))
                       (not (:nested f))
                       (= :scalar (-> f :type :kind))
                       (not (get-in f [:type :layout :enum]))))
                (:fields descriptor))
    (let [field-writers
          (vec
           (for [f (:fields descriptor)]
             (let [t        (:type f)
                   {:keys [category bits signed?]} (type/scalar-info (:name t))
                   off      (:offset f)
                   kw       (keyword (:name f))]
                ;; The `^Object` hint is load-bearing: `aset` on `^objects`
                ;; has per-primitive-type overloads the compiler cannot
                ;; disambiguate from a primitive. Without it: reflective (50x).
               (case category
                 :int   (case bits
                          8  (if signed?
                               (fn field-write [^MemorySegment seg ^objects arr ^long idx]
                                 (aset arr idx kw)
                                 (aset arr (inc idx) ^Object (.get seg ValueLayout/JAVA_BYTE (long off))))
                               (fn field-write [^MemorySegment seg ^objects arr ^long idx]
                                 (aset arr idx kw)
                                 (aset arr (inc idx) ^Object (bit-and (.get seg ValueLayout/JAVA_BYTE (long off)) 0xff))))
                          16 (if signed?
                               (fn field-write [^MemorySegment seg ^objects arr ^long idx]
                                 (aset arr idx kw)
                                 (aset arr (inc idx) ^Object (.get seg ValueLayout/JAVA_SHORT (long off))))
                               (fn field-write [^MemorySegment seg ^objects arr ^long idx]
                                 (aset arr idx kw)
                                 (aset arr (inc idx) ^Object (bit-and (.get seg ValueLayout/JAVA_SHORT (long off)) 0xffff))))
                          32 (if signed?
                               (fn field-write [^MemorySegment seg ^objects arr ^long idx]
                                 (aset arr idx kw)
                                 (aset arr (inc idx) ^Object (.get seg ValueLayout/JAVA_INT (long off))))
                               (fn field-write [^MemorySegment seg ^objects arr ^long idx]
                                 (aset arr idx kw)
                                 (aset arr (inc idx) ^Object (bit-and (.get seg ValueLayout/JAVA_INT (long off)) 0xffffffff))))
                          64 (let [unsigned-ret (fn [v] (let [l (long v)]
                                                          (if (neg? l)
                                                            (.add (biginteger l) two-to-64)
                                                            l)))]
                               (if signed?
                                 (fn field-write [^MemorySegment seg ^objects arr ^long idx]
                                   (aset arr idx kw)
                                   (aset arr (inc idx) ^Object (.get seg ValueLayout/JAVA_LONG (long off))))
                                 (fn field-write [^MemorySegment seg ^objects arr ^long idx]
                                   (aset arr idx kw)
                                   (aset arr (inc idx) ^Object (unsigned-ret (.get seg ValueLayout/JAVA_LONG (long off))))))))
                 :float (case bits
                          32 (fn field-write [^MemorySegment seg ^objects arr ^long idx]
                               (aset arr idx kw)
                               (aset arr (inc idx) ^Object (double (.get seg ValueLayout/JAVA_FLOAT (long off)))))
                          64 (fn field-write [^MemorySegment seg ^objects arr ^long idx]
                               (aset arr idx kw)
                               (aset arr (inc idx) ^Object (double (.get seg ValueLayout/JAVA_DOUBLE (long off))))))
                 :bool  (fn field-write [^MemorySegment seg ^objects arr ^long idx]
                          (aset arr idx kw)
                          (aset arr (inc idx) ^Object (boolean (.get seg ValueLayout/JAVA_BOOLEAN (long off)))))))))]
      (let [nfields (count field-writers)
            arr-size (* 2 (long nfields))
            chain (reduce (fn [next-k fw-idx]
                            (let [fw      (nth fw-idx 0)
                                  base-ix (* 2 (long (nth fw-idx 1)))]
                              (fn [^MemorySegment seg ^objects arr]
                                (fw seg arr base-ix)
                                (next-k seg arr))))
                          (fn [_seg _arr] nil)
                          (reverse (map vector field-writers (range))))]
        (fn struct-reader [^MemorySegment seg]
          (let [arr (object-array arr-size)]
            (chain seg arr)
            (PersistentArrayMap/createWithCheck arr)))))))

(def ^:private struct-writer-chain-cache (atom {}))

(defn- compiled-struct-writer-chain
  "The cached `[size align chain]` for an all-scalar struct, or nil.
  Cached by descriptor so the build happens once per struct shape.
  Exposed so the marshal path can pool its segment while reusing the
  chain (one less per-call segment allocation than the wrapping
  `compiled-struct-writer` path)."
  [descriptor]
  (if-let [e (find @struct-writer-chain-cache descriptor)]
    (val e)
    (let [parts (build-struct-writer-chain descriptor)]
      (swap! struct-writer-chain-cache assoc descriptor parts)
      parts)))

(defn- compiled-struct-reader
  "The compiled reader for `descriptor`, or nil. Cached by descriptor so
  the build happens once per struct shape."
  [descriptor]
  (if-let [e (find @struct-reader-cache descriptor)]
    (val e)
    (let [r (build-struct-reader descriptor)]
      (swap! struct-reader-cache assoc descriptor r)
      r)))

(defn- marshal-struct-into!
  "Write the fields of Clojure map `m` into the existing segment `seg`
  for the struct `descriptor`, each at its C-ABI offset. A scalar field
  is written directly; a buffer field (`:target`) copies the caller's
  value into `arena` and writes the `{ptr, len}` pair at the field's two
  wire offsets; a nested struct field recurses into a sub-segment. Used
  by `marshal-struct` (top-level struct argument), the nested-field
  recursion, and `marshal-struct-collection` (slice/array of structs)."
  [arena ^MemorySegment seg descriptor m]
  (doseq [{:keys [name type offset len-offset target nested]} (:fields descriptor)]
    (let [v (get m (keyword name))]
      (when (nil? v)
        (throw-missing-field (:name descriptor) name))
      (cond
        target
        (let [{:keys [address length]} (marshal-buffer-field arena type v)]
          (.set seg ValueLayout/JAVA_LONG (long offset) (.address ^MemorySegment address))
          (.set seg ValueLayout/JAVA_LONG (long len-offset) (long length)))

        nested
        (let [inner (:layout type)
              sub   (.asSlice seg (long offset) (long (:size inner)))]
          (marshal-struct-into! arena sub inner v))

        :else
        (write-scalar seg type offset (to-carrier {:type type} v))))))

(defn- marshal-buffer-field
  "Copy a buffer field's value into the call arena, returning
  `{:address seg :length len}`. A `:string` field copies UTF-8 bytes; a
  `:bytes` field copies a `byte[]`; a slice field writes each scalar
  element at the backing carrier's stride. The arena owns the copy for
  the call's duration."
  [arena type v]
  (case (:kind type)
    :string (let [bs (.getBytes ^String v StandardCharsets/UTF_8)
                  len (alength ^bytes bs)
                  seg ^MemorySegment (.allocate ^Arena arena (long len) 1)]
              (when (pos? len)
                (MemorySegment/copy bs (long 0) seg ValueLayout/JAVA_BYTE (long 0) (long len)))
              {:address seg :length len})
    :bytes  (let [bs v
                  len (alength ^bytes bs)
                  seg ^MemorySegment (.allocate ^Arena arena (long len) 1)]
              (when (pos? len)
                (MemorySegment/copy bs (long 0) seg ValueLayout/JAVA_BYTE (long 0) (long len)))
              {:address seg :length len})
    (let [elem (buffer-field-element type)
          bl   (value-layout elem)
          bb   (.byteSize bl)
          coll (if (vector? v) v (vec v))
          n    (count coll)
          seg  ^MemorySegment (.allocate ^Arena arena (* n bb) bb)]
      (dotimes [i n]
        (write-scalar seg elem (* (long i) bb) (to-carrier {:type elem} (nth coll i))))
      {:address seg :length n})))

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
  nothing and never dereferences the pointer. An all-scalar struct uses a
  compiled reader that captures each field's offset and scalar getter at
  build time, eliminating the per-field type dispatch the generic path
  does; structs with buffer or nested fields fall back to that path."
  [^MemorySegment seg descriptor]
  (if-let [reader (compiled-struct-reader descriptor)]
    (reader seg)
    (reduce (fn [acc field]
              (assoc acc (keyword (:name field)) (read-struct-field seg field)))
            {} (:fields descriptor))))

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
    (let [inner (:layout type)
          m (read-struct (.asSlice seg (long offset) (long (:size inner))) inner)]
      ;; A nested defrecordz field rebuilds via its map factory, mirroring the
      ;; top-level record-return path; a deftypez field (no :record) stays a map.
      (if-let [factory-sym (:record inner)]
        ((requiring-resolve factory-sym) m)
        m))

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

(def ^:private max-native-read-bytes
  "A sanity ceiling on a single read whose length is sourced from native
  memory (a wire struct's len field or an owned-slice out-param). A length
  above this is corrupt data, not a buffer to allocate or reinterpret; it
  is rejected with :clj-zig/corrupt-native-length rather than driving a
  multi-GB allocation or a wrapped-negative reinterpret. 1 GiB is well
  under the per-array limit and far above any legitimate single field."
  0x40000000)

(defn- check-native-len
  "Return `len` as a long when it is non-negative and at most
  `max-native-read-bytes`, else throw. The length is untrusted (read from
  native); a negative or absurdly large value is corrupt data, and
  interpreting it would OOM or overflow the reinterpret size."
  ^long [len]
  (let [n (long len)]
    (when (or (neg? n) (> n max-native-read-bytes))
      (throw (ex-info "A native buffer or slice reported a corrupt length."
                      {:level :error
                       :error/code :clj-zig/corrupt-native-length
                       :length n
                       :max-native-read-bytes max-native-read-bytes})))
    n))

(defn- read-slice-values
  "Copy `len` elements from the native address `addr` into an immutable
  Clojure vector. A scalar element bulk-copies into a typed primitive
  array (one native move, `bool` element by element) and is coerced with
  the unsigned-return policy (ADR 18). A named-enum element bulk-copies
  its backing ints the same way, then maps each to its member keyword.
  A named-struct element reads one struct per stride via `read-struct`,
  producing a vector of maps. A zero length reads nothing, so the
  address is never dereferenced for an empty slice."
  [addr len elem]
  (if (zero? len)
    []
    (let [n (long len)]
      (cond
        (and (= :named (:kind elem)) (enum-type? elem))
        (let [backing (:backing (:layout elem))
              bl      (value-layout backing)
              size    (check-native-len (* n (.byteSize bl)))
              seg     (.reinterpret (MemorySegment/ofAddress addr) size)
              {:keys [bits]} (type/scalar-info (:name backing))
              arr     (case bits
                        8  (fill-array seg bl n (byte-array n))
                        16 (fill-array seg bl n (short-array n))
                        32 (fill-array seg bl n (int-array n))
                        64 (fill-array seg bl n (long-array n)))]
          (mapv #(enum-value->member (:layout elem) (coerce-scalar backing %)) arr))

        (= :named (:kind elem))
        (let [inner  (:layout elem)
              stride (long (:size inner))
              size   (check-native-len (* n stride))
              base   (.reinterpret (MemorySegment/ofAddress addr) size)]
          (mapv #(read-struct (.asSlice base (* (long %) stride) stride) inner)
                (range n)))

        :else
        (let [layout (value-layout elem)
              size   (check-native-len (* n (.byteSize layout)))
              seg    (.reinterpret (MemorySegment/ofAddress addr) size)
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
  (let [n   (check-native-len len)
        out (byte-array n)]
    (when (pos? n)
      (let [seg (.reinterpret (MemorySegment/ofAddress addr) n)]
        (MemorySegment/copy seg ValueLayout/JAVA_BYTE (long 0) out (int 0) (int n))))
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

(def ^:private enum-index-cache
  ;; Per enum `:values`, the `{kw->value value->kw}` lookup maps. Keyed by
  ;; the `:values` vector (structural equality); growth is bounded by the
  ;; number of distinct enum definitions.
  (atom {}))

(defn- enum-index
  "The `{:kw->value :value->kw}` lookup maps for an enum layout's members."
  [descriptor]
  (let [values (:values descriptor)]
    (or (get @enum-index-cache values)
        (let [idx {:kw->value (into {} (map (fn [{:keys [name value]}]
                                              [(keyword (str name)) value])) values)
                   :value->kw (into {} (map (fn [{:keys [name value]}]
                                              [value (keyword (str name))])) values)}]
          (swap! enum-index-cache assoc values idx)
          idx))))

(defn- enum-member->value
  "The backing integer for an enum member keyword, or nil when no member
  of `descriptor` carries that name."
  [descriptor kw]
  (get (:kw->value (enum-index descriptor)) kw))

(defn- enum-value->member
  "The member keyword for an enum backing integer, or the raw integer when
  no member of `descriptor` carries that value."
  [descriptor v]
  (or (get (:value->kw (enum-index descriptor)) v) v))

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
  or nil for an `:optional` pointer, a `BigInteger` for a 128-bit integer
  (read out of the returned 16-byte segment), and the unsigned-aware
  scalar value otherwise."
  [ret v]
  (cond
    (type/void-type? ret)     nil
    (= :optional (:kind ret)) (deref-optional ret v)
    (= :handle (:kind ret))   (when-not (zero? (.address ^MemorySegment v))
                                (Handle. (-> ret :of :name) v))
    (and (= :named (:kind ret))
         (enum-type? ret))    (enum-value->member (:layout ret) (long v))
    (i128-type? ret)          (i128-segment->bigint ret v)
    :else                     (coerce-scalar ret v)))

(def ^:private error-buffer-bytes
  "The size of the error-name buffer the caller hands an error-union
  wrapper; error names are short, so 256 bytes is ample."
  256)

(defn- read-error-name
  "Read an error name from `buf` and return it as a keyword. `n` is the
  native-written byte count, clamped to the buffer size and floored at zero
  so a corrupt length cannot drive the copy past the buffer."
  [^MemorySegment buf n]
  (let [len   (long (max 0 (min n error-buffer-bytes)))
        bytes (byte-array len)]
    (MemorySegment/copy buf ValueLayout/JAVA_BYTE 0 bytes (int 0) (int len))
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

(defn- check-arity!
  "Throw :clj-zig/arity when `args` is not `arity` long. Caches the count
  in a primitive `long` so the previous shape's double `count` call (one
  for the check, one for the diagnostic) collapses to one. ArraySeq (the
  direct-call shape) is Counted, so `count` is O(1) on the hot path;
  `apply` produces a LazySeq that walks, but that is the rare path."
  [var-sym ^long arity args]
  (let [actual (long (count args))]
    (when (not= actual arity)
      (throw (wrong-arity-ex var-sym arity actual)))))

(defn- scalar-only?
  "True when every param and the return cross as a plain scalar carrier, so
  the call needs no confined arena. A scalar param coerces straight to its
  carrier with `to-carrier` (no native segment is allocated), and a scalar
  or `:void` return reads back with no out-pointer. Anything that touches
  the arena -- a slice, pointer, array, struct, enum (the enum-aware path
  of `enum-aware-scalar?` covers it), handle, optional, a 128-bit integer
  (a 16-byte segment, and a by-value return prepends a SegmentAllocator to
  the handle), or an error-union/owned/struct return -- is excluded. The
  enum-aware path picks up signatures over only scalars and enums; everything
  else takes the general arena-backed path. (`:void` normalizes to
  `{:kind :scalar :name :void}`, so the return test covers it.)"
  [params ret]
  (and (every? (fn [p] (and (= :scalar (-> p :type :kind))
                            (not (i128-type? (:type p)))))
               params)
       (= :scalar (:kind ret))
       (not (i128-type? ret))))

(defn- enum-aware-scalar?
  "True when every param and the return cross as a plain scalar carrier,
  a named enum, or a `[:handle Type]` arg (a pointer aset, no arena), and
  the return is a plain scalar, named enum, `[:handle Type]`, or `:void`,
  so the call needs no confined arena, AND the signature is not
  pure-scalar (`scalar-only?` covers that case). An enum crosses the C
  ABI as its backing scalar; a handle is a pointer threaded opaquely
  across. Both lower to the same `(int|long|ptr) -> ...` ABI the scalar
  hot path serves. The not-pure-scalar guard keeps the scalar hot path
  single-shape."
  [params ret]
  (and (not (scalar-only? params ret))
       (every? (fn [p] (let [k (:kind (:type p))]
                         (or (and (= :scalar k) (not (i128-type? (:type p))))
                             (and (= :named k) (enum-type? (:type p)))
                             (and (= :handle k) (= :named (-> p :type :of :kind))))))
               params)
       (or (and (= :scalar (:kind ret)) (not (i128-type? ret)))
           (and (= :named (:kind ret)) (enum-type? ret))
           (= :handle (:kind ret)))))

(defn- const-slice-of-scalar?
  "True when `param`'s type is `[:slice :const <scalar>]` for a carrier
  scalar (the shape that lowers to `(ptr, len)` with one bulk copy and no
  copy-back). Excludes bool slices (no bulk-copy primitive), mutable slices
  (which need copy-back), and struct-element slices (which need
  per-element marshalling)."
  [param]
  (let [t (:type param)]
    (and (= :slice (:kind t))
         (:const? t)
         (= :scalar (-> t :of :kind))
         (not= :bool (:category (type/scalar-info (-> t :of :name)))))))

(defn- slice-aware?
  "True when the signature has at least one const-slice-of-scalar arg and
  every other param and the return is a plain scalar, named enum, or
  const-slice-of-scalar. The slice-aware invoker opens a confined arena
  for the slice segments and fills a thread-local carrier array inline,
  skipping marshal-array's per-arg map and the general path's loop
  overhead. Mutable slices, structs, handles, and other arena-touching
  shapes stay on the general path."
  [params ret]
  (and (some const-slice-of-scalar? params)
       (every? (fn [p] (or (and (= :scalar (-> p :type :kind))
                                (not (i128-type? (:type p))))
                           (and (= :named (-> p :type :kind))
                                (enum-type? (:type p)))
                           (const-slice-of-scalar? p)))
               params)
       (or (and (= :scalar (:kind ret)) (not (i128-type? ret)))
           (and (= :named (:kind ret)) (enum-type? ret)))))

(defn- slice-aware-writers
  "Build per-param writer closures for a slice-aware signature. Each
  writer takes `(arena, carriers, off, arg)` and writes the param's
  carriers starting at `off`. A scalar/enum writer captures the
  per-bind `scalar-param-coerce` (no per-call `to-carrier` dispatch)
  and writes one slot; a const-slice writer allocates a segment from
  `arena`, bulk-copies the primitive array in, and writes (seg, len-long)
  at `off` and `(inc off)`. Returns `[writers carrier-counts]` so the
  invoker can advance its offset without rederiving the count per call."
  [params]
  (let [entries
        (for [p params]
          (let [t (:type p)]
            (cond
               (and (= :scalar (:kind t)) (not (i128-type? t)))
               (let [coerce (scalar-param-coerce p)]
                 {:n 1
                  :write (fn [_arena ^objects cs ^long off arg]
                           (aset cs off (coerce arg)))})

              (and (= :named (:kind t)) (enum-type? t))
              (let [layout  (:layout t)
                    backing (:backing layout)
                    kw->val (:kw->value (enum-index layout))]
                {:n 1
                 :write (fn [_arena ^objects cs ^long off arg]
                          (let [value (get kw->val arg)]
                            (when (nil? value)
                              (throw-unknown-enum-member layout arg))
                            (aset cs off (to-carrier {:type backing} value))))})

              (const-slice-of-scalar? p)
              (let [elem  (:of t)
                    bl    (value-layout elem)
                    bytes (.byteSize bl)]
                {:n 2
                 :write (fn [^Arena arena ^objects cs ^long off arg]
                          (let [len (Array/getLength arg)
                                seg ^MemorySegment (.allocate arena (* len bytes) bytes)]
                            (MemorySegment/copy arg (int 0) seg bl (long 0) (int len))
                            (aset cs off seg)
                            (aset cs (inc off) (Long/valueOf (long len)))))}))))]
    [(mapv :write entries) (mapv :n entries)]))

(defn- slice-aware-chain
  "Build a 3-arg `(arena, carriers, args)` fn that runs each slice-aware
  writer at its pre-computed offset."
  [writers counts]
  (let [offs (reductions (fn [acc c] (+ acc c)) 0 (drop-last (seq counts)))
        indexed (map vector writers offs (range))]
    (reduce (fn [next-k [writer off idx]]
              (fn [^Arena arena ^objects carriers args]
                (writer arena carriers (long off) (nth args idx))
                (next-k arena carriers args)))
            (fn [_arena _carriers _args] nil)
            (reverse indexed))))

(defn- build-marshal-chain
  "Build a 3-arg `[arena args carriers]` fn that calls each per-bind
  marshal fn at its pre-computed offset. Mirrors `slice-aware-chain`."
  [marshal-fns carrier-counts base-offset]
  (let [offs    (reductions (fn [acc c] (+ acc c))
                            (long base-offset)
                            (drop-last (seq carrier-counts)))
        indexed (map vector marshal-fns offs (range))]
    (reduce (fn [next-k [marshal-fn off idx]]
              (fn marshal-chain-link [^Arena arena args ^objects carriers]
                (marshal-fn arena (nth args idx) carriers off)
                (next-k arena args carriers)))
            (fn [_arena _args _carriers] nil)
            (reverse indexed))))

(defn- enum-param-coerce
  "Build a per-call coercion fn for one enum param: keyword to backing
  scalar carrier. Throws `:clj-zig/unknown-enum-member` for a non-member.
  The enum index map and the backing scalar's carrier coercion are both
  captured at bind time so the per-call body is a single map lookup
  followed by a specialized `.longValue`/`unchecked-*` path, with no
  atom dereference and no per-call `case category`/`case bits` dispatch."
  [layout]
  (let [backing (:backing layout)
        coerce  (scalar-param-coerce {:type backing})
        idx     (enum-index layout)
        kw->val (:kw->value idx)]
    (fn enum-coerce [arg]
      (let [value (get kw->val arg)]
        (when (nil? value)
          (throw-unknown-enum-member layout arg))
        (coerce value)))))

(defn- enum-return-coerce
  "Build a per-call return coercion fn for an enum return: backing scalar
  to keyword (or the raw integer when no member carries it, total per
  ADR 20). The enum index map is captured at bind time so the per-call
  body is a single map lookup with no atom dereference."
  [layout]
  (let [val->kw (:value->kw (enum-index layout))]
    (fn enum-ret [result]
      (let [v (long result)]
        (or (get val->kw v) v)))))

(defn- handle-param-coerce
  "Build a per-call coercion fn for one [:handle Type] param: validate the
  arg is a Handle of the expected type and return its native segment for
  aset into the carrier array. Throws `:clj-zig/handle-type-mismatch`
  for a wrong-typed handle."
  [expected]
  (fn handle-coerce [arg]
    (when-not (and (instance? Handle arg) (= expected (.type ^Handle arg)))
      (throw-handle-mismatch expected arg))
    (.segment ^Handle arg)))

(defn- handle-return-coerce
  "Build a per-call return coercion fn for a [:handle Type] return:
  wrap the returned MemorySegment in a Handle of the expected type,
  unless the address is zero (nil for a null handle). Mirrors the
  `:handle` branch of `from-return` with the expected type name
  captured at bind time so the per-call body is a single address
  check and Handle construction with no `cond` dispatch."
  [expected-name]
  (fn handle-ret [^MemorySegment v]
    (when-not (zero? (.address v))
      (Handle. expected-name v))))

(defn- enum-aware-coercions
  "Build `[param-coercions return-coercion]` for an enum- or handle-aware
  signature: one coercion fn per param (scalar uses the pre-bound
  `scalar-param-coerce`, enum uses the keyword-to-backing lookup, handle
  validates the type and returns its segment) and one return fn. The
  return fn is `enum-return-coerce` for an enum, `scalar-return-coerce`
  for a scalar (both with the per-call dispatch inlined at bind time),
  or `handle-return-coerce` for a handle return (a single address
  check plus Handle construction)."
  [params ret]
  [(vec (for [p params]
          (let [type (:type p)]
            (cond
              (= :scalar (:kind type)) (scalar-param-coerce p)
              (enum-type? type)        (enum-param-coerce (:layout type))
              (= :handle (:kind type)) (handle-param-coerce (-> type :of :name))))))
   (cond
     (= :named (:kind ret))  (enum-return-coerce (:layout ret))
     (= :scalar (:kind ret)) (scalar-return-coerce ret)
     (= :handle (:kind ret)) (handle-return-coerce (-> ret :of :name)))])

(defn- safe-free
  "Invoke the free shim with `args`, swallowing a fault so a failure in the
  body's `__free` cannot mask the primary result or exception. Teardown must
  not throw; the primary error (if any) stays visible. A nil `free-handle`
  (no shim, or a borrowed return) is a no-op, mirroring the arena-close
  discipline in `foreign/join-then-close-arena`. An `InterruptedException`
  re-interrupts the current thread so an interrupt-driven shutdown is not
  lost by the swallow."
  [free-handle args]
  (when free-handle
    (try (.invokeWithArguments ^MethodHandle free-handle (object-array args))
         (catch InterruptedException _
           (.interrupt (Thread/currentThread)))
         (catch Throwable _ nil))))

(def ^:private noop-copy-back!
  "A constant no-op copy-back, installed at bind time when no param can
  produce a copy-back thunk (no mutable slices, pointers, or optionals).
  Lets the general invoker skip the per-call cb-count loop and the
  per-call copy-back! closure allocation."
  (fn noop-copy-back! []))

(defn- param-may-copy-back?
  "True if marshalling this param can produce a non-nil copy-back thunk.
  Const slices, arrays, named structs, handles, strings, and scalars
  never copy back; mutable slices and pointers do; an :optional is
  conservatively treated as may (a pointer-pointed optional can copy
  back, a scalar-pointed one cannot, but the distinction is rare)."
  [param]
  (let [t (:type param)]
    (case (:kind t)
      (:manyptr :ptr) true
      :slice (not (:const? t))
      :optional true
      false)))

;;;; general-path return dispatch

(defn- invoke-eu-struct
  "Run the error-union-over-a-struct downcall. The union combines its
  out-params (errbuf, errlen) with a struct out-pointer (`out`). On success
  (errlen 0) read the struct and free owned buffers in a finally so a read
  fault cannot leak (the owned-record free-in-finally discipline); on
  failure read the error keyword. The error path wrote no struct, so the
  free shim does not run and there is nothing to free. The result rebuilds
  as a record via its map-> factory when the named type is a defrecordz,
  else a plain map.

  Carriers is the thread-local base array of size `(:n-base ctx) + 3`; the
  trailing three slots are filled with errbuf, errlen, and out before the
  invoke, then cleared so the next call on this thread starts clean."
  [{:keys [^MethodHandle spreader ret record-factory free-handle error-buffer-bytes n-base struct-reader struct-out-tl]}
   ^Arena arena ^objects carriers copy-back!]
  (let [desc   (-> ret :of :layout)
        out    ^MemorySegment (if struct-out-tl
                                (.get ^ThreadLocal struct-out-tl)
                                (.allocate arena (long (:size desc)) (long (:align desc))))
        errbuf ^MemorySegment (.allocate arena error-buffer-bytes 1)
        errlen ^MemorySegment (.allocate arena 8 8)
        i0     (int n-base)
        i1     (inc i0)
        i2     (inc i1)]
    (aset carriers i0 errbuf)
    (aset carriers i1 errlen)
    (aset carriers i2 out)
    (.invokeExact spreader ^objects carriers)
    (let [n (.get errlen ValueLayout/JAVA_LONG 0)]
      (if (zero? n)
        (try
          (copy-back!)
          (let [m (struct-reader out)]
            (if record-factory (record-factory m) m))
          (finally
            (safe-free free-handle [out])))
        (do
          (copy-back!)
          (read-error-name errbuf n))))))

(defn- invoke-error-union
  "Run a non-struct error-union downcall. The value type is a scalar
  (coerced with the unsigned policy), `:void` (nil), or a named enum whose
  backing int maps to its member keyword (an unknown int returns the raw
  int, total per ADR 20). On failure read the error keyword.

  Carriers is the thread-local base array of size `base-count + 2`; the
  trailing two slots are filled with errbuf and errlen before the invoke."
  [{:keys [^MethodHandle spreader ret error-buffer-bytes n-base]} ^Arena arena
   ^objects carriers copy-back!]
  (let [errbuf ^MemorySegment (.allocate arena error-buffer-bytes 1)
        errlen ^MemorySegment (.allocate arena 8 8)
        i0     (int n-base)
        i1     (inc i0)]
    (aset carriers i0 errbuf)
    (aset carriers i1 errlen)
    (let [result (.invokeExact spreader ^objects carriers)
          n      (do (copy-back!) (.get errlen ValueLayout/JAVA_LONG 0))]
      (if (zero? n)
        (let [value-t (:of ret)]
          (cond
            (type/void-type? value-t) nil
            (enum-type? value-t)      (enum-value->member (:layout value-t) (long result))
            :else                     (coerce-scalar value-t result)))
        (read-error-name errbuf n)))))

(defn- invoke-owned-record
  "Run an owned or borrowed record downcall. The result writes its fields
  through a caller-allocated wire-struct out-segment; clj-zig reads each
  field, then frees owned memory through the per-field shim. The free runs
  in a finally so a read fault cannot leak any buffer the body allocated.
  A borrowed record has no shim. The result rebuilds as a record via its
  map-> factory when the named type is a defrecordz, else a plain map.

  Carriers is the thread-local base array of size `base-count + 1`; the
  trailing slot is filled with the out-segment before the invoke."
  [{:keys [^MethodHandle spreader ret record-factory free-handle n-base struct-reader struct-out-tl]} ^Arena arena
   ^objects carriers copy-back!]
  (let [desc (-> ret :of :layout)
        out  ^MemorySegment (if struct-out-tl
                              (.get ^ThreadLocal struct-out-tl)
                              (.allocate arena (long (:size desc)) (long (:align desc))))
        i0   (int n-base)]
    (aset carriers i0 out)
    (.invokeExact spreader ^objects carriers)
    (try
      (copy-back!)
      (let [m (struct-reader out)]
        (if record-factory (record-factory m) m))
      (finally
        (safe-free free-handle [out])))))

(defn- invoke-owned-slice
  "Run an owned or borrowed slice / :bytes / :string downcall. The result
  writes its pointer and length to two out-params; clj-zig copies the
  elements out (a :bytes return as one byte[], a :string return decoded as
  UTF-8 with replacement, any other slice as a vector), then frees owned
  memory through the shim. The free runs in a finally so a read fault (a
  wild pointer, or an OOM on a huge length) cannot leak the slice the body
  allocated (ADR 21). copy-back! runs inside the same try: the native call
  already allocated the owned slice, so a copy-back fault must still free. A borrowed return has no shim.

  Carriers is the thread-local base array of size `(:n-base ctx) + 2`; the
  trailing two slots are filled with pout and lout before the invoke."
  [{:keys [^MethodHandle spreader ret free-handle n-base]} ^Arena arena
   ^objects carriers copy-back!]
  (let [pout ^MemorySegment (.allocate arena 8 8)
        lout ^MemorySegment (.allocate arena 8 8)
        i0   (int n-base)
        i1   (inc i0)]
    (aset carriers i0 pout)
    (aset carriers i1 lout)
    (.invokeExact spreader ^objects carriers)
    (let [addr (.get pout ValueLayout/JAVA_LONG 0)
          len  (.get lout ValueLayout/JAVA_LONG 0)]
      (try
        (copy-back!)
        (case (:kind ret)
          :bytes  (read-bytes addr len)
          :string (read-utf8-string addr len)
          (read-slice-values addr len (-> ret :of :of)))
        (finally
          (safe-free free-handle [addr len]))))))

(defn- invoke-struct-return
  "Run a plain struct-return downcall. The result is written through a
  caller-allocated out-segment, then read back into a Clojure map (rebuilt
  as a record via its map-> factory when the named type is a defrecordz).
  An enum return crosses as its backing int and takes the scalar path, not
  this one.

  Carriers is the thread-local base array of size `(:n-base ctx) + 1`; the
  trailing slot is filled with the out-segment before the invoke."
  [{:keys [^MethodHandle spreader ret record-factory n-base struct-reader struct-out-tl]} ^Arena arena
   ^objects carriers copy-back!]
  (let [desc (:layout ret)
        out  ^MemorySegment (if struct-out-tl
                              (.get ^ThreadLocal struct-out-tl)
                              (.allocate arena (long (:size desc)) (long (:align desc))))
        i0   (int n-base)]
    (aset carriers i0 out)
    (.invokeExact spreader ^objects carriers)
    (copy-back!)
    (let [m (struct-reader out)]
      (if record-factory (record-factory m) m))))

(defn- invoke-i128-return
  "Run a 128-bit-integer return downcall. The result is written through a
  caller-allocated 16-byte out-segment (the C `__int128` shape), then read
  back as a BigInteger with the signedness policy of `ret`. The integer
  crosses by pointer on every host -- a 128-bit value passed by value
  follows divergent System V / MSVC register conventions -- so the wrapper
  takes `*const i128` args and writes the return through a `*i128`
  out-param, identical on every host.

  Carriers is the thread-local base array of size `(:n-base ctx) + 1`; the
  trailing slot is filled with the out-segment before the invoke."
  [{:keys [^MethodHandle spreader ret n-base]} ^Arena arena ^objects carriers copy-back!]
  (let [out (.allocate arena i128-layout)
        i0  (int n-base)]
    (aset carriers i0 out)
    (.invokeExact spreader ^objects carriers)
    (copy-back!)
    (i128-segment->bigint ret out)))

(defn- invoke-optional-struct
  "Run an optional-over-struct downcall. The body returns null or a
  c_allocator pointer to the nice struct; the FFM reads the struct through
  the returned address, rebuilds it as a map (or record via the factory),
  and frees in a finally: buffer fields first, then the struct allocation.
  A null return yields nil with no free.

  Carriers is the thread-local base array of size `(:n-base ctx)` (no
  trailing slots; the optional return is the FFM return value)."
  [{:keys [^MethodHandle spreader ret record-factory free-handle struct-reader]} _arena
   ^objects carriers copy-back!]
  ;; base-count names the array length; nothing is appended, and the array
  ;; is already in invoke order.
  (let [result (.invokeExact spreader ^objects carriers)
        addr   (.address ^MemorySegment result)]
    (if (zero? addr)
      (do (copy-back!) nil)
      (let [layout (:layout (:of ret))
            sized  (.reinterpret ^MemorySegment result (long (:size layout)))]
        (try
          (copy-back!)
          (let [m (struct-reader sized)]
            (if record-factory (record-factory m) m))
          (finally
            (safe-free free-handle [addr])))))))

(defn- invoke-scalar
  "Run a plain scalar, enum, or void downcall: invoke, copy mutable args
  back, and read the return with the unsigned policy. The arena backs any
  slice/pointer arguments.

  Carriers is the thread-local base array, exactly the base carriers. A
  128-bit-integer return crosses by pointer and routes to
  `invoke-i128-return`, never this fn; the `arena` arg is threaded only for
  signature uniformity with the other `invoke-*` helpers and is unused here."
  [{:keys [^MethodHandle spreader ret]} _arena ^objects carriers copy-back!]
  (let [result (.invokeExact spreader ^objects carriers)]
    (copy-back!)
    (from-return ret result)))

(defn- make-stream-reducible
  "Return an `IReduceInit` that drives the iteration from `iter-addr`,
  applying the reduction and calling `free-handle` in a finally so a
  fault cannot leak the iterator (ADR 50). The native iterator is freed
  ONLY when the reducible is reduced, so a caller that obtains it and
  discards it without reducing leaks it; reduce it (e.g. with `into`)
  or do not hold the reducible."
  [iter-addr next-handle free-handle ret elem-layout]
  (let [elem-type (:of ret)]
    (reify
      clojure.lang.IReduceInit
      (reduce [_ f init-val]
        (try
          (with-open [arena (Arena/ofConfined)]
            (let [out-seg (.allocate arena ^MemoryLayout elem-layout)]
              (loop [acc init-val]
                (let [has-val (.invokeWithArguments ^MethodHandle next-handle
                                                     (object-array [iter-addr out-seg]))]
                  (if has-val
                    (let [elem (coerce-scalar elem-type (read-scalar out-seg elem-type 0))
                          result (f acc elem)]
                      (if (reduced? result)
                        @result
                        (recur result)))
                    acc)))))
          (finally
            (safe-free free-handle [iter-addr])))))))

(defn- free-shim-handle
  "Bind the `<sym>` downcall (a `__free` or stream `__next`/`__free` shim)
  returning void with `arg-layouts`, the shared shape of every per-bind
  shim. The `__next` handle returns bool and is built inline at its one
  call site."
  [linker lookup sym arg-layouts]
  (.downcallHandle linker (foreign/find-symbol lookup sym)
                   (FunctionDescriptor/ofVoid (into-array MemoryLayout arg-layouts))
                   (into-array Linker$Option [])))

(defn- bind-shim-handles
  "Bind the per-return-shape shim handles and return them as a map:
  `:free-handle` (the `__free` shim releasing owned memory; nil for a
  borrowed return), and for a `:stream` return the iterator's `:next-h`,
  `:free-h`, and `:elem-lay`. The shim symbol names are derived from the
  wrapper's `export-symbol`. `classify` is the return-shape map from
  `classify-return`. Owned record and error-union-over-struct returns
  carry a per-field shim over a wire-struct pointer; a `:string` return
  always owns its bytes."
  [linker lookup export-symbol ret classify]
  (let [{:keys [eu-struct? owned-rec? opt-struct? stream?]} classify
        free-sym     (str export-symbol "__free")
        struct-free? (or eu-struct? (and owned-rec? (= :owned (:kind ret))))
        free-handle  (cond
                       opt-struct?
                       (free-shim-handle linker lookup free-sym [ValueLayout/JAVA_LONG])
                       struct-free?
                       (free-shim-handle linker lookup free-sym [ValueLayout/ADDRESS])
                       (contains? #{:owned :bytes :string} (:kind ret))
                       (free-shim-handle linker lookup free-sym
                                         [ValueLayout/JAVA_LONG ValueLayout/JAVA_LONG])
                       :else nil)]
    {:free-handle free-handle
     :next-h   (when stream?
                 (.downcallHandle linker
                                  (foreign/find-symbol lookup (str export-symbol "__next"))
                                  (FunctionDescriptor/of ValueLayout/JAVA_BOOLEAN
                                    (into-array MemoryLayout [ValueLayout/JAVA_LONG ValueLayout/ADDRESS]))
                                  (into-array Linker$Option [])))
      :free-h   (when stream?
                  (free-shim-handle linker lookup free-sym [ValueLayout/JAVA_LONG]))
      :elem-lay (when stream? (value-layout (:of ret)))}))

(defn- build-slice-setup
  "The slice-aware path's bind-time state, or a map of nils when the
  signature is not slice-aware. Assembled once so `bind-context` binds
  each field without repeating the `when slice-path?` guard."
  [params ret scalar-path? enum-path?]
  (if (and (not scalar-path?) (not enum-path?) (slice-aware? params ret))
    (let [[writers counts] (slice-aware-writers params)]
      {:slice-path?   true
       :slice-writers writers
       :slice-counts  counts
       :slice-chain   (slice-aware-chain writers counts)
       :slice-total   (reduce + counts)})
    {:slice-path?   false
     :slice-writers nil
     :slice-counts  nil
     :slice-chain   nil
     :slice-total   nil}))

(defn- bind-context
  "The per-bind context shared by the scalar hot path and the general
  invoker: the downcall handle, the return-shape classification, the
  free/next stream handles, and the return-context map the general-path
  dispatch threads into each `invoke-*` helper. Built once per bind,
  reused every call; `bind` only chooses the scalar or general invoker
  from it."
  [spec library-path]
  (let [linker foreign/linker
        ;; with-native-access layers the native-access diagnostic on library-lookup's bad-path data.
        lookup (with-native-access #(foreign/library-lookup library-path))
        sym    (foreign/find-symbol lookup (:symbol spec))
        handle ^MethodHandle (.downcallHandle linker sym (descriptor spec)
                                              (into-array Linker$Option []))
        params (:params spec)
        ret    (:ret spec)
        arity  (count params)
        classify     (classify-return ret)
        {:keys [eu-struct? owned-rec? owned-slice? opt-struct? struct-ret? stream?]}
        classify
        ;; Unwrap :of for ownership/error-union/optional; safe because the
        ;; record-factory lookup returns nil for non-named or enum-named values.
        ret-value    (if (contains? #{:owned :borrowed :error-union :optional} (:kind ret))
                       (:of ret)
                       ret)
        ;; A record return names the map-factory that rebuilds it; a plain
        ;; struct return has none and stays a map.
        record-factory (when (and (= :named (:kind ret-value)) (:record (:layout ret-value)))
                         (requiring-resolve (:record (:layout ret-value))))
        shims    (bind-shim-handles linker lookup (:symbol spec) ret classify)
        free-handle (:free-handle shims)
        next-h      (:next-h shims)
        free-h      (:free-h shims)
        elem-lay    (:elem-lay shims)
        ;; Carrier-array sizing for the general invoker's thread-local
        ;; invoke array. The base carriers fill indices `[0, n-base)`;
        ;; the dispatch helper fills any trailing out-seg slots after that.
        ;; (A 128-bit integer return used to prepend a SegmentAllocator at
        ;; index 0 for a by-value struct return; it now crosses through a
        ;; trailing out-pointer like a struct return, so base-offset is 0.)
        scalar-path? (scalar-only? params ret)
        enum-path?   (enum-aware-scalar? params ret)
        {slice-path?   :slice-path?
         slice-writers :slice-writers
         slice-counts  :slice-counts
         slice-chain   :slice-chain
         slice-total   :slice-total}
        (build-slice-setup params ret scalar-path? enum-path?)
        i128-ret?    (i128-type? ret)
        base-offset  0
        n-base       (reduce + (map param-carrier-count params))
        n-trailing   (cond stream? 0
                           eu-struct? 3
                           (= :error-union (:kind ret)) 2
                           owned-slice? 2
                           (or owned-rec? struct-ret? i128-ret?) 1
                           :else 0)
        total        (+ base-offset n-base n-trailing)
        has-mutable-args? (some param-may-copy-back? params)
        ;; Cache the spreader once per bind: `invokeWithArguments`
        ;; re-derives the spreader and its MethodType per call. `asType`
        ;; widens the return to Object so one call site covers all returns.
        spreader-arity (cond
                         (or scalar-path? enum-path?) arity
                         slice-path?                 (int slice-total)
                         :else                       (int total))
        spreader     (let [obj-array-class (class (object-array 0))]
                       (-> (.asSpreader ^MethodHandle handle obj-array-class
                                        ^int spreader-arity)
                           (.asType (MethodType/methodType
                                     Object (into-array Class [obj-array-class])))))
        ;; The struct desc and reader captured at bind time so the invoke
        ;; helpers skip the per-call cache lookup. nil for non-struct
        ;; returns; only struct-reading helpers touch it.
        struct-desc   (cond
                        struct-ret?                       (:layout ret)
                        (or owned-rec? eu-struct? opt-struct?) (-> ret :of :layout)
                        :else                             nil)
        struct-reader (when struct-desc
                        (or (compiled-struct-reader struct-desc)
                            (fn fallback-reader [seg] (read-struct seg struct-desc))))
        ;; A per-thread out-segment for struct-return shapes, allocated
        ;; from the JVM-lifetime global arena (pool-enabled path only);
        ;; the disabled path allocates per call so the arena owns it.
        struct-out-size  (when struct-desc (long (:size struct-desc)))
        struct-out-align (when struct-desc (long (:align struct-desc)))
        struct-out-tl    (when (and pool-enabled struct-out-size)
                           (ThreadLocal/withInitial
                            (reify java.util.function.Supplier
                              (get [_]
                                (.allocate ^Arena global-arena
                                           struct-out-size struct-out-align)))))]
    {:handle handle :spreader spreader :params params :ret ret :arity arity
     :stream? stream? :eu-struct? eu-struct? :owned-rec? owned-rec?
     :owned-slice? owned-slice? :opt-struct? opt-struct? :struct-ret? struct-ret?
     ;; The per-bind return context the general-path dispatch threads into
     ;; each `invoke-*` helper: the bound spreader and the return shape's
     ;; once-computed metadata.
     :invoke-ctx {:spreader spreader :ret ret :record-factory record-factory
                  :free-handle free-handle :error-buffer-bytes error-buffer-bytes
                  :n-base n-base
                  :struct-reader struct-reader
                  :struct-out-tl struct-out-tl}
     :next-h next-h :free-h free-h :elem-lay elem-lay
     :var-sym (symbol (str (:ns spec)) (str (:name spec)))
     ;; The hot path for a scalar-only signature: no per-call arena, and a
     ;; thread-local carrier array reused across calls on the same thread
     ;; (each thread gets its own, so concurrent callers never share one).
     ;; The native call does not retain the array, and one-directional
     ;; interop (ADR 10) means a call cannot re-enter itself on the same
     ;; thread, so reuse is safe.
      :scalar?      scalar-path?
       :enum?        enum-path?
       :slice?       slice-path?
       :slice-writers (when slice-path? slice-writers)
       :slice-counts  (when slice-path? slice-counts)
       :slice-chain   slice-chain
       :scalar-coercions (when scalar-path?
                           [(mapv scalar-param-coerce params)
                            (scalar-return-coerce ret)])
      :carriers-tl  (when (or scalar-path? enum-path?)
                      (ThreadLocal/withInitial
                       (reify java.util.function.Supplier
                         (get [_] (object-array arity)))))
      ;; The slice-aware path's own carrier array, sized for the sum of
      ;; per-param carrier counts (a slice takes two slots).
      :slice-carriers-tl (when slice-path?
                           (ThreadLocal/withInitial
                            (reify java.util.function.Supplier
                              (get [_] (object-array slice-total)))))
      ;; The enum-aware path's per-arg and per-return coercion fns, built
      ;; once at bind time. nil for the scalar and general paths.
      :enum-coercions (when enum-path? (enum-aware-coercions params ret))
      ;; The general invoker's thread-local state: the pre-sized carrier
      ;; array (`total` slots) and a copy-back slot array sized for the
      ;; arity (the worst case is one copy-back per param). Reused across
      ;; calls on the same thread; safe for the same one-directional-interop
      ;; reason the scalar hot path is (ADR 10).
      :gen-carriers-tl (when (and (not scalar-path?) (not enum-path?) (not slice-path?))
                         (ThreadLocal/withInitial
                          (reify java.util.function.Supplier
                            (get [_] (object-array total)))))
      :gen-copybacks-tl (when (and (not scalar-path?) (not enum-path?) (not slice-path?))
                          (ThreadLocal/withInitial
                           (reify java.util.function.Supplier
                             (get [_] (object-array arity)))))
       :gen-base-offset base-offset
       :gen-n-base      n-base
       :gen-marshal-fns (when (and (not scalar-path?) (not enum-path?) (not slice-path?))
                          (mapv marshal-arg-fn params))
       :gen-carrier-counts (when (and (not scalar-path?) (not enum-path?) (not slice-path?))
                             (mapv param-carrier-count params))
       :has-mutable-args? has-mutable-args?
       :i128-ret?       i128-ret?}))

;;;; Thread-local arena pooling

(deftype PoolEntry [^Arena arena
                    ^longs counter])

(def ^:private pool-enabled
  (let [v (System/getProperty "clj-zig.arena-pool")]
    (if (some? v) (Boolean/parseBoolean v) true)))

;; A JVM-lifetime arena for per-thread reusable segments (the struct-out
;; pool below). The segments it allocates are reused across calls on the
;; owning thread; the arena itself is never closed. `ofShared` because
;; the arena is created at ns load (no owning thread) but each thread's
;; TL init allocates its own segment from it; the per-call access is
;; confined to the owning thread by the ThreadLocal. The shared-arena
;; allocation overhead is paid once at TL init.
(def ^:private ^Arena global-arena (Arena/ofShared))

(def ^:private refresh-interval 1024)

(def ^:private ^ThreadLocal tl-arena
  (ThreadLocal/withInitial
   (reify java.util.function.Supplier
     (get [_] (PoolEntry. (Arena/ofConfined) (long-array [0]))))))

(defn- acquire-pooled-arena
  "Return the current thread's pooled confined Arena, bumping the
  per-thread call counter in place. The arena is refreshed (closed and
  replaced) when the counter reaches `refresh-interval`, bounding the
  pool's memory growth."
  ^Arena []
  (let [entry   ^PoolEntry (.get tl-arena)
        counter ^longs (.counter entry)
        n       (long (aget counter 0))]
    (if (>= n refresh-interval)
      (let [old           (.arena entry)
            fresh         (PoolEntry. (Arena/ofConfined) (long-array [0]))
            fresh-counter (.counter fresh)]
        ;; Install the fresh entry before closing the old arena, so a close
        ;; failure cannot orphan the new arena: the next call retries on
        ;; the installed entry, and the close is swallowed teardown. The
        ;; fresh counter starts at 1 (this call), not 0.
        (.set tl-arena fresh)
        (try (.close ^Arena old) (catch Throwable _ nil))
        (aset fresh-counter 0 1)
        (.arena fresh))
      (do
        (aset counter 0 (inc n))
        (.arena entry)))))

(defn- pool-invoker
  "Build the per-call invoker fn for an arena-using signature, given a
  pre-bound `arena-fn` of `[arena args]`. The pool-enabled branch is
  straight-line: check arity, acquire the pooled arena, call the
  arena-fn (no `try/finally`; the pool has no per-call release). The
  pool-disabled branch wraps the arena-fn in `with-open` so each call
  closes its confined arena. The split is resolved at bind time so the
  per-call path matches the runtime config without re-checking."
  [var-sym arity arena-fn]
  (if pool-enabled
    (fn pooled-invoker [& args]
      (check-arity! var-sym arity args)
      (arena-fn (acquire-pooled-arena) args))
    (fn unpooled-invoker [& args]
      (check-arity! var-sym arity args)
      (with-open [arena (Arena/ofConfined)]
        (arena-fn arena args)))))

(defn bind
  "Load `library-path`, look up the spec's symbol, and return a Clojure
  fn that calls it with scalar coercion. The library is held by the
  global arena for the JVM lifetime; redefinition produces a fresh
  content-addressed library rather than reloading.

  A scalar-only signature (every param and the return a plain scalar) takes
  a hot path that opens NO confined arena and does no per-arg marshalling
  bookkeeping; it coerces directly into a thread-local carrier array and
  invokes. Every other signature keeps the general path, whose confined
  arena holds the native copies of slice/pointer/struct args for the call
  (ADR 37/39). With `-Dclj-zig.arena-pool=true` the arena is a pooled
  thread-local one reused across calls, so its
  lifetime is logically call-bounded but physically extended."
  [spec library-path]
  (let [{:keys [spreader ret arity invoke-ctx next-h free-h elem-lay var-sym
                scalar? enum? enum-coercions scalar-coercions carriers-tl
                slice? slice-chain slice-carriers-tl
                stream? eu-struct? owned-rec? owned-slice? opt-struct? struct-ret? i128-ret?
                gen-carriers-tl gen-copybacks-tl gen-base-offset
                gen-marshal-fns gen-carrier-counts
                has-mutable-args?]}
        (bind-context spec library-path)]
    (cond
      scalar?
      (let [[param-coercions return-coerce] scalar-coercions]
        (fn [& args]
          (check-arity! var-sym arity args)
          (let [^objects carriers (.get ^ThreadLocal carriers-tl)]
            (loop [i 0 as args]
              (when (< i (long arity))
                (aset carriers i ((nth param-coercions i) (first as)))
                (recur (inc i) (next as))))
            (return-coerce (.invokeExact ^MethodHandle spreader ^objects carriers)))))

      enum?
      (let [[param-coercions return-coerce] enum-coercions]
        (fn [& args]
          (check-arity! var-sym arity args)
          (let [^objects carriers (.get ^ThreadLocal carriers-tl)]
            (loop [i (long 0) as args]
              (when (< i (long arity))
                (aset carriers i ((nth param-coercions i) (first as)))
                (recur (inc i) (next as))))
            (return-coerce (.invokeExact ^MethodHandle spreader ^objects carriers)))))

      slice?
      (let [return-coerce (cond
                            (= :named (:kind ret))  (enum-return-coerce (:layout ret))
                            (= :scalar (:kind ret)) (scalar-return-coerce ret)
                            :else                   #(from-return ret %))
            arena-fn (fn slice-arena-fn [^Arena arena args]
                       (let [^objects carriers (.get ^ThreadLocal slice-carriers-tl)]
                         (slice-chain arena carriers args)
                         (return-coerce (.invokeExact ^MethodHandle spreader ^objects carriers))))]
        (pool-invoker var-sym arity arena-fn))

       :else
        (let [;; dispatch is specialized to the bind's single return shape.
              dispatch (cond
                        stream?                      (fn stream-dispatch [_arena ^objects carriers copy-back!]
                                                       (let [iter-addr (.invokeExact ^MethodHandle spreader ^objects carriers)]
                                                         (copy-back!)
                                                         (make-stream-reducible iter-addr next-h free-h ret elem-lay)))
                        eu-struct?                   (fn eu-struct-dispatch [_arena ^objects carriers copy-back!]
                                                       (invoke-eu-struct invoke-ctx _arena carriers copy-back!))
                        (= :error-union (:kind ret)) (fn error-union-dispatch [_arena ^objects carriers copy-back!]
                                                       (invoke-error-union invoke-ctx _arena carriers copy-back!))
                        owned-rec?                   (fn owned-rec-dispatch [_arena ^objects carriers copy-back!]
                                                       (invoke-owned-record invoke-ctx _arena carriers copy-back!))
                        owned-slice?                 (fn owned-slice-dispatch [_arena ^objects carriers copy-back!]
                                                       (invoke-owned-slice invoke-ctx _arena carriers copy-back!))
                        opt-struct?                  (fn opt-struct-dispatch [_arena ^objects carriers copy-back!]
                                                       (invoke-optional-struct invoke-ctx _arena carriers copy-back!))
                         struct-ret?                  (fn struct-ret-dispatch [_arena ^objects carriers copy-back!]
                                                        (invoke-struct-return invoke-ctx _arena carriers copy-back!))
                         i128-ret?                    (fn i128-ret-dispatch [_arena ^objects carriers copy-back!]
                                                        (invoke-i128-return invoke-ctx _arena carriers copy-back!))
                         :else                        (fn scalar-dispatch [_arena ^objects carriers copy-back!]
                                                        (invoke-scalar invoke-ctx _arena carriers copy-back!)))
              arena-fn (if has-mutable-args?
                        (fn general-arena-fn-mutable [^Arena arena args]
                          (let [^objects carriers (.get ^ThreadLocal gen-carriers-tl)
                                base-offset (long gen-base-offset)
                                ^objects copybacks (.get ^ThreadLocal gen-copybacks-tl)
                                cb-count (loop [i (long 0) off base-offset cb (long 0)]
                                           (if (>= i (long arity))
                                             cb
                                             (let [copy-back ((nth gen-marshal-fns i)
                                                               arena (nth args i) carriers off)]
                                               (when copy-back
                                                 (aset copybacks cb copy-back))
                                               (recur (inc i)
                                                      (+ off (long (nth gen-carrier-counts i)))
                                                      (if copy-back (inc cb) cb)))))]
                            (dispatch arena carriers
                                      (fn []
                                        (loop [i (long 0)]
                                          (when (< i cb-count)
                                            ((aget copybacks i))
                                            (recur (inc i))))))))
                        (let [chain (build-marshal-chain gen-marshal-fns gen-carrier-counts gen-base-offset)]
                          (fn general-arena-fn-immutable [^Arena arena args]
                            (let [^objects carriers (.get ^ThreadLocal gen-carriers-tl)]
                              (chain arena args carriers)
                              (dispatch arena carriers noop-copy-back!)))))]
         (pool-invoker var-sym arity arena-fn)))))

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
