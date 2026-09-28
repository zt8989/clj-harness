(ns harness.edge.fork-test
  "The record-level half of 'fork a session': which LINES are cut points, and where a named one
  cuts. The HTTP half lives in `harness.edge.fork-http-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [harness.edge.replay :as replay]))

(defn- fact
  "A fact row as the record holds it: an `event` whose CUSTOM name is KIND."
  [kind payload]
  {:ts 1 :runId "r" :type "event"
   :payload {:type "CUSTOM" :name kind :value payload}})

(defn- message [m]
  {:ts 1 :runId "r" :type "message" :payload m})

(defn- wire
  "A wire frame row (`RUN_FINISHED` and friends): an `event` whose payload IS the frame."
  [run-id type]
  {:ts 1 :runId run-id :type "event" :payload {:type type}})

(deftest the-cut-is-a-step-end-and-nothing-else
  ;; owner, 2026-09-28: a cut is a LINE; a `step/end` is the boundary that keeps one model call
  ;; together with the tools it asked for; and no line named means the last one there is.
  (let [records [(message {:role "user" :content "hi"})
                 (fact "step/end" {})
                 (message {:role "user" :content "more"})
                 (fact "step/end" {})]]
    (is (= {:cut 4 :at 3 :step-seq 3} (replay/fork-cut records))
        "the last step's end, and that line is KEPT")
    (is (= {:cut 2 :at 1 :step-seq 1} (replay/fork-cut records {:step-seq 1}))
        "any step's end may be named")
    (is (nil? (replay/fork-cut records {:step-seq 0}))
        "a line that is not a step's end is refused, not rounded")
    (is (nil? (replay/fork-cut records {:step-seq 2}))
        "…including a step's own first line, which the picker never offers")))

(deftest a-record-with-no-step-behind-it-has-no-fork-point
  (is (nil? (replay/fork-cut [(message {:role "user" :content "hi"})])))
  (is (nil? (replay/fork-cut [])))
  (is (empty? (replay/fork-points [(message {:role "user" :content "hi"})])))
  (is (nil? (replay/fork-cut [(fact "compaction/start" {:compactionId "c1"})
                              (fact "context/compacted" {:compactionId "c1" :summary "s"})
                              (fact "compaction/end" {:compactionId "c1"})]))
      "a compaction is not a cut of its own -- it happens between two steps, and the step's end
       beside it is what a fork names"))

(deftest the-points-are-step-ends-and-each-names-the-compaction-after-it
  ;; IT KNOWS ABOUT COMPACTIONS WITHOUT BEING CUT BY ONE (owner, 2026-09-28): a compaction
  ;; happens BETWEEN two steps, so the step's end just before it IS 'the fork from before the
  ;; compaction' -- and `:compactionId` is what tells a caller which line that is.
  (let [records [(message {:role "user" :content "one"})
                 (fact "step/end" {})
                 (fact "compaction/start" {:compactionId "c1"})
                 (fact "context/compacted" {:compactionId "c1" :summary "s"})
                 (fact "compaction/end" {:compactionId "c1"})
                 (message {:role "user" :content "two"})
                 (fact "step/end" {})
                 (fact "compaction/start" {:compactionId "failed"})
                 (fact "compaction/end" {:compactionId "failed" :error "boom"})]]
    (is (= [{:seq 1 :at 1 :tools [] :compactionId "c1"}
            {:seq 6 :at 1 :tools [] :compactionId nil}]
           (replay/fork-points records))
        "the step ends, each naming the compaction that follows it (a FAILED attempt is not one)")))

;; ------------------------------------------------ the envelope gate (ticket 02)

(deftest the-envelope-gate-sees-only-what-fell-out
  (testing "a closed run with all its pairs is clean"
    (is (nil? (replay/envelope-violations
               [(message {:role "user" :content "hi"})
                (assoc (fact "model/start" {}) :runId "r")
                (assoc (fact "model/end" {}) :runId "r")
                (assoc (fact "step/start" {}) :runId "r")
                (assoc (fact "step/end" {}) :runId "r")
                (wire "r" "RUN_FINISHED")]))))
  (testing "a run that never ended is reported once, as itself"
    (let [v (replay/envelope-violations [(message {:role "user" :content "hi"})
                                         (assoc (fact "model/start" {}) :runId "r")])]
      (is (= 1 (count v)))
      (is (= "run" (:layer (first v))))
      (is (= "r" (:run-id (first v))))))
  (testing "a stopped run's dangling model/step pairs are NOT a violation"
    ;; THE CORRECTION OF 2026-09-27: the file cannot tell a step that was stopped from one
    ;; that was killed, and the repair (closing-frames) writes a terminal, not a model/end.
    ;; Counting those pairs refused the very sessions the repair exists to save.
    (is (nil? (replay/envelope-violations [(message {:role "user" :content "hi"})
                                           (assoc (fact "model/start" {}) :runId "r")
                                           (wire "r" "RUN_FINISHED")])))))
