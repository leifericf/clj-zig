(ns clj-zig.infer
  "Read a Zig `pub fn` prototype into data so a signatureless `defnz` can
  infer its boundary contract from the co-located source. `prototype`
  parses only the declaration, never the body: the parameter bindings and
  their Zig type strings, and the return type string. Mapping those strings
  to boundary type forms is a separate step. Pure."
  (:require [clojure.string :as str]))

(defn- top-level-split
  "Split `s` on `sep` characters that sit at bracket depth zero, so a comma
  inside `[`, `(`, or `{` does not split a parameter."
  [s sep]
  (loop [chars (seq s), depth 0, cur [], acc []]
    (if-let [c (first chars)]
      (cond
        (#{\[ \( \{} c)                    (recur (rest chars) (inc depth) (conj cur c) acc)
        (#{\] \) \}} c)                    (recur (rest chars) (dec depth) (conj cur c) acc)
        (and (= c sep) (zero? depth))      (recur (rest chars) depth [] (conj acc (apply str cur)))
        :else                              (recur (rest chars) depth (conj cur c) acc))
      (conj acc (apply str cur)))))

(defn- parse-param
  "A `name: type` parameter into `{:binding name :zig-type type}`, splitting
  on the binding's colon."
  [p]
  (let [idx (.indexOf p ":")]
    {:binding  (str/trim (subs p 0 idx))
     :zig-type (str/trim (subs p (inc idx)))}))

(defn- match-paren
  "The index of the `)` that closes the `(` at `open` in `s`, or nil."
  [s open]
  (loop [i (inc open), depth 1]
    (when (< i (count s))
      (case (.charAt ^String s i)
        \( (recur (inc i) (inc depth))
        \) (if (= depth 1) i (recur (inc i) (dec depth)))
        (recur (inc i) depth)))))

(defn prototype
  "The prototype of `pub fn <fn-name>` in `zig-text`, or nil when the file
  declares no such function. Returns `{:name <fn-name> :params [{:binding
  :zig-type}...] :ret <return-type-string>}`, with type strings left
  verbatim for a later mapping step. Parses the declaration only."
  [zig-text fn-name]
  (let [m (re-matcher (re-pattern (str "(?s)\\b(?:pub\\s+)?fn\\s+"
                                       (java.util.regex.Pattern/quote fn-name)
                                       "\\s*\\("))
                      zig-text)]
    (when (.find m)
      (let [open  (dec (.end m))
            close (match-paren zig-text open)]
        (when close
          (let [params-str (subs zig-text (inc open) close)
                after      (subs zig-text (inc close))
                ret        (some-> (re-find #"(?s)^(.*?)\{" after) second str/trim)
                params     (->> (top-level-split params-str \,)
                                (map str/trim)
                                (remove str/blank?)
                                (mapv parse-param))]
            {:name fn-name :params params :ret ret}))))))

;; --- Mapping Zig type strings to boundary type forms --------------------

(def ^:private scalar-types
  #{:i8 :i16 :i32 :i64 :i128 :u8 :u16 :u32 :u64 :u128 :isize :usize
    :f16 :f32 :f64 :f80 :f128 :bool :void})

(defn- strip-const
  "Split a leading `const ` qualifier off a pointee type, returning
  `[const? rest]`."
  [s]
  (if (str/starts-with? s "const ")
    [true (str/trim (subs s 6))]
    [false s]))

(defn- top-level-index
  "The index of the first `ch` in `s` at bracket depth zero, or nil."
  [s ch]
  (loop [i 0, depth 0]
    (when (< i (count s))
      (let [c (.charAt ^String s i)]
        (cond
          (#{\[ \( \{} c)            (recur (inc i) (inc depth))
          (#{\] \) \}} c)            (recur (inc i) (dec depth))
          (and (= c ch) (zero? depth)) i
          :else                       (recur (inc i) depth))))))

(defn- parse-type
  "A Zig type string into a boundary type form: a scalar keyword, a
  compound vector, or a symbol for a named struct or enum."
  [s]
  (let [s (str/trim s)]
    (cond
      (str/starts-with? s "[]")
      (let [[c inner] (strip-const (str/trim (subs s 2)))]
        (if c [:slice :const (parse-type inner)] [:slice (parse-type inner)]))

      (str/starts-with? s "[*]")
      (let [[c inner] (strip-const (str/trim (subs s 3)))]
        (if c [:manyptr :const (parse-type inner)] [:manyptr (parse-type inner)]))

      (re-find #"^\[\d+\]" s)
      (let [[_ n inner] (re-find #"^\[(\d+)\](.*)$" s)]
        [:array (Long/parseLong n) (parse-type inner)])

      (str/starts-with? s "?")
      [:optional (parse-type (subs s 1))]

      (str/starts-with? s "*")
      (let [[c inner] (strip-const (str/trim (subs s 1)))]
        (if c [:ptr :const (parse-type inner)] [:ptr (parse-type inner)]))

      (top-level-index s \!)
      (let [i (top-level-index s \!)]
        [:error-union (symbol (str/trim (subs s 0 i)))
         (parse-type (subs s (inc i)))])

      (scalar-types (keyword s)) (keyword s)

      :else (symbol s))))

(defn zig-type->boundary
  "The boundary type form for a Zig type string. The default reads a
  parameter, whose shape is fully determined by the type. A `:return`
  position yields `::policy-needed` when the type is a slice or a pointer,
  because a returned `[]T` or `*T` carries no ownership or handle policy in
  its type; that policy is a Clojure-side decision and needs an explicit
  signature."
  ([type-str] (zig-type->boundary type-str :param))
  ([type-str position]
   (let [b (parse-type type-str)]
     (if (and (= position :return)
              (vector? b)
              (#{:slice :ptr :manyptr} (first b)))
       ::policy-needed
       b))))

(defn infer-signature
  "The boundary signature vector inferred from `fn-name`'s prototype in
  `zig-text`: each parameter's binding and boundary type, then `:ret` and
  the return type. Throws `:clj-zig/inferred-fn-not-found` when the file
  declares no such `pub fn`, and `:clj-zig/contract-policy-needed` when the
  return is a slice or pointer, whose ownership or handle policy must be
  stated in an explicit signature."
  [zig-text fn-name]
  (let [proto (prototype zig-text fn-name)]
    (when-not proto
      (throw (ex-info (str "No `pub fn " fn-name "` to infer a signature from.")
                      {:level :error :error/code :clj-zig/inferred-fn-not-found
                       :name fn-name})))
    (let [ret (zig-type->boundary (:ret proto) :return)]
      (when (= ret ::policy-needed)
        (throw (ex-info (str "The return of `" fn-name "` is a slice or pointer,"
                             " whose ownership or handle policy is not in the"
                             " Zig type; give an explicit signature.")
                        {:level :error :error/code :clj-zig/contract-policy-needed
                         :name fn-name :ret (:ret proto)})))
      (-> (vec (mapcat (fn [{:keys [binding zig-type]}]
                         [(symbol binding) (zig-type->boundary zig-type)])
                       (:params proto)))
          (conj :ret ret)))))

(comment
  (prototype "pub fn add(x: i64, y: i64) i64 {\n    return x + y;\n}\n" "add")
  ;; => {:name "add" :params [{:binding "x" :zig-type "i64"}
  ;;                          {:binding "y" :zig-type "i64"}] :ret "i64"}
  (zig-type->boundary "[]const f64")          ;; => [:slice :const :f64]
  (zig-type->boundary "[]u8" :return)         ;; => :clj-zig.infer/policy-needed
  (infer-signature "pub fn add(x: i64, y: i64) i64 {}" "add"))
  ;; => [x :i64 y :i64 :ret :i64]
