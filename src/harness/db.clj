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

(defn- exec!
  "One statement, no result expected. The migration steps and the transaction
  bodies speak through this."
  [^Connection c ^String sql]
  (with-open [st (.createStatement c)]
    (.execute st sql)))

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

(def migrations
  "The forward migration chain. (nth migrations i) takes the store from schema
  version i to i+1, and (count migrations) is the version this harness speaks.
  APPEND ONLY: a landed step is never edited, because stores somewhere have
  already run it, and a step that changes meaning silently corrupts them.

  Each step runs in its own transaction together with the version bump, so a step
  that throws leaves the store exactly where it was -- never half-migrated.

  EMPTY ON PURPOSE, and the honest state of things: this store has no tenant yet,
  so it has no table to hold. The claim itself -- the application id -- is not a
  migration step; it is what makes a file this store's, stamped when the file is
  created (see migrate-connection!). Inventing tables ahead of the feature that
  reads them would be a guess to be migrated away later.

  The first step arrives with the first table. A test may pass its own chain as
  the first argument to migrate!, with-connection or with-transaction -- that is
  how the walk across several versions is exercised today, without waiting for
  the features that will bring those versions."
  [])

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
      (in-transaction c (fn [] (exec! c (str "PRAGMA application_id = " magic)))))
    (doseq [version (range (inc on-disk) (inc target))]
      (in-transaction c
                      (fn []
                        ((nth steps (dec version)) c)
                        (exec! c (str "PRAGMA user_version = " version)))))
    (when created?
      ;; Only ever on the way IN to WAL, never back out: a store left in rollback
      ;; mode by a creation that died here is perfectly usable, just coarser about
      ;; concurrency, and downgrading a WAL store would be a pointless write.
      (exec! c "PRAGMA journal_mode = WAL"))
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
     (fn [^Connection c]
       (with-open [st (.createStatement c)
                   rs (.executeQuery st "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")]
         (loop [acc []]
           (if (.next rs)
             (recur (conj acc (.getString rs 1)))
             acc)))))))
