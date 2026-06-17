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

(comment
  (prototype "pub fn add(x: i64, y: i64) i64 {\n    return x + y;\n}\n" "add")
  ;; => {:name "add" :params [{:binding "x" :zig-type "i64"}
  ;;                          {:binding "y" :zig-type "i64"}] :ret "i64"}
  (prototype "pub fn dot(a: []const f64, b: []const f64) f64 {}" "dot"))
