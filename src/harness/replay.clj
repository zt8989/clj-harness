(ns harness.replay
  "Rebuild a conversation from a thread's JSONL log, so it can be continued after the
  process that recorded it is gone.

  This is the read side of the log contract, promoted from dev/ (2026-09-13,
  ticket 05) because rebuilding is now a product capability -- the
  /api/threads/<id>/rebuild endpoint serves a client its own history back. The
  iron law is UNTOUCHED and must stay so: the kernel never reads its log during
  a run. Rebuilding is an explicit management action, outside any run -- which
  is also why every rebuild action leaves its own audit line at the edge.

  The reconstruction rule is one line: seed from the FIRST input's messages, then fold
  every recorded frame onto it in order. Later inputs are ignored rather than merged --
  a client's second input already restates everything before it, so folding it in would
  duplicate the conversation. The frames are the source of truth; the inputs are only a
  starting point.

  The DIRECTORY is the caller's -- this namespace stays a pure reader and never
  learns where the process keeps its home. The FILENAME rule, though, is shared
  with the writer through harness.home/sanitize: that one expression is the part
  the two sides must agree on, and sharing it is what stops them drifting."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.ag-ui :as ag]
            [harness.frames :as frames]
            [harness.home :as home]
            [harness.memory :as mem]
            [harness.loop :as loop]))

(defn- log-file
  "The file the writer in harness.http would have produced for this thread."
  [dir thread-id]
  (home/log-file dir thread-id))

(defn read-lines
  "A thread's raw log lines. A missing log is a NAMED failure, not an empty
  conversation: continuing from nothing would silently drop a whole history."
  [dir thread-id]
  (let [f (log-file dir thread-id)]
    (when-not (.exists f)
      (throw (ex-info (str "no log for thread " (pr-str thread-id) " at " f)
                      {:thread-id thread-id :path (str f)})))
    (str/split-lines (slurp f :encoding "UTF-8"))))

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
             (not (and (seq frames) (frames/terminal? (peek frames)))))
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
    (into (vec seed) (frames/apply-frames frames))))

(defn lines->messages
  "A thread's raw log lines -> the AG-UI message list they describe."
  [lines]
  (records->messages (lines->records lines)))

(defn- first-input
  "The first input line's payload -- the seed messages and the run context both
  come from there."
  [records]
  (some->> records
           (filter #(= "input" (:kind %)))
           first
           :payload))

(defn rebuild
  "What a client needs to RE-OWN its conversation: the AG-UI message list
  (seed + every recorded frame folded in, reasoning and tool calls included)
  plus the context the conversation was started with. The client takes both
  into its next ordinary RunAgentInput -- the server holds no rebuilt state,
  exactly as it holds no conversation state ever."
  [dir thread-id]
  (let [records (lines->records (read-lines dir thread-id))
        input   (first-input records)]
    {:messages (records->messages records)
     :context  (:context input)}))

(defn history
  "A thread's log -> the provider-shaped messages you can hand straight to
  loop/run-chan.

  This is the whole point of the namespace: after the process that wrote the log is
  gone, this rebuilds the conversation that was in flight, reasoning and tool results
  included, and it comes back in exactly the shape the model expects -- the reasoning
  folded onto its assistant message, calls in the provider's casing."
  [dir thread-id]
  (let [records (lines->records (read-lines dir thread-id))]
    (ag/inbound (records->messages records) (mem/prompt) (:context (first-input records)))))

(defn threads
  "The conversations a log DIRECTORY holds: one entry per *.jsonl file --
  {:thread-id .. :last-activity <epoch millis> :bytes <file size>} -- newest
  first. The thread-id is the FILE's stem: the writer sanitizes thread ids into
  filenames, and this listing speaks filenames. An empty or MISSING directory is
  an empty list, not an error -- no logs yet is the normal state of a fresh
  install.

  The listing says nothing about whether a log is complete; rebuilding a
  truncated one is refused, and the refusal names why."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(and (.isFile %) (str/ends-with? (.getName %) ".jsonl")))
       (mapv (fn [^java.io.File f]
               (let [name (.getName f)]
                 {:thread-id     (subs name 0 (- (count name) (count ".jsonl")))
                  :last-activity (.lastModified f)
                  :bytes         (.length f)})))
       (sort-by :last-activity >)
       vec))

(defn resume!
  "Rebuild a thread from its log, append TEXT as a new user turn, and run the agent on.
  Returns the AG-UI frames the continuation produced.

  The rebuilt history is exactly what the server would have recomputed on the next run,
  so this continues the conversation rather than starting a fresh one -- which is the
  whole reason the log is worth keeping.

  It does NOT append to the log. The writer lives at the http edge, and this namespace
  is deliberately the read side only; a resumed conversation therefore leaves no new
  trace on disk. An author-side action, not a run path."
  ([dir thread-id text] (resume! dir thread-id text (mem/effective-provider thread-id)))
  ([dir thread-id text provider]
     (let [run-id (str (java.util.UUID/randomUUID))
         emit   (ag/outbound thread-id run-id)
         frames (atom [])
         events (loop/run-chan provider
                               (conj (history dir thread-id) {:role "user" :content text})
                               {:thread-id thread-id})]
     (loop []
       (when-let [event (async/<!! events)]
         (when-not (= :run/done (:type event))
           (doseq [frame (emit event)] (swap! frames conj frame))
           (recur))))
     @frames)))
