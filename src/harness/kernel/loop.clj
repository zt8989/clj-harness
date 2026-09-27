(ns harness.kernel.loop
  "ReAct: stream a turn, run its tool calls concurrently, append the results, repeat.
  Terminates when a turn has no tool calls. No iteration cap, by design.
  Events leave the kernel over a core.async channel (run-chan)."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [harness.kernel.event :as ev]
            [harness.kernel.frames :as frames]
            [harness.kernel.hooks.dispatch :as hook]
            [harness.kernel.llm :as llm]
            [harness.kernel.stop :as stop]
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
  puts it in the request body and the START EVENT records its SIGNATURE -- the name
  set, the count, and (when the edge's :tool-signature is in OPTS) the byte size.
  That is what makes the signature 'the table that WENT OUT' rather than a second
  resolution that happens to agree -- and it is why the resolve lives in this function
  rather than in the provider layer. The TABLE itself is not recorded: it is runtime
  configuration, repeated byte-for-byte on every call, and the signature is what a
  reader deciding 'is this the same envelope' actually needs (`harness.kernel.tools`).

  A REFUSAL FOR LENGTH IS RECOVERABLE, and this is the only place that can act on one. The
  vendor refused the WHOLE request, so before the run ends the history is handed to
  `:on-overflow` for an AGGRESSIVE compaction, and -- when it comes back SHORTER -- the call
  is made again, in the same turn, before any terminal frame. `:overflow-retries` caps the
  attempts (0 disables it); `:halted?` is asked first, so a run somebody stopped is not
  prolonged by a retry. A refusal this layer does not RECOGNISE, or a recovery that shortened
  nothing, is rethrown UNTOUCHED: the vendor's own words are what the run reports."
  [provider history emit thread-id {:keys [on-overflow recoveries halted? tool-signature
                                          idle-timeout-ms]
                                    :or {recoveries 1}
                                    :as _opts}]
  (loop [attempt 0]
    (let [specs   (tools/specs thread-id)
          ;; THE SIGNATURE, NOT THE TABLE (`harness.kernel.event/model-start`). It is
          ;; computed HERE, once, from the very array that goes into the request body,
          ;; so a reader can never be shown a signature of a table other than the one
          ;; that went out. The caller may hand in its own (:tool-signature) to add the
          ;; byte measure the kernel does not own -- the edge does, and does.
          _       (emit (ev/model-start provider ((or tool-signature tools/default-signature) specs)))
          outcome (try
                    (let [{:keys [message telemetry]}
                          ;; THE IDLE GUARD RIDES THE PROVIDER MAP, and this is the one
                          ;; place that puts it there for a run: `harness.kernel.llm` guards
                          ;; the reading of a stream it was told the deadline for, and a
                          ;; provider map carrying no deadline is not guarded at all (see
                          ;; `harness.kernel.llm/default-idle-timeout-ms`). Handed in with
                          ;; the tool table, for the same reason: this is where the request
                          ;; this call will send is assembled.
                          (llm/stream! (assoc provider :tools specs
                                                    :idle-timeout-ms idle-timeout-ms)
                                       @history emit thread-id)]
                      (emit (ev/model-end telemetry))
                      {:message message})
                    (catch Throwable t
                      (emit (ev/model-end nil))
                      {:error t}))]
      (if-some [t (:error outcome)]
        (if (and (llm/context-overflow? t)
                 on-overflow
                 (not (halted?))
                 (< attempt recoveries))
          (if-some [shorter (try (on-overflow @history t) (catch Throwable _ nil))]
            (do (reset! history (vec shorter))
                (recur (inc attempt)))
            (throw t))
          (throw t))
        (:message outcome)))))

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
;; ------------------------------------------ stopping a run somebody asked to stop

(defn- stop-sentence
  "WHAT A RUN THAT WAS STOPPED BY A PERSON SAYS IN ITS TERMINAL FRAME.

  IT NAMES THE PERSON, because the record's reader has to be able to tell an ending
  somebody asked for from one that went wrong -- that difference is the whole reason
  the stop is a server-side request rather than a browser that hung up (ticket 09 of
  `.scratch/session-after-refresh`)."
  [] (str "this run was stopped: a person pressed stop on this conversation while the run"
       " was going, so it was cut off without finishing"))

(defn- stopped!
  "End this run because its stop switch was rung -- BY THROWING, so the one `catch`
  that already turns a dying run into a terminal frame does this too.

  A STOPPED RUN DID NOT FINISH, and that is why it ends like a failure on the wire: the
  vocabulary has two terminals and RUN_FINISHED would be the one line in the record
  that lies. The reason (`stop-sentence`) is what makes it readable as a stop rather
  than a fault, and the process log's `run/terminal` line carries it verbatim."
  [] (throw (ex-info (stop-sentence) {:stopped true})))

(defn- timeout-sentence
  "WHAT A MODEL CALL THAT WENT QUIET FOR TOO LONG IS REFUSED WITH -- the sentence the run
  ends on, and it names WHICH of the two endings this was.

  A TIMEOUT WITH NOTHING EMITTED IS RETRIABLE, and this is only read once the retries have
  run out; a timeout AFTER the call had already put something on the wire is not, and the
  second sentence says why rather than leaving a person to guess at the difference. The
  reason is not tidiness: a client that has been shown half an answer would be shown the
  whole thing twice if the call were made again -- the retry appends to the message the
  first attempt opened."
  [idle-ms attempt limit emitted?]
  (if emitted?
    (str "the model went quiet" (when idle-ms (str " for " idle-ms " ms"))
         " (attempt " attempt ") after it had already answered part of this turn, so the"
         " run was cut off: that half-written answer cannot be retried without the client"
         " being shown it twice")
    (str "the model produced no data" (when idle-ms (str " for " idle-ms " ms"))
         " on " attempt " attempt" (when (< 1 attempt) "s") ", and the " limit " retr"
         (if (= 1 limit) "y" "ies") " this session allows were spent, so the run was cut"
         " off")))

(defn- cut-off-call!
  "Close the model-call SEGMENT of a call that was cut off while it was still running, and
  nothing else.

  THE RECORD PAIRS `:model/start` WITH `:model/end` BY ORDER -- the nth start of a run is
  that run's nth call (`harness.kernel.event/model-start`) -- so an abandoned attempt that
  never got to write its own end would leave the NEXT attempt's end paired with THIS
  attempt's start, and the record would attribute one call's telemetry to another. An
  empty telemetry is the honest end of a call that reported nothing, which is exactly what
  a call cut off mid-stream is.

  IT IS NOT ALWAYS NEEDED: a timeout that arrived as a THROWABLE from the provider layer
  (the read side's own guard) went through `model-call!`'s catch, which already emitted the
  end, and `end-seen?` is what keeps this from writing a second one."
  [end-seen? emit]
  (when-not @end-seen?
    (emit (ev/model-end nil))))

(defn- await-call
  "Wait for one of PORTS (channels each carrying a call's answer), or for this run's STOP
  SWITCH to be rung, or -- when IDLE is given -- for the thing being waited on to go QUIET
  for `:idle-ms`. Answers {:port .. :value .. :stopped? .. :idle? ..}.

  THE PORT IS THE ANSWER, not the value: a rung switch delivers nil, and so does a
  channel whose call reported nothing -- the port is the only thing that tells the two
  apart. `:wake` is nil for a run that was handed no switch (an offline replay, a
  test), and then this is `alts!!` over the call channels and nothing else.

  IDLE IS {:idle-ms ms :last-at <an atom of ms>}, A LIVE DEADLINE RATHER THAN A FIXED
  TIMEOUT, and that is the whole reason the arithmetic is here instead of one call to
  `async/timeout`: what is being watched is SILENCE, so the deadline moves with every
  event the call emits (`:last-at` is what the emitting wrapper updates) and the remaining
  time is recomputed each pass. A stream that answers every 400 ms runs forever under a
  500 ms deadline; a fixed timeout would cut it off.

  A TIMER THAT LOSES THE RACE IS ABANDONED, not cancelled -- core.async's timers cannot be
  withdrawn, and a fired timer's value goes to a channel nobody holds. Nothing is lost by
  it: the next pass arms a fresh one from `:last-at`.

  AN UNARMED CLOCK IS NOT A DEADLINE, which is what `@:last-at` being nil means: the call
  has not reached the point where silence would be evidence of anything yet (see `alive`),
  so there is nothing to measure. IT IS STILL WATCHED FOR, at `poll-ms`, because the clock
  can arm LATER -- a wait that installed no timer at all while unarmed would sleep through
  the arming and never fire (measured: it hung exactly there). The poll is a timer, not a
  spin, and it runs only in the window before the request goes out; the moment the clock
  arms, the next pass measures the real remaining silence from it."
  ([ports cancel] (await-call ports cancel nil))
  ([ports cancel idle]
   (let [wake    (:wake cancel)
         idle-ms (:idle-ms idle)
         last-at (:last-at idle)
         ;; HOW OFTEN AN UNARMED CLOCK IS LOOKED AT. Small enough that the deadline is
         ;; met to within a few tens of milliseconds of the request going out, and it
         ;; never exceeds the deadline itself (a session that asked for 20 ms gets 20).
         poll-ms 50]
     (loop []
       ;; `some->` AND NOT A BARE `@`: a caller that handed in no IDLE at all (the drain
       ;; over a turn's tool calls, which is not a model call) has no clock to read, and
       ;; dereferencing nil is not 'no value' -- it is a NullPointerException out of
       ;; `clojure.core/deref`'s future branch.
       (let [seen-at (some-> last-at deref)
             timer   (when idle-ms
                       (async/timeout
                        (if seen-at
                          (max 1 (- (long idle-ms)
                                    (- (System/currentTimeMillis) (long seen-at))))
                          (min (long idle-ms) poll-ms))))
             [value port] (async/alts!! (cond-> (vec ports)
                                          (some? wake)  (conj wake)
                                          (some? timer) (conj timer)))]
         (cond
           (and (some? timer) (identical? port timer))
           ;; THE CLOCK IS READ AGAIN RATHER THAN KEPT: the call may have armed it (or
           ;; said something) between the timer firing and this line, in which case this
           ;; pass measured a window that no longer exists and the next one is the right
           ;; one. An arm that happened mid-poll falls through here too, and the pass
           ;; after it measures from the real one.
           (let [seen-at (some-> last-at deref)]
             (if (and seen-at
                      (> (- (System/currentTimeMillis) (long seen-at)) (long idle-ms)))
               {:value nil :port nil :idle? true}
               (recur)))

           :else
           {:value    value
            :port     port
            :stopped? (and (some? wake) (identical? port wake))}))))))

(def ^:private arrived
  "The kernel events that mean THE VENDOR SAID SOMETHING -- the ones a client is handed a
  frame for. This is the whole input to the idle guard's retry decision (`emitted?` in
  `model-call-watched`), and it is spelled out rather than asked as 'any event at all'
  because two of the events on this channel are emitted BY THE LOOP around the call:
  `:model/start` before the request, and `:model/end` in its catch. Counting those would
  make every silent call look like one that had already answered -- and an answer already
  on the client's screen is exactly what forbids a retry."
  #{:text/delta :reasoning/delta :tool/call})

(def ^:private alive
  "The kernel events that ARM AND RE-ARM the idle deadline: the call's own boundaries plus
  everything the vendor says inside them.

  THE DEADLINE MUST NOT START BEFORE THE CALL DOES, and that is not a detail -- it is the
  difference between watching a vendor and watching the harness. `model-call!` resolves the
  session's TOOL TABLE before it emits `:model/start`, and resolving that table is what
  STARTS THE SESSION'S MCP SERVERS (`harness.cap.mcp`): a server whose command does not
  exist takes as long as the OS needs to say so, which is over half a second on a slow
  machine and has nothing to do with the vendor. Counting that window would cut off a call
  that had not been made yet -- measured: a run whose broken MCP server took 600 ms to fail
  ended on 'the model produced no data for 500 ms' before a single byte was sent.
  (`mcp-wired-test/a-server-that-will-not-start-does-not-break-the-run`.)

  SO AN UNARMED CLOCK IS NO CLOCK (`await-call`), and the arm is `:model/start` -- the
  event that says the request is about to go out. From there the deadline covers exactly
  what it is for: connection setup, the vendor's prefill, and every silence between two
  tokens of the stream."
  #{:model/start :model/end :text/delta :reasoning/delta :tool/call})

(defn- model-call-watched
  "One model call, watched from the outside: started on a thread of its own so a run that
  gets stopped does not have to WAIT for a vendor that is still streaming, and so a vendor
  that goes QUIET can be given up on and tried again.

  THE CALL RUNS ON ITS OWN THREAD and the loop listens to it, to the stop switch and to the
  idle deadline at once. A call that is ABANDONED -- the switch was rung, or the deadline
  passed -- keeps its HTTP request until the vendor finishes; there is no process to kill
  for a vendor, which ticket 08 of `.scratch/session-after-refresh` states as the boundary.
  What this layer CAN do is stop listening, and that is what the gate below is: every frame
  the call emits goes through a wrapper that stops forwarding the moment the attempt is
  over, so an abandoned attempt cannot write frames into a run that has moved on to its
  next try (or already sent its terminal).

  A THROW IS CARRIED AS A VALUE (`t`) rather than escaping the thread: a thread's
  exception goes nowhere, and the loop is what has to turn it into `:run/error` on the one
  path that already does.

  THE IDLE GUARD IS ASKED TWICE, DELIBERATELY, and the two answers are one fact seen from
  two sides. The PROVIDER layer's own guard (`harness.kernel.llm/idle-guarded-lines`) is what
  actually DISCONNECTS a real vendor -- it closes the response body and throws, and that
  throwable arrives here through the reply channel. The deadline HERE is what covers a call
  that says nothing at all (a scripted provider mid-step, a vendor whose stream is up but
  silent): it wins the `alts!!`, so the attempt is abandoned rather than waited on forever.
  Whichever side notices first the outcome is the same event and the same decision, so a
  race between them is not a race at all.

  AND THE DEADLINE IS ARMED BY THE CALL, NOT BY THE ATTEMPT: it starts counting at
  `:model/start` and nowhere earlier, because everything before that line belongs to the
  harness rather than to the vendor -- resolving the session's tool table is what starts
  its MCP servers, and a server that takes 600 ms to fail would otherwise be read as a
  vendor that had gone quiet (`alive`).

  AND WHAT THE DECISION IS, in one place: a timeout that emitted NOTHING is retried while
  the budget lasts; a timeout that emitted SOMETHING ends the run, because the half-written
  answer is already on the client's screen (`timeout-sentence`); an ordinary failure is
  rethrown untouched; and a stop still wins over all of it.

  OPTS carries the overflow recovery through to the call -- `:on-overflow` and the retry
  ceiling -- plus this guard's two knobs (`:idle-timeout-ms` and `:idle-timeout-retries`),
  neither of which the kernel reads from anywhere: the edge resolves them from harness.edn,
  and a run handed neither is not guarded at all. `:halted?` is supplied HERE from the run's
  own stop switch, so a run a person stopped is not prolonged by a retry that arrives after
  the press, and `:on-pressure` rides the same way (see `drive!`)."
  [provider history emit thread-id cancel opts]
  (let [idle-ms (:idle-timeout-ms opts)
        limit   (long (or (:idle-timeout-retries opts) 0))]
    (loop [attempt 1]
      (let [ch        (async/chan 1)
            ;; UNARMED UNTIL THE CALL BEGINS -- nil, and not 'now': see `alive`. The
            ;; deadline must not count the vendor's own setup work.
            last-at   (atom nil)
            seen?     (atom false)
            end-seen? (atom false)
            ;; :open UNTIL THIS ATTEMPT IS OVER -- see the docstring. A closed gate keeps
            ;; the LAST-AT it was handed, which nothing reads again.
            gate      (atom :open)
            call-emit (fn [e]
                        (when (and (= :open @gate) (not (stop/rung? cancel)))
                          (when (contains? alive (:type e))
                            (reset! last-at (System/currentTimeMillis)))
                          ;; WHAT COUNTS AS 'SAID SOMETHING' IS WHAT THE VENDOR SAID, not
                          ;; what this layer says around it: `:model/start` and `:model/end`
                          ;; are emitted HERE (before the request and in its catch) and the
                          ;; one thing they must never do is make a silent call look like one
                          ;; that had already answered -- a retry would then be refused for a
                          ;; message the client never saw.
                          (when (contains? arrived (:type e)) (reset! seen? true))
                          (when (= :model/end (:type e)) (reset! end-seen? true))
                          (emit e)))
            _         (async/thread
                        (async/>!! ch (try (model-call! provider history call-emit thread-id
                                                        (assoc opts :halted? #(stop/rung? cancel)))
                                           (catch Throwable t t))))
            answer    (await-call [ch] cancel {:idle-ms idle-ms :last-at last-at})]
        (when (:stopped? answer) (stopped!))
        (let [value    (:value answer)
              timed?   (or (:idle? answer) (llm/idle-timeout? value))
              emitted? @seen?]
          (cond
            ;; NOTHING WAS SAID AND THERE IS BUDGET LEFT: cut the attempt off and try
            ;; again. Its end is written here because its thread is still running.
            (and timed? (not emitted?) (< attempt (+ 1 limit)))
            (do (reset! gate :closed)
                (cut-off-call! end-seen? emit)
                (emit (ev/model-timeout idle-ms attempt limit true false))
                (recur (inc attempt)))

            ;; EITHER the budget is spent or the answer had already begun: the run is over,
            ;; and the frame says which of the two it was before the terminal does.
            timed?
            (do (reset! gate :closed)
                (cut-off-call! end-seen? emit)
                (emit (ev/model-timeout idle-ms attempt limit false emitted?))
                (throw (ex-info (timeout-sentence idle-ms attempt limit emitted?)
                                {:llm/idle-timeout true :idle-ms idle-ms
                                 :attempt attempt :limit limit :emitted emitted?})))

            (instance? Throwable value) (throw value)

            :else value))))))

(defn- relieve-pressure!
  "Before a call goes out: ask the edge's `:on-pressure` for a SHORTER history, and take it
  or keep the one we have. True when it was taken, so the caller can re-apply its own
  pre-LLM step to it.

  SYMMETRIC WITH `:on-overflow`, and for the same reason -- the kernel knows neither what
  `harness.edge.pressure` measures nor what a compaction does to a record. `:on-overflow` is
  the relief the VENDOR's refusal triggers; this is the same relief, asked BEFORE the refusal
  instead of after it (`.scratch/compaction-shape` ticket 04: a run grew from 62% of its
  window to over 100% in twelve minutes, and nothing looked again until the vendor said no).

  WHAT IT IS HANDED IS THE ARRAY ABOUT TO GO OUT, so an edge that measures it measures a
  REQUEST rather than a record it hopes agrees with one.

  TWO REFUSALS OF ITS OWN, because a shorter array is a CLAIM and this is where it would be
  lived with:
    * UNCHANGED OR LONGER -> nothing is taken. Shorter is the edge's measurement -- only it
      has a token estimator -- and swapping an array for itself is noise.
    * A CALL LEFT UNANSWERED -> nothing is taken. A view rebuilt from the record can be a
      beat behind the turn in flight, and a history whose `tool_calls` have no results is
      refused by every OpenAI-shaped vendor: a self-inflicted 400 is worse than a big request.
  AND A METER MUST NEVER KILL A RUN: anything the edge throws is swallowed and the call goes
  out as it stood."
  [history on-pressure]
  (boolean
   (when on-pressure
     (when-some [shorter (try (on-pressure @history) (catch Throwable _ nil))]
       (let [now @history
             new (vec shorter)]
         (when (and (not= new now)
                    (empty? (llm/unanswered-tool-calls new)))
           (reset! history new)
           true))))))
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

  OPTS may carry :cancel -- ONE RUN'S STOP SWITCH, a `harness.kernel.stop/handle`. With
  one, a person can stop this run from outside it: the switch is read at every step
  boundary, the model call and the turn's tool calls are each WAITED ON BESIDE it, and
  a run that finds it rung stops where it is. The calls still in flight are stopped
  (a command's process tree dies), each of them is answered with `frames/cut-off-result`
  so the record keeps no open call, and the run ends on `:run/error` with a reason that
  says a person stopped it -- a stopped run DID NOT FINISH, and the wire's other
  terminal would be a line that lies. Without a switch (an offline replay, a test)
  there is no cancellation path at all and the loop behaves exactly as it did before.

  OPTS may carry :on-pressure -- a question asked BEFORE EVERY MODEL CALL, not only at the
  run's start: 'is this the request to send, or is there a shorter one?'. It is handed the
  array about to go out and answers a shorter history or nil (`relieve-pressure!`), which is
  the same contract as `:on-overflow` -- the relief a vendor's refusal triggers, asked one
  step EARLIER. Without it nothing is asked and the loop behaves exactly as it did before.
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
  [provider messages emit {:keys [thread-id resume before-llm cancel on-overflow
                                  overflow-retries on-tool-result tool-signature
                                  on-pressure
                                  ;; THE IDLE GUARD'S TWO KNOBS, resolved by the EDGE from
                                  ;; harness.edn (`harness.edge.llm-timeout`) and handed in
                                  ;; exactly the way `:overflow-retries` is -- the kernel
                                  ;; reads no configuration, and a run handed neither is
                                  ;; not guarded at all.
                                  idle-timeout-ms idle-timeout-retries]
                            :as _opts}]
  (let [;; THE HISTORY IS MADE VENDOR-LEGAL BEFORE ANYTHING READS IT. A record can deliver an
        ;; answer to a call LATE -- the closing repair a cut-off run's log gets is APPENDED,
        ;; after whatever the client recorded meanwhile -- and folded in file order that
        ;; answer sits behind later messages, where it answers nothing and a vendor refuses
        ;; the whole request. `llm/adjacent-answers` moves a RECORDED answer behind the call
        ;; it answers (the placement `answer!` makes for a replay); a well-shaped history
        ;; comes back unchanged, and a call with no result anywhere is still refused below.
        history (atom (vec (llm/adjacent-answers messages)))
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
        ;; HOW MANY TIMES A CALL THE VENDOR REFUSED FOR LENGTH MAY BE RETRIED (see
        ;; `model-call!`). Read from the edge's config; an offline run hands in nothing and
        ;; gets the default, and 0 turns the recovery off entirely.
        retries (long (or overflow-retries 1))
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
                      ;; A STOP THAT ARRIVED BETWEEN STEPS IS HONOURED HERE, before anything
                      ;; new is asked of a provider.
                      _         (when (stop/rung? cancel) (stopped!))
                      ;; IS THIS THE REQUEST TO SEND? Asked before EVERY call -- not only at
                      ;; the run's start -- because a conversation grows BETWEEN calls (one tool
                      ;; result can be enormous), and the vendor's refusal for length arrives
                      ;; only after a request was assembled and paid for. What the edge hands
                      ;; back is the CONVERSATION and not this run's per-call decorations, so the
                      ;; step is applied to it again; `prepare` derives what is missing (a skill
                      ;; body, a job's ending) rather than duplicating what is there, and
                      ;; NOTHING is re-reported: these messages are already in `added` and their
                      ;; `context/injected` frames have already gone out.
                      _         (when (relieve-pressure! history on-pressure)
                                  (swap! history prepare thread-id))
                      ;; THE MODEL CALL IS THE LONG ONE, so it runs on a thread of its own
                      ;; and this waits on BOTH it and the switch: a stop does not have to
                      ;; wait for a vendor that is still talking.
                      ;; THE MODEL CALL IS THE LONG ONE, and `model-call-watched` is
                      ;; what makes the idle guard and the stop switch both able to cut it
                      ;; short -- it owns the attempt loop, the abandoned attempt's gate and
                      ;; the stop check, so this stays one line.
                      assistant (model-call-watched provider history emit thread-id cancel
                                                    {:on-overflow         on-overflow
                                                     :recoveries          retries
                                                     :idle-timeout-ms     idle-timeout-ms
                                                     :idle-timeout-retries idle-timeout-retries
                                                     :tool-signature      tool-signature})
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
                          ;; ONE SLOT PER CALL is where a call puts HOW TO STOP WHAT IT
                          ;; STARTED (a command's process tree, today -- see
                          ;; `harness.kernel.tools/*stop*` and `cap.tools`'s bash). The loop
                          ;; hands one down and reads them all when it has to stop.
                          stops (atom {})
                          ;; WHAT A CALL SAYS ONCE THE SWITCH IS RUNG IS DROPPED. An
                          ;; abandoned call can finish later on its own thread, and frames
                          ;; arriving after this run's terminal would be frames after the
                          ;; end of the run. The ENDING the record needs for such a call is
                          ;; written by the loop below (the cut-off result), not by it.
                          call-emit (fn [e] (when-not (stop/rung? cancel) (emit e)))
                          chs  (mapv (fn [{:keys [id] :as call}]
                                       (let [ch (async/chan 1)
                                             slot (atom nil)]
                                         (swap! stops assoc id slot)
                                         (async/thread
                                           ;; EMIT doubles as the lifecycle
                                           ;; on-phase: the seam's pre/execute/post
                                           ;; events ride the same channel out to
                                           ;; the edge.
                                           (binding [tools/*stop* slot]
                                             (let [{:keys [content error parked]}
                                                   (tools/run! call thread-id call-emit)
                                                   ;; A JUST-PRODUCED RESULT MAY BE SPILLED BEFORE IT
                                                   ;; ENTERS THE HISTORY (`harness.cap.spill`): the
                                                   ;; replacement then rides BOTH the emitted frame and
                                                   ;; the tool message, so the model view never holds the
                                                   ;; giant text. An error or a parked call is not spilled
                                                   ;; -- there is nothing to retrieve -- and the hook is
                                                   ;; never handed one.
                                                   content (if (or error parked)
                                                             content
                                                             ((or on-tool-result (fn [_ c] c))
                                                              (get-in call [:function :name]) content))]
                                               (async/>!! ch {:id id :content content
                                                              :error error :parked parked}))))
                                         ch))
                                     calls)
                          done (atom {})
                          ;; DRAIN THE TURN, AND BE WILLING TO WALK AWAY FROM IT: a stop
                          ;; does not have to wait for a command that is still running --
                          ;; the call is killed below instead, and its answer is the
                          ;; cut-off sentence rather than a result nobody wants.
                          outcome (loop [left (count chs)]
                                    (if (zero? left)
                                      :answered
                                      (let [{:keys [value stopped?]} (await-call chs cancel nil)]
                                        (if stopped?
                                          :stopped
                                          (do (when (nil? (:parked value))
                                                ;; THE RESULT IS EMITTED PLAINLY: it
                                                ;; really arrived before the stop, and
                                                ;; a result the record does not carry
                                                ;; is an open call.
                                                (emit (ev/tool-result (:id value)
                                                                      (:content value)
                                                                      (:error value))))
                                              (swap! done assoc (:id value) value)
                                              (recur (dec left)))))))]
                      ;; ...and forgotten once every call has answered, so the plan
                      ;; does not accumulate for the life of the process. A STOP GOES
                      ;; THROUGH HERE TOO: the plan is this turn's, and this turn is over.
                      (tools/forget-turn! thread-id token)
                      (when (= :stopped outcome)
                        ;; STOP WHAT IS STILL RUNNING. A command's tree is what has to
                        ;; die -- killing the shell we hold leaves the command itself
                        ;; running with nobody attached (`harness.infra.shell`'s own note).
                        (doseq [[_ slot] @stops :when (some? @slot)]
                          (try (@slot) (catch Throwable _ nil)))
                        ;; AND ANSWER EVERY CALL THAT HAS NO RESULT. An assistant message
                        ;; whose tool_calls has no answering tool message is a shape the
                        ;; vendors refuse, so a stop must not leave one behind -- the words
                        ;; are `frames/cut-off-result`, the same sentence a repaired log
                        ;; gives a call that never returned. THE ANSWER GOES TO THE RECORD AND
                        ;; NOT TO THE CLIENT (`ev/cut-off-result`, and the edge's dispatch):
                        ;; the record must be complete for the next run, and the page that
                        ;; pressed stop must see the call it asked to stop as CANCELLED rather
                        ;; than as a call that quietly finished.
                        (doseq [{:keys [id]} calls :when (not (contains? @done id))]
                          (emit (ev/cut-off-result id (frames/cut-off-result))))
                        (stopped!))
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
        ;; A STOP IS A TERMINAL OF ITS OWN NAME (`stopped!`), and it stays a RUN_ERROR on
        ;; the wire -- what the separate event buys is the code the frame carries, which
        ;; is how a CLIENT draws a stop as a stop instead of a failure.
        (if (:stopped (ex-data t))
          (emit (ev/run-stopped (ex-message t)))
          (emit (ev/run-error (ex-message t))))))
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
