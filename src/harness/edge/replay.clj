(ns harness.edge.replay
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
  learns where the process keeps its home. That is why every entry point but the
  listing takes one: the routes locate a stem through `locate` (which workspace a
  conversation lives in follows from its project) and hand this namespace the
  directory they found. The FILENAME rule is shared with the writer through
  harness.infra.home/sanitize: that one expression is the part the two sides must agree
  on, and sharing it is what stops them drifting.

  THE LISTING IS A TREE WALK. `threads` used to take one directory, because there
  was one; the logs are now a tree -- one workspace per project plus a reserved
  one -- so a listing has to walk it, and `locate` exists because a stem is no
  longer unique across it. Both are the reading side's answer to the tree. What
  this namespace still refuses to learn is where the tree starts: the caller
  passes the root in, exactly as it passes one directory in for a rebuild."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.ag-ui :as ag]
            [harness.kernel.frames :as frames]
            [harness.infra.home :as home]
            [harness.cap.project :as project]
            [harness.cap.providers :as providers]
            [harness.kernel.loop :as loop]
            [harness.cap.system-prompt :as system-prompt]))

(defn read-lines
  "A log FILE's raw lines (see harness.infra.home/log-file). A missing log is a NAMED
  failure, not an empty conversation: continuing from nothing would silently drop
  a whole history."
  [^java.io.File f]
  (when-not (.exists f)
    (throw (ex-info (str "no log at " (.getAbsolutePath f))
                    {:path (.getAbsolutePath f)})))
  (str/split-lines (slurp f :encoding "UTF-8")))

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

(defn- ensure-complete!
  "Refuse a log whose last RUN did not finish.

  AN INPUT IS WHAT OPENS A RUN, AND ONLY AN INPUT. A log can hold lines that are
  not a run at all -- a `project/bound` audit line is written the moment a
  session is bound, and `provider/changed` can land outside any run -- and such a
  log is COMPLETE as it stands: it records no conversation because none has
  happened yet. Reading it must yield an empty message list, which is the honest
  answer for a session that exists but has never run. Judging by 'the log has
  records' would refuse exactly the state a fresh session is in, and the refusal
  would name a truncated run that never existed.

  So the walk is: an `input` line opens a run, a terminal frame closes it, and
  the run must be closed at the end. That covers the harder case a
  'peek at the last frame' rule misses -- the first run finished, a second input
  was recorded, and the process died before that run's first frame -- which is a
  truncated log even though the last line in it is a RUN_FINISHED. Trailing
  `message` lines do NOT reopen a run: the returned tail is written after the
  terminal frame, so closing on the frame and letting the tail follow is what the
  writer actually does.

  Frames are the source of truth within a run: a log killed mid-run must not be
  mistaken for a shorter conversation."
  [records]
  (let [last-input (reduce (fn [seen {:keys [kind]}]
                             (if (= "input" kind) (inc seen) seen))
                           0 records)
        terminals  (reduce (fn [n {:keys [kind payload]}]
                             (if (and (= "event" kind) (frames/terminal? payload))
                               (inc n)
                               n))
                           0 records)]
    (when (> last-input terminals)
      (let [frames (->> records (filter #(= "event" (:kind %))) (mapv :payload))]
        (throw (ex-info (str "the log ends mid-run: the last recorded frame is "
                             (if-let [t (:type (peek frames))] (str t) "absent")
                             ", not RUN_FINISHED or RUN_ERROR. Replaying a truncated log "
                             "would produce half a conversation, so it is refused.")
                        {:last-frame (peek frames)}))))))

(defn open-run
  "The run a log ends on WITHOUT closing it, or nil -- {:run-id .. :last-frame ..
  :unanswered [toolCallId ..]}.

  THE SAME WALK `ensure-complete!` REFUSES ON, read the other way: an `input`
  opens a run, a terminal frame closes it, and this is what the open one left
  unsaid. The refusal and the repair must not drift, so they ask the same two
  questions of the same records -- and `closing-frames` is what the answer is for.

  ONLY THE OPEN RUN'S CALLS ARE REPORTED. A call with no result INSIDE a run that
  did close is the ordinary approval park -- its :run/interrupt ends the run and the
  answer arrives on the resume run -- so 'every call needs a result' would report
  the normal flow as damage and append a result to a call a human is still deciding."
  [records]
  (let [input-at  (last (keep-indexed (fn [i r] (when (= "input" (:kind r)) i)) records))
        closed-at (last (keep-indexed (fn [i r]
                                        (when (and (= "event" (:kind r))
                                                   (frames/terminal? (:payload r)))
                                          i))
                                      records))]
    (when (and (some? input-at) (or (nil? closed-at) (< closed-at input-at)))
      (let [frames   (->> records
                          (drop (inc (or closed-at input-at)))
                          (filter #(= "event" (:kind %)))
                          (mapv :payload))
            answered (into #{} (keep #(when (= "TOOL_CALL_RESULT" (:type %))
                                        (:toolCallId %)))
                           frames)
            calls    (distinct (keep #(when (= "TOOL_CALL_START" (:type %))
                                        (:toolCallId %))
                                      frames))]
        {:run-id     (:runId (nth records input-at))
         :last-frame (:type (peek frames))
         :unanswered (vec (remove answered calls))}))))

