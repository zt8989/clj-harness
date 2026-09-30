(ns harness.cap.project
  "A session's project directory. thread-id -> binding is this namespace's whole
  state, and since the sqlite ticket it is a ROW rather than an atom entry: the
  home's store holds one `projects` row per directory and one `sessions` row per
  conversation, and a session's project is a foreign key on the second. What that
  buys is the thing the atom could not do -- the binding is still there after the
  process is gone -- and what it costs is that a binding is now a database read.

  Both tables' DDL lives in harness.infra.db/migrations (the store owns the schema);
  the QUERIES over them live here, with the entity they are about.

  A BINDING IS STILL A DEFAULT, NOT A FENCE. The binding re-roots the file tools
  and the shell for one session:

    - resolve-path maps a RELATIVE path into the project directory; an
      absolute path passes through untouched.
    - binding-for answers the directory (or nil), which is what bash needs for
      its working directory, what the project endpoints report, and what a
      session asking about itself is told.

  out-of-bounds? is the separate, explicit enforcement question: whether a
  resolved path stays inside the directories a bound session may touch. The
  fence engages ONLY when a binding exists, so an unbound session gets false for
  everything -- the same regression guarantee resolve-path makes. What to DO
  about an out-of-bounds answer (park it, ask a human) is the tool seam's
  business, not this namespace's.

  THE FENCE HAS A SECOND AND TIGHTER HALF, and that half is not a property of any
  project: THIS HOME declares paths sensitive (config.edn's :security :sensitive-paths --
  the cloud CLIs, key material and keychains, container and package-manager credentials)
  and a file tool aimed at one of them parks a human EVEN WHEN the path is inside the
  fence. A project bound at the operator's home directory would otherwise hand
  ~/.ssh/id_rsa to a read with nothing asked. That half is not a project's to configure
  and not a missing binding's to switch off, which is why it sits BESIDE the fence rather
  than inside it -- see sensitive-paths and sensitive-path?.

  The allowed set is itself configurable per project: .harness/harness.edn in
  the bound project (overlaid on the configuration home's user-level
  harness.edn) can add allow paths and tighten the fence. See harness-config
  for the two-level shape, and out-of-bounds? for what the fence does with it.

  TWO QUESTIONS, TWO COLUMNS, and the difference is load-bearing enough to argue
  once, here. The `projects` row carries IDENTITY -- `canonical_path`, the one
  form every spelling of a directory collapses to, so the same folder can never
  become two projects. The `sessions` row carries the SPELLING that session was
  bound with, because `bind!` hands back the string it was given, `binding-for`
  answers it, and the shell runs in it: all of that is about this session's
  binding, and none of it should change because some other session bound the same
  directory through a different spelling. One column of each kind is what lets
  both statements be true at once -- identity shared, spelling not.

  WHAT A SESSION OPENS WITH is answered here too, for a structural reason rather
  than a topical one: skill-roots and preamble-files pair a harness.edn value
  with the session's binding, and this is the only namespace that can see the
  config reader, the binding, and both of their (deliberately pure, deliberately
  project-free) consumers."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.infra.env :as env]
            [harness.cap.preamble :as preamble]
            [harness.cap.jobs :as jobs]
            [harness.cap.skills :as skills]
            ;; The config.edn half of the sensitive list, READ here rather than re-parsed:
            ;; that namespace owns the file (its shape check, its built-in list, its
            ;; writer), and this one owns what the list MEANS for a call.
            [harness.cap.providers :as providers])
  (:import (java.io File IOException)
           (java.sql Connection)))

(defn- absolute
  "DIR as an absolute path string. Kept as the input's own form where possible
  (getAbsolutePath does not chase symlinks), because the value is echoed back
  to the user and handed to ProcessBuilder -- both want what was asked for,
  resolved against the process working directory, not canonicalized."
  [dir]
  (str (.getAbsoluteFile (io/file dir))))

(defn- canonical-path
  "F's canonical form -- symlinks chased, `..` collapsed, absolute -- which is how
  this namespace decides that two spellings name ONE directory.

  Failure is a NAMED error rather than a fallback to the spelling as given:
  falling back would make one directory two projects whenever canonicalization
  hiccuped, and 'why do I have two entries for one folder' is a far worse thing
  to have to debug than this message. The message carries the ABSOLUTE path, like
  every other refusal in this repo, so it names a file the reader can go look at
  rather than a string whose meaning still depends on where they are standing."
  [^File f]
  (try
    (.getCanonicalPath (.getAbsoluteFile f))
    (catch IOException e
      (throw (ex-info (str "could not resolve " (.getAbsolutePath f)
                           " to a canonical path (" (ex-message e) "), so it cannot be"
                           " told apart from another spelling of the same directory")
                      {:path (.getAbsolutePath f) :reason :uncanonical})))))

(defn- upsert-project!
  "The project row for CANONICAL, created if this home has not seen that
  directory before. Returns its id.

  A directory gets exactly one row however it was spelled, which is the whole
  point of keying on the canonical form -- and why nothing about the spelling
  belongs in this table: two sessions bound through two spellings share this row
  and must not fight over what it says."
  [^Connection c ^String canonical]
  (if-some [row (first (db/query c "SELECT id FROM projects WHERE canonical_path = ?" canonical))]
    (:id row)
    (:id (first (db/query c "INSERT INTO projects (canonical_path, created_at)
                             VALUES (?, ?) RETURNING id"
                          canonical (System/currentTimeMillis))))))

(defn- touch-session!
  "Record that SESSION-ID now belongs to project PROJECT-ID, storing the absolute
  PATH this session was bound with. Creates the row if this home has not seen
  that conversation before. The archived flag is left alone: rebinding is not a
  change of archival state.

  `last_project_path` is set alongside, and that is not a second copy of the same
  fact: it is the CANONICAL form, which is what a later re-add matches on -- see
  `adopt-remembered-sessions!` for what reads it. Both are written from the one
  place a binding is ever made, so a session that is bound cannot end up with a
  memory that disagrees with its binding."
  [^Connection c session-id project-id ^String path ^String canonical]
  (db/execute! c "INSERT INTO sessions (id, project_id, path, last_project_path, created_at)
                  VALUES (?, ?, ?, ?, ?)
                  ON CONFLICT(id) DO UPDATE SET project_id        = excluded.project_id,
                                                path              = excluded.path,
                                                last_project_path = excluded.last_project_path"
               session-id project-id path canonical (System/currentTimeMillis)))

