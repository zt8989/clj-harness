(ns harness.edge.projection
  "THE CONTENT PROJECTION: the record is the truth, and this keeps a queryable copy of it in the store.

  ADR 0008 (`docs/adr/0008-the-log-is-the-truth-and-sqlite-projects-it.md`) is the decision, and its
  two halves are what this namespace is shaped by:

    IT IS NOT ON THE WRITE PATH, BUT IT LISTENS TO IT (`.scratch/record-window/` ticket 02):
    the reader walks each session's NEW BYTES, projects the complete lines it finds, and advances a
    byte offset; WHAT STARTS THAT WALK is a line landing in the record, handed over by
    `harness.infra.stream/listen-every!`. The listener only MARKS the conversation dirty (two
    `atom` operations) and a coalesced round -- at most one every `coalesce-ms` -- reads what was
    marked. A run that is mid-flight is fine: the projection is simply behind it, and how far
    behind is the number `lag` answers. A process that dies mid-pass loses nothing but the pass
    (the offset is written with the rows it accounts for, in one transaction).

    WHY A LISTENER RATHER THAN THE CLOCK THIS USED TO HAVE: a 2-second `scheduleAtFixedRate` ran a
    whole-store pass on a fixed beat whether or not a byte had been written, and a round walked
    the session list and `stat`ed every conversation -- measured 100-127 ms a round, ~5% of one
    core, for ever, on an IDLE process. Nothing is written when nobody is running, so the write
    stream is the only honest trigger, and an idle process now runs no round at all.

    EVERY ROW HERE IS RECOMPUTABLE FROM THE RECORD. `rebuild!` is that claim as an action: delete a
    session's rows, put its offset back to 0, project again, and the result is row for row what was
    there. That is the only reason a second copy of a conversation's content is allowed to exist.

  WHAT IT IS FOR (the owner's call, 2026-09-25): queries and state recovery that today either read a
  whole record -- one 68 MB session is ~1.8 s per `read-records` -- or do not exist at all: a range by
  `seq`, a search by keyword, a count by tool name. The store answers those; the record remains what
  answers 'what actually happened'.

  A LINE THAT WILL NOT PARSE IS SKIPPED AND SAID OUT LOUD, and it is worth knowing why this differs
  from the folders (`harness.edge.replay` refuses a torn line in the middle, because a fold that
  swallowed one would hand back a shorter conversation as if it were the whole one). A projection is
  an INDEX, not an account: refusing to advance would leave `lag` growing for ever over a log nobody
  is going to repair, and the thing a reader is owed is 'this copy is missing a line', which is what
  the warning and `lag` say. The record is untouched either way.

  THE LAG IS A NUMBER, NOT SILENCE (decision 5): `lag` answers (file size - byte offset) per session
  and in total, so a reader can say how far behind the copy is instead of assuming it is current."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.replay :as replay]
            [harness.edge.host :as host]
            [harness.edge.stats :as stats]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.infra.log :as log]
            [harness.infra.stream :as stream])
  (:import (java.io File RandomAccessFile)
           (java.util.concurrent Executors ScheduledExecutorService ThreadFactory TimeUnit)))

;; ------------------------------------------------------------------ reading the new bytes

(defn- complete-bytes
  "TEXT -> how many BYTES of it are complete lines.

  A TRAILING PARTIAL LINE IS NOT A LINE: the writer appends whole lines, but a reader can still catch
  the newest one mid-flush, and projecting half a row would either fail or -- worse -- succeed with a
  wrong shape. So the tail after the last newline is left for the next pass, which is also what makes
  the byte offset mean 'everything before this is projected' rather than 'roughly'."
  ^long [^String text]
  (let [nl (.lastIndexOf text "\n")]
    (if (neg? nl)
      0
      (alength (.getBytes (subs text 0 (inc nl)) "UTF-8")))))

(defn- parsed
  "One LINE -> [row skipped?]: the record's own row shape (`harness.edge.replay`'s reader), or nil and
  true for a line this build cannot read.

  THE READER IS REUSED, not re-implemented: what a row IS (the two kinds, the envelope, the CUSTOM
  names) is one vocabulary, and a second spelling here would be a second rule -- the drift this repo
  refuses everywhere else."
  [^String line]
  (try [(first (replay/lines->records [line])) false]
       (catch Throwable _ [nil true])))

