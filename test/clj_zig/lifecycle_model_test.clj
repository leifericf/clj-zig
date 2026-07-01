(ns clj-zig.lifecycle-model-test
  "Model-based test of the define and redefine lifecycle. stateful-check
  generates sequences of define, call, redefine-into-failure, recompile,
  and bad-arity operations against a real Var driven through the pipeline,
  and checks the invariants that no single test pins: a redefinition that
  fails to compile keeps the last good binding and records the failed
  attempt, a body already built comes back cached, recompiling preserves
  behavior, and a wrong arity is a clear error."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [stateful-check.core :refer [specification-correct?]]
            [clj-zig :as zig]
            [clj-zig.core :as core]
            [clj-zig.spec :as spec]))

(def ^:private subject-ns (create-ns 'clj-zig.lifecycle-subject))
(def ^:private the-var (intern subject-ns 'f))

(defn- spec0 []
  (spec/build-spec {:ns 'clj-zig.lifecycle-subject :name 'f :signature '[x :i64 :ret :i64]}))

(def ^:private wrap (fn [invoke] (fn [x] (invoke x))))

(defn- define!
  "Define f as x + k and return whether the build was :compiled or :cached."
  [k]
  (core/establish-binding! the-var (spec0) (str "return x + " k ";") {} wrap)
  (get-in (meta the-var) [:clj-zig/info :status]))

(defn- define-fail! []
  (try (core/establish-binding! the-var (spec0) "return x +;" {} wrap) :ok
       (catch clojure.lang.ExceptionInfo _ :failed)))

(defn- call! [n] (@the-var n))

;; Postconditions run after every command has executed, so a command must
;; capture any live state it wants to assert at the moment it runs and
;; return it; the postcondition then checks a pure snapshot.

(def lifecycle-spec
  {:commands
   {:define
    {:args          (fn [_] [(gen/choose 0 4)])
     :command       (fn [k] (define! k))
     :next-state    (fn [s [k] _] (-> s (assoc :k k :defined? true)
                                      (update :seen conj k)))
     :postcondition (fn [prev _ [k] result]
                      ;; A body already built this run must come back cached.
                      (if (contains? (:seen prev) k) (= :cached result) (keyword? result)))}

    :call
    {:requires      (fn [s] (:defined? s))
     :args          (fn [_] [(gen/choose -1000 1000)])
     :command       (fn [n] (call! n))
     :postcondition (fn [_ next [n] result] (= result (+ n (:k next))))}

    :redefine-fail
    {:requires      (fn [s] (:defined? s))
     :command       (fn [] {:result         (define-fail!)
                            :failed-attempt? (some? (:clj-zig/failed-attempt (meta the-var)))
                            :last-good       (call! 7)})
     :postcondition (fn [_ next _ result]
                      (and (= :failed (:result result))
                           (:failed-attempt? result)
                           (= (:last-good result) (+ 7 (:k next)))))}

    :recompile
    {:requires      (fn [s] (:defined? s))
     :command       (fn [] (core/recompile! the-var) (call! 4))
     :postcondition (fn [_ next _ result] (= result (+ 4 (:k next))))}

    :bad-arity
    {:requires      (fn [s] (:defined? s))
     ;; A wrapped Var enforces its arity at the Clojure level; the inner
     ;; invoker's :clj-zig/arity guards the unwrapped path.
     :command       (fn [] (try (@the-var 1 2) :no-throw
                                 (catch clojure.lang.ArityException _ :arity)))
     :postcondition (fn [_ _ _ result] (= :arity result))}}

   :initial-state (fn [] {:k nil :defined? false :seen #{}})})

(deftest define-and-redefine-lifecycle-holds
  (is (specification-correct? lifecycle-spec
                              {:gen {:max-length 6} :run {:num-tests 15}})))

;; --- Module-backed lifecycle (ADR 34) -----------------------------------
;;
;; The same define/redefine invariants, but the body `@import`s an external
;; module whose source is mutated between commands. This pins the boundary a
;; single test cannot reach: editing the module is picked up with no stale
;; binding, an unchanged module is reused (cache hit), and a module that no
;; longer compiles keeps the last good binding and reports a diagnostic
;; attributed to the module, not the wrapper.

(def ^:private module-ns (create-ns 'clj-zig.module-lifecycle-subject))
(def ^:private module-var (intern module-ns 'g))

(def ^:private module-file
  (let [f (java.io.File/createTempFile "clj-zig-modlife" ".zig")]
    (.deleteOnExit f)
    (.getAbsolutePath f)))

;; A monotonic revision so every write strictly changes the module's size,
;; flipping its dir-signature regardless of mtime resolution: an edit always
;; relinks, and only a no-write redefine can be a cache hit.
(def ^:private module-rev (atom 0))

(defn- write-module!
  "Rewrite the module to answer `a`, with a fresh revision comment so its
  content (and size) always changes."
  [a]
  (spit module-file (str "// rev " (swap! module-rev inc) "\n"
                         "pub fn answer() i64 {\n    return " a ";\n}\n")))

(defn- break-module!
  "Rewrite the module so it no longer compiles."
  []
  (spit module-file (str "// rev " (swap! module-rev inc) "\n"
                         "pub fn answer() i64 {\n    return notdefined;\n}\n")))

(core/register-deps! 'clj-zig.module-lifecycle-subject
                     {:zig/modules {"mymod" {:path module-file}}})

(defn- module-spec []
  (spec/build-spec {:ns 'clj-zig.module-lifecycle-subject :name 'g
                    :signature '[x :i64 :ret :i64]}))

(def ^:private module-body
  "const m = @import(\"mymod\");\n    return m.answer() + x;")

(defn- establish-module!
  "(Re)establish g over the current module, returning the build status."
  []
  (core/establish-binding! module-var (module-spec) module-body {} wrap)
  (get-in (meta module-var) [:clj-zig/info :status]))

(defn- break-establish!
  "Establish g over a broken module, returning the failed-attempt's
  diagnostic origin and the still-bound last-good result for x=7."
  []
  (break-module!)
  (let [outcome (try (establish-module!) :ok
                     (catch clojure.lang.ExceptionInfo _ :failed))
        attempt (:clj-zig/failed-attempt (meta module-var))]
    {:outcome   outcome
     :origin    (:zig/origin attempt)
     :module    (:zig/module attempt)
     :last-good (@module-var 7)}))

(def module-lifecycle-spec
  {:commands
   {:edit-module
    {:args          (fn [_] [(gen/choose 0 5)])
     :command       (fn [a] (write-module! a) {:status (establish-module!) :probe (@module-var 3)})
     :next-state    (fn [s [a] _] (assoc s :a a :defined? true :broken? false))
     ;; A changed module always relinks, and the new behavior is live.
     :postcondition (fn [_ next [a] result]
                      (and (= :compiled (:status result))
                           (= (:probe result) (+ 3 a))
                           (= a (:a next))))}

    :call
    {:requires      (fn [s] (:defined? s))
     :args          (fn [_] [(gen/choose -1000 1000)])
     :command       (fn [n] (@module-var n))
     ;; Holds even while broken?: the last good binding answers.
     :postcondition (fn [_ next [n] result] (= result (+ n (:a next))))}

    :redefine-unchanged
    {:requires      (fn [s] (and (:defined? s) (not (:broken? s))))
     :command       (fn [] {:status (establish-module!) :probe (@module-var 5)})
     ;; An unchanged module reuses its compilation: a cache hit, behavior kept.
     :postcondition (fn [_ next _ result]
                      (and (= :cached (:status result))
                           (= (:probe result) (+ 5 (:a next)))))}

    :break-module
    {:requires      (fn [s] (:defined? s))
     :command       (fn [] (break-establish!))
     :next-state    (fn [s _ _] (assoc s :broken? true))
     :postcondition (fn [_ next _ result]
                      (and (= :failed (:outcome result))
                           (= :module (:origin result))
                           (= "mymod" (:module result))
                           (= (:last-good result) (+ 7 (:a next)))))}}

   :initial-state (fn [] {:a nil :defined? false :broken? false})})

(deftest module-lifecycle-holds
  (is (specification-correct? module-lifecycle-spec
                              {:gen {:max-length 5} :run {:num-tests 10}})))

(deftest clean-removes-a-cache-tree-without-disturbing-a-live-binding
  (define! 1)
  (is (= 6 (call! 5)))
  (let [scratch (str (java.nio.file.Files/createTempDirectory
                      "clj-zig-clean" (make-array java.nio.file.attribute.FileAttribute 0))
                     "/cache")]
    (spit (doto (java.io.File. scratch) (-> (.getParentFile) (.mkdirs))) "x")
    (zig/clean! scratch)
    (is (not (.exists (java.io.File. scratch))))
    (is (= 6 (call! 5))))
  (core/recompile! the-var)
  (is (= 6 (call! 5))))

(deftest a-non-ex-info-shell-failure-is-recorded-as-a-failed-attempt
  ;; The keep-last-good contract must hold for ANY failure to (re)establish,
  ;; not only the structured ex-info a compile throws. A raw shell failure
  ;; (an IOException, an IllegalStateException) is recorded as
  ;; :clj-zig/shell-failure and the last good binding stays live.
  (define! 1)
  (is (= 6 (call! 5)))
  (with-redefs [core/establish! (fn [& _]
                                  (throw (IllegalStateException. "shell blew up")))]
    (let [ex (try (core/establish-binding! the-var (spec0) "return x + 2;" {} wrap)
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex)
          "the raw failure is rethrown as a structured diagnostic")
      (is (= :clj-zig/shell-failure (:error/code (ex-data ex))))
      (is (instance? IllegalStateException (.getCause ex)))
      (let [attempt (:clj-zig/failed-attempt (meta the-var))]
        (is (some? attempt) "the failed attempt is recorded for explain")
        (is (= :clj-zig/shell-failure (:error/code attempt))))
      (is (= 6 (call! 5)) "the last good binding is still live"))))
