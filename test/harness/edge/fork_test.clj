(ns harness.edge.fork-test
  "The record-level half of 'fork a session from just before a compaction': where the cut
  lands, and which compactions are fork points at all. The HTTP half lives in
  `harness.edge.http-test`."
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
      (is (= {:cut 5 :compaction-id "c2"} (replay/fork-cut records))))
    (testing "a named compaction cuts before its own start row"
      (is (= {:cut 1 :compaction-id "c1"} (replay/fork-cut records "c1"))))
    (testing "everything before the cut is the record as it stood then"
      (is (= 1 (:cut (replay/fork-cut records "c1")))))))

(deftest a-failed-compaction-is-not-a-fork-point
  (let [records [(message {:role "user" :content "hi"})
                 (fact "compaction/start" {:compactionId "bad"})
                 (fact "compaction/end" {:compactionId "bad" :error "boom"})]]
    (is (nil? (replay/fork-cut records)))
    (is (nil? (replay/fork-cut records "bad")))))

(deftest an-unknown-compaction-id-is-refused-not-guessed
  (let [records (vec (concat [(message {:role "user" :content "hi"})]
                             (successful "c1")))]
    (is (nil? (replay/fork-cut records "nope")))))

(deftest a-record-with-no-compaction-has-no-fork-point
  (is (nil? (replay/fork-cut [(message {:role "user" :content "hi"})])))
  (is (nil? (replay/fork-cut []))))