(defn- adopt-remembered-sessions!
  "Rebind every session that remembers CANONICAL as its last project, and answer
  how many there were.

  THIS IS WHAT MAKES REMOVING A PROJECT REVERSIBLE, and it is the only reason
  `sessions.last_project_path` exists. A removal deletes the project row and the
  version 1 trigger unbinds its sessions -- after which those sessions are, to
  every reader, sessions that were never bound: `binding-for` answers nil and the
  fence is off. What they kept is the memory of where they were, and this is the
  other half of that pair: the moment a directory becomes a project again, the
  sessions that remember it come back with it. Their archive flags were never
  touched, so those come back too, and their logs never moved, so the history
  does.

  A session that is ALREADY bound elsewhere is left alone. Rebinding it would
  quietly pull a conversation out of the project a person moved it to, on the
  strength of an older memory -- and re-adding a directory is not a request to
  reorganize anything.

  Called from `add-project!`, which is exactly where a directory becomes a
  project: a bind to an already-existing project must not adopt (the sessions
  there are already bound), and there is no third way into this table."
  [^Connection c ^String canonical]
  (db/execute! c "UPDATE sessions
                     SET project_id = (SELECT id FROM projects WHERE canonical_path = ?),
                         path       = last_project_path
                   WHERE last_project_path = ?
                     AND project_id IS NULL"
               canonical canonical))

(defn- checked-directory
  "DIR as a File, having established that it IS a directory. A typo'd path must
  fail where it was typed, not surface later as a confusing read failure, so both
  refusals are NAMED and carry the path as given. Shared by every verb that
  accepts a directory, so a new verb cannot accidentally skip the check."
  [dir]
  (let [f (io/file dir)]
    (when-not (.exists f)
      (throw (ex-info (str "no such directory: " dir)
                      {:path (str dir) :reason :missing})))
    (when-not (.isDirectory f)
      (throw (ex-info (str "not a directory: " dir)
                      {:path (str dir) :reason :not-a-directory})))
    f))

(defn add-project!
  "DIR becomes a project of this home, with no session in it yet. Returns
  {:project-id .. :path <canonical> :adopted <n>}.

  THIS IS THE ONE VERB THAT CREATES A PROJECT WITHOUT A CONVERSATION, and it is
  what the sidebar's 'add a project' is. Without it the only way to get a project
  was to bind a session to a directory, which is backwards for a product whose
  sessions must belong to a project: the first project would need a session that
  had nowhere to go.

  FIND-OR-CREATE, keyed on the canonical path, so adding a directory that is
  already listed is not an error and does not produce a second row -- it answers
  the SAME project, and the caller (the sidebar) switches to it. That is the
  behaviour a person gets by re-adding something they forgot was already there,
  and a 'duplicate' refusal would be worse than useless: there is nothing for them
  to fix.

  IT ALSO ADOPTS THE SESSIONS THAT REMEMBER THIS DIRECTORY, which is the other
  half of `remove-project!`: the directory becomes a project again and the
  conversations that were in it come back, archive flags and logs included. Nil
  for a project that was never removed, because nothing remembers a directory the
  sidebar has never let go of.

  Validation happens before anything is written, and it is the same check `bind!`
  runs -- see `checked-directory`."
  [dir]
  (let [f     (checked-directory dir)
        canon (canonical-path f)]
    (db/with-transaction
      (fn [^Connection c]
        (let [id (upsert-project! c canon)]
          {:project-id id
           :path       canon
           :adopted    (long (adopt-remembered-sessions! c canon))})))))

(defn bind!
  "Bind THREAD-ID's session to directory DIR, which must exist and be a
  directory -- anything else throws a NAMED error, because a typo'd path must
  fail where it was typed, not surface later as a confusing read failure.
  Returns the absolute path the binding stored.

  DIR of nil DROPS the binding (the unbind direction; rebinding to another
  directory is just binding again -- the last bind wins). Dropping never creates
  a session row: a conversation this home has never heard of is not one it
  should start keeping, and the drop is a no-op on it exactly as the old
  dissoc was.

  DROPPING ALSO CLEARS THE REMEMBERED PROJECT, and that is the line between this
  verb and removing a project. `(bind! id nil)` is a REQUEST to release this
  session -- normally because it is about to be bound somewhere else -- so the
  session is released, memory and all, and removing a project later cannot drag
  it back. `remove-project!` says nothing about any session: it deletes one
  directory's row and leaves the conversations in it remembering where they were,
  which is what lets re-adding the directory bring them home. One is a statement
  about a conversation, the other about a directory.

  Validation happens BEFORE anything is written, so a refused bind leaves the
  store exactly as it was -- the same 'no trace on failure' the HTTP edge
  promises about the audit line it lands afterwards.

  THE ONE-ARITY FORM IS REFUSED, by name, and the refusal is the honest news
  rather than an oversight: `(bind! dir)` used to write a binding into the
  no-session slot, and a row keyed by nothing is a row this schema will not hold
  (sessions.id is NOT NULL). The signature is kept because removing it would turn
  a caller's mistake into an arity error at some unrelated call site; refusing it
  here says which argument is missing and why it matters."
  ([dir]
   (throw (ex-info (str "bind! needs a thread id to bind " dir
                        ": a binding belongs to a conversation, and the no-session"
                        " slot (nil) cannot hold one")
                   {:path (str dir) :reason :no-session-slot})))
  ([thread-id dir]
   (if (nil? dir)
     (do (db/with-transaction
           (fn [^Connection c]
             (db/execute! c "UPDATE sessions
                                SET project_id = NULL, path = NULL, last_project_path = NULL
                              WHERE id = ?"
                          thread-id)))
         nil)
     (let [f     (checked-directory dir)
           canon (canonical-path f)
           abs   (absolute dir)]
       (db/with-transaction
         (fn [^Connection c]
           (touch-session! c thread-id (upsert-project! c canon) abs canon)))
       abs))))

