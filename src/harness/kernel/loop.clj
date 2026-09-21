(ns harness.kernel.loop
  "ReAct: stream a turn, run its tool calls concurrently, append the results, repeat.
  Terminates when a turn has no tool calls. No iteration cap, by design.
  Events leave the kernel over a core.async channel (run-chan)."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [harness.kernel.event :as ev]
            [harness.kernel.hooks.dispatch :as hook]
            [harness.kernel.llm :as llm]
            [harness.kernel.tools :as tools]))

(defn- added!
  "Put MESSAGE at the end of HISTORY and note it among the messages this run ADDED."
  [history added message]
  (swap! history conj message)
  (swap! added conj message))

(defn- call-position
  "The index in HISTORY of the assistant message that NAMED CALL-ID, or nil when no
  message in it does.

  THE SAME READING `harness.kernel.llm/unanswered-tool-calls` DOES, and deliberately:
  that function is the rule, this is the position the rule talks about, and a second
  opinion about how a call is named would be a second chance to disagree with it."
  [history call-id]
  (first (keep-indexed (fn [i message]
                         (when (and (= "assistant" (:role message))
                                    (some #(= call-id (:id %)) (:tool_calls message)))
                           i))
                       history)))

(defn- answer-position
  "Where in HISTORY an answer to the call named by the assistant message at INDEX goes:
  directly behind that message, and behind the answers already sitting there.

  THE SECOND HALF IS CALL ORDER, and it is not the vendor's rule but this harness's. A
  turn with two parked calls is replayed one decision at a time, so 'directly behind the
  assistant message' would put the second answer in FRONT of the first and the history
  would list a turn's answers backwards
  (`harness.approval-test/a-mixed-decision-list-is-answered-in-call-order`). The vendor
  needs the answers adjacent to the call; the order among them is the call order."
  [history index]
  (loop [j (inc index)]
    (if (= "tool" (:role (nth history j nil)))
      (recur (inc j))
      j)))

(defn- answer!
  "Put the TOOL message that answers a call into HISTORY where the vendor's rule wants
  it -- behind the assistant message that named that call (`answer-position`) -- and
  note it among the messages this run added. Answers true when it landed there.

  WHY NOT THE END, which is where it used to go: what the vendor checks is ADJACENCY
  (`harness.kernel.llm/unanswered-tool-calls`), and a history this run was HANDED may
  have something behind that assistant message -- the edge applies its pre-LLM step
  before handing the run over, so a skill body or a job's ending can be sitting there.
  A tool message appended past it answers nothing: the run asks the same question
  again, and approving it a second time runs the tool again. This is what
  `.scratch/session-opening` ticket 02 is about.

  FALSE IS AN ANSWER AND NOT A FAILURE: a history that does not carry that assistant
  message at all (a client that rebuilt a conversation from a window that lost it) has
  no place to put the answer, so it goes to the end -- where the run's own reader can
  still find it -- and the caller reports it as unplaced. Inventing an adjacency would
  be worse than saying so."
  [history added message]
  (if-some [i (call-position @history (:tool_call_id message))]
    (do (swap! history (fn [h]
                         (let [at (answer-position h i)]
                           (vec (concat (subvec h 0 at) [message] (subvec h at))))))
        (swap! added conj message)
        true)
    (do (added! history added message)
        false)))

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

  Each answer goes in behind the call it answers (`answer!`), never at the end of the
  history: this run was handed what the session held, and an injection may be sitting
  behind that call already.

  Answers {:parked <the calls that had to be parked AGAIN> :unplaced <the ids whose
  answer had no call to sit behind>}. A verdict is spent once, so a replay of a decided
  interrupt parks afresh and the run must stop on that interrupt rather than carry on to
  the provider with an unanswered call."
  [decisions thread-id emit history added]
  (let [outcomes (mapv (fn [{:keys [interrupt-id verdict payload]}]
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
                               {:parked parked}
                               (do (emit (ev/tool-result call-id content error))
                                   (if (answer! history added {:role "tool" :tool_call_id call-id
                                                               :content content})
                                     {}
                                     {:unplaced call-id}))))))
                       decisions)]
    {:parked   (vec (keep :parked outcomes))
     :unplaced (vec (keep :unplaced outcomes))}))

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

