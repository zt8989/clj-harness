(ns harness.loop
  "ReAct: stream a turn, run its tool calls serially, append the results, repeat.
  Terminates when a turn has no tool calls. No iteration cap, by design."
  (:refer-clojure :exclude [run!])
  (:require [clojure.core.async :as async]
            [harness.event :as ev]
            [harness.llm :as llm]
            [harness.tools :as tools]))

(defn- drive!
  "Run one run, calling EMIT with each harness.event value as it is produced.
  Returns the final history. Shared by both the callback and channel contracts so
  the run's behaviour lives in exactly one place."
  [provider messages emit]
  (let [history (atom (vec messages))]
    (emit (ev/run-start))
    (try
      (loop []
        (let [assistant (llm/stream! provider @history emit)
              calls     (:tool_calls assistant)]
          (swap! history conj assistant)
          (when (seq calls)
            (doseq [{:keys [id] :as call} calls]        ; serial, by design
              (let [{:keys [content error]} (tools/run! call)]
                (emit (ev/tool-result id content error))
                (swap! history conj {:role "tool" :tool_call_id id :content content})))
            (recur))))
      (emit (ev/run-end))
      (catch Throwable t
        (emit (ev/run-error (ex-message t)))))
    @history))

(defn run!
  "Drive one run to completion. MESSAGES is the provider-shaped history.
  ON-EVENT receives each harness.event value. Returns the final history.

  Callback contract -- kept for compatibility; the channel form is run-chan."
  [provider messages on-event]
  (drive! provider messages on-event))

(defn run-chan
  "Drive one run, returning a channel of harness.event values. After the run a
  terminal event {:type :run/done :history <final-history>} is put, then the
  channel closes. The channel is unbuffered: a slow consumer applies natural
  backpressure rather than dropping events.

  Pass your own CH to own the buffer policy; otherwise one is created. The
  producer runs on async/thread because the run does blocking I/O (the network
  stream and the tools), so it must not occupy a go block."
  ([provider messages] (run-chan provider messages (async/chan)))
  ([provider messages ch]
   (async/thread
     (let [history (drive! provider messages #(async/put! ch %))]
       (async/put! ch {:type :run/done :history history})
       (async/close! ch)))
   ch))