(defn begin-subagent!
  "THREAD-ID becomes a session of this home: the subagent SUBAGENT that PARENT
  delegated to, bound the way its parent is bound. Answers the directory it landed
  on, or nil.

  ONE WRITE, BECAUSE IT IS ONE FACT. A subagent's conversation has an identity --
  who asked for it, what it ran as, where its record goes -- and all three come
  from the delegation that is starting, so they are written together or not at all.
  The alternative (bind it like any session, then patch the two columns) would
  leave a row that answers 'an ordinary conversation with no parent' to anything
  reading it in between.

  THE BINDING IS COPIED, NOT RE-DERIVED. The subagent resolves relative paths and
  reads its fence against exactly the directory its parent is bound to, spelled the
  same way -- see harness.cap.subagents/run-one, which is where that matters and
  where the alternative (resolving an already-canonical path again) would silently
  move a session's files. A parent that is bound to nothing hands its subagent
  nothing, and the row is written anyway: the record still has a home in the tree's
  reserved workspace, and 'who delegated this' is a fact worth keeping either way.

  A PARENT THE STORE HAS NEVER HEARD OF IS ORDINARY, not an error. Session ids are
  the CLIENT's to invent, so a delegation from a conversation with no row yet
  inherits the empty binding and lands here as an unbound subagent.

  MOVING THE PARENT LATER DOES NOT MOVE THIS ROW, and that is the deliberate half
  of 'together or not at all': the pairing is about the DELEGATION's life, not the
  parent's. What a project removal does to the two is the same thing it does to any
  session -- it unbinds them both in one statement, through the schema's own
  trigger -- because that statement is about the DIRECTORY.

  NOT PUBLIC FOR THE PAIR'S SAKE: see `sessions`, which now reports the two columns
  so a reader can tell a subagent from a conversation."
  [thread-id {:keys [parent subagent]}]
  (db/with-transaction
    (fn [^Connection c]
      (let [{:keys [project-id path last-project-path]}
            (first (db/query c "SELECT project_id, path, last_project_path
                                  FROM sessions WHERE id = ?" parent))]
        (touch-session! c thread-id project-id path last-project-path)
        (db/execute! c "UPDATE sessions SET parent_id = ?, subagent = ? WHERE id = ?"
                     parent subagent thread-id)
        path))))

(defn binding-for
  "The project directory THREAD-ID's session is bound to, as an absolute path
  string -- or nil. Nil is the explicit, everyday answer for NO binding, never
  an error: most sessions are unbound, and an unbound session must behave
  exactly as it did before this namespace existed.

  The answer is the spelling THIS session was bound with, which is what the
  binding returned when it was made -- not the project's canonical form, and not
  some other session's spelling of the same directory. No join onto `projects`
  is needed for that: the session's own row carries it, and an unbound session's
  is NULL by the schema's CHECK.

  The nil THREAD-ID case is the deliberate no-session slot the rest of the
  session-scoped surface has: offline tools and replay run outside any session,
  ask with nil, and get the identity treatment. It is answered here rather than
  by the query, so no row can ever be found for it."
  [thread-id]
  (when (some? thread-id)
    (:path (first (db/select "SELECT path FROM sessions WHERE id = ?" thread-id)))))

(defn identity-for
  "The CANONICAL path of the project THREAD-ID's session belongs to -- the
  project's identity, or nil for a session with no project.

  Separate from `binding-for` because they answer different questions and one
  caller needs each: `binding-for` answers the spelling this session was bound
  with, which is what gets echoed back and run in, while THIS answers the shared
  identity, which is what a name derived from the project must use -- the
  workspace a session's log lands in must be the same for every session of one
  project however each of them spelled it."
  [thread-id]
  (when (some? thread-id)
    (:canonical-path (first (db/select "SELECT p.canonical_path AS canonical_path
                                          FROM sessions s JOIN projects p ON p.id = s.project_id
                                         WHERE s.id = ?"
                                       thread-id)))))

(defn projects
  "Every project this home knows: {:id :canonical-path :created-at}, oldest
  first. One row per DIRECTORY, so this is the deduplication made visible --
  binding one directory twice, or through two spellings of it, does not grow
  this list."
  []
  (db/select "SELECT id, canonical_path, created_at FROM projects ORDER BY created_at, id"))

(defn migrate-legacy-project-config!
  "Move a project-level harness.edn / mcp.edn ASIDE, for every project this home knows:
  `<name>.bak`, or `.bak.1`, `.bak.2` ... when one is already there.

  NOTHING IS MERGED, because there is nowhere to merge it: the project level is gone
  (.scratch/config-merge decision 2), so a project's file is history rather than
  configuration -- moved so it stops looking like something the harness reads, and left
  whole so a person can lift what they want into this home's config.edn by hand. Returns
  the paths it moved, for the boot banner.

  A HOUSEKEEPING PASS, NOT A READ, and its reach is the STORE's: no session is bound at
  boot, so the only project directories this can find are the ones this home has opened
  before. A project nobody has opened since the move keeps its file until somebody binds
  it -- and nothing reads it then either, which is the property that matters. Nothing here
  throws: this runs at boot."
  []
  (let [moved (atom [])]
    (doseq [{:keys [canonical-path]} (projects)
            name ["harness.edn" "mcp.edn"]]
      (let [f (io/file canonical-path ".harness" name)]
        (when (.exists f)
          (try
            (let [base (str (.getAbsolutePath f) ".bak")
                  bak  (loop [i 0]
                         (let [c (if (zero? i) (io/file base) (io/file (str base "." i)))]
                           (if (.exists c) (recur (inc i)) c)))]
              (when (.renameTo f bak)
                (swap! moved conj (str canonical-path "/.harness/" (.getName bak)))))
            (catch Throwable _ nil)))))
    @moved))

(defn listed-dir
  "DIR canonicalized, when this home lists it as a project -- nil for anything else.

  THE GATE ON A ROUTE THAT MAY BE HANDED A PATH. `/api/git` can be asked about a session,
  and a session's directory is whatever it was bound to; the OTHER form asks about a
  DIRECTORY, because a session this page has minted and not sent to has no row to look up
  (`.scratch/composer-new-session-bar/`). Left open, that form would answer 'is this path a
  repository, and what branch is it on' for any path on the machine. The projects list is
  exactly the set of directories the page already holds (`GET /api/projects` hands it over
  on every listing), so this gate widens nothing: it is the menu the composer's picker
  draws, and nothing else.

  CANONICAL ON THE WAY IN, because the two spellings of one directory are one project here
  -- the same deduplication `add-project!` does."
  [dir]
  (when-not (str/blank? (str dir))
    (let [canonical (try (.getCanonicalPath (io/file (str dir)))
                         (catch Exception _ nil))]
      (when (some #(= canonical (:canonical-path %)) (projects))
        canonical))))

(defn- as-session
  "One `sessions` row as this namespace hands it out: {:id :project-id :path
  :archived? :created-at :parent-id :subagent}.

  `:archived?` is a BOOLEAN, converted here rather than left as the column's 0/1 --
  Clojure's `boolean` says 0 is true and a flag that means the opposite of what it
  looks like is the kind of bug that survives review. ONE conversion, in one place,
  for every reader: two readers each turning 0/1 into a boolean are two chances for
  one of them to say it backwards."
  [row]
  (-> row
      (assoc :archived? (pos? (long (:archived row))))
      (dissoc :archived)))

(def ^:private session-columns
  "The columns a session reader asks for, so two queries over one table cannot drift
  into handing out two shapes. `run_state` is the LAST KNOWN run state (NULL reads
  as idle -- see the migration `sessions-remember-their-run-state` in
  harness.infra.db), and it is the column the sidebar's `running` reads."
  "id, project_id, path, archived, created_at, title, last_sent_at, parent_id, subagent, run_state")
(defn sessions
  "Every session this home knows: {:id :project-id :path :archived? :created-at
  :parent-id :subagent}, oldest first.

  `:subagent` AND `:parent-id` RIDE ALONG RATHER THAN BEING FILTERED OUT HERE.
  'Every session this home knows' is what this function says, and a subagent's
  conversation IS one -- it has a row, a project and a log for exactly that reason
  (see begin-subagent!). Who does not want to see it says so at the read: the
  sidebar's listing is conversations a person can open, and a filter here would take
  the row away from every other reader too -- including the one that exists to SHOW
  them.

  This is the WHOLE table, so it includes the sessions that belong to no project;
  which of those is a TASK is `tasks`' question, not this one's. Reading AND setting
  the archive flag live here: both are statements about the same column, and a reader
  in another namespace would have to re-state the 0/1 conversion to write it. See
  `archive!`."
  []
  (mapv as-session
        (db/select (str "SELECT " session-columns " FROM sessions ORDER BY created_at, id"))))

(defn session-exists?
  "Does this home know THREAD-ID as a session?

  THE QUESTION THE RUN EDGE ASKS BEFORE IT WRITES ANYTHING (ticket 03 of
  `.scratch/sessions-live-on-the-server`), and it is a different question from 'is
  there a log': a log can be sitting in the tree that this home never agreed to keep,
  and the record that matters is the ROW. `sessions` and `tasks` answer 'which ones'
  -- this answers 'this one', which is what a request carries.

  READ FROM THE STORE, not from the sessions table in memory: the row is the durable
  statement that a conversation exists here, and a process that has just started holds
  no conversations at all."
  [thread-id]
  (when (some? thread-id)
    (boolean (seq (db/select "SELECT 1 FROM sessions WHERE id = ?" (str thread-id))))))

(defn tasks
  "Every session this home knows that belongs to NO project and remembers none:
  {:id :project-id :path :archived? :created-at} with `:project-id` and `:path` null,
  oldest first.

  TWO COLUMNS, NOT ONE, AND THE SECOND IS THE POINT. A session can be unbound for two
  different reasons and they are not the same fact: `remove-project!` lets a directory
  go and its sessions keep `last_project_path` -- the MEMORY of where they were, which
  is what re-adding that directory matches on -- so such a session is waiting for its
  project to come back and is not a task. A task is a conversation that never had a
  home, or one whose home was released on purpose (`bind! id nil`, which clears the
  memory too). Only the second kind is listed here, and this WHERE clause is the whole
  of that distinction.

  Empty is the ordinary answer: a home whose every conversation has a project."
  []
  (mapv as-session
        (db/select (str "SELECT " session-columns " FROM sessions"
                        " WHERE project_id IS NULL AND last_project_path IS NULL"
                        " ORDER BY created_at, id"))))

;; ------------------------------------------------------------- the run state
;;
;; THE COLUMN'S TWO WRITERS AND ONE READER live beside the entity, like every
;; other query over `sessions`. The writers are called from the run edge's
;; register/unregister pair, so the store's word moves with the registry's -- see
;; harness.edge.sessions, whose adapter wires the two together. The reader is the
;; listing's.

(defn- run-state
  "A row's run_state column as a WORD: `running` or `idle`, with NULL (a session
  that has never run, or any row the column predates) read as `idle` rather than
  as a third thing. ONE conversion, in one place, for every reader -- the same
  discipline `as-session`'s archived 0/1 conversion argues for."
  [row]
  (or (:run-state row) "idle"))

(defn run-states
  "THREAD-ID -> run_state, for each id the store knows -- ids it has never heard
  of are absent. THE LISTING'S BULK READ: one SELECT answers the column for every
  row, so the sidebar draws it from the same snapshot as everything else.
  Per-session asks go through `set-run-state!`'s callers instead; this is the one
  read the listing makes."
  []
  (reduce (fn [m row]
            (assoc m (str (:id row)) (run-state row)))
          {}
          (db/select "SELECT id, run_state FROM sessions")))

(defn set-run-state!
  "Record that THREAD-ID's LAST KNOWN run state is now STATE (`running` or `idle`),
  and answer nothing -- a state write is not a question. A session this home has
  never heard of is left alone rather than created: the row is made by the session
  registration (`register-session!` / `bind!`), and a run-state write never
  certifies that a conversation exists (that is the same line `archive!` refuses
  to cross in the other direction -- it refuses, rather than creating, because an
  archive is a command; a state note is an observation, so it is simply dropped)."
  [thread-id state]
  (db/with-transaction
    (fn [^Connection c]
      (db/execute! c "UPDATE sessions SET run_state = ? WHERE id = ?"
                   (str state) (str thread-id))))
  nil)

(defn clear-startup-run-state!
  "Set every `running` row back to `idle` -- THE STARTUP CLEANUP, called once when
  a process begins serving, before it can start any run of its own.

  WHY THIS EXISTS AND WHERE IT DRAWS THE LINE: `run_state` is last known, and the
  last process to write it may have died mid-run -- a kill -9 never reaches
  `run-finished!`, so the column can carry a `running` nothing will ever take
  back. This process is the authority on ITS runs, and it starts with none: every
  `running` it finds was left by someone else (a dead process, or one still
  serving another home's copy of this store -- which the store open refuses
  anyway). Clearing here is therefore not a guess, it is the same honesty the
  migration states: the column says what the store was last TOLD, and at startup
  the truth is that nothing is running in this process yet. A process that is
  STILL ALIVE and serving this same store is refused at the open by the claims
  table's own machinery (harness.cap.claims) -- so no live writer's word is
  wiped."
  []
  (db/with-transaction
    (fn [^Connection c]
      (db/execute! c "UPDATE sessions SET run_state = 'idle' WHERE run_state = 'running'"))))

;; ---------------------------------------------------- the numbers the strip draws
;;
;; THE COLUMN'S WRITER AND ITS READER, beside the entity like every other query over
;; `sessions`. The numbers themselves are the EDGE's to fold (`harness.edge.stats` /
;; `harness.edge.context`); this namespace only keeps the last snapshot of them, which
;; is why the payload crosses this boundary as an opaque map. See
;; `harness.infra.db/sessions-remember-their-numbers` for why storing a fold is a
;; decision and what it costs.