(defn- unanswerable-call-message
  "What the run is refused WITH when its history leaves a tool call unanswered that no
  parked record in this process answers.
  
  NAMED RATHER THAN RELAYED. The vendor's answer to this shape is a 400 whose sentence
  names neither the call nor the reason -- 'insufficient tool messages following
  tool_calls message' -- and a caller that gets it cannot tell an assembled history
  bug from a harness one, nor which call to fix. So the ids are in the sentence, and so
  is the one thing a human can act on: a park lives in the process that made it, so
  after a restart nothing can answer these, and the conversation cannot be continued as
  it stands.
  
  A REFUSAL RATHER THAN A REPAIR. Answering the call here would be inventing a result
  the model never saw and that no tool produced; the honest repair for a call whose run
  was cut off belongs where a human asked to read the log back
  (harness.edge.replay/closing-frames), not on the way to the provider."
  [ids]
  (str "this run's history leaves " (count ids) " tool call"
       (when (< 1 (count ids)) "s") " unanswered and no parked approval in this process"
       " can answer " (if (< 1 (count ids)) "them" "it") " (" (str/join ", " ids) "):"
       " an OpenAI-shaped vendor refuses a request whose assistant message with tool_calls"
       " is not followed by a tool message for each 'tool_call_id', so the run was"
       " refused before the provider was called. Send a result for "
       (if (< 1 (count ids)) "those calls" "that call") ", or start a new session."))

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

  AND THAT IS WHY A RUN CAN END ON AN INTERRUPT WITHOUT ANY MODEL CALL: a history
  that still carries such an unanswered call -- the client continued the conversation
  without resuming it, which a refresh that lost the parked card does -- cannot be sent
  to any OpenAI-shaped vendor. The run asks the question again when this process still
  holds the park, and refuses by name when nobody does. See `stalled` in drive!.

  OPTS may carry :resume, the decisions a human handed back for this thread's
  parked calls; they are replayed at the top of the run, before the first LLM
  call, so the provider sees a complete turn again.

  THE CALLER HANDS IN A PRE-LLM STEP, and the reason it takes one is worth stating
  because this used to require it: a session's loaded SKILL BODIES have to be back
  in the history before EVERY LLM call -- and so does the ending of a background job
  nobody waited for (harness.cap.jobs), which the same step carries. A load must be visible to the very next
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
  vendor reported for it, without the kernel timing anything itself.

  ANSWERS WHAT THE RUN ADDED, rather than only the history it ended with: the caller
  needs 'the messages this run put in' to write the returned side of its record, and
  the history's tail is no longer that answer. A replayed call's tool message is
  inserted BEHIND the call it answers (`answer!`), so counting past the messages the
  run was handed would file one of the CLIENT's messages as the kernel's own and drop
  the one that really was. So the run keeps its own account: {:history <the final
  history> :added <the messages it added, in the order it added them> :unplaced <the
  replayed calls whose answer had to go to the end>}."
  [provider messages emit {:keys [thread-id resume before-llm] :as _opts}]
  (let [history (atom (vec messages))
        ;; WHAT THIS RUN ADDED, said by the run itself (see the docstring above): every
        ;; site that puts a message into `history` notes it here. `with-skills` counts
        ;; too -- a derived injection is a message this run put in the conversation,
        ;; and the record has always shown it on the returned side.
        added   (atom [])
        ;; REPLAYED CALLS WHOSE ANSWER HAD NOWHERE TO SIT (`answer!`): collected out here
        ;; rather than inside the try, because :run/done reports them whether the run
        ;; went on to the provider or died on the way.
        unplaced (atom [])
        ;; (history, thread-id) -> history, called immediately before every LLM
        ;; call. Identity when the caller passed nothing, so the code path is the
        ;; same either way -- exactly how the unbound hook sink keeps its callers
        ;; free of a second branch.
        prepare (or before-llm (fn [h _thread-id] h))
        ;; WHAT THE STEP JUST ADDED, SAID OUT LOUD. Applying the step is one atomic
        ;; step (swap-vals! answers both sides of it), so the messages it appended are
        ;; exactly the tail past the old count -- and each one is emitted as
        ;; :context/injected, which the edge turns into a CUSTOM frame the client can
        ;; draw. That is the whole of "the model was handed this and did not ask for
        ;; it": the step itself stays as silent as it was, and this is where the run
        ;; says what happened.
        with-skills (fn []
                      (let [[before after] (swap-vals! history prepare thread-id)
                            fresh         (subvec after (count before))]
                        (swap! added into fresh)
                        (doseq [message fresh]
                          (emit (ev/context-injected message)))))]
    (emit (ev/run-start))
    (try
      (let [replay   (if (seq resume)
                       (replay! resume thread-id emit history added)
                       {:parked [] :unplaced []})
            replayed (:parked replay)
            _        (swap! unplaced into (:unplaced replay))
            ;; WHAT THIS HISTORY LEAVES UNANSWERED, read before the first model call
            ;; because it decides whether there is one to make. A run that parks a call
            ;; ENDS on :run/interrupt with the call unanswered (see drive!'s own note
            ;; below), so a client that continues such a conversation WITHOUT resuming
            ;; anything -- a refresh that lost the parked card, a second run started
            ;; beside the first -- hands the next run a block nothing answers, and the
            ;; vendor refuses the request before the model runs at all. The vendor's own
            ;; sentence names neither the call nor the reason, which is how this arrived
            ;; as an opaque 400 on a conversation that then stayed bricked: the client's
            ;; history is the client's, so every later message re-sent the same block.
            ;;
            ;; TWO ANSWERS, and the difference is whether anyone can still answer:
            ;;
            ;;   * STILL PARKED HERE. The human has not decided yet and a decision can
            ;;     still arrive, so the run ASKS AGAIN -- it ends on the same interrupt
            ;;     it ended on before, and the client gets its card back instead of a
            ;;     request nobody can serve. Nothing is invented and nothing is
            ;;     pre-empted: the same question, asked a second time.
            ;;   * NOBODY HOLDS IT. A park lives in the process that made it, so after a
            ;;     restart this call can never be answered. Sending it is what produced
            ;;     the 400; guessing a result for it would be inventing one. The run is
            ;;     refused BY NAME instead -- see `unanswerable-call-message`.
            stalled  (vec (llm/unanswered-tool-calls @history))
            still    (when (seq stalled) (tools/parked-interrupts thread-id stalled))
            dead     (vec (remove (set (map :id still)) stalled))
            refusal  (when (seq dead) (unanswerable-call-message dead))
            _        (when refusal (throw (ex-info refusal {:unanswered dead})))
            parked
            (cond
              ;; A replayed call that had to park again: the turn is still
              ;; unanswered, so there is no provider call to make.
              (seq replayed) replayed
              ;; A call parked LAST turn, still undecided: ask the same question
              ;; again rather than send a history the vendor refuses.
              (seq still)    still
              :else          (loop []
                (let [_         (with-skills)
                      assistant (model-call! provider @history emit thread-id)
                      calls     (:tool_calls assistant)]
                  (added! history added assistant)
                  (if (seq calls)
                    ;; One buffered channel per call: the tool thread never blocks
                    ;; on put, and alts!! over them hands back results as they
                    ;; finish. The channels are deliberately never closed --
                    ;; alts!! treats a closed empty port as ready-with-nil, which
                    ;; would let a drain swallow a phantom nil and strand a real
                    ;; result.
                    (let [;; THE SEAM IS TOLD ABOUT THE WHOLE TURN BEFORE ANY OF IT
                          ;; RUNS, and this is the only place that can do it: a tool
                          ;; sees one call, and the anchor tools need to know which of
                          ;; their siblings address the same file (see the batch
                          ;; section of harness.kernel.tools). Unregistered -- a direct run!,
                          ;; a replayed approval -- every call is on its own, which is
                          ;; what it was before batching existed.
                          ;;
                          ;; REGISTERED BEFORE THE TOOL THREADS ARE SPAWNED, which is what
                          ;; makes the sentence above true rather than aspirational: a body
                          ;; may ask about its own turn (`sole-call-of-its-name?`), and a
                          ;; planner that is slow would otherwise let it read the empty --
                          ;; or the previous -- plan. The token names THIS registration, so
                          ;; one turn finishing cannot drop a later turn's plan for the
                          ;; same thread-id (see register-turn!).
                          token (tools/register-turn! thread-id calls)
                          chs  (mapv (fn [{:keys [id] :as call}]
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
                      (tools/forget-turn! thread-id token)
                      (let [results (mapv #(get @done (:id %)) calls)
                            parked  (vec (keep :parked results))]
                        ;; Answer every call that actually ran; a parked call
                        ;; stays unanswered until a human decides.
                        (doseq [{:keys [id content] :as result} results
                                :when (nil? (:parked result))]
                          (added! history added {:role "tool" :tool_call_id id :content content}))
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
    {:history  @history
     :added    @added
     :unplaced @unplaced}))

(defn run-chan
  "Drive one run, returning a channel of harness.kernel.event values. After the run a
  terminal event {:type :run/done :history <final-history> :added <what this run put
  in> :unplaced <replayed calls with no call to sit behind>} is put, then the channel
  closes. `:added` is what the edge writes as the message record's RETURNED side -- the
  history's tail would be a guess (see `drive!`). The channel is unbuffered and the
  producer BLOCKS on every put (>!!): a slow consumer applies natural backpressure
  rather than dropping events or queueing them up.

  The producer runs on async/thread because the run does blocking I/O (the
  network stream and the tools), so it must not occupy a go block."
  ([provider messages] (run-chan provider messages nil))
  ([provider messages {:keys [thread-id] :as opts}]
   (let [ch (async/chan)]
     (async/thread
       (let [{:keys [history added unplaced]} (drive! provider messages #(async/>!! ch %) opts)]
         (async/>!! ch {:type :run/done :history history :added added :unplaced unplaced})
         (async/close! ch)))
     ch)))
