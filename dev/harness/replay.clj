(ns harness.replay
  "Rebuild a conversation from a thread's JSONL log, so it can be continued after the
  process that recorded it is gone.

  This lives under dev/ on purpose. The kernel never reads its log -- the log is a
  record, and the client owns the conversation. But 'the log is a record' is only a
  defensible position if the record can actually be read back, and that is what this
  namespace is: the read side, outside the kernel.

  The reconstruction rule is one line: seed from the FIRST input's messages, then fold
  every recorded frame onto it in order. Later inputs are ignored rather than merged --
  a client's second input already restates everything before it, so folding it in would
  duplicate the conversation. The frames are the source of truth; the inputs are only a
  starting point."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.ag-ui :as ag]
            [harness.llm :as llm]
            [harness.loop :as loop]
            [harness.wire :as wire]))

(defn- log-file
  "The file the writer in harness.http would have produced for this thread.

  The sanitisation is repeated from there rather than shared: sharing it would mean the
  kernel growing a read side, and the kernel must not read its own log. It is one
  expression, and the two sites have to agree -- if you change one, change both."
  [dir thread-id]
  (io/file dir (str (str/replace (str thread-id) #"[^A-Za-z0-9._-]" "_") ".jsonl")))

(defn read-lines
  "A thread's raw log lines. A missing log is a failure, not an empty conversation."
  [dir thread-id]
  (str/split-lines (slurp (log-file dir thread-id) :encoding "UTF-8")))

(defn lines->records
  "Parse a log's lines. A line that will not parse is a hard failure that names the
  line: a log killed mid-write must not be mistaken for a shorter conversation."
  [lines]
  (mapv (fn [idx line]
          (try
            (json/read-str line :key-fn keyword)
            (catch Exception e
              (throw (ex-info (str "line " (inc idx) " of the log is not valid JSON ("
                                   (ex-message e) ") -- the log is truncated or corrupt")
                              {:line (inc idx)})))))
        (range)
        lines))

(defn- ensure-complete! [records frames]
  (when (and (seq records)
             (not (and (seq frames) (wire/terminal? (peek frames)))))
    (throw (ex-info (str "the log ends mid-run: the last recorded frame is "
                         (if-let [t (:type (peek frames))] (str t) "absent")
                         ", not RUN_FINISHED or RUN_ERROR. Replaying a truncated log "
                         "would produce half a conversation, so it is refused.")
                    {:last-frame (peek frames)}))))

(defn records->messages
  "Parsed log records -> the AG-UI message list they describe."
  [records]
  (let [seed   (some->> records
                        (filter #(= "input" (:kind %)))
                        first
                        :payload
                        :messages)
        frames (->> records
                    (filter #(= "event" (:kind %)))
                    (mapv :payload))]
    (ensure-complete! records frames)
    (into (vec seed) (wire/apply-frames frames))))

(defn lines->messages
  "A thread's raw log lines -> the AG-UI message list they describe."
  [lines]
  (records->messages (lines->records lines)))

(defn history
  "A thread's log -> the provider-shaped messages you can hand straight to
  loop/run-chan.

  This is the whole point of the namespace: after the process that wrote the log is
  gone, this rebuilds the conversation that was in flight, reasoning and tool results
  included, and it comes back in exactly the shape the model expects -- the reasoning
  folded onto its assistant message, calls in the provider's casing."
  [dir thread-id]
  (let [records (lines->records (read-lines dir thread-id))
        context (some->> records
                         (filter #(= "input" (:kind %)))
                         first
                         :payload
                         :context)]
    (ag/inbound (records->messages records) (llm/prompt) context)))

(defn resume!
  "Rebuild a thread from its log, append TEXT as a new user turn, and run the agent on.
  Returns the AG-UI frames the continuation produced.

  The rebuilt history is exactly what the server would have recomputed on the next run,
  so this continues the conversation rather than starting a fresh one -- which is the
  whole reason the log is worth keeping.

  It does NOT append to the log. The writer lives at the http edge, and this namespace
  is deliberately the read side only; a resumed conversation therefore leaves no new
  trace on disk."
  ([dir thread-id text] (resume! dir thread-id text (llm/config)))
  ([dir thread-id text provider]
   (let [run-id (str (java.util.UUID/randomUUID))
         emit   (ag/outbound thread-id run-id)
         frames (atom [])
         events (loop/run-chan provider
                               (conj (history dir thread-id) {:role "user" :content text}))]
     (loop []
       (when-let [event (async/<!! events)]
         (when-not (= :run/done (:type event))
           (doseq [frame (emit event)] (swap! frames conj frame))
           (recur))))
     @frames)))