(defn remember-numbers!
  "Record the numbers THREAD-ID's strip draws, as the LAST KNOWN snapshot: PAYLOAD is
  stored whole as one JSON value (so it may grow keys without a migration), and it is
  the caller's job to have stamped WHEN it was taken (`:numbersAt`) -- this namespace
  does not read the clock for it, because the edges that fold the numbers already do.

  A session this home has never heard of is left alone rather than created, the same
  rule `set-run-state!` follows: a note about a conversation never certifies that the
  conversation exists."
  [thread-id payload]
  (db/with-transaction
    (fn [^Connection c]
      (db/execute! c "UPDATE sessions SET numbers = ? WHERE id = ?"
                   (json/write-str payload) (str thread-id))))
  nil)

(defn numbers-for
  "The stored numbers for THREAD-ID, parsed, or NIL when there are none -- either the
  column was never written for it, or this home has never heard of it. Nil is the
  ordinary answer for a session nobody has watched, and the reader falls back to
  folding the record (`harness.edge.http/stats-get`).

  IT IS READ BACK WITH KEYWORD KEYS, which is the shape the fold's payload has: a
  reader must not be able to tell a stored answer from a folded one except by the
  `:numbersAt` the stored one carries."
  [thread-id]
  (when (some? thread-id)
    (when-some [stored (:numbers (first (db/select "SELECT numbers FROM sessions WHERE id = ?"
                                        (str thread-id))))]
      (json/read-str stored :key-fn keyword))))

