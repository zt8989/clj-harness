(ns harness.kernel.llm
  "Provider layer. One multimethod, dispatched on :protocol.

  Contract for every method:
    (stream! provider messages on-event thread-id)
      -> {:message   <provider-shaped assistant message>
          :telemetry <what the vendor said ABOUT the call>}

  ON-EVENT is called with each harness.kernel.event value as it is produced. The returned
  MESSAGE is provider-shaped and is appended to the history VERBATIM by
  harness.kernel.loop -- never rebuilt. That is what keeps reasoning_content alive across
  tool rounds, which DeepSeek requires whenever the request carries tools
  (omitting it there is a hard HTTP 400).

  THE TELEMETRY IS A SECOND THING, and it is not part of the message: the vendor's
  usage (prompt/completion/cached tokens), its finish_reason, and the model name it
  echoes back. It belongs to the CALL, not to the conversation -- replaying it into a
  later request would be inventing a field the vendor never asked for. So it travels
  beside the message, the kernel puts it on the `model/end` audit line, and it never
  goes on the wire. A method that has nothing to report returns an empty map, which is
  honest: 'this round reported nothing' is not 'this round reported zero'.

  THE REQUEST'S TOOL TABLE RIDES ON THE PROVIDER MAP, as :tools -- RESOLVED BY THE
  CALLER (harness.kernel.loop), not here. The caller is also what writes the
  `model/start` audit line, and the table on that line has to be the table that went
  out: two resolutions would be two tables that happen to agree, and 'described
  exactly like this' would stop being a fact about the request. So this layer does
  NOT reach for the thread's toolset at all, and THREAD-ID is opaque to the methods.

  VERBATIM INCLUDES THE FIELD'S PRESENCE, not just its text: a thinking-mode vendor
  mentions `reasoning_content` on every round, empty when the round had no reasoning,
  and it demands the field back -- so an empty mention is kept as an empty value rather
  than dropped (`consume-sse`). A history that arrives WITHOUT it -- a session rebuilt
  from a record written before this rule -- is repaired on the way out by
  `thinking-mode-history`, which the edge applies before the `message` audit line is
  written. See .scratch/reasoning-round-trip/spec.md for the verified vendor behaviour.

  The system prompt's carrier lives here too: prompt.md is read once and frozen
  (see `prompt` / `reset-prompt!` below), because the provider's prefix cache is
  what makes the freezing matter.

  A provider is just a config map, so (harness.cap.providers/current-provider) or
  (harness.cap.providers/effective-provider) can be handed straight to loop/run-chan:
    {:protocol :openai-completions, :base-url .., :model .., :api-key ..
     :reasoning-effort ..}

  WHAT WENT OUT AND WHAT CAME BACK can also be written down for reading later, and
  that is a different thing from the telemetry above: the telemetry is a fact the run
  acts on, while the traffic log (harness.infra.llm-debug) is evidence for a question
  asked afterwards -- 'why did the vendor's prefix cache miss on this call', say,
  which only the exact request bytes can answer. It is OPT-IN
  (CLJ_HARNESS_LLM_DEBUG) and best-effort: the wire path never waits on it and never
  fails because of it.

  :reasoning-effort is present only when some tier chose one. :input/:output and
  the two counts (:context-window / :max-output-tokens) are not read here at all:
  they describe what a model is, which is the edge's business
  (harness.edge.http/guard-input-modalities!) and the catalog's, not the wire's. In
  particular :max-output-tokens is NOT sent as max_tokens -- the vendor's own
  default decides how much a response may hold, and this harness does not
  second-guess it."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.infra.llm-debug :as llm-debug]
            [harness.kernel.event :as ev])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(defmulti stream!
  (fn [_provider _messages _on-event _thread-id] (:protocol _provider)))

;; ------------------------------------------------------------------- prompt

;; The system prompt's carrier and its freezing discipline live here, with the
;; provider layer, because that is whose constraint it is: the provider's prefill
;; (prompt cache) keys on a byte-identical first message.

(defonce frozen-prompt (atom nil))

(defn prompt
  "The system prompt, FROZEN: prompt.md is read once -- on the first call -- and
  every run after that reuses the same text. The provider's prefill (prompt
  cache) keys on a stable prefix; a system prompt that changes per run would
  miss it on every call. Editing prompt.md takes effect only after
  (reset-prompt!) or a process restart."
  []
  (or @frozen-prompt
      (reset! frozen-prompt (slurp "prompt.md" :encoding "UTF-8"))))

