(ns zigar.ffm
  "Load a compiled library and bind a native symbol through the finalized
  Foreign Function & Memory API (JEP 454, Java 22+). Imperative shell: it
  turns a boundary spec into a downcall handle and wraps it in a Clojure
  fn that coerces scalars across the boundary.

  Coercion honors the unsigned-return policy (docs/03): a value that fits
  the signed JVM range comes back as a `Long`; a `:u64`/`:usize` value
  beyond it is promoted to `BigInteger`, never a surprise negative. A
  `:void` return is `nil`."
  (:require [clojure.java.io :as io]
            [zigar.type :as type])
  (:import (java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
                              MemoryLayout MemorySegment SymbolLookup ValueLayout)
           (java.lang.invoke MethodHandle)
           (java.math BigInteger)))

(def ^:private two-to-64 (.shiftLeft BigInteger/ONE 64))

(defn- value-layout ^ValueLayout [t]
  (let [{:keys [category bits]} (type/scalar-info (:name t))]
    (case category
      :int   (case bits 8 ValueLayout/JAVA_BYTE 16 ValueLayout/JAVA_SHORT
                        32 ValueLayout/JAVA_INT 64 ValueLayout/JAVA_LONG)
      :float (case bits 32 ValueLayout/JAVA_FLOAT 64 ValueLayout/JAVA_DOUBLE)
      :bool  ValueLayout/JAVA_BOOLEAN)))

(defn- descriptor ^FunctionDescriptor [spec]
  (let [arg-layouts (into-array MemoryLayout (map (comp value-layout :type) (:params spec)))]
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
  content-addressed library rather than reloading (ADR 12)."
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
      (->> (map to-carrier params args)
           (object-array)
           (.invokeWithArguments handle)
           (from-return ret)))))
