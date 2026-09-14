(ns harness.ui.test-runner
  "The runtime half of the vitest bridge: vitest asks for the test list, then
  runs one test at a time and gets a result object back.

  Why a bridge at all. The suites in this directory are ClojureScript, mirroring
  ui/src/harness/ui one for one, and cljs.test has its own reporter, its own
  fixtures and its own `is`. Vitest knows none of that. What crosses between them
  is deliberately thin: a list of `{id, ns, name}` in, a `{pass, fail, error}` out.
  Nothing about assertions or reporters leaks across, and vitest never has to
  understand a single line of the suites.

  Why not shadow-cljs's own `:node-test` target, which also runs cljs.test: it
  owns the whole run -- it discovers namespaces, runs them, prints and exits --
  and the point of this change is that VITEST owns the run. A target that decides
  the exit code cannot be a test file inside another runner."
  (:require-macros [harness.ui.test-runner :refer [deftest-index]])
  (:require [cljs.test :as t]
            [clojure.string :as str]
            [harness.ui.approval-test]
            [harness.ui.client-test]
            [harness.ui.frames-test]
            [harness.ui.turn-test]))

(def ^:private suites
  "Every suite, in the order the runner reports them. A suite that is not listed
  here is not run, so this is the one place a new one has to be added -- and it
  is also why each of these namespaces ends with `(deftest-index)`."
  [harness.ui.frames-test/test-index
   harness.ui.client-test/test-index
   harness.ui.turn-test/test-index
   harness.ui.approval-test/test-index])

(def ^:private ordered
  "Every test in suite order, then source order within a suite. Built once from
  the compiled indexes, and kept as a VECTOR: `vals` of a hash map would hand
  vitest the cases in hash order, so the run's output would stop matching the
  order anyone reads the files in."
  (vec (apply concat suites)))

(def ^:private by-id
  (into {} (map (juxt :id identity)) ordered))

;; ------------------------------------------------------------------ reporting

(def ^:private result
  "The running test's counters and failure lines. Reset per test, read at the
  end -- cljs.test reports through a multimethod, so the state has to live
  somewhere the report methods can reach."
  (atom nil))

(defn- failure-line [m]
  (str (t/testing-vars-str m)
       (when-some [msg (:message m)] (str " -- " msg))
       "\n      expected: " (pr-str (:expected m))
       "\n        actual: " (pr-str (:actual m))))

;; A reporter of our own, so cljs.test's printer stays out of it: vitest owns the
;; output format. `::collect` is what distinguishes these methods from the
;; default reporter's -- dispatch is [reporter type], and empty-env takes the
;; reporter as its argument.
(defmethod t/report [::collect :pass] [_] (swap! result update :pass inc))
(defmethod t/report [::collect :fail] [m] (swap! result update :fail conj (failure-line m)))
(defmethod t/report [::collect :error] [m] (swap! result update :error conj (failure-line m)))
(defmethod t/report [::collect :default] [_] nil)

;; -------------------------------------------------------------- the exported API

(defn tests
  "Every test, as JS: [{id, ns, name}]. Called once, at driver load, to build the
  vitest suite.

  `clj->js`, not `into-array`: a Clojure map put into a JS array stays a Clojure
  map, so vitest would read `.id` as undefined and register every case under the
  name \"undefined\" -- eleven tests, all anonymous."
  []
  (clj->js (mapv (fn [{:keys [id ns name]}] {:id id :ns ns :name name}) ordered)))

(defn- run-one
  "V's block, plus one step that resolves PROMISE once the block has drained.

  The appended step is what makes an ASYNC test work: run-block invokes a fn, and
  if it returns an IAsyncTest it continues with (rest fns) inside that test's
  continuation -- so the resolver runs after the async body's `done`, not before
  it. A sync test drains the same way."
  [v promise]
  (t/set-env! (t/empty-env ::collect))
  (t/run-block
   (concat (t/test-vars-block [v])
           [(fn []
              (let [{:keys [pass fail error]} @result]
                (t/clear-env!)
                (promise #js {:pass pass
                              :fail  (into-array fail)
                              :error (into-array error)})))])))

(defn run
  "Run one test by ID and answer a Promise of {pass, fail, error}. An unknown id
  REJECTS rather than resolving empty: a suite that silently reports nothing is
  the failure mode this whole bridge exists to make impossible."
  [id]
  (if-some [{:keys [v]} (get by-id id)]
    (js/Promise. (fn [resolve _] (reset! result {:pass 0 :fail [] :error []})
                   (run-one v resolve)))
    (js/Promise.reject (js/Error. (str "no such test: " id)))))
