(ns harness.loop
  "ReAct: stream a turn, run its tool calls concurrently, append the results, repeat.
  Terminates when a turn has no tool calls. No iteration cap, by design.
  Events leave the kernel over a core.async channel (run-chan)."
  (:require [clojure.core.async :as async]
            [harness.event :as ev]
            [harness.llm :as llm]
            [harness.tools :as tools]))

(defn- drive!
  "Run one run, calling EMIT with each harness.event value as it is produced.
  Returns the final history. The producer side of run-chan; all run behaviour
  lives in exactly this one place. OPTS carries {:thread-id}, the session the
  run serves -- it selects the thread's effective toolset, nothing else.

  Tool calls of one turn run CONCURRENTLY, each on its own thread-pool thread --
  they are blocking I/O, not go material. Every :tool/result is emitted the
  moment its tool finishes, so results flow in completion order. The history
  still appends the tool messages in the provider's call order: whatever the
  completion order was, each tool_call_id is answered exactly once, in the
  order the calls were made. Conversion to AG-UI frames stays serial at the
  consumer, so ag/outbound's message-id atom never races."
  [provider messages emit {:keys [thread-id] :as _opts}]
  (let [history (atom (vec messages))]
    (emit (ev/run-start))
    (try
      (loop []
        (let [assistant (llm/stream! provider @history emit thread-id)
              calls     (:tool_calls assistant)]
          (swap! history conj assistant)
          (when (seq calls)
            ;; One buffered channel per call: the tool thread never blocks on
            ;; put, and alts!! over them hands back results as they finish.
            ;; The channels are deliberately never closed -- alts!! treats a
            ;; closed empty port as ready-with-nil, which would let a drain
            ;; swallow a phantom nil and strand a real result.
            (let [chs  (mapv (fn [{:keys [id] :as call}]
                               (let [ch (async/chan 1)]
                                 (async/thread
                                   ;; EMIT doubles as the lifecycle on-phase:
                                   ;; the seam's pre/execute/post events ride the
                                   ;; same channel out to the edge.
                                   (let [{:keys [content error]} (tools/run! call thread-id emit)]
                                     (async/>!! ch {:id id :content content :error error})))
                                 ch))
                             calls)
                  done (atom {})]
              (dotimes [_ (count chs)]
                ;; alts!! returns [value port]; the value carries its own id.
                (let [[{:keys [id content error] :as result} _] (async/alts!! chs)]
                  (emit (ev/tool-result id content error))
                  (swap! done assoc id result)))
              (doseq [{:keys [id]} calls]
                (swap! history conj {:role "tool" :tool_call_id id
                                     :content (:content (@done id))})))
            (recur))))
      (emit (ev/run-end))
      (catch Throwable t
        (emit (ev/run-error (ex-message t)))))
    @history))

(defn run-chan
  "Drive one run, returning a channel of harness.event values. After the run a
  terminal event {:type :run/done :history <final-history>} is put, then the
  channel closes. The channel is unbuffered and the producer BLOCKS on every
  put (>!!): a slow consumer applies natural backpressure rather than dropping
  events or queueing them up.

  The producer runs on async/thread because the run does blocking I/O (the
  network stream and the tools), so it must not occupy a go block."
  ([provider messages] (run-chan provider messages nil))
  ([provider messages {:keys [thread-id] :as opts}]
   (let [ch (async/chan)]
     (async/thread
       (let [history (drive! provider messages #(async/>!! ch %) opts)]
         (async/>!! ch {:type :run/done :history history})
         (async/close! ch)))
     ch)))
