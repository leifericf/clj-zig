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
