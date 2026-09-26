(ns harness.edge.ag-ui
  "Conversion between the kernel's flat event stream and AG-UI wire events.

  OUTBOUND (outbound) is a stateful fold, because AG-UI constrains message and tool-call
  ids to be opened and closed in order while the kernel's events carry no such
  structure. That state lives here and nowhere else -- it never leaks into the kernel.

  THE ORDER IS THE MODEL'S OWN (2026-09-22, the owner's call). A turn's frames come out
  in the order the vendor produced them -- `reasoning group -> text message -> tool calls
  parented to it` when the model thought first and then answered, which is the common
  shape -- and a model that goes BACK to thinking after the answer has started keeps the
  SAME reasoning message open, its later deltas landing there. That is why the reasoning
  is closed by the end of the MODEL CALL (`:model/end`) and not by the answer's first
  token: closing it early was this edge deciding that the thinking was over, and a vendor
  that thought again afterwards got a second reasoning message -- drawn, before this was
  fixed, as a second 思考 row UNDER the answer.
  A turn with no text still opens an empty TEXT_MESSAGE, so every tool call has a
  parentMessageId and every reasoning message has an assistant message to fold onto.

  INVARIANT: never emit a *_CHUNK event. The client's applier throws on an unexpanded
  one, and relying on its stream transform to expand them first is a needless risk.

  INBOUND (inbound) is the other direction: a client's message list back into the
  provider's shape. AG-UI keeps reasoning as a message of its own; the provider wants
  it as a field on the assistant message. Folding it back is MANDATORY, not cosmetic:
  a request carrying tools must echo reasoning_content or DeepSeek answers HTTP 400."
  (:require [clojure.string :as str]))

(defn- open-text [s]
  (if (:text s)
    s
    (let [id (str (:run-id s) "-m" (:n s))]
      (-> s (assoc :text id) (update :n inc)
            (update :frames conj {:type "TEXT_MESSAGE_START" :messageId id :role "assistant"})))))

(defn- close-text [s]
  (if-let [id (:text s)]
    (-> s (assoc :text nil) (update :frames conj {:type "TEXT_MESSAGE_END" :messageId id}))
    s))

(defn- close-reasoning [s]
  (if-let [id (:reasoning s)]
    (let [closed (-> s (assoc :reasoning nil)
                       (update :frames conj {:type "REASONING_MESSAGE_END" :messageId id}
                               {:type "REASONING_END" :messageId id}))]
      ;; An assistant message must always follow the reasoning group. Without this,
      ;; a turn that produced reasoning and then nothing -- which is precisely what
      ;; a max_tokens-exhausted think looks like, finish_reason "length" with empty
      ;; content -- would leave the reasoning with no assistant message to fold
      ;; back onto, and DeepSeek answers 400 on the next turn.
      ;;
      ;; BUT ONLY WHEN THE TURN HAS NONE YET. A turn that reasoned, answered and
      ;; then called a tool has already opened its assistant message (`:text`) and
      ;; hung the call off it (`:parent`); opening one more here leaves an EMPTY
      ;; assistant message between the call and its result, and an OpenAI-shaped
      ;; vendor refuses that history before the model runs (the 2026-09-22 refusal
      ;; in harness.infra.log named two such calls). A tool call's parent IS the
      ;; assistant message its result must follow, so the group needs no second one.
      (if (or (:text closed) (:parent closed)) closed (open-text closed)))
    s))

(defn- open-reasoning [s]
  (if (:reasoning s)
    s
    (let [id (str (:run-id s) "-r" (:n s))]
      (-> s (assoc :reasoning id) (update :n inc)
            (update :frames conj {:type "REASONING_START" :messageId id}
                                ;; The role is a literal the shipped schema insists on.
                                ;; @ag-ui/client's applier ignores it and hard-codes
                                ;; "reasoning" itself -- but zod validates the event
                                ;; BEFORE the applier ever sees it, so omitting it is a
                                ;; hard client-side failure. Found by running the real
                                ;; client, not by reading the spec.
                                {:type "REASONING_MESSAGE_START" :messageId id :role "reasoning"})))))

(defn- interrupt-frame
  "One parked call -> the wire's interrupt object. AG-UI validates this shape
  strictly: id and reason are required, message and toolCallId optional, and the
  human-facing line is MESSAGE -- a client renders that, not reason.

  TWO KINDS OF STOP, ONE SHAPE. A parked call is either waiting for a person to
  approve it (`tool-approval`, the default and every case that existed before) or
  waiting for a person to answer a question (`elicitation`). REASON is what tells a
  client which card to draw, and MESSAGE is what it puts on the card -- for a
  question that is the question itself, because 'Approve ask? arguments' would be a
  sentence about a tool call rather than the thing being asked.

  THE FALLBACK NAMES NOBODY, and that is deliberate: WHO asked is on the harness's
  own endpoint (`GET /api/elicitation`, which answers `server` or `askedBy`), so a
  sentence here that guessed would be a second, wrong answer to a question a card
  already asks properly -- 'A server is asking you' is false when the asker is one of
  this harness's own tools."
  [{:keys [id tool-call-id name args reason question]}]
  (if (= :elicitation reason)
    {:id id
     :reason "elicitation"
     :message (or (:prompt question) "You are being asked for input.")
     :toolCallId tool-call-id}
    {:id id
     :reason "tool-approval"
     :message (str "Approve `" name "`? arguments: " args)
     :toolCallId tool-call-id}))

(def injected-part-name
  "The name of the `data` part an injected message is carried by, on both ends: the
  CUSTOM frame a run emits for something the client never sent, and the opening entry
  the session owns. `harness.kernel.frames/apply-frames` folds a frame into a card by
  this name and the UI looks it up by it (`ui/src/lib/injections.ts`), so a second
  spelling on this side would be a card that draws nowhere."
  "injected-context")

(def timeout-part-name
  "The name of the `data` part A MODEL CALL THAT WENT QUIET is carried by, on both ends:
  the CUSTOM frame a run emits the moment the idle guard cuts a call off, and the card the
  UI draws for it (`ui/src/lib/llm-timeout.ts`). `harness.kernel.frames/apply-frames`
  drops it on the floor for the same reason it drops every CUSTOM name it does not know,
  which is exactly the behaviour this frame wants.

  THE WIRE ONLY, AND THAT IS THE FEATURE: `harness.edge.http/wire-only-frame?` is what
  keeps this name out of the record. It is spelled once, there, because there are two
  frame sinks -- the agent route and a subagent's -- and a second spelling would be a
  second rule. The name is deliberately NOT in `harness.edge.replay/wire-custom-names`
  either: that list is the CUSTOM names A ROW MAY CARRY, and nothing about this frame ever
  becomes a row."
  "llm-timeout")
