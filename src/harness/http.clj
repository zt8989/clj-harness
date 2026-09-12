(ns harness.http
  "The AG-UI edge. One POST endpoint, SSE out, CORS so a browser app on :5173 can call
  it directly (there is no proxy in front of us).

  Also append-only JSONL logging: one file per thread, holding both the inbound
  RunAgentInput and every frame we emitted. It is a RECORD, never a source of truth --
  the client owns the conversation, and nothing here is ever read back."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.ag-ui :as ag]
            [harness.event :as ev]
            [harness.llm :as llm]
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

(defn- log-file [thread-id]
  (io/file (str (System/getProperty "user.home") "/.lisp-harness/logs")
           (str (str/replace (str thread-id) #"[^A-Za-z0-9._-]" "_") ".jsonl")))

(defn- log! [thread-id run-id kind payload]
  (let [f (log-file thread-id)]
  (.mkdirs (.getParentFile f))
    (spit f (str (json/write-str {:ts (System/currentTimeMillis)
                                  :runId run-id :kind kind :payload payload}) "\n")
          :append true :encoding "UTF-8")))

;; ------------------------------------------------------------------- the edge

(defonce ^:private provider-override (atom nil))

(defn use-provider!
  "Serve from PROVIDER instead of config.edn; pass nil to go back to config.
  This is how the whole edge can be exercised offline, against a scripted provider,
  without an API key or a network."
  [provider]
  (reset! provider-override provider))

(defn- current-provider [] (or @provider-override (llm/config)))

(def ^:private terminal #{"RUN_FINISHED" "RUN_ERROR"})

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

(defn- run-agent! [ch input]
  (let [thread-id (str (:threadId input))
        run-id    (str (:runId input))
        ;; ONE emitter and ONE converter per run. The converter owns the open-message
        ;; state machine, so building it per event restarts every message id and
        ;; re-emits START frames -- which an AG-UI client treats as fatal.
        emit      (runner thread-id run-id ch)
        convert   (ag/outbound thread-id run-id)]
    (log! thread-id run-id "input" input)
    (try
      (loop/run! (current-provider)
                 (ag/inbound (:messages input) (llm/prompt) (:context input))
                 (fn [kernel-event]
                   (doseq [frame (convert kernel-event)] (emit frame))))
      (catch Throwable t
        ;; Reaching here means we failed before the loop could report for itself --
        ;; a bad config, an unreadable prompt, a malformed RunAgentInput. Push a
        ;; well-formed RUN_STARTED..RUN_ERROR pair so the client sees a terminated
        ;; run rather than a broken stream.
        (let [conv (ag/outbound thread-id run-id)]
          (doseq [frame (into (vec (conv (ev/run-start)))
                              (conv (ev/run-error (ex-message t))))]
            (emit frame)))))))

(defn- handle-run [req]
  (let [input (json/read-str (slurp (:body req) :encoding "UTF-8") :key-fn keyword)]
    ;; as-channel wants no status or headers of its own, and the work must not block
    ;; the worker that :on-open runs on.
    (hk/as-channel req {:on-open (fn [ch] (future (run-agent! ch input)))})))

(defn handler [req]
  (if (= :options (:request-method req))
    {:status 204 :headers cors}
    (handle-run req)))

;; ---------------------------------------------------------------------- start

(defn start!
  "Start the server and return its stop fn. Default port is 8080."
  [& [opts]]
  (let [server (hk/run-server handler (merge {:port port} opts))]
    (println (str "harness listening on http://localhost:" (:port (merge {:port port} opts)))
             "-- POST an AG-UI RunAgentInput here; stop with (stop!)")
    server))

(defn -main [& _]
  (start!)
  @(promise))