(defn closing-frames
  "What a log that ends mid-run is MISSING, as {:run-id .. :last-frame .. :frames
  [frame ..]} -- or nil when the log ends where a log should.

  PURE: it says what is missing, and the writer is the edge, which owns the file.

  A TOOL CALL THAT NEVER ANSWERED GETS A RESULT FIRST, and that is not tidiness:
  the rebuilt conversation is handed to the client as the NEXT run's history, and an
  assistant message whose tool_calls has no answering tool message is a shape the
  vendors refuse -- so closing the run without answering it would trade a refusal on
  the read side for a 400 on the next call. The sentence is the true one (the call
  was cut off and nothing was recorded) rather than an invented result.

  AND THE TERMINAL IS RUN_ERROR, NEVER RUN_FINISHED. A run that never reached a
  terminal frame did not finish; this log is an append-only record whose whole value
  is that its lines are true, and RUN_FINISHED would be the one line in it that lies.
  The message says what happened and who wrote the line, because the reader of a
  record is entitled to know a frame appeared without a run having emitted it."
  [records]
  (when-let [{:keys [run-id last-frame unanswered]} (open-run records)]
    (let [results (vec (map-indexed
                        (fn [i id]
                          {:type      "TOOL_CALL_RESULT"
                           :messageId (str run-id "-cut-" (inc i))
                           :toolCallId id
                           :content   (str "the run was cut off before this call"
                                           " returned; no result was recorded")})
                        unanswered))]
      {:run-id     run-id
       :last-frame last-frame
       :frames     (conj results
                         {:type    "RUN_ERROR"
                          :message (str "the run was cut off: this record ends "
                                        (if last-frame
                                          (str "at " last-frame)
                                          "before the run's first frame")
                                        " with no terminal frame, and it was closed"
                                        " when the session was continued")})})))

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
    (ensure-complete! records)
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
  [^java.io.File f]
  (let [records (lines->records (read-lines f))
        input   (first-input records)]
    {:messages (records->messages records)
     :context  (:context input)}))

(defn- thread-id-of
  "The session a log FILE belongs to: its stem. The writer names the file through
  harness.infra.home/sanitize, so reading the name back is the same 'what is on disk is
  what there is' stance `threads` takes -- asking the store instead would answer a
  different question, and would fail for a log that was moved by hand.

  This exists for the system message's sake. Replay has no hook sink, so assembly
  appends nothing here -- but the DERIVATION is what matters: whoever assembles a
  system message recomputes it from live facts for THIS thread rather than reading
  a copy out of the log, and that argument has to name the thread even when the
  answer is currently 'nothing to append'."
  [^java.io.File f]
  (let [n (.getName f)]
    (subs n 0 (- (count n) (count ".jsonl")))))

(defn history
  "A log FILE -> the provider-shaped messages you can hand straight to
  loop/run-chan.

  This is the whole point of the namespace: after the process that wrote the log is
  gone, this rebuilds the conversation that was in flight, reasoning and tool results
  included, and it comes back in exactly the shape the model expects -- the reasoning
  folded onto its assistant message, calls in the provider's casing.

  The system message is ASSEMBLED, not read out of the log: prompt.md's frozen
  opening plus whatever the SystemPrompt hooks append for the thread the file
  names. Nothing is appended here, because replay has no hook sink (see
  harness.cap.system-prompt/assemble) -- so what comes back is the frozen opening, byte
  for byte, which is exactly what the tests below pin."
  [^java.io.File f]
  (let [records (lines->records (read-lines f))]
    (ag/inbound (records->messages records)
                (system-prompt/assemble (thread-id-of f))
                (:context (first-input records)))))

