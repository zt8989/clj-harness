(ns harness.fake
  "A scripted provider, so the whole loop can be driven offline. It lives under
  test/ to stay out of the core line budget."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness.kernel.event :as ev]
            [harness.kernel.llm :as llm]))

(def ^:private chunk-size 5)

(defn- emit! [make-event s on-event pace-ms]
  ;; ABSENT IS ZERO, and that is not the same as 'the caller always says': a provider built
  ;; by a TEST (a catalog entry in config.edn, a registry fixture) never went through
  ;; `scripted`, so it carries no `:pace-ms` at all -- and `(pos? nil)` is an NPE, which is
  ;; exactly how a resolved-config run used to die with a sentence about `doubleValue`.
  (let [pause (long (or pace-ms 0))]
    (doseq [c (partition-all chunk-size s)]
      (when (pos? pause) (Thread/sleep pause))
      (on-event (make-event (str/join c))))))

;; A SHARED script for tests that need a script living in an EDN file (a
;; registry entry cannot carry an atom -- edn/read-string has no reader for
;; one). The test sets this to a fresh atom before the run; the :fake protocol
;; reads from it. The pin seam (fake/scripted) keeps its own script and is
;; unaffected.
(defonce test-script (atom []))

(defn- script-provider [script on-event pace-ms]
  (let [turn (first @script)
        {:keys [reasoning reasoning-after content tool-calls usage refuse]} turn]
    (swap! script #(vec (rest %)))
    (when refuse
      (let [[status body] (if (map? refuse)
                            [(:status refuse 400) (:body refuse)]
                            [400 refuse])]
        (throw (ex-info (str "HTTP " status ": " body) {:status status}))))
    (emit! ev/reasoning-delta reasoning on-event pace-ms)
    (emit! ev/text-delta content on-event pace-ms)
    ;; THE VENDOR SHAPE THAT PUT A SECOND 思考 ROW ON THE PAGE (2026-09-22). A thinking-mode
    ;; model can go BACK to its reasoning after the answer has started -- measured on a
    ;; real session, whose stream was `reasoning "…Answer briefly"`, the answer's first
    ;; token, then `reasoning " in Chinese."`, then the answer again. The edge keeps ONE
    ;; reasoning message open across that (see harness.edge.ag-ui), and a turn is the only
    ;; way to play it here. `:reasoning-after` is that late piece; a turn without it
    ;; behaves exactly as it did.
    (emit! ev/reasoning-delta reasoning-after on-event pace-ms)
    (let [calls (mapv (fn [{:keys [id name arguments]}]
                        (let [args (json/write-str arguments)]
                          (on-event (ev/tool-call id name args))
                          {:id id :type "function"
                           :function {:name name :arguments args}}))
                      tool-calls)]
      {:message
       (cond-> {:role "assistant" :content (or content "")}
         ;; MENTIONED IS NOT THE SAME AS NON-EMPTY, and a thinking-mode vendor needs
         ;; the difference kept: it sends the field on every round -- empty when the
         ;; round had no reasoning -- and requires it back on the next request. A turn
         ;; that says `:reasoning ""` is that vendor; one that omits it is a vendor
         ;; with nothing to say about reasoning at all. See
         ;; harness.kernel.llm/consume-sse, which keeps the same distinction on the
         ;; real wire.
         (or (contains? turn :reasoning) (contains? turn :reasoning-after))
         (assoc :reasoning_content (str (or reasoning "") (or reasoning-after "")))
         (seq calls)                 (assoc :tool_calls calls))
       ;; REPORTED vs SILENT, kept apart the same way `:reasoning` is: a turn that
       ;; writes a :usage is a vendor that reports one, and a turn that omits the key
       ;; is a vendor that says nothing about the call -- which is not the same as
       ;; reporting zeroes (see the contract in harness.kernel.llm).
       :telemetry (if (contains? turn :usage) {:usage usage} {})})))

(defn scripted
  "Provider over a vector of turns. A turn is
     {:reasoning s, :content s, :reasoning-after s,
      :tool-calls [{:id s :name s :arguments map}], :usage map,
      :refuse {:status n :body s}}
  `:refuse` IS A VENDOR SAYING NO BEFORE THE STREAM OPENS -- the turn's whole content is the
  HTTP status and body `harness.kernel.llm` would have thrown, so a test can meet an overflow
  refusal (or any other) without a network. A bare string is a 400; a map names the status.
  The turn is CONSUMED like any other, so the script that follows it is what the next call sees.
  `:reasoning-after` IS REASONING THAT ARRIVES AFTER THE ANSWER STARTED -- the shape a
  real thinking-mode vendor streamed (see the emitter below). It is emitted after
  `:content` and joins the SAME reasoning message on the wire (`harness.edge.ag-ui`).
  The assistant message it returns is deliberately OpenAI-shaped, because that is
  what the history holds.

  :usage IS THE VENDOR'S OWN SHAPE -- `prompt_tokens`, `completion_tokens`,
  `total_tokens`, `prompt_tokens_details.cached_tokens` -- and it travels as this
  call's telemetry, which the kernel puts on the `model/end` audit line verbatim.
  The fake reports it exactly as a real vendor does, because a test that folds
  tokens has to fold something shaped like what production sends. A turn that
  omits the key reports NOTHING about the call, which is not the same as
  reporting zeroes.

  OPTS:
    :thinking  be a THINKING-MODE VENDOR on the way in as well as on the way out --
               refuse any request whose assistant messages do not carry
               `reasoning_content`, with the real vendor's own 400. See
               `refuse-unless-echoed!`.
    :pace-ms   how long each CHUNK of the stream takes, in milliseconds, and zero unless
               a caller asks. THE KNOB A WALKTHROUGH NEEDS to watch an answer GROW --
               see the note beside it in the map below."
  ([turns] (scripted turns {}))
  ([turns {:keys [thinking pace-ms]}]
   {:protocol :fake :script (atom (vec turns)) :thinking (boolean thinking)
    ;; HOW LONG EACH CHUNK OF THE STREAM TAKES, in milliseconds, and zero -- no pause at
    ;; all -- unless a caller asks (a walkthrough that has to SEE an answer grow does;
    ;; the offline suite must not, or every case that streams would pay for it). A REAL
    ;; VENDOR TAKES TIME, and this is the knob that makes the double able to reproduce
    ;; the one shape a test cannot otherwise reach: a turn that is still being written
    ;; while somebody looks at it. See `harness.e2e-server/script-in`'s "pace-ms".
    :pace-ms (long (or pace-ms 0))
    ;; THE PIN NAMES ITSELF, like every other scripted provider in the repo does
    ;; (the seeded config, http_test's pins, providers_test's fixtures all carry
    ;; both). Without them a `model/start` audit line would name nobody, and the
    ;; offline suite would be folding a record shape production never writes.
    :base-url "http://offline.invalid/v1" :model "scripted"
    ;; AND IT DECLARES A WINDOW, for the same reason and one more: the composer's
    ;; context ring divides a call's `usage.prompt_tokens` by the window on that
    ;; call's own `model/start` line, and a pin is the one provider that records no
    ;; `provider/init` -- so without a number here, every suite and every
    ;; `--scripted` walkthrough would have a numerator and nothing to divide it by.
    ;; THE NUMBER IS THE DOUBLE'S, not a vendor's: this provider is a script.
    :context-window 128000}))

(def ^:private thinking-mode-refusal
  "The real vendor's 400, byte for byte -- what a DeepSeek-compatible gateway answers
  when a thinking-mode request carries an assistant message with no
  `reasoning_content` (verified against one on 2026-09-16; both requests and both
  responses are in `.scratch/reasoning-round-trip/evidence/`).

  COPIED RATHER THAN PARAPHRASED, because the whole point of the strict mode is that a
  test meets what production meets. `harness.kernel.llm` reports a failed vendor by
  throwing `HTTP <status>: <body>`, so this is thrown the same way."
  (json/write-str {:error {:message "The `reasoning_content` in the thinking mode must be passed back to the API."
                           :type "invalid_request_error"
                           :param ""
                           :code "invalid_request_error"}}))

(defn- refuse-unless-echoed!
  "Every assistant message in MESSAGES must carry `reasoning_content`, or this vendor
  answers 400.

  EVERY ONE, not only the tool-calling ones: that is what the vendor's sentence says
  ('in the thinking mode'), and it is the mirror of `llm/thinking-mode-history`, which
  pads every one. A double stricter than the vendor would fail us for something
  production accepts; a looser one would let the bug this exists for through."
  [messages]
  (when (some #(and (= "assistant" (:role %))
                    (not (contains? % :reasoning_content)))
              messages)
    (throw (ex-info (str "HTTP 400: " thinking-mode-refusal) {:status 400}))))


