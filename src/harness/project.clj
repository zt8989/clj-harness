(ns harness.project
  "A session's project directory. thread-id -> binding is this namespace's whole
  state, and since the sqlite ticket it is a ROW rather than an atom entry: the
  home's store holds one `projects` row per directory and one `sessions` row per
  conversation, and a session's project is a foreign key on the second. What that
  buys is the thing the atom could not do -- the binding is still there after the
  process is gone -- and what it costs is that a binding is now a database read.

  Both tables' DDL lives in harness.db/migrations (the store owns the schema);
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
  both statements be true at once -- identity shared, spelling not."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.db :as db]
            [harness.home :as home])
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
  change of archival state."
  [^Connection c session-id project-id ^String path]
  (db/execute! c "INSERT INTO sessions (id, project_id, path, created_at)
                  VALUES (?, ?, ?, ?)
                  ON CONFLICT(id) DO UPDATE SET project_id = excluded.project_id,
                                                path       = excluded.path"
               session-id project-id path (System/currentTimeMillis)))

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
  {:project-id .. :path <canonical>}.

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

  Validation happens before anything is written, and it is the same check `bind!`
  runs -- see `checked-directory`."
  [dir]
  (let [f     (checked-directory dir)
        canon (canonical-path f)]
    (db/with-transaction
      (fn [^Connection c]
        {:project-id (upsert-project! c canon)
         :path       canon}))))

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
             (db/execute! c "UPDATE sessions SET project_id = NULL, path = NULL WHERE id = ?"
                          thread-id)))
         nil)
     (let [f   (checked-directory dir)
           abs (absolute dir)]
       (db/with-transaction
         (fn [^Connection c]
           (touch-session! c thread-id (upsert-project! c (canonical-path f)) abs)))
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

  Reading the flag is this namespace's; SETTING it is the archive ticket's
  business, so there is deliberately no `archive!` here yet."
  []
  (mapv (fn [row] (-> row
                      (assoc :archived? (pos? (long (:archived row))))
                      (dissoc :archived)))
        (db/select "SELECT id, project_id, path, archived, created_at FROM sessions ORDER BY created_at, id")))

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
  call writes to the store."
  ([] (harness-config nil))
  ([thread-id]
   (merge (read-harness-edn (io/file (home/root) "harness.edn"))
          (when-let [dir (binding-for thread-id)]
            (read-harness-edn (io/file dir ".harness" "harness.edn"))))))

(defn out-of-bounds?
  "TRUE when PATH, as THREAD-ID's session resolves it, lands outside every
  directory a bound session may touch. The allowed set:

    - the project directory itself -- UNLESS the project's harness.edn set
      :approval {:strict true}, which tightens the fence until every project
      path needs an approval too;
    - the configuration home (config.edn, providers.edn, .env -- reading
      one's own configuration is the fence's explicit allowance, and strict
      does not tighten it away: the config home is harness's own ground, not
      the project's);
    - :approval {:allow [..]} -- extra paths the project declares free of
      the fence, each resolved for the session like any tool path (relative
      to the project root, absolute passes through).

  The config is read fresh per call, so harness.edn edits take effect on the
  next tool call. The fence engages ONLY when a binding exists: an unbound
  session answers false for every path, byte-for-byte the pre-binding
  behavior. This fn is the WHERE question as a boolean -- whether to PARK an
  out-of-bounds call is the tool seam's decision, made through the ordinary
  approval flow."
  [thread-id path]
  (boolean
   (when-let [dir (binding-for thread-id)]
     (let [{:keys [approval]}   (harness-config thread-id)
           {:keys [allow strict]} approval
           resolved (resolve-path thread-id path)
           allowed  (concat (when-not strict [dir])
                            [(home/root)]
                            (map #(resolve-path thread-id %) (or allow [])))]
       (not (some #(under? resolved %) allowed))))))
