(ns harness.http
  "The AG-UI edge. One POST endpoint, SSE out, CORS so a browser app on :5173 can call
  it directly (there is no proxy in front of us).

  Also append-only JSONL logging: one file per thread, these line kinds.

    \"input\"   -- the client's RunAgentInput as received.
    \"event\"   -- every AG-UI frame we emitted.
    \"message\" -- one line per provider-shaped message the LLM saw or produced,
                   VERBATIM: the frozen system prompt, each inbound message
                   (per-run context rides as a trailing user message), and every
                   assistant reply / tool result the kernel appended.
    \"tools/*\" -- the tool execution lifecycle (pre-execute / execute /
                   post-execute), keyed by toolCallId. No wire frame at all.
    \"approval/decided\" -- a human's answer to a parked call, with the interrupt
                   id and whatever payload the client attached.

  All of it is a RECORD, never a source of truth -- the client owns the conversation,
  and the server never reads the file back."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [harness.ag-ui :as ag]
            [harness.event :as ev]
            [harness.home :as home]
            [harness.memory :as mem]
            [harness.opaque :as opaque]
            [harness.loop :as loop]
            [org.httpkit.server :as hk])
  (:import [java.nio.charset StandardCharsets]))

(def port 8080)
(def ui-origin "http://localhost:5173")

(def ^:private cors
  {"Access-Control-Allow-Origin"  ui-origin
   "Access-Control-Allow-Methods" "POST, OPTIONS"
   "Access-Control-Allow-Headers" "Content-Type"})

;; ------------------------------------------------------------------- logging

(defn- log! [thread-id run-id kind payload]
  (let [f (home/log-file thread-id)]
    (.mkdirs (.getParentFile f))
    (spit f (str (json/write-str {:ts (System/currentTimeMillis)
                                  :runId run-id :kind kind :payload payload}) "\n")
          :append true :encoding "UTF-8")))

(defn- log-messages!
  "One \"message\" line per provider-shaped message, VERBATIM. The submitted and
  the returned side of the message record both come through here."
  [thread-id run-id msgs]
  (doseq [m msgs]
    (log! thread-id run-id "message" m)))

;; ------------------------------------------------------------------- the edge

