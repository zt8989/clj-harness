(ns harness.infra.db
  "The home's metadata store: one SQLite file at <root>/harness.db, holding the
  facts about this home that can be REWRITTEN.

  WHY A DATABASE, AND WHY HERE. Two rulings from .scratch/project-sidebar/spec.md
  (decision 2) are built into this namespace, and they are what a reader should
  check the code against:

    1. The store holds STATE, not records. Projects, session ownership, archive
       flags, the editing mode's anchor bookkeeping, and a session's task list are
       all things rewritten in place. A session's jsonl log, config.edn
       and harness.edn
       are append-only or hand-edited and stay FILES. The criterion is not 'how
       often does it change' but 'can it be rewritten': an archive flag moves
       once a year and needs a row, because there is nowhere else to put it; a
       message is never rewritten and never belongs in a table. Nothing here
       mirrors a log, and nothing here replaces a configuration file -- opening
       the store reads no config file at all, and the config files are still
       re-read on every call so editing one needs no restart.

    2. What the store buys is not concurrent readers; it is ONE COMMIT FOR
       SEVERAL FACTS. Archiving a session rewrites a row the listing must agree
       with; a hash edit advances anchor ownership and writes its undo record.
       Under 'one file per fact plus atomic writes' those are either several
       writes that can half-succeed or a hand-rolled two-phase commit. Here it
       is `with-transaction`. Cross-process serialization comes along for free.

  THE FILE'S IDENTITY IS IN ITS BYTES. SQLite keeps an application id in the
  database header (offset 68) and the schema version in user_version (offset 60).
  This store claims the id 0x6861726E ('harn'), so 'is this file this store?' is
  answered by reading 100 bytes -- WITHOUT opening the file with SQLite, and
  therefore without touching it. That distinction is the point: a refusal must
  leave somebody else's database byte-for-byte alone, and merely opening a
  foreign SQLite file can create -wal/-shm sidecars beside it or convert its
  journal mode. Every judgement in `inspect` is made from bytes for that reason.

  THE ONE ORDERING THAT MATTERS. The identity is written while the journal is
  still in its default rollback mode, and WAL is switched on only afterwards (see
  migrate-connection!). A WAL holds newer copies of pages than the main file, so
  a header read from the main file can be STALE while a WAL sits beside it. A
  stale header may show an old schema version -- which migration heals -- but it
  must never be able to hide the identity, because then the store would look
  foreign and refuse to open itself. Writing the identity before WAL is ever
  enabled makes that impossible: every later copy of page 1 already carries it.

  CONNECTIONS ARE PER CALL, nothing is cached, matching config.edn and
  harness.infra.home/root: the root can move (CLJ_HARNESS_HOME, a test binding) between
  calls, and a cached handle would keep pointing at the old file. It is also what
  keeps test isolation honest -- the store follows the same root everything else
  in the home does, so the test runner's temp directory moves it too.

  READERS AND WRITERS. `with-connection` is the door for reading, `with-transaction`
  for writing. Both take an optional migration chain as their FIRST argument and
  use this namespace's own when it is omitted; that seam exists so a test can
  walk a multi-version chain without waiting for real features to land."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.infra.home :as home])
  (:import (java.io File IOException)
           (java.nio.file CopyOption Files NoSuchFileException StandardCopyOption)
           (java.sql Connection SQLException)
           (org.sqlite SQLiteConfig SQLiteConfig$TransactionMode SQLiteErrorCode
                       SQLiteException)))

;; ---------------------------------------------------------------- the identity

(def ^:private magic
  "This store's application id, as SQLite records it in the database header:
  'harn' as four ASCII bytes. A serial number would do the same job; letters were
  chosen so that a human looking at a stray harness.db in a hexdump can tell what
  wrote it without consulting this namespace."
  0x6861726E)

(def ^:private sqlite-magic
  "The 16 bytes every SQLite database begins with. Checked before anything else,
  because it is the one thing a truncated or foreign file cannot fake."
  "SQLite format 3\u0000")

(defn- head
  "The first 100 bytes of F -- the SQLite database header -- or fewer when the
  file is shorter, or nil when there is no file. Bounded on purpose: a store can
  be large, and this is read on every single open."
  [^File f]
  (when (.exists f)
    (let [buf (byte-array 100)]
      (with-open [in (io/input-stream f)]
        (loop [off 0]
          (if (>= off 100)
            buf
            (let [n (.read in buf off (- 100 off))]
              (if (neg? n)
                (java.util.Arrays/copyOf buf off)
                (recur (+ off n))))))))))

(defn- be16
  "Two bytes of the header, big-endian -- how SQLite stores every number in it."
  ^long [^bytes b off]
  (bit-or (bit-shift-left (bit-and (aget b off) 0xFF) 8)
          (bit-and (aget b (+ off 1)) 0xFF)))

(defn- be32
  "Four bytes of the header, big-endian. Separate from be16 rather than a
  general reader: the header has exactly these two widths, and a width argument
  would be a parameter nobody varies."
  ^long [^bytes b off]
  (bit-or (bit-shift-left (bit-and (aget b off) 0xFF) 24)
          (bit-shift-left (bit-and (aget b (+ off 1)) 0xFF) 16)
          (bit-shift-left (bit-and (aget b (+ off 2)) 0xFF) 8)
          (bit-and (aget b (+ off 3)) 0xFF)))

(defn- declared-bytes
  "How many bytes the header says the file is, or nil when its page count is
  STALE. SQLite only maintains 'size of the database in pages' (offset 28) while
  'version-valid-for' (offset 92) still matches the file change counter (offset
  24); when the two disagree the count describes an older state of the file, and
  comparing a file against it would report a healthy database as truncated."
  [^bytes b]
  (when (= (be32 b 24) (be32 b 92))
    (* (let [p (be16 b 16)] (if (= p 1) 65536 p))
       (be32 b 28))))

(defn- journal-beside
  "The name of the journal SQLite keeps beside F while it is being written, or nil
  when there is none. Both names, because this store wears both: `-wal` once WAL is
  on (which is every store that has finished being created), `-journal` while it is
  still in the default rollback mode (which is how a store is born -- see the
  namespace docstring on the ordering that matters)."
  [^File f]
  (some (fn [suffix]
          (let [side (io/file (str (.getPath f) suffix))]
            (when (.exists side) suffix)))
        ["-wal" "-journal"]))

