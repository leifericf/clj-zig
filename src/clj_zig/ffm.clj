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
           (java.lang.invoke MethodHandle)
           (java.lang.reflect Array)
           (java.math BigInteger)
           (java.nio.charset StandardCharsets)))

(def ^:private two-to-64 (.shiftLeft BigInteger/ONE 64))
(def ^:private two-to-64-minus-1 (.subtract two-to-64 BigInteger/ONE))
(def ^:private two-to-128 (.shiftLeft BigInteger/ONE 128))

;; The C `__int128` ABI: a 16-byte value passed and returned by value as a
;; pair of 64-bit halves. FFM represents it as a struct of two JAVA_LONGs;
;; the carrier is a MemorySegment, and a by-value return makes FFM prepend a
;; SegmentAllocator to the downcall handle. A probe confirmed the round-trip.
(def ^:private i128-layout
  (MemoryLayout/structLayout (into-array MemoryLayout [ValueLayout/JAVA_LONG ValueLayout/JAVA_LONG])))

(defn- i128-type?
  "True when a normalized type is one of the 128-bit integer scalars."
  [t]
  (and (= :scalar (:kind t)) (type/i128-type? (:name t))))

(defn- bigint->i128-segment
  "Write `b` as a little-endian two's-complement i128 (two longs) into a
  fresh 16-byte segment allocated from `arena`."
  [^Arena arena ^BigInteger b]
  (let [pattern (.mod (.add b two-to-128) two-to-128)  ; the 128-bit pattern, 0..2^128-1
        lo (.longValue (.mod pattern two-to-64))
        hi (.longValue (.shiftRight pattern 64))
        seg (.allocate arena i128-layout)]
    (.set seg ValueLayout/JAVA_LONG 0 lo)
    (.set seg ValueLayout/JAVA_LONG 8 hi)
    seg))

(defn- i128-segment->bigint
  "Read a little-endian i128 (two longs) from `seg` as a BigInteger,
  applying the unsigned-or-signed policy of `t` (`:u128` keeps the full
  unsigned pattern; `:i128` subtracts 2^128 when the sign bit is set)."
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
    [(if (i128-type? type) i128-layout (value-layout type))]))

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
        struct-ret?   (and (= :named (:kind ret)) (not (enum-type? ret)))]
    {:eu?          eu?
     :eu-struct?   (and eu?
                        (= :named (get-in ret [:of :kind]))
                        (not (enum-type? (:of ret))))
     :owned-rec?   owned-rec?
     :owned-slice? (and (contains? #{:owned :borrowed :bytes :string} (:kind ret))
                        (not owned-rec?))
     :struct-ret?  struct-ret?
     :opt-struct?  (and (= :optional (:kind ret))
                        (= :named (get-in ret [:of :kind]))
                        (not (enum-type? (:of ret))))
     :stream?      (= :stream (:kind ret))}))