(def ^:private terminal #{"RUN_FINISHED" "RUN_ERROR"})

(defn- lifecycle-record
  "A tool-lifecycle kernel event -> the [kind payload] jsonl line it becomes,
  keyed by toolCallId like applepi's ADR-0021 audit lines. Nil for every other
  event kind. The audit line is additive: the event itself carries no wire
  frame, so the AG-UI conversion upstream of this is untouched."
  [ev]
  (case (:type ev)
    :tool/pre-execute
    ["tools/pre-execute" (merge {:toolCallId (:id ev) :toolName (:name ev)}
                                (when-not (= :pass (:outcome ev))
                                  {:outcome (name (:outcome ev))})
                                (when (seq (:missing ev))
                                  {:missing (mapv name (:missing ev))}))]

    :tool/execute
    ["tools/execute" (merge {:toolCallId (:id ev) :toolName (:name ev)}
                            (when (:error ev) {:error (:error ev)}))]

    :tool/post-execute
    ["tools/post-execute" {:toolCallId (:id ev) :toolName (:name ev)}]

    nil))

(defn- runner
  "Build the frame emitter for one run. Two http-kit rules have to hold at once:

    - the status and headers ride on the FIRST send!, not on the ring response, so a
      separate header-only send is not an option;
    - the LAST frame carries close-after-send?. Closing down a separate code path
      loses the response: http-kit buffers small writes, and a lone close discards
      whatever was never flushed. Watched a complete, correctly logged run deliver
      zero frames that way.

  Bodies are UTF-8 BYTES: this machine's JVM default charset is GBK, so handing
  http-kit a String would be a coin flip on any non-ASCII."
  [thread-id run-id ch]
  (let [first? (atom true)]
    (fn [frame]
      (log! thread-id run-id "event" frame)
      (let [body (.getBytes (str "data: " (json/write-str frame) "\n\n")
                            StandardCharsets/UTF_8)
            head (when @first?
                   {:status  200
                    :headers (merge cors {"Content-Type" "text/event-stream"
                                          "Cache-Control" "no-cache"})
                    :body    body})]
        (reset! first? false)
        (hk/send! ch (or head body) (contains? terminal (:type frame)))))))

(defn- resume-decisions
  "A client's resume entries -> the decisions the kernel replays, in order:
  {:interrupt-id .. :verdict :approved|:vetoed :payload ..}, each also naming the
  call it answers so the audit line can be keyed by toolCallId.

  The protocol allows only \"resolved\" and \"cancelled\"; anything else, and any
  interrupt this process never parked, is a hard error. Guessing an approval is
  the worst possible failure mode here, so it is refused rather than defaulted."
  [resume]
  (mapv (fn [{:keys [interruptId status payload]}]
          (let [verdict (case status
                          "resolved"  :approved
                          "cancelled" :vetoed
                          (throw (ex-info (str "unknown resume status: " status) {})))
                id      (str interruptId)
                rec     (mem/parked id)]
            (when-not rec
              (throw (ex-info (str "unknown interrupt: " id) {})))
            {:interrupt-id id :verdict verdict :payload payload
             :tool-call-id (:tool-call-id rec)}))
        resume))

(defn- run-agent! [ch input]
  (let [thread-id (str (:threadId input))
        run-id    (str (:runId input))
        ;; ONE emitter and ONE converter per run. The converter owns the open-message
        ;; state machine, so building it per event restarts every message id and
        ;; re-emits START frames -- which an AG-UI client treats as fatal.
        emit    (runner thread-id run-id ch)
        convert (ag/outbound thread-id run-id)]
    (log! thread-id run-id "input" input)
    (async/go
      ;; A malformed input, an unreadable prompt, a bad config -- or a resume
      ;; naming an interrupt this process never parked -- blows up before the run
      ;; starts. Catch it here and push a well-formed RUN_STARTED..RUN_ERROR pair
      ;; so the client sees a terminated run rather than a broken stream.
      (let [[provider messages decisions]
            (try [(opaque/current-provider)
                  (ag/inbound (:messages input) (mem/prompt) (:context input))
                  (resume-decisions (:resume input))]
                 (catch Throwable t
                   (doseq [frame (into (vec (convert (ev/run-start)))
                                       (convert (ev/run-error (ex-message t))))]
                     (emit frame))
                   nil))]
        (when provider
          ;; The decision record: what the human answered, next to the input that
          ;; carried it. The same verdict also lands on the resumed call's
          ;; tools/pre-execute line, keyed by toolCallId -- this row is the one
          ;; that carries the interrupt id and the client's payload.
          (doseq [d decisions]
            (log! thread-id run-id "approval/decided" d))
          ;; The introspection record, provider half: what this run actually
          ;; serves with, api-key stripped by the memory surface itself.
          (mem/record-provider! thread-id provider)
          ;; The message record, submitted side: what the first LLM call is about
          ;; to see. The FROZEN system prompt plus every inbound message in the
          ;; provider's shape, one line each, VERBATIM. Context rides as a
          ;; trailing user message -- it must never touch the system prompt, or
          ;; the provider's prefill (prompt cache) would miss every call.
          (log-messages! thread-id run-id messages)
          ;; Drain run-chan and convert each kernel event to AG-UI frames. The
          ;; stream closes via :run/end's RUN_FINISHED (or RUN_ERROR), or via
          ;; :run/interrupt's RUN_FINISHED carrying outcome.interrupts; the
          ;; :run/done history itself is never converted -- it is the returned
          ;; side of the message record instead.
          (let [events (loop/run-chan provider messages {:thread-id thread-id
                                                         :resume decisions})]
            (loop []
              (when-let [ev (async/<! events)]
                (if (= :run/done (:type ev))
                  ;; Returned side: every message the kernel appended after the
                  ;; initial vector -- assistant replies VERBATIM (the history
                  ;; holds the provider message unrebuilt, reasoning and tool
                  ;; calls intact) and each tool result as the tool message
                  ;; submitted on the next call. :run/done follows RUN_ERROR
                  ;; too, so any run the kernel started leaves its full message
                  ;; tail on disk -- but it lands one beat AFTER the terminal
                  ;; frame, so a reader racing the consumer may not see it yet.
                  (do ;; Introspection record, history half: the final history
                      ;; is addressable by thread-id from here on -- same
                      ;; landing point as the jsonl tail below, same window.
                      (mem/record-history! thread-id (:history ev))
                      (log-messages! thread-id run-id
                                     (subvec (:history ev) (count messages))))
                  (do ;; Tool-lifecycle events are audit lines, not wire frames:
                      ;; each lands as its own jsonl line, keyed by toolCallId.
                      (when-let [[kind payload] (lifecycle-record ev)]
                        (log! thread-id run-id kind payload))
                      (doseq [frame (convert ev)] (emit frame))
                      (recur)))))))))))


(defn- handle-run [req]
  (let [input (json/read-str (slurp (:body req) :encoding "UTF-8") :key-fn keyword)]
    ;; as-channel wants no status or headers of its own. run-agent! returns immediately
    ;; -- the run is driven by a go loop draining the core.async channel -- so it does
    ;; not block the worker that :on-open runs on.
    (hk/as-channel req {:on-open (fn [ch] (run-agent! ch input))})))

(defn handler [req]
  (if (= :options (:request-method req))
    {:status 204 :headers cors}
    (handle-run req)))

;; ---------------------------------------------------------------------- start

(defn start!
  "Start the server and return its stop fn. Default port is 8080."
  [& [opts]]
  (let [opts   (merge {:port port} opts)
        server (hk/run-server handler opts)]
    (println (str "harness listening on http://localhost:" (:port opts))
             "-- POST an AG-UI RunAgentInput here; stop with (stop!)")
    server))

(defn -main [& _]
  (start!)
  @(promise))