(defn remember-send!
  "THE PERSON PRESSED SEND in THREAD-ID's conversation: stamp the time, and let
  that same arrival name the session if it has no name yet. Answers whether a row
  was written.

  ONE STATEMENT, TWO COLUMNS, BECAUSE THEY ARE ONE EVENT. `last_sent_at` is what
  the sidebar sorts and labels rows by, and it moves on EVERY send; `title` is
  written ONCE, from the first send's own text. Writing them together is not a
  convenience -- it is the reason a second statement cannot drift from the first
  (a code path that stamped the time and forgot the name, or the other way round,
  would be a row whose two facts came from different turns).

  THE NAME'S ONE-WAY RULE IS `COALESCE`: the second, third and fortieth send all
  take this same statement, and `COALESCE(title, ?)` leaves an existing name EXACTLY
  as it is. A BLANK OR ABSENT TEXT therefore writes nothing to the name -- a run
  whose messages carry no user turn (`harness.edge.ag-ui/first-user-text` answers nil
  for one) still stamps the time, because a run did happen.

  A SESSION THAT RAN BEFORE EITHER COLUMN EXISTED heals itself the next time it
  runs: SAID is the first user turn of the conversation the session holds -- the
  session is the authority (ADR 0002 decision 1) and the action no longer carries
  the conversation (`harness.edge.http/run-agent!` hands over the session's own
  messages first, then this action's entries) -- so it is still the session's FIRST
  send, whatever the newest one says. The name is not backfilled (the owner's call
  -- an old conversation keeps its id until somebody talks to it again) and the TIME
  is (`sessions-remember-their-last-send` walks the logs once, because a row with no
  time at all is a row the sidebar cannot draw)."
  [thread-id said]
  (let [named (when-not (str/blank? (str said)) said)]
    (pos?
     (db/with-transaction
       (fn [^Connection c]
         (db/execute! c (str "UPDATE sessions"
                             "   SET last_sent_at = ?, title = COALESCE(title, ?)"
                             " WHERE id = ?")
                      (System/currentTimeMillis) named thread-id))))))

(defn title
  "THREAD-ID's stored title, or nil -- either the column was never written or this home has
  never heard of the session."
  [thread-id]
  (when (some? thread-id)
    (:title (first (db/select "SELECT title FROM sessions WHERE id = ?" (str thread-id))))))

(defn set-title!
  "Write THREAD-ID's title, replacing whatever was there, and answer whether a row moved.

  THE ONE DOOR A NAME CAN BE SET THROUGH, beside `remember-send!`'s COALESCE: a forked
  session is named `[fork] …` the moment it is made, and its first send may never come.
  A session this home has never heard of is left alone, the same rule `set-run-state!` and
  `remember-numbers!` follow -- a note about a conversation never certifies that it exists."
  [thread-id value]
  (pos? (db/with-transaction
          (fn [^Connection c]
            (db/execute! c "UPDATE sessions SET title = ? WHERE id = ?" value (str thread-id))))))

(defn register-session!
  "Make THREAD-ID a session of this home, belonging to no project: a row with no
  project, no path and no remembered project. Answers the id.

  FIND-OR-CREATE, and both callers can be racing themselves: the sidebar's 'new task'
  registers an id it has just minted, and the AG-UI edge registers an id it has never
  heard of as the first thing a run does -- while being called again for every later
  turn of that same conversation. So an id this home already knows is left EXACTLY as
  it is: `DO NOTHING`, not `DO UPDATE`, because a session that belongs to a project
  must not be unbound by somebody asking it to exist, and a session that remembers a
  project must not have that memory cleared. Registering is a statement that a
  conversation EXISTS, not a statement about where it lives.

  THIS IS THE ONLY WAY A TASK IS BORN, and it is deliberately not `bind!`'s nil
  direction: releasing a session and starting to keep one are two different
  statements, and `bind!`'s docstring says why the first never creates a row."
  [thread-id]
  (db/with-transaction
    (fn [^Connection c]
      (db/execute! c "INSERT INTO sessions (id, project_id, path, last_project_path, created_at)
                      VALUES (?, NULL, NULL, NULL, ?)
                      ON CONFLICT(id) DO NOTHING"
                   thread-id (System/currentTimeMillis))))
  thread-id)

(defn archive!
  "Mark THREAD-ID's session archived (true) or not (false), and answer the flag
  coming back out of the store -- which is the value now on disk, not the one
  that was asked for.

  ONE VERB FOR BOTH DIRECTIONS, because they are one state change: archive and
  unarchive differ only in the boolean, and two functions would be two chances
  for the two directions to drift. It is IDEMPOTENT for the same reason --
  archiving an already-archived session answers true and changes nothing, so a
  double click or a retried request is not an error anybody has to handle.

  NOTHING HERE TOUCHES THE LOG, and this is the one state change in this
  namespace where a caller could plausibly get that wrong: every other verb
  leaves marks on purpose (a bind lands an audit line through the HTTP edge), and
  the habit of writing one would be exactly backwards here. The archive ticket's
  acceptance pins the jsonl's byte count AND mtime before and after, so an audit
  line would fail the very assertion that proves archiving is not a deletion. The
  flag is in the store because that is what the store is for.

  A SESSION THIS HOME HAS NEVER HEARD OF IS REFUSED BY NAME, rather than answered
  cheerfully: '0 rows updated' plus a true-looking flag would be a lie about a
  conversation that does not exist here, and the sidebar needs a reason it can
  show on the row the click landed on. An UNBOUND session (a row with no project)
  is accepted -- the flag is a property of the conversation, and one that is
  bound later keeps it.

  No audit line, and no timestamp either: an archive is a rewrite of a row, and
  `sessions` carries no `archived_at` because nothing in this feature reads one
  back."
  [thread-id archived?]
  (let [updated (db/with-transaction
                  (fn [^Connection c]
                    (db/execute! c "UPDATE sessions SET archived = ? WHERE id = ?"
                                 (if archived? 1 0) thread-id)))]
    (when (zero? (long updated))
      (throw (ex-info (str "no session " thread-id " in this home, so there is nothing to "
                           (if archived? "archive" "unarchive")
                           " -- the sidebar only draws rows the store knows about, so this id"
                           " was either never created here or belongs to another home")
                      {:thread-id thread-id :reason :no-such-session})))
    (boolean archived?)))

(defn delete-session!
  "Take THREAD-ID's ROW out of the store, and answer the id.

  THE ROW ONLY, and that boundary is the point rather than an omission: what a conversation leaves in
  the other tables (its task list, its claim, its anchors, the content copy) and the record file
  itself each have an owner, and `harness.edge.forget` is the one place that knows the whole list and
  calls them in an order that can be retried. A second implementation of that list here would be free
  to disagree with it.

  A SESSION THIS HOME HAS NEVER HEARD OF IS REFUSED BY NAME, like `archive!` and for the same
  reason: 'I deleted it' about a conversation that was never here is a lie the caller cannot
  detect."
  [thread-id]
  (let [id      (str thread-id)
        removed (db/with-transaction
                  (fn [^Connection c] (db/execute! c "DELETE FROM sessions WHERE id = ?" id)))]
    (when (zero? (long removed))
      (throw (ex-info (str "no session " id " in this home, so there is nothing to delete")
                      {:thread-id id :reason :no-such-session})))
    id))

(defn remove-project!
  "Take directory CANONICAL out of this home's project list. Answers
  {:path .. :unbound <n>} -- how many sessions stopped being bound.

  THIS IS A REMOVAL, NOT A DELETION, and the whole verb is the difference. What
  goes is the `projects` ROW: the directory is no longer one this home's sidebar
  draws, which is what 'removed' has to mean to be worth doing. What stays is
  everything that is not the row -- the conversation logs under
  `projects/<workspace>/` are never opened, let alone moved or deleted, and each
  session keeps the memory of where it was (`last_project_path`).

  UNBINDING IS DONE BY THE SCHEMA, not here: the BEFORE DELETE trigger from
  version 1 clears `project_id` and `path` on this project's sessions in the same
  statement, so no session can be left half-bound and the CHECK is never asked to
  judge a row mid-change (see harness.infra.db's migration docstring). Doing it here as
  well would be a second implementation of the same invariant, free to disagree.

  THE SESSIONS THEREFORE BECOME ORDINARY UNBOUND SESSIONS: `binding-for` answers
  nil, `out-of-bounds?` answers false, and their paths resolve unchanged --
  byte-for-byte the behaviour of a session that never had a project. That is the
  honest state and it is why 'removed' needs no special case anywhere else: every
  reader already knows what an unbound session is. The one thing they keep is the
  memory, which is what `add-project!` adopts when the directory comes back.

  A DIRECTORY THIS HOME DOES NOT KNOW IS REFUSED BY NAME, for the reason
  `archive!` refuses an unknown session: the sidebar needs a sentence it can show
  on the row the click landed on, and 'removed' about something that was never
  there tells the caller their click worked when the list they were looking at
  was stale.

  NO AUDIT LINE and no timestamp: nothing here happens to a log. There is no
  per-session line to write -- the workspace is a function of the project, and the
  project is going away -- and the log directory is not this verb's to touch."
  [canonical]
  (db/with-transaction
    (fn [^Connection c]
      (let [row (first (db/query c "SELECT id FROM projects WHERE canonical_path = ?" canonical))]
        (when (nil? row)
          (throw (ex-info (str "no project at " canonical " in this home, so there is nothing to remove")
                          {:path canonical :reason :no-such-project})))
        (let [id      (:id row)
              ;; Counted BEFORE the delete, because the trigger is about to clear
              ;; the column this counts on -- and the count is the answer the
              ;; caller reports, so reading it afterwards would always be zero.
              unbound (:n (first (db/query c "SELECT COUNT(*) AS n FROM sessions WHERE project_id = ?" id)))]
          (db/execute! c "DELETE FROM projects WHERE id = ?" id)
          {:path    canonical
           :unbound (long unbound)})))))

(defn cwd-changed
  "The CwdChanged hook-event FACTS for a binding change: BEFORE (the previous
  directory, nil for a first bind) -> AFTER (the directory now bound). This is
  the event source the hook engine wires at the binding-change point -- the
  payload shape is locked here, with a test, so it cannot drift. Field names
  follow the hook-payload convention (snake_case, aligned with the CodeBuddy
  list). What to DO with the event -- spawning commands, timeouts, gating -- is
  the hook engine's business, not this namespace's: here it is only the fact
  that the working directory moved."
  [thread-id before after]
  {:hook        "CwdChanged"
   :thread_id   thread-id
   :project_dir after
   :before      before})

(defn resolve-path
  "PATH as this session will use it: relative paths resolve against the
  session's project directory when one is bound, and pass through UNCHANGED
  when none is; absolute paths always pass through. Absolute means absolute
  per java.io.File (a drive letter on Windows, a leading / on Unix).

  The unbound case is the identity function ON PURPOSE: it is the regression
  guarantee that a session without a binding sees byte-for-byte the behavior
  the tools had before project directories existed."
  ([path] (resolve-path nil path))
  ([thread-id path]
   (let [f (io/file path)]
     (if (.isAbsolute f)
       path
       (if-let [dir (binding-for thread-id)]
         (str (io/file dir path))
         path)))))

(defn- under?
  "Canonical containment: PATH's canonical form is DIR's canonical form or
  starts with it followed by a separator -- so C:\\proj contains C:\\proj\\a.txt
  but never C:\\project2, however alike the strings look. Both sides are
  canonicalized, so .. segments, case differences and symlinks collapse to the
  same answer whatever form the path arrived in. A path that cannot be
  canonicalized at all (a broken link, an invalid name) is provably inside
  NOTHING and answers false -- the caller decides what an unprovable path
  costs, and for the fence that cost is an approval."
  [path dir]
  (try
    (let [cp  (.getCanonicalPath (io/file path))
          cd  (.getCanonicalPath (io/file dir))
          sep (File/separator)]
      (boolean (or (= cp cd)
                   (str/starts-with? cp (if (.endsWith cd sep) cd (str cd sep))))))
    (catch Exception _ false)))

(defn harness-config
  "The session configuration in force for THREAD-ID: the :session section of this home's
  config.edn -- how a session runs, the seven keys harness.edn used to hold, with their
  names and their shapes unchanged.

  ONE LEVEL, AND THAT IS THE WHOLE READ. There used to be two: the configuration home's
  harness.edn, overlaid by the bound project's .harness/harness.edn. THE PROJECT LEVEL IS
  GONE (.scratch/config-merge/spec.md decision 2) -- it is a shape we may bring back one
  day, and the shape to bring back is 'the same section in two files, shallow, project
  wins', not a second file name. So this answer no longer depends on the binding at all,
  and .harness/harness.edn is a file nothing reads.

  THREAD-ID IS STILL THE ARGUMENT because every caller has a session, and because the
  answer becomes per-session again the day a project level comes back: carrying it now
  keeps the seam the shape of the thing that will sit behind it.

  THE READ IS FRESH (harness.cap.providers re-reads the file on every call, the config.edn
  discipline), so editing config.edn moves the fence with no restart. A missing section is
  the empty map; a section that EXISTS and is broken is a NAMED failure out of
  providers/check-config -- and that distinction is the one the fence depends on."
  ([] (harness-config nil))
  ([_thread-id]
   (providers/session-config)))

(defn harness-config-path
  "The absolute path of the file harness-config reads: what a failure sentence names when
  it has to be actionable, since 'the block is the wrong shape' only helps once a reader
  knows which file to open. It is config.edn, and it is the SAME file for every session
  now that there is no project level."
  []
  (.getAbsolutePath (home/config-file)))

;; ------------------------------------------- what a session opens with
;;
;; THE COMPOSITION LIVES HERE, and this is the only namespace that can host it.
;; Two questions -- "which directories hold this session's skills" and "which
;; instruction files does it read" -- are each answered by a PURE function of
;; (configured value, project directory), because the namespaces answering them
;; must not require this one (see harness.cap.skills, whose roots the fence needs).
;; Somebody has to pair the config with the binding, and that somebody needs to
;; see config.edn, the binding, and both readers: only this namespace can.
;;
;; It is also the right home by subject matter -- config.edn's :session is where these
;; come from, and this is the namespace that reads it.

(defn skill-layers
  "The skill directories THREAD-ID's session reads, each with its layer -- the
  session-facing answer for anyone that has to SAY where a skill came from (the
  skill list; see harness.cap.skills/skill-list).

  It is the pairing (configured value, binding) described above, and the one
  `skill-roots` is derived from, so there is no second place where a root's layer
  could be decided.

  Read fresh on every call, like harness-config itself: editing harness.edn or
  rebinding the project moves the roots on the next call, with no restart."
  [thread-id]
  (skills/root-layers (:skills (harness-config thread-id)) (binding-for thread-id)))

(defn skill-roots
  "The skill directories THREAD-ID's session reads, in precedence order. The
  session-facing answer: it does the pairing described above, so a model (or a
  human, in the REPL) asks one question instead of three.

  The paths alone -- what the fence and the `skill` tool body want. Read fresh on
  every call, like harness-config itself: editing harness.edn or rebinding the
  project moves the roots on the next call, with no restart."
  [thread-id]
  (mapv :path (skill-layers thread-id)))

(defn preamble-files
  "The instruction files THREAD-ID's session reads, in the order they are
  presented. The same pairing as skill-roots, for the other half."
  [thread-id]
  (preamble/instruction-files (:instructions (harness-config thread-id))
                              (binding-for thread-id)))

(defn fence
  "The fence in force for THREAD-ID, as data: {:dir .., :strict? bool,
  :free [[path why] ..]} -- or nil when the session is unbound and there is no fence
  at all. One read answers 'is there a fence', 'which directory is it around' and
  'what is it made of', so a caller cannot see a bound session and its fence as two
  different facts.

  ONE LIST, TWO READERS, and that is the point of it being here rather than
  written twice. `out-of-bounds?` tests against it, and the <project> block the
  model reads states it -- and a block that stated a rule the gate does not
  enforce would be a system message that lies, which is the one thing a block
  whose job is to state the rules must not do. 'why' is the sentence the block
  prints beside a free path, so a path joining this set cannot arrive without
  someone saying why it is free.

  :free, in the order the block states it:

    - the project directory itself -- UNLESS the project's harness.edn set
      :approval {:strict true}, which tightens the fence until every project
      path needs an approval too (hence :strict?, which says so out loud
      rather than leaving the block to infer it from a missing row);
    - the configuration home (config.edn, .env -- reading
      one's own configuration is the fence's explicit allowance, and strict
      does not tighten it away: the config home is harness's own ground, not
      the project's);
    - the machine's TEMPORARY DIRECTORIES (harness.infra.env/temp-dirs --
      java.io.tmpdir and, where it exists, POSIX /tmp), for the same reason the
      configuration home is here and with the same status: scratch is not the
      project's ground, and strict does not tighten it away either;
    - :approval {:allow [..]} -- extra paths the project declares free of
      the fence, each resolved for the session like any tool path (relative
      to the project root, absolute passes through);
    - the session's SKILL ROOTS (skill-roots, above), for the same reason the
      configuration home is here and with the same status: they are not project
      files, they are what the host and the human installed for their agents. A
      skill's body routinely says 'read references/x.md', and a path like that
      resolves NEXT TO THE SKILL -- so without this every reference file would
      park a human, which would make loading a skill useless.

  Note what is NOT here and must not be: the CONTENT of an instruction file. An
  AGENTS.md that says 'read ~/notes/x.md' does not make ~/notes/x.md allowed. The
  roots are places the harness was configured to look, not capabilities a document
  can grant itself.

  The config is read fresh per call, so harness.edn edits move the fence on the
  next call."
  [thread-id]
  (when-let [dir (binding-for thread-id)]
    (let [{:keys [approval]}   (harness-config thread-id)
          {:keys [allow strict]} approval]
      {:dir     dir
       :strict? (boolean strict)
       :free    (concat
                 (when-not strict
                   [[dir "this project"]])
                 [[(home/root)
                   (str "this harness's configuration home; reading your own"
                        " configuration there is allowed")]]
                 (for [t (env/temp-dirs)]
                   [t (str "the machine's temporary directory; scratch that is"
                           " meant to be thrown away")])
                 (for [r (skill-roots thread-id)]
                   [r "where this session's skills live"])
                 (for [p (or allow [])]
                   [(resolve-path thread-id p)
                    "declared free by the project's :approval {:allow ..}"]))})))

