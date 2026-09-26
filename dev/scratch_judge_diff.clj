(ns scratch-judge-diff
  (:require [harness.test-runner :as tr]
            [harness.edge.replay :as replay]))

(tr/isolate!)

(def run-id "r1")
(def user {:ts 1 :runId run-id :type "message" :source "client" :id "u1"
           :payload {:role "user" :content "hi"}})
(def head [{:ts 1 :runId run-id :type "event" :payload {:type "RUN_STARTED" :threadId "t" :runId run-id}}
           {:ts 1 :runId run-id :type "event" :payload {:type "TEXT_MESSAGE_START" :messageId "r1-m0" :role "assistant"}}
           {:ts 1 :runId run-id :type "event" :payload {:type "TEXT_MESSAGE_CONTENT" :messageId "r1-m0" :delta "the answer"}}
           {:ts 1 :runId run-id :type "event" :payload {:type "TOOL_CALL_START" :messageId "r1-m0" :toolCallId "c1"
                                                        :toolCallName "read" :parentMessageId "r1-m0"}}
           {:ts 1 :runId run-id :type "event" :payload {:type "TOOL_CALL_END" :messageId "r1-m0" :toolCallId "c1"}}])
(def tail [{:ts 1 :runId run-id :type "event" :payload {:type "RUN_FINISHED" :threadId "t" :runId run-id}}])
(def model {:ts 1 :runId run-id :type "message" :source "model"
            :payload {:role "assistant" :content "the answer" :reasoning_content "THINKING"
                      :tool_calls [{:id "c1" :type "function" :function {:name "read" :arguments "{}"}}]}})
(def tool {:ts 1 :runId run-id :type "message" :source "tool"
           :payload {:role "tool" :tool_call_id "c1" :content "result"}})
(def rframes [{:ts 1 :runId run-id :type "event" :payload {:type "REASONING_START" :messageId "r1-r0"}}
              {:ts 1 :runId run-id :type "event" :payload {:type "REASONING_MESSAGE_START" :messageId "r1-r0" :role "reasoning"}}
              {:ts 1 :runId run-id :type "event" :payload {:type "REASONING_MESSAGE_CONTENT" :messageId "r1-r0" :delta "THINKING"}}
              {:ts 1 :runId run-id :type "event" :payload {:type "REASONING_MESSAGE_END" :messageId "r1-r0"}}
              {:ts 1 :runId run-id :type "event" :payload {:type "REASONING_END" :messageId "r1-r0"}}])

(let [old-way (vec (concat [user] rframes head tail [model tool]))
      new-way (vec (concat [user] head tail [model tool]))
      a (mapv :message (replay/entries old-way))
      b (mapv :message (replay/entries new-way))]
  (println :counts (count a) (count b))
  (doseq [[i [x y]] (map-indexed vector (map vector a b))]
    (when (not= x y)
      (println :DIFF-AT i)
      (println :old (pr-str x))
      (println :new (pr-str y)))))
(System/exit 0)
