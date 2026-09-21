(ns harness.edge.trajectory
  "What a session's model actually saw, per turn, folded from its RECORD.

  SIBLING OF harness.edge.replay AND harness.edge.stats, all three reading the same
  jsonl -- and the three read DIFFERENT HALVES of it on purpose:

    - `replay` reads `input` + `event` and rebuilds the CONVERSATION (what the client
      holds, what a rebuild hands back);
    - `stats` reads `input` + the `model/start` / `model/end` pair and answers in
      NUMBERS (turns, calls, tokens, durations);
    - this namespace reads `input` + `message` + `tools/*` and rebuilds WHAT THE MODEL
      SAW: the frozen system message, the session's opening in front of the question,
      whatever a run derived for itself behind it, every user message, and each tool call
      with its arguments and result.

  The third one is not a nicer rendering of the first two: the conversation reader
  never sees the system message or the injected context (the client never holds them),
  and the numbers reader never sees a single message. A reader who wants 'what did the
  model have in front of it' has to fold the `message` lines, and those are written
  VERBATIM by the edge -- one line per provider-shaped message, both the side submitted
  to the first call of a run and the side the kernel appended.

  WHAT A TURN IS (CONTEXT.md): one user message and ALL the output it caused. Here that
  has a second edge worth naming -- AN `input` RECORD IS NOT A TURN BOUNDARY. A
  park/resume writes a second `input` with the same runId and NO new user message (the
  client restates its whole history), so it continues the turn the human parked. The
  boundary is a user message ID that has not been seen before -- ids, not content,
  because the same text sent twice is two turns and because the system message changes
  between runs. `harness.edge.stats` counts turns on exactly this rule; this namespace
  groups the actual items by it.

  THE FOLD IS A PURE FUNCTION OVER RECORDS (records->trajectory), for the same reason
  stats' is: what can be asserted is the interesting part. The file walk (log-trajectory)
  does nothing but the I/O, and THE DIRECTORY IS THE CALLER'S -- this namespace never
  learns where a process keeps its home.

  IT TOLERATES A LIVE LOG. A session is most likely to be looked at while it is running,
  so a half-written last line is dropped (stats/read-records) and a last run with no
  terminal frame is a FLAG, not a refusal -- the opposite of `replay`'s choice, which
  refuses, because half a conversation handed to a client is worse than no answer. Here
  the honest answer is 'this is as far as it got'.

  ORDER IS THE RECORD'S ORDER, not the reference screenshot's. The session's opening --
  its instruction files and skills catalog -- enters the conversation when it is born
  (`.scratch/session-opening`), so it stands in FRONT of the question on every run after
  that; the run's own context entry is a user message behind the question, and skill
  bodies land where the call that wanted them did. All of that is what the model saw, so
  it is what this returns."
  (:require [clojure.string :as str]
            [harness.edge.ag-ui :as ag]
            [harness.edge.stats :as stats]))

;; ------------------------------------------------------------------- the runs