(defn- injection-value
  "One injected message -> the value both readers of `injected-part-name` take: what the
  card says (the role it arrived with and its text). Built here rather than at each
  emitter because the UI's parser reads this exact shape by name.

  TWO SPELLINGS COME THROUGH HERE, and the second one is why this is not one line. A
  message a run was handed is provider-shaped -- `content` is the string the model read.
  A RECORD ENTRY THAT ALREADY CARRIES ITS OWN CARD (`.scratch/session-opening`) is a
  part vector, and `(str content)` on one of those would put the vector's printed form
  into the card. So a message that carries a card part is read OFF that card: the entry
  is where the bytes were first said, and any card built from it -- a frame, or the
  entry's own part -- is the same bytes."
  [message]
  (let [c (:content message)
        card (when (sequential? c)
               (some #(when (= injected-part-name (:name %)) %) c))]
    (if-some [found card]
      (:data found)
      {:role (:role message) :text (str c)})))

(defn injected-frame
  "The CUSTOM frame for one message a run was handed without the client sending it:
  a skill body, an instruction block, a catalog, the ending of a background job.

  ONE PLACE BUILDS THIS SHAPE, because two do emit it: the kernel's pre-LLM step
  (through `step` below, for whatever it splices) and the edge itself, for the
  injections it folded in before handing the history over (which is why the edge is the
  one that knows they happened). Every frame this builds is named by the run that
  emitted it -- and THE TWO EMITTERS MUST NOT SHARE A SPELLING, because both count from
  zero and a frame's id is what the record folds a card under (`apply-frames` in
  `harness.kernel.frames`, then a first-wins dedupe in `replay/append-new` and
  `sessions/append!`): a collision would draw ONE card for TWO injections. So the
  kernel's splices are `<run-id>-ctx<n>` -- `ctx` for the `:context/injected` event they
  answer -- and the edge's own are `<run-id>-pre<i>`, the injections a run STARTED
  with.

  AND THE BIRTH'S OWN OPENING IS *NOT* ONE OF THESE (2026-09-21, the owner's call). It
  used to be a frame per block, under the ENTRY's id (`session-opening-<i>`), so that
  the page that MINTED the session could see it at all -- and it could not work: a
  CUSTOM frame is a PART, the adapter hangs it on the message being streamed, and the
  frame's own `messageId` is dropped on the way in, so the card landed under the answer
  instead of in the person's column. The birth now hands the page the CONVERSATION
  (`conversation-snapshot`), which carries the messages themselves, ids and all.

  WHAT MAKES THIS FRAME SPECIAL, and the whole feature rests on it: the client draws
  it and NEVER SENDS IT BACK. `@assistant-ui`'s adapter turns a CUSTOM event into a
  `data` part, and its outgoing conversion carries text, reasoning and tool calls
  only -- a data part has no case there. So a person sees what the model was handed,
  and the conversation stays the client's.

  `messageId` IS OURS AND DETERMINISTIC, unlike the text and tool ids: the rebuild
  folds one card message per frame (`harness.kernel.frames/apply-frames`) and hands
  the client the same ids back, so a card survives a refresh under the same name."
  [message-id message]
  {:type "CUSTOM" :name injected-part-name :messageId message-id
   :value (injection-value message)})

(defn- step [s ev]
  (case (:type ev)
    :run/start
    (update s :frames conj {:type "RUN_STARTED"
                            :threadId (:thread-id s) :runId (:run-id s)})

    :reasoning/delta
    ;; The id must be read AFTER opening: inside a -> the map literal still sees the
    ;; outer binding, so (-> s open-reasoning (update .. conj {.. (:reasoning s)})) would
    ;; capture the pre-open value. Hence the explicit let.
    (let [s (open-reasoning s)]
      (update s :frames conj {:type "REASONING_MESSAGE_CONTENT"
                              :messageId (:reasoning s) :delta (:text ev)}))

    :text/delta
    (let [s (open-text s)]
      ;; THE ANSWER'S FIRST TOKEN DOES NOT END THE THINKING (2026-09-22, the owner's
      ;; call). What used to be here was `close-reasoning`, and the price was a SECOND
      ;; reasoning message for the vendor shape `思考 · 答案 · 思考`: the model went back
      ;; to its reasoning after the answer had begun, the group had already been closed,
      ;; so a new one was opened -- and a reader saw a stray 思考 row under the answer.
      ;; The frames follow the MODEL's order now; `:model/end` closes the thinking.
      ;; THE TURN'S ASSISTANT MESSAGE IS THE ONE ITS TOOL CALLS WILL HANG OFF, and
      ;; this is where it is chosen: text arrives before the calls of the same
      ;; message, so the message the text just opened is the message that owns them.
      ;; Remembering it is what lets a turn's SECOND call reuse the first one's
      ;; parent instead of opening an assistant message of its own.
      (-> s (assoc :parent (:text s))
            (update :frames conj {:type "TEXT_MESSAGE_CONTENT"
                                  :messageId (:text s) :delta (:text ev)})))

    :model/start
    ;; ONE MODEL CALL IS ONE ASSISTANT MESSAGE, so a new call ends the previous
    ;; turn's ownership of a parent: without this, a call-only turn that follows
    ;; another would hang its calls on the message the earlier turn used, and the
    ;; record would describe the two turns as one. It carries no frame of its own
    ;; (audit only), exactly like :model/end below.
    (assoc s :parent nil)

    :tool/call
    ;; The parent must be named, then closed, before the call is announced -- that is
    ;; the order the protocol's own example uses.
    ;;
    ;; ONE PARENT FOR THE WHOLE TURN, and the reason is the bug this branch used to
    ;; carry: opening a fresh TEXT_MESSAGE per call turned a two-call turn into TWO
    ;; assistant messages with one call each, and the first of them was then followed
    ;; by an assistant message instead of its tool message -- a history no
    ;; OpenAI-shaped vendor will accept, and one a rebuilt conversation cannot
    ;; continue from (see harness.kernel.loop's refusal). The real client applies the
    ;; same rule we do here: @ag-ui/core resolves `parentMessageId` to the assistant
    ;; message and pushes EVERY call of the turn onto it.
    (let [s      (if (:parent s)
                   s
                   (let [opened (-> s close-reasoning open-text)]
                     (assoc opened :parent (:text opened))))
          parent (:parent s)]
      (-> s close-text
            (update :frames conj {:type "TOOL_CALL_START" :toolCallId (:id ev)
                                  :toolCallName (:name ev) :parentMessageId parent}
                                {:type "TOOL_CALL_ARGS" :toolCallId (:id ev) :delta (:args ev)}
                                {:type "TOOL_CALL_END" :toolCallId (:id ev)})))

    :context/injected
    ;; A CUSTOM FRAME, which is AG-UI's own extension point and the ONLY frame kind
    ;; here the client does not send back: @assistant-ui's adapter turns it into a
    ;; `data` part (in the order it arrived), and its outgoing conversion sends text,
    ;; reasoning and tool calls only -- so the card is visible and is NOT part of the
    ;; conversation. That is exactly what an injection needs to be: the model was
    ;; given it, the client must not re-send it, and a person should still see it.
    ;;
    ;; THE TEXT IS NOT CLOSED AROUND IT: the part lands in the message that is open
    ;; (a skill body arrives after the tool call that asked for it; a job's ending
    ;; between two calls), which is where it arrived in the model's history too.
    ;;
    ;; `messageId` IS OURS AND DETERMINISTIC, unlike the text/tool ids: the rebuild
    ;; (harness.kernel.frames/apply-frames) makes one card message per frame, and the
    ;; same card has to come back with the same id after a refresh.
    (-> s (update :n inc)
          (update :frames conj (injected-frame (str (:run-id s) "-ctx" (:n s))
                                               {:role (:role ev) :content (:text ev)})))

    :model/timeout
    ;; A CUSTOM FRAME THE OTHER WAY ROUND: the injection card above is about what the MODEL
    ;; was handed, and this is about the CALL -- a vendor that stopped answering, and what
    ;; the harness is doing about it. Same extension point, same rules, one difference that
    ;; is the feature: THIS FRAME IS NEVER RECORDED (`harness.edge.http/wire-only-frame?`).
    ;; The record is what a reload rebuilds a conversation from, and a stall is not part of
    ;; the conversation -- it is a fact about a call that was in flight, which a person
    ;; watching wants and a reader of the log has no use for.
    ;;
    ;; NO `messageId`, AND ONE WOULD BE THROWN AWAY ANYWAY: the adapter hangs a CUSTOM frame
    ;; on the message being streamed and drops the frame's own id on the way in (see
    ;; `injected-frame` above, where the same measurement is written down) -- so an id here
    ;; would be a field that lies about being used.
    (update s :frames conj {:type  "CUSTOM"
                            :name  timeout-part-name
                            :value {:idleMs   (:idle-ms ev)
                                    :attempt  (:attempt ev)
                                    :limit    (:limit ev)
                                    :retrying (:retrying ev)
                                    :emitted  (:emitted ev)}})

    ;; A `:run/cut-off-result` IS THE SAME FRAME (`harness.kernel.event`), because the
    ;; RECORD does not distinguish an answer that arrived from one written at a stop -- it
    ;; is the WIRE that must not carry the cut-off one, and that is the edge's dispatch,
    ;; not this converter's (see `harness.edge.http`'s drain).
    (:tool/result :run/cut-off-result)
    (let [id (str (:run-id s) "-t" (:n s))
          s  (-> s (update :n inc) close-text)]
      (update s :frames conj {:type "TOOL_CALL_RESULT" :messageId id :toolCallId (:id ev)
                              :content (:content ev) :role "tool"}))

    :model/end
    ;; THE THINKING ENDS WITH THE CALL THAT DID THE THINKING, and this is now the only
    ;; place a reasoning message is closed while a turn is still running (see
    ;; `:text/delta`). The frames it emits are the reasoning's own END pair; the event
    ;; itself still carries nothing of its own (audit only), exactly like the rest of
    ;; the model-call telemetry below -- and `close-reasoning` also opens the assistant
    ;; message a reasoning-with-no-answer turn needs, which is the rule it always had.
    (close-reasoning s)

    (:tool/pre-execute :tool/execute :tool/post-execute)
    ;; The audit-only events carry no AG-UI frame at all: the edge records them as
    ;; jsonl lines. Passing the event through unchanged keeps the fold total
    ;; without inventing wire frames for audit data. (The constants share one
    ;; result and so must be grouped in a list -- bare consecutive constants would
    ;; pair each with its own result.)
    ;;
    ;; THE MODEL-CALL BOUNDARIES ARE HERE FOR THE SAME REASON, and the reason is
    ;; worth repeating because they are the ones somebody will be tempted to wire
    ;; up: tok/s and cache hits are numbers the CONVERSATION has no use for, and
    ;; adding a frame for them would be changing a protocol to carry a statistic.
    ;; The client learns them from the management edge (harness.edge.stats), and
    ;; the run it is watching looks exactly as it did before. :model/start is the
    ;; one of the pair that also carries state, and it is handled above.
    s

    :run/end
    (-> s close-reasoning close-text
          (update :frames conj {:type "RUN_FINISHED"
                                :threadId (:thread-id s) :runId (:run-id s)}))

    :run/interrupt
    ;; The other terminal: same frame type, an outcome that names the parked
    ;; calls a human must decide. RUN_FINISHED is still the closing frame, so
    ;; the edge's terminal framing and the client's stream handling are
    ;; unchanged; the client turns outcome.interrupts into pendingInterrupts.
    (-> s close-reasoning close-text
          (update :frames conj {:type "RUN_FINISHED"
                                :threadId (:thread-id s) :runId (:run-id s)
                                :outcome {:type "interrupt"
                                          :interrupts (mapv interrupt-frame
                                                            (:interrupts ev))}}))

    :run/error
    (-> s close-reasoning close-text
          (update :frames conj {:type "RUN_ERROR" :message (:message ev)}))

    ;; A STOP IS AN ERROR WITH A CODE (`harness.kernel.event/run-stopped`), and the code is
    ;; the whole difference on the other side: `code: "stopped"` says a PERSON ended this
    ;; run, so a client can draw it as a stop rather than a failure without reading the
    ;; sentence -- which is written for a reader, not for a matcher. The frame type is
    ;; unchanged on purpose: the wire's terminal vocabulary is two frames, a stopped run
    ;; did not finish, and a client that knows nothing about this code draws it as what it
    ;; is (a run that ended in an error) rather than as something that never happened.
    :run/stopped
    (-> s close-reasoning close-text
          (update :frames conj {:type "RUN_ERROR" :message (:message ev)
                                :code "stopped"}))))

