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
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.cap.preamble :as preamble]
            [harness.cap.skills :as skills])
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

(defn sessions
  "Every session this home knows: {:id :project-id :path :archived? :created-at},
  oldest first. `:archived?` is a BOOLEAN, converted here rather than left as
  the column's 0/1 -- Clojure's `boolean` says 0 is true, and a flag that means
  the opposite of what it looks like is the kind of bug that survives review.

  Reading AND setting the flag live here: both are statements about the same
  column, and a reader in another namespace would have to re-state the 0/1
  conversion to write it. See `archive!`."
  []
  (mapv (fn [row] (-> row
                      (assoc :archived? (pos? (long (:archived row))))
                      (dissoc :archived)))
        (db/select "SELECT id, project_id, path, archived, created_at FROM sessions ORDER BY created_at, id")))

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

(defn- read-harness-edn
  "One harness.edn FILE, or {} when it does not exist -- a missing file is the
  empty configuration, never an error: most projects have no .harness/ at all.
  A file that EXISTS but is broken -- not valid EDN, or not a map -- is a hard,
  NAMED failure carrying the absolute path. A silently ignored config would be
  indistinguishable from a config that says nothing, and the fence's whole
  meaning depends on that distinction never blurring."
  [file]
  (let [f (io/file file)]
    (if-not (.exists f)
      {}
      (let [abs (.getAbsolutePath f)
            v   (try
                  (edn/read-string (slurp f :encoding "UTF-8"))
                  (catch Exception e
                    (throw (ex-info (str "harness.edn is not valid EDN: " abs
                                         " (" (ex-message e) ")")
                                    {:path abs :reason :invalid-edn}))))]
        (when-not (map? v)
          (throw (ex-info (str "harness.edn must be an EDN map: " abs)
                          {:path abs :reason :not-a-map})))
        v))))

(defn harness-edn-levels
  "The two harness.edn maps THREAD-ID's session is configured by, UNMERGED, with
  the file each came from:

    {:user    {...}                       ; the config home
     :project {...}                       ; the bound project, {} when unbound
     :files   {:user <abs> :project <abs>|nil}}

  harness-config is this pair shallow-merged, and answers the question most
  consumers are asking. This fn exists for the ones whose overlay is FINER than a
  top-level key -- the editing mode is the first, composing its block key by key
  so a project can turn one knob off without restating the block (see
  harness.cap.editing). It is handed out rather than re-read by that consumer so the
  reading discipline above stays ONE rule with ONE implementation: 'absent is the
  empty configuration, broken is a named failure' is exactly the distinction the
  fence depends on, and a second copy of it would be a second chance to blur it.

  The paths ride along because a broken block has to be actionable, and ':editing
  is the wrong shape' only becomes actionable once the reader knows which of the
  two files to open."
  [thread-id]
  (let [dir (binding-for thread-id)]
    {:user    (read-harness-edn (io/file (home/root) "harness.edn"))
     :project (if dir (read-harness-edn (io/file dir ".harness" "harness.edn")) {})
     :files   {:user    (.getAbsolutePath (io/file (home/root) "harness.edn"))
               :project (when dir
                          (.getAbsolutePath (io/file dir ".harness" "harness.edn")))}}))

(defn harness-config
  "The .harness/harness.edn configuration for THREAD-ID, merged from two
  levels: the configuration home's harness.edn (the USER level), overlaid by
  the bound project's .harness/harness.edn (the PROJECT level).

  The merge is a SHALLOW merge of top-level keys, project wins -- a project
  :approval replaces the user's whole :approval, it does not merge into it.
  That is deliberate, and now written down: a deep merge would make 'what
  will the fence actually do' a function of how two files nest, when the
  point of a project-level override is to be legible in one file.

  Each level is read fresh on every call (the config.edn discipline), so
  editing harness.edn moves the fence without a restart. A missing level is
  the empty map. The first consumer is the approval boundary:
  :approval {:allow [..]} adds paths to the fence's allowed set,
  :approval {:strict true} removes the project directory from it. The
  skills/mcp/hooks subdirectories of .harness/ are RESERVED for their own
  consumers -- this reader only ever opens harness.edn.

  NOTE THE MIXED SOURCE, which is the home's boundary rather than an accident
  (.scratch/project-sidebar/spec.md decision 2): the BINDING comes from the
  store, the CONFIGURATION from files. State is rewritten, config is edited by
  hand -- so editing harness.edn still needs no restart, and no part of this
  call writes to the store.

  The shallow merge is THIS key's shape and not a house rule: a key whose
  consumers want a finer overlay composes the pair itself, from
  harness-edn-levels above. Whichever way a key is composed, this fn is
  unchanged -- a caller that wants the fence's answer still gets it the same
  way it always did."
  ([] (harness-config nil))
  ([thread-id]
   (let [{:keys [user project]} (harness-edn-levels thread-id)]
     (merge user project))))

;; ------------------------------------------- what a session opens with
;;
;; THE COMPOSITION LIVES HERE, and this is the only namespace that can host it.
;; Two questions -- "which directories hold this session's skills" and "which
;; instruction files does it read" -- are each answered by a PURE function of
;; (configured value, project directory), because the namespaces answering them
;; must not require this one (see harness.cap.skills, whose roots the fence needs).
;; Somebody has to pair the config with the binding, and that somebody needs to
;; see harness.edn, the binding, and both readers: only this namespace can.
;;
;; It is also the right home by subject matter -- harness.edn is where these keys
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
                 (for [r (skill-roots thread-id)]
                   [r "where this session's skills live"])
                 (for [p (or allow [])]
                   [(resolve-path thread-id p)
                    "declared free by the project's :approval {:allow ..}"]))})))

(defn out-of-bounds?
  "TRUE when PATH, as THREAD-ID's session resolves it, lands outside every
  directory a bound session may touch. Which directories those are -- and why
  each one is free -- is `fence`, and this fn is only the containment question
  asked of that list. Keeping the two apart is what stops the rule and its
  statement from being two rules.

  A session with no binding has no fence, so this answers false for every path,
  byte-for-byte the pre-binding behavior. This fn is the WHERE question as a
  boolean -- whether to PARK an out-of-bounds call is the tool seam's decision,
  made through the ordinary approval flow."
  [thread-id path]
  (boolean
   (when-let [{:keys [free]} (fence thread-id)]
     (let [resolved (resolve-path thread-id path)]
       (not (some (fn [[free-path _]] (under? resolved free-path)) free))))))


;; ---------------------------------------------------------------- the pre-LLM step

(defn before-llm
  "HISTORY with this session's loaded skill bodies folded back in, for THREAD-ID --
  the loop's pre-LLM step, as a function the kernel is handed rather than one it
  requires (see harness.kernel.loop).

  IT IS ASSEMBLED HERE because this is where the two halves already meet: the roots
  are this namespace's business (skill-roots), the folding is
  harness.cap.skills'. Neither can own the pair -- harness.cap.skills cannot require
  this namespace, because this one requires IT -- and a second copy at the edge
  would be a copy that drifts, which is the failure the ticket named: a rebuilt
  conversation that carries no skill bodies is a different conversation.

  The roots are resolved PER CALL, not once per run, so editing harness.edn
  mid-run moves them -- matching every other configuration read in this codebase."
  [history thread-id]
  (skills/derived-injections history (skill-roots thread-id)))
