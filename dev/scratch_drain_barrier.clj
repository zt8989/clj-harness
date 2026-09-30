;; Scratch: the drain barrier's cost, measured both ways.
;;
;; `harness.kernel.loop/drained!` asks the CONSUMER whether it has dealt with everything the
;; run emitted before that point, and waits on a promise for the answer -- five seconds, and
;; then it gives up. A consumer of `run-chan` that never answers therefore costs the WHOLE
;; deadline, once per barrier, on every run.
;;
;; This measures one scripted turn, no tools, twice: drained by a consumer that answers
;; (`loop/answer-drain!`, the obligation `run-chan`'s docstring states) and by one that does
;; not. Measured 2026-09-30 on this machine:
;;
;;   consumer that answers      mean  139ms   (the 5,001ms wait is gone)
;;   consumer that never does   mean 5192ms   (5,001ms of it the deadline, 165ms of it work)
;;
;; THAT WAIT WAS MOST OF THE BACKEND SUITE. `harness.approval-test` was 224s in a full run
;; and `harness.session-tools-test` 122s -- both almost entirely this, and neither touches a
;; shell, a server or a network.
;;
;; isolate! first, per AGENTS.md: this process must never resolve a store against the
;; developer's real ~/.clj-harness.
(require 'harness.test-runner)
(harness.test-runner/isolate!)

(require '[clojure.core.async :as async]
         '[harness.fake :as fake]
         '[harness.kernel.loop :as loop]
         '[harness.test-support :as support])

(defn- drain!
  "Read CH to its end. ANSWERS? says whether this consumer does the one thing a reader of a
  run channel owes the kernel."
  [ch answers?]
  (loop []
    (when-let [ev (async/<!! ch)]
      (when answers? (loop/answer-drain! ev))
      (when-not (= :run/done (:type ev)) (recur)))))

(defn- once-ms [answers?]
  (let [t0 (System/currentTimeMillis)]
    (drain! (loop/run-chan (fake/scripted [{:content "hello"}]) []
                           {:thread-id (str "perf-" (System/nanoTime))})
            answers?)
    (- (System/currentTimeMillis) t0)))

(defn- report [label answers?]
  (once-ms answers?)                            ;; warm-up: compile, first touch
  (let [times (vec (repeatedly 10 #(once-ms answers?)))
        total (reduce + times)]
    (println (format "%-38s n=10  mean=%7.1fms  max=%6dms"
                     label (double (/ total 10)) (apply max times)))))

(support/with-builtins
 (fn []
   (report "consumer that ANSWERS the barrier" true)
   (report "consumer that never answers" false)))

(println "done")