(defn outbound
  "Stateful converter. Returns (fn [kernel-event] -> vector of AG-UI wire frames)."
  [thread-id run-id]
  (let [s (atom {:thread-id thread-id :run-id run-id :n 0
                 :reasoning nil :text nil :parent nil :frames []})]
    (fn [ev]
      (let [next (step (assoc @s :frames []) ev)]
        (reset! s (assoc next :frames []))
        (:frames next)))))

;; ------------------------------------------------------------------- inbound

(def ^:private ag-ui-only
  "WHAT ONLY THE WIRE KEEPS: the fields AG-UI's own messages carry that a provider message has
  not got. `:id` is the one to know about -- it is also the entry's identity, which
  `harness.edge.replay/entries` stamps back onto a message it folds, and a fold that read it as
  a way of SPELLING a message translated a record's own row a second time (see `absorbed`)."
  #{:id :encryptedValue :subagentRunId :metadata :activityType})

(defn- strip-ag-ui-only [m] (apply dissoc m ag-ui-only))

;; ------------------------------------------------------------ content parts
;;
;; A message's content is either a STRING (the common case, and it passes through
;; untouched) or a vector of PARTS, and the part shapes differ between the two
;; protocols:
;;
;;   AG-UI in     {:type "image" :source {:type "url"  :value "https://…"}}
;;                {:type "image" :source {:type "data" :value "<base64>"
;;                                        :mimeType "image/png"}}
;;   provider out {:type "image_url" :image_url {:url "https://…"}}
;;                {:type "image_url" :image_url {:url "data:image/png;base64,…"}}
;;   AG-UI in     {:type "text" :text "…"}
;;   provider out {:type "text" :text "…"}          same shape, passed through
;;   provider in  {:type "image_url" :image_url {:url …}}   ALREADY the provider's
;;                (a record's own row: `provider-messages` wrote it) -- and reading the record
;;                back must not translate it a second time, so this shape is an INPUT too.
;;
;; THE TRANSLATION BELONGS HERE, not in harness.kernel.llm. The "message" line in the
;; log is a VERBATIM record of what the LLM was about to see; translating at the
;; protocol layer would leave that line holding a shape no provider ever received,
;; which is a log that lies about the one thing it exists to record. Doing it on
;; the way IN keeps writer and reader agreeing without either knowing about parts.
;;
;; It is also the seam where a SECOND protocol would split the two apart: the
;; right-hand shapes above are OpenAI-compatible chat-completions specifically,
;; and a provider that wanted Anthropic's `{:type "image" :source {:type "base64"…}}`
;; would need its own table here rather than a shared one.

(defn- provider-image-url
  "An AG-UI image part -> the provider's image_url part. A `data` source is
  prefixed into a data URL, which is how the wire carries inline bytes."
  [part]
  (let [src (:source part)
        v   (:value src)]
    (case (:type src)
      "url"  {:type "image_url" :image_url {:url v}}
      "data" {:type "image_url"
              :image_url {:url (str "data:" (:mimeType src) ";base64," v)}}
      (throw (ex-info (str "unsupported image source " (pr-str (:type src))
                           "; this harness carries a \"url\" or a \"data\" source"
                           (when (seq v) (str " (the part has a value of "
                                              (count (str v)) " chars)")))
                      {:part part})))))

(def ^:private provider-parts
  "WHAT THIS HARNESS CAN HAND A PROVIDER: a part's type -> the type it comes out as, and how to
  carry it there.

  `:out` EQUAL TO THE KEY MEANS THE PART IS ALREADY THE PROVIDER'S, which is exactly what the
  message-level tell asks of every part it finds (`provider-shaped-part?`) -- so the vocabulary
  is written ONCE, and a tell that disagreed with the translator about what this harness can
  carry is not expressible. It used to be: the tell listed AG-UI's part types and read
  everything else as 'already the provider's', so a `:document` part -- which AG-UI may spell
  and this harness refuses BY NAME -- was read as carried (2026-09-21).

  THE KEYS ARE THE INPUT SIDE. They hold AG-UI's spelling of a part this provider spells
  differently (`image`) AND the provider's own spelling (`image_url`), because a record's row
  comes back through here -- `provider-messages` wrote it -- and reading the record must not
  translate it a second time.

  A TYPE OUTSIDE THIS TABLE IS REFUSED WITH ITS NAME by `provider-part`, never forwarded: a part
  handed over untouched reaches the vendor as a shape it does not know, and the 400 that comes
  back names nothing useful. Refusing is what turns 'the vendor rejected the request' into 'this
  harness cannot carry a :document part'."
  {"text"      {:out "text"      :carry (fn [part] (select-keys part [:type :text]))}
   "image"     {:out "image_url" :carry provider-image-url}
   "image_url" {:out "image_url" :carry (fn [part] part)}})

(defn- provider-shaped-part?
  "Is PART already the provider's spelling? Its type comes out as the type it went in as.

  ASKED POSITIVELY, from the table above rather than from a list of AG-UI's own types: AG-UI
  may spell parts this harness has no case for, and those are the ones the refusal exists for."
  [part]
  (let [{:keys [out]} (get provider-parts (:type part))]
    (boolean (and (some? out) (= (:type part) out)))))

(defn- provider-part
  "One part -> the provider's, by the table above."
  [part]
  (if-some [{:keys [carry]} (get provider-parts (:type part))]
    (carry part)
    (throw (ex-info (str "unsupported content part type " (pr-str (:type part))
                         "; this harness carries \"text\" and \"image\"")
                    {:part part}))))

(defn- provider-content
  "A message's content -> the provider's. A string is a string; a vector of parts
  is translated part by part, and an EMPTY vector is left alone (there is nothing
  to translate and nothing to refuse)."
  [content]
  (if (sequential? content)
    (mapv provider-part content)
    content))

(defn- provider-tool-call [tc]
  {:id (:id tc) :type "function"
   :function {:name (get-in tc [:function :name])
              :arguments (get-in tc [:function :arguments])}})

(defn- provider-assistant
  "Whitelist rebuild. Content is translated (assistant content is a string in
  practice, but a multimodal turn is legal and must not arrive untranslated),
  everything else is either renamed to the provider's casing or dropped.

  `reasoning_content` COMES FROM TWO PLACES AND BOTH ARE KEPT. The shipped client
  splits reasoning into a separate `reasoning`-role message before the assistant one
  (that is REASONING, above -- the fold the outbound side relies on), but another
  client may simply carry the field on the message itself. Dropping the second would
  be a silent loss of exactly what a thinking-mode vendor demands back: the message's
  OWN field wins when it is there, because it is the more specific statement about
  that message, and a message that says 'my reasoning is empty' is saying something."
  [m reasoning]
  (cond-> {:role "assistant" :content (provider-content (or (:content m) ""))}
    (or (contains? m :reasoning_content) (seq reasoning))
    (assoc :reasoning_content (if (contains? m :reasoning_content)
                                (str (:reasoning_content m))
                                reasoning))
    (seq (:toolCalls m)) (assoc :tool_calls (mapv provider-tool-call (:toolCalls m)))))

