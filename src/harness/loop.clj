(ns harness.loop
  "ReAct: stream a turn, run its tool calls concurrently, append the results, repeat.
  Terminates when a turn has no tool calls. No iteration cap, by design.
  Events leave the kernel over a core.async channel (run-chan)."
  (:require [clojure.core.async :as async]
            [harness.event :as ev]
            [harness.hooks.dispatch :as hook]
            [harness.llm :as llm]
            [harness.project :as project]
            [harness.skills :as skills]
            [harness.tools :as tools]))

(defn- replay!
  "Answer the parked calls a human decided on, before the next LLM call. Each
  decision is written into the parked record and its call goes through the seam
  again -- approved runs the tool for real, vetoed answers with the veto as the
  result. DECISIONS is [{:interrupt-id .. :verdict :approved|:vetoed :payload ..}]
  in the order the client sent them.

  Replayed SERIALLY, unlike a turn's calls: these are answers to one prompt, and
  keeping their events in the decision order is worth more than the parallelism.
  An interrupt this process never parked is a caller error -- surfaced as a run
  error, never guessed into an approval.

  Returns the calls that had to be parked AGAIN: a verdict is spent once, so a
  replay of a decided interrupt parks afresh and the run must stop on that
  interrupt rather than carry on to the provider with an unanswered call."
  [decisions thread-id emit history]
  (vec
   (keep (fn [{:keys [interrupt-id verdict payload]}]
           (let [rec (tools/parked interrupt-id)]
             (when-not rec
               (throw (ex-info (str "unknown interrupt: " interrupt-id) {})))
             (tools/decide-approval! interrupt-id verdict payload)
             (let [call-id (:tool-call-id rec)
                   {:keys [content error parked]}
                   (tools/run! {:id call-id
                                :function {:name (:name rec) :arguments (:args rec)}}
                               thread-id emit)]
               (if parked
                 parked
                 (do (emit (ev/tool-result call-id content error))
                     (swap! history conj {:role "tool" :tool_call_id call-id
                                          :content content})
                     nil)))))
         decisions)))

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
  consumer, so ag/outbound's message-id atom never races.

  A call the seam parked (:needs-approval) never ran and is left UNANSWERED --
  no :tool/result, no tool message -- and the run ends on :run/interrupt instead
  of :run/end: the conversation is now the human's to decide. Its tool message
  lands on the resume run, after the decision.

  OPTS may carry :resume, the decisions a human handed back for this thread's
  parked calls; they are replayed at the top of the run, before the first LLM
  call, so the provider sees a complete turn again.

  SKILL BODIES ARE RE-DERIVED BEFORE EVERY LLM CALL (harness.skills/derived-
  injections). This is the ONE place they can enter the conversation: a load has
  to be visible to the very next call, because the model asked for the
  instructions in order to follow them now, and a load that only took effect on
  the following turn would have been pointless to issue. Re-deriving rather than
  remembering is what makes that free -- the function is idempotent, so applying
  it to a history that already has the bodies changes nothing, and there is no
  bookkeeping to get out of step with the conversation.

  The roots are read fresh at each LLM call, so editing harness.edn mid-run moves
  them, matching every other configuration read in this codebase."
  [provider messages emit {:keys [thread-id resume] :as _opts}]
  (let [history (atom (vec messages))
        ;; The session's roots, resolved once per run: the binding and the
        ;; configuration are both per-session facts, and a run serves one session.
        roots (project/skill-roots thread-id)
        with-skills (fn [] (swap! history skills/derived-injections roots))]
    (emit (ev/run-start))
    (try
      (let [replayed (when (seq resume)
                       (replay! resume thread-id emit history))
            parked
            (if (seq replayed)
              ;; A replayed call that had to park again: the turn is still
              ;; unanswered, so there is no provider call to make.
              replayed
              (loop []
                (let [_         (with-skills)
                      assistant (llm/stream! provider @history emit thread-id)
                      calls     (:tool_calls assistant)]
                  (swap! history conj assistant)
                  (if (seq calls)
                    ;; One buffered channel per call: the tool thread never blocks
                    ;; on put, and alts!! over them hands back results as they
                    ;; finish. The channels are deliberately never closed --
                    ;; alts!! treats a closed empty port as ready-with-nil, which
                    ;; would let a drain swallow a phantom nil and strand a real
                    ;; result.
                    (let [chs  (mapv (fn [{:keys [id] :as call}]
                                       (let [ch (async/chan 1)]
                                         (async/thread
                                           ;; EMIT doubles as the lifecycle
                                           ;; on-phase: the seam's pre/execute/post
                                           ;; events ride the same channel out to
                                           ;; the edge.
                                           (let [{:keys [content error parked]}
                                                 (tools/run! call thread-id emit)]
                                             (async/>!! ch {:id id :content content
                                                            :error error :parked parked})))
                                         ch))
                                     calls)
                          done (atom {})]
                      ;; THE SEAM IS TOLD ABOUT THE WHOLE TURN BEFORE ANY OF IT
                      ;; RUNS, and this is the only place that can do it: a tool
                      ;; sees one call, and the anchor tools need to know which of
                      ;; their siblings address the same file (see the batch
                      ;; section of harness.tools). Unregistered -- a direct run!,
                      ;; a replayed approval -- every call is on its own, which is
                      ;; what it was before batching existed.
                      (tools/register-turn! thread-id calls)
                      (dotimes [_ (count chs)]
                        ;; alts!! returns [value port]; the value carries its own
                        ;; id, so completion order needs no bookkeeping. A parked
                        ;; call reports no result -- it has not been answered.
                        (let [[result _] (async/alts!! chs)]
                          (when (nil? (:parked result))
                            (emit (ev/tool-result (:id result) (:content result) (:error result))))
                          (swap! done assoc (:id result) result)))
                      ;; ...and forgotten once every call has answered, so the plan
                      ;; does not accumulate for the life of the process.
                      (tools/forget-turn!)
                      (let [results (mapv #(get @done (:id %)) calls)
                            parked  (vec (keep :parked results))]
                        ;; Answer every call that actually ran; a parked call
                        ;; stays unanswered until a human decides.
                        (doseq [{:keys [id content] :as result} results
                                :when (nil? (:parked result))]
                          (swap! history conj {:role "tool" :tool_call_id id :content content}))
                        (if (seq parked)
                          parked
                          (recur))))
                    nil))))]
        ;; Stop is an OBSERVER, and it fires only where the run actually stops
        ;; normally -- a run that ends on an interrupt is waiting for a human,
        ;; and a run that threw ends on :run/error, which is StopFailure's
        ;; business (declared, not wired). Its verdict is discarded: there is
        ;; nothing left for it to gate.
        (when-not (seq parked)
          (hook/emit :stop {}))
        (emit (if (seq parked)
                (ev/run-interrupt
                 (mapv (fn [{:keys [interrupt-id id name args]}]
                         {:id interrupt-id :tool-call-id id :name name :args args})
                       parked))
                (ev/run-end))))
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
