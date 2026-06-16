(ns zigar.ffm
  "Load a compiled library and bind a native symbol through the finalized
  Foreign Function & Memory API (Java 22+). Imperative shell: it
  turns a boundary spec into a downcall handle and wraps it in a Clojure
  fn that coerces scalars across the boundary.

  Coercion honors the unsigned-return policy: a value that fits
  the signed JVM range comes back as a `Long`; a `:u64`/`:usize` value
  beyond it is promoted to `BigInteger`, never truncated to a negative. A
  `:void` return is `nil`."
  (:require [clojure.java.io :as io]
            [zigar.type :as type])
  (:import (java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
                              MemoryLayout MemorySegment SymbolLookup ValueLayout)
           (java.lang.invoke MethodHandle)
           (java.lang.reflect Array)
           (java.math BigInteger)))

(def ^:private two-to-64 (.shiftLeft BigInteger/ONE 64))

(declare marshal-struct enum-member->value enum-value->member)

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
  backing scalar; a slice is an address and a `usize` length."
  [{:keys [type]}]
  (case (:kind type)
    :slice                            [ValueLayout/ADDRESS ValueLayout/JAVA_LONG]
    :named                            (if (enum-type? type)
                                        [(value-layout (-> type :layout :backing))]
                                        [ValueLayout/ADDRESS])
    (:ptr :manyptr :array :optional)  [ValueLayout/ADDRESS]
    [(value-layout type)]))

(defn- return-layout ^ValueLayout [ret]
  (cond
    (= :optional (:kind ret))                ValueLayout/ADDRESS
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
        extra       (cond eu?         [ValueLayout/ADDRESS ValueLayout/ADDRESS]
                          struct-ret? [ValueLayout/ADDRESS]
                          :else       [])
        arg-layouts (into-array MemoryLayout (concat (mapcat param-layouts (:params spec))
                                                     extra))
        ret-value   (if eu? (:of ret) ret)]
    (if (or struct-ret? (type/void-type? ret-value))
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
      :float (case bits 32 (float v) 64 (double v))
      :bool  (boolean v))))

(defn- marshal-array
  "Copy a primitive array into a fresh native segment from `arena` and pass
  its address. Yields the segment, its length, and for a mutable pointee a
  thunk that copies the segment back into the array after the call.
  Element copies honor the layout's byte order."
  [arena {:keys [type]} arr]
  (let [elem  (value-layout (:of type))
        bytes (.byteSize elem)
        len   (Array/getLength arr)
        seg   ^MemorySegment (.allocate ^Arena arena (* len bytes) bytes)]
    (MemorySegment/copy arr (int 0) seg elem (long 0) (int len))
    {:address   seg
     :length    len
     :copy-back (when-not (:const? type)
                  (fn [] (MemorySegment/copy seg elem (long 0) arr (int 0) (int len))))}))

(defn- marshal-arg
  "Coerce one boundary argument to its native carriers. Pointers and slices
  pass as a native segment address; a single-item pointer demands a
  one-element array, guarding against a read past the end."
  [arena param arg]
  (case (-> param :type :kind)
    :slice   (let [{:keys [address length copy-back]} (marshal-array arena param arg)]
               {:carriers [address (long length)] :copy-back copy-back})
    :manyptr (let [{:keys [address copy-back]} (marshal-array arena param arg)]
               {:carriers [address] :copy-back copy-back})
    :ptr     (do (when (not= 1 (Array/getLength arg))
                   (throw (ex-info "A :ptr argument must be a one-element array."
                                   {:level :error
                                    :error/code :zigar/pointer-arity
                                    :expected 1
                                    :actual (Array/getLength arg)})))
                 (let [{:keys [address copy-back]} (marshal-array arena param arg)]
                   {:carriers [address] :copy-back copy-back}))
    :array   (let [n (-> param :type :length)]
               (when (not= n (Array/getLength arg))
                 (throw (ex-info (str "An :array argument must have length " n ".")
                                 {:level :error
                                  :error/code :zigar/array-length
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
                                       :error/code :zigar/unknown-enum-member
                                       :type (:name layout) :member arg})))
                    {:carriers [(int value)]})
                  {:carriers [(marshal-struct arena layout arg)]}))
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
               32 (.set seg ValueLayout/JAVA_FLOAT off (float cv))
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
                          {:level :error :error/code :zigar/missing-field
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

(defn bind
  "Load `library-path`, look up the spec's symbol, and return a Clojure
  fn that calls it with scalar coercion. The library is held by the
  global arena for the JVM lifetime; redefinition produces a fresh
  content-addressed library rather than reloading."
  [spec library-path]
  (let [linker (Linker/nativeLinker)
        lookup (SymbolLookup/libraryLookup (.toPath (io/file library-path)) (Arena/global))
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
        var-sym (symbol (str (:ns spec)) (str (:name spec)))]
    (fn [& args]
      (when (not= (count args) arity)
        (throw (ex-info (str "Wrong number of arguments to " var-sym
                             ": expected " arity ", got " (count args))
                        {:level :error
                         :error/code :zigar/arity
                         :var var-sym
                         :expected arity
                         :actual (count args)})))
      ;; A confined arena holds the native copies of any slice arguments
      ;; for exactly the duration of the call. Mutable slices are copied
      ;; back before it closes, keeping the lifetime to the call.
      (with-open [arena (Arena/ofConfined)]
        (let [marshalled    (mapv #(marshal-arg arena %1 %2) params args)
              base-carriers (mapcat :carriers marshalled)
              copy-back!    #(run! (fn [m] (when-let [back (:copy-back m)] (back)))
                                   marshalled)]
          (if (= :error-union (:kind ret))
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
            ;; A struct return is written through a caller-allocated
            ;; out-segment, then read back into a Clojure map. An enum
            ;; return crosses as its backing int and takes the plain path.
            (if (and (= :named (:kind ret)) (not (enum-type? ret)))
              (let [desc (:layout ret)
                    out  ^MemorySegment (.allocate arena (long (:size desc))
                                                   (long (:align desc)))]
                (->> (concat base-carriers [out])
                     (object-array)
                     (.invokeWithArguments handle))
                (copy-back!)
                (let [m (read-struct out desc)]
                  (if record-factory (record-factory m) m)))
              (let [result (->> base-carriers (object-array) (.invokeWithArguments handle))]
                (copy-back!)
                (from-return ret result)))))))))

(comment
  ;; A whole small pipeline: build, compile, bind, call.
  (require '[zigar.spec :as spec] '[zigar.source :as source] '[zigar.compile :as compile])
  (let [s   (spec/build-spec '{:ns app.core :name add :signature [x :i64 y :i64 :ret :i64]})
        dir (str (java.nio.file.Files/createTempDirectory
                  "zigar" (make-array java.nio.file.attribute.FileAttribute 0)))
        lib (compile/compile! {:source (source/generate s "return x + y;")
                               :source-path (str dir "/source.zig")
                               :library-path (str dir "/libadd." (compile/dynamic-library-extension))
                               :ctx {:var 'app.core/add :signature (:signature s)}})
        add (bind s (:library lib))]
    (add 20 22)))  ;; => 42