(defn- provider-user
  "A user (or any other role) message, stripped of AG-UI-only fields and with its
  content translated. This is where an image actually enters a conversation."
  [m]
  (cond-> (-> m strip-ag-ui-only (update :content provider-content))
    ;; An AG-UI message with no content at all would otherwise carry :content nil,
    ;; which a provider reads as a null message body.
    (nil? (:content m)) (assoc :content "")))

(def ^:private ag-ui-only-roles
  "The roles AG-UI has and a provider does not."
  #{"reasoning" "activity"})

(defn- provider-shaped?
  "Is M ALREADY the message a provider reads?

  THE FOLD HAS TO TELL, because it runs over BOTH halves of a record: since
  `.scratch/jsonl-two-kinds` 票 02 an ENTRY's row holds the message the provider was handed
  (`provider-messages` is what wrote it), while a message DERIVED FROM A RUN'S FRAMES is
  AG-UI's own spelling -- and a rebuild feeds the two through one fold
  (`harness.edge.replay/history`). The tell is exactly where the dialects disagree:
  AG-UI's camelCase tool fields, the roles AG-UI keeps for itself, and its way of SPELLING
  a content part -- which is asked as 'is every part one this provider can carry?', the same
  table `provider-part` translates by (`provider-parts`). A message with none of those is the
  same message in both -- a plain string body is byte for byte what a provider gets -- so
  translating it or not comes to the same thing.

  THE PARTS ARE ASKED POSITIVELY, not by a list of AG-UI's own types: AG-UI may spell parts
  this harness has no case for (`:document`), and a tell that read those as 'already the
  provider's' would hand the vendor a shape it does not know -- the very 400 the refusal in
  `provider-part` exists to name. The table is what this harness CARRIES; anything else is
  translated (and there refused by name).

  THE ENVELOPE'S FIELDS ARE NOT PART OF THE TELL (`ag-ui-only`, and `absorbed` takes them off
  before this runs). They are what the WIRE keeps rather than a way of spelling a message, and
  a tell that read them as a dialect would translate a record's own row a second time: the
  entry's `:id`, which `harness.edge.replay/entries` stamps back onto the message, made an
  already-translated `image_url` part look like AG-UI's `image` -- and the second translation
  REFUSED it by name. That is the RUN_ERROR of 2026-09-21 that this shape was found by: a
  session with a picture in its record could not be run again."
  [m]
  (and (not (contains? ag-ui-only-roles (:role m)))
       (not (contains? m :toolCalls))
       (not (contains? m :toolCallId))
       (every? provider-shaped-part?
               (when (vector? (:content m)) (:content m)))))

