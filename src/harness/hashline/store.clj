(ns harness.hashline.store
  "What anchor-based editing remembers, and where: four tables in the home's
  store (`harness.db`), queried from here.

  NOTHING IS CACHED. Every function below opens the store, asks its question and
  closes -- there is no atom, no delay, no session object. That is why a RESTART
  IS A NO-OP for this namespace: the anchors a session handed out before the
  process died are the anchors it hands out after, because the answer was never
  in memory to lose. It is also why the tables are keyed the way they are (see
  harness.db/hashline-store) rather than by anything process-local.

  WHAT LIVES HERE: the stored view of a file (its anchors and their checksums,
  including which of them the model has actually SEEN), which anchors a session has
  out, where its allocation probe stands, and the single edit that can still be
  undone. Four tables, and the reason `:served` is one of them rather than a fact
  about a call is ticket 04's finding: a paged read mints anchors for lines it did
  not return, so 'this anchor exists' and 'this anchor was shown' are different
  facts, and an edit addressed at an unshown line is the guess this scheme exists
  to prevent.

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
  :line-checksums :served} -- or nil when this session has never been shown that
  file.

  Nil is the everyday answer for the first read of a file, and the callers read it
  as 'there is nothing to validate against, so hand out fresh anchors'.

  `:served` is the anchors the model has actually SEEN (a set), which is not the
  same as the anchors that exist: a paged read mints anchors for lines it did not
  return, and an edit must not be addressed by one of those."
  [thread-id path]
  (when-let [row (first (db/select "SELECT file_checksum, line_count, anchors, line_checksums, served
                                      FROM hashline_snapshots
                                     WHERE path = ? AND thread_id = ?"
                                   path (str thread-id)))]
    {:file-checksum  (:file-checksum row)
     :line-count     (:line-count row)
     :anchors        (vec (parse (:anchors row)))
     :line-checksums (vec (parse (:line-checksums row)))
     :served         (set (parse (:served row)))}))

(defn ownership
  "THREAD-ID's anchors, as anchor -> the path each one currently names. What
  minting walks past, and what an edit is validated against."
  [thread-id]
  (into {}
        (map (fn [row] [(:anchor row) (:path row)]))
        (db/select "SELECT anchor, path FROM hashline_ownership WHERE thread_id = ?"
                   (str thread-id))))

(defn owner-of
  "The file ANCHOR currently names in THREAD-ID's session, or nil when this session
  does not hold it.

  This is how `replace` finds the file to edit when it was called without a path:
  an anchor is unique to one file for one session, so the anchor IS the address and
  the path is derived rather than asked for. It is also the check behind
  `:require-path` -- a caller-supplied path that disagrees with the anchor is
  refused, because editing the file you named while addressing lines in another is
  exactly the mistake that mode exists to catch."
  [thread-id anchor]
  (some-> (first (db/select "SELECT path FROM hashline_ownership
                              WHERE thread_id = ? AND anchor = ?"
                            (str thread-id) anchor))
          :path))

(defn claim-anchors!
  "Record ANCHORS as owned by THREAD-ID for PATH, leaving rows that already exist
  alone.

  `advance!` claims what an edit ADDED and knows those rows cannot exist yet -- a
  collision there is a broken exclusivity invariant and is refused by the schema,
  which is what `claim!`'s bare INSERT is for. This one is for a caller holding
  anchors it has established ARE this session's (harness.hashline.replace's
  content fallback), where some are already recorded and re-claiming them is not an
  exception but the expected case."
  [thread-id path anchors]
  (when (seq anchors)
    (db/with-transaction
      (fn [c]
        (doseq [a anchors]
          (db/execute! c "INSERT INTO hashline_ownership (thread_id, anchor, path)
                          VALUES (?, ?, ?)
                          ON CONFLICT(thread_id, anchor) DO NOTHING"
                       (str thread-id) a path))))))

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
  "Upsert the file's stored view on an open connection. SERVED is an optional set
  of anchors to record as shown; omitting it LEAVES THE STORED ONE ALONE, which is
  what an edit wants -- the rows it showed are added, not the whole set restated."
  [c thread-id path {:keys [file-checksum line-count anchors line-checksums served served?]}]
  (db/execute! c "INSERT INTO hashline_snapshots
                    (path, thread_id, file_checksum, line_count, anchors, line_checksums, served, updated_at)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                  ON CONFLICT(path, thread_id) DO UPDATE SET
                    file_checksum  = excluded.file_checksum,
                    line_count     = excluded.line_count,
                    anchors        = excluded.anchors,
                    line_checksums = excluded.line_checksums,
                    served         = CASE WHEN ? THEN excluded.served ELSE hashline_snapshots.served END,
                    updated_at     = excluded.updated_at"
               path (str thread-id) file-checksum line-count
               (render anchors) (render line-checksums)
               (render (vec (or served [])))
               (System/currentTimeMillis)
               (if served? 1 0)))

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