(defn- short-of-its-header?
  "The length of F when it is genuinely short of the size its own header declares,
  or nil when no such claim can be made from outside.

  A FILE SHORTER THAN ITS OWN HEADER IS NOT BY ITSELF A WRECK. SQLite grows a
  database by writing page 1 first -- the header, carrying the new, larger page
  count -- and the pages it counts after it, so while a checkpoint is in flight the
  file is LEGITIMATELY shorter than its own header says. Measured on this store:
  writing while checkpointing a 6 MB WAL, 127843 readings in twelve seconds said
  'the file is shorter than its header declares'. Judging one of those moments is
  what moved a healthy 18 MB store aside three times on 2026-09-18.

  So the claim is only made when the shortness cannot be growth: the same length
  read twice, with no journal beside the file in between. The journal is what makes
  the difference -- SQLite removes it once a checkpoint has finished, and a finished
  checkpoint is a file whose length has caught up with its header. The length is
  read FIRST and the journal looked for SECOND, deliberately: a journal that appears
  in between means the file is being written right now, and a file being written is
  never judged.

  What this does not cover: a journal removed between the two readings by something
  other than a finished checkpoint (an outside `rm`, a journal-mode change). That
  residue is not decided here either -- opening the store still gets the last word,
  and only SQLite's own verdict moves a file (see `damage?`).

  The caller prints the number this returns, which is why it returns one: the reason
  a store is called a wreck must quote the size it was judged by, not a fresh look."
  [^File f ^long first-reading ^long declared]
  (let [journal (journal-beside f)
        again   (.length f)]
    (when (and (nil? journal) (= first-reading again) (< again declared))
      again)))

(defn- inspect
  "What is at F, judged from its bytes alone:

    {:state :fresh}                     nothing there, or an empty file -- what
                                        SQLite leaves before the first write,
                                        and what a rebuild starts from
    {:state :ours}                      the header carries this store's id
    {:state :foreign}                   a readable SQLite database belonging
                                        to something else
    {:state :unidentifiable}            a SQLite file too short to name an owner
    {:state :damaged}                   a store whose own bytes say it is shorter
                                        than it declares -- an OPINION, because a
                                        file being written to looks exactly like
                                        this; the healing path opens it and lets
                                        SQLite decide
    {:state :not-sqlite}                no SQLite header at all

  The order of the questions is the order of their answers' authority. The magic
  comes first, because a file that does not begin like a database is not ours to
  judge in any other respect. The identity comes before the truncation check, so
  a broken file that names a different owner is refused rather than repaired --
  moving somebody's wrecked database aside destroys their file as thoroughly as
  overwriting a healthy one.

  Ownership is only ever claimed from the header. Below 100 bytes there is no
  application id to read, so a file that short is :unidentifiable rather than
  guessed at, however plausibly it sits at the store's own path. That is what
  makes 'this store never moves aside a file whose owner it cannot establish' a
  rule the code actually keeps, rather than one it keeps most of the time.

  The truncation check is the one judgement here that can be wrong about a HEALTHY
  file, because a file mid-checkpoint really is shorter than its own header; it is
  made only where that explanation is ruled out. See `short-of-its-header?`."
  [^File f]
  (let [b   (head f)
        n   (if (nil? b) 0 (alength b))
        len (.length f)]
    (cond
      (zero? n) {:state :fresh}

      (< n 16) {:state :not-sqlite :bytes n}

      (not= sqlite-magic (String. b 0 16 "ISO-8859-1")) {:state :not-sqlite :bytes n}

      (< n 100) {:state :unidentifiable :bytes n}

      (not= (be32 b 68) magic) {:state :foreign
                                :app-id (be32 b 68)
                                :user-version (be32 b 60)
                                :bytes len}

      :else (if-some [declared (declared-bytes b)]
              (if-some [short (short-of-its-header? f len declared)]
                {:state :damaged
                 :why (str "its header declares " declared " bytes but only "
                           short " are there")}
                {:state :ours :user-version (be32 b 60)})
              {:state :ours :user-version (be32 b 60)}))))

;; ------------------------------------------------------------- moved-aside fact

(defonce ^:private recovery-log
  (atom []))