(defn- absorbed
  "Drop activity, fold reasoning into the assistant message it precedes, and rebuild
  each message in the provider's shape -- content parts translated on the way.

  THE ENTRANCE TAKES OFF WHAT ONLY THE WIRE KEEPS (`ag-ui-only`: the entry's `:id`, AG-UI's
  `metadata`, the fields it keeps for its own bookkeeping). One message is one fact, and this
  fold runs over that fact in EITHER spelling -- so which spelling it is holding must not
  depend on the envelope. A message read back off the record carries the provider's parts AND
  the entry's id, and a fold that let the id stand for 'AG-UI spelling left' would translate
  a message that is already translated -- and the second translation REFUSES an `image_url`
  part by name. That is how a session with a picture in its record stopped being continueable
  on 2026-09-21. Nothing below has to know the envelope's fields were ever there, which is why
  this is one line HERE rather than a duty of every caller.

  A MESSAGE THAT IS ALREADY IN THAT SHAPE IS PASSED THROUGH (`provider-shaped?`): the fold
  is IDEMPOTENT -- `provider-messages` is what WROTE a record's entry rows, so reading them
  back has to be a no-op -- and that is what lets one reader assemble a rebuild out of a
  record that speaks both dialects at once: the ENTRY rows, already the provider's, and the
  messages folded out of a run's frames, which are AG-UI's own spelling.

  Reasoning that trails the whole list would be dropped, and that cannot happen: the
  outbound side always closes a turn with a text message, even an empty one. That
  invariant is what makes this fold total."
  [messages]
  (:out
   (reduce (fn [{:keys [pending] :as acc} m]
             (cond
               (= "reasoning" (:role m))
               (assoc acc :pending (str pending (:content m "")))

               (= "activity" (:role m))
               acc

               (provider-shaped? m)
               {:pending (if (= "assistant" (:role m)) nil pending)
                :out (conj (:out acc)
                           (if (and (seq pending) (nil? (:reasoning_content m)))
                             (assoc m :reasoning_content pending)
                             m))}

               (= "assistant" (:role m))
               {:pending nil :out (conj (:out acc) (provider-assistant m pending))}

               (= "tool" (:role m))
               {:pending pending
                :out (conj (:out acc) {:role "tool"
                                       :tool_call_id (:toolCallId m)
                                       :content (str (:content m))})}

               :else
               {:pending pending :out (conj (:out acc) (provider-user m))}))
           {:pending nil :out []}
           ;; THE WIRE'S OWN FIELDS COME OFF BEFORE ANYTHING DECIDES WHAT THIS MESSAGE IS.
           ;; `strip-ag-ui-only` is the same one the translate branches apply, so both halves of
           ;; the fold answer the same shape: no AG-UI-only field leaves here, whichever way a
           ;; message was spelled.
           (map strip-ag-ui-only messages))))