;; ---------------------------------------------- this home's sensitive paths
;;
;; THE FENCE'S SECOND HALF, and the one part of it that is NOT about a project or a
;; binding. The free paths above say where a bound session may work; this says which
;; paths park a human ANYWAY, including paths inside the free set. The two are asked
;; together at every fenced tool call (harness.cap.tools/fence) and they are stated
;; together in the <project> block, which is why both live here: a rule the gate
;; enforces and a rule the model is told have to be one rule, or the block lies.
;;
;; IT DOES NOT DEPEND ON A BINDING, deliberately. The fence engages only when a project
;; is bound -- that is what keeps an unbound session on its pre-binding behavior -- but a
;; credential list that a missing binding switches off is not a guard. These paths are
;; THIS HOME's, so every session honours them; what is left of the binding here is only
;; how a RELATIVE entry resolves, which is how any other configured path resolves.

(defn sensitive-paths
  "The paths this HOME declares sensitive, resolved for THREAD-ID's session: what
  config.edn's :security :sensitive-paths names, or the built-in list when it names
  none, with a leading `~` already expanded to the operating system's home.
  harness.cap.providers owns that file and that expansion (and its built-in list); this
  fn adds the one thing that needs a session: a RELATIVE entry resolves against the
  project directory like any tool path, and passes through unchanged when nothing is
  bound.

  The list is read FRESH on every call, like the fence and like every other configured
  value, so editing config.edn moves it on the next call rather than the next restart."
  [thread-id]
  (mapv (fn [p] (resolve-path thread-id p)) (providers/sensitive-paths)))

