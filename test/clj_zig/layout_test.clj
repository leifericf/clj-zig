(ns clj-zig.layout-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clj-zig.layout :as layout]
            [clj-zig.type :as type]
            [clj-zig.zig :as zig]))

(deftest describes-fields-in-order-with-offsets
  (let [d (layout/describe 'Point '[x :f64 y :f64])]
    (is (= 'Point (:name d)))
    (is (= [{:name 'x :type {:kind :scalar :name :f64} :offset 0}
            {:name 'y :type {:kind :scalar :name :f64} :offset 8}]
           (:fields d)))
    (is (= 16 (:size d)))
    (is (= 8 (:align d)))))

(deftest pads-mixed-fields-to-c-alignment
  (let [d (layout/describe 'Mix '[a :u8 b :i32])]
    (testing "the i32 starts at the next aligned offset"
      (is (= [0 4] (mapv :offset (:fields d)))))
    (testing "the struct takes its widest field's alignment and rounds up"
      (is (= 4 (:align d)))
      (is (= 8 (:size d))))))

(defn- field-target [fields-form]
  (:target (first (:fields (layout/describe 'T fields-form)))))

(deftest lays-out-a-mixed-scalar-buffer-and-string-record
  ;; A record carrying two scalars, a :string, and a [:bytes [:slice :u8]]
  ;; field. Each buffer field expands to two usize words aligned to the word
  ;; width, so the layout is the C-ABI layout of the wire struct (ADR 21
  ;; section 4 specifies (ptr then len), not the nice record.
  (let [d (layout/describe 'RenderResult
                           '[status :i32
                             w      :u32
                             media  :string
                             bytes  [:bytes [:slice :u8]]])]
    (is (= [{:name       'status
             :type       {:kind :scalar :name :i32}
             :offset     0}
            {:name       'w
             :type       {:kind :scalar :name :u32}
             :offset     4}
            {:name       'media
             :type       {:kind :string}
             :offset     8
             :len-offset 16
             :target     :string}
            {:name       'bytes
             :type       {:kind :bytes
                          :of   {:kind   :slice
                                 :const? false
                                 :of     {:kind :scalar :name :u8}}}
             :offset     24
             :len-offset 32
             :target     (keyword "byte[]")}]
           (:fields d)))
    (is (= 40 (:size d)))
    (is (= 8 (:align d)))))

(deftest maps-each-buffer-kind-to-its-marshalled-target
  (testing "each buffer field kind records its Clojure-side target"
    (is (= :string            (field-target '[s :string])))
    (is (= (keyword "byte[]") (field-target '[b [:bytes [:slice :u8]]])))
    (is (= :vector            (field-target '[xs [:slice :i64]])))
    (is (= :vector            (field-target '[xs [:owned [:slice :i64]]])))
    (is (= :vector            (field-target '[xs [:borrowed [:slice :i64]]]))))
  (testing "a scalar field carries no marshalled target"
    (is (nil? (field-target '[n :i64])))))

(deftest buffer-fields-expand-to-a-target-width-word-pair
  ;; A buffer field's two words are usize, which is target-width. The word
  ;; size is derived from the usize scalar, not a hardcoded constant, so a
  ;; future 32-bit target that changed usize's width would recompute the
  ;; offsets and the descriptor's content hash alongside it.
  (let [word  (quot (:bits (type/scalar-info :usize)) 8)
        d     (layout/describe 'S '[s :string])
        field (first (:fields d))]
    (is (= word (- (:len-offset field) (:offset field))))
    (is (= (* 2 word) (:size d)))
    (is (= word (:align d)))))

(defn- rejection-code [fields]
  (try (layout/describe 'Bad fields) nil
       (catch clojure.lang.ExceptionInfo e
         (:error/code (ex-data e)))))

(deftest rejects-unsupported-field-types
  (testing "a nested struct naming an undeclared type is rejected"
    (is (= :clj-zig/unknown-field (rejection-code '[p Point]))))
  (testing "an unbounded pointer field is rejected"
    (is (= :clj-zig/unsupported-field (rejection-code '[p [:ptr :i64]])))
    (is (= :clj-zig/unsupported-field (rejection-code '[p [:manyptr :i64]]))))
  (testing "a carrierless scalar field is rejected"
    (is (= :clj-zig/unsupported-field (rejection-code '[n :u128]))))
  (testing "a slice of a carrierless scalar is rejected"
    (is (= :clj-zig/unsupported-field (rejection-code '[xs [:slice :u128]])))))

(deftest emits-an-extern-struct
  (let [src (zig/render [(layout/zig-struct (layout/describe 'Point '[x :f64 y :f64]))])]
    (is (str/includes? src "const Point = extern struct {"))
    (is (str/includes? src "x: f64,"))
    (is (str/includes? src "y: f64,"))))

(deftest emits-a-wire-extern-struct-expanding-buffer-fields
  (let [src (zig/render
              [(layout/zig-struct
                (layout/describe 'RenderResult
                                 '[status :i32
                                   media  :string
                                   bytes  [:bytes [:slice :u8]]]))])]
    (testing "scalar fields keep their carrier declarations"
      (is (str/includes? src "status: i32,")))
    (testing "each buffer field expands to a usize ptr and len"
      (is (str/includes? src "media_ptr: usize,"))
      (is (str/includes? src "media_len: usize,"))
      (is (str/includes? src "bytes_ptr: usize,"))
      (is (str/includes? src "bytes_len: usize,")))
    (testing "no buffer field name leaks through undivided"
      (is (not (str/includes? src "media:")))
      (is (not (str/includes? src "bytes:"))))))

(defn- enum-error-code [f]
  (try (f) ::no-throw
       (catch clojure.lang.ExceptionInfo e
         (:error/code (ex-data e)))))

(deftest describes-an-enum-with-configurable-backing
  (testing "the default backing is i32 when no options are given"
    (is (= {:kind :scalar :name :i32}
           (:backing (layout/describe-enum 'Tag '[a 0 b 1])))))
  (testing "an explicit backing is stored in the descriptor"
    (is (= {:kind :scalar :name :u8}
           (:backing (layout/describe-enum 'Compact '[a 0 b 1] {:backing :u8})))))
  (testing "zig-enum emits the backing width"
    (is (str/includes? (zig/render [(layout/zig-enum (layout/describe-enum 'Compact '[a 0 b 1] {:backing :u8}))])
                       "enum(u8) {"))))

(deftest rejects-an-invalid-enum-backing
  (testing "a non-integer scalar is not an enum backing"
    (is (= :clj-zig/bad-enum-backing
           (enum-error-code #(layout/describe-enum 'Bad '[a 0] {:backing :f64})))))
  (testing "a carrierless scalar is not an enum backing"
    (is (= :clj-zig/bad-enum-backing
           (enum-error-code #(layout/describe-enum 'Bad '[a 0] {:backing :i128})))))
  (testing "a non-scalar keyword is not an enum backing"
    (is (= :clj-zig/bad-enum-backing
           (enum-error-code #(layout/describe-enum 'Bad '[a 0] {:backing :string}))))))

(deftest rejects-a-member-value-that-does-not-fit-the-backing
  (testing "256 does not fit a u8 backing"
    (is (= :clj-zig/enum-value-overflow
           (enum-error-code #(layout/describe-enum 'Ovf '[a 0 b 256] {:backing :u8})))))
  (testing "the signed range is honored for an i8 backing"
    (is (= :clj-zig/enum-value-overflow
           (enum-error-code #(layout/describe-enum 'Ovf '[a -128 b 127 c 128] {:backing :i8})))))
  (testing "a value within range is accepted"
    (is (= 255 (-> (layout/describe-enum 'Ok '[a 255] {:backing :u8})
                   :values first :value)))))

(def point-layout
  (layout/describe 'Point '[x :f64 y :f64]))

(deftest lays-out-a-nested-struct-field
  (let [rect (layout/describe 'Rect '[origin Point size Point] {'Point point-layout})]
    (testing "each nested struct field takes the inner type's size and alignment"
      (is (= 0 (-> rect :fields first :offset)))
      (is (= 16 (-> rect :fields second :offset)))
      (is (= 32 (:size rect)))
      (is (= 8 (:align rect))))
    (testing "a nested field carries the inner struct's layout"
      (is (= 'Point (-> rect :fields first :type :name)))
      (is (true? (-> rect :fields first :nested))))
    (testing "the wire extern struct embeds the inner type by name"
      (is (str/includes? (zig/render [(layout/zig-struct rect)]) "origin: Point,"))
      (is (str/includes? (zig/render [(layout/zig-struct rect)]) "size: Point,")))))

(deftest lays-out-recursive-nesting
  (testing "a three-level nesting chain computes offsets through the middle struct"
    (let [rect-layout (layout/describe 'Rect '[origin Point size Point] {'Point point-layout})
          scene  (layout/describe 'Scene '[bounds Rect depth :u8]
                                  {'Point point-layout 'Rect rect-layout})]
      ;; bounds (Rect = 32 bytes, align 8) at offset 0; depth (u8) at offset 32.
      (is (= 0 (-> scene :fields first :offset)))
      (is (= 32 (-> scene :fields second :offset)))
      (is (= 40 (:size scene))))))

(deftest accepts-a-nested-field-with-a-buffer-carrying-inner
  (let [buf-layout (layout/describe 'Buf '[media :string] {})]
    (testing "a nested buffer-carrying struct field is accepted as :nested"
      (let [outer (layout/describe 'Outer '[inner Buf] {'Buf buf-layout})]
        (is (= 1 (count (:fields outer))))
        (is (:nested (first (:fields outer))))))))

(deftest rejects-a-nested-field-naming-an-undeclared-type
  (is (= :clj-zig/unknown-field
         (enum-error-code #(layout/describe 'Outer '[inner Missing] {})))))

(deftest rejects-a-128-bit-integer-struct-field
  (testing "an i128 field is not wired into the field marshaller"
    (is (= :clj-zig/unsupported-field
           (enum-error-code #(layout/describe 'Cell '[n :i128]))))
    (is (= :clj-zig/unsupported-field
           (enum-error-code #(layout/describe 'Cell '[n :u128]))))))

(deftest rejects-a-field-name-that-is-not-a-valid-zig-identifier
  (is (= :clj-zig/bad-field-name
         (enum-error-code #(layout/describe 'T '[bad-name :i64]))))
  (is (= :clj-zig/bad-field-name
         (enum-error-code #(layout/describe-enum 'E '[bad-name 0]))))
  (testing "underscores are valid"
    (is (= ::no-throw (enum-error-code #(layout/describe 'T '[good_name :i64]))))))
