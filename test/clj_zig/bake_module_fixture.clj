(ns clj-zig.bake-module-fixture
  "A native function that imports an external Zig module, loaded by the
  bake test so a module-dependent defnz can be baked into a resource tree.
  The module path is absolute so the compile, which runs in a throwaway
  build directory, resolves it (ADR 34)."
  (:require [clojure.java.io :as io]
            [clj-zig.core :as z]))

(z/zig-deps
 {:zig/modules
  {"answers" {:path (.getAbsolutePath
                     (io/file "test/clj_zig/module_fixture/root.zig"))}}})

(z/defnz ask
  [:ret :i32]
  "const m = @import(\"answers\");\n    return m.answer();")
