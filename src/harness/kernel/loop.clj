(ns harness.kernel.loop
  "ReAct: stream a turn, run its tool calls concurrently, append the results, repeat.
  Terminates when a turn has no tool calls. No iteration cap, by design.
  Events leave the kernel over a core.async channel (run-chan)."
  (:require [clojure.core.async :as async]
            [harness.kernel.event :as ev]
            [harness.kernel.hooks.dispatch :as hook]
            [harness.kernel.llm :as llm]
            [harness.kernel.tools :as tools]))

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

(defn- model-call!
  "One model call with its boundaries emitted: :model/start before the request,
  :model/end after it -- AND WHEN IT THROWS, which is why this is a function
  rather than two lines at the call site.

  A CALL THAT DIES MID-STREAM REPORTS NOTHING, so its :model/end carries an empty
  telemetry -- and the segment is closed either way. A segment with no end cannot
  be told from one that is still running, which is exactly the distinction a reader
  of the record needs: a stalled call must not look like a call that never
  finished. The error itself is rethrown untouched, so the run still ends on
  :run/error after this line.

  THE TELEMETRY RIDES OUT ON THE EVENT and stops there -- it is not appended to
  the history. It belongs to the call, not to the conversation: showing it to the
  provider on a later request would be inventing a field the vendor never asked
  for.

  THE TOOL TABLE IS RESOLVED ONCE, HERE, and handed to both sides: the provider
  puts it in the request body and the start event records it. That is what makes
  `model/start`'s :tools the table that WENT OUT rather than a second resolution
  that happens to agree -- and it is why the resolve lives in this function rather
  than in the provider layer."
  [provider history emit thread-id]
  (let [specs (tools/specs thread-id)]
    (emit (ev/model-start provider specs))
    (try
      (let [{:keys [message telemetry]}
            (llm/stream! (assoc provider :tools specs) history emit thread-id)]
        (emit (ev/model-end telemetry))
        message)
      (catch Throwable t
        (emit (ev/model-end nil))
        (throw t)))))

(defn- drive!
  "Run one run, calling EMIT with each harness.kernel.event value as it is produced.
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

  THE CALLER HANDS IN A PRE-LLM STEP, and the reason it takes one is worth stating
  because this used to require it: a session's loaded SKILL BODIES have to be back
  in the history before EVERY LLM call. A load must be visible to the very next
  call -- the model asked for the instructions in order to follow them NOW -- and a
  load that only took effect on the following turn would have been pointless to
  issue. This remains the ONE place they can enter the conversation; what changed is
  who guarantees it. It is `:before-llm` in OPTS, applied to the history immediately
  before each `llm/stream!`, and the edge supplies it (harness.cap.project/
  before-llm). Re-deriving rather than remembering is what makes that free: the
  function is idempotent, so applying it to a history that already has the bodies
  changes nothing and there is no bookkeeping to get out of step.

  With NOTHING handed in nothing is injected, which is the honest default: this
  namespace cannot know what a session's history should be decorated with, and an
  offline replay that wants the bodies passes the same function the edge does.

  EVERY MODEL CALL IS BRACKETED by :model/start / :model/end (`model-call!`, just
  above): the pair is what lets the record say how long a call took and what the
  vendor reported for it, without the kernel timing anything itself."
  [provider messages emit {:keys [thread-id resume before-llm] :as _opts}]
  (let [history (atom (vec messages))
        ;; (history, thread-id) -> history, called immediately before every LLM
        ;; call. Identity when the caller passed nothing, so the code path is the
        ;; same either way -- exactly how the unbound hook sink keeps its callers
        ;; free of a second branch.
        prepare (or before-llm (fn [h _thread-id] h))
        with-skills (fn [] (swap! history prepare thread-id))]
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
                      assistant (model-call! provider @history emit thread-id)
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
                      ;; section of harness.kernel.tools). Unregistered -- a direct run!,
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
                 (mapv (fn [{:keys [interrupt-id id name args reason question]}]
                         ;; :reason and :question ride along because there is more
                         ;; than one way to park: a human deciding, and a server
                         ;; asking a question. The client has to tell them apart to
                         ;; draw the right card, and the question belongs on it.
                         ;; What reaches the WIRE is harness.edge.ag-ui's business,
                         ;; and it stays a strict interrupt object there.
                         (cond-> {:id interrupt-id :tool-call-id id :name name :args args}
                           reason   (assoc :reason reason)
                           question (assoc :question question)))
                       parked))
                (ev/run-end))))
      (catch Throwable t
        (emit (ev/run-error (ex-message t)))))
    @history))

(defn run-chan
  "Drive one run, returning a channel of harness.kernel.event values. After the run a
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
