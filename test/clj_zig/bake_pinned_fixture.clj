(ns clj-zig.bake-pinned-fixture
  "A module-dependent function whose module is pinned (ADR 36) with a local
  checkout supplied by :path. Bake compiles it from the checkout while the
  fingerprint stays the reproducible pinned identity, so a consumer declaring
  the same pinned reference with no :path resolves the baked library."
  (:require [clojure.java.io :as io]
            [clj-zig.core :as z]))

(z/zig-deps
 {:zig/modules
  {"answers" {:git/sha "0000000000000000000000000000000000000000"
              :root    "src/root.zig"
              :path    (.getAbsolutePath
                        (io/file "test/clj_zig/module_fixture/root.zig"))}}})

(z/defnz ask-pinned
  [:ret :i32]
  "const m = @import(\"answers\");\n    return m.answer();")