(def context-entry-id
  "The id `context-entry` stamps on the opening context: a NAME rather than a generated
  id because the conversation deduplicates by id (`harness.edge.sessions/append!`), so a
  session born twice ends up with one opening block.

  EXPORTED BECAUSE IT IS ALSO A READER'S QUESTION. The block is an ordinary user message
  by design, so a reader asking 'what did the PERSON first say' has to tell it apart from
  the client's own turns -- `first-user-text` does, and a literal copied into that reader
  would be a second place deciding which message is ours."
  "session-context")

(defn context-entry
  "The AG-UI message a session's opening CONTEXT becomes, or nil when there is none.

  IT IS AN ORDINARY USER MESSAGE WITH A NAME. The session owns this the way it owns
  everything else it was born with: the message goes into the conversation once
  (`harness.edge.sessions`), so every later run continues from it instead of being handed
  it again -- which is what keeps it inside the provider's cached prefix rather than
  appended at the end of a conversation that has moved on.

  THE ID IS FIXED rather than generated, because the conversation it enters is
  deduplicated by id (`harness.edge.sessions/append!`): a session that is born twice --
  a retry of the very first action, an id that was created and asked for again -- must
  end up with one opening context and not two."
  [context]
  (when (seq context)
    {:id      context-entry-id
     :role    "user"
     :content (str/join "\n" (map #(str "- " (:description %) ": " (:value %)) context))}))

(def opening-entry-prefix
  "The prefix `opening-entries` numbers the conversation's opening with: `session-opening-0`,
  `session-opening-1`, ...

  EXPORTED FOR THE SAME REASON `context-entry-id` IS, and it is the same question one
  message further along: the opening blocks are ordinary user messages by design, so a
  reader asking 'what did the PERSON first say' has to tell them apart from the client's
  own turns. A prefix rather than a table of names because the blocks are NUMBERED --
  how many there are is what `cap.preamble` read off the disk, not a constant."
  "session-opening-")

(defn opening-entry?
  "Is MESSAGE one of the conversation's opening blocks (`opening-entries`)?

  BY ID AND NOT BY POSITION, for the reason `first-user-text` skips the context entry
  the same way: the opening enters the conversation at birth, and every later run sees
  it wherever the birth put it."
  [message]
  (boolean (some-> (:id message) (str/starts-with? opening-entry-prefix))))

(defn injected?
  "Is MESSAGE one the EDGE put into a conversation rather than one a PERSON typed?

  TWO OF THEM ENTER AS ORDINARY USER MESSAGES, and that is by design: the session's
  opening context (`context-entry`) and its opening blocks (`opening-entries`). Every
  reader asking 'what did the person say' therefore has to tell them apart, and this is
  that rule NAMED ONCE -- `first-user-text` (which names a session after the first thing
  somebody said) asks it, and so do the record's readers
  (`harness.edge.stats/user-ids`, which counts turns, and `harness.edge.trajectory`,
  which draws them). A copy of the test at each of those would be a second place deciding
  which message is ours, which is exactly what `context-entry-id`'s docstring refuses."
  [message]
  (or (= context-entry-id (:id message))
      (opening-entry? message)))

(defn opening-entries
  "BLOCKS -> the conversation's OPENING, as the session entries the run that BIRTHS a
  conversation writes in front of the question.

  THE OPENING HAPPENS ONCE (`.scratch/session-opening`). Each entry goes into the
  conversation through `harness.edge.sessions/append!` and is continued from by every
  later run, instead of being re-read and re-appended behind the conversation on every
  run -- which is what this replaces, and what made a run's own answer land after the
  material for a question that had already been asked (`tail-blocks`, retired).

  THE ID IS FIXED rather than generated, exactly as `context-entry-id`'s is and for the
  same reason: the conversation deduplicates by id, so a session that is born twice --
  a retry of the very first action, an id that was created and asked for again -- owns
  one opening and not two.

  ONE MESSAGE, TWO READINGS, and both are needed here. The `data` part is the CARD the
  page draws (`injected-part-name`), the same one the frames carry for a run's own
  injections; the `text` part is what the MODEL reads. `sessions/model-view` drops
  the first and keeps the second, so the model view needs no case for a card and the
  screen needs no new renderer -- the two readings the table already keeps for a
  conversation.

  AND THE ENTRY IS WHAT GOES ON THE WIRE, ONCE, under this id: the run that births the
  conversation hands the page the whole conversation as messages
  (`conversation-snapshot`), and a reader that has the id draws the card from the text
  even when the part itself did not survive the trip (`ui/src/lib/injections.ts`).

  THE ORDER IS `cap.preamble/messages`'s DECISION, not this function's: it numbers what
  it is handed and never reorders (instruction files first, the skills catalog last)."
  [blocks]
  (mapv (fn [i block]
          (let [text (str (:content block))]
            {:id      (str opening-entry-prefix i)
             :role    (:role block)
             :content [{:type "data" :name injected-part-name :data (injection-value block)}
                       {:type "text" :text text}]}))
        (range)
        blocks))

(defn card-entry?
  "Does MESSAGE carry one of the cards this namespace names -- the part a person sees
  without the model ever being handed it?

  STRUCTURAL, so it answers for both spellings: the session entries a birth writes
  (`opening-entries`, a part vector) and the messages a run was handed (which never
  carry one -- `provider-part` refuses a `data` part by name)."
  [message]
  (let [c (:content message)]
    (boolean (and (sequential? c) (some #(= injected-part-name (:name %)) c)))))

(defn client-never-sent
  "The entries of ADDED whose ids the client did not send in SENT -- the messages this
  run wrote into the conversation ON THE CLIENT'S BEHALF: the birth context and the
  opening blocks, at the birth. Empty on every other run, because an action adds what
  the client sent and nothing else.

  THE ID IS THE IDENTITY, the same rule `harness.edge.sessions/append!` applies: what
  the client sent is recognised by id, not by position, so a retried action is an
  action that added what the conversation already held."
  [added sent]
  (let [mine (into #{} (keep :id) sent)]
    (into [] (remove #(contains? mine (:id %))) added)))

(defn- wire-message
  "One entry -> the message a `MESSAGES_SNAPSHOT` may carry.

  AG-UI VALIDATES EVERY FRAME IT PARSES (`@ag-ui/client` runs its own schema over each
  event of the run), and its message schema has `content` as TEXT -- or as an input
  block this home does not use. This home's entries carry PARTS: a card for the screen,
  the text the model reads, an image the person attached. So the projection keeps the
  id, the role and the TEXT, and drops the rest.

  WHY DROPPING THE CARD PART IS NOT LOSING IT: the reader draws the card from the id and
  the text (`ui/src/lib/injections.ts`, `isOpeningEntryId`), and the text is the same
  bytes the card's value carries -- the server builds both from the one block
  (`opening-entries`). A message the CLIENT sent needs no projection at all: it is
  already the shape the client's own conversion produced, which is what the schema it is
  about to be parsed by accepts."
  [{:keys [content] :as message}]
  (assoc message
         :content (if (sequential? content)
                    (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) content))
                    (str content))))

(defn conversation-snapshot
  "ENTRIES -> the AG-UI `MESSAGES_SNAPSHOT` frame that puts them on the client.

  WHY A MESSAGE LIST AND NOT THE CARD FRAMES THIS REPLACES (2026-09-21, the owner's
  call). A `CUSTOM` frame is a PART: the adapter hangs it on the message being
  streamed, and the frame's own `messageId` is dropped on the way in (upstream's parser
  does not read the field), so a card the client never held a message for lands under
  the answer instead of in the column the record puts it in. `MESSAGES_SNAPSHOT` is the
  AG-UI frame for exactly this: the conversation, as messages, ids included -- which is
  the copy the window and `sofar` already answer with, on the one run that has no window
  to answer through (the run that BIRTHS a conversation, whose opening and context the
  client never sent).

  THE MESSAGES ARE PROJECTED (`wire-message`): what the client sent is already in the
  wire's own shape, and what the birth wrote is not -- and the wire's schema is strict
  enough to refuse a part vector outright (measured 2026-09-21: a `data` part in a user
  message fails the client's parser and the run dies with a Zod error on screen).

  WHAT IT COSTS: one frame carrying the conversation, on that run only. The record
  keeps it like any other frame it sent (`jsonl-two-kinds`: a frame is a row), so a
  rebuild reproduces it and a reader that has never heard of it ignores it
  (`harness.kernel.frames/apply-frames` has no case for it -- the conversation is
  already there, in the entries)."
  [entries]
  {:type "MESSAGES_SNAPSHOT" :messages (mapv wire-message entries)})

;; ------------------------------------------------------------ input modality
;;
;; What a run is ABOUT to send, as modality keywords, so it can be checked against
;; what the selected model declared it accepts. This is deliberately a SEPARATE
;; question from provider-part's: that one refuses what the harness cannot CARRY
;; at all, this one refuses what the chosen model did not declare. A harness that
;; can carry images still has text-only models.
;;
;; The answer is read off the CLIENT's messages rather than the translated ones,
;; because the client's spelling is the human-facing one -- an error can say
;; ":image" rather than ":image_url", which is the difference between a message a
;; person can act on and one they have to decode.

(def ^:private part-modality
  "AG-UI content part type -> the modality it exercises. A part type absent here
  is not this check's business: provider-part refuses it with a better message
  than 'the model does not accept it' would be."
  {"text" :text "image" :image})

(defn carried-input-types
  "The modalities the client's MESSAGES carry, as a set. A string content carries
  :text (a run always sends text, even when it sends a picture alongside it); a
  part vector contributes whatever its parts name.

  Only USER messages are inspected. The other roles in a rebuilt conversation come
  from us -- the assistant's own turns, the tool results -- and a model cannot be
  blamed for what it already said: an assistant turn holding an image is a fact
  about history, not a request the model has to accept."
  [messages]
  (into #{}
        (comp (filter #(= "user" (:role %)))
              (mapcat (fn [m]
                        (let [c (:content m)]
                          (if (sequential? c)
                            (keep #(get part-modality (:type %)) c)
                            [:text])))))
        messages))

(def ^:private title-limit
  "How much of the first message is kept as a session's name: a STORAGE guard, not a
  display rule. `sessions.title` is carried for every session in the sidebar's one
  listing, so a first message that was a pasted file must not make that payload
  megabytes; what the eye is shown is the client's business and is shorter still
  (`ui/src/lib/session-title.ts`). 200 characters is far past any title and far
  short of any paste."
  200)

(defn- clip-codepoints
  "S at most N CODEPOINTS. `subs` counts UTF-16 units, so clipping at 200 with it
  can cut an emoji in half and store a lone surrogate -- the client's clip counts
  code points for the same reason, and this is the storage twin of it."
  [^String s n]
  (if (<= (.codePointCount s 0 (.length s)) n)
    s
    (subs s 0 (.offsetByCodePoints s 0 n))))

(defn message-text
  "One message's text, as a person would read it: a string content as it stands, a
  part vector with its text parts joined by newlines. Parts of other modalities
  contribute nothing -- an image is not a name.

  PUBLIC BECAUSE TWO SIDES HAVE TO AGREE ON IT. The record's conversation carries an
  injected message's CARD part and the submitted side never does
  (`harness.edge.sessions/model-view` drops it), so `harness.edge.trajectory/shape`
  compares this reading rather than the raw content -- otherwise every opening entry
  would look like a message the client never sent, and the alignment would file the
  person's own question as something the server injected."
  [message]
  (let [c (:content message)]
    (cond
      (string? c) c
      (sequential? c) (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) c))
      :else nil)))

(defn first-user-text
  "WHAT THE PERSON FIRST SAID IN MESSAGES, or nil: the first `user` message, trimmed
  and clipped to a length a store may hold.

  IT IS HANDED THE MESSAGES, NOT A RUN BODY. A run no longer carries the conversation
  (ADR 0002 decision 9: the body's `messages` retired, and `harness.edge.http` refuses a
  body that names it), so a run body has nothing for this to read. The caller is the one
  that holds the conversation -- `harness.edge.http/run-agent!` hands over the session's
  own messages plus the entries THIS action adds, in that order -- and what it gets back
  is the first turn of the conversation rather than of the request.

  IT IS THE FIRST USER MESSAGE AND NOT THE LAST, which is what makes it usable as a
  session's name: the sequence the caller hands over begins at the conversation's
  beginning, so this answers the same thing on the first turn and on the fortieth -- the
  sidebar's title for a session that ran before this harness kept titles is picked up
  the next time it runs.

  ONLY `user` ROLES COUNT, for the reason `carried-input-types` gives one screen up:
  everything else in the vector is ours -- the system prompt, the assistant's own
  turns, tool results. The user messages in it are the CLIENT's, with TWO exceptions
  that are skipped by name (`injected?`, which is the one place that rule lives): the
  opening context this edge writes in at birth (`context-entry`) and the session's
  opening blocks (`opening-entries`). Both are ordinary user messages by design and
  neither is something a person said. Skipped by id rather than by position, because
  they enter the conversation at birth and every later run sees them wherever the birth
  put them.

  BLANK TURNS ARE SKIPPED RATHER THAN NAMED: the first message that SAYS something is
  the answer, which is what 'the first thing they said' means when the first thing
  they sent was an empty line."
  [messages]
  (let [said (->> messages
                  (remove injected?)
                  (filter #(= "user" (:role %)))
                  (keep message-text)
                  (map str/trim)
                  (remove str/blank?)
                  first)]
    (when said
      (clip-codepoints said title-limit))))

(defn undeclared-input
  "The modalities in MESSAGES that DECLARED does not cover, sorted. Nil DECLARED
  means nothing was declared, and nothing declared means nothing promised -- so
  the answer is empty and the run proceeds. Guarding an undeclared model would be
  guessing on its behalf, and an inline provider that never claimed to be
  text-only would start failing runs for a reason nobody wrote down."
  [messages declared]
  (if (nil? declared)
    []
    (vec (sort (remove (set declared) (carried-input-types messages))))))

(defn provider-messages
  "MESSAGES -> what a provider is handed for them: the cards gone, every part translated,
  reasoning folded into the assistant message it precedes.

  PUBLIC BECAUSE THE RECORD NEEDS EXACTLY THIS VIEW. A `message` row IS an element of the
  messages array the model was handed (owner, 2026-09-21), so a row's payload is the
  message as the PROVIDER reads it -- an AG-UI `source` wrapper or a `data` card under a
  `message` row would be a record lying about the one thing it exists to keep
  (`harness.edge.http/run-agent!` writes each entry's row through here). `absorbed` is the
  same fold `inbound` uses, which is what keeps the live run and the record from drifting."
  [messages]
  (absorbed messages))

(defn provider-array
  "MESSAGES THAT ARE ALREADY IN THE PROVIDER'S SHAPE + PROMPT -> the array a provider is
  handed.

  `inbound` IS FOR AG-UI MESSAGES: it translates the parts and folds the reasoning away.
  THIS is for the ones that are already what a provider reads -- which is what the RECORD
  holds, because since `.scratch/jsonl-two-kinds` 票 02 an entry's row carries the message
  the provider was given (`provider-messages` is what wrote it). Feeding those back through
  `absorbed` a second time has to be a no-op, and it is: `provider-shaped?` answers by the
  SPELLING of the parts and fields, so a row that already says `image_url` is passed through
  rather than translated again (2026-09-21 -- before the envelope's `:id` stopped counting as
  a spelling, this was the refusal that made a picture-bearing session uncontinueable).

  THE SYSTEM MESSAGE IS STILL ASSEMBLED HERE, and only here: the prompt REPLACES a leading
  system message or is prepended, which is the one rule `inbound` and a rebuild both need --
  the prefix the provider's cache keys on. Written once so a live run and a rebuild cannot
  disagree about it."
  [messages prompt]
  (let [msgs (vec messages)
        sys  {:role "system" :content prompt}]
    (if (= "system" (get-in msgs [0 :role]))
      (assoc msgs 0 sys)
      (into [sys] msgs))))

(defn inbound
  "A conversation's AG-UI messages -> the provider's message vector.
  PROMPT is the FROZEN system prompt text. A leading system message is replaced
  by it; otherwise it is prepended. CONTEXT, when given, becomes the message the
  session was born with (`context-entry`), placed after the messages that are already
  in the conversation. IT MUST NEVER TOUCH THE SYSTEM MESSAGE -- the provider's prefill
  (prompt cache) keys on a stable prefix, so a per-run system prompt would miss it
  every call.

  NOTHING IS SPLICED IN HERE, and that is the change of 2026-09-21
  (`.scratch/session-opening`). The session's opening blocks -- the instruction files
  and the skills catalog (`harness.cap.preamble`) -- used to arrive as a BLOCKS argument
  and go AFTER the conversation. They now enter the conversation itself, ONCE, when the
  conversation is born (`opening-entries`), so they arrive here as ordinary entries of
  MESSAGES and stand in front of the question on every run that follows. What a run
  still derives for itself -- a skill body, a job's ending -- is folded in by the CALLER
  (`harness.cap.project/before-llm`) and stays behind the conversation, which is where
  the prefill note above wants the changing half anyway.

  A RUN NO LONGER CARRIES THE CONVERSATION and no longer carries a per-run context
  either (ticket 03 of `.scratch/sessions-live-on-the-server`): the edge hands over the
  session's messages, which already hold the opening context, and CONTEXT is what the
  VERY FIRST of those runs passes so that the message enters the conversation. It stays
  an argument because this is a converter and the caller is the one that knows whether
  the conversation has a beginning yet -- the rebuild path and the protocol tests pass
  what the record holds."
  [messages prompt context]
  (provider-array (absorbed (cond-> (vec messages)
                              (seq context) (conj (context-entry context))))
                  prompt))

(defn place-updates
  "A provider array already built by `inbound` + UPDATES -> the same array with one
  `developer` message per update inserted before the LAST user message.
 
  WHERE IT GOES AND WHY. `system -> history -> developer updates -> the question`:
  the update is the PREMISE of the question, so it stands before it, while the
  context and skill bodies the pre-LLM step appends are MATERIAL FOR the question and
  stay behind it (`.scratch/instruction-updates` decision 3). The message is the FULL
  new instruction text, not a diff -- the vendor's instruction slot overwrites, and a
  diff would ask the model to merge two sources.
 
  IT ANSWERS NIL WHEN IT CANNOT PLACE THEM, and that is a fact the caller must act on
  rather than a failure to swallow: a history whose last message is not a user turn has
  no 'before the question' to be before, and guessing a position would put the update
  somewhere the model was never handed it. The caller falls back to :replace and says
  so (decision 3). An empty UPDATES returns MESSAGES unchanged.
 
  THE CHAIN IS RESENT EVERY RUN because the client does not hold these messages --
  they are not entries of the conversation and never come back. Their price is the
  tail, which every run re-sends anyway; the SHARED PREFIX (message[0] plus the
  history the client restates) is what stays cached, and that is the whole difference
  from :replace."
  [messages updates]
  (if (empty? updates)
    messages
    (let [msgs (vec messages)
          i    (dec (count msgs))]
      (when (and (>= i 0) (= "user" (:role (nth msgs i))))
        (into (subvec msgs 0 i)
              (concat (map (fn [text] {:role "developer" :content text}) updates)
                      (subvec msgs i)))))))
