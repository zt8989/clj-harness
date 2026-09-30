(ns harness.edge.projection
  "THE CONTENT PROJECTION: the record is the truth, and this keeps a queryable copy of it in the store.

  ADR 0008 (`docs/adr/0008-the-log-is-the-truth-and-sqlite-projects-it.md`) is the decision, and its
  two halves are what this namespace is shaped by:

    IT IS NOT ON THE WRITE PATH. Nothing here is called by `harness.infra.stream` or by `log!`: the
    reader walks each session's NEW BYTES, projects the complete lines it finds, and advances a byte
    offset. A run that is mid-flight is fine -- the projection is simply behind it -- and a process
    that dies mid-pass loses nothing but the pass (the offset is written with the rows it accounts
    for, in one transaction).

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
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.infra.log :as log])
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

(defn- project-row!
  "Write ONE record row into the projection. Idempotent by construction: every statement is keyed."
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
  (db/execute! c "DELETE FROM projection_offsets WHERE session_id = ?" session-id))

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
  session is exactly what made this clock expensive: two store connections PER CONVERSATION per tick,
  every one of them opening the file and walking the migration chain (`.scratch/memory-hygiene/`
  ticket 04, measured at 3,914 ms a round against a 2,000 ms interval)."
  [^java.sql.Connection c]
  (db/query c "SELECT s.id AS id, o.path AS path, o.byte_offset AS byte_offset,
                      o.line_offset AS line_offset
                 FROM sessions s
                 LEFT JOIN projection_offsets o ON o.session_id = s.id
                ORDER BY s.id"))

(defn project! []
  "One pass: feed every session's new bytes into the store. Answers the totals.

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
                            (listed-sessions c)))))]
    ;; EACH SESSION COMMITS ITSELF, IN CHUNKS (see `write-session!`): a pass that took one lock for
    ;; everything it had to write is exactly the long transaction the chunks exist to avoid.
    (doseq [w work] (write-session! w))
    {:sessions (count work)
     :rows     (reduce + 0 (map :total work))
     :skipped  (reduce + 0 (map :skipped work))
     :bytes    (reduce + 0 (map :bytes work))}))

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
   (when session-id
     (db/with-transaction (fn [c] (forget! c (str session-id)))))
   (project!)))

(defn forget-session!
  "Drop everything this projection holds for SESSION-ID -- the rows AND the offset, both.

  WHAT IT IS NOT, and it is the whole reason it is not `rebuild!`: a rebuild forgets and then
  projects the record again, because the record is still there. This is the door for a record that is
  GOING AWAY (`.scratch/session-lifecycle/`), where projecting again would be making a copy of a
  conversation nobody has."
  [session-id]
  (db/with-transaction (fn [c] (forget! c (str session-id)))))

;; ------------------------------------------------------------------ the clock

(def interval-ms
  "How often the background pass runs, in milliseconds. Two seconds is a compromise the lag number
  makes measurable rather than a promise: a reader of the projection is never more than this behind a
  run, and an idle home pays one `exists` per session per tick."
  2000)

(defonce ^:private clock (atom nil))

(defn- run-once! []
  (try (project!)
       (catch Throwable t (log/warn! :projection/failed {:reason (ex-message t)}))))

(defn start!
  "Start the projection's clock and answer the fn that stops it. Idempotent, like the session sweeper:
  a second start answers the first one's stop fn.

  A BACKGROUND THREAD RATHER THAN A STEP ON THE WRITER, which is ADR 0008 decision 2 spelled as a
  mechanism: `stream/push!` must not wait for a database write, and a projection that misses a tick
  is simply a little further behind -- the number `lag` answers."
  []
  (if-some [s @clock]
    (do (.shutdown ^ScheduledExecutorService s) (compare-and-set! clock s nil) (start!))
    (let [s (Executors/newSingleThreadScheduledExecutor
             (reify ThreadFactory
               (newThread [_ r] (doto (Thread. ^Runnable r "harness-projection")
                                  (.setDaemon true)))))]
      ;; THE FIRST PASS WAITS ONE TICK, and that is a MEASURED decision rather than politeness: this
      ;; clock belongs to every process that serves, and a suite starts hundreds of servers. With a
      ;; first pass at 0, each of them walked the store and the log tree immediately, took the store's
      ;; write lock while the test was using it, and `harness.edge.http-test` went past its 300s
      ;; limit (measured: three namespaces never got to run). A server that has been up for one tick
      ;; has a projection that is one tick behind, which is the number `lag` answers -- and a test
      ;; that wants a projection NOW calls `project!`.
      (.scheduleAtFixedRate ^ScheduledExecutorService s
                            ^Runnable (fn [] (run-once!))
                            interval-ms interval-ms TimeUnit/MILLISECONDS)
      (if (compare-and-set! clock nil s)
        (fn [] (.shutdown s) (compare-and-set! clock s nil) nil)
        (do (.shutdown s) (start!))))))