(defn- advance-on!
  "The statements `advance!`/`advance-with-undo!` share, on a connection they have
  already opened a transaction on.

  `:served` is INTERSECTED with the new anchor set here rather than taken as given:
  an anchor that survived the edit stays shown, one that was freed was shown and is
  gone, and one that was minted was not shown to anyone yet. The intersection is
  done in Clojure because SQL has no set operation over a JSON column, and it is
  done AT ALL because the stored set is otherwise append-only in a world where
  anchors come and go -- it would grow for the life of the session with names that
  no longer address anything.

  The rows an edit hands back are added afterwards by the caller, with
  `mark-served!`, because only the caller knows which rows it actually emitted."
  [c thread-id path {:keys [added freed probe file-checksum line-checksums anchors served served?]}]
  (let [surviving (set anchors)
        served    (when served? (into #{} (filter surviving) served))]
    (put-state! c thread-id path {:file-checksum  file-checksum
                                  :line-count     (count line-checksums)
                                  :anchors        anchors
                                  :line-checksums line-checksums
                                  :served         served
                                  :served?        served?})
    (release! c thread-id path freed)
    (claim!   c thread-id path added)
    (put-probe! c thread-id probe)))

(defn advance!
  "Land the database half of one edit for THREAD-ID on PATH, in ONE transaction:
  the file's new stored view, the anchors it added, the anchors it freed, and
  where its probe now stands.

  CHANGE is `harness.hashline.anchors/align`'s answer, plus the file's new
  :file-checksum and :line-checksums, optionally :served (the shown set to record,
  intersected with the surviving anchors). One transaction because the ownership
  and the view are two halves of one fact -- a view that names an anchor the
  session does not own, or ownership of an anchor no view names, is a state no
  reader could act on."
  [thread-id path change]
  (let [thread-id (str thread-id)]
    (db/with-transaction (fn [c] (advance-on! c thread-id path change)))
    change))

(defn mark-served!
  "Record ANCHORS as shown to the model for THREAD-ID's view of PATH. ANCHORS is a
  SET of anchors, which is what the rows emitter produces and why this does not
  call `distinct`: in Clojure 1.12 `(distinct coll)` destructures its argument with
  `nth`, so it throws on a set rather than de-duplicating it. A set is already
  distinct; `vec` is the whole conversion needed.

  IT ADDS, IT NEVER REPLACES, and that is the whole difference between this and the
  `:served` that `advance!` writes. Advancing INTERSECTS the shown set with the
  surviving anchors -- correct for an alignment, since it is pruning to what still
  exists -- whereas this unions. An edit does both, in order: advance, then mark the
  rows it handed back.

  Reading the stored set and writing the union happens INSIDE the transaction, so
  two calls marking the same file at once cannot each add their own half and lose
  the other's -- which is exactly what 'set the column to my set' would do. The
  earlier version of this did set, and the symptom was not subtle: after one edit,
  every line the edit's few answer rows did not happen to mention became 'never
  shown to you', including the lines the model had just read."
  [thread-id path anchors]
  (when (seq anchors)
    (db/with-transaction
      (fn [c]
        (let [stored (first (db/query c "SELECT served FROM hashline_snapshots
                                          WHERE path = ? AND thread_id = ?"
                                      path (str thread-id)))
              before (if stored (set (parse (:served stored))) #{})]
          (db/execute! c "UPDATE hashline_snapshots
                             SET served = ?
                           WHERE path = ? AND thread_id = ?"
                       (render (vec (into before anchors))) path (str thread-id)))))))

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
  (let [thread-id (str thread-id)]
    (db/with-transaction
      (fn [c]
        (advance-on! c thread-id path change)
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
