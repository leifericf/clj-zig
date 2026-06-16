(ns zigar.ffm
  "Load a compiled library and bind a native symbol through the finalized
  Foreign Function & Memory API (Java 22+). Imperative shell: it
  turns a boundary spec into a downcall handle and wraps it in a Clojure
  fn that coerces scalars across the boundary.

  Coercion honors the unsigned-return policy: a value that fits
  the signed JVM range comes back as a `Long`; a `:u64`/`:usize` value
  beyond it is promoted to `BigInteger`, never a surprise negative. A
  `:void` return is `nil`."
  (:require [clojure.java.io :as io]
            [zigar.type :as type])
  (:import (java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
                              MemoryLayout MemorySegment SymbolLookup ValueLayout)
           (java.lang.invoke MethodHandle)
           (java.lang.reflect Array)
           (java.math BigInteger)))

(def ^:private two-to-64 (.shiftLeft BigInteger/ONE 64))

(defn- value-layout ^ValueLayout [t]
  (let [{:keys [category bits]} (type/scalar-info (:name t))]
    (case category
      :int   (case bits 8 ValueLayout/JAVA_BYTE 16 ValueLayout/JAVA_SHORT
                        32 ValueLayout/JAVA_INT 64 ValueLayout/JAVA_LONG)
      :float (case bits 32 ValueLayout/JAVA_FLOAT 64 ValueLayout/JAVA_DOUBLE)
      :bool  ValueLayout/JAVA_BOOLEAN)))

(defn- param-layouts
  "The native layouts one boundary param crosses as. A scalar is one
  layout; a pointer is an address; a slice is an address and a `usize`
  length."
  [{:keys [type]}]
  (case (:kind type)
    :slice                   [ValueLayout/ADDRESS ValueLayout/JAVA_LONG]
    (:ptr :manyptr :array)   [ValueLayout/ADDRESS]
    [(value-layout type)]))

(defn- descriptor ^FunctionDescriptor [spec]
  (let [arg-layouts (into-array MemoryLayout (mapcat param-layouts (:params spec)))]
    (if (type/void-type? (:ret spec))
      (FunctionDescriptor/ofVoid arg-layouts)
      (FunctionDescriptor/of (value-layout (:ret spec)) arg-layouts))))

(defn- to-carrier
  "Coerce a Clojure value to the param's native carrier. Integers cross
  as their low `bits` two's-complement bits, so an unsigned value rides
  in the signed carrier without truncation."
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
  ride behind a native segment; a single-item pointer demands a one-element
  array, guarding against a read past the end."
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
    {:carriers [(to-carrier param arg)]}))

(defn- from-return
  "Coerce a native return to a Clojure value, applying the unsigned-return
  policy for `:u64`/`:usize`."
  [ret v]
  (if (type/void-type? ret)
    nil
    (let [{:keys [category signed? bits]} (type/scalar-info (:name ret))]
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
                        (if (neg? l) (.add (biginteger l) two-to-64) l))))))))

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
      ;; for exactly the duration of the call: mutable slices are copied
      ;; back before it closes, honoring the call-only lifetime rule.
      (with-open [arena (Arena/ofConfined)]
        (let [marshalled (mapv #(marshal-arg arena %1 %2) params args)
              result     (->> (mapcat :carriers marshalled)
                              (object-array)
                              (.invokeWithArguments handle))]
          (run! (fn [m] (when-let [back (:copy-back m)] (back))) marshalled)
          (from-return ret result))))))

(comment
  ;; A whole small pipeline: build -> compile -> bind -> call.
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
