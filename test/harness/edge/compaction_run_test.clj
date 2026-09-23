(ns harness.edge.compaction-run-test
  "Compaction's write half, asserted with its effects handed in.

  `harness.edge.compaction/perform!` takes `append` (write a row) and `summarize` (one model
  call) as arguments, so the lock, the range, the row order and the no-op case are ordinary
  function calls -- no provider, no file, no server. The rows it appends are folded back
  through the READ half (`harness.edge.replay`), which is the one assertion that matters:
  the writer and the reader agree on the record's shape."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.providers :as providers]
            [harness.edge.compaction :as compaction]
            [harness.edge.http :as http]
            [harness.edge.replay :as replay]
            [harness.fake :as fake]
            [harness.infra.home :as home]))

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

;; -------------------------------------------------------------------- the route

(defn- plant!
  "Write THREAD-ID's log under the projects tree `replay/locate` searches, so a route can
  find it without a run having happened."
  [thread-id rows]
  (let [f (home/log-file (#'http/unbound-dir) thread-id)]
    (.mkdirs (.getParentFile f))
    (spit f (str (str/join "\n" (map json/write-str rows)) "\n") :encoding "UTF-8")
    f))

(defn- wait-for-rows
  "RECORDS of LOG, polled until KIND appears (the writer appends off-thread) or ~2s."
  [log kind]
  (loop [n 0]
    (let [rows (replay/read-records log)
          ks   (mapv replay/kind rows)]
      (if (or (some #{kind} ks) (>= n 200))
        rows
        (do (Thread/sleep 10) (recur (inc n)))))))

(deftest the-compact-route-summarizes-and-records-one-compaction
  ;; THE WHOLE WRITE PATH ONCE: a planted record, the route deciding, a scripted provider
  ;; standing in for the summarizer, and the rows it wrote read back off disk. A SERVER RUNS
  ;; ONLY TO DRIVE THE RECORD WRITER -- `log!` appends off-thread.
  (let [thread-id "compact-route"
        rows      (mapv (fn [i] (entry i (str "u" i) (apply str (repeat 4000 "a")))) (range 25))
        log       (plant! thread-id rows)
        stop      (http/start! {:port 0})]
    (providers/use-provider! thread-id (fake/scripted [{:content "THE SUMMARY"}]))
    (try
      (let [resp (#'http/compact-post nil thread-id)
            body (json/read-str (String. ^bytes (:body resp) "UTF-8") :key-fn keyword)]
        (is (= 200 (:status resp)) (pr-str body))
        (is (true? (:compacted body)))
        (is (= 4 (count (:shadowed body))) "25 x 1008 tokens, retain 20480 -> the 4 oldest go")
        (let [ks (mapv replay/kind (wait-for-rows log "compaction/end"))]
          (is (some #{"context/compacted"} ks))
          (is (some #{"model/start"} ks) "the summary is its own bracketed model call")
          (is (some #{"model/end"} ks))
          (is (< (.indexOf ks "compaction/start") (.indexOf ks "compaction/end"))
              "start first, end last")))
      (finally
        (stop)
        (io/delete-file log true)
        (providers/use-provider! thread-id nil)))))
