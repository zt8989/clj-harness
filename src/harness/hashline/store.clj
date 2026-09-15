(ns harness.hashline.store
  "What anchor-based editing remembers, and where: four tables in the home's
  store (`harness.db`), queried from here.

  NOTHING IS CACHED. Every function below opens the store, asks its question and
  closes -- there is no atom, no delay, no session object. That is why a RESTART
  IS A NO-OP for this namespace: the anchors a session handed out before the
  process died are the anchors it hands out after, because the answer was never
  in memory to lose. It is also why the tables are keyed the way they are (see
  harness.db/hashline-store) rather than by anything process-local.

  WHAT LIVES HERE, and the one thing that does not. Here: the stored view of a
  file (its anchors and their checksums), which anchors a session has out, where
  its allocation probe stands, and the single edit that can still be undone. NOT
  here: which lines a request has actually SHOWN the model -- that is a fact about
  one call, not about the session, and the tools that emit rows are where it
  belongs.

  THE CRITICAL SECTION, and why the mutex is here rather than in the tools. An
  edit reads a file, checks it against the stored view, writes it, advances the
  anchors and records the undo. Those five steps have to happen as one, and
  `harness.loop/drive!` runs a turn's tool calls CONCURRENTLY -- so two edits to
  one file in one turn each read the same base state, each compute their own new
  content, and the later write wins while both report success. That is silent
  data loss, not a race that shows up in a log.

  The store's own isolation does not fix it: a transaction makes a write atomic,
  not a read-check-write SEQUENCE, and the sequence is what has to be exclusive.
  So `with-path-lock` guards the whole thing, keyed by the CANONICAL path (two
  spellings of one file must take the same lock), and `advance-with-undo!` is the
  single transaction that lands the database half. The file write in the middle is
  the caller's, and it holds this lock while it does it."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [harness.db :as db])
  (:import [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.locks Lock ReentrantLock]))

;; ------------------------------------------------------------------ encoding

(defn- render [v] (json/write-str v))
(defn- parse  [s] (json/read-str s))

;; ------------------------------------------------------------------- reading

(defn state
  "THREAD-ID's stored view of PATH -- {:file-checksum :line-count :anchors
  :line-checksums} -- or nil when this session has never been shown that file.

  Nil is the everyday answer for the first read of a file, and the tools read it
  as 'there is nothing to validate against, so hand out fresh anchors'."
  [thread-id path]
  (when-let [row (first (db/select "SELECT file_checksum, line_count, anchors, line_checksums
                                      FROM hashline_snapshots
                                     WHERE path = ? AND thread_id = ?"
                                   path (str thread-id)))]
    {:file-checksum  (:file-checksum row)
     :line-count     (:line-count row)
     :anchors        (vec (parse (:anchors row)))
     :line-checksums (vec (parse (:line-checksums row)))}))

(defn ownership
  "THREAD-ID's anchors, as anchor -> the path each one currently names. What
  minting walks past, and what an edit is validated against."
  [thread-id]
  (into {}
        (map (fn [row] [(:anchor row) (:path row)]))
        (db/select "SELECT anchor, path FROM hashline_ownership WHERE thread_id = ?"
                   (str thread-id))))

(defn probe-of
  "Where THREAD-ID's allocation probe stands, or nil when it has never minted --
  in which case the caller seeds it from the session key (see anchors/seed)."
  [thread-id]
  (some-> (first (db/select "SELECT probe FROM hashline_sessions WHERE thread_id = ?"
                            (str thread-id)))
          :probe))

(defn undo-for
  "The one edit PATH can still take back, or nil. The record names the text on
  both sides of it, so an undo can both restore and refuse -- see ticket 08."
  [path]
  (when-let [row (first (db/select "SELECT * FROM hashline_undo WHERE path = ?" path))]
    {:prior-text     (:prior-text row)
     :bom            (not (zero? (long (:bom row))))
     :ending         (:ending row)
     :anchors        (vec (parse (:anchors row)))
     :resulting-text (:resulting-text row)
     :mode           (:mode row)}))

