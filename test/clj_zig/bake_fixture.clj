(ns clj-zig.bake-fixture
  "A small namespace of native functions, loaded by the bake test so its
  established defnz functions can be compiled into a resource tree."
  (:require [clj-zig.core :as z]))

(z/defnz add
  [x :i64
   y :i64
   :ret :i64]
  "return x + y;")

(z/defnz mul
  [x :i64
   y :i64
   :ret :i64]
  "return x * y;")
