(ns harness.db
  "The home's metadata store: one SQLite file at <root>/harness.db, holding the
  facts about this home that can be REWRITTEN.

  WHY A DATABASE, AND WHY HERE. Two rulings from .scratch/project-sidebar/spec.md
  (decision 2) are built into this namespace, and they are what a reader should
  check the code against:

    1. The store holds STATE, not records. Projects, session ownership, archive
       flags, and the editing mode's anchor bookkeeping are all things rewritten
       in place. A session's jsonl log, config.edn, providers.edn and harness.edn
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
  harness.home/root: the root can move (CLJ_HARNESS_HOME, a test binding) between
  calls, and a cached handle would keep pointing at the old file. It is also what
  keeps test isolation honest -- the store follows the same root everything else
  in the home does, so the test runner's temp directory moves it too.

  READERS AND WRITERS. `with-connection` is the door for reading, `with-transaction`
  for writing. Both take an optional migration chain as their FIRST argument and
  use this namespace's own when it is omitted; that seam exists so a test can
  walk a multi-version chain without waiting for real features to land."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.home :as home])
  (:import (java.io File IOException)
           (java.nio.file CopyOption Files StandardCopyOption)
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

(defn- inspect
  "What is at F, judged from its bytes alone:

    {:state :fresh}                     nothing there, or an empty file -- what
                                        SQLite leaves before the first write,
                                        and what a rebuild starts from
    {:state :ours}                      the header carries this store's id
    {:state :foreign}                   a readable SQLite database belonging
                                        to something else
    {:state :unidentifiable}            a SQLite file too short to name an owner
    {:state :damaged}                   the wreck of a store, unusable as it
                                        stands
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
  rule the code actually keeps, rather than one it keeps most of the time."
  [^File f]
  (let [b (head f)
        n (if (nil? b) 0 (alength b))]
    (cond
      (zero? n) {:state :fresh}

      (< n 16) {:state :not-sqlite :bytes n}

      (not= sqlite-magic (String. b 0 16 "ISO-8859-1")) {:state :not-sqlite :bytes n}

      (< n 100) {:state :unidentifiable :bytes n}

      (not= (be32 b 68) magic) {:state :foreign
                                :app-id (be32 b 68)
                                :user-version (be32 b 60)
                                :bytes (.length f)}

      :else (if-some [declared (declared-bytes b)]
              (if (< (.length f) declared)
                {:state :damaged
                 :why (str "its header declares " declared " bytes but only "
                           (.length f) " are there")}
                {:state :ours :user-version (be32 b 60)})
              {:state :ours :user-version (be32 b 60)}))))

;; ------------------------------------------------------------- moved-aside fact

(defonce ^:private recovery-log
  (atom []))

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
  worse than refusing to start."
  [^File f why]
  (let [stamp (str (System/currentTimeMillis))
        moves (atom [])]
    (doseq [suffix ["" "-wal" "-shm"]]
      (let [src (io/file (str (.getPath f) suffix))]
        (when (.exists src)
          (let [dst  (io/file (str (.getPath f) suffix ".corrupt-" stamp))
                opts (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING])]
            (try
              (Files/move (.toPath src) (.toPath dst) opts)
              (catch IOException e
                (throw (ex-info (str "the store at " (.getAbsolutePath f) " is damaged ("
                                     why ") but could not be moved aside ("
                                     (ex-message e) "); nothing was written over it")
                                {:path (.getAbsolutePath f)
                                 :reason :quarantine-failed}))))
            (swap! moves conj (.getAbsolutePath dst))))))
    (let [fact {:path (.getAbsolutePath f) :why why :moved (vec @moves)}]
      (swap! recovery-log conj fact)
      (binding [*out* *err*]
        (println (str "harness.db: the store was damaged (" why ") and moved aside"
                      (when (seq @moves) (str ": " (str/join ", " @moves))))))
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
  shape worth arguing, and harness.project's namespace docstring does: identity
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
  harness.db-test/no-table-in-the-store-mirrors-a-log)."
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

(defn- hashline-store
  "Version 1 -> 2: what anchor-based editing has to remember between calls, and
  between restarts.

  FOUR TABLES, each a different question about the same idea -- a four-letter name
  for a line (see harness.hashline.anchors):

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
  harness.db-test). Each of these columns is ONE VALUE -- an array, written and
  read whole, never queried by element -- so a table for them would buy nothing
  and cost a row count proportional to every file the session has ever read.

  THE COLUMN NAMES ARE CHOSEN AGAINST A REGEX. harness.db-test forbids any column
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
  "Version 2 -> 3: which of a file's anchors that session has actually SHOWN the
  model.

  A separate step rather than a column added to `hashline-snapshots`'s DDL, because
  that step has already run in stores that exist: the chain is append-only, and
  editing a landed step would leave those stores holding a schema the harness no
  longer agrees with. ALTER TABLE is what an appended step does.

  WHY THIS IS STATE AND NOT DERIVABLE. An anchor can be owned without ever having
  been displayed: `read` pages a long file, and the anchors it minted for the pages
  it did not return are real anchors for real lines -- but the model has never seen
  them. Letting an edit address one would mean the model is changing a line it has
  never looked at, which is exactly the guess this feature exists to prevent. So
  'shown' has to be recorded, and the array is pruned to the live anchor set on
  every edit (see harness.hashline.store), so it cannot grow past one file."
  [^Connection c]
  (ddl! c "ALTER TABLE hashline_snapshots
             ADD COLUMN served TEXT NOT NULL DEFAULT '[]'"))

(def migrations
  "The forward migration chain. (nth migrations i) takes the store from schema
  version i to i+1, and (count migrations) is the version this harness speaks.
  APPEND ONLY: a landed step is never edited, because stores somewhere have
  already run it, and a step that changes meaning silently corrupts them.

  Each step runs in its own transaction together with the version bump, so a step
  that throws leaves the store exactly where it was -- never half-migrated.

  The claim itself -- the application id -- is not a step; it is what makes a
  file this store's, stamped when the file is created (see migrate-connection!).

  WHY ONE CHAIN AND NOT ONE PER TENANT. A schema version is a totally ordered
  fact about a file, so the steps that produce it have to be in one list in one
  order; assembling that list from fragments at load time is the registration
  machinery the store was built to avoid (and it would make 'which version is
  this file at' depend on load order). So the DDL lives here -- the store owns
  the SCHEMA -- while the queries over each tenant's tables live with the entity
  that owns them (harness.project, and later the anchor store).

  A test may pass its own chain as the first argument to migrate!,
  with-connection or with-transaction -- that is how the walk across several
  versions is exercised without waiting for the features that bring them."
  [projects-and-sessions
   hashline-store
   hashline-served])

(defn target-version
  "The schema version this harness speaks: the number of steps in `migrations`."
  []
  (count migrations))

(defn- migrate-connection!
  "Bring the open connection up to STEPS, claiming the file first when it is new.
  CREATED? says the file was not a recognizable store before this call, which
  decides both the claim and whether WAL is switched on at the end."
  [^Connection c ^File f steps created?]
  (let [on-disk (pragma-int c "user_version")
        target  (count steps)]
    (when (> on-disk target)
      (throw (ex-info (str "the store at " (.getAbsolutePath f) " is at schema version "
                           on-disk ", but this harness only knows " target
                           " -- a newer harness wrote it; upgrade this one, or move the file aside")
                      {:path (.getAbsolutePath f) :reason :too-new
                       :version on-disk :target target})))
    (when created?
      ;; The claim, made before WAL is ever enabled on this file: see the
      ;; namespace docstring on why that ordering is load-bearing. Not a numbered
      ;; step, because it is the file's identity rather than a version of its
      ;; schema -- every version of this store has it.
      (in-transaction c (fn [] (ddl! c (str "PRAGMA application_id = " magic)))))
    (doseq [version (range (inc on-disk) (inc target))]
      (in-transaction c
                      (fn []
                        ((nth steps (dec version)) c)
                        (ddl! c (str "PRAGMA user_version = " version)))))
    (when created?
      ;; Only ever on the way IN to WAL, never back out: a store left in rollback
      ;; mode by a creation that died here is perfectly usable, just coarser about
      ;; concurrency, and downgrading a WAL store would be a pointless write.
      (ddl! c "PRAGMA journal_mode = WAL"))
    (pragma-int c "user_version")))

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
  "The refusal for the wreck of a store, for the read-only path that must not
  repair it. The healing path (`ensure-connection!`) does not come through here:
  it quarantines and rebuilds. This is what a diagnostic is told."
  [^File f {:keys [why]}]
  (ex-info (str (.getAbsolutePath f) " is damaged: " why
                ". Nothing was written; opening the store rebuilds it, moving the "
                "damaged file aside under a .corrupt- name")
           {:path (.getAbsolutePath f) :reason :damaged :why why}))

(defn- ensure-connection!
  "The store, open, at STEPS' latest version. Creates it when nothing is there,
  rebuilds it when what is there is ours and ruined, and refuses -- by name, with
  no write -- when what is there belongs to someone else or cannot be identified."
  [steps]
  (let [f (home/db-file)]
    (loop [attempt 0]
      (let [seen (inspect f)]
        (case (:state seen)
          :not-sqlite     (throw (not-a-store f seen))
          :foreign        (throw (someone-elses f seen))
          :unidentifiable (throw (nameless f seen))
          (let [created? (not= :ours (:state seen))]
            (when (= :damaged (:state seen))
              (quarantine! f (:why seen)))
            (let [outcome (connect-and-migrate! f steps created?)]
              (if-let [why (:damage outcome)]
                (do
                  (quarantine! f why)
                  (when (pos? attempt)
                    (throw (ex-info (str "the store at " (.getAbsolutePath f)
                                         " is damaged and could not be rebuilt: " why)
                                    {:path (.getAbsolutePath f) :reason :rebuild-failed})))
                  (recur (inc attempt)))
                outcome))))))))

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
  ([steps] (with-connection steps (fn [c] (pragma-int c "user_version")))))

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
