(ns harness.llm
  "Provider layer. One multimethod, dispatched on :protocol.

  Contract for every method:
    (stream! provider messages on-event thread-id) -> assistant message

  THREAD-ID is the session the run serves; it selects the thread's effective
  toolset (harness.memory base + session overlay) for the request's tools array
  and is otherwise opaque to the methods.

  ON-EVENT is called with each harness.event value as it is produced. The returned
  assistant message is provider-shaped and is appended to the history VERBATIM by
  harness.loop -- never rebuilt. That is what keeps reasoning_content alive across
  tool rounds, which DeepSeek requires whenever the request carries tools
  (omitting it there is a hard HTTP 400).

  The system prompt's carrier lives here too: prompt.md is read once and frozen
  (see `prompt` / `reset-prompt!` below), because the provider's prefix cache is
  what makes the freezing matter.

  A provider is just a config map, so (harness.memory/current-provider) or
  (harness.memory/effective-provider) can be handed straight to loop/run-chan:
    {:protocol :openai-completions, :base-url .., :model .., :api-key ..
     :reasoning-effort ..}
  :reasoning-effort is present only when some tier chose one. :input/:output and
  the two counts (:context-window / :max-output-tokens) are not read here at all:
  they describe what a model is, which is the edge's business
  (harness.http/guard-input-modalities!) and the catalog's, not the wire's. In
  particular :max-output-tokens is NOT sent as max_tokens -- the vendor's own
  default decides how much a response may hold, and this harness does not
  second-guess it."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.event :as ev]
            [harness.tools :as tools])
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

(defn- absorb!
  "Fold one chunk's delta into TEXT, THINK and CALLS.
  Tool-call fragments arrive spread across chunks: the first carries id and name,
  later ones only index plus an arguments fragment. Key by index and concatenate.
  OpenRouter proxies reasoning as `reasoning` while DeepSeek uses
  `reasoning_content`; both are accepted."
  [text think calls delta]
  (when-let [c (:content delta)] (.append text c))
  (when-let [r (or (:reasoning_content delta) (:reasoning delta))] (.append think r))
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
  (when-let [r (or (:reasoning_content delta) (:reasoning delta))]
    (when (seq r) (emit (ev/reasoning-delta r)))))

(defn- fold-tool-calls [calls]
  (mapv (fn [[_ t]] {:id (:id t) :type "function"
                     :function {:name (:name t) :arguments (:arguments t)}})
        (sort-by key calls)))

(defn consume-sse
  "Fold a seq of SSE lines into an assistant message, calling EMIT for each event.
  Pure over LINES -- the network layer only supplies them, which is what makes the
  parser testable against a recorded body with no network at all."
  [lines emit]
  (let [text  (StringBuilder.)
        think (StringBuilder.)
        calls (atom {})]
    (doseq [payload (data-payloads lines)]
      (let [delta (get-in (json/read-str payload :key-fn keyword) [:choices 0 :delta])]
        (absorb! text think calls delta)
        (speak! delta emit)))
    (let [assembled (fold-tool-calls @calls)]
      ;; Emitted only once fully assembled: no incremental args, and therefore no
      ;; state machine that can be cut off in the middle of a JSON string.
      (doseq [{:keys [id function]} assembled]
        (emit (ev/tool-call id (:name function) (:arguments function))))
      (cond-> {:role "assistant" :content (str text)}
        (pos? (.length think)) (assoc :reasoning_content (str think))
        (seq assembled)        (assoc :tool_calls assembled)))))

(defmethod stream! :openai-completions
  [{:keys [model reasoning-effort] :as provider} messages on-event thread-id]
  (let [body (json/write-str (cond-> {:model model
                                      :messages messages
                                      :tools (tools/specs thread-id)
                                      :stream true}
                               reasoning-effort (assoc :reasoning_effort reasoning-effort)))
        resp (.send http-client (request provider body) (HttpResponse$BodyHandlers/ofInputStream))]
    (when-not (= 200 (.statusCode resp))
      (throw (ex-info (str "HTTP " (.statusCode resp) ": "
                           (slurp (.body resp) :encoding "UTF-8"))
                      {:status (.statusCode resp)})))
    ;; line-seq is lazy: it MUST be forced inside with-open, or the body leaks and
    ;; the caller deadlocks waiting on a stream nobody is draining.
    (with-open [r (io/reader (.body resp) :encoding "UTF-8")]
      (consume-sse (line-seq r) on-event))))
