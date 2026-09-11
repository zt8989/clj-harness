(ns harness.loop
  "ReAct: stream a turn, run its tool calls serially, append the results, repeat.
  Terminates when a turn has no tool calls. No iteration cap, by design."
  (:refer-clojure :exclude [run!])
  (:require [harness.event :as ev]
            [harness.llm :as llm]
            [harness.tools :as tools]))

(defn run!
  "Drive one run to completion. MESSAGES is the provider-shaped history.
  ON-EVENT receives each harness.event value. Returns the final history."
  [provider messages on-event]
  (let [history (atom (vec messages))]
    (on-event (ev/run-start))
    (try
      (loop []
        (let [assistant (llm/stream! provider @history on-event)
              calls     (:tool_calls assistant)]
          (swap! history conj assistant)
          (when (seq calls)
            (doseq [{:keys [id] :as call} calls]        ; serial, by design
              (let [{:keys [content error]} (tools/run! call)]
                (on-event (ev/tool-result id content error))
                (swap! history conj {:role "tool" :tool_call_id id :content content})))
            (recur))))
      (on-event (ev/run-end))
      (catch Throwable t
        (on-event (ev/run-error (ex-message t)))))
    @history))
