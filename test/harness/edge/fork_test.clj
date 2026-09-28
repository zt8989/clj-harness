(ns harness.edge.fork-test
  "The record-level half of 'fork a session from just before a compaction': where the cut
  lands, and which compactions are fork points at all. The HTTP half lives in
  `harness.edge.fork-http-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [harness.edge.replay :as replay]))

(defn- fact
  "A fact row as the record holds it: an `event` whose CUSTOM name is KIND."
  [kind payload]
  {:ts 1 :runId "r" :type "event"
   :payload {:type "CUSTOM" :name kind :value payload}})

(defn- message [m]
  {:ts 1 :runId "r" :type "message" :payload m})

(defn- successful
  "One recorded compaction `id` at record lines [start fact end] order, plus the fact's own
  summary. Returns the three rows."
  [id]
  [(fact "compaction/start" {:compactionId id})
   (fact "context/compacted" {:compactionId id :summary (str "summary-of-" id)})
   (fact "compaction/end" {:compactionId id})])

(deftest the-cut-is-the-start-row-of-the-most-recent-successful-compaction
  (let [records (vec (concat [(message {:role "user" :content "hi"})]
                             (successful "c1")
                             [(message {:role "user" :content "more"})]
                             (successful "c2")))]
    (testing "default is the most recent one that produced a context/compacted"
      (is (= {:cut 5 :kind :compaction :compaction-id "c2" :at 5} (replay/fork-cut records))))
    (testing "a named compaction cuts before its own start row"
      (is (= {:cut 1 :kind :compaction :compaction-id "c1" :at 1}
             (replay/fork-cut records {:compaction-id "c1"}))))
    (testing "everything before the cut is the record as it stood then"
      (is (= 1 (:cut (replay/fork-cut records {:compaction-id "c1"})))))))

(deftest a-failed-compaction-is-not-a-fork-point
  (let [records [(message {:role "user" :content "hi"})
                 (fact "compaction/start" {:compactionId "bad"})
                 (fact "compaction/end" {:compactionId "bad" :error "boom"})]]
    (is (nil? (replay/fork-cut records)))
    (is (nil? (replay/fork-cut records {:compaction-id "bad"})))))

(deftest an-unknown-compaction-id-is-refused-not-guessed
  (let [records (vec (concat [(message {:role "user" :content "hi"})]
                             (successful "c1")))]
    (is (nil? (replay/fork-cut records {:compaction-id "nope"})))))

(deftest a-record-with-no-compaction-has-no-fork-point
  (is (nil? (replay/fork-cut [(message {:role "user" :content "hi"})])))
  (is (nil? (replay/fork-cut []))))

(deftest a-record-with-no-compaction-forks-at-its-last-step
  ;; owner, 2026-09-28: tying the cut to a compaction made every never-compacted session
  ;; unforkable -- and a `step/end` is the boundary the compaction plan itself uses.
  (let [records [(message {:role "user" :content "hi"})
                 (fact "step/end" {})
                 (message {:role "user" :content "more"})
                 (fact "step/end" {})]]
    (is (= {:cut 4 :kind :step :step-seq 3 :at 3} (replay/fork-cut records))
        "the last step's end, and that line is KEPT")
    (is (= {:cut 2 :kind :step :step-seq 1 :at 1} (replay/fork-cut records {:step-seq 1}))
        "any step's end may be named")
    (is (nil? (replay/fork-cut records {:step-seq 0}))
        "a line that is not a step's end is refused, not rounded")
    (is (nil? (replay/fork-cut records {:step-seq 2})))
    (testing "and the points a person picks from are the same lines"
      (is (= [{:seq 1 :kind :step :at 1 :tools []}
              {:seq 3 :kind :step :at 1 :tools []}]
             (replay/fork-points records))))))

;; ------------------------------------------------ the envelope gate (ticket 02)

(defn- wire
  "A wire frame row (`RUN_FINISHED` and friends): an `event` whose payload IS the frame."
  [run-id type]
  {:ts 1 :runId run-id :type "event" :payload {:type type}})

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
