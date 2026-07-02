(ns clj-zig.zigtest-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-zig.zigtest :refer [deftestz]]))

(deftestz passing-zig-test
  "A trivial Zig test that always passes."
  "test \"one plus one\" {
      try @import(\"std\").testing.expect(1 + 1 == 2);
  }")

(deftestz another-passing-test
  "test basic arithmetic"
  "const std = @import(\"std\");
  test \"addition\" {
      try std.testing.expect(2 + 2 == 4);
  }
  test \"multiplication\" {
      try std.testing.expect(3 * 4 == 12);
  }")

(deftest zig-test-failure-reports-output
  (testing "a failing Zig test produces a clear failure message"
    (let [body "test \"always fails\" {
        try @import(\"std\").testing.expect(false);
    }"
          result (clj-zig.zigtest/run-zig-test body)]
      (is (not (:pass result)))
      (is (string? (:output result))))))