(defn reset-prompt!
  "Re-read prompt.md into the frozen slot. The deliberate counterpart of
  freezing: the agent -- or you, in the REPL -- opts into a new prefix, trading
  one cold prefill for the change."
  []
  (reset! frozen-prompt nil))

;; ------------------------------------------------------------ openai-completions

(defonce ^:private http-client
  (-> (HttpClient/newBuilder)
      (.version HttpClient$Version/HTTP_1_1)     ; deterministic streaming
      (.build)))

(defn- request [{:keys [base-url api-key]} body]
  (-> (HttpRequest/newBuilder (URI/create (str (str/replace base-url #"/+$" "") "/chat/completions")))
      (.header "Authorization" (str "Bearer " api-key))
      (.header "Content-Type" "application/json")
      (.header "Accept" "text/event-stream")
      (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
      (.build)))

(defn- data-payloads [lines]
  (for [line lines
        :when (str/starts-with? line "data:")
        :let  [payload (str/trim (subs line 5))]
        :when (not= "[DONE]" payload)]
    payload))

(defn- reasoning-field
  "DELTA's reasoning -> [text present?]. PRESENT? says the vendor MENTIONED the field
  at all, which is not the same as it carrying text, and the difference matters on
  the way out: see `consume-sse`.

  Two spellings are accepted, because the vendors disagree and neither is wrong:
  DeepSeek uses `reasoning_content`, OpenRouter proxies it as `reasoning`. One
  definition for both the assembly and the emit, so a third spelling would be added
  in one place."
  [delta]
  (cond
    (contains? delta :reasoning_content) [(str (:reasoning_content delta)) true]
    (contains? delta :reasoning)         [(str (:reasoning delta)) true]
    :else                                [nil false]))

(defn- absorb!
  "Fold one chunk's delta into TEXT, THINK and CALLS.
  Tool-call fragments arrive spread across chunks: the first carries id and name,
  later ones only index plus an arguments fragment. Key by index and concatenate."
  [text think calls delta]
  (when-let [c (:content delta)] (.append text c))
  (let [[r present?] (reasoning-field delta)]
    (when present? (.append think (or r ""))))
  (doseq [tc (:tool_calls delta)]
    (let [i (:index tc)]
      (when-let [id (:id tc)] (swap! calls assoc-in [i :id] id))
      (when-let [n (get-in tc [:function :name])] (swap! calls assoc-in [i :name] n))
      (swap! calls update-in [i :arguments]
             (fnil str "") (get-in tc [:function :arguments] "")))))

(defn- speak!
  "Emit deltas only when they carry something. The opening chunk of every stream is
  {\"role\":\"assistant\",\"content\":\"\"}, and an empty string is TRUTHY in Clojure:
  emitting it would open a text message ahead of the reasoning, which breaks the
  adjacency rule ag-ui relies on to fold reasoning back onto its assistant message."
  [delta emit]
  (when (seq (:content delta)) (emit (ev/text-delta (:content delta))))
  (let [[r _] (reasoning-field delta)]
    (when (seq r) (emit (ev/reasoning-delta r)))))

(defn- fold-tool-calls [calls]
  (mapv (fn [[_ t]] {:id (:id t) :type "function"
                     :function {:name (:name t) :arguments (:arguments t)}})
        (sort-by key calls)))

(defn- telemetry-fields
  "The three things a chunk says ABOUT the call rather than IN it: the vendor's
  usage, its finish_reason, and the model name it echoes back. Returned as the
  partial map this chunk contributes, merged last-wins into the stream's telemetry.

  THE KEYS ARE TAKEN AS THEY ARRIVE and never renamed -- `:usage` keeps whatever
  the vendor put inside it (OpenAI-compatible vendors spell cached tokens
  `prompt_tokens_details.cached_tokens`; a fold that wanted a translated key
  would be guessing at a second spelling this repo has no evidence for).

  A KEY IS WRITTEN ONLY WHEN THE CHUNK HAS A NON-NIL VALUE FOR IT, and a `null` is
  never allowed to stand in for a real one. Most chunks carry `\"finish_reason\": null`,
  and a vendor that reports usage at all usually carries `\"usage\": null` on every chunk
  BUT the last -- so a fold that wrote every occurrence would erase the one chunk that
  said `tool_calls`, or the one that carried the numbers. The second loss is the worse
  one: usage is the number this whole log exists to make readable
  (`prompt_tokens_details.cached_tokens`), OURS is the only copy of it -- the vendor is
  not asked twice -- and a wiped usage does not look wiped, it looks like a call that
  reported nothing."
  [chunk]
  (cond-> {}
    (some? (:usage chunk)) (assoc :usage (:usage chunk))
    (some? (:model chunk)) (assoc :model (:model chunk))
    (some? (get-in chunk [:choices 0 :finish_reason]))
    (assoc :finish-reason (get-in chunk [:choices 0 :finish_reason]))))

(defn tee-lines
  "LINES -> the same lines, each one (plus a newline) also appended to SB as it is
  realized. The raw frame text, for the traffic log.

  WHY THE RAW TEXT IS WORTH THE BYTES: a folded `:telemetry` is a READING of the stream,
  and a reading cannot be checked against its source once the source is gone. 'The
  vendor never mentioned cached tokens' and 'our fold dropped them' look identical in a
  folded map, and only the second one is a bug -- so the evidence behind the reading
  stays on the record.

  THE LINES ARE ALREADY DECODED TEXT, which is what keeps this honest: what lands is the
  vendor's own frame text (`data: {...}`), NOT a re-serialization of the parsed chunks --
  the same property the request line has, for the same reason (the prefix cache keys on
  bytes, so a second spelling is not evidence about the wire). ONE THING IS NORMALIZED,
  said here rather than left to be discovered: `line-seq` has already dropped the line
  terminators, so a `\\n` is put back and a CRLF vendor reads as LF. The frame text --
  which is what any question is actually about -- is untouched.

  LAZY ON PURPOSE, so this cannot make the stream wait for its last line: it is the
  consumer's realization that fills SB. A stream that dies mid-way therefore leaves a
  PARTIAL record of what did arrive, which is exactly the case somebody is reading."
  [^StringBuilder sb lines]
  (map (fn [line] (.append sb line) (.append sb "\n") line) lines))

(defn consume-sse
  "Fold a seq of SSE lines into the assistant message AND the call's telemetry,
  calling EMIT for each event.
  Pure over LINES -- the network layer only supplies them, which is what makes the
  parser testable against a recorded body with no network at all.

  Returns {:message <assistant message> :telemetry <map>}, and the telemetry map is
  EMPTY when the stream reported nothing about the call -- which is what a stream
  that died mid-way looks like, and is not the same as one that reported zeroes."
  [lines emit]
  (let [text      (StringBuilder.)
        think     (StringBuilder.)
        calls     (atom {})
        seen?     (atom false)
        telemetry (atom {})]
    (doseq [payload (data-payloads lines)]
      (let [chunk (json/read-str payload :key-fn keyword)
            delta (get-in chunk [:choices 0 :delta])
            [_ present?] (reasoning-field delta)]
        (swap! telemetry merge (telemetry-fields chunk))
        (when present? (reset! seen? true))
        (absorb! text think calls delta)
        (speak! delta emit)))
    (let [assembled (fold-tool-calls @calls)]
      ;; Emitted only once fully assembled: no incremental args, and therefore no
      ;; state machine that can be cut off in the middle of a JSON string.
      (doseq [{:keys [id function]} assembled]
        (emit (ev/tool-call id (:name function) (:arguments function))))
      {:message
       (cond-> {:role "assistant" :content (str text)}
         ;; THE FIELD IS KEPT WHEN THE VENDOR MENTIONED IT, EMPTY INCLUDED -- which is
         ;; not the same rule as 'when there is text'. A thinking-mode vendor that has
         ;; nothing to reason about still sends the field, and it REQUIRES it back on
         ;; the next request (a DeepSeek-compatible gateway answers HTTP 400 otherwise:
         ;; 'The reasoning_content in the thinking mode must be passed back to the API').
         ;; Answering 'the vendor said nothing' with silence is what this used to do,
         ;; and it is what made the next request impossible: see
         ;; `thinking-mode-history` and .scratch/reasoning-round-trip/spec.md.
         @seen?          (assoc :reasoning_content (str think))
         (seq assembled) (assoc :tool_calls assembled))
       :telemetry @telemetry})))

(defn thinking-mode-history
  "MESSAGES -> the history a THINKING-MODE vendor must be shown, which is the same
  history with one requirement met: **every assistant message carries
  `reasoning_content`**, an empty string when there was none.

  IT IS THE VENDOR'S RULE, NOT OUR TIDINESS. A DeepSeek-compatible gateway refuses a
  thinking-mode request whose history holds an assistant message without that field
  -- `The reasoning_content in the thinking mode must be passed back to the API.`,
  HTTP 400 -- and it refuses even when the round it objects to produced no reasoning
  at all. That is exactly the case this exists for: the vendor signals 'no reasoning
  this round' as an EMPTY value on the wire, our assembly reads that as 'nothing to
  say' and writes no key, and the next request is refused. Verified against a real
  vendor on 2026-09-16 -- the same history 400s without the key and streams 200 with
  `\"\"`; the transcripts are in `.scratch/reasoning-round-trip/evidence/`, and
  `harness.fake`'s strict mode answers with that vendor's own sentence.

  AN EMPTY STRING IS THE HONEST FILL: not reasoning the model did not produce, but
  the fact that this round had none, said in the shape the vendor demands. Anything
  else -- the previous round's reasoning, a summary -- would be putting words in the
  model's mouth and sending them back as if it had thought them.

  A NON-THINKING PROVIDER IS UNTOUCHED, byte for byte: with no :reasoning-effort the
  vendor never enters thinking mode, the field means nothing to it, and adding one
  would be our invention rather than its requirement.

  CALLED WHERE THE RUN'S MESSAGES ARE ASSEMBLED rather than inside `stream!`, so that the
  array handed to the provider is the array the run reasoned about -- and NOT inside the
  record's writer: since `.scratch/jsonl-two-kinds` 票 02 a run logs the ENTRIES it was
  handed (each one a `message` row, in the provider's own shape), and this pad is the
  wire's requirement rather than a statement about what entered the conversation -- it
  writes an empty `reasoning_content` onto a message nobody sent one for. The record keeps
  the message; the vendor's demand is met on the way out. See
  harness.edge.http/run-agent!."
  [messages provider]
  (if-not (:reasoning-effort provider)
    messages
    (mapv (fn [m]
            (if (and (= "assistant" (:role m))
                     (not (contains? m :reasoning_content)))
              (assoc m :reasoning_content "")
              m))
          messages)))

(defn unanswered-tool-calls
  "MESSAGES -> the tool-call ids this request would leave UNANSWERED, in the order a
  vendor walks the list. Empty when the history is well shaped, which is nearly every
  run: this is a reader of a shape some run produced, not a rule the run has to keep.
  
  THE VENDOR'S RULE, WRITTEN DOWN ONCE. An assistant message carrying `tool_calls`
  must be followed, IMMEDIATELY, by a tool message for each of its ids, and an
  OpenAI-shaped vendor refuses a request that breaks it with HTTP 400 -- verbatim: 'An
  assistant message with tool_calls must be followed by tool messages responding to
  each tool_call_id. (insufficient tool messages following tool_calls message)'. It
  refuses before the model runs, and the sentence names neither the call nor the
  reason, which is the whole problem with learning this from the vendor.
  
  A CALL IS ANSWERED BY THE TOOL MESSAGES DIRECTLY BEHIND ITS ASSISTANT MESSAGE, not
  by a tool message somewhere further down: an id answered late is still a refusal on
  the wire, because what the vendor checks is ADJACENCY. Same rule as the repair in
  harness.edge.replay/open-runs, one layer up -- there it is about a log, here about
  the list a run is about to send.
  
  WHO ASKS, and why the answer is only ever a set of ids: harness.kernel.loop runs it
  at the top of every run to tell a call that is STILL BEING DECIDED (this process
  holds the park; the run asks the client again) from one NOBODY CAN ANSWER (no park
  anywhere; the run is refused by name before the vendor is), and
  harness.test-support's vendor-shaped provider runs the same function so a test can
  meet the refusal production meets."
  [messages]
  (into []
        (comp (mapcat (fn [i]
                        (let [ids (seq (map :id (:tool_calls (nth messages i))))]
                          (when ids
                            (let [answered (into #{} (keep :tool_call_id)
                                                 (take-while #(= "tool" (:role %))
                                                             (drop (inc i) messages)))]
                              (remove answered ids))))))
              (distinct))
        (range (count messages))))

(defn adjacent-answers
  "MESSAGES -> the same messages, with every recorded tool answer sitting DIRECTLY BEHIND
  the assistant message that named its call, in call order.
  
  THE OTHER HALF OF `unanswered-tool-calls`, and it exists because a RECORD CAN DELIVER AN
  ANSWER LATE. A run cut off mid-call is repaired by
  `harness.edge.replay/closing-frames`, whose TOOL_CALL_RESULT is APPENDED to the log --
  after whatever else the client recorded in the meantime, its next messages included.
  Folded back in file order, that answer lands BEHIND those messages, so the call reads as
  unanswered and the vendor refuses the whole conversation before the model runs: the
  session is bricked even though a result for the call is sitting right there.
  
  MOVING IT IS NOT INVENTING A RESULT: the message is already in the history, and this is
  the same placement `harness.kernel.loop/answer!` makes for a replayed call, one layer up.
  What it never does is invent one: an answer with no assistant message in the list to sit
  behind is LEFT WHERE IT IS, exactly as `answer!` leaves an unplaced replay -- nothing
  here reports it, because there is no call for the vendor to miss either.
  
  A WELL-SHAPED HISTORY COMES BACK UNCHANGED, message for message: an answer already
  directly behind its call is emitted where it was, and the rest of the list does not move."
  [messages]
  (let [msgs    (vec messages)
        ;; THE ASSISTANT MESSAGE THAT NAMED EACH CALL, by call id -- the same reading
        ;; `unanswered-tool-calls` walks, so the two cannot disagree about what a call is.
        owner   (into {}
                      (for [[i m] (map-indexed vector msgs)
                            :when (= "assistant" (:role m))
                            tc    (:tool_calls m)]
                        [(:id tc) i]))
        ;; CALL ID -> THE INDICES OF THE TOOL MESSAGES THAT ANSWER IT *and have a call here
        ;; to sit behind*. An answer with no owner is not movable (see the docstring).
        answers (reduce (fn [acc [i m]]
                          (let [cid (when (= "tool" (:role m)) (:tool_call_id m))]
                            (if (and cid (contains? owner cid))
                              (update acc cid (fnil conj []) i)
                              acc)))
                        {} (map-indexed vector msgs))
        moved?  (into #{} (mapcat val answers))]
    (vec
     (mapcat (fn [i]
               (let [m (nth msgs i)]
                 (cond
                   ;; THIS ONE IS EMITTED BEHIND ITS CALL, not here.
                   (contains? moved? i)
                   nil
                   ;; THE CALL: itself, then its answers in call order.
                   (= "assistant" (:role m))
                   (cons m (mapcat (fn [tc] (map #(nth msgs %) (get answers (:id tc))))
                                   (:tool_calls m)))
                   :else
                   [m])))
             (range (count msgs))))))

(defmethod stream! :openai-completions
  [{:keys [model reasoning-effort tools] :as provider} messages on-event thread-id]
  (let [body (json/write-str (cond-> {:model model
                                      :messages messages
                                      :tools tools
                                      :stream true}
                               reasoning-effort (assoc :reasoning_effort reasoning-effort)))
        ;; THE REQUEST LANDS BEFORE IT GOES OUT, so a call that never comes back is
        ;; still on the record -- a hang is exactly when somebody wants to read what
        ;; was sent. Best-effort, and off unless CLJ_HARNESS_LLM_DEBUG is set:
        ;; harness.infra.llm-debug is the file and the whole argument for it.
        _    (llm-debug/record! {:at :request :thread-id thread-id :model model
                                 :base-url (:base-url provider) :body body})
        resp (.send http-client (request provider body) (HttpResponse$BodyHandlers/ofInputStream))]
    (when-not (= 200 (.statusCode resp))
      (let [refusal (slurp (.body resp) :encoding "UTF-8")]
        (llm-debug/record! {:at :error :thread-id thread-id :model model
                            :status (.statusCode resp) :body refusal})
        (throw (ex-info (str "HTTP " (.statusCode resp) ": " refusal)
                        {:status (.statusCode resp)}))))
    ;; line-seq is lazy: it MUST be forced inside with-open, or the body leaks and
    ;; the caller deadlocks waiting on a stream nobody is draining.
    (with-open [r (io/reader (.body resp) :encoding "UTF-8")]
      (let [raw (StringBuilder.)
            out (consume-sse (tee-lines raw (line-seq r)) on-event)]
        ;; THE RESPONSE IS LOGGED AS THE VENDOR SENT IT *AND* AS WHAT IT MEANT, and it
        ;; takes both to be able to check either. `:body` is the raw frame text, the way
        ;; the request line's `:body` is the raw request -- the same argument on the
        ;; other side of the wire. `:message` and `:telemetry` stay because they are what
        ;; a reader usually wants and what a fold is FOR; the raw text is the evidence
        ;; behind them rather than a replacement for them. WITHOUT IT there was no way to
        ;; tell 'the vendor never mentioned cached tokens' from 'our fold dropped them'.
        (llm-debug/record! {:at :response :thread-id thread-id :model model
                            :body (str raw)
                            :message (:message out) :telemetry (:telemetry out)})
        out))))
