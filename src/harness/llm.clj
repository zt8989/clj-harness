(ns harness.llm
  "Provider layer. One multimethod, dispatched on :protocol.

  Contract for every method:
    (stream! provider messages on-event) -> assistant message

  ON-EVENT is called with each harness.event value as it is produced. The returned
  assistant message is provider-shaped and is appended to the history VERBATIM by
  harness.loop -- never rebuilt. That is what keeps reasoning_content alive across
  tool rounds, which DeepSeek requires whenever the request carries tools
  (omitting it there is a hard HTTP 400).

  A provider is just a config map, so (config) can be handed straight to loop/run!:
    {:protocol :openai-completions, :base-url .., :model .., :api-key .., :reasoning-effort ..}"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dotenv :as dotenv]
            [harness.event :as ev]
            [harness.tools :as tools])
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(defmulti stream!
  (fn [provider _messages _on-event] (:protocol provider)))

;; --------------------------------------------------------------------- config

(defn config
  "config.edn, re-read every time so it can be edited while the process runs.
  The API key comes from .env through the dotenv library, following ITS precedence:
  a value in .env wins over a real environment variable. So .env is the single place
  that decides, and setting a shell variable will NOT override it."
  []
  (assoc (edn/read-string (slurp "config.edn"))
         :api-key (dotenv/env "HARNESS_API_KEY")))

(defn prompt
  "prompt.md, re-read before every run so the agent can rewrite its own instructions
  -- or its own kernel -- and see the change take effect on the very next turn."
  []
  (slurp "prompt.md" :encoding "UTF-8"))

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
  later ones only index plus an arguments fragment. Key by index and concatenate."
  [text think calls delta]
  (when-let [c (:content delta)] (.append text c))
  (when-let [r (:reasoning_content delta)] (.append think r))
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
  (when (seq (:reasoning_content delta)) (emit (ev/reasoning-delta (:reasoning_content delta)))))

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
  [{:keys [model reasoning-effort] :as provider} messages on-event]
  (let [body (json/write-str (cond-> {:model model
                                      :messages messages
                                      :tools (tools/specs)
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
