(ns harness.edge.relieve-pressure-test
  "The mid-run pressure question (ticket 04 of `.scratch/compaction-shape`).

  `relieve-pressure!` is what the loop asks before EVERY model call, handing it the array that is
  about to go out. It is asserted here as the function it is; the loop's own half of the seam is
  `harness.kernel.loop-test`'s.

  THE FIXTURES ARE SMALL ON PURPOSE. With no live session in this process the meter estimates the
  array it was handed, so 'an array that crosses a small window' is the whole setup -- and the
  scripted provider's window is 128,000 tokens, seven tenths of which is 89,600."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.providers :as providers]
            [harness.edge.http :as http]
            [harness.edge.replay :as replay]
            [harness.fake :as fake]
            [harness.infra.home :as home]))

;; ------------------------------------------------------------------ the records

(defn- entry [ts id text]
  {:ts ts :runId "r1" :type "message" :payload {:role "user" :content text}
   :source "client" :id id})

(defn- big-rows
  "N four-thousand-character entries -- 1008 estimated tokens each."
  [n]
  (vec (map (fn [i] (entry i (str "u" i) (apply str (repeat 4000 "a")))) (range n))))

(defn- as-array
  "The array the edge would hand in: the record's own messages, in order."
  [records]
  (mapv :message (replay/entries (vec records))))

(defn- plant! [thread-id rows]
  (let [f (home/log-file (#'http/unbound-dir) thread-id)]
    (.mkdirs (.getParentFile f))
    (spit f (str (str/join "\n" (map json/write-str rows)) "\n") :encoding "UTF-8")
    f))

(defn- until [pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 10) (recur))))))

;; -------------------------------------------------------------------- the seam

(deftest a-crossed-threshold-is-relieved-before-the-call
  ;; 95 x 1008 = 95,760 estimated tokens against a 128,000-token window: over seven tenths.
  (let [stop (http/start! {:port 0})]
    (try
      (let [thread-id "relieve-over"
            log       (plant! thread-id (big-rows 95))]
        (providers/use-provider! thread-id (fake/scripted [{:content "MID SUMMARY"}]))
        (try
          (let [array (as-array (replay/read-records log))
                view  (#'http/relieve-pressure! thread-id
                                                (fake/scripted [{:content "MID SUMMARY"}])
                                                array)]
            (is (until #(some #{"context/compacted"}
                              (mapv replay/kind (replay/read-records log)))
                       3000)
                "the summary reached the record")
            (is (some? view) "and the caller is handed an array to send instead")
            (is (< (count view) (count array)))
            (is (str/starts-with? (str (:content (first view))) "<compacted-summary>")
                "and it is the compacted conversation, not the one handed in"))
          (finally
            (providers/use-provider! thread-id nil)
            (io/delete-file log true))))
      (finally (stop)))))

(deftest a-threshold-not-crossed-costs-nothing
  (let [stop (http/start! {:port 0})]
    (try
      (let [thread-id "relieve-under"
            log       (plant! thread-id (big-rows 3))]
        (providers/use-provider! thread-id (fake/scripted [{:content "SHOULD NOT BE USED"}]))
        (try
          (let [array (as-array (replay/read-records log))
                view  (#'http/relieve-pressure! thread-id
                                                (fake/scripted [{:content "SHOULD NOT BE USED"}])
                                                array)]
            (is (nil? view) "nothing to answer")
            (is (not-any? #{"compaction/start"} (mapv replay/kind (replay/read-records log)))
                "no rows, no model call"))
          (finally
            (providers/use-provider! thread-id nil)
            (io/delete-file log true))))
      (finally (stop)))))
