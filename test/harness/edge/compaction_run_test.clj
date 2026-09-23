(ns harness.edge.compaction-run-test
  "Compaction's write half, asserted with its effects handed in.

  `harness.edge.compaction/perform!` takes `append` (write a row) and `summarize` (one model
  call) as arguments, so the lock, the range, the row order and the no-op case are ordinary
  function calls -- no provider, no file, no server. The rows it appends are folded back
  through the READ half (`harness.edge.replay`), which is the one assertion that matters:
  the writer and the reader agree on the record's shape."
  (:require [clojure.test :refer [deftest is testing]]
            [harness.edge.compaction :as compaction]
            [harness.edge.replay :as replay]))

;; ------------------------------------------------------------------ the records

(defn- entry [ts id text]
  {:ts ts :runId "r1" :type "message" :payload {:role "user" :content text}
   :source "client" :id id})

(defn- big [i]
  ;; 400 characters -> estimate-message says 400/4 + 4 (block) + 4 (role) = 108 tokens.
  (entry i (str "u" i) (apply str (repeat 400 "a"))))

(defn- six []
  (vec (map big (range 6))))

(defn- row
  "A [kind payload] pair as the record row it becomes -- what `append` hands the writer."
  [kind payload]
  {:ts 0 :runId nil :type "event"
   :payload {:type "CUSTOM" :name kind :value payload}})

(defn- write-rows [written] (mapv (fn [[k p]] (row k p)) written))

;; ---------------------------------------------------------------------- the range

(deftest the-plan-keeps-the-tail-verbatim-and-hands-over-the-head
  (let [plan (compaction/plan (six) 1000 0.16)]
    (is (= [0 1 2 3] (:shadowed plan))
        "the four OLDEST nodes are compacted; the two newest are the retained tail")
    (is (= 4 (count (:messages plan))))
    (is (= 432 (:head-tokens plan)) "4 x 108")))

(deftest the-whole-surface-in-the-retained-tail-is-nothing-to-do
  (is (nil? (compaction/plan [(entry 0 "u1" "hi")] 1000 0.16))))

;; ------------------------------------------------------------------------ the run

(deftest a-compaction-writes-three-rows-and-shortens-the-model-view
  (let [records (six)
        written (atom [])
        result  (compaction/perform! records {:window 1000 :retain-ratio 0.16
                                              :append    (fn [k p] (swap! written conj [k p]))
                                              :summarize (fn [msgs] (str "SUMMARY of " (count msgs)))})
        all     (into records (write-rows @written))
        view    (replay/compacted-messages (replay/entries all) (replay/compaction-facts all))]
    (is (= [0 1 2 3] (:shadowed result)))
    (is (= ["compaction/start" "context/compacted" "compaction/end"] (mapv first @written))
        "start first, end LAST -- that order is the lock")
    (is (= "<compacted-summary>SUMMARY of 4</compacted-summary>" (:content (first view)))
        "the projection stands one summary where the four compacted nodes stood")
    (is (= 3 (count view)) "one summary + the two retained")
    (is (= 6 (count (replay/entries all))) "the conversation still holds every original")))

(deftest nothing-to-compact-writes-nothing
  (let [written (atom [])]
    (is (nil? (compaction/perform! [(entry 0 "u1" "hi")]
                                   {:window 1000 :retain-ratio 0.16
                                    :append    (fn [k p] (swap! written conj [k p]))
                                    :summarize (fn [_] "S")})))
    (is (= [] @written) "no range, no rows -- not an empty pair")))

(deftest a-held-lock-refuses-a-second-compaction
  (let [records (conj (six) (row "compaction/start" {:compactionId "open"}))
        written (atom [])]
    (is (= "open" (compaction/lock-active? records)))
    (is (nil? (compaction/perform! records {:window 1000 :retain-ratio 0.16
                                            :append    (fn [k p] (swap! written conj [k p]))
                                            :summarize (fn [_] "S")})))
    (is (= [] @written) "a held lock writes nothing")))

(deftest a-failed-summary-still-closes-its-end-with-the-error
  (let [written (atom [])
        thrown  (try
                  (compaction/perform! (six) {:window 1000 :retain-ratio 0.16
                                              :append    (fn [k p] (swap! written conj [k p]))
                                              :summarize (fn [_] (throw (ex-info "no model" {})))})
                  nil
                  (catch Throwable t t))]
    (is (instance? Throwable thrown) "the failure is rethrown untouched")
    (is (= ["compaction/start" "compaction/end"] (mapv first @written)))
    (is (= "no model" (:error (second (second @written))))
        "the end carries the error: a failure is recorded as a failure, not left unrecorded")))

(deftest a-second-compaction-names-the-first-summary
  (let [records   (six)
        written   (atom [])
        append    (fn [k p] (swap! written conj [k p]))]
    (compaction/perform! records {:window 1000 :retain-ratio 0.16
                                  :append append :summarize (fn [_] "S1")})
    (let [all   (into records (write-rows @written))
          plan2 (compaction/plan all 1000 0.16)]
      (is (= [7] (:shadowed plan2))
          "the second range names the first SUMMARY's fact seq (7), not the four originals it replaced"))))