(defonce ^:private opened-stores
  (atom #{}))

(defn store-paths-opened
  "Every store FILE this process has resolved and opened, as absolute paths --
  which is the one thing a test run has to be able to prove about itself.

  THE QUESTION IT ANSWERS IS 'DID WE GO TO THIS HOME?', and it is asked because the
  answer used to be guessed from the file's [bytes mtime] and could therefore be
  wrong in the one case that happens all day: a LIVE HARNESS SESSION keeps its own
  state (anchors, todo lists, session rows) in that store while somebody runs the
  suite, so the developer's store moves for reasons that have nothing to do with
  the tests. `harness.test-runner` reads this set before it decides anything, and
  a store this process never opened is somebody else's writing -- reported, not
  blamed (see `isolation-verdict`).

  Absolute paths, because that is the form a comparison with `home/db-file` can be
  made in whatever root was in force when the connection opened."
  []
  @opened-stores)

(defn forget-store-paths-opened!
  "Empty the record above. ONE CALLER, AND IT IS THE REASON THIS EXISTS: isolation
  is installed in the MIDDLE of a process's life, so everything resolved before it
  (the runner's own look at the developer's store, to fingerprint it) has to be
  forgotten or the verdict below would convict the fixture itself."
  []
  (reset! opened-stores #{}))

(defonce ^:private store-open-lock
  ;; ONE THREAD OPENS OR BUILDS THE STORE AT A TIME. Every connection runs this, so
  ;; 'the store is not usable yet' and 'the store is not there yet' are answers several
  ;; threads get at the same instant -- and a thread that looks at or opens a file while
  ;; another is building or moving it does not fail with DAMAGE, which has a repair. It
  ;; fails with READONLY_DBMOVED, or an I/O error from a stat on a path that is gone, or
  ;; -- worst of the three -- it reads a HALF-BUILT store as :ours (the identity is
  ;; written first, on purpose, see the namespace docstring), gets CORRUPT from the
  ;; engine, and moves aside the store somebody else was in the middle of creating.
  ;; Nothing can repair any of those, so the whole open runs under this monitor. See
  ;; ensure-connection! for why that is cheaper than it sounds.
  (Object.))

(defn recoveries
  "Every damaged store this process has moved aside, oldest first, each as
  {:path .. :why .. :moved [..]}. It exists because the alternative to recording
  a rebuild is a store that is quietly empty -- and 'the store was empty' and
  'the store was thrown away' are the two answers that must never be confused.
  A quarantine is ALSO printed to stderr, for the human at the terminal; this is
  the same fact for code (diagnostics, the settings surface, a test)."
  []
  @recovery-log)

(defn- quarantine!
  "Move F -- and the -wal/-shm beside it -- aside under a timestamped name, so a
  fresh store can be built at the original path.

  The sidecars travel WITH the database or not at all: a -wal left next to a new
  empty store is a file whose pages may still match, and SQLite would replay them
  into the replacement. If any of the three cannot be moved, this throws rather
  than proceeding -- overwriting a file we failed to preserve is the one outcome
  worse than refusing to start.

  AND SOMEBODY ELSE MAY HAVE GOT THERE FIRST. Every connection runs the judgement, so
  several threads reach this call at once and only one of them finds the files still
  there. A source that is already gone is that thread's ANSWER, not a failure -- it
  means the store has been moved aside, which is the whole of what this call wanted.
  What it must not do is record one recovery twice, so only the call that moved the
  database ITSELF writes the fact down; a call that found nothing to move says so on
  stderr and adds nothing."
  [^File f why]
  (let [stamp    (str (System/currentTimeMillis))
        moves    (atom [])
        mine?    (atom false)]
    (doseq [suffix ["" "-wal" "-shm"]]
      (let [src (io/file (str (.getPath f) suffix))]
        (when (.exists src)
          (let [dst  (io/file (str (.getPath f) suffix ".corrupt-" stamp))
                opts (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])
                gone (try
                       (Files/move (.toPath src) (.toPath dst) opts)
                       false
                       (catch NoSuchFileException _
                         ;; Between the check above and this move another thread took
                         ;; it. Nothing to preserve, nothing to report, no failure.
                         true)
                       (catch IOException e
                         (throw (ex-info (str "the store at " (.getAbsolutePath f)
                                              " is damaged (" why
                                              ") but could not be moved aside ("
                                              (ex-message e) "); nothing was written over it")
                                         {:path (.getAbsolutePath f)
                                          :reason :quarantine-failed}))))]
            (when-not gone
              (swap! moves conj (.getAbsolutePath dst))
              (when (= "" suffix) (reset! mine? true)))))))
    (let [fact {:path (.getAbsolutePath f) :why why :moved (vec @moves)}]
      (if @mine?
        (do
          (swap! recovery-log conj fact)
          (binding [*out* *err*]
            (println (str "harness.db: the store was damaged (" why ") and moved aside"
                          (when (seq @moves) (str ": " (str/join ", " @moves)))))))
        (binding [*out* *err*]
          (println (str "harness.db: the damaged store at " (.getAbsolutePath f)
                        " had already been moved aside by another thread"
                        (when (seq @moves)
                          (str " (this call carried " (str/join ", " @moves) ")"))))))
      fact)))

;; ------------------------------------------------------------------ connections

(defn- store-url
  "Where a connection goes to reach F. An absolute path, so which file this is
  never depends on the process's working directory."
  ^String [^File f]
  (str "jdbc:sqlite:" (.getAbsolutePath f)))

(defn- store-config
  "The settings every connection to this store is opened with. The three
  decisions here -- and they are decisions, which is why they are together --
  are foreign keys, how long to wait for a lock, and when the lock is taken."
  ^SQLiteConfig []
  (doto (SQLiteConfig.)
    ;; On for every connection, so that a table with a reference in it is safe
    ;; the moment one exists. Off would mean every future table's author has to
    ;; remember to turn it on, and the one who forgets writes the foreign key
    ;; that silently does not hold.
    (.enforceForeignKeys true)
    ;; Two threads of one turn's tool calls both write, and waiting is what we
    ;; want. Five seconds is longer than any transaction here (a handful of
    ;; statements); without it a contended write FAILS instead of queueing.
    (.setBusyTimeout 5000)
    ;; BEGIN IMMEDIATE: the write lock is taken when the transaction opens, so a
    ;; read-then-write cannot lose an update to a concurrent one that upgraded
    ;; first. Deliberately NOT the journal mode -- see the ns docstring.
    (.setTransactionMode SQLiteConfig$TransactionMode/IMMEDIATE)))

(defn- pragma-int
  "One integer PRAGMA, read through a connection. Through a connection rather
  than off the file's header on purpose: with a -wal beside it, only SQLite
  knows the current value (the ns docstring's ordering note)."
  ^long [^Connection c ^String pragma]
  (with-open [st (.createStatement c)]
    (let [rs (.executeQuery st (str "PRAGMA " pragma))]
      (.next rs)
      (.getInt rs 1))))

(defn- ddl!
  "One statement, no parameters, no result -- the migration steps speak through
  this. DDL specifically: named apart from `execute!` because the two differ by
  more than a letter, and a DDL statement is the one kind of write here that
  cannot take a placeholder at all."
  [^Connection c ^String sql]
  (with-open [st (.createStatement c)]
    (.execute st sql)))

(defn- bind-params!
  "Fill a prepared statement's placeholders from PARAMS, in order. Clojure's
  Integer/Long/Boolean are not java.sql's, and a driver left to guess would bind
  a Long as DECIMAL -- so the types this store actually uses are named one by
  one, and anything else is a NAMED failure.

  The failure is the point: a silent `(str v)` would bind a keyword as the string
  \":editing\" and a Double as \"1.5\", and the store would accept both. A value
  that has a type the schema does not have a column for is a bug in the caller,
  and it should be told so where the statement is, not discovered later by
  reading a row that looks almost right."
  [^java.sql.PreparedStatement st params]
  (doseq [[i v] (map-indexed vector params)]
    (let [idx (int (inc i))]
      (cond
        (nil? v)         (.setObject st idx nil)
        (boolean? v)     (.setBoolean st idx v)
        (integer? v)     (.setLong st idx (long v))
        (string? v)      (.setString st idx v)
        :else            (throw (ex-info (str "cannot bind a " (.getName (class v))
                                              " (" (pr-str v) ") to parameter " idx
                                              "; this store binds nil, booleans,"
                                              " integers and strings")
                                         {:param idx :value v :type (class v)}))))))

(defn- column-key
  "A column label as the keyword a Clojure caller expects: lower-cased, with
  underscores turned into hyphens. SQL spells `canonical_path`; every map in this
  repo spells `:canonical-path`. Converting here, once, is what keeps the store's
  rows from reading like a foreign dialect at every call site -- and what stops
  the two spellings from quietly disagreeing, since a caller who guesses wrong
  gets a key that is simply absent rather than an error."
  [^String label]
  (keyword (str/replace (str/lower-case label) "_" "-")))

(defn query
  "Run SQL with PARAMS on a connection you already hold, and return the rows as
  maps of column keywords (see `column-key`). `select` is the one-shot form of
  the same thing -- use this one when you are INSIDE a transaction, where opening
  a second connection would ask a different one and miss your own uncommitted
  writes. Shared rather than re-written per caller: two namespaces each
  hand-rolling ResultSet walking is two places to get the closed-resource and the
  column-index details right."
  [^Connection c ^String sql & params]
  (with-open [st (.prepareStatement c sql)]
    (bind-params! st params)
    (with-open [rs (.executeQuery st)]
      (let [cols (mapv (fn [i] (column-key (.getColumnLabel (.getMetaData rs) (int i))))
                       (range 1 (inc (.getColumnCount (.getMetaData rs)))))]
        (loop [acc []]
          (if-not (.next rs)
            acc
            (recur (conj acc (zipmap cols (map #(.getObject rs (int %))
                                               (range 1 (inc (count cols)))))))))))))

(defn execute!
  "Run SQL with PARAMS on a connection you already hold and return the update
  count -- for a write whose only result is 'how many rows did that touch'. This
  is the writing counterpart of `query`, and shares its rule: inside a
  transaction, use the connection the transaction gave you."
  [^Connection c ^String sql & params]
  (with-open [st (.prepareStatement c sql)]
    (bind-params! st params)
    (.executeUpdate st)))

(defn- damage?
  "SQLite's own verdict that the file it was handed is unusable. The two codes
  both mean 'this is not a working database': NOTADB when the header itself is
  unreadable to SQLite, CORRUPT when a page is. Every other SQLException -- a
  missing directory, a permission -- is a different problem with a different
  answer, and must not be mistaken for damage to repair."
  [^SQLException e]
  (and (instance? SQLiteException e)
       (contains? #{SQLiteErrorCode/SQLITE_CORRUPT SQLiteErrorCode/SQLITE_NOTADB}
                  (.getResultCode ^SQLiteException e))))

(defn- in-transaction
  "Run F inside one transaction on C: commit when it returns, roll back when it
  throws. F takes no arguments and returns whatever its caller should see.

  The autocommit flag is restored in a `finally` so a failed transaction cannot
  leave the connection stuck in a transaction for its next user. Nested calls are
  not savepoints: SQLite has no nested transactions without them, and the second
  BEGIN would be refused -- which is the honest outcome, since a caller nesting
  these has a concurrency design to explain."
  [^Connection c f]
  (let [settled (atom false)]
    (.setAutoCommit c false)
    (try
      (let [v (f)]
        (.commit c)
        (reset! settled true)
        v)
      (finally
        (when-not @settled
          (try (.rollback c) (catch Throwable _)))
        (.setAutoCommit c true)))))

;; ------------------------------------------------------------------- migrations

(defn- projects-and-sessions
  "Version 0 -> 1: the home's two entities, and the columns are the whole
  contract.

    projects  one row per DIRECTORY, identified by `canonical_path`: symlinks,
              `..` and differing separators collapse to one form, so two
              spellings of one directory are one project and never two rows.

    sessions  one row per conversation: which project it belongs to (NULL for
              none), the absolute path THIS session was bound with (`path`),
              and whether it is archived. Deliberately NO message column of any
              kind -- a conversation lives in its jsonl log, and a second copy
              here would be a second truth that rots.

  WHY THE PATH IS ON THE SESSION RATHER THAN THE PROJECT is the one part of this
  shape worth arguing, and harness.cap.project's namespace docstring does: identity
  is shared between sessions, the spelling is not.

  UNBOUND IS ONE STATE, NOT TWO, and the schema keeps it that way. `project_id`
  and `path` are null together or neither is: the CHECK refuses a half-bound row,
  and the BEFORE DELETE trigger on `projects` clears both columns of its sessions
  in one statement, so the CHECK is never asked to judge a row mid-change.

  The trigger is BEFORE DELETE rather than the obvious AFTER UPDATE, and that
  ordering is forced by the CHECK. FK ON DELETE SET NULL clears `project_id`
  alone -- a row with a NULL project and a live path, which is precisely the
  half-bound state the CHECK exists to refuse, so the cascade fails and the
  delete with it. Clearing both first means the cascade finds nothing left to do.
  Without the pair, removing a project would leave sessions whose `binding-for`
  answered nothing while `path` still named a directory: one row saying two
  different things depending on who reads it.

  ON DELETE SET NULL is then also the schema saying what the sidebar's 'remove
  project' says in words: removing a project unbinds its sessions, it does not
  delete them. The two tables arrive together because a session with nowhere to
  point is not a state this home has any use for.

  `sessions.id` is NOT NULL, which SQLite does not imply from PRIMARY KEY on a
  text column -- a quirk worth pinning down, because 'bind the nil session' would
  otherwise write a row with a NULL id, and NULLs count as distinct in a unique
  index, so every such bind would add another one.

  A plain INTEGER PRIMARY KEY on `projects`, not AUTOINCREMENT: it is a rowid
  alias, so it gets ids assigned the same way without SQLite adding a
  `sqlite_sequence` bookkeeping table of its own. That table would show up in the
  store's table list, and the store's table list is part of its contract (see
  harness.infra.db-test/no-table-in-the-store-mirrors-a-log)."
  [^Connection c]
  (ddl! c "CREATE TABLE projects (
              id             INTEGER PRIMARY KEY,
              canonical_path TEXT NOT NULL UNIQUE,
              created_at     INTEGER NOT NULL)")
  (ddl! c "CREATE TABLE sessions (
              id         TEXT PRIMARY KEY NOT NULL,
              project_id INTEGER REFERENCES projects(id) ON DELETE SET NULL,
              path       TEXT,
              archived   INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL,
              CHECK ((project_id IS NULL) = (path IS NULL)))")
  (ddl! c "CREATE TRIGGER projects_forget_their_sessions
             BEFORE DELETE ON projects
             BEGIN
               UPDATE sessions SET project_id = NULL, path = NULL
                WHERE project_id = old.id;
             END"))

(defn- sessions-remember-the-project-path
  "Version 1 -> 2: `sessions.last_project_path`, the project a session most
  recently belonged to, kept so that REMOVING a project can be undone.

  Removing a project deletes its row, and the BEFORE DELETE trigger from version
  1 unbinds its sessions in the same statement -- that is decision 4, and it is
  what makes 'removed' mean the same thing to every reader (`binding-for` answers
  nil and the fence is off, exactly as for a session that never had a project).
  What that alone cannot do is bring the sessions BACK: the link is gone, and the
  sidebar's promise is that re-adding the same directory returns them, archive
  flags and history with them.

  So the session carries a second, quieter path: the CANONICAL form of the
  project it last belonged to. It is not a binding -- nothing resolves a tool path
  through it, `binding-for` never reads it, and a session whose project was
  removed is genuinely unbound -- it is the memory a re-add matches on. The
  trigger is deliberately left as it is: it clears `project_id` and `path`, and
  this column survives the removal untouched, which is the whole reason it exists.

  EXPLICIT UNBIND DOES CLEAR IT (`bind! .. nil`), and the difference is the
  point: asking for a session to be released means released, while removing a
  project is a statement about the DIRECTORY, so the sessions remember where they
  were and a re-add is lossless.

  Backfilled from the join, so a store written by version 1 has the memory too:
  without it, every session bound before this migration would be forgotten by the
  first removal, which is exactly the loss this step is here to prevent.

  The column is a PATH and not a project id, and that is forced rather than
  chosen: the id it would name is deleted by the removal, so an id would be a
  dangling reference by construction."
  [^Connection c]
  (ddl! c "ALTER TABLE sessions ADD COLUMN last_project_path TEXT")
  (ddl! c "UPDATE sessions
              SET last_project_path = (SELECT canonical_path FROM projects
                                        WHERE projects.id = sessions.project_id)
            WHERE project_id IS NOT NULL"))

(defn- table?
  "Does this store have a table called NAME? The probe half of a migration step:
  a step that leaves a table behind can be recognised by it."
  [^Connection c ^String name]
  (boolean (seq (query c "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?"
                      name))))

(defn- column?
  "Does TABLE have a column called NAME? The probe for a step that adds one."
  [^Connection c ^String table ^String name]
  (boolean (some #(= name (:name %))
                 (query c (str "SELECT name FROM pragma_table_info(?)") table))))

(defn- hashline-store
  "What anchor-based editing has to remember between calls, and between
  restarts.

  FOUR TABLES, each a different question about the same idea -- a four-letter name
  for a line (see harness.cap.hashline.anchors):

    hashline_snapshots  A FILE AS ONE SESSION LAST SAW IT: its whole-file
                        checksum, its line count, and for each line the anchor
                        that names it and the checksum it was named against.
                        Keyed by (path, thread_id) -- see below.
    hashline_ownership  WHICH ANCHORS A SESSION HAS OUT: anchor -> the one file
                        that anchor names. This is what makes an anchor exclusive:
                        minting walks past anything in here, and a stale or
                        borrowed anchor is refused by looking it up.
    hashline_sessions   WHERE A SESSION'S ANCHOR PROBE STANDS. The probe is the
                        pool position the next mint walks from; keeping it means a
                        session does not re-walk from its seed every time, and
                        that a restart resumes where it left off.
    hashline_undo       THE ONE EDIT THAT CAN BE TAKEN BACK, per file: the text
                        before it, the encoding that text had, the anchors that
                        named it, and the text it produced. Written by the edit,
                        read by undo -- and cleared by `write`, the boundary where
                        a file stops being the file the model was looking at.

  WHY THE SNAPSHOT IS KEYED BY (path, thread_id) AND NOT BY PATH. Upstream keys it
  by path, which reads naturally until you notice what the row holds: the anchors.
  An anchor is minted for ONE session and is not a name another session may use,
  so a snapshot keyed by path alone would hand session B the very anchors session
  A is holding for that file, and B's edits would be addressed by names A owns.
  Splitting the key is what makes 'two sessions read one file' two rows instead of
  a race -- the cost is one duplicated file checksum per session.

  WHY THE ANCHORS AND CHECKSUMS ARE JSON IN A COLUMN rather than a row per line. A
  row per line is ten thousand rows for one large file, per session, and the
  schema's own rule is that a table is a decision somebody has to make (see
  harness.infra.db-test). Each of these columns is ONE VALUE -- an array, written and
  read whole, never queried by element -- so a table for them would buy nothing
  and cost a row count proportional to every file the session has ever read.

  THE COLUMN NAMES ARE CHOSEN AGAINST A REGEX. harness.infra.db-test forbids any column
  that looks like conversation content, and `prior_text`/`resulting_text` name
  plainly what they hold -- the file's text, before and after -- where `content`
  would both trip that guard and be vaguer about which text is meant. They are
  state in the sense that decides it here: they are REWRITTEN on every edit, and
  undo cannot exist without them."
  [^Connection c]
  (ddl! c "CREATE TABLE hashline_snapshots (
              path           TEXT NOT NULL,
              thread_id      TEXT NOT NULL,
              file_checksum  TEXT NOT NULL,
              line_count     INTEGER NOT NULL,
              anchors        TEXT NOT NULL,
              line_checksums TEXT NOT NULL,
              updated_at     INTEGER NOT NULL,
              PRIMARY KEY (path, thread_id))")
  (ddl! c "CREATE TABLE hashline_ownership (
              thread_id TEXT NOT NULL,
              anchor    TEXT NOT NULL,
              path      TEXT NOT NULL,
              PRIMARY KEY (thread_id, anchor))")
  (ddl! c "CREATE TABLE hashline_sessions (
              thread_id  TEXT PRIMARY KEY NOT NULL,
              probe      INTEGER NOT NULL,
              updated_at INTEGER NOT NULL)")
  (ddl! c "CREATE TABLE hashline_undo (
              path           TEXT PRIMARY KEY NOT NULL,
              prior_text     TEXT NOT NULL,
              bom            INTEGER NOT NULL,
              ending         TEXT NOT NULL,
              anchors        TEXT NOT NULL,
              resulting_text TEXT NOT NULL,
              mode           INTEGER,
              updated_at     INTEGER NOT NULL)"))

(defn- hashline-served
  "Which of a file's anchors that session has actually SHOWN the model.

  A separate step rather than a column added to `hashline-store`'s DDL, because
  that step has already run in stores that exist: the chain is append-only, and
  editing a landed step would leave those stores holding a schema the harness no
  longer agrees with. ALTER TABLE is what an appended step does.

  WHY THIS IS STATE AND NOT DERIVABLE. An anchor can be owned without ever having
  been displayed: `read` pages a long file, and the anchors it minted for the pages
  it did not return are real anchors for real lines -- but the model has never seen
  them. Letting an edit address one would mean the model is changing a line it has
  never looked at, which is exactly the guess this feature exists to prevent. So
  'shown' has to be recorded, and the array is pruned to the live anchor set on
  every edit (see harness.cap.hashline.store), so it cannot grow past one file."
  [^Connection c]
  (ddl! c "ALTER TABLE hashline_snapshots
             ADD COLUMN served TEXT NOT NULL DEFAULT '[]'"))

(defn- hashline-undo-served
  "Which anchors were SHOWN to the session, recorded with the undo record as well
  as with the file's view.

  UNDO HAS TO PUT BACK THE ANCHORS, not only the text. A model that undoes an edit
  is usually about to edit the file again -- and the anchors it holds are the ones
  it had before, which are exactly the ones this record names. Restoring the text
  while leaving the live anchor set alone would produce a file whose anchors
  describe the state the undo just removed, and the model's very next call would be
  refused for addressing lines that 'moved'.

  So the record carries the shown set too, and for the same reason the view does:
  it is not derivable from the anchors (a paged read mints anchors for lines it
  never returned), so a restored anchor set without it would mark every line
  never-shown and refuse the retry the undo exists to enable.

  AN APPENDED STEP, like `hashline-served` and for the same reason: that step has
  already run in stores that exist, and the chain does not get edited once it has
  landed."
  [^Connection c]
  (ddl! c "ALTER TABLE hashline_undo
             ADD COLUMN served TEXT NOT NULL DEFAULT '[]'"))

(defn- todos-table
  "A SESSION'S TASK LIST: the one list `todo_write` replaces whole.

  ONE ROW PER SESSION, WITH THE LIST AS ONE VALUE, which is the same shape
  `hashline_snapshots` keeps its anchors and line checksums in and for the same
  reason: the column is written and read WHOLE and never queried by element. A row
  per item would buy nothing here and cost a position column nobody reads -- and
  the tool call it comes from REPLACES the list rather than appending to it, so
  there is no per-item history to keep in the first place.

  THAT REPLACEMENT IS WHY THIS IS STATE AND NOT A RECORD. The store's boundary
  (this namespace's docstring, point 1) is 'can it be rewritten': a message cannot,
  and a list the model rewrites on every call can. It is also why the table is
  named for the THING and not for the writing of it -- `todos`, not `todo_events`.

  `items` is JSON text, and the name is chosen against harness.db-test's guard on
  column names: it holds the list, and calling it `content` would both trip that
  guard and say less about what it is."
  [^Connection c]
  (ddl! c "CREATE TABLE todos (
              thread_id  TEXT PRIMARY KEY NOT NULL,
              items      TEXT NOT NULL,
              updated_at INTEGER NOT NULL)"))

(defn- session-claims
  "WHICH PROCESS IS SERVING A CONVERSATION: one row per live claim, gone when the
  claim is handed back.

  A CLAIM IS NOT A SESSION ROW, and the two lifetimes are why it is its own table.
  `sessions` says which conversations this home KEEPS -- for good, one row each,
  read by every listing. This says which of them a PROCESS is serving right now:
  seconds to minutes, one row each while it lasts, read by the run edge. A column on
  the other table would be NULL for almost every session and would need clearing by
  a process that died without clearing it, which is precisely the case the row's
  own contents have to answer (harness.cap.claims).

  THREE FACTS ABOUT THE OWNER, because they answer different questions: `instance`
  is a random id the owning process minted for itself, so 'is this row mine?' needs
  no OS call; `pid` and `started_at` are what a LATER process asks the OS about to
  decide whether the owner is still there. `started_at` is the owner's own start
  instant, and it is the column that keeps a REUSED pid from pinning a conversation
  forever. `token` belongs to the CLAIM rather than to the process -- see
  `harness.cap.claims/release!` for the race it closes.

  `since` is when this claim was taken, for the sentence a refused client reads."
  [^Connection c]
  (ddl! c "CREATE TABLE session_claims (
              thread_id  TEXT PRIMARY KEY NOT NULL,
              instance   TEXT NOT NULL,
              token      TEXT NOT NULL,
              pid        INTEGER NOT NULL,
              started_at INTEGER NOT NULL,
              since      INTEGER NOT NULL)"))

(def migrations
  "The forward migration chain, as NAMED steps.

  A STEP IS `{:name .. :present? .. :run ..}`. `:run` takes the connection and
  does the work; `:present?` answers whether this store has ALREADY had that work
  done to it. The second field is the whole point -- see below.

  APPEND ONLY, AND THE NAME IS THE IDENTITY. A step's `:name` is what the store
  records as done, so renaming one re-runs it; add steps, never edit them.

  WHY A NAME AND NOT A POSITION, WHICH IS WHAT THIS USED TO BE. The schema version
  was the LENGTH of this vector -- a position -- and a position is only meaningful
  against ONE chain. Two branches that each append a step at the same index give
  the same number two meanings, and then a store migrated by one is misread by the
  other. That is not hypothetical: `hashline-edit` appends `hashline-store` where
  this branch appends `sessions-remember-the-project-path`, so 'version 2' meant
  two different schemas, and the store in this home went 2 -> 4 under one chain and
  could not be opened by the other AT ALL -- the version-number guard refused it as
  'a newer harness wrote it'. The number was never the fact; the steps are.

  SO NOTHING IS REFUSED FOR BEING 'TOO NEW'. A chain that does not know a step
  simply has no step to run for it, and an unrecognised table is inert. What the
  old guard protected -- writing rows into a schema this code does not understand
  -- is a real hazard, and it is now bounded by the same thing that makes the walk
  work: a chain only ever runs the steps it names, and only where its own probe
  says the work is missing.

  `:present?` IS ALSO HOW AN EXISTING STORE IS ADOPTED. A store written before this
  table existed has no record of what ran, and the number cannot be trusted to say
  (that is the bug above). So the probe answers instead: a step whose work is
  already in the schema is RECORDED as done rather than run again. That is what
  lets one store be opened by both chains, and it is why the column that went
  missing earlier now heals itself instead of needing a hand-written ALTER.

  THE ANCHOR STEPS ARE APPENDED HERE, AFTER THE PROJECT ONES, and their probes are
  what make that safe in both directions: a store migrated by `hashline-edit`'s own
  chain already has the four tables and the two `served` columns, so those steps are
  RECORDED rather than run, and a store that never saw them gets them now.

  A test may pass its own chain as the first argument to migrate!,
  with-connection or with-transaction -- that is how the walk across several
  versions is exercised without waiting for the features that bring them."
  [{:name     "projects-and-sessions"
    :present? #(table? % "projects")
    :run      projects-and-sessions}
   {:name     "sessions-remember-the-project-path"
    :present? #(column? % "sessions" "last_project_path")
    :run      sessions-remember-the-project-path}
   {:name     "hashline-store"
    :present? #(table? % "hashline_snapshots")
    :run      hashline-store}
   {:name     "hashline-served"
    :present? #(column? % "hashline_snapshots" "served")
    :run      hashline-served}
   {:name     "hashline-undo-served"
    :present? #(column? % "hashline_undo" "served")
    :run      hashline-undo-served}
   {:name     "todos"
    :present? #(table? % "todos")
    :run      todos-table}
   ;; APPENDED, like every step after the first: a store written before this table
   ;; existed has no record of it, and the probe is what says whether it needs it.
   {:name     "session-claims"
    :present? #(table? % "session_claims")
    :run      session-claims}])

(defn target-version
  "The schema version this harness speaks: the number of steps in `migrations`."
  []
  (count migrations))

(def ^:private steps-table
  "The record of which named steps a store has had. CREATED HERE RATHER THAN AS A
  STEP, for the same reason the application id is not one: every store has it,
  including the ones written before it existed, and a step that creates the table
  recording steps is a step whose own record has nowhere to go."
  "CREATE TABLE IF NOT EXISTS schema_steps (
     name       TEXT PRIMARY KEY NOT NULL,
     applied_at INTEGER NOT NULL)")

(defn- applied-steps
  "The step names this store has recorded."
  [^Connection c]
  (set (map :name (query c "SELECT name FROM schema_steps"))))

(defn- record-step!
  "Claim NAME as done. `INSERT OR IGNORE` because the claim is a fact about the
  file, not a counter: a step recorded twice is the same fact stated twice, and
  the primary key is what says so."
  [^Connection c ^String name]
  (execute! c "INSERT OR IGNORE INTO schema_steps (name, applied_at) VALUES (?, ?)"
            name (System/currentTimeMillis)))

(defn- migrate-connection!
  "Bring the open connection up to STEPS, claiming the file first when it is new.
  CREATED? says the file was not a recognizable store before this call, which
  decides both the claim and whether WAL is switched on at the end.

  EACH STEP RUNS IN ONE TRANSACTION WITH ITS OWN RECORD, so a step that throws
  leaves neither its work nor the claim that it happened -- never half-migrated,
  and never a store that says it did something it did not.

  A STEP WHOSE PROBE SAYS THE WORK IS ALREADY THERE IS RECORDED, NOT RUN. That is
  the adoption path for every store written before `schema_steps` existed, and it
  is also what makes two chains able to share one file: each records the steps it
  recognises and has no opinion about the rest."
  [^Connection c ^File f steps created?]
  (when created?
    ;; The claim, made before WAL is ever enabled on this file: see the
    ;; namespace docstring on why that ordering is load-bearing. Not a step,
    ;; because it is the file's identity rather than a version of its schema.
    (in-transaction c (fn [] (ddl! c (str "PRAGMA application_id = " magic)))))
  (ddl! c steps-table)
  (let [done (applied-steps c)
        todo (remove #(contains? done (:name %)) steps)]
    (doseq [{:keys [name present? run]} todo]
      (in-transaction c
                      (fn []
                        (if (present? c)
                          (record-step! c name)
                          (do (run c)
                              (record-step! c name))))))
    ;; `user_version` is kept as a BREADCRUMB and never read as a gate: it counts
    ;; the steps of the chain that is running, so it means different numbers under
    ;; different chains -- which is exactly why nothing decides anything from it
    ;; any more. `schema_steps` is the truth.
    ;;
    ;; WRITTEN ONLY WHEN SOMETHING HAPPENED, because "a second open changes
    ;; nothing" is a promise this store keeps to everything that watches it -- the
    ;; test that fingerprints the file, and any backup or sync that reads an mtime
    ;; as a signal. A pragma written on every open would move the mtime of an
    ;; untouched store on every single request.
    (when (seq todo)
      (ddl! c (str "PRAGMA user_version = "
                   (count (filter #(contains? (applied-steps c) (:name %)) steps))))))
  (when created?
    ;; Only ever on the way IN to WAL, never back out: a store left in rollback
    ;; mode by a creation that died here is perfectly usable, just coarser about
    ;; concurrency, and downgrading a WAL store would be a pointless write.
    (ddl! c "PRAGMA journal_mode = WAL"))
  (pragma-int c "user_version"))

(defn- connect-and-migrate!
  "Open F and bring it up to STEPS. Either a live connection, or {:damage why}
  when SQLite itself says the file is unusable -- damage is a VALUE here, not an
  exception, because the caller's next move (move it aside, try once more) is not
  an error path, it is the recovery.

  The connection is closed on the way out of every path that does not hand it
  back, including a migration step that throws: a leaked handle keeps its file
  locked, and the caller's next move is to try again."
  [^File f steps created?]
  (let [c (.createConnection (store-config) (store-url f))]
    (try
      (migrate-connection! c f steps created?)
      c
      (catch SQLException e
        (.close c)
        (if (damage? e)
          {:damage (ex-message e)}
          (throw (ex-info (str "the store at " (.getAbsolutePath f)
                               " could not be opened: " (ex-message e))
                          {:path (.getAbsolutePath f) :reason :open-failed} e))))
      (catch Throwable t
        (.close c)
        (throw t)))))

(defn- not-a-store
  "The refusal for a file with no SQLite header. One message covers both 'someone
  put something else here' and 'our own header was zeroed', because at the byte
  level those are the same fact, and naming one of them would be a guess dressed
  up as a diagnosis."
  [^File f {:keys [bytes]}]
  (ex-info (str (.getAbsolutePath f) " is not a SQLite database (" bytes
                " bytes, no 'SQLite format 3' header) -- harness.db must be its own file, "
                "and nothing was written over this one")
           {:path (.getAbsolutePath f) :reason :not-sqlite}))

(defn- someone-elses
  "The refusal for a healthy SQLite database that is not this store. The message
  reports what the file says about itself, because the two likely causes -- a
  CLJ_HARNESS_HOME pointed at a directory that already had a database in it, and
  a harness.db copied from somewhere else -- are told apart by exactly that."
  [^File f {:keys [app-id user-version bytes]}]
  (ex-info (str (.getAbsolutePath f) " is a SQLite database, but not this store's: its "
                "application id is " app-id " (this store claims " magic ") and it is "
                bytes " bytes at schema version " user-version
                ". Nothing was written over it; move it aside or point "
                "CLJ_HARNESS_HOME somewhere else")
           {:path (.getAbsolutePath f) :reason :foreign
            :app-id app-id :user-version user-version}))

(defn- nameless
  "The refusal for a file that begins like a SQLite database but is too short to
  carry an application id. Refused rather than rebuilt, and that is the whole
  point: at this length nothing in the file says who wrote it, so moving it aside
  would be the store deciding that a file it cannot identify is its own to
  discard. A truncated store is a real loss when it happens, and this refusal is
  the difference between losing it and being told to go look."
  [^File f {:keys [bytes]}]
  (ex-info (str (.getAbsolutePath f) " begins like a SQLite database but stops after "
                bytes " bytes, inside the 100-byte header where the application id "
                "would be -- so there is no way to tell whether it is a destroyed "
                "harness.db or somebody else's file. Nothing was written or moved; "
                "look at it, and move it aside yourself if it is not needed")
           {:path (.getAbsolutePath f) :reason :unidentifiable :bytes bytes}))

(defn- wrecked
  "The refusal for a store whose BYTES read as unusable, for the read-only path that
  cannot open it to ask.

  WORDED AS A READING, NOT A VERDICT, and that distinction is the whole of it: the
  healing path does not act on this (only SQLite's own answer moves a file), so a
  diagnostic that announced 'the store is damaged' could be describing a store that
  opens perfectly well -- a file being written to is legitimately shorter than its own
  header. What a diagnostic can honestly report is what the bytes said."
  [^File f {:keys [why]}]
  (ex-info (str (.getAbsolutePath f) " does not read as a usable store: " why
                ". That answer comes from the file's bytes alone, because a read-only"
                " question does not open the file -- and opening it is what decides: a"
                " store in this state may open perfectly well, since a file being"
                " written to is shorter than its own header while a checkpoint runs."
                " Nothing was written or moved.")
           {:path (.getAbsolutePath f) :reason :damaged :why why}))

(defn- ensure-connection!
  "The store, open, at STEPS' latest version. Creates it when nothing is there,
  rebuilds it when SQLITE says what is there is unusable, and refuses -- by name, with
  no write -- when what is there belongs to someone else or cannot be identified.

  ONLY THE ENGINE'S VERDICT MOVES A FILE. `inspect` reads bytes, and a byte reading is
  a snapshot of a file that another thread may be in the middle of writing -- so it is
  a hint about what to expect, never a reason to move somebody's store aside. The move
  happens one step below: `connect-and-migrate!` opens the file, and only SQLite saying
  CORRUPT or NOTADB (`damage?`) turns into a quarantine and a rebuild.

  A FILE THAT ONLY LOOKS CUT SHORT IS THEREFORE OPENED rather than thrown away, and
  that is the point: opening it is how the question 'is this a wreck or a snapshot?' is
  actually answered. What was judged damaged is not treated as NEW either -- nothing of
  ours is written into a file we have not managed to read yet (see `created?` below).

  THE JUDGEMENT AND THE OPEN RUN UNDER ONE MONITOR (`store-open-lock`). The alternative
  is not a slower answer but a WRONG one, and there are three ways it goes wrong: a
  second thread that judged the same wreck and then opened the file after the first had
  moved it fails with READONLY_DBMOVED; the same window gives an I/O error from a stat
  on a path that is gone; and worst, a store that is being BUILT reads as ours from
  outside (the identity is written first), so a reader that skipped the lock opens a
  half-built file, hears CORRUPT from the engine, and moves aside the store somebody
  else was creating -- while that somebody's own connection dies of READONLY_DBMOVED.
  None of the three is damage, so none of them has a repair.

  That is affordable because only the OPEN is serialized: `with-connection` opens a
  fresh JDBC connection per call anyway, and the queries that follow run unlocked."
  [steps]
  (let [f     (home/db-file)
        _     (swap! opened-stores conj (.getAbsolutePath f))
        judge (fn []
                (let [seen (inspect f)]
                  (case (:state seen)
                    :not-sqlite     (throw (not-a-store f seen))
                    :foreign        (throw (someone-elses f seen))
                    :unidentifiable (throw (nameless f seen))
                    seen)))
        open! (fn [^long attempt seen]
                ;; CREATED MEANS 'there was nothing here and this process is about to
                ;; build it'. A :damaged file is NOT created: it has an owner and a
                ;; schema already, and the identity a creation writes must not go into
                ;; a file whose usability is the open question.
                (let [outcome (connect-and-migrate! f steps (= :fresh (:state seen)))]
                  (if-let [why (:damage outcome)]
                    (do
                      (quarantine! f why)
                      (when (pos? attempt)
                        (throw (ex-info (str "the store at " (.getAbsolutePath f)
                                             " is damaged and could not be rebuilt: " why)
                                        {:path (.getAbsolutePath f)
                                         :reason :rebuild-failed})))
                      ::retry)
                    outcome)))]
    (loop [attempt 0]
      ;; THE JUDGEMENT RUNS INSIDE THE LOCK TOO, and that is not belt-and-braces: a
      ;; store being built READS AS :ours from outside, because the identity is written
      ;; before anything else. A reader that judged outside the lock would open that
      ;; half-built file and hand the engine a wreck to report.
      ;;
      ;; THE COST IS ONE UNCONTENDED MONITOR per connection, against a JDBC connection
      ;; that `with-connection` opens per call regardless -- and only the OPEN is
      ;; serialized, never the queries that follow it.
      (let [answer (locking store-open-lock (open! attempt (judge)))]
        (if (= ::retry answer)
          (recur (inc attempt))
          answer)))))

;; ------------------------------------------------------------------- the surface

(defn with-connection
  "Call F with an open store connection, and close it afterwards. Reading goes
  through here; writing goes through `with-transaction`, which adds the one thing
  a write needs.

  F takes one argument, the java.sql.Connection. The optional STEPS arity is the
  migration-chain seam described on `migrations`."
  ([f] (with-connection migrations f))
  ([steps f]
   (let [c (ensure-connection! steps)]
     (try (f c) (finally (.close c))))))

(defn select
  "One-shot read: run SQL with PARAMS against the store, opening and closing a
  connection for this one question. The shape most callers want -- `query` is for
  the ones already inside a connection, where a second open would ask a different
  connection and miss writes the current transaction has not committed."
  [sql & params]
  (with-connection (fn [c] (apply query c sql params))))

(defn with-transaction
  "Run F inside one transaction on an open store connection: everything F writes
  commits together, or nothing does. F takes the java.sql.Connection and may run
  as many statements as it likes.

  This is the reason the store exists at all: several facts that must agree are
  written as one act. See the namespace docstring, point 2."
  [f]
  (with-connection (fn [c] (in-transaction c (fn [] (f c))))))

(defn migrate!
  "Bring the store up to the version this harness speaks and return that version.
  Idempotent: on an up-to-date store it opens, reads, and closes. This is the
  MUTATING half of the version question -- and the one the tests use to build a
  store on a chain of their own (the STEPS arity), which is how the walk across
  several versions is exercised without waiting for the features that bring them."
  ([] (migrate! migrations))
  ([steps]
   ;; HOW MANY OF *THIS* CHAIN'S STEPS THE STORE HAS, which is the question the
   ;; caller is asking -- and NOT `user_version`, which is a breadcrumb written by
   ;; whichever chain ran last and therefore means different numbers to different
   ;; chains. A store built by a longer chain answers the longer chain's count to
   ;; the longer chain and this chain's count to this one; neither is misled.
   (with-connection
     steps
     (fn [c] (count (filter #(contains? (applied-steps c) (:name %)) steps))))))

(defn schema-version
  "The schema version of the store AS THE FILE REPORTS IT, or nil when there is
  no store yet. The READ-ONLY half of the version question, and read-only is the
  whole point: it opens no connection, creates nothing, and leaves no -wal/-shm
  behind, so a diagnostic or a documentation pass can ask without changing
  anything. `migrate!` is its counterpart, and it heals.

  That difference shows in one place: while a -wal sits beside the file, the
  header can lag one step behind what `migrate!` would report. A diagnostic whose
  answer is stale reads better than one that writes in order to be accurate.

  The refusals are the same ones an open gives -- somebody else's database, a
  file that is not a database, a file too short to identify, and a wreck -- since
  a read-only question deserves the same named answer as a read-write one."
  []
  (let [f (home/db-file)
        seen (inspect f)]
    (case (:state seen)
      :fresh          nil
      :ours           (:user-version seen)
      :foreign        (throw (someone-elses f seen))
      :not-sqlite     (throw (not-a-store f seen))
      :unidentifiable (throw (nameless f seen))
      :damaged        (throw (wrecked f seen)))))

(defn tables
  "The store's table names, sorted. What the store holds is part of its contract
  -- point 1 of the namespace docstring says no table may mirror a log -- and a
  contract needs a way to be looked at, both by the test that guards it and by a
  diagnostic that has to explain a home.

  The optional STEPS arity answers for a store built by another chain, which is
  how a test inspects a store it migrated itself."
  ([] (tables migrations))
  ([steps]
   (with-connection
     steps
     (fn [c] (mapv :name (query c "SELECT name FROM sqlite_master
                                    WHERE type = 'table' ORDER BY name"))))))