(defn- descriptor ^FunctionDescriptor [spec]
  (let [ret (:ret spec)
        {:keys [eu? eu-struct? owned-rec? owned-slice? struct-ret? stream?]}
        (classify-return ret)
        ;; An error-union wrapper carries two trailing out-params, the
        ;; error-name buffer and its length; a struct return carries one
        ;; out-pointer the result is written through; an owned-slice,
        ;; :bytes, or :string return carries two out-params for the slice's
        ;; pointer and length; an owned or borrowed record carries one
        ;; out-pointer to its wire struct. An error-union over a struct
        ;; combines all three: the error-name buffer, its length, AND the
        ;; struct out-pointer. All four export `void`.
        extra        (cond eu-struct?                     [ValueLayout/ADDRESS ValueLayout/ADDRESS ValueLayout/ADDRESS]
                           eu?                            [ValueLayout/ADDRESS ValueLayout/ADDRESS]
                           owned-slice?                   [ValueLayout/ADDRESS ValueLayout/ADDRESS]
                           (or struct-ret? owned-rec?)    [ValueLayout/ADDRESS]
                           :else                          [])
        arg-layouts  (into-array MemoryLayout (concat (mapcat param-layouts (:params spec))
                                                      extra))
        ret-value    (if eu? (:of ret) ret)]
    (cond
      stream?    (FunctionDescriptor/of ValueLayout/JAVA_LONG arg-layouts)
      (or eu-struct? struct-ret? owned-rec? owned-slice? (type/void-type? ret-value))
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

(declare marshal-struct-into! marshal-buffer-field buffer-field-element marshal-array)

(defn- marshal-arg-into!
  "Marshal one boundary `arg` for `param` directly into `carriers` at
  `offset`, returning the param's copy-back thunk or nil. The hot-path
  companion to `marshal-arg`: same per-case semantics, no per-arg
  `{:carriers [...]}` map allocation, and no intermediate carrier vector.
  The caller pre-sized `carriers` for `base-offset + n-base + n-trailing`
  and pre-computed each param's carrier count at bind time so the loop
  that calls this can advance its offset without realizing a layout vec."
  [^Arena arena param arg ^objects carriers offset]
  (let [type (:type param)
        off  (long offset)
        ^objects cs carriers]
    (case (:kind type)
      :string (let [bs (if (string? arg)
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
                nil)
      :slice (let [{:keys [address length copy-back]} (marshal-array arena param arg)]
               (aset cs off address)
               (aset cs (inc off) length)
               copy-back)
      :manyptr (let [{:keys [address copy-back]} (marshal-array arena param arg)]
                 (aset cs off address)
                 copy-back)
      :ptr (do (when (not= 1 (Array/getLength arg))
                 (throw (ex-info "A :ptr argument must be a one-element array."
                                 {:level :error
                                  :error/code :clj-zig/pointer-arity
                                  :expected 1
                                  :actual (Array/getLength arg)})))
               (let [{:keys [address copy-back]} (marshal-array arena param arg)]
                 (aset cs off address)
                 copy-back))
      :array (let [n (-> param :type :length)
                   actual (if (= :named (:kind (:of (:type param))))
                            (count arg)
                            (Array/getLength arg))]
               (when (not= n actual)
                 (throw (ex-info (str "An :array argument must have length " n ".")
                                 {:level :error
                                  :error/code :clj-zig/array-length
                                  :expected n
                                  :actual actual})))
               (aset cs off (:address (marshal-array arena param arg)))
               nil)
      :optional (let [pointed (-> param :type :of)]
                  (cond
                    (nil? arg) (do (aset cs off MemorySegment/NULL) nil)
                    (= :scalar (:kind pointed))
                    (let [layout (value-layout pointed)
                          seg    ^MemorySegment (.allocate arena
                                                           (.byteSize layout)
                                                           (.byteSize layout))]
                      (write-scalar seg pointed 0 (to-carrier {:type pointed} arg))
                      (aset cs off seg)
                      nil)
                    :else (let [{:keys [copy-back]} (marshal-arg-into! arena
                                                                       (update param :type :of)
                                                                       arg cs off)]
                            copy-back)))
      :named (let [layout (-> type :layout)]
               (if (:enum layout)
                 (let [value (enum-member->value layout arg)]
                   (when (nil? value)
                     (throw (ex-info (str arg " is not a member of enum " (:name layout) ".")
                                     {:level :error
                                      :error/code :clj-zig/unknown-enum-member
                                      :type (:name layout) :member arg})))
                   (aset cs off (to-carrier {:type (:backing layout)} value))
                   nil)
                 (do (aset cs off (marshal-struct arena layout arg))
                     nil)))
      :handle (let [expected (-> type :of :name)]
                (when-not (and (instance? Handle arg) (= expected (:type arg)))
                  (throw (ex-info (str "Expected a :handle of " expected
                                       " but got " (pr-str arg) ".")
                                  {:level :error
                                   :error/code :clj-zig/handle-type-mismatch
                                   :expected expected :actual arg})))
                (aset cs off (:segment arg))
                nil)
      (if (i128-type? type)
        (do (aset cs off (bigint->i128-segment arena (biginteger arg)))
            nil)
        (do (aset cs off (to-carrier param arg))
            nil)))))

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
              (throw (ex-info (str (nth arr i) " is not a member of enum " (:name layout) ".")
                              {:level :error
                               :error/code :clj-zig/unknown-enum-member
                               :type (:name layout) :member (nth arr i)})))
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
    (if (i128-type? (:type param))
      ;; A 128-bit integer crosses as a 16-byte segment allocated in the call
      ;; arena; the general path (not the scalar hot path) owns that arena.
      {:carriers [(bigint->i128-segment arena (biginteger arg))]}
      {:carriers [(to-carrier param arg)]})))

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
  written directly; a buffer field copies the caller's value into the call
  arena and writes the `{ptr, len}` pair; a nested struct field recurses
  into a sub-segment at the field's offset."
  [arena descriptor m]
  (let [seg ^MemorySegment (.allocate ^Arena arena (long (:size descriptor))
                                      (long (:align descriptor)))]
    (marshal-struct-into! arena seg descriptor m)
    seg))

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
        (throw (ex-info (str "Struct " (:name descriptor) " is missing field " name ".")
                        {:level :error :error/code :clj-zig/missing-field
                         :type (:name descriptor) :field name})))
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
              seg     (.reinterpret (MemorySegment/ofAddress addr) (* n (.byteSize bl)))
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
              base   (.reinterpret (MemorySegment/ofAddress addr) (* n stride))]
          (mapv #(read-struct (.asSlice base (* (long %) stride) stride) inner)
                (range n)))

        :else
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