;; ------------------------------------------------------------------- writing

(defn- put-state!
  "Upsert the file's stored view on an open connection."
  [c thread-id path {:keys [file-checksum line-count anchors line-checksums]}]
  (db/execute! c "INSERT INTO hashline_snapshots
                    (path, thread_id, file_checksum, line_count, anchors, line_checksums, updated_at)
                  VALUES (?, ?, ?, ?, ?, ?, ?)
                  ON CONFLICT(path, thread_id) DO UPDATE SET
                    file_checksum  = excluded.file_checksum,
                    line_count     = excluded.line_count,
                    anchors        = excluded.anchors,
                    line_checksums = excluded.line_checksums,
                    updated_at     = excluded.updated_at"
               path (str thread-id) file-checksum line-count
               (render anchors) (render line-checksums) (System/currentTimeMillis)))

(defn- claim!
  "Record the anchors this edit ADDED. A plain INSERT on (thread_id, anchor): the
  primary key is the exclusivity rule, so a collision is refused by the schema
  rather than hoped against in code."
  [c thread-id path added]
  (doseq [a added]
    (db/execute! c "INSERT INTO hashline_ownership (thread_id, anchor, path) VALUES (?, ?, ?)"
                 (str thread-id) a path)))

(defn- release!
  "Give back the anchors this edit FREED. Scoped to the path they named, so a
  stale `:freed` from elsewhere cannot take an anchor off a live line."
  [c thread-id path freed]
  (doseq [a freed]
    (db/execute! c "DELETE FROM hashline_ownership
                     WHERE thread_id = ? AND anchor = ? AND path = ?"
                 (str thread-id) a path)))

(defn- put-probe!
  [c thread-id probe]
  (db/execute! c "INSERT INTO hashline_sessions (thread_id, probe, updated_at)
                  VALUES (?, ?, ?)
                  ON CONFLICT(thread_id) DO UPDATE SET
                    probe = excluded.probe, updated_at = excluded.updated_at"
               (str thread-id) probe (System/currentTimeMillis)))

(defn- put-undo!
  [c path {:keys [prior-text bom ending anchors resulting-text mode]}]
  (db/execute! c "INSERT INTO hashline_undo
                    (path, prior_text, bom, ending, anchors, resulting_text, mode, updated_at)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                  ON CONFLICT(path) DO UPDATE SET
                    prior_text = excluded.prior_text, bom = excluded.bom,
                    ending = excluded.ending, anchors = excluded.anchors,
                    resulting_text = excluded.resulting_text, mode = excluded.mode,
                    updated_at = excluded.updated_at"
               path prior-text (if bom 1 0) ending (render anchors) resulting-text
               mode (System/currentTimeMillis)))

(defn advance!
  "Land the database half of one edit for THREAD-ID on PATH, in ONE transaction:
  the file's new stored view, the anchors it added, the anchors it freed, and
  where its probe now stands.

  CHANGE is `harness.hashline.anchors/align`'s answer, plus the file's new
  :file-checksum and :line-checksums. One transaction because the ownership and
  the view are two halves of one fact -- a view that names an anchor the session
  does not own, or ownership of an anchor no view names, is a state no reader
  could act on."
  [thread-id path change]
  (let [thread-id (str thread-id)
        {:keys [added freed probe file-checksum line-checksums anchors]} change]
    (db/with-transaction
      (fn [c]
        (put-state! c thread-id path {:file-checksum  file-checksum
                                      :line-count     (count line-checksums)
                                      :anchors        anchors
                                      :line-checksums line-checksums})
        (release! c thread-id path freed)
        (claim!   c thread-id path added)
        (put-probe! c thread-id probe)))
    change))