(defn- new-rows
  "FILE, BYTE-OFFSET and LINE-OFFSET -> [ROWS NEXT-BYTE-OFFSET SKIPPED], where ROWS are `[line-index
  row]` for every readable complete line after the offset.

  READING BY BYTES, NOT BY LINES, is the whole point of keeping an offset instead of a count: a 68 MB
  log is not re-read to find out what is new, and a pass over an idle session costs one `length`. The
  offset only ever advances past a newline, so the decode starts on a character boundary too."
  [^File f ^long byte-offset ^long line-offset]
  (with-open [raf (RandomAccessFile. f "r")]
    (let [len (.length raf)]
      (if (<= len byte-offset)
        [[] byte-offset 0]
        (let [buf (byte-array (- len byte-offset))]
          (.seek raf byte-offset)
          (.readFully raf buf)
          (let [text     (String. buf "UTF-8")
                complete (complete-bytes text)]
            (if (zero? complete)
              [[] byte-offset 0]
              (let [found (mapv (fn [i line] (conj (parsed line) (+ line-offset i)))
                                (range)
                                (str/split-lines (subs text 0 (dec complete))))]
                [(vec (keep (fn [[row skipped? i]] (when-not skipped? [i row])) found))
                 (+ byte-offset complete)
                 (count (filter second found))]))))))))

;; ------------------------------------------------------------------ one row at a time

(defn- text-of
  "The message's content, as a TEXT column can hold it: a STRING STAYS A STRING, and anything else (a
  multimodal parts vector) is stored as its own JSON.

  THE ASYMMETRY IS DELIBERATE and it is the difference between a searchable copy and a faithful one: a
  plain string is what 'search by keyword' is for, and a quoted one would make every query pay for the
  envelope. A parts vector has no text form to lose, so JSON is exactly it."
  [content]
  (cond
    (nil? content)    nil
    (string? content) content
    :else             (json/write-str content)))

(defn- message-row
  "One record row -> the `messages` insert's parameters, or nil for a row that is not a message.

  `seq` IS THE RECORD'S OWN OFFSET -- the line index this row arrived at -- which is what makes the
  projection recomputable and `INSERT OR REPLACE` idempotent: projecting the same line twice writes
  the same row, and the second write is the same fact."
  [[i row]]
  (when (= "message" (replay/kind row))
    (let [m (replay/payload row)]
      {:seq       (long i)
       :run-id    (:runId row)
       :source    (:source row)
       :role      (str (or (:role m) "unknown"))
       :content   (text-of (:content m))
       :reasoning (:reasoning_content m)
       :calls     (vec (:tool_calls m))
       :at        (:ts row)})))

(defn- project-message!
  "One MESSAGE row into the conversation tables, and the tool calls it declared.

  Idempotent by construction: every statement is keyed."
  [^java.sql.Connection c session-id entry]
  (when-some [{:keys [seq run-id source role content reasoning calls at]} (message-row entry)]
    (db/execute! c "INSERT OR REPLACE INTO messages
                      (session_id, seq, run_id, source, role, content, reasoning, tool_calls, at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                 session-id seq run-id source role content reasoning (count calls) at)
    (doseq [call calls]
      (db/execute! c "INSERT OR REPLACE INTO tool_calls
                        (session_id, seq, call_id, name, arguments, result)
                      VALUES (?, ?, ?, ?, ?, NULL)"
                   session-id seq (str (:id call))
                   (get-in call [:function :name])
                   (get-in call [:function :arguments])))
    ;; A TOOL'S ANSWER FINDS ITS CALL BY THE CALL'S OWN ID, and it arrives in a LATER line -- rows are
    ;; consumed in file order, so a replay fills it identically.
    (when-some [call-id (and (= "tool" role) (:tool_call_id (replay/payload (second entry))))]
      (db/execute! c "UPDATE tool_calls SET result = ?
                       WHERE session_id = ? AND call_id = ?"
                   content session-id (str call-id))))
  nil)

(defn- project-model-start!
  "One `model/start` FACT row -> a new row in `model_calls`, keyed by ITS OWN line index.

  The model's NAME is here -- `harness.kernel.event/model-start` records what the request went
  out under -- and the usage is not: that arrives on the `model/end` line, and
  `project-model-end!` fills this row in when it does."
  [^java.sql.Connection c session-id [i row]]
  (let [p (replay/payload row)]
    (db/execute! c "INSERT OR REPLACE INTO model_calls
                      (session_id, seq, run_id, model, at)
                    VALUES (?, ?, ?, ?, ?)"
                 session-id (long i) (:runId row) (:model p) (:ts row))))

(defn- project-model-end!
  "One `model/end` FACT row -> the call its `model/start` opened, filled in with the vendor's report.

  PAIRED BY ORDER, WHICH IS WHAT THE RECORD SAYS: the newest start of this session with no end
  yet is this end's call (`harness.kernel.event/model-start` pairs them the same way). ASKED OF
  THE STORE RATHER THAN REMEMBERED IN A PASS, because a byte offset can fall between the two
  lines -- a pass may read the end long after it read the start, and a pending call held in
  memory would be gone by then. The same reason there is an offset at all, one level down.

  AN END WITH NO START is still a call that happened (a reset onto a log whose first readable
  line is an end): it is written under its OWN line index rather than dropped, and the
  leaderboard counts it."
  [^java.sql.Connection c session-id [i row]]
  (let [p     (replay/payload row)
        usage (:usage p)
        f     (stats/usage-fields usage)
        at    (:ts row)]
    (when (zero? (db/execute! c "UPDATE model_calls
                                    SET end_seq = ?, model = COALESCE(?, model),
                                        prompt_tokens = ?, completion_tokens = ?,
                                        total_tokens = ?, cached_tokens = ?, ms = ? - at
                                  WHERE session_id = ? AND seq = (
                                    SELECT MAX(seq) FROM model_calls
                                     WHERE session_id = ? AND end_seq IS NULL)"
                               (long i) (:model p)
                               (:promptTokens f) (:completionTokens f)
                               (:totalTokens f) (:cachedTokens f) at
                               session-id session-id))
      (db/execute! c "INSERT OR REPLACE INTO model_calls
                        (session_id, seq, run_id, model, end_seq,
                         prompt_tokens, completion_tokens, total_tokens, cached_tokens, at)
                      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                   session-id (long i) (:runId row) (:model p) (long i)
                   (:promptTokens f) (:completionTokens f)
                   (:totalTokens f) (:cachedTokens f) at))))

(defn- project-row!
  "Write ONE record row into the projection. Idempotent by construction: every statement is keyed.

  WHAT A ROW IS DECIDES WHICH TABLE IT LANDS IN (`replay/kind`): a message row the conversation,
  the two `model/*` fact rows the call they bracket. Everything else -- the wire frames and the
  other facts -- is not something this copy answers a question about, and is left alone."
  [^java.sql.Connection c session-id entry]
  (case (replay/kind (second entry))
    "message"     (project-message! c session-id entry)
    "model/start" (project-model-start! c session-id entry)
    "model/end"   (project-model-end! c session-id entry)
    nil)
  nil)

;; ------------------------------------------------------------------ the offset table

(defn- stored-offset
  "What the store says has been projected for SESSION-ID: {:path :byte-offset :line-offset}, or nil.
  (The store's column keys are the Clojure spelling -- `column-key` turns `byte_offset` into
  `:byte-offset` -- so the row is read that way everywhere below.)"
  [session-id]
  (first (db/select "SELECT path, byte_offset, line_offset FROM projection_offsets
                      WHERE session_id = ?"
                    session-id)))

(defn- forget!
  "Drop everything projected for SESSION-ID -- rows AND offset. The first half of a rebuild, and what a
  session whose log was replaced needs."
  [^java.sql.Connection c session-id]
  (db/execute! c "DELETE FROM messages WHERE session_id = ?" session-id)
  (db/execute! c "DELETE FROM tool_calls WHERE session_id = ?" session-id)
  (db/execute! c "DELETE FROM model_calls WHERE session_id = ?" session-id)
  (db/execute! c "DELETE FROM projection_offsets WHERE session_id = ?" session-id))

(defn- forget-everything!
  "Drop the WHOLE projection -- every content table and every offset -- so the next pass copies
  every log again. `rebuild!` with no session named, and the action a schema change that added a
  table asks for (`.scratch/global-stats-panel/`)."
  [^java.sql.Connection c]
  (db/execute! c "DELETE FROM messages")
  (db/execute! c "DELETE FROM tool_calls")
  (db/execute! c "DELETE FROM model_calls")
  (db/execute! c "DELETE FROM projection_offsets"))

(defn- find-log
  "The file SESSION-ID's record is in, or nil: a session that has never run, and -- because
  `replay/find-log` REFUSES a stem with two logs -- a conversation whose file landed in two workspaces,
  which a background pass must not die of."
  [session-id]
  (try (replay/find-log (home/projects-dir) session-id) (catch Throwable _ nil)))

(defn- log-for
  "The file to read for a LISTED session -- the row `listed-sessions` already handed over (id, stored
  path, offsets), so there is NO SECOND STORE READ here. THE STORED PATH FIRST: one `exists`, no walk of
  the log tree; the walk is what a session that has never been projected (or whose file moved, which is
  the same thing -- the old path is gone) pays once."
  [session-id stored-path]
  (if-some [p stored-path]
    (let [f (io/file p)]
      (if (.exists f) f (find-log session-id)))
    (find-log session-id)))

(defn- nothing-new?
  "Whether LISTED's log holds nothing this projection has not already copied: THE SAME FILE, THE SAME
  LENGTH. One `stat` and a comparison -- NO QUERY, NO CONNECTION, and that is the whole point of
  carrying the offset beside the session's id (`.scratch/memory-hygiene/` ticket 04). A SHORTER file
  is not this projection's continuation (a replaced log), so it is never 'unchanged'."
  [^File log {:keys [path byte-offset]}]
  (and (some? log)
       (some? path)
       (= (.getAbsolutePath log) (str path))
       (= (.length log) (long (or byte-offset 0)))))

(defn- read-session!
  "Read SESSION-ID's new bytes and decide what they mean -- NO DATABASE, NO WRITE. Answers the work
  `write-session!` will commit, or nil when the file cannot be read at all (a log deleted between the
  `exists` above and this call is an ordinary race, not an incident)."
  [session-id ^File log listed]
  (let [path      (.getAbsolutePath log)
        ;; THE OFFSET IS ALREADY IN HAND (`listed-sessions` read it with the session), so this is not
        ;; a second store read: that is the per-session connection this pass used to pay.
        stored    listed
        size      (.length log)
        ;; A DIFFERENT FILE, OR A SHORTER ONE, IS NOT THIS PROJECTION'S CONTINUATION: the offset
        ;; describes a stream of bytes, and a log that moved elsewhere or was replaced by a shorter
        ;; one is a different stream. Starting over is the honest answer -- rows the old stream wrote
        ;; would be wrong to keep.
        reset?    (or (nil? (:path stored))
                      (not= path (str (:path stored)))
                      (< (long size) (long (or (:byte-offset stored) 0))))
        from      (if reset? 0 (long (or (:byte-offset stored) 0)))
        from-line (if reset? 0 (long (or (:line-offset stored) 0)))
        [rows to skipped] (new-rows log from from-line)]
    (when (pos? skipped)
      (log/warn! :projection/skipped-lines
                 {:session-id session-id :path path :skipped skipped}))
    {:session-id session-id :path path :rows rows :to to :skipped skipped
     :bytes (- to from)
     :from-line from-line :reset? reset? :total (count rows)}))

(def rows-per-commit
  "HOW MANY ROWS GO IN ONE TRANSACTION. THE LOCK IS HELD FOR THE LENGTH OF A TRANSACTION, and that
  length is what a run notices: `harness.infra.db`'s busy timeout is five seconds, so a projection
  that commits a whole backlog at once makes the run's own bookkeeping (a title, a send time, a
  claim) wait behind it -- and time out. Measured on this machine's suite: with one transaction per
  pass, `harness.edge.http-test` went red on cases that poll a row with a five-second deadline.

  SO A PASS LANDS IN CHUNKS, and the offset lands with the LAST of them: the number in
  `projection_offsets` still means 'everything before this is projected', and no single transaction
  is longer than this many rows."
  200)

(defn- commit-chunk!
  "One transaction's worth: FORGET when this is a reset's first chunk, the rows themselves, and -- on
  the last chunk -- the offset that says they are in."
  [session-id {:keys [path to from-line total reset?]} chunk first? last?]
  (db/with-transaction
    (fn [^java.sql.Connection c]
      (when (and reset? first?) (forget! c session-id))
      (doseq [entry chunk] (project-row! c session-id entry))
      (when last?
        (db/execute! c "INSERT OR REPLACE INTO projection_offsets
                          (session_id, path, byte_offset, line_offset, updated_at)
                        VALUES (?, ?, ?, ?, ?)"
                     (str session-id) (str path) to (+ from-line total)
                     (System/currentTimeMillis)))))
  nil)

(defn- write-session!
  "Commit what `read-session!` decided, IN CHUNKS (see `rows-per-commit`): the rows, the offset, and a
  reset when the log is a different stream."
  [work]
  (let [{:keys [session-id rows skipped total]} work
        chunks (vec (partition-all rows-per-commit rows))]
    (when (pos? skipped)
      (log/warn! :projection/skipped-lines {:session-id session-id :skipped skipped}))
    (if (seq chunks)
      (doseq [[i chunk] (map-indexed vector chunks)]
        (commit-chunk! session-id work chunk (zero? i) (= i (dec (count chunks)))))
      ;; NOTHING NEW TO WRITE, BUT THE OFFSET MAY STILL NEED SAYING: a reset onto an empty log (a
      ;; conversation whose file was replaced by nothing) moves the number without adding a row.
      (commit-chunk! session-id work [] true true)))
  nil)

;; ------------------------------------------------------------------ the pass, and what it is for

(defn- listed-sessions
  "The conversations this home knows AND what this projection has already copied of each, in ONE query
  on the round's own connection: `{:id … :path … :byte-offset … :line-offset …}`, with path/offsets nil
  for a session that has never been projected. THE STORE DECIDES WHICH CONVERSATIONS EXIST -- a log
  sitting in the tree that no row names is not one -- and the offsets ride along because asking per
  session is exactly what made the 2-second clock expensive: two store connections PER CONVERSATION per tick,
  every one of them opening the file and walking the migration chain (`.scratch/memory-hygiene/`
  ticket 04, measured at 3,914 ms a round against a 2,000 ms interval).

  A LISTENER'S ROUND ASKS FOR THE IDS IT WAS MARKED WITH, in the same query and on the same
  connection: `IN (…)` over the marked conversations, so a round that copies one reads the store
  once -- the whole-store pass's discipline applied to the half a listener knows about. IDS EMPTY
  IS NOT 'EVERYTHING' (that is NIL, and it belongs to `project!`): it is no ids at all, and the
  round it describes has nothing to do."
  ([^java.sql.Connection c] (listed-sessions c nil))
  ([^java.sql.Connection c ids]
   (let [base "SELECT s.id AS id, o.path AS path, o.byte_offset AS byte_offset,
                      o.line_offset AS line_offset
                 FROM sessions s
                 LEFT JOIN projection_offsets o ON o.session_id = s.id"]
     (if (seq ids)
       (apply db/query c
              (str base " WHERE s.id IN ("
                   (str/join "," (repeat (count ids) "?")) ") ORDER BY s.id")
              ids)
       (db/query c (str base " ORDER BY s.id"))))))
(defn- project-round!
  "One pass over the sessions IDS names: feed their new bytes into the store. Answers the totals.
  IDS NIL MEANS EVERY SESSION THE STORE LISTS, which is what `project!` is; a collection means
  exactly those, which is the round a listener's marks describe.

  IT IS SAFE TO CALL AS OFTEN AS ANYONE LIKES, AND IT IS CHEAP BECAUSE IT TOUCHES WHAT CHANGED
  (`.scratch/memory-hygiene/` ticket 04): ONE connection for the whole round (`db/with-connection` -- the
  listing and the offsets come from that one query), then per conversation a `stat` and a comparison --
  a session whose file is the same one at the same length produces NO query, NO connection and NO write
  at all. That used to be two connections PER conversation per tick (one to ask the offset, one to ask
  it again), which is why the clock was turned off in ticket 01.

  THE ROWS IT DOES WRITE LAND IN CHUNKS (`rows-per-commit`), because what a run notices is not how many
  transactions a pass spends but HOW LONG ONE HOLDS THE WRITE LOCK. Measured here: with one transaction
  per pass, `harness.edge.http-test` went red on cases whose deadline is five seconds -- the same five
  seconds as the store's busy timeout.

  `:sessions` COUNTS WHAT THIS ROUND TOUCHED -- conversations with new bytes, which is what 'only the
  ones that changed' means. It used to count every conversation that could be read."
  [ids]
  (let [work (db/with-connection
               (fn [c]
                 (vec (keep (fn [listed]
                              (let [log (log-for (:id listed) (:path listed))]
                                (when (and (some? log) (not (nothing-new? log listed)))
                                  ;; A LOG THAT WENT AWAY between the `exists` and the read is an
                                  ;; ordinary race (a test wipes one, a person deletes one): the session
                                  ;; is still the store's, and the next pass sees whatever is there then.
                                  (try (read-session! (:id listed) log listed)
                                       (catch Throwable _ nil)))))
                            (listed-sessions c ids)))))]
    ;; EACH SESSION COMMITS ITSELF, IN CHUNKS (see `write-session!`): a pass that took one lock for
    ;; everything it had to write is exactly the long transaction the chunks exist to avoid.
    (doseq [w work] (write-session! w))
    (let [answer {:sessions (count work)
                  :rows     (reduce + 0 (map :total work))
                  :skipped  (reduce + 0 (map :skipped work))
                  :bytes    (reduce + 0 (map :bytes work))}]
      ;; THE STATISTICS ARE A DOORBELL OF THEIR OWN (`.scratch/global-stats-panel/`): a round that
      ;; wrote rows may have moved a leaderboard, and the ring reaches only a reader who is looking
      ;; at one -- a page with no statistics view open pays nothing for this line.
      (when (pos? (:rows answer)) (host/ring-stats!))
      answer)))

(defn project! []
  "One pass over EVERY session this home lists: the whole-store form of `project-round!`, kept
  because it is the shape a test and a repair call reach for -- `harness.edge.projection-test` drives it
  directly and never starts the trigger. Answers the same totals; a listener's round is the same
  function with the ids it was handed."
  (project-round! nil))

(defn lag []
  "HOW FAR BEHIND THE COPY IS, per session and in total, in bytes (ADR 0008 decision 5): the record's
  own length minus the offset projected. A session with no log is not behind -- there is nothing to
  read -- and one that has never been projected is behind by its whole file, which is the truth rather
  than a special case."
  (let [rows (db/with-connection
               (fn [c]
                 (into {}
                       (keep (fn [listed]
                               (when-some [log (log-for (:id listed) (:path listed))]
                                 (let [size (.length log)
                                       from (long (or (:byte-offset listed) 0))]
                                   [(:id listed) {:path      (.getAbsolutePath log)
                                                  :bytes     size
                                                  :projected from
                                                  :lag       (max 0 (- size from))}])))
                             (listed-sessions c)))))]
    {:total    (reduce + 0 (map :lag (vals rows)))
     :sessions rows}))

(defn rebuild!
  "THE CLAIM AS AN ACTION (ADR 0008 decision 6): throw the projection away and make it again from the
  record -- for one session, or for all of them. Answers what the pass wrote.

  THIS IS THE ONLY REASON A SECOND COPY IS ALLOWED. If rebuilding ever answered something other than
  what was there before, the copy would have become a truth of its own -- which is exactly what the
  boundary this decision overrules was protecting."
  ([] (rebuild! nil))
  ([session-id]
   (db/with-transaction
    (fn [c] (if session-id (forget! c (str session-id)) (forget-everything! c))))
   (project!)))

(defn forget-session!
  "Drop everything this projection holds for SESSION-ID -- the rows AND the offset, both.

  WHAT IT IS NOT, and it is the whole reason it is not `rebuild!`: a rebuild forgets and then
  projects the record again, because the record is still there. This is the door for a record that is
  GOING AWAY (`.scratch/session-lifecycle/`), where projecting again would be making a copy of a
  conversation nobody has."
  [session-id]
  (db/with-transaction (fn [c] (forget! c (str session-id)))))

;; ------------------------------------------------ what the copy is asked to COUNT
;;
;; THE SECOND THING THE PROJECTION IS FOR (ADR 0008: 'a range by `seq`, a search by keyword, a
;; count by tool name'). Everything below is a QUESTION rather than a copy -- the record could
;; answer each of them, only not without reading every log this home holds.

(defn tool-leaderboard
  "Every tool this home has called SINCE the cutoff (epoch ms), most calls first:
  [{:name .. :calls n} ..].

  A RANKING, AND A STABLE ONE: ties break by name, so two reads of an unchanged store answer the
  same order rather than whatever SQLite happened to reach first.

  THE TIME IS THE MESSAGE'S, NOT A COLUMN OF `tool_calls`: a call has no timestamp of its own --
  it rides the assistant message that declared it, on the same record line, so the join is on the
  key the two tables already share (`session_id` + `seq`). Nothing about already-projected rows has
  to be redone to ask this, which is the whole reason it is a join rather than a fifth column."
  [since]
  (let [rows (db/select "SELECT t.name AS name, COUNT(*) AS calls
                           FROM tool_calls t
                           JOIN messages m ON m.session_id = t.session_id AND m.seq = t.seq
                          WHERE t.name IS NOT NULL AND m.at >= ?
                          GROUP BY t.name",
                        since)]
    (vec (sort-by (fn [{:keys [name calls]}] [(- (long calls)) (str name)]) rows))))

(defn- skill-name
  "One `skill` call's arguments -> the skill it named, or nil for arguments this build cannot read."
  [arguments]
  (try (some-> (json/read-str (str arguments) :key-fn keyword) :name str)
       (catch Throwable _ nil)))

(defn skill-leaderboard
  "The SKILLS this home has loaded SINCE the cutoff, most loads first: [{:name .. :calls n} ..].

  A SKILL IS LOADED BY THE `skill` TOOL, and WHICH one is the call's own `name` argument -- so
  this asks `tool_calls` for those rows and counts the name inside the arguments, rather than
  adding a column the projection would have to keep in step with the tool's own schema. The window
  is the declaring message's, the same join `tool-leaderboard` makes."
  [since]
  (let [counts (frequencies (keep (comp skill-name :arguments)
                                  (db/select "SELECT t.arguments AS arguments
                                                FROM tool_calls t
                                                JOIN messages m ON m.session_id = t.session_id
                                                                   AND m.seq = t.seq
                                               WHERE t.name = 'skill' AND m.at >= ?"
                                             since)))]
    (vec (->> counts
              (map (fn [[name calls]] {:name name :calls (long calls)}))
              (sort-by (fn [{:keys [name calls]}] [(- (long calls)) (str name)]))))))

(defn model-leaderboard
  "Token usage per model SINCE the cutoff, most tokens first: [{:model .. :calls n ..} ..].

  A KEY NO CALL REPORTED IS ABSENT FROM THE ROW (`harness.edge.stats/usage-fields` keeps 'not
  reported' apart from zero), and the order puts a model whose usage nobody reported after the
  ones with numbers: a count of calls is not a count of tokens. `model_calls` carries its own
  timestamp (the START line's), so this window needs no join."
  [since]
  (let [rows (db/select "SELECT model AS model, COUNT(*) AS calls,
                                SUM(prompt_tokens) AS prompt_tokens,
                                SUM(completion_tokens) AS completion_tokens,
                                SUM(total_tokens) AS total_tokens,
                                SUM(cached_tokens) AS cached_tokens
                           FROM model_calls
                          WHERE at >= ?
                          GROUP BY model",
                        since)]
    (vec (sort-by (fn [r] [(- (long (or (:total-tokens r) 0)))
                           (- (long (:calls r)))
                           (str (:model r))])
                  (mapv (fn [r]
                          (cond-> {:model (:model r) :calls (long (:calls r))}
                            (some? (:prompt-tokens r))     (assoc :promptTokens (long (:prompt-tokens r)))
                            (some? (:completion-tokens r)) (assoc :completionTokens (long (:completion-tokens r)))
                            (some? (:total-tokens r))      (assoc :totalTokens (long (:total-tokens r)))
                            (some? (:cached-tokens r))     (assoc :cachedTokens (long (:cached-tokens r)))))
                        rows)))))

(defn stats-answer
  "The three leaderboards over the last DAYS days, as one answer -- the whole of what the
  statistics view draws, and the payload `GET /api/stats` and the statistics downlink both hand
  over.

  THE WINDOW IS THE QUESTION, not a filter laid over a whole-home number: each ranking is asked
  with a cutoff, so the store reads the rows in the window and nothing else. `:since` rides along
  so a reader can say what the numbers cover rather than guess."
  [days]
  (let [days  (max 1 (min 3650 (long days)))
        since (- (System/currentTimeMillis) (* days 24 60 60 1000))]
    {:days   days
     :since  since
     :tools  (tool-leaderboard since)
     :skills (skill-leaderboard since)
     :models (model-leaderboard since)}))

;; ------------------------------------------------ making the copy again, for a window
;;
;; THE ONE THING IN THIS NAMESPACE THAT IS NOT INCREMENTAL, and it is A PERSON'S ACTION rather than
;; a process's (`.scratch/global-stats-panel/`): everything above reads bytes it has not read yet;
;; this re-reads a BOUNDED set of logs on request. It exists because a table added to the
;; projection cannot be filled by offsets written before it existed (`harness.infra.db`'s
;; `model-calls` step), and the answer is the statistics view's own button -- NOT a scan at startup,
;; which would be exactly the full read this layer refuses.

;; The trigger's thread, declared here because the scheduling below needs it and its own section is
;; further down (`declare` is the idiom the trigger section uses for `run-round!`).
(declare ^:private clock)

(defn- sessions-in-window
  "The conversations with a projected row at or after SINCE."
  [since]
  (vec (distinct (map :session-id
                      (db/select "SELECT session_id AS session_id FROM messages WHERE at >= ?
                                  UNION
                                  SELECT session_id AS session_id FROM model_calls WHERE at >= ?"
                                 since since)))))

(defn rebuild-window!
  "Make the copy again for the conversations with anything in the last DAYS days, and answer
  {:days n :since ms :sessions n :rows n ...} (the rest of the totals is `project-round!`'s).

  SCOPED ON PURPOSE. `rebuild!` with an argument does one conversation and with no argument does
  the whole store; this does the ones a reader is actually looking at, so the cost is proportional
  to the window rather than to the home.

  IT CHANGES NO RECORD. It deletes projected rows and projects them again from the same logs --
  ADR 0008 decision 6's action, the claim that makes a second copy of the content acceptable."
  [days]
  (let [days  (max 1 (min 3650 (long days)))
        since (- (System/currentTimeMillis) (* days 24 60 60 1000))
        ids   (sessions-in-window since)]
    (db/with-transaction (fn [c] (doseq [id ids] (forget! c (str id)))))
    (assoc (project-round! ids) :days days :since since)))

(defn schedule-rebuild-window!
  "Run `rebuild-window!` on the projection's own thread, so a request does not wait for logs to be
  read. Answers whether it was scheduled: with no trigger running (`start!` was never called -- a
  REPL, a suite) there is no thread of ours to lend, and the caller runs it itself."
  [days]
  (if-some [^ScheduledExecutorService s @clock]
    (do (.schedule s ^Runnable (fn [] (rebuild-window! days)) 0 TimeUnit/MILLISECONDS)
        true)
    false))

;; ------------------------------------------------ the trigger is the write stream, not a clock

;; TICKET 02 OF `.scratch/record-window/`: THE CLOCK IS GONE. It used to be a 2-second
;; `scheduleAtFixedRate` running `project!` -- a walk of the whole session list and a `stat` per
;; conversation -- whether or not a single byte had been written. Measured on an idle process:
;; 100-127 ms a round, ~5% of one core, for ever. The thing that can be pushed IS the record, and
;; it is pushed: `harness.infra.stream/listen-every!` hands every written line to this namespace,
;; so a line landing is the trigger and an idle process has nothing at all to do.
;;
;; A LINE DOES NOT BUY A ROUND (`coalesce-ms`). A streaming answer writes thousands of lines, and a
;; transaction per line would be the clock's cost with a worse shape -- so the listener only MARKS
;; the conversation dirty, and the round that copies it runs on the small scheduler below, ONCE,
;; `coalesce-ms` after the first mark. Marks that land while a round is running belong to the next
;; round, which is scheduled when this one ends; that is what keeps a burst of lines a couple of
;; small transactions instead of thousands, and it is also why a round can never pile up behind
;; itself the way `scheduleAtFixedRate` did.
;;
;; THE LISTENER RUNS ON THE WRITER'S THREAD (`harness.infra.stream`'s own rule), so `mark-dirty!`
;; does the two cheap things and returns: a `swap!` into a set and -- only when no round is already
;; waiting -- a `schedule` on an executor, which does not block. It never touches the store, the
;; log tree or a connection; the round does that, on its own thread.

(def coalesce-ms
  "How long the first mark of a burst waits before a round picks it up, in milliseconds.

  IT IS A DEBOUNCE, NOT AN INTERVAL: nothing is scheduled when nothing is written, so this number
  says how long a reader of the projection can be behind a run AT MOST, not how often the process
  wakes up. A quarter of a second is the compromise between 'the store keeps up with a stream'
  and 'a burst of lines is one transaction': a streaming answer's lines arrive far faster than
  this, so they coalesce, and a reader of the copy is a quarter-second behind what was written."
  250)

(defonce ^:private dirty
  ;; The conversations the write stream has told this namespace about since the last round. A SET,
  ;; because what a round needs is WHICH sessions, not how many lines each one wrote: the bytes are
  ;; on the file, and the per-session offset is what decides which of them are new.
  (atom #{}))

(defonce ^:private scheduled
  ;; Is a round already waiting (or running)? THE ONE THING THAT TURNS A BURST INTO A ROUND: the
  ;; listener's `compare-and-set!` on this is what makes the second..thousandth line of a stream
  ;; cheap, and the round clears it BEFORE it reads so that the marks arriving during the round
  ;; get a round of their own.
  (atom false))

(defonce ^:private rounds-run
  ;; How many rounds this trigger has run. FOR A TEST, and it is the honest observable for both
  ;; claims the ticket makes: an idle process runs ZERO of them, and a burst of lines runs one (or
  ;; two) rather than one per line. Counting connections would say the same thing less directly --
  ;; the test's own reads open connections too.
  (atom 0))

(defonce ^:private clock
  ;; The one thread a round runs on, or nil when nothing is listening. REPLACED, not added to, when
  ;; `start!` is called again -- the session sweeper's own clock keeps the same shape.
  (atom nil))

(defonce ^:private listening
  ;; The way to stop this trigger's listener, held beside the thread it belongs to: stopping the
  ;; projection must unplug its own doorbell, or a suite that starts a hundred servers would leave a
  ;; hundred listeners swapping into `dirty` for the rest of the process.
  (atom nil))

(declare run-round!)

(defn- schedule-round!
  "Make the scheduler run one round in `coalesce-ms`, unless one is already waiting or running or
  there is nothing marked. Answers whether THIS call is the one that scheduled it."
  [^ScheduledExecutorService s]
  (and (seq @dirty)
       (compare-and-set! scheduled false true)
       (try
         (.schedule s ^Runnable (fn [] (run-round!)) coalesce-ms TimeUnit/MILLISECONDS)
         true
         (catch Throwable _
           ;; THE THREAD WAS ALREADY GOING AWAY when the mark arrived (`stop` landed between the
           ;; mark and this call): the mark stays in `dirty`, and the next `start!` picks it up.
           ;; Clearing the flag is what keeps a rejection from wedging every later mark behind a
           ;; round that will never run.
           (reset! scheduled false)
           false))))

(defn- drain-dirty!
  "Take the sessions marked dirty and EMPTY the set in one atomic step: a mark that lands while the
  round is reading its bytes belongs to the NEXT round, not this one, and losing it would be a line
  nobody ever copies." []
  (let [[before _] (swap-vals! dirty (constantly #{}))]
    before))

(defn- mark-dirty!
  "The doorbell: THREAD-ID's record just grew. RUNS ON THE WRITER'S THREAD, so it does the two cheap
  things and returns -- see the section note above. ANY LINE COUNTS, row or not: this namespace
  reads the FILE, and a header line grows it exactly like a message does."
  [thread-id]
  (when (some? thread-id)
    (swap! dirty conj (str thread-id))
    (when-some [^ScheduledExecutorService s @clock]
      (schedule-round! s))))

(defn- run-round! []
  (let [ids (drain-dirty!)]
    (swap! rounds-run inc)
    (try
      (when (seq ids) (project-round! ids))
      (catch Throwable t
        ;; A ROUND THAT THREW DID NOT COPY WHAT IT DRAINED, and those marks are already gone from
        ;; `dirty` -- so they go back, and the round this one schedules tries again. A store that is
        ;; momentarily locked is the case this is for: the clock used to retry the same work two
        ;; seconds later, and a listener must not silently drop it.
        (swap! dirty into ids)
        (log/warn! :projection/failed {:reason (ex-message t)}))
      (finally
        ;; MARKS THAT LANDED WHILE THIS ROUND RAN GET A ROUND OF THEIR OWN. Nothing is scheduled
        ;; when there are none, which is what makes an idle process idle.
        (reset! scheduled false)
        (when-some [^ScheduledExecutorService s @clock]
          (schedule-round! s))))))

(defn start!
  "Start the projection and answer the fn that stops it.

  WHAT IT STARTS IS A LISTENER, NOT A CLOCK (ticket 02 above): attaching the write stream's
  doorbell is the whole of it, and NO PASS RUNS HERE. The first pass waits for the first written
  line, which is a stronger version of the decision the clock made by waiting one 2-second tick:
  this trigger belongs to every process that serves and a suite starts hundreds of servers, so a
  `project!` at 0 meant each of them walked the store and the log tree immediately, took the
  store's write lock while the test was using it, and `harness.edge.http-test` went past its 300s
  limit (measured: three namespaces never got to run). An idle server now pays for one listener
  that is never called -- and an idle process runs no round at all.

  IT ASKS THE STORE NOTHING AND READS NO LOG (`schedule-rebuild-window!` is where the one action
  that does is scheduled, and it is a person's). Everything here is a doorbell: the pass itself
  is the listener's, and it starts nothing.

  WHAT IT GIVES UP, SAID OUT LOUD: a line a PREVIOUS process wrote and never copied before it stopped
  has nobody left to ring the bell, so it waits for that conversation's next line -- the narrow window
  the clock used to close on its next tick. It is bounded by the thing the whole design rests on: a
  live record is APPENDED to, `read-session!` reads from the stored offset to the end of the file, and
  the next line therefore carries the offset past everything before it. A home that wants the copy
  current with no write to wait for calls `project!` or `rebuild!`, both of which are still the whole
  store.
  IDEMPOTENT IN THE WAY THE SWEEPER IS: a second `start!` REPLACES the first trigger (thread and
  listener) rather than stacking a second one, and answers a stop fn for the trigger it made.
  That fn is the teardown `harness.edge.http/start!` keeps: a process that stops serving stops
  copying, and the next one resumes at the offset it left (`projection_offsets`)." []
  ;; THE PREVIOUS TRIGGER GOES FIRST (its listener and its thread), so a second `start!` is a
  ;; replacement rather than a second doorbell nobody ever unplugs.
  (when-some [l @listening]
    (l)
    (compare-and-set! listening l nil))
  (when-some [s @clock]
    (.shutdown ^ScheduledExecutorService s)
    (compare-and-set! clock s nil))
  (let [s (Executors/newSingleThreadScheduledExecutor
           (reify ThreadFactory
             (newThread [_ r] (doto (Thread. ^Runnable r "harness-projection")
                                (.setDaemon true)))))]
    (if (compare-and-set! clock nil s)
      (let [unlisten (stream/listen-every! (fn [{:keys [thread-id]}] (mark-dirty! thread-id)))]
        (reset! listening unlisten)
        (reset! scheduled false)
        ;; A MARK THAT WAS ALREADY IN `dirty` (a trigger replaced while a line was in flight) WOULD
        ;; OTHERWISE SIT UNTIL THE NEXT LINE, so the window is closed by trying once here.
        (schedule-round! s)
        (fn []
          (unlisten)
          (when (identical? unlisten @listening) (compare-and-set! listening unlisten nil))
          (.shutdown ^ScheduledExecutorService s)
          (compare-and-set! clock s nil)
          nil))
      (do (.shutdown ^ScheduledExecutorService s) (start!)))))

(defn pending
  "WHAT THE TRIGGER IS HOLDING, as of this instant: the conversations marked dirty and not yet
  copied, whether a round is waiting or running, and how many rounds this trigger has run.

  IT IS HERE FOR THE TWO CLAIMS THAT ARE OTHERWISE INVISIBLE. 'An idle process does not walk the
  store' is `:dirty` empty AND `:rounds` zero; 'a burst of lines is one round, not one per line'
  is `:rounds` a small number beside a large one. A store-side number (connections, rows) would
  say the same thing less directly, because the test's own reads move it too. A SNAPSHOT IS NOT A
  FACT ABOUT A LATER MOMENT (`docs/rules/concurrency.md`): the round runs on another thread, so
  this answers what was true when it was asked." []
  {:dirty      (vec (sort @dirty))
   :scheduled? (boolean @scheduled)
   :rounds     (long @rounds-run)})

(defn reset-trigger!
  "Forget the marks and the schedule, and count rounds from zero. FOR TESTS, in the spirit of
  `harness.infra.stream/reset-readers!`: a case that starts the projection in a home of its own
  must not inherit the previous case's dirty conversations, and a case about coalescing has to be
  able to say how many rounds IT caused. It does NOT stop a running trigger -- `start!`'s stop fn
  is that." []
  (reset! dirty #{})
  (reset! scheduled false)
  (reset! rounds-run 0))