(defn sensitive-path?
  "TRUE when PATH, as THREAD-ID's session resolves it, IS one of this home's sensitive
  paths, SITS INSIDE one, or CONTAINS one.

  THE THIRD CASE IS NOT SYMMETRY FOR ITS OWN SAKE. `read` names a FILE, but glob and grep
  name a DIRECTORY and then walk everything under it: a grep over a directory that holds
  ~/.ssh reads the private key's bytes while the path it was handed names nothing
  sensitive at all. 'These two paths overlap, either way round' is therefore the honest
  question, and it is the one answered here.

  THE COST IS STATED WHERE IT IS PAID: a session bound at the operator's home directory
  parks on a directory-wide operation over it (`grep` the home, `glob` the home), which
  is exactly the case this list exists for. Nothing else moves -- no other configuration
  makes a path sensitive, and no project can declare one free.

  WHETHER TO PARK on a true answer is the tool seam's decision (`fence` in
  harness.cap.tools), the same division out-of-bounds? keeps."
  [thread-id path]
  (boolean
   (when-let [paths (seq (sensitive-paths thread-id))]
     (let [resolved (resolve-path thread-id path)]
       (some (fn [p] (or (under? resolved p) (under? p resolved))) paths)))))

(defn out-of-bounds?
  "TRUE when PATH, as THREAD-ID's session resolves it, lands outside every
  directory a bound session may touch. Which directories those are -- and why
  each one is free -- is `fence`, and this fn is only the containment question
  asked of that list. Keeping the two apart is what stops the rule and its
  statement from being two rules.

  A session with no binding has no fence, so this answers false for every path,
  byte-for-byte the pre-binding behavior -- WHICH IS THIS FN'S ALONE TO PROMISE: the
  sensitive list beside it (sensitive-path?) belongs to this HOME rather than to a project
  and answers for unbound sessions too. This fn is the WHERE question as a boolean --
  whether to PARK an out-of-bounds call is the tool seam's decision, made through the
  ordinary approval flow."
  [thread-id path]
  (boolean
   (when-let [{:keys [free]} (fence thread-id)]
     (let [resolved (resolve-path thread-id path)]
       (not (some (fn [[free-path _]] (under? resolved free-path)) free))))))


