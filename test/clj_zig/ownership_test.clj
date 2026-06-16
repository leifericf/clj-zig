(ns clj-zig.ownership-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig :as zig]
            [clj-zig.core :refer [defnz]]))

(defn- bytes-of [^String s] (.getBytes s "UTF-8"))

;; --- Owned returns ------------------------------------------------------

(defnz shout
  [s [:slice :const :u8]
   :ret [:owned [:slice :u8]]]
  "const out = std.heap.c_allocator.alloc(u8, s.len) catch @panic(\"oom\");
   for (s, 0..) |c, i| out[i] = std.ascii.toUpper(c);
   return out;")

(defnz doubled
  [xs [:slice :const :f64]
   :ret [:owned [:slice :f64]]]
  "const out = std.heap.c_allocator.alloc(f64, xs.len) catch @panic(\"oom\");
   for (xs, 0..) |v, i| out[i] = v * 2.0;
   return out;")

(deftest an-owned-slice-return-is-a-copied-vector
  (testing "an owned byte slice comes back as a vector of values"
    (is (= [72 69 76 76 79] (shout (byte-array (bytes-of "hello"))))))
  (testing "an owned slice of another scalar copies element values"
    (is (= [2.0 4.0 6.0] (doubled (double-array [1.0 2.0 3.0]))))))

;; --- Borrowed returns ---------------------------------------------------

(defnz rest-of
  [s [:slice :const :u8]
   :ret [:borrowed [:slice :const :u8]]]
  "return s[1..];")

(deftest a-borrowed-slice-return-is-a-copied-view
  (testing "a borrowed view comes back as a vector, leaving Zig's memory alone"
    (is (= [101 108 108 111] (rest-of (byte-array (bytes-of "hello"))))))
  (testing "an empty borrowed view is an empty vector"
    (is (= [] (rest-of (byte-array (bytes-of "x")))))))

;; --- Generated form -----------------------------------------------------

(deftest an-owned-return-emits-a-free-shim
  (let [src (zig/generated-source #'shout)]
    (is (str/includes? src "const std = @import(\"std\");"))
    (is (str/includes? src "__free")))
  (testing "a borrowed return frees nothing, so it emits no shim"
    (is (not (str/includes? (zig/generated-source #'rest-of) "__free")))))

;; --- Rejections ---------------------------------------------------------

(deftest ownership-wrappers-are-return-only-and-slice-only
  (testing "an ownership wrapper in argument position is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":owned"
          (zig/build-spec '{:ns app :name f
                            :signature [x [:owned [:slice :u8]] :ret :i64]}))))
  (testing "an ownership wrapper over a non-slice is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":owned"
          (zig/build-spec '{:ns app :name f
                            :signature [x :i64 :ret [:owned :i64]]})))))