(defn run-segments
  "RECORDS split into runs, in order:
  [{:input <record> :submitted [msg…] :returned [msg…]}].

  A run is OPENED by an `input` record. Its SUBMITTED messages are the `message` lines
  that come before its first `event`; every `message` line after that is the RETURNED
  side, because the kernel writes its tail at :run/done, i.e. after the terminal frame
  (the same fact harness.edge.replay's docstring rests on).

  A `message` line before any `input` belongs to no run and is dropped: there is no turn
  it could be shown under, and inventing one would put a message on screen that no model
  call ever had in front of it.

  PUBLIC, like `stats/incomplete?` and `stats/user-ids`, because a SECOND reader needs
  exactly this split: harness.edge.context counts the messages of the run the last
  reporting call belongs to (the system message against everything else), and 'which
  records are one run, and which side of it is a message on' is one rule -- a second
  implementation of it would be a second chance to disagree about where a run starts."
  [records]
  (loop [[record & more] records
         current         nil
         acc             []]
    (cond
      (nil? record)
      (cond-> acc current (conj current))

      (= "input" (:kind record))
      (recur more {:input record :submitted [] :returned [] :calls [] :streaming false}
             (cond-> acc current (conj current)))

      (= "message" (:kind record))
      (recur more
             (when current
               (if (:streaming current)
                 (update current :returned conj (:payload record))
                 (update current :submitted conj (:payload record))))
             acc)

      (= "event" (:kind record))
      (recur more (when current (assoc current :streaming true)) acc)

      (= "model/start" (:kind record))
      (recur more (when current (update current :calls conj record)) acc)

      (= "model/end" (:kind record))
      (recur more (when current (update current :calls conj record)) acc)

      :else
      (recur more current acc))))

;; -------------------------------------------------------------------- aligning

(defn- shape
  "A message reduced to the two things the submitted side and the client's side can
  agree on: role and TEXT. Everything else differs by construction -- the client's copy
  carries an :id, the provider's does not.

  TEXT AND NOT THE RAW CONTENT, because the two sides differ by a card: the record's
  conversation carries an injected message's `data` part (that is what the page draws)
  and the submitted side never does (`harness.edge.sessions/model-view` takes it out).
  Comparing the raw contents would make the sides disagree about every message the
  session's opening consists of, the match would fail, and the alignment would degrade
  to 'nothing matched' -- filing the person's own question as something the server
  injected. `ag_ui/message-text` is the reading both sides can answer."
  [message]
  [(:role message) (ag/message-text message)])

(defn- align
  "Where the client's own messages sit inside a run's submitted block:
  {:before <opening blocks> :own <the client's messages>
   :between <injections that landed inside them> :after <what follows them>}.

  A run's submitted block is [system] + [the conversation this run continued, its own
  entries included] + [what the run derived for itself] (see
  `harness.edge.ag-ui/inbound`). The client's messages are matched IN ORDER and by
  role-and-content, and the ends fall out: what precedes the first match is what was
  injected before the conversation (the layout every log written before 2026-09-18 has,
  and every log since `.scratch/session-opening`: the opening is in front), what lies
  BETWEEN two matches was injected too (a `/name` body in one of those older logs), and
  what follows the last match is what the run put after the client's own words -- a skill
  body, a job's ending.

  THE MATCH IS NOT CONTIGUOUS, which is what keeps those older logs readable: a body
  spliced between two retransmitted messages makes a contiguous match find nothing there,
  and that would file the whole block as 'the run opened with this' -- the one answer this
  view may not give, because the client's own words would be drawn as something the server
  injected.

  AN EMPTY CLIENT LIST MAKES THE WHOLE REST 'BEFORE': with nothing to match, the extra
  user messages are the blocks, not trailing context -- the alternative would file a
  session's instruction files under 'this run added it'.

  NO MATCH AT ALL (a multimodal message, whose provider shape differs from the client's)
  degrades to the same answer, and the user messages then come from the `input` payload
  instead -- which is where their ids are anyway."
  [submitted ins]
  (let [n (count ins)]
    (if (zero? n)
      {:before submitted :own [] :between [] :after []}
      (let [idx (loop [i 0 k 0 found []]
                  (cond
                    (= k n)                 found
                    (= i (count submitted)) nil
                    (= (shape (nth submitted i)) (shape (nth ins k)))
                    (recur (inc i) (inc k) (conj found i))
                    :else                   (recur (inc i) k found)))]
        (if (nil? idx)
          {:before submitted :own [] :between [] :after []}
          (let [hit     (set idx)
                first-i (first idx)
                last-i  (last idx)]
            {:before  (vec (subvec submitted 0 first-i))
             :own     (mapv #(nth submitted %) idx)
             :between (vec (keep-indexed (fn [i m]
                                           (when (and (< first-i i) (< i last-i)
                                                      (not (contains? hit i)))
                                             m))
                                         submitted))
             :after   (vec (subvec submitted (inc last-i)))}))))))

;; ------------------------------------------------------------------ what ran

(defn- tool-lifecycles
  "toolCallId -> {:arrivedAt :resumedAt :executedAt :closedAt :outcome :error}, from the
  audit lines the seam and the kernel leave behind.

  THE FOUR MARKS ARE FOUR DIFFERENT MOMENTS, and the reason this function exists is that
  their NAMES in the record do not say which is which:

    `tools/pre-execute`  THE CALL ARRIVED at the seam. Its :outcome says what the seam
                         decided -- absent means pass, because the writer spells out only
                         the exceptions. A SECOND one for the same id is the park being
                         resumed, after a human decided.
    `tools/execute`      THE CALL LEFT EXECUTION -- this is when the tool FINISHED, not
                         when it started (see harness.kernel.event/tool-executed). So the
                         span of a tool call is arrivedAt -> executedAt, and reading this
                         line as a start makes every tool look instantaneous.
    `tools/post-execute` THE SEAM IS DONE with it: bookkeeping, milliseconds after the
                         execution ended.

  A VETOED CALL HAS NO executedAt AT ALL -- it never ran, which is not the same statement
  as 'it ran in zero seconds'. A PARKED ONE has :resumedAt, and the gap between it and
  :arrivedAt is the time a person spent deciding: real time, and NOT the tool's own."
  [records]
  (reduce (fn [acc {:keys [kind payload ts]}]
            (let [id    (:toolCallId payload)
                  seen  (get acc id)]
              (case kind
                "tools/pre-execute"
                (-> acc
                    (update-in [id :arrivedAt] #(or % ts))
                    (assoc-in [id :outcome] (or (:outcome payload) "pass"))
                    ;; A second arrival for the same id is the resume, which is what marks
                    ;; the park's end. Later ones overwrite: the last word is the one that
                    ;; stuck.
                    (cond-> (some? (:arrivedAt seen)) (assoc-in [id :resumedAt] ts)))

                "tools/execute"
                (-> acc
                    (assoc-in [id :executedAt] ts)
                    (assoc-in [id :error] (:error payload)))

                "tools/post-execute"
                (assoc-in acc [id :closedAt] ts)

                acc)))
          {}
          records))

(defn- call-index
  "toolCallId -> {:name … :argsText …}, from every assistant message in the record.

  THE NAME AND THE ARGUMENTS LIVE ON THE CALL SIDE: the audit lines deliberately carry
  none (a tool line names the tool, never its body), and the tool message that answers
  carries only the result. So the pair is read from the assistant message here, once,
  instead of by every renderer that wants to show 'name {args}' beside a result."
  [records]
  (reduce (fn [acc {:keys [kind payload]}]
            (if (= "message" kind)
              (reduce (fn [acc {:keys [id function]}]
                        (assoc acc id {:name (:name function) :argsText (:arguments function)}))
                      acc
                      (:tool_calls payload))
              acc))
          {}
          records))

;; ---------------------------------------------------------------- the messages

(defn- text-of
  "A message's content as text. A string is itself; a content-parts vector keeps every
  text part and renders the rest as data, so a picture referenced in a message is not
  silently dropped from a view whose whole promise is 'this is what the model had'.

  AN INJECTED MESSAGE'S CARD IS THE ONE PART THAT IS NOT RENDERED: a `data` part is the
  screen's copy of an injection, never anything a provider read
  (`harness.edge.sessions/model-view` drops it for the model), so showing its EDN would
  put a card's own source in the middle of a view about what the model had."
  [content]
  (cond
    (string? content)     content
    (sequential? content) (str/join "\n" (keep (fn [p]
                                                 (cond
                                                   (some? (:text p))      (:text p)
                                                   (= "data" (:type p))   nil
                                                   :else                  (pr-str p)))
                                               content))
    (nil? content)        ""
    :else                 (str content)))

(defn- context-item
  "One injected user message -- the instruction files, the skills catalog, the run's own
  context, a skill body, the ending of a background job.

  ONE KIND, NO SOURCE. This used to say WHERE the block sat relative to the client's
  messages (`opening` for what was spliced before them, `run` for what came after), and
  that was a fact about a layout that no longer exists: every injection now lands after
  the client's messages, in one tail (see harness.edge.ag_ui/inbound -- system, question,
  context, skill context). A source that is the same for every item is a field that says
  nothing, and the block's own first line says what it is anyway."
  [message]
  {:kind "context" :text (text-of (:content message))})

(defn- append-last
  "Append ITEMS to the last turn. A log with no turn open yet gets nothing appended:
  there is no turn to show them under."
  [turns items]
  (if (empty? turns)
    turns
    (update-in turns [(dec (count turns)) :items] into items)))

(defn- add-context
  "Append injected context to the last turn, SKIPPING what the readback already carries
  byte for byte.

  AN INJECTION IS SHOWN ONCE FOR THE WHOLE SESSION, not once per turn. A run RESTATES
  its injections -- the instruction files and the catalog are re-read and re-rendered on
  every run (the server holds no session), and a skill body is re-derived on every turn
  its trigger is still in the history -- so 'show what the run carried' draws the same
  bytes under every turn, and a five-turn session reads as if the opening happened five
  times. That is a picture of a flow that
  did not happen, and it is the one thing this view may not do. So the question 'has
  this been shown?' is asked against the WHOLE trajectory.

  The CHANGE case falls out of that rather than being written beside it: bytes that
  changed are bytes nobody has seen, so they are shown again, in the turn where they
  changed -- the same rule the system item already follows (see
  `the-system-message-appears-again-only-when-it-changes`).

  The caller hands in one run's blocks in the record's order, so what this drops is
  exactly the repeats."
  [turns messages]
  (if (empty? turns)
    turns
    (let [i     (dec (count turns))
          turn  (get turns i)
          shown (into #{}
                      (comp (filter #(= "context" (:kind %))) (map :text))
                      (mapcat :items turns))
          items (mapv context-item messages)
          fresh (remove #(contains? shown (:text %)) items)]
      (assoc-in turns [i :items] (into (:items turn) fresh)))))

(defn- open-turn [turns]
  (conj turns {:index (inc (count turns)) :items [] :calls []}))

(defn- user-item
  "One user message, taking its text from the message the PROVIDER actually got (the
  aligned one) so that a shape difference cannot make the view disagree with the wire.
  When alignment failed, the client's own copy is the fallback -- same content by
  construction, minus any translation.

  `:at` IS THE `input` RECORD'S OWN `:ts`: when the client handed this message over. A
  user message is a MOMENT, not a span, and that moment is the only thing on the record
  that dates it -- the model call that follows is a different fact with its own marks."
  [ins own-of message at]
  (let [shown (get own-of (.indexOf ins message) message)]
    (cond-> {:kind "user" :id (:id message) :text (text-of (:content shown))}
      (some? at) (assoc :at at))))

(defn- one-call
  "ONE model call out of its two lines. START is the `model/start` record (or nil when
  an end arrived with no start of its own -- it happened, its start line is what is
  missing), END the `model/end` (or nil when the call never closed).

  WHAT THE VENDOR SAID IS TAKEN VERBATIM (`:usage` in its own keys, `:finish-reason`
  as it spelled it) and the total is derived on top; what the request carried is taken
  from the start's payload. Nothing is renamed and nothing is filled in."
  [start end]
  (let [payload (:payload end)
        usage   (:usage payload)]
    (cond-> {}
      (some? (:model (:payload start))) (:model (:payload start))
      (seq (:tools (:payload start)))   (assoc :tools (:tools (:payload start)))
      (some? start)                     (assoc :startedAt (:ts start))
      (some? end)                       (assoc :endedAt (:ts end))
      (seq usage)                       (assoc :usage usage)
      (some? (stats/tokens-of usage))   (assoc :tokens (stats/tokens-of usage))
      (some? (:finish-reason payload))  (assoc :finishReason (:finish-reason payload)))))

(defn- calls-of
  "The model calls one run made, in order, from its `model/start` / `model/end` lines:

    {:model … :tools […as sent…] :startedAt … :endedAt …
     :usage <the vendor's own map> :tokens <total or nil> :finishReason …}

  THE TWO LINES PAIR BY ORDER -- the nth start of a run is the nth call, exactly as
  harness.kernel.loop says (a counter in the record would be the same fact twice, and
  two copies drift). So a start with no end is a call that never reported (the log
  stops there, or it died mid-stream): it is a call, it has a start, and it has no
  usage -- which is why every field below is written only when there is something to
  write. `:tokens` comes from harness.edge.stats/tokens-of so that 'what counts as
  this call's tokens' has one spelling, and it is ABSENT (never 0) when the vendor
  reported nothing.

  A call with no `:tools` key is a call whose request carried no table; a turn with no
  `:calls` at all is a record that PREDATES these lines (see the inventory in
  .scratch/trajectory/spec.md). The three answers are different, and none of them is
  'the tools this session happens to have today'."
  [call-records]
  (loop [[record & more] call-records
         pending         nil
         acc             []]
    (cond
      (nil? record)
      (cond-> acc pending (conj (one-call pending nil)))

      (= "model/start" (:kind record))
      (recur more record (cond-> acc pending (conj (one-call pending nil))))

      (= "model/end" (:kind record))
      (recur more nil (conj acc (one-call pending record)))

      :else
      (recur more pending acc))))

(defn- append-calls
  "Append one run's calls to the last turn. Absent when the run made none: a record
  that cannot tell is not a record that says 'zero calls'."
  [turns calls]
  (if (or (empty? turns) (empty? calls))
    turns
    (update-in turns [(dec (count turns)) :calls] into calls)))

(defn- returned-items
  "The items one run's RETURNED tail contributes, in order: assistant replies (with their
  reasoning, when the vendor reported any) and tool results. A user message in the tail
  is a mid-run injection -- a skill body the model asked for -- so it lands as context,
  at the position it actually took in the conversation.

  EACH ITEM NAMES THE CALL IT BELONGS TO (`:call`), and that is a structural fact rather
  than a guess: the kernel appends the history ONE CALL AT A TIME -- one assistant message
  per model call, in call order, followed by the results of what that call asked for. So
  the nth assistant message of a run is the nth of its calls, and everything between it
  and the next one belongs to the same call. The TIMES live on the call, not on the item:
  one fact, one place, and `:call` is the pointer to it.

  OFFSET IS NIL WHEN THE RUN RECORDED NO CALLS AT ALL (a log from before the model lines)
  and then NO ITEM CARRIES `:call`: pointing at a call the record does not have would be
  inventing one. A resumed run passes the turn's own count instead, so numbering continues
  and the pointer stays an index into that turn's `:calls`.

  A TOOL ITEM CARRIES ITS OWN four marks as well, because a tool call has a life of its
  own: `arrivedAt` (it reached the seam), `resumedAt` (a park ended -- absent when nobody
  had to decide), `executedAt` (it LEFT execution, i.e. the tool finished) and `closedAt`
  (the seam is done). The span a tool occupied is arrivedAt -> executedAt; reading
  `executedAt` as a START is the mistake that makes every tool look instantaneous, and it
  is a mistake the line's own name invites. `executed` false means no execute line at all
  -- a vetoed call, which is not 'it ran in zero seconds'."
  [tail call-of life-of offset]
  (first
   (reduce (fn [[items next-call current] message]
             (case (:role message)
               "assistant"
               (let [mine (when (some? offset) (+ offset next-call))]
                 [(conj items (cond-> {:kind "assistant" :text (text-of (:content message))}
                                (some? mine) (assoc :call mine)
                                (seq (:reasoning_content message))
                                (assoc :reasoning (:reasoning_content message))))
                  (inc next-call)
                  (or mine current)])

               "tool"
               (let [id  (:tool_call_id message)
                     lif (get life-of id)]
                 [(conj items (cond-> {:kind      "tool"
                                       :toolCallId id
                                       :name      (get-in call-of [id :name])
                                       :argsText  (get-in call-of [id :argsText])
                                       :result    (text-of (:content message))
                                       ;; RAN means an execute line exists. NOT the same as
                                       ;; 'took no time': a vetoed call is the other case.
                                       :executed  (some? (:executedAt lif))}
                                (some? current)           (assoc :call current)
                                (some? (:arrivedAt lif))  (assoc :arrivedAt (:arrivedAt lif))
                                (some? (:resumedAt lif))  (assoc :resumedAt (:resumedAt lif))
                                (some? (:executedAt lif)) (assoc :executedAt (:executedAt lif))
                                (some? (:closedAt lif))   (assoc :closedAt (:closedAt lif))
                                (some? (:error lif))      (assoc :error (:error lif))
                                (some? (:outcome lif))    (assoc :outcome (:outcome lif))))
                  next-call
                  current])

               "user"
               [(conj items (cond-> (context-item message)
                              (some? current) (assoc :call current)))
                next-call
                current]

               ;; A reasoning-role message is not an item the model saw as its own: the
               ;; request carries reasoning INSIDE the assistant message.
               [items next-call current]))
           [[] 0 nil]
           tail)))

;; ------------------------------------------------------------------- the answer

(defn- system-item [text initial?]
  (cond-> {:kind "system" :text text}
    initial? (assoc :initial true)))

(defn- one-run
  "STATE + one run -> STATE. Turns are opened by new user messages, and everything the
  run showed goes under them in the record's order:

    [system?] [injected context] [user …] [injected context] [assistant / tool …]

  THE INJECTIONS SIT ON BOTH SIDES OF THE USER'S MESSAGE NOW, and which side is a fact
  about where they were read rather than a preference. The session's OPENING -- its
  instruction files and skills catalog -- enters the conversation at its birth
  (`.scratch/session-opening`), so it is IN FRONT of the question on every run after that
  and is drawn there; the session's own context entry sits behind it, where ticket 03 of
  `.scratch/sessions-live-on-the-server` put it. What a run DERIVES for itself (a skill
  body, a job's ending) still lands after the client's messages -- system, question,
  context, skill context (see harness.edge.ag_ui/inbound) -- so those blocks are drawn
  after this turn's own user message and before its answer. Blocks that arrived BETWEEN
  two retransmitted messages (an older layout, and a `/name` body in a log written before
  today) are drawn where they were too; the retransmitted history they sat inside is not
  listed, because a client restates its whole conversation on every run. All of it is
  deduped like every other injection, so the ordinary case -- the same bytes already
  shown in the turn that asked for them -- draws nothing here.

  AN INJECTION IS NOT A TURN: the opening enters as ordinary user messages, and a turn
  belongs to something a PERSON said (`harness.edge.ag_ui/injected?`, the one rule
  `stats/user-ids` counts turns with too).

  A turn's opening items land on its FIRST new user message; one `input` can bring
  several new user messages (the client may hand over more than one), and each of the
  rest opens a turn of its own -- which is the same counting rule stats uses, applied to
  items instead of to a number.

  STATE CARRIES THE CONVERSATION AS WELL AS THE PICTURE (`:history`), because the input
  lines no longer restate it: what a run continues from has to be folded here for the
  alignment to know which submitted messages are the client's own. See the binding
  below -- and note that this is the same conversation `harness.edge.replay` folds for
  the server, arrived at the same way, from the same lines."
  [state run life-of call-of]
  (let [{:keys [seen shownSystem turns history]} state
        payload   (get-in run [:input :payload])
        ;; THE CONVERSATION THIS RUN CONTINUED, plus what it brought, and the sum is what
        ;; the alignment below needs: WHICH MESSAGES ON THE SUBMITTED SIDE ARE THE
        ;; CLIENT'S OWN rather than something the run spliced in.
        ;;
        ;; AN INPUT LINE NO LONGER RESTATES THE CONVERSATION (ticket 03 of
        ;; `.scratch/sessions-live-on-the-server`): it says what ENTERED it
        ;; (`:added`), because the server holds the rest. So the conversation is
        ;; FOLDED HERE, one run at a time -- what entered plus what the run answered --
        ;; which is what the old spelling handed over in one field. Without it, every
        ;; earlier message would look like an injection and be drawn as injected
        ;; context under the turn that happened to follow it.
        ;;
        ;; `:messages` is still read as the input's own entries when a log was written
        ;; under the old contract, and such a log needs no folding (the input line
        ;; carries the history itself) -- so this fold is for the field's absence, not
        ;; its presence.
        added     (vec (:added payload))
        raw       (if (contains? payload :messages)
                    ;; THE OLD SHAPE, AND IT NEEDS NO FOLDING: an input line written
                    ;; before ticket 03 restates the whole conversation, so that field IS
                    ;; the alignment's answer and adding the folded history to it would
                    ;; list every earlier message twice.
                    (vec (:messages payload))
                    ;; THE NEW SHAPE: the conversation this run continued (folded from the
                    ;; runs before it) plus what this one brought.
                    (vec (concat (or history []) added)))
        ins       (vec (remove #(= "reasoning" (:role %)) raw))
        submitted (:submitted run)
        sys       (first (filter #(= "system" (:role %)) submitted))
        body      (vec (remove #(= "system" (:role %)) submitted))
        {:keys [before own between after]} (align body ins)
        own-of    (into {} (map-indexed (fn [i m] [i m]) own))
        texts     (str (:content sys))
        at        (:ts (:input run))
        fresh     (vec (remove #(contains? seen (:id %))
                               (filter #(= "user" (:role %)) raw)))
        changed?  (not= texts shownSystem)
        seen'     (into seen (stats/user-ids (:input run)))
        ;; THIS RUN'S CALLS, and where they start counting inside the turn: a resumed
        ;; run's calls continue the same turn's numbering, so an item's :call stays a
        ;; pointer into the turn's own :calls vector. NIL when this run recorded no calls
        ;; at all -- then the items carry no pointer, because there is nothing to point at.
        calls     (calls-of (:calls run))
        offset    (when (seq calls)
                    (if (empty? turns) 0 (count (:calls (peek turns)))))]

    (if (seq fresh)
      ;; A new turn: the system message opens it when it is new or different, then the
      ;; injected blocks, then the user message itself.
      (let [;; WHERE THE INJECTIONS SIT, relative to the person's own words: the session's
            ;; opening stands IN FRONT (it enters the conversation first, so every run
            ;; after the birth reads it there), and the session's context entry sits
            ;; BEHIND the question, where ticket 03 put it. Both are drawn in the place
            ;; the model read them, and neither opens a turn.
            lead  (take-while ag/injected? fresh)
            rest' (drop-while ag/injected? fresh)
            tail  (filter ag/injected? rest')
            said  (remove ag/injected? rest')
            turns (-> turns
                      open-turn
                      (cond-> changed?
                        (append-last [(system-item texts (nil? shownSystem))]))
                      (add-context before)
                      (add-context lead)
                      (add-context between))
            ;; A RUN CAN BRING NOTHING BUT INJECTIONS (the opening, on a first action that
            ;; carried no words of its own): then no turn is opened beyond the one the
            ;; run's own output will land in.
            turns (if (seq said)
                    (reduce (fn [turns message]
                              (-> turns open-turn (append-last [(user-item ins own-of message at)])))
                            (append-last turns [(user-item ins own-of (first said) at)])
                            (rest said))
                    turns)
            turns (-> turns
                      (add-context tail)
                      (add-context after)
                      (append-last (returned-items (:returned run) call-of life-of
                                                   (when (seq calls) 0)))
                      (append-calls calls))]
        {:seen seen' :shownSystem texts :turns turns
         :history (into raw (:returned run))})

      ;; No new user message: the run continues the turn it parked in. Its injections are
      ;; deduped like every other one, and its output lands after them.
      (let [turns (-> turns
                      (add-context before)
                      (add-context between)
                      (add-context after)
                      (cond-> changed? (append-last [(system-item texts false)]))
                      (append-last (returned-items (:returned run) call-of life-of offset))
                      (append-calls calls))]
        {:seen seen' :shownSystem texts :turns turns
         :history (into raw (:returned run))}))))

(defn records->trajectory
  "RECORDS -> {:turns [...] :incomplete bool}. See the namespace docstring for the fold's
  rules and the route (GET /api/threads/<stem>/trajectory) for the payload.

  EACH TURN CARRIES WHAT THE MODEL HAD, IN THE RECORD'S ORDER. A turn is :index, the
  :calls it made, and one :items vector whose entries are keyed by :kind --

    system     :text :initial
    context    :text
    user       :id :text
    assistant  :text :call :reasoning (only when the vendor reported some)
    tool       :toolCallId :name :argsText :result :executed
               :arrivedAt :resumedAt :executedAt :closedAt :outcome :error

  -- and a call is :index, :model, :tools (absent when the request carried none),
  :startedAt, :endedAt, :usage (the vendor's own map), :tokens and :finishReason.
  AN ITEM'S TIMES LIVE ON ITS CALL, not on the item: `:call` is the pointer, one fact
  in one place. A tool item keeps its own marks because a tool call has a life of its own,
  and a call that never ran has no `:executedAt` -- absent, never zero.

  `:calls` IS WHAT THE MODEL WAS SENT rather than what it saw: the model name actually
  used and the tool table as it went out, verbatim from the `model/start` line. A turn
  from a record that predates those lines has NO `:calls` -- a different answer from
  'this turn made no calls', and both are different from 'here are the tools this
  session has today'.

  WHAT IS NOT HERE IS NOT GUESSED. No token counts and no durations: those belong to the
  model call, and this payload answers 'what did it see'. `:executed` is a yes/no about
  a tool call, with the ABSENCE of the execute line behind it -- never a zero.

  THE SYSTEM MESSAGE IS SHOWN ONCE, and again whenever its bytes change: the frozen
  prompt is the same text on every run, so listing it per turn would be the same fact
  written N times -- but a prompt that CHANGED between turns is the single most important
  thing this view could show, and hiding it would be worse than repeating it.

  THE INJECTED CONTEXT OBEYS THE SAME RULE, spelled out in `add-context`: a block is shown
  once for the whole session, and again only in the turn where its bytes changed. The runs
  restate their injections -- the server holds no session, so it re-reads the instruction
  files and re-derives every skill body on every run -- and drawing each run's own bytes
  would repeat one fact under every turn, making a five-turn session look like five
  openings."
  [records]
  (let [life-of (tool-lifecycles records)
        call-of (call-index records)]
    {:turns      (mapv (fn [turn]
                         (let [calls (:calls turn)]
                           (cond-> turn
                             (seq calls)   (assoc :calls (vec (map-indexed
                                                               (fn [i call] (assoc call :index i))
                                                               calls)))
                             (empty? calls) (dissoc :calls))))
                       (:turns (reduce (fn [state run] (one-run state run life-of call-of))
                                       {:seen #{} :shownSystem nil :turns [] :history []}
                                       (run-segments records))))
     :incomplete (stats/incomplete? records)}))

(defn log-trajectory
  "A log FILE -> records->trajectory of it. The file entry point, the counterpart of
  harness.edge.stats/log-stats: the caller locates the stem and this namespace never
  learns where the log came from."
  [f]
  (records->trajectory (stats/read-records f)))
