(ns zigar.diagnostics
  "Render a structured diagnostic into the human form a Clojure developer
  reads at the REPL. The data is the contract; this only formats it.

  Rendering starts from Clojure: the failing Var and its signature first,
  then the generated source path, then the compiler's own output:

      Could not compile defnz app.core/add

      Signature:
        [x :i64
         y :i64
         :ret :i64]

      Generated Zig:
        .zigar/cache/macos-aarch64/app.core/add-83a1c0/source.zig

      Zig error:
        source.zig:2:14: error: expected expression, found ';'"
  (:require [clojure.string :as str]))

(defn- well-formed-signature? [sig]
  (and (vector? sig)
       (>= (count sig) 2)
       (= :ret (nth sig (- (count sig) 2)))))

(defn- format-signature
  "Lay the signature out one binding/type pair per line, the return last."
  [sig]
  (if (well-formed-signature? sig)
    (let [args  (subvec sig 0 (- (count sig) 2))
          ret   (peek sig)
          lines (concat (map (fn [[b t]] (str (pr-str b) " " (pr-str t)))
                             (partition 2 args))
                        [(str ":ret " (pr-str ret))])]
      (str "  [" (str/join "\n   " lines) "]"))
    (str "  " (pr-str sig))))

(defn- indent [text]
  (->> (str/split-lines (str/trimr text))
       (map #(str "  " %))
       (str/join "\n")))

(defn render
  "Format a diagnostic map as the multi-line string shown to a developer."
  [{:keys [message var signature] :as diagnostic}]
  (let [header (or message
                   (when var (str "Could not compile defnz " var "."))
                   "Zigar diagnostic.")]
    (str/join
     "\n"
     (cond-> [header]
       signature                    (into ["" "Signature:" (format-signature signature)])
       (:zig/source-path diagnostic) (into ["" "Generated Zig:"
                                            (str "  " (:zig/source-path diagnostic))])
       (seq (:zig/stderr diagnostic)) (into ["" "Zig error:"
                                             (indent (:zig/stderr diagnostic))])))))