(defn- logs-under
  "Every *.jsonl file at any depth under DIR, in no particular order. The tree is
  what 'the logs' means now: one workspace per project plus a reserved one, so a
  listing is a walk rather than a single directory scan -- and it stays a
  FILESYSTEM fact rather than a database query, because a log that was moved by
  hand is still a log."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".jsonl")))))

(defn threads
  "The conversations a log TREE holds: one entry per *.jsonl file --
  {:thread-id .. :last-activity <epoch millis> :bytes <file size>} -- newest
  first. DIR is the tree's root (harness.infra.home/projects-dir); the walk is
  recursive, so its workspaces are covered without this namespace knowing how
  they are named. The thread-id is the FILE's stem, and this listing speaks
  filenames.

  An empty or MISSING tree is an empty list, not an error -- no logs yet is the
  normal state of a fresh install. The listing says nothing about whether a log
  is complete; rebuilding a truncated one is refused, and the refusal names why.
  Nor does it say whether a stem is UNIQUE: one conversation whose binding moved
  between runs has a log in each workspace, and that is what `locate` refuses."
  [dir]
  (->> (logs-under dir)
       (mapv (fn [^java.io.File f]
               (let [name (.getName f)]
                 {:thread-id     (subs name 0 (- (count name) (count ".jsonl")))
                  :last-activity (.lastModified f)
                  :bytes         (.length f)})))
       (sort-by :last-activity >)
       vec))

(defn logs-for
  "EVERY log file under DIR whose name is the one a STEM gets: none, one, or
  several. A stem comes from `threads`, so it is already a file stem; a full path
  works too, since sanitizing it leaves only the final segment changed -- and
  sanitize is the one rule for turning an id into a filename, so it is used rather
  than restated.

  The whole vector is the answer, rather than one file, because the callers
  disagree about what to do with more than one and neither may guess: rebuilding
  refuses (no half is the conversation), and a log move refuses too (whichever
  file it carried, the other would be left behind as a second half). Returning the
  list is what lets each say so in its own words instead of one caller
  re-interpreting the other's refusal.

  None is an ordinary answer: a session that has never run has no log."
  [dir stem]
  (let [want (str (home/sanitize stem) ".jsonl")]
    (->> (logs-under dir)
         (filter #(= want (.getName ^java.io.File %)))
         (sort-by #(str %))
         vec)))

(defn find-log
  "THE log file a STEM names anywhere under DIR -- nil when there is none.
  Several is a NAMED failure: a conversation whose log landed in two workspaces is
  not one this function can hand back (see logs-for)."
  [dir stem]
  (let [found (logs-for dir stem)]
    (case (count found)
      0 nil
      1 (first found)
      (throw (ex-info (str "thread " (pr-str stem) " has " (count found)
                           " logs, in different workspaces: "
                           (str/join ", " (map #(.getAbsolutePath ^java.io.File %) found))
                           ". Neither one is the whole of it")
                      {:thread-id stem
                       :paths (mapv #(.getAbsolutePath ^java.io.File %) found)})))))

(defn locate
  "The log file a STEM names, found anywhere under DIR -- the same answer as
  find-log, with 'nothing found' made a failure too, for the callers that must be
  handed a file:

    one match   that file
    none        a NAMED failure saying so, with where it looked
    several     a NAMED failure naming every file, and what to do about it

  A rebuild must not guess and must not invent an empty conversation: rebuilding
  from nothing would silently drop a whole history, which is why 'nothing found'
  is an exception here and an answer in find-log."
  [dir stem]
  (if-some [found (find-log dir stem)]
    found
    (throw (ex-info (str "no log for thread " (pr-str stem) " under "
                         (.getAbsolutePath (io/file dir))
                         " -- nothing there is named "
                         (str (home/sanitize stem) ".jsonl"))
                    {:thread-id stem
                     :path (str (io/file dir (str (home/sanitize stem) ".jsonl")))}))))

(defn resume!
  "Rebuild a thread from its log, append TEXT as a new user turn, and run the agent on.
  Returns the AG-UI frames the continuation produced.

  The rebuilt history is exactly what the server would have recomputed on the next run,
  so this continues the conversation rather than starting a fresh one -- which is the
  whole reason the log is worth keeping.

  It does NOT append to the log. The writer lives at the http edge, and this namespace
  is deliberately the read side only; a resumed conversation therefore leaves no new
  trace on disk. An author-side action, not a run path."
  ([f thread-id text] (resume! f thread-id text (providers/effective-provider thread-id)))
  ([f thread-id text provider]
     (let [run-id (str (java.util.UUID/randomUUID))
         emit   (ag/outbound thread-id run-id)
         frames (atom [])
         events (loop/run-chan provider
                               (conj (history f) {:role "user" :content text})
                               {:thread-id thread-id
                                ;; the same pre-LLM step the edge passes, so a
                                ;; rebuilt conversation carries its skill bodies
                                :before-llm project/before-llm})]
     (loop []
       (when-let [event (async/<!! events)]
         (when-not (= :run/done (:type event))
           (doseq [frame (emit event)] (swap! frames conj frame))
           (recur))))
     @frames)))
