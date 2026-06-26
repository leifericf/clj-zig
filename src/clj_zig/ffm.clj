(ns clj-zig.ffm
  "Load a compiled library and bind a native symbol through the finalized
  Foreign Function & Memory API (Java 22+). Imperative shell: it
  turns a boundary spec into a downcall handle and wraps it in a Clojure
  fn that coerces scalars across the boundary.

  Coercion honors the unsigned-return policy: a value that fits
  the signed JVM range comes back as a `Long`; a `:u64`/`:usize` value
  beyond it is promoted to `BigInteger`, never truncated to a negative. A
  `:void` return is `nil`."
  (:require [clojure.java.io :as io]
            [clj-zig.type :as type])
  (:import (java.lang IllegalCallerException)
           (java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
                              MemoryLayout MemorySegment SymbolLookup ValueLayout)
           (java.lang.invoke MethodHandle)
           (java.lang.reflect Array)
           (java.math BigInteger)
           (java.nio.charset StandardCharsets)))

(def ^:private two-to-64 (.shiftLeft BigInteger/ONE 64))

(declare marshal-struct enum-member->value enum-value->member)

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
        ;; out-pointer the result is written through. Both export `void`.
        eu?         (= :error-union (:kind ret))
        struct-ret? (and (= :named (:kind ret)) (not (enum-type? ret)))
        own?        (contains? #{:owned :borrowed :bytes :string} (:kind ret))
        extra       (cond eu?         [ValueLayout/ADDRESS ValueLayout/ADDRESS]
                          own?        [ValueLayout/ADDRESS ValueLayout/ADDRESS]
                          struct-ret? [ValueLayout/ADDRESS]
                          :else       [])
        arg-layouts (into-array MemoryLayout (concat (mapcat param-layouts (:params spec))
                                                     extra))
        ret-value   (if eu? (:of ret) ret)]
    (if (or struct-ret? own? (type/void-type? ret-value))
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

(defn- marshal-array
  "Copy a primitive array into a fresh native segment from `arena` and pass
  its address. Yields the segment, its length, and for a mutable pointee a
  thunk that copies the segment back into the array after the call. Bulk
  copy carries the numeric carriers; `bool` has no bulk array copy, so it
  crosses element by element."
  [arena {:keys [type]} arr]
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
                    (fn [] (MemorySegment/copy seg elem (long 0) arr (int 0) (int len)))))}))

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
                 (MemorySegment/copy bs (int 0) seg ValueLayout/JAVA_BYTE (long 0) (int len)))
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
    :array   (let [n (-> param :type :length)]
               (when (not= n (Array/getLength arg))
                 (throw (ex-info (str "An :array argument must have length " n ".")
                                 {:level :error
                                  :error/code :clj-zig/array-length
                                  :expected n
                                  :actual (Array/getLength arg)})))
               {:carriers [(:address (marshal-array arena param arg))]})
    :optional (if (nil? arg)
                {:carriers [MemorySegment/NULL]}
                (marshal-arg arena (update param :type :of) arg))
    :named    (let [layout (-> param :type :layout)]
                (if (:enum layout)
                  (let [value (enum-member->value layout arg)]
                    (when (nil? value)
                      (throw (ex-info (str arg " is not a member of enum " (:name layout) ".")
                                      {:level :error
                                       :error/code :clj-zig/unknown-enum-member
                                       :type (:name layout) :member arg})))
                    {:carriers [(int value)]})
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
  struct `descriptor`, each at its C-ABI offset."
  [arena descriptor m]
  (let [seg ^MemorySegment (.allocate ^Arena arena (long (:size descriptor))
                                      (long (:align descriptor)))]
    (doseq [{:keys [name type offset]} (:fields descriptor)]
      (let [v (get m (keyword name))]
        (when (nil? v)
          (throw (ex-info (str "Struct " (:name descriptor) " is missing field " name ".")
                          {:level :error :error/code :clj-zig/missing-field
                           :type (:name descriptor) :field name})))
        (write-scalar seg type offset (to-carrier {:type type} v))))
    seg))

(defn- read-struct
  "Read a native struct segment into a Clojure map keyed by field name."
  [^MemorySegment seg descriptor]
  (reduce (fn [acc {:keys [name type offset]}]
            (assoc acc (keyword name)
                   (coerce-scalar type (read-scalar seg type offset))))
          {} (:fields descriptor)))

(defn- fill-array
  "Bulk-copy `n` carrier elements of `layout` from `seg` (at offset 0) into a
  freshly allocated primitive `arr`, returning `arr`. The array's component
  type must match the layout's carrier. One native move replaces a
  per-element `.get` loop, mirroring `read-bytes`."
  [^MemorySegment seg layout ^long n arr]
  (MemorySegment/copy seg layout (long 0) arr (int 0) (int n))
  arr)

(defn- read-slice-values
  "Copy `len` elements of scalar type `elem` from the native address `addr`
  into an immutable Clojure vector. Numeric carriers bulk-copy into a typed
  primitive array (one native move) and are then coerced with the
  unsigned-return policy (ADR 18); `bool` has no bulk array copy in the FFM
  API, so it reads element by element, the same special case `marshal-array`
  makes on the write side. A zero length reads nothing, so the address is
  never dereferenced for an empty slice."
  [addr len elem]
  (if (zero? len)
    []
    (let [n      (long len)
          layout (value-layout elem)
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
          (mapv #(coerce-scalar elem %) arr))))))

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
  "Read the pointee of an `:optional` single-item pointer return: nil when
  the address is null, else the coerced scalar the pointer addresses."
  [ret ^MemorySegment seg]
  (when-not (zero? (.address seg))
    (let [scalar (-> ret :of :of)
          sized  (.reinterpret seg (.byteSize (value-layout scalar)))]
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
    (keyword (String. bytes "UTF-8"))))

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
        lookup (with-native-access
                 #(SymbolLookup/libraryLookup (.toPath (io/file library-path)) (Arena/global)))
        sym    ^MemorySegment (.orElseThrow (.find lookup (:symbol spec)))
        handle ^MethodHandle (.downcallHandle linker sym (descriptor spec)
                                              (into-array Linker$Option []))
        params (:params spec)
        ret    (:ret spec)
        arity  (count params)
        ;; A record return names the map-factory that rebuilds it; a
        ;; plain struct return has none and stays a map.
        record-factory (when (and (= :named (:kind ret)) (:record (:layout ret)))
                         (requiring-resolve (:record (:layout ret))))
        own?    (contains? #{:owned :borrowed :bytes :string} (:kind ret))
        ;; An owned slice (or :bytes buffer, or :string) return carries a
        ;; free shim clj-zig calls once it has copied the elements out; a
        ;; borrowed return frees nothing. A :string return always owns its
        ;; bytes (it is allocated by the body and decoded on read).
        free-handle (when (contains? #{:owned :bytes :string} (:kind ret))
                      (.downcallHandle linker
                                       (.orElseThrow (.find lookup (str (:symbol spec) "__free")))
                                       (FunctionDescriptor/ofVoid
                                        (into-array MemoryLayout [ValueLayout/JAVA_LONG
                                                                  ValueLayout/JAVA_LONG]))
                                       (into-array Linker$Option [])))
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
            (= :error-union (:kind ret))
            (let [errbuf ^MemorySegment (.allocate arena error-buffer-bytes 1)
                  errlen ^MemorySegment (.allocate arena 8 8)
                  result (->> (concat base-carriers [errbuf errlen])
                              (object-array)
                              (.invokeWithArguments handle))
                  n      (do (copy-back!) (.get errlen ValueLayout/JAVA_LONG 0))]
              (if (zero? n)
                (let [value-t (:of ret)]
                  (when-not (type/void-type? value-t) (coerce-scalar value-t result)))
                (read-error-name errbuf n)))

            ;; An owned or borrowed slice return writes its pointer and
            ;; length to two out-params. clj-zig copies the elements out (a
            ;; :bytes return as one byte[], a :string return decoded as UTF-8
            ;; with replacement, any other slice as a vector), then frees
            ;; owned memory through the shim.
            own?
            (let [pout ^MemorySegment (.allocate arena 8 8)
                  lout ^MemorySegment (.allocate arena 8 8)]
              (->> (concat base-carriers [pout lout])
                   (object-array)
                   (.invokeWithArguments handle))
              (copy-back!)
              (let [addr (.get pout ValueLayout/JAVA_LONG 0)
                    len  (.get lout ValueLayout/JAVA_LONG 0)]
                ;; Free owned memory in a finally so a read fault (a wild
                ;; pointer, or an OOM on a huge length) cannot leak the slice
                ;; the body allocated (ADR 21, mirror of c8a822b). A borrowed
                ;; return has no shim. A :string read decodes UTF-8 with the
                ;; JVM replacement action, so invalid bytes never throw here.
                (try
                  (case (:kind ret)
                    :bytes  (read-bytes addr len)
                    :string (read-utf8-string addr len)
                    (read-slice-values addr len (-> ret :of :of)))
                  (finally
                    (when free-handle
                      (.invokeWithArguments free-handle (object-array [addr len])))))))

            ;; A struct return is written through a caller-allocated
            ;; out-segment, then read back into a Clojure map. An enum
            ;; return crosses as its backing int and takes the plain path.
            (and (= :named (:kind ret)) (not (enum-type? ret)))
            (let [desc (:layout ret)
                  out  ^MemorySegment (.allocate arena (long (:size desc))
                                                 (long (:align desc)))]
              (->> (concat base-carriers [out])
                   (object-array)
                   (.invokeWithArguments handle))
              (copy-back!)
              (let [m (read-struct out desc)]
                (if record-factory (record-factory m) m)))

            :else
            (let [result (->> base-carriers (object-array) (.invokeWithArguments handle))]
              (copy-back!)
              (from-return ret result)))))))))

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