;; ---------------------------------------------------------------- the pre-LLM step

(defn before-llm
  "HISTORY with the skill bodies a person's `/name` asked for folded back in, for
  THREAD-ID -- the loop's pre-LLM step, as a function the kernel is handed rather than
  one it requires (see harness.kernel.loop). A skill the MODEL loaded does not come
  through here at all: `skill` answers with the body, so it is an ordinary tool result.

  IT IS ASSEMBLED HERE because this is where the two halves already meet: the roots
  are this namespace's business (skill-roots), the folding is
  harness.cap.skills'. Neither can own the pair -- harness.cap.skills cannot require
  this namespace, because this one requires IT -- and a second copy at the edge
  would be a copy that drifts, which is the failure the ticket named: a rebuilt
  conversation that carries no skill bodies is a different conversation.

  The roots are resolved PER CALL, not once per run, so editing harness.edn
  mid-run moves them -- matching every other configuration read in this codebase.

  AND THE SESSION'S OTHER INJECTION IS HERE FOR THE SAME REASON: the endings of
  background jobs nobody waited for (`harness.cap.jobs/before-llm`) ride the same step.
  It is a different KIND of thing -- the skills half is derived from the conversation
  and is idempotent for free, while a notice is remembered in the jobs registry because
  the client never holds one -- but the two meet here for exactly the reason the skills
  half is here at all: this is the one place a session's history gets decorated, and a
  second place would be the copy that drifts. Every caller that hands the kernel a
  pre-LLM step (both run paths and the author-side replay) therefore gets both halves
  without knowing either exists."
  [history thread-id]
  (-> history
      (skills/derived-injections (skill-roots thread-id))
      (jobs/before-llm thread-id)))
