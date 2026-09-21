(ns harness.edge.ag-ui
  "Conversion between the kernel's flat event stream and AG-UI wire events.

  OUTBOUND (outbound) is a stateful fold, because AG-UI constrains message and tool-call
  ids to be opened and closed in order while the kernel's events carry no such
  structure. That state lives here and nowhere else -- it never leaks into the kernel.

  The shape it produces, per assistant turn:
    reasoning group (optional)  ->  text message  ->  tool calls parented to it
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
    (-> s (assoc :reasoning nil)
          (update :frames conj {:type "REASONING_MESSAGE_END" :messageId id}
                              {:type "REASONING_END" :messageId id})
          ;; An assistant message must always follow the reasoning group. Without this,
          ;; a turn that produced reasoning and then nothing -- which is precisely what
          ;; a max_tokens-exhausted think looks like, finish_reason "length" with empty
          ;; content -- would leave the reasoning with no assistant message to fold
          ;; back onto, and DeepSeek answers 400 on the next turn.
          open-text)
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
  waiting for a person to answer a server's question (`elicitation`). REASON is
  what tells a client which card to draw, and MESSAGE is what it puts on the card
  -- for a question that is the question itself, because 'Approve ask? arguments'
  would be a sentence about a tool call rather than the thing being asked."
  [{:keys [id tool-call-id name args reason question]}]
  (if (= :elicitation reason)
    {:id id
     :reason "elicitation"
     :message (or (:prompt question) "A server is asking you for input.")
     :toolCallId tool-call-id}
    {:id id
     :reason "tool-approval"
     :message (str "Approve `" name "`? arguments: " args)
     :toolCallId tool-call-id}))

(defn injected-frame
  "The CUSTOM frame for one message a run was handed without the client sending it:
  a skill body, an instruction block, a catalog, the ending of a background job.

  ONE PLACE BUILDS THIS SHAPE, because two do emit it: the kernel's pre-LLM step
  (through `step` below, for whatever it splices) and the edge itself, for the
  session's opening blocks -- the edge assembles those (with their hooks) before the
  kernel is handed anything, so it is the edge that knows they happened.

  WHAT MAKES THIS FRAME SPECIAL, and the whole feature rests on it: the client draws
  it and NEVER SENDS IT BACK. `@assistant-ui`'s adapter turns a CUSTOM event into a
  `data` part, and its outgoing conversion carries text, reasoning and tool calls
  only -- a data part has no case there. So a person sees what the model was handed,
  and the conversation stays the client's.

  `messageId` IS OURS AND DETERMINISTIC, unlike the text and tool ids: the rebuild
  folds one card message per frame (`harness.kernel.frames/apply-frames`) and hands
  the client the same ids back, so a card survives a refresh under the same name."
  [message-id message]
  {:type "CUSTOM" :name "injected-context" :messageId message-id
   :value {:role (:role message) :text (str (:content message))}})

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
    (let [s (-> s close-reasoning open-text)]
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

    :tool/result
    (let [id (str (:run-id s) "-t" (:n s))
          s  (-> s (update :n inc) close-text)]
      (update s :frames conj {:type "TOOL_CALL_RESULT" :messageId id :toolCallId (:id ev)
                              :content (:content ev) :role "tool"}))

    (:tool/pre-execute :tool/execute :tool/post-execute
     :model/end)
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
          (update :frames conj {:type "RUN_ERROR" :message (:message ev)}))))

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

(def ^:private ag-ui-only #{:id :encryptedValue :subagentRunId :metadata :activityType})

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

(defn- provider-part
  "One AG-UI content part -> the provider's. An unknown part type is a NAMED
  failure, never a pass-through: a part forwarded untouched reaches the vendor as
  a shape it does not know, and the 400 that comes back names nothing useful.
  Refusing here is what turns 'the vendor rejected the request' into 'this harness
  cannot carry a :document part'."
  [part]
  (case (:type part)
    "text"      (select-keys part [:type :text])
    "image"     (provider-image-url part)
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

(defn- absorbed
  "Drop activity, fold reasoning into the assistant message it precedes, and rebuild
  each message in the provider's shape -- content parts translated on the way.

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
           messages)))

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
    {:id      "session-context"
     :role    "user"
     :content (str/join "\n" (map #(str "- " (:description %) ": " (:value %)) context))}))

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

(defn- tail-blocks
  "MSGS with BLOCKS spliced in AT THE END, after the conversation.

  THE ORDER IS THE POINT: system prompt, then the question, then what the run was
  handed for it (and a skill body last of all -- `cap.skills/derived-injections`
  appends its own). A model reads the question first and the material for it
  immediately after, which is where a person would put it.

  IT COSTS THE PREFILL NOTHING. The prompt cache keys on a stable PREFIX: that prefix
  is the system message plus the conversation as the session has it, and the injections
  were never part of it -- they used to sit between the two and are now behind both,
  which leaves the cacheable prefix exactly as long as the conversation made it.

  An EMPTY BLOCKS returns MSGS ITSELF, not an equal vector: this is the path every
  caller takes when a session has no instruction files and no skills, and the shape of
  the result is the regression guarantee the whole feature rests on (a session with
  nothing configured sends byte-for-byte what it sent before)."
  [msgs blocks]
  (if (empty? blocks)
    msgs
    (into msgs blocks)))

(defn inbound
  "A conversation's AG-UI messages -> the provider's message vector.
  PROMPT is the FROZEN system prompt text. A leading system message is replaced
  by it; otherwise it is prepended. CONTEXT, when given, becomes the message the
  session was born with (`context-entry`), placed after the messages that are already
  in the conversation and before this run's blocks. IT MUST NEVER TOUCH THE SYSTEM
  MESSAGE -- the provider's prefill (prompt cache) keys on a stable prefix, so a
  per-run system prompt would miss it every call.

  A RUN NO LONGER CARRIES THE CONVERSATION and no longer carries a per-run context
  either (ticket 03 of `.scratch/sessions-live-on-the-server`): the edge hands over the
  session's messages, which already hold the opening context, and CONTEXT is what the
  VERY FIRST of those runs passes so that the message enters the conversation. It stays
  an argument because this is a converter and the caller is the one that knows whether
  the conversation has a beginning yet.

  BLOCKS are what this run was handed on top of the conversation -- the session's
  instruction files and skills catalog (harness.cap.preamble), already rendered. They
  go AFTER the conversation, in the order the model should read them: the question
  first, then the material for it. They are ordinary user messages: nothing
  in this namespace becomes an AG-UI frame (the edge emits one CUSTOM frame per block
  it spliced, which is how a person sees them -- see `injected-frame`).

  This namespace stays a CONVERTER: it is handed the blocks rather than reading
  anything itself. The three-arity is the shape without them -- what the rebuild
  path and the protocol tests use -- and a session with nothing configured passes
  the same thing through it."
  ([messages prompt context] (inbound messages prompt [] context))
  ([messages prompt blocks context]
   (let [msgs (absorbed (cond-> (vec messages)
                          (seq context) (conj (context-entry context))))
         sys  {:role "system" :content prompt}
         msgs (if (= "system" (get-in msgs [0 :role]))
                (assoc msgs 0 sys)
                (into [sys] msgs))]
     (tail-blocks msgs blocks))))