(defn- reasoning-role-refusal
  "The real vendor's 422, copied -- what the kongming gateway (a DeepSeek-compatible
  endpoint) answered when a request's message array carried a `role \"reasoning\"` element
  (measured 2026-09-25 on thread `f59c09dd-…`: fifteen compactions, fifteen refusals, every
  one this sentence). The vendor's sentence names the offending INDEX, so that is filled in;
  the request-id and column tail it ends with are per-request and dropped.
  "
  [i]
  (json/write-str {:error {:message (str "Failed to deserialize the JSON body into the target type: "
                                   "messages[" i "].role: unknown variant `reasoning`, expected "
                                   "one of `system`, `user`, `assistant`, `tool`, `latest_reminder`")
                           :type "invalid_request_error"
                           :param ""
                           :code "invalid_request_error"}}))

(defn- refuse-reasoning-role!
  "A MESSAGE WHOSE ROLE IS `reasoning` IS NOT A REQUEST ANY PROVIDER READS. AG-UI keeps a
  thought as a message of its own; a provider reads it as `reasoning_content` on the
  assistant message it belongs to (`harness.edge.ag-ui/absorbed` is that fold), and every
  OpenAI-compatible endpoint refuses the other spelling BY NAME.

  EVERY REQUEST, not only a compaction's: the bug this catches was a request built BESIDE
  the one path that folds (`.scratch/compaction-shape` ticket 01), so the next path that
  assembles an array by hand meets this too."
  [messages]
  (when-let [i (first (keep-indexed (fn [i m] (when (= "reasoning" (:role m)) i)) messages))]
    (throw (ex-info (str "HTTP 422: " (reasoning-role-refusal i)) {:status 422}))))
(defmethod llm/stream! :fake
  [{:keys [script thinking] :as provider} messages on-event _thread-id]
  ;; TWO FACTS, not one: `:thinking` says this is the KIND of vendor that enforces
  ;; the rule, and :reasoning-effort says THIS request is in thinking mode -- which
  ;; is what the vendor's sentence is conditioned on ("in the thinking mode"). A
  ;; request without it is a different mode and gets no refusal, so the boundary
  ;; between the two is testable.
  ;; A ROLE NO PROVIDER READS IS REFUSED WHATEVER THE MODE, because that is what the vendor
  ;; did -- the 422 arrived on a call whose provider was in thinking mode, but the sentence it
  ;; answered with is about the ROLE, not about the mode.
  (refuse-reasoning-role! messages)
  (when (and thinking (:reasoning-effort provider)) (refuse-unless-echoed! messages))
  (script-provider (or script test-script) on-event (:pace-ms provider)))