(def ^:private enum-index-cache
  ;; Per enum `:values`, the `{kw->value value->kw}` lookup maps, so repeated
  ;; marshal/unmarshal of the same enum is O(1) rather than an O(n) scan per
  ;; element. Keyed by the `:values` vector (structural equality); growth is
  ;; bounded by the number of distinct enum definitions.
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
                                (->Handle (-> ret :of :name) v))
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

(defn- scalar-only?
  "True when every param and the return cross as a plain scalar carrier, so
  the call needs no confined arena. A scalar param coerces straight to its
  carrier with `to-carrier` (no native segment is allocated), and a scalar
  or `:void` return reads back with no out-pointer. Anything that touches
  the arena -- a slice, pointer, array, struct, enum, handle, optional, a
  128-bit integer (a 16-byte segment, and a by-value return prepends a
  SegmentAllocator to the handle), or an error-union/owned/struct return
  -- is excluded and takes the general path. (`:void` normalizes to
  `{:kind :scalar :name :void}`, so the return test covers it.)"
  [params ret]
  (and (every? (fn [p] (and (= :scalar (-> p :type :kind))
                            (not (i128-type? (:type p)))))
               params)
       (= :scalar (:kind ret))
       (not (i128-type? ret))))

(defn- safe-free
  "Invoke the free shim with `args`, swallowing a fault so a failure in the
  body's `__free` cannot mask the primary result or exception. Teardown must
  not throw; the primary error (if any) stays visible. A nil `free-handle`
  (no shim, or a borrowed return) is a no-op, mirroring the arena-close
  discipline in `foreign/join-then-close-arena`."
  [free-handle args]
  (when free-handle
    (try (.invokeWithArguments ^MethodHandle free-handle (object-array args))
         (catch Throwable _ nil))))

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
  fault cannot leak (the owned-record free-in-finally discipline); on
  failure read the error keyword. The error path wrote no struct, so the
  free shim does not run and there is nothing to free. The result rebuilds
  as a record via its map-> factory when the named type is a defrecordz,
  else a plain map.

  Carriers is the thread-local base array of size `(:n-base ctx) + 3`; the
  trailing three slots are filled with errbuf, errlen, and out before the
  invoke, then cleared so the next call on this thread starts clean."
  [{:keys [^MethodHandle handle ret record-factory free-handle error-buffer-bytes n-base]}
   ^Arena arena ^objects carriers copy-back!]
  (let [desc   (-> ret :of :layout)
        out    ^MemorySegment (.allocate arena (long (:size desc)) (long (:align desc)))
        errbuf ^MemorySegment (.allocate arena error-buffer-bytes 1)
        errlen ^MemorySegment (.allocate arena 8 8)
        i0     (int n-base)
        i1     (inc i0)
        i2     (inc i1)]
    (aset carriers i0 errbuf)
    (aset carriers i1 errlen)
    (aset carriers i2 out)
    (.invokeWithArguments handle carriers)
    (let [n (.get errlen ValueLayout/JAVA_LONG 0)]
      (if (zero? n)
        (try
          (copy-back!)
          (let [m (read-struct out desc)]
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
  [{:keys [^MethodHandle handle ret error-buffer-bytes n-base]} ^Arena arena
   ^objects carriers copy-back!]
  (let [errbuf ^MemorySegment (.allocate arena error-buffer-bytes 1)
        errlen ^MemorySegment (.allocate arena 8 8)
        i0     (int n-base)
        i1     (inc i0)]
    (aset carriers i0 errbuf)
    (aset carriers i1 errlen)
    (let [result (.invokeWithArguments handle carriers)
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
  in a finally so a read fault cannot leak any buffer the body allocated
  (mirror of the owned-record free-in-finally discipline). A borrowed record has no shim. The result rebuilds
  as a record via its map-> factory when the named type is a defrecordz,
  else a plain map.

  Carriers is the thread-local base array of size `base-count + 1`; the
  trailing slot is filled with the out-segment before the invoke."
  [{:keys [^MethodHandle handle ret record-factory free-handle n-base]} ^Arena arena
   ^objects carriers copy-back!]
  (let [desc (-> ret :of :layout)
        out  ^MemorySegment (.allocate arena (long (:size desc)) (long (:align desc)))
        i0   (int n-base)]
    (aset carriers i0 out)
    (.invokeWithArguments handle carriers)
    (try
      (copy-back!)
      (let [m (read-struct out desc)]
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
  allocated (ADR 21, mirror of the owned-record free-in-finally discipline). copy-back! runs inside the same
  try: the native call already allocated the owned slice, so a copy-back
  fault must still free. A borrowed return has no shim.

  Carriers is the thread-local base array of size `(:n-base ctx) + 2`; the
  trailing two slots are filled with pout and lout before the invoke."
  [{:keys [^MethodHandle handle ret free-handle n-base]} ^Arena arena
   ^objects carriers copy-back!]
  (let [pout ^MemorySegment (.allocate arena 8 8)
        lout ^MemorySegment (.allocate arena 8 8)
        i0   (int n-base)
        i1   (inc i0)]
    (aset carriers i0 pout)
    (aset carriers i1 lout)
    (.invokeWithArguments handle carriers)
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
  [{:keys [^MethodHandle handle ret record-factory n-base]} ^Arena arena
   ^objects carriers copy-back!]
  (let [desc (:layout ret)
        out  ^MemorySegment (.allocate arena (long (:size desc)) (long (:align desc)))
        i0   (int n-base)]
    (aset carriers i0 out)
    (.invokeWithArguments handle carriers)
    (copy-back!)
    (let [m (read-struct out desc)]
      (if record-factory (record-factory m) m))))

(defn- invoke-optional-struct
  "Run an optional-over-struct downcall. The body returns null or a
  c_allocator pointer to the nice struct; the FFM reads the struct through
  the returned address, rebuilds it as a map (or record via the factory),
  and frees in a finally: buffer fields first, then the struct allocation.
  A null return yields nil with no free.

  Carriers is the thread-local base array of size `(:n-base ctx)` (no
  trailing slots; the optional return is the FFM return value)."
  [{:keys [^MethodHandle handle ret record-factory free-handle]} _arena
   ^objects carriers copy-back!]
  ;; base-count names the array length; nothing is appended, and the array
  ;; is already in invoke order.
  (let [result (.invokeWithArguments handle carriers)
        addr   (.address ^MemorySegment result)]
    (if (zero? addr)
      (do (copy-back!) nil)
      (let [layout (:layout (:of ret))
            sized  (.reinterpret ^MemorySegment result (long (:size layout)))]
        (try
          (copy-back!)
          (let [m (read-struct sized layout)]
            (if record-factory (record-factory m) m))
          (finally
            (safe-free free-handle [addr])))))))

(defn- invoke-scalar
  "Run a plain scalar, enum, or void downcall: invoke, copy mutable args
  back, and read the return with the unsigned policy. The arena backs any
  slice/pointer arguments. A 128-bit-integer return is a by-value struct,
  so FFM prepends a SegmentAllocator to the downcall handle; the arena is
  that allocator, threaded in front of the carriers.

  Carriers is the thread-local base array. For a non-i128 return the array
  is exactly the base carriers; for an i128 return it is sized for one
  leading slot (the arena as SegmentAllocator) plus the base carriers, the
  loop wrote them at indices 1..n-base, and this fn fills index 0 with the
  arena before invoking."
  [{:keys [^MethodHandle handle ret]} ^Arena arena ^objects carriers copy-back!]
  (let [result (if (i128-type? ret)
                 (do (aset carriers 0 arena)
                     (.invokeWithArguments handle carriers))
                 (.invokeWithArguments handle carriers))]
    (copy-back!)
    (from-return ret result)))

(defn- make-stream-reducible
  "Return an `IReduceInit` that drives the iteration from `iter-addr`,
  calling `next-handle` in a loop (writing the element to an out-pointer
  and returning a bool), applying the reduction, and calling `free-handle`
  in a finally so a fault cannot leak the iterator (ADR 50). The native
  iterator is freed ONLY when the reducible is reduced, so a caller that
  obtains it and discards it without reducing leaks it; reduce it (e.g.
  with `into`) or do not hold the reducible."
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

(defn- bind-context
  "The per-bind context shared by the scalar hot path and the general
  invoker: the downcall handle, the return-shape classification, the
  free/next stream handles, and the return-context map the general-path
  dispatch threads into each `invoke-*` helper. Built once per bind,
  reused every call; `bind` only chooses the scalar or general invoker
  from it."
  [spec library-path]
  (let [linker foreign/linker
        ;; Loading a native library is a restricted operation; a JVM that
        ;; denies native access fails here, so translate it into a
        ;; diagnostic that names the flag rather than the raw FFM error.
        ;; `foreign/library-lookup` opens the file and degrades a bad path
        ;; as data; `with-native-access` layers the native-access diagnostic
        ;; on top, so both failure modes read clearly.
        lookup (with-native-access #(foreign/library-lookup library-path))
        sym    (foreign/find-symbol lookup (:symbol spec))
        handle ^MethodHandle (.downcallHandle linker sym (descriptor spec)
                                              (into-array Linker$Option []))
        params (:params spec)
        ret    (:ret spec)
        arity  (count params)
        free-sym (str (:symbol spec) "__free")
        {:keys [eu-struct? owned-rec? owned-slice? opt-struct? struct-ret? stream?]}
        (classify-return ret)
        ;; An owned/borrowed record and an error-union over a struct both
        ;; wrap the value's named type under :of; everything else (a plain
        ;; struct return, an enum, a scalar, a scalar/void/enum error-union)
        ;; names the value type directly on ret. Unwrapping consistently is
        ;; safe: the record-factory lookup below returns nil for any
        ;; non-named or enum-named value.
        ret-value    (if (contains? #{:owned :borrowed :error-union :optional} (:kind ret))
                       (:of ret)
                       ret)
        ;; A record return names the map-factory that rebuilds it; a plain
        ;; struct return has none and stays a map.
        record-factory (when (and (= :named (:kind ret-value)) (:record (:layout ret-value)))
                         (requiring-resolve (:record (:layout ret-value))))
        ;; An owned slice (or :bytes buffer, or :string) return carries a
        ;; free shim taking the slice's pointer and length; an owned record
        ;; and an error-union over a struct both carry a per-field free shim
        ;; taking a pointer to the wire struct (the eu-struct shim runs on
        ;; the success path only); a borrowed return frees nothing. A
        ;; :string return always owns its bytes (allocated by the body,
        ;; decoded on read).
        struct-free? (or eu-struct? (and owned-rec? (= :owned (:kind ret))))
        free-handle  (cond
                       opt-struct?
                       (free-shim-handle linker lookup free-sym [ValueLayout/JAVA_LONG])
                       struct-free?
                       (free-shim-handle linker lookup free-sym [ValueLayout/ADDRESS])
                       (contains? #{:owned :bytes :string} (:kind ret))
                       (free-shim-handle linker lookup free-sym
                                         [ValueLayout/JAVA_LONG ValueLayout/JAVA_LONG])
                       :else nil)
        next-h       (when stream?
                       (.downcallHandle linker
                                        (foreign/find-symbol lookup (str (:symbol spec) "__next"))
                                        (FunctionDescriptor/of ValueLayout/JAVA_BOOLEAN
                                          (into-array MemoryLayout [ValueLayout/JAVA_LONG ValueLayout/ADDRESS]))
                                        (into-array Linker$Option [])))
        free-h       (when stream?
                       (free-shim-handle linker lookup free-sym [ValueLayout/JAVA_LONG]))
        elem-lay     (when stream? (value-layout (:of ret)))
        ;; Carrier-array sizing for the general invoker's thread-local
        ;; invoke array. The base carriers fill indices `[base-offset,
        ;; base-offset+n-base)`; the dispatch helper fills any trailing
        ;; out-seg slots after that. base-offset is 1 only for an i128
        ;; return, where FFM prepends the arena as SegmentAllocator.
        scalar-path? (scalar-only? params ret)
        i128-ret?    (i128-type? ret)
        base-offset  (if i128-ret? 1 0)
        n-base       (reduce + (map param-carrier-count params))
        n-trailing   (cond stream? 0
                           eu-struct? 3
                           (= :error-union (:kind ret)) 2
                           owned-slice? 2
                           (or owned-rec? struct-ret?) 1
                           :else 0)
        total        (+ base-offset n-base n-trailing)]
    {:handle handle :params params :ret ret :arity arity
     :stream? stream? :eu-struct? eu-struct? :owned-rec? owned-rec?
     :owned-slice? owned-slice? :opt-struct? opt-struct? :struct-ret? struct-ret?
     ;; The per-bind return context the general-path dispatch threads into
     ;; each `invoke-*` helper: the bound handle and the return shape's
     ;; once-computed metadata.
     :invoke-ctx {:handle handle :ret ret :record-factory record-factory
                  :free-handle free-handle :error-buffer-bytes error-buffer-bytes
                  :n-base n-base}
     :next-h next-h :free-h free-h :elem-lay elem-lay
     :var-sym (symbol (str (:ns spec)) (str (:name spec)))
     ;; The hot path for a scalar-only signature: no per-call arena, and a
     ;; thread-local carrier array reused across calls on the same thread
     ;; (each thread gets its own, so concurrent callers never share one).
     ;; The native call does not retain the array, and one-directional
     ;; interop (ADR 10) means a call cannot re-enter itself on the same
     ;; thread, so reuse is safe.
     :scalar?      scalar-path?
     :carriers-tl  (when scalar-path?
                     (ThreadLocal/withInitial
                      (reify java.util.function.Supplier
                        (get [_] (object-array arity)))))
     ;; The general invoker's thread-local state: the pre-sized carrier
     ;; array (`total` slots) and a copy-back slot array sized for the
     ;; arity (the worst case is one copy-back per param). Reused across
     ;; calls on the same thread; safe for the same one-directional-interop
     ;; reason the scalar hot path is (ADR 10).
     :gen-carriers-tl (when-not scalar-path?
                        (ThreadLocal/withInitial
                         (reify java.util.function.Supplier
                           (get [_] (object-array total)))))
     :gen-copybacks-tl (when-not scalar-path?
                         (ThreadLocal/withInitial
                          (reify java.util.function.Supplier
                            (get [_] (object-array arity)))))
     :gen-base-offset base-offset
     :gen-n-base      n-base
     :i128-ret?       i128-ret?}))

;; --- Thread-local arena pooling for the call path ------------------------
;; With -Dclj-zig.arena-pool=true, a thread-local confined arena is reused
;; across calls instead of creating and closing one per call. The arena is
;; refreshed (closed and replaced) every refresh-interval calls to bound its
;; growth. Disabled by default; enable for latency-sensitive workloads where
;; the per-call arena overhead dominates.

(def ^:private pool-enabled
  (Boolean/getBoolean "clj-zig.arena-pool"))

(def ^:private refresh-interval 1024)

(def ^:private ^ThreadLocal tl-arena
  (ThreadLocal/withInitial
   (reify java.util.function.Supplier
     (get [_] {:arena (Arena/ofConfined) :count 0}))))

(defn- refresh-if-needed []
  (let [entry (.get tl-arena)]
    (if (>= (:count entry) refresh-interval)
      (let [old   (:arena entry)
            fresh {:arena (Arena/ofConfined) :count 0}]
        ;; Install the fresh arena before closing the old one, so a close
        ;; failure cannot orphan the new arena: the next call retries on the
        ;; installed entry, and the close is swallowed teardown.
        (.set tl-arena fresh)
        (try (.close ^Arena old) (catch Throwable _ nil))
        fresh)
      entry)))

(defn- with-pooled-arena
  "Run `f` with an Arena. When pooling is enabled, reuses a thread-local
  confined arena, incrementing its call counter (the arena is refreshed
  every `refresh-interval` calls to bound memory growth). When disabled,
  allocates a fresh confined arena per call (the default, matching the
  existing `with-open` pattern)."
  [f]
  (if pool-enabled
    (let [entry (refresh-if-needed)
          arena (:arena entry)]
      (.set tl-arena (assoc entry :count (inc (:count entry))))
      (f arena))
    (with-open [arena (Arena/ofConfined)]
      (f arena))))

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
  the call (ADR 37/39). With `-Dclj-zig.arena-pool=true` the arena is a
  pooled thread-local one reused across calls (`with-pooled-arena`), so its
  lifetime is logically call-bounded but physically extended.

  The general path fills a pre-sized thread-local carrier array through
  `marshal-arg-into!` and dispatches the return through `invoke-*` helpers
  that fill the trailing out-seg slots in place. The choreography avoids
  the per-call `mapv` of marshalled maps, the `mapcat :carriers` lazy seq,
  the `copy-back!` closure, and the `concat`+`object-array` alloc each
  helper used to do; the per-call allocation surface is now the arena, the
  out-segments themselves, and any owned/buffer resource the body allocates."
  [spec library-path]
  (let [{:keys [handle params ret arity invoke-ctx next-h free-h elem-lay var-sym
                scalar? carriers-tl stream? eu-struct? owned-rec? owned-slice?
                opt-struct? struct-ret?
                gen-carriers-tl gen-copybacks-tl gen-base-offset gen-n-base]}
        (bind-context spec library-path)]
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
        (with-pooled-arena
         (fn [^Arena arena]
           (let [^objects carriers (.get ^ThreadLocal gen-carriers-tl)
                 ^objects copybacks (.get ^ThreadLocal gen-copybacks-tl)
                 base-offset (long gen-base-offset)]
             ;; Fill the base carriers, collecting copy-back thunks into
             ;; `copybacks`; the helper reads `cb-count` to know how many
             ;; to run. The loop advances the write offset by each param's
             ;; carrier count without realizing a per-arg map or vector.
             (let [cb-count (loop [i (long 0) off base-offset cb (long 0)]
                              (if (>= i (long arity))
                                cb
                                (let [param (nth params i)
                                      arg   (nth args i)
                                      copy-back (marshal-arg-into! arena param arg carriers off)]
                                  (when copy-back
                                    (aset copybacks cb copy-back))
                                  (recur (inc i)
                                         (+ off (param-carrier-count param))
                                         (if copy-back (inc cb) cb)))))]
               ;; The copy-back thunk runs each collected thunk once. Built
               ;; per call (no closed-over marshalled vec), but its body is
               ;; a tight loop over the pre-sized array.
               (let [copy-back! (fn []
                                  (loop [i (long 0)]
                                    (when (< i cb-count)
                                      ((aget copybacks i))
                                      (recur (inc i)))))]
                 (cond
                   stream?                      (let [iter-addr (.invokeWithArguments ^MethodHandle handle carriers)]
                                                  (copy-back!)
                                                  (make-stream-reducible iter-addr next-h free-h ret elem-lay))
                   eu-struct?                   (invoke-eu-struct   invoke-ctx arena carriers copy-back!)
                   (= :error-union (:kind ret)) (invoke-error-union invoke-ctx arena carriers copy-back!)
                   owned-rec?                   (invoke-owned-record invoke-ctx arena carriers copy-back!)
                   owned-slice?                 (invoke-owned-slice  invoke-ctx arena carriers copy-back!)
                   opt-struct?                  (invoke-optional-struct invoke-ctx arena carriers copy-back!)
                   struct-ret?                  (invoke-struct-return invoke-ctx arena carriers copy-back!)
                   :else                        (invoke-scalar       invoke-ctx arena carriers copy-back!)))))))))))

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
