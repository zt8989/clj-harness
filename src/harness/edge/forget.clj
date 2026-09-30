(ns harness.edge.forget
  "TAKING A CONVERSATION BACK -- the one door that removes a conversation from this home instead of
  hiding it. `harness.cap.project/archive!` is the hiding one, and the difference between them is the
  whole point of having both: an archive writes ONE column and leaves every byte where it is (its own
  docstring says so, and a case asserts the record's length and mtime), while this takes the record and
  everything this home kept about the conversation out of the home for good
  (`.scratch/session-lifecycle/`).

  WHAT GOES, and each of these is a place ONE conversation left something. They live in four
  namespaces, which is why this door exists rather than a caller walking the list itself:

    THE RECORD      the conversation's own `.jsonl`, wherever the tree keeps it
    ITS ROWS        `sessions` (the row that says it exists) and the content copy the projection keeps
                    (`messages`, `tool_calls`, `projection_offsets`)
    ITS ANCHORS     every hashline row keyed by this thread -- the anchors it was shown, the views it
                    had of them, and the row that says it has ever looked at anything
    ITS TASK LIST   `todos`
    ITS CLAIM       a `session_claims` row, which a process that died mid-run can leave behind

  WHAT IT DELIBERATELY LEAVES, one reason each:

    `hashline_undo`     keyed by PATH, not by thread: the last edit of a FILE is undoable by whoever
                        holds that file next, and a conversation being deleted is not the owner of the
                        undo that belongs to another one.
    THE PROJECT ROW     a conversation's project is not the conversation's. Removing a directory is
                        `harness.cap.project/remove-project!`, and it is a different statement.
    THE IN-MEMORY RING  `harness.infra.stream/forget-kept!` is the SESSION's own moment, and putting
                        the session away (`sessions/drop!`) is what this door does BEFORE any row goes:
                        a session served here while its row is deleted underneath it would be a live
                        conversation with no store row, which every reader would then answer about
                        differently -- and `drop!` is also what releases the claim this process holds.

  REFUSED WHILE A RUN IS IN FLIGHT HERE, by name, and for a stronger reason than an archive's refusal:
  a run is writing the very bytes being deleted, and its landing callback would write into a table whose
  row is gone. A conversation held by ANOTHER process is not refused -- this door cannot see another
  process's runs, and the claim it does not hold is not its to release.

  THE ORDER IS THE STORE FIRST AND THE FILE LAST, which is ADR 0008 read as an instruction (the record
  is the truth and the store is a copy that can be made again). A row without a record is a
  conversation this home would keep listing for ever, with nothing behind it; a record without a row is
  one the tree walk still shows and a rebuild re-registers. Losing the copy is recoverable; losing the
  conversation is not. And the file goes last because it is the only step that CANNOT be retried from
  what is left -- every delete before it is idempotent, so a failure part way through leaves a
  conversation that can be deleted again."
  (:require [clojure.java.io :as io]
            [harness.cap.claims :as claims]
            [harness.cap.hashline.store :as hashline]
            [harness.cap.project :as project]
            [harness.cap.todos :as todos]
            [harness.edge.projection :as projection]
            [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]
            [harness.infra.home :as home]))

(defn record-file
  "The file THREAD-ID's record lives in, or nil when this home holds none.

  NIL RATHER THAN A REFUSAL, because 'no record' is an ordinary state for a conversation to be in: a
  session that was registered and never run has none, and one whose file a person deleted by hand has
  none either. Neither is a reason to refuse the deletion of the row."
  [thread-id]
  (try (replay/locate (home/projects-dir) thread-id)
       (catch Throwable _ nil)))

(defn forget!
  "Take THREAD-ID back out of this home. Answers the id.

  Throws, BY NAME: for a run of it that is in flight here (nothing is deleted then), for an id this
  home has never seen (`harness.cap.project/delete-session!` refuses it), and for a store or a file that
  will not go. The namespace docstring says the order and why it is that order."
  [thread-id]
  (let [id (str thread-id)]
    (when (sessions/running? id)
      (throw (ex-info (str "there is a run of " id " in flight here, so it is not deleted"
                           " -- stop it first, or wait for it to finish")
                      {:thread-id id :reason :running})))
    ;; THE LIVE SESSION FIRST, when this process is the one holding it: putting it away stops the
    ;; processes it started, hands back the claim, and is the moment the in-memory ring of its record
    ;; goes. A session this process does not hold is left alone -- another process's claim is not this
    ;; door's to release.
    (when (some? (sessions/live-entry id)) (sessions/drop! id))
    (project/delete-session! id)
    (projection/forget-session! id)
    (hashline/forget-thread! id)
    (todos/forget! id)
    (claims/forget! id)
    (when-some [f (record-file id)]
      (io/delete-file f true))
    id))