(defn advance-with-undo!
  "The whole database half of an edit, in ONE transaction: everything `advance!`
  writes, plus the undo record for it.

  This is the transaction ticket 09's batch rests on. An edit that advanced the
  anchors but failed to record the undo would leave a file whose last change can
  no longer be taken back, and one that recorded the undo without advancing would
  offer to restore text whose anchors the session no longer holds. Either half
  alone is a lie about what happened, so they commit together or not at all.

  UNDO may be nil -- an edit with nothing to undo -- and then this is `advance!`
  with a different name. It is the same function on purpose: a caller choosing
  which one to call based on whether it has an undo record would be a caller that
  can get the choice wrong."
  [thread-id path change undo]
  (let [thread-id (str thread-id)
        {:keys [added freed probe file-checksum line-checksums anchors]} change]
    (db/with-transaction
      (fn [c]
        (put-state! c thread-id path {:file-checksum  file-checksum
                                      :line-count     (count line-checksums)
                                      :anchors        anchors
                                      :line-checksums line-checksums})
        (release! c thread-id path freed)
        (claim!   c thread-id path added)
        (put-probe! c thread-id probe)
        (when undo (put-undo! c path undo))))
    change))

(defn record-undo!
  "Record undo information for PATH on its own, for the case where a caller must
  put it down BEFORE it touches the file (so a failed write can be rolled back).
  `advance-with-undo!` is the usual route and the one an edit should take."
  [path undo]
  (db/with-transaction (fn [c] (put-undo! c path undo))))

(defn clear-undo!
  "Forget PATH's undo history. `write` calls this: the file is no longer the file
  the model was looking at, so the edit that came before it is not something to
  offer to take back."
  [path]
  (db/with-transaction (fn [c] (db/execute! c "DELETE FROM hashline_undo WHERE path = ?" path))))

(defn forget-file!
  "Drop THREAD-ID's whole view of PATH -- the stored anchors and every anchor it
  still owned there -- in ONE transaction.

  This is what a `write` does to a file, and it is deliberately total: the file's
  content is no longer the content those anchors were minted against, so every one
  of them has to go, not just the ones the write happened to change. Leaving them
  owned would keep them out of circulation for a session that can no longer use
  them, and leaving the stored view would let the next edit validate against a
  file that no longer exists.

  The undo record is NOT touched here: `write` clears it separately, because
  'forget this file' and 'this file's last edit is no longer undoable' are two
  statements and a caller may want the first without the second."
  [thread-id path]
  (db/with-transaction
    (fn [c]
      (db/execute! c "DELETE FROM hashline_ownership WHERE thread_id = ? AND path = ?"
                   (str thread-id) path)
      (db/execute! c "DELETE FROM hashline_snapshots WHERE thread_id = ? AND path = ?"
                   (str thread-id) path))))

;; ------------------------------------------------------------------ the lock

(defonce ^:private path-locks (ConcurrentHashMap.))

(defn canonical
  "PATH as the identity this namespace keys locks and rows by: absolute, symlinks
  and `..` chased away. Two spellings of one file must land on the same lock,
  which is the whole reason this exists -- `a/../a/f.txt` and `a/f.txt` are
  different strings and the same file."
  [path]
  (try
    (.getCanonicalPath (io/file path))
    (catch Exception _ (str path))))

(defn- lock-for ^Lock [^String canon]
  (or (.get ^ConcurrentHashMap path-locks canon)
      (let [fresh (ReentrantLock.)]
        (or (.putIfAbsent path-locks canon fresh) fresh))))

(defn with-path-lock
  "Run F holding the lock for PATH's canonical form.

  REENTRANT, so nested use does not deadlock, and keyed per canonical path, so two
  edits to one file serialize while edits to different files do not. The map
  grows with the number of distinct paths a process ever edits, which is bounded
  by the files a session actually touches and is the price of not having a
  registry to invalidate."
  [path f]
  (let [^Lock l (lock-for (canonical path))]
    (.lock l)
    (try (f) (finally (.unlock l)))))
