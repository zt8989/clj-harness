(ns harness.infra.db-test
  "harness.infra.db's external behaviour: where the store lives, what a first open
  builds, what it refuses, and what it does when the file it finds is ruined.

  Every test that could create a file points the home at a scratch directory of
  its own (with-redefs on harness.infra.home/root), so a test can damage a store, or
  fill one with junk, without touching the store the rest of the suite is using
  -- and without a trace of any of it reaching the developer's real home.

  The tests that matter most are the refusals. A store that quietly overwrites
  somebody else's database, or that treats a damaged file as an empty one, fails
  in the direction nobody notices."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.infra.db :as db]
            [harness.cap.providers :as providers]
            [harness.infra.home :as home]
            [harness.test-support :as support])
  (:import (java.io File StringWriter)
           (java.nio.file Files OpenOption)
           (java.sql Connection DriverManager)))

;; One scratch directory for this namespace, made by the runner's own mkdtemp --
;; nothing to wipe and nothing another process could have made first.
(def ^:private scratch
  (io/file (support/temp-dir "db")))

(defn- fresh-root
  "A new, empty home directory for one test."
  []
  (let [dir (io/file scratch (str "root-" (System/nanoTime)))]
    (.mkdirs dir)
    dir))

(defn- with-root
  "Run F with harness.infra.home/root pointed at DIR -- so every path this namespace
  derives (config.edn, the logs directory, harness.db) resolves inside it."
  [dir f]
  (with-redefs [home/root (constantly (str dir))]
    (f)))

(defn- raw-connection
  "A plain JDBC connection to PATH with none of harness.infra.db's settings: how the
  tests read a file the way a FOREIGN program would, and how they plant one."
  ^Connection [^File path]
  (DriverManager/getConnection (str "jdbc:sqlite:" (.getAbsolutePath path))))

(defn- pragma
  [^File path ^String name]
  (with-open [c (raw-connection path)
              st (.createStatement c)
              rs (.executeQuery st (str "PRAGMA " name))]
    (.next rs)
    (.getInt rs 1)))

(defn- plant-foreign-store!
  "A healthy SQLite database at PATH belonging to something else: a table of its
  own, a row in it, and the default application id."
  [^File path]
  (with-open [c (raw-connection path)
              st (.createStatement c)]
    (.execute st "CREATE TABLE other_app (x INTEGER)")
    (.execute st "INSERT INTO other_app VALUES (1)")))

(defn- fingerprint
  "Every file whose name starts with PREFIX under DIR: name -> [bytes mtime].
  What a refusal promises is that this does not move."
  [^File dir ^String prefix]
  (into {}
        (for [f (or (.listFiles dir) (make-array File 0))
              :when (str/starts-with? (.getName f) prefix)]
          [(.getName f) [(.length f) (.lastModified f)]])))

(defn- truncate!
  "Copy SRC to DEST keeping only the first N bytes -- what a store looks like
  after the process died mid-write."
  [^File src ^File dest ^long n]
  (let [bytes (Files/readAllBytes (.toPath src))]
    (Files/write (.toPath dest)
                 (java.util.Arrays/copyOf bytes (int (min n (alength bytes))))
                 (make-array OpenOption 0))))

(defn- declare-more-pages!
  "Make DEST's own header count MORE pages than the file holds, and return the size
  it now declares. That is the state a checkpoint is in between writing page 1 --
  which carries the new count -- and writing the pages page 1 counts, so it is what
  a HEALTHY store looks like while it is being written to."
  ^long [^File dest ^long extra]
  (let [path  (.toPath dest)
        bytes (Files/readAllBytes path)
        be32  (fn [^long off]
                (bit-or (bit-shift-left (bit-and (aget bytes off) 0xFF) 24)
                        (bit-shift-left (bit-and (aget bytes (+ off 1)) 0xFF) 16)
                        (bit-shift-left (bit-and (aget bytes (+ off 2)) 0xFF) 8)
                        (bit-and (aget bytes (+ off 3)) 0xFF)))
        page  (let [p (bit-or (bit-shift-left (bit-and (aget bytes 16) 0xFF) 8)
                              (bit-and (aget bytes 17) 0xFF))]
                (if (= p 1) 65536 p))
        want  (+ (be32 28) extra)
        with  (doto (aclone bytes)
                (aset 28 (unchecked-byte (bit-shift-right want 24)))
                (aset 29 (unchecked-byte (bit-shift-right want 16)))
                (aset 30 (unchecked-byte (bit-shift-right want 8)))
                (aset 31 (unchecked-byte want)))]
    (Files/write path with (make-array OpenOption 0))
    (* page want)))

(declare raw-rows)

(defn- rows
  "Every row of SQL on the store in this home, as vectors of values -- the
  tests' window into what a transaction actually committed."
  [^String sql]
  (db/with-connection (fn [^Connection c] (raw-rows c sql))))

(defn- raw-rows
  "Rows read through a connection the TEST owns. Separate from `rows` for the
  one case where the point is that an outside program can still read the file."
  [^Connection c ^String sql]
  (with-open [st (.createStatement c)
              rs (.executeQuery st sql)]
    (loop [acc []]
      (if-not (.next rs)
        acc
        (let [n (.getColumnCount (.getMetaData rs))]
          (recur (conj acc (mapv #(.getObject rs (int %)) (range 1 (inc n))))))))))

(defn- step
  "A migration step that records itself and leaves a table behind, so both the
  order of a walk and its effects are observable.

  A step is a MAP now -- {:name :present? :run} -- and the probe is not
  ceremony: it is what lets a chain recognise work that is already there, which
  is how one store is opened by two chains. A table named after the step is what
  this one leaves, so `sqlite_master` is what it asks."
  [label log]
  {:name label
   :present? (fn [^Connection c]
               (boolean (seq (db/query c
                                       "SELECT name FROM sqlite_master
                                         WHERE type = 'table' AND name = ?"
                                       label))))
   :run (fn [^Connection c]
          (swap! log conj label)
          (with-open [st (.createStatement c)]
            (.execute st (str "CREATE TABLE " label " (x INTEGER)"))))})

;; ------------------------------------------------------------------ the home

(deftest the-store-lives-in-this-home-and-nowhere-else
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (testing "harness.db resolves inside the root"
          (is (= (str dir) (str (.getParentFile (home/db-file)))))
          (is (= "harness.db" (.getName (home/db-file)))))
        (testing "and a store that does not exist yet is not created by asking
                  about it"
          (is (nil? (db/schema-version)))
          (is (not (.exists (home/db-file)))))
        (testing "opening it creates it there, and only there"
          (is (= (db/target-version) (db/migrate!)))
          (is (= ["harness.db"] (mapv #(.getName %) (.listFiles dir)))))))))

(deftest opening-the-store-reads-no-configuration-file
  ;; The boundary this guards is .scratch/project-sidebar decision 2: the store
  ;; holds state, while records AND configuration stay files. Opening it must
  ;; therefore not consult config.edn.
  ;;
  ;; THE TRIPWIRE IS A BROKEN config.edn, not a missing one: a missing file reads as
  ;; an empty one now (harness.infra.home/config), so its absence would prove nothing.
  ;; harness.cap.providers/config still refuses a file that says something which is not
  ;; a map, so a store that reached for its configuration would fail here with that
  ;; sentence rather than passing quietly.
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (spit (io/file dir "config.edn") "[1 2 3]" :encoding "UTF-8")
        (testing "this home's configuration is a file the reader would refuse"
          ;; THE READER THAT PARSES is providers/config -- `home/config` only hands
          ;; over bytes, so it refuses nothing and would be a tripwire that never
          ;; fires. Reaching for the configuration on this path therefore fails HERE.
          (is (thrown? clojure.lang.ExceptionInfo (providers/config))
              "so reaching for it on this path could not pass unnoticed"))
        (testing "and the store still opens, is written to, and is read back"
          (db/with-transaction
            (fn [^Connection c]
              (with-open [st (.createStatement c)]
                (.execute st "CREATE TABLE probe (x INTEGER)")
                (.execute st "INSERT INTO probe VALUES (7)"))))
          (is (= [[7]] (rows "SELECT x FROM probe"))))
        (testing "and the file it did not read is the one it did not touch"
          (is (= "[1 2 3]" (slurp (io/file dir "config.edn") :encoding "UTF-8"))))))))

;; ------------------------------------------------------------------ first open

(deftest a-first-open-builds-the-schema-and-a-second-changes-nothing
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (testing "the store arrives at the version this harness speaks"
          (is (pos? (db/target-version)))
          (is (= (db/target-version) (db/migrate!)))
          (is (= (db/target-version) (db/schema-version))))
        (testing "with exactly the tables the home's entities declared"
          ;; Both feature sets now: this store runs BOTH chains' steps -- the
          ;; project/session tables (with `schema_steps` recording them) and the
          ;; four anchor tables.
          (is (= ["hashline_ownership" "hashline_sessions" "hashline_snapshots"
                  "hashline_undo" "projects" "schema_steps" "session_claims" "sessions"
                  "todos"]
                 (db/tables))))
        (testing "and the file is claimed: its application id is this store's,
                  read the way a foreign program would read it"
          (is (= 0x6861726E (pragma (home/db-file) "application_id"))))
        (testing "a second open changes nothing"
          (let [before (fingerprint dir "harness.db")]
            (is (= (db/target-version) (db/migrate!)))
            (is (= (db/target-version) (db/schema-version)))
            (is (= before (fingerprint dir "harness.db")))))))))

;; ------------------------------------------------------------------- refusals

(deftest a-foreign-database-is-refused-and-left-alone
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (let [db-file   (home/db-file)
              _         (plant-foreign-store! db-file)
              untouched (fingerprint dir "harness.db")]
          (testing "the refusal names the file and what the file says about itself"
            (let [thrown (try (db/schema-version) nil (catch Exception e e))]
              (is (some? thrown) "a foreign database must not be opened")
              (is (str/includes? (ex-message thrown) "not this store's"))
              (is (= :foreign (:reason (ex-data thrown))))
              (is (= 0 (:app-id (ex-data thrown))))))
          (testing "and the file is byte-for-byte where it was, with no sidecars"
            (is (= untouched (fingerprint dir "harness.db"))))
          (testing "its content is still readable by whoever owns it"
            (is (= [[1]]
                   (with-open [c (raw-connection db-file)]
                     (raw-rows c "SELECT x FROM other_app"))))))))))

(deftest a-file-that-is-not-a-database-at-all-is-refused
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (spit (home/db-file) "this is somebody's notes, not a database")
        (let [before (fingerprint dir "harness.db")
              thrown (try (db/schema-version) nil (catch Exception e e))]
          (is (some? thrown))
          (is (str/includes? (ex-message thrown) "not a SQLite database"))
          (is (= :not-sqlite (:reason (ex-data thrown))))
          (is (= before (fingerprint dir "harness.db"))
              "a refusal writes nothing, not even a sidecar"))))))

(deftest a-damaged-database-that-is-not-ours-is-refused-instead-of-repaired
  ;; The identity is checked before the damage, and that order is the promise:
  ;; a file that says it belongs to someone else is never repaired, however
  ;; broken it is. Moving somebody's wrecked database aside destroys their file
  ;; just as thoroughly as overwriting a healthy one.
  (let [dir    (fresh-root)
        source (io/file dir "source.db")]
    (plant-foreign-store! source)
    (with-root
      dir
      (fn []
        (doseq [n [100 200 4096]]
          (let [db-file (home/db-file)]
            (io/delete-file db-file true)
            (truncate! source db-file n)
            (let [before (fingerprint dir "harness.db")
                  thrown (try (db/schema-version) nil (catch Exception e e))]
              (is (some? thrown)
                  (str "cut to " n " bytes: a foreign database must not be repaired"))
              (is (= :foreign (:reason (ex-data thrown)))
                  (str "cut to " n " bytes: its header still names its owner"))
              (is (= before (fingerprint dir "harness.db"))
                  (str "cut to " n " bytes: nothing was moved or written")))))))))

(deftest a-file-too-short-to-identify-is-refused-not-rebuilt
  ;; Below the 100-byte header there is no application id to read, so ownership
  ;; is genuinely unknowable from the bytes. The store refuses rather than
  ;; guessing, and this is the sharpest edge in the whole namespace: a truncated
  ;; harness.db is a real loss, and moving it aside under the belief that "a
  ;; file this shape at this path must be ours" is exactly how a store quietly
  ;; discards a file whose owner it never established. The refusal leaves the
  ;; file untouched and names what to do -- look at it, move it aside by hand.
  (let [dir    (fresh-root)
        source (io/file dir "source.db")]
    (plant-foreign-store! source)
    (with-root
      dir
      (fn []
        (doseq [n [16 40 99]]
          (let [db-file (home/db-file)]
            (io/delete-file db-file true)
            (truncate! source db-file n)
            (let [before (fingerprint dir "harness.db")
                  thrown (try (db/schema-version) nil (catch Exception e e))]
              (is (some? thrown)
                  (str n " bytes: a file whose owner cannot be read must not be rebuilt"))
              (is (= :unidentifiable (:reason (ex-data thrown)))
                  (str n " bytes: the refusal says why it cannot decide"))
              (is (str/includes? (ex-message thrown) "application id")
                  (str n " bytes: and names the missing fact"))
              (is (= before (fingerprint dir "harness.db"))
                  (str n " bytes: it is left exactly where it was")))))
        (testing "the healing path refuses the same way, so nothing is moved either"
          (let [db-file (home/db-file)]
            (io/delete-file db-file true)
            (truncate! source db-file 40)
            (let [thrown (try (db/migrate!) nil (catch Exception e e))]
              (is (= :unidentifiable (:reason (ex-data thrown)))))
            (is (= 40 (.length db-file))
                "still under its own name, still 40 bytes: not quarantined, not replaced")
            (is (empty? (filter #(str/includes? (.getName %) ".corrupt-")
                                (.listFiles dir)))
                "and nothing was renamed out from under it")))))))

(deftest a-damaged-store-of-ours-is-moved-aside-and-rebuilt-said-out-loud
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (let [db-file (home/db-file)]
          ;; A store with a table in it, closed cleanly -- then cut down past its
          ;; header, which is what a killed writer leaves behind. Past the header
          ;; on purpose: cutting INTO it would leave no application id to read,
          ;; and that case is a refusal (the test above), not a rebuild.
          (db/with-transaction
            (fn [^Connection c]
              (with-open [st (.createStatement c)]
                (.execute st "CREATE TABLE before_the_cut (x INTEGER)"))))
          (truncate! db-file db-file 100)
          (is (= 100 (.length db-file)))
          (testing "a read-only question about a wreck refuses and writes nothing"
            (let [before (fingerprint dir "harness.db")
                  thrown (try (db/schema-version) nil (catch Exception e e))]
              (is (= :damaged (:reason (ex-data thrown))))
              (is (= before (fingerprint dir "harness.db")))))
          (let [said    (StringWriter.)
                version (binding [*err* said] (db/migrate!))]
            (testing "the caller is not asked to handle it: the store just works"
              (is (= (db/target-version) version))
              (is (= 0x6861726E (pragma db-file "application_id"))))
            (testing "the damaged file is kept under a name nobody will open"
              (let [leftovers (filter #(str/includes? (.getName %) ".corrupt-")
                                      (.listFiles dir))]
                (is (= 1 (count leftovers)))
                (is (= 100 (.length ^File (first leftovers)))
                    "the wreck is preserved exactly as it was found"))
              (is (empty? (filter #(str/starts-with? (.getName %) "harness.db-wal")
                                  (.listFiles dir)))
                  "no WAL is left to be replayed into the replacement"))
            (testing "and the rebuild is reported rather than passed off as an empty store"
              (is (str/includes? (str said) "damaged"))
              (let [fact (last (db/recoveries))]
                (is (= (str db-file) (:path fact)))
                (is (seq (:moved fact)))))            (testing "the rebuilt store carries the schema and nothing of the wreck"
              (is (= ["hashline_ownership" "hashline_sessions" "hashline_snapshots"
                      "hashline_undo" "projects" "schema_steps" "session_claims" "sessions"
                      "todos"]
                     (db/tables))
                  "the old table is gone; the home's own tables are here, freshly built")
              (db/with-transaction
                (fn [^Connection c]
                  (with-open [st (.createStatement c)]
                    (.execute st "INSERT INTO projects (canonical_path, created_at)
                                  VALUES ('/rebuilt', 1)"))))
              (is (= 1 (:n (first (db/select "SELECT COUNT(*) AS n FROM projects"))))
                  "and it is writable"))))))))

(deftest a-file-short-of-its-own-header-while-a-journal-is-beside-it-is-not-a-wreck
  ;; THE ONE JUDGEMENT IN THIS NAMESPACE THAT CAN BE WRONG ABOUT A HEALTHY FILE. A
  ;; store grows by writing page 1 first -- the header, carrying the new page count
  ;; -- and the pages it counts afterwards, so while a checkpoint is in flight a
  ;; healthy store really is shorter than its own header says. On 2026-09-18 that
  ;; moment was judged three times and three healthy stores were moved aside.
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (db/migrate!)
        (let [db-file (home/db-file)]
          (is (empty? (filter #(re-find #"-(wal|journal)$" (.getName %))
                              (.listFiles dir)))
              "a store at rest keeps no journal beside it")
          (declare-more-pages! db-file 64)
          (testing "with a journal beside it, 'short' is not a verdict"
            (let [wal (io/file (str (.getPath db-file) "-wal"))]
              ;; Only its PRESENCE is read by the judgement; what is inside it is
              ;; noise on purpose. What the file being there means is 'a write is in
              ;; flight', and that is the fact this case turns on.
              (spit wal "not a journal, just something in the way\n")
              (try
                (let [answer (try (db/schema-version) (catch Exception e e))]
                  (is (number? answer)
                      (str "the read-only question answers instead of calling it a wreck"
                           (when-not (number? answer) (str " -- said " (ex-message answer))))))
                (is (empty? (filter #(str/includes? (.getName %) ".corrupt-")
                                    (.listFiles dir)))
                    "and nothing was moved aside")
                (finally (io/delete-file wal true)))))
          (testing "with nothing beside it, the same file is still a wreck"
            (let [thrown (try (db/schema-version) nil (catch Exception e e))]
              (is (= :damaged (:reason (ex-data thrown)))
                  "a file that cannot be growing is still judged by what it says")
              (testing "and the reason quotes the size it judged by, never a fresh look"
                (let [nums (mapv parse-long (re-seq #"\d+" (:why (ex-data thrown))))]
                  (is (= 2 (count nums)))
                  (is (not= (first nums) (second nums))
                      "two readings that agree is the shape of a file mid-write"))))))))))

(deftest what-moves-a-store-aside-is-the-engines-answer-not-a-byte-reading
  ;; A BYTE READING IS NOT A VERDICT. `inspect` reads a snapshot of a file another
  ;; thread may be in the middle of writing, so what it reports is a hint about what to
  ;; expect; the move happens one step below, when SQLite itself says the file cannot be
  ;; used. MEASURED, and it is why this case is about the REASON rather than about some
  ;; store the engine forgives: a file whose header counts more pages than the file holds
  ;; is CORRUPT to SQLite too (`[SQLITE_CORRUPT] The database disk image is malformed`).
  ;; The engine does not rescue that shape -- the WAL check in the judgement above is
  ;; what keeps a live write from being judged at all. What this case pins is that the
  ;; harness now moves a file on the engine's word, and says whose word it was.
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (db/migrate!)
        (declare-more-pages! (home/db-file) 64)  ; the header now counts more than is there
        (testing "the read-only question reports the byte reading, and moves nothing"
          (let [before (fingerprint dir "harness.db")
                thrown (try (db/schema-version) nil (catch Exception e e))]
            (is (= :damaged (:reason (ex-data thrown))))
            (is (str/includes? (ex-message thrown) "bytes alone")
                (str "worded as a reading, not a verdict: " (ex-message thrown)))
            (is (= before (fingerprint dir "harness.db")) "and it moved nothing")))
        (testing "the healing path asks the engine, and the reason it records is the engine's"
          (is (number? (db/migrate!)) "the store works by the time this returns")
          (let [why (:why (last (db/recoveries)))]
            (is (str/includes? why "SQLITE_CORRUPT")
                (str "the reason recorded is SQLite's own: " why))
            (is (not (str/includes? why "declares"))
                "and not the byte reading, which used to be quoted as the cause")))))))

;; ------------------------------------------------------------------ migrations

(deftest migration-steps-run-one-version-at-a-time-and-never-twice
  (let [dir (fresh-root)
        log (atom [])
        v1  [(step "step_1" log)]
        v3  [(step "step_1" log) (step "step_2" log) (step "step_3" log)]]
    (with-root
      dir
      (fn []
        (testing "a store built by an older harness, one step behind"
          (is (= 1 (db/migrate! v1)))
          (is (= ["schema_steps" "step_1"] (db/tables v1)))
          (is (= ["step_1"] @log)))
        (testing "today's harness walks it up, running only the steps it lacks"
          (is (= 3 (db/migrate! v3)))
          (is (= ["schema_steps" "step_1" "step_2" "step_3"] (db/tables v3)))
          (is (= ["step_1" "step_2" "step_3"] @log)
              "each step ran exactly once, in order, and step_1 did not run again"))
        (testing "and there is nothing left to do the next time"
          (is (= 3 (db/migrate! v3)))
          (is (= ["step_1" "step_2" "step_3"] @log))
          (is (= ["schema_steps" "step_1" "step_2" "step_3"] (db/tables v3))))))))

(deftest a-store-written-before-the-removal-memory-still-has-it
  ;; The version 1 -> 2 step, and the reason it is a step rather than a column in
  ;; the CREATE TABLE above: a store that predates it holds sessions whose project
  ;; was bound the ordinary way, and those are exactly the ones a removal would
  ;; silently forget if the new column arrived empty. Any older store is a store
  ;; somebody is using.
  ;;
  ;; Built by running TODAY'S first step and then stopping -- `migrations` is
  ;; public for this, and using the real step is the point: a hand-written copy of
  ;; the version 1 DDL would drift from the thing it claims to be. The rows go in
  ;; through `raw-connection`, NOT through harness.infra.db's own helpers, and that is
  ;; load-bearing rather than incidental: db/select and db/with-transaction open
  ;; the store the normal way, which migrates it first, so seeding with them would
  ;; add the column while the table was still empty and leave nothing to backfill.
  (let [dir   (fresh-root)
        one   [(first db/migrations)]
        bound "bound-before-the-migration"
        free  "never-bound"]
    (with-root
      dir
      (fn []
        (is (= 1 (db/migrate! one)) "a store one version behind")
        (with-open [c (raw-connection (home/db-file))]
          (doseq [sql [(str "INSERT INTO projects (id, canonical_path, created_at)
                               VALUES (1, '/before/the/migration', 1)")
                      (str "INSERT INTO sessions (id, project_id, path, archived, created_at)
                               VALUES ('" bound "', 1, '/before/the/migration', 1, 1)")
                      (str "INSERT INTO sessions (id, project_id, path, archived, created_at)
                               VALUES ('" free "', NULL, NULL, 0, 2)")]]
            (with-open [st (.createStatement c)] (.execute st sql))))
        (is (= (db/target-version) (db/migrate!))
            "today's harness walks it up")
        (let [remembered (fn [id]
                           (:last-project-path
                            (first (db/select "SELECT last_project_path FROM sessions WHERE id = ?" id))))]
          (testing "the session that had a project remembers it, from the join"
            (is (= "/before/the/migration" (remembered bound))))
          (testing "and a session that never had one stays empty -- nothing to remember"
            (is (nil? (remembered free)))))
        (testing "the backfill changed no binding and no flag"
          (let [row (first (db/select "SELECT project_id, path, archived FROM sessions WHERE id = ?" bound))]
            (is (= 1 (:project-id row)))
            (is (= "/before/the/migration" (:path row)))
            (is (= 1 (:archived row)))))))))

(deftest two-chains-share-one-store-without-either-refusing-it
  ;; THIS TEST USED TO ASSERT THE OPPOSITE, and the reversal is the fix.
  ;;
  ;; A schema version was the LENGTH of a chain, so a store built by a longer
  ;; chain looked "newer" to a shorter one and was refused outright. The chains in
  ;; this repository are longer than this one on another branch, appended at the
  ;; same index, and the number the file carried was written by whichever chain
  ;; touched it last -- so the store in the developer's real home went 2 -> 4 under
  ;; one chain and could not be opened by the other AT ALL. Refusing is what broke.
  ;;
  ;; Now each chain runs the steps it recognises and has no opinion about the rest,
  ;; and a step whose work is already present is RECORDED rather than run.
  (let [dir (fresh-root)
        log (atom [])
        v5  (mapv #(step (str "step_" %) log) [1 2 3 4 5])
        v3  [(step "step_1" log) (step "step_2" log) (step "step_3" log)]]
    (with-root
      dir
      (fn []
        (testing "a store built by the longer chain"
          (is (= 5 (db/migrate! v5)))
          (is (= ["schema_steps" "step_1" "step_2" "step_3" "step_4" "step_5"] (db/tables v5))))
        (let [walked @log
              before (fingerprint dir "harness.db")]
          (testing "the shorter chain OPENS it -- it does not refuse it"
            (is (= 3 (db/migrate! v3)) "the steps this chain knows about")
            (is (= walked @log)
                "and it ran nothing: every step it names was already there"))
          (testing "leaving the longer chain's work alone"
            (is (= ["schema_steps" "step_1" "step_2" "step_3" "step_4" "step_5"] (db/tables v3))
                "including the tables only the other chain knows how to make")))
        (testing "and the longer chain still works afterwards"
          (is (= 5 (db/migrate! v5))))))))

(deftest a-store-missing-a-steps-work-gets-it-applied-even-if-its-number-disagrees
  ;; The self-healing half, and the case that used to need a hand-written ALTER:
  ;; a store whose breadcrumb says it is up to date while the column a step adds is
  ;; simply not there. The number is not consulted -- the PROBE is.
  (let [dir (fresh-root)
        log (atom [])
        chain [(step "step_1" log) (step "step_2" log)]]
    (with-root
      dir
      (fn []
        (is (= 2 (db/migrate! chain)))
        ;; Take step_2's table away and forget that it ran, leaving a store that
        ;; claims to be finished and is not.
        (db/with-transaction
         (fn [c]
           (db/execute! c "DROP TABLE step_2")
           (db/execute! c "DELETE FROM schema_steps WHERE name = 'step_2'")))
        (is (= 2 (db/migrate! chain)) "both steps are recorded once the missing one is done")
        (is (= ["step_1" "step_2"]
               (filterv #(clojure.string/starts-with? % "step_") (db/tables chain)))
            "and the missing work was done, without being asked twice")
        (is (= ["step_1" "step_2" "step_2"] @log)
            "step_1 was not re-run; step_2 was, because its probe said so")))))

(deftest a-migration-step-that-throws-leaves-the-store-where-it-was
  ;; Each step and its version bump commit together, so a step that dies leaves
  ;; no half-built table and no version claiming it ran. The store is then
  ;; exactly as usable as it was -- and, because the connection the step died on
  ;; is closed on the way out, the retry can open it.
  (let [dir       (fresh-root)
        log       (atom [])
        one-step  [(step "step_1" log)]
        broken    [(step "step_1" log)
                   {:name     "step_2"
                    :present? (fn [_] false)
                    :run      (fn [^Connection c]
                                (swap! log conj "step_2")
                                (with-open [st (.createStatement c)]
                                  (.execute st "CREATE TABLE step_2 (x INTEGER)"))
                                (throw (ex-info "this step is broken" {})))}]
        two-steps [(step "step_1" log) (step "step_2" log)]]
    (with-root
      dir
      (fn []
        (is (= 1 (db/migrate! one-step)))
        (is (some? (try (db/migrate! broken) nil (catch Exception e e)))
            "a broken step surfaces rather than being swallowed")
        (testing "the failed step left neither its table nor its version"
          (is (= ["schema_steps" "step_1"] (db/tables one-step)))
          (is (= ["step_1" "step_2"] @log)
              "step_1 did not run a second time; step_2 ran and was rolled back"))
        (testing "and the store took the retry"
          (is (= 2 (db/migrate! two-steps)))
          (is (= ["schema_steps" "step_1" "step_2"] (db/tables two-steps))))))))

;; ---------------------------------------------------------------- transactions

(deftest a-transaction-commits-every-statement-or-none-of-them
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (db/with-transaction
          (fn [^Connection c]
            (with-open [st (.createStatement c)]
              (.execute st "CREATE TABLE facts (id TEXT PRIMARY KEY, n INTEGER)")
              (.execute st "INSERT INTO facts VALUES ('kept', 1)"))))
        (let [before (rows "SELECT id, n FROM facts ORDER BY id")]
          (testing "a pair of writes that throws part-way is rolled back whole"
            (is (thrown? Exception
                         (db/with-transaction
                           (fn [^Connection c]
                             (with-open [st (.createStatement c)]
                               (.execute st "INSERT INTO facts VALUES ('half', 2)")
                               (.execute st "UPDATE facts SET n = 99 WHERE id = 'kept'")
                               (throw (ex-info "the pair was not wanted" {})))))))
            (is (= before (rows "SELECT id, n FROM facts ORDER BY id"))
                "both writes are gone: no row was half-added and none was rewritten"))
          (testing "and a transaction that returns normally commits all of it"
            (db/with-transaction
              (fn [^Connection c]
                (with-open [st (.createStatement c)]
                  (.execute st "INSERT INTO facts VALUES ('both', 3)")
                  (.execute st "UPDATE facts SET n = 42 WHERE id = 'kept'"))))
            (is (= [["both" 3] ["kept" 42]]
                   (rows "SELECT id, n FROM facts ORDER BY id")))))))))

(deftest a-failed-transaction-leaves-the-store-open-for-the-next-one
  ;; What a caller actually depends on: a write that blew up must not wedge the
  ;; store. The autocommit flag is restored and the connection closed on every
  ;; path out, so the next caller gets a clean store rather than one still held
  ;; by the transaction that died.
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (db/with-transaction
          (fn [^Connection c]
            (with-open [st (.createStatement c)]
              (.execute st "CREATE TABLE probe (x INTEGER)"))))
        (is (thrown? Exception
                     (db/with-transaction
                       (fn [^Connection c]
                         (with-open [st (.createStatement c)]
                           (.execute st "INSERT INTO probe VALUES (1)")
                           (.execute st "SELECT * FROM no_such_table"))))))
        (db/with-transaction
          (fn [^Connection c]
            (with-open [st (.createStatement c)]
              (.execute st "INSERT INTO probe VALUES (2)"))))
        (is (= [[2]] (rows "SELECT x FROM probe"))
            "the failed transaction's row is gone, and the store took the next write")))))

(defn- hammer
  "Run THREADS threads, each doing ROUNDS read-modify-write transactions on the
  counter. Returns the failures they reported: empty when all is well.

  THE THREADS ARE RELEASED TOGETHER (harness.test-support/start-gate). Started one
  after another they tend to run one after another, and a lost update needs two of
  them to be inside their transactions at the same moment -- so a run without a gate
  passes with the bug in place, which is the one outcome worse than a red test."
  [threads rounds]
  (let [gate     (support/start-gate threads)
        failures (atom [])
        workers  (mapv (fn [_]
                         (Thread.
                          (fn []
                            (try
                              ((:arrive gate))
                              (dotimes [_ rounds]
                                (db/with-transaction
                                  (fn [^Connection c]
                                    (let [n (with-open [st (.createStatement c)
                                                        rs (.executeQuery st "SELECT n FROM counter WHERE id = 1")]
                                              (.next rs)
                                              (.getInt rs 1))]
                                      (with-open [st (.createStatement c)]
                                        (.execute st (str "UPDATE counter SET n = " (inc n)
                                                          " WHERE id = 1")))))))
                              (catch Throwable t
                                (swap! failures conj (ex-message t)))))))
                       (range threads))]
    (doseq [w workers] (.start w))
    (doseq [w workers] (.join w 30000))
    (when (not= threads ((:witness gate)))
      (swap! failures conj (str "only " ((:witness gate)) " of " threads
                                " threads reached the gate")))
    @failures))

(deftest two-threads-writing-do-not-lose-each-others-updates
  ;; A read-then-write across a transaction boundary is where a lost update
  ;; shows up: without BEGIN IMMEDIATE, both threads read the same value and the
  ;; second write erases the first. Real threads and real connections, because
  ;; this is a claim about the lock, and a simulated one would test the
  ;; simulation.
  (let [dir     (fresh-root)
        threads 4
        rounds  25]
    (with-root
      dir
      (fn []
        (db/with-transaction
          (fn [^Connection c]
            (with-open [st (.createStatement c)]
              (.execute st "CREATE TABLE counter (id INTEGER PRIMARY KEY, n INTEGER)")
              (.execute st "INSERT INTO counter VALUES (1, 0)"))))
        (let [failures (hammer threads rounds)]
          (testing "no thread was turned away"
            (is (empty? failures)))
          (testing "every write landed exactly once, so none was erased"
            (is (= [[(* threads rounds)]]
                   (rows "SELECT n FROM counter WHERE id = 1")))))))))

;; ------------------------------------------------------------------ the schema

(defn- seed-a-bound-session!
  "One project with one bound session, written through the schema's own rules.
  Returns the project id."
  [^String project-path ^String session-id]
  (db/with-transaction
    (fn [^Connection c]
      (let [pid (:id (first (db/query c "INSERT INTO projects (canonical_path, created_at)
                                          VALUES (?, 1) RETURNING id"
                                     project-path)))]
        (db/execute! c "INSERT INTO sessions (id, project_id, path, created_at)
                        VALUES (?, ?, ?, 1)"
                     session-id pid project-path)
        pid))))

(deftest unbound-is-one-state-and-the-schema-keeps-it-that-way
  ;; `project_id` and `path` are null together or neither is. A half-bound row
  ;; would say two different things depending on who read it -- `binding-for`
  ;; would answer nothing while the session still carried a path -- so the
  ;; schema refuses it rather than every reader having to decide which half wins.
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (let [pid (seed-a-bound-session! "/proj" "schema-half")]
          (testing "the bound state is accepted"
            (is (= [["schema-half" pid "/proj"]]
                   (mapv (juxt :id :project-id :path)
                         (db/select "SELECT id, project_id, path FROM sessions")))))
          (testing "a row with a path but no project is refused"
            (is (thrown? Exception
                         (db/with-transaction
                           (fn [^Connection c]
                             (db/execute! c "INSERT INTO sessions (id, project_id, path, created_at)
                                             VALUES ('half', NULL, '/orphan', 1)"))))))
          (testing "and so is a row with a project but no path"
            (is (thrown? Exception
                         (db/with-transaction
                           (fn [^Connection c]
                             (db/execute! c "INSERT INTO sessions (id, project_id, path, created_at)
                                             VALUES ('half2', ?, NULL, 1)"
                                          pid))))))
          (testing "unbinding through the schema's own path clears both, not one"
            ;; What the FK does when a project is removed. Written as raw SQL
            ;; because the route that removes projects belongs to a later ticket;
            ;; what THIS ticket owes is the guarantee that when it lands, the
            ;; sessions it unbinds are in the unbound state and not a state
            ;; halfway between.
            (db/with-transaction
              (fn [^Connection c] (db/execute! c "DELETE FROM projects WHERE id = ?" pid)))
            (is (= [["schema-half" nil nil]]
                   (mapv (juxt :id :project-id :path)
                         (db/select "SELECT id, project_id, path FROM sessions")))
                "the path went with the project_id: no orphaned spelling is left")
            (is (not-any? #(= pid (:project-id %)) (db/select "SELECT project_id FROM sessions")))))))))

(deftest a-session-id-cannot-be-null-even-though-sqlite-would-allow-it
  ;; SQLite does not imply NOT NULL from PRIMARY KEY on a text column, and NULLs
  ;; count as distinct in a unique index -- so without the explicit NOT NULL,
  ;; every bind of a nil thread id would quietly add another row.
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (seed-a-bound-session! "/proj" "anchored")
        (is (thrown? Exception
                     (db/with-transaction
                       (fn [^Connection c]
                         (db/execute! c "INSERT INTO sessions (id, created_at) VALUES (NULL, 1)"))))
            "a NULL session id is refused, twice over if it were allowed")))))

(deftest binding-types-this-store-does-not-have-are-named-failures
  ;; The alternative -- stringifying whatever arrives -- would store \":hashline\"
  ;; for a keyword and \"1.5\" for a Double, and both rows would read back as
  ;; plausible strings. A type the schema has no column for is a caller bug, and
  ;; it is told so where the statement is.
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (db/with-transaction
          (fn [^Connection c]
            (db/execute! c "CREATE TABLE probe (x TEXT)")))
        (doseq [bad [:a-keyword 1.5 [1 2] {'a 1}]]
          (let [thrown (try (db/with-transaction
                              (fn [^Connection c]
                                (db/execute! c "INSERT INTO probe (x) VALUES (?)" bad)))
                            nil
                            (catch Exception e e))]
            (is (some? thrown) (str (pr-str bad) " must not be silently stringified"))
            (is (str/includes? (ex-message thrown) "cannot bind")
                (str (pr-str bad) ": the refusal says what went wrong"))))
        (testing "and the supported types all still bind"
          (db/with-transaction
            (fn [^Connection c]
              (db/execute! c "INSERT INTO probe (x) VALUES (?)" "a string")))
          (is (= [["a string"]] (mapv (juxt :x) (db/select "SELECT x FROM probe")))))))))

;; --------------------------------------------------------------- the boundary
(deftest sessions-hold-no-conversation-content
  ;; The column-level half of the boundary `no-table-in-the-store-mirrors-a-log`
  ;; guards at table level. That test catches a whole new table; this one catches
  ;; the cheaper mistake -- adding a `title` or a `summary` column to `sessions`
  ;; so the sidebar can show something prettier. A title IS conversation content,
  ;; and copying it here would be the second truth this store may not hold.
  ;;
  ;; The list is exact, like the table list: a column arrives here only when
  ;; somebody writes down why it is state and not a record.
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (let [declared-state-columns
              {"projects"           #{"id" "canonical_path" "created_at"}
               "sessions"           #{"id" "project_id" "path" "archived" "created_at"
                                      "last_project_path"}
               ;; The anchor store (harness.cap.hashline.store).
               "hashline_snapshots" #{"path" "thread_id" "file_checksum" "line_count"
                                      "anchors" "line_checksums" "served" "updated_at"}
               "hashline_ownership" #{"thread_id" "anchor" "path"}
               "hashline_sessions"  #{"thread_id" "probe" "updated_at"}
               ;; `prior_text`/`resulting_text` are the file's own text on either
               ;; side of one edit, which is why they are named that and not
               ;; `content`: they are the ONE place in this store that holds a
               ;; document, and the name says which document and which side of the
               ;; edit it is. `served` and `mode` are here because an undo restores
               ;; the ANCHORS and the permission bits as well as the text -- see
               ;; harness.cap.hashline.undo.
               "hashline_undo"      #{"path" "prior_text" "bom" "ending" "anchors"
                                      "served" "resulting_text" "mode" "updated_at"}
               ;; The session's task list (harness.todos). STATE, and the reason is
               ;; in the write rather than in the row: `todo_write` sends the
               ;; COMPLETE list every time and the stored value is replaced whole,
               ;; so there is no append and no history to keep. `items` is the list
               ;; as one JSON value -- written whole, read whole, never queried by
               ;; element -- which is why it is one column and not a row per item;
               ;; the name is chosen against the guard below, where `content` would
               ;; both trip it and say less.
               "todos"              #{"thread_id" "items" "updated_at"}
               ;; WHO IS SERVING A CONVERSATION RIGHT NOW (harness.cap.claims): a
               ;; row per live claim, DELETED when the claim is handed back, and
               ;; rewritten in place when one process takes over from a process
               ;; that is gone. It holds no message, no frame and no path -- the
               ;; three facts about the OWNER, the claim's own token, and when it
               ;; was taken -- which is why it is state rather than a record.
               "session_claims"     #{"thread_id" "instance" "token" "pid"
                                      "started_at" "since"}}
              forbidden #"(?i)\b(messages?|frames?|events?|logs?|jsonl|transcripts?|contents?|parts?|titles?|summar(y|ies)|previews?|snippets?|bodies|body)\b"]
          (is (pos? (db/target-version)) "the store has a schema to inspect")
          (doseq [[table columns] declared-state-columns]
            (let [actual (set (map :name (db/select (str "PRAGMA table_info(" table ")"))))]
              (testing (str table " carries exactly the columns it declared")
                (is (= columns actual)))
              (testing (str "and none of " table "'s columns holds conversation content")
                (is (empty? (filter #(re-find forbidden %) actual))
                    (str "these columns look like records rather than state: "
                         (pr-str (filter #(re-find forbidden %) actual))))))))))))

(deftest no-table-in-the-store-mirrors-a-log
  ;; .scratch/project-sidebar decision 2, stated as something a machine checks.
  ;; The store holds what can be REWRITTEN; a message, a frame, or a log line
  ;; cannot be, so none of them may have a table.
  ;;
  ;; TWO assertions, and the exact list is the important one: a table arriving in
  ;; this store is a decision somebody has to make, so a name that is not on the
  ;; list fails this test until whoever added it writes down why it is state and
  ;; not a record. The name pattern alone would pass for anything innocuously
  ;; named -- `turns`, `history`, `archive` -- which is exactly how a log mirror
  ;; would arrive if nobody were watching.
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (let [declared-state-tables #{"projects" "sessions" "schema_steps"
                                      "hashline_snapshots" "hashline_ownership"
                                      "hashline_sessions" "hashline_undo" "todos"
                                      "session_claims"}
              forbidden            #"(?i)\b(messages?|frames?|events?|logs?|jsonl|transcripts?|contents?|parts?)\b"]
          (testing "the store's tables are exactly the ones the home declared"
            (is (= declared-state-tables (set (db/tables)))))
          (testing "and none of them is a place a log could be mirrored into"
            (is (empty? (filter #(re-find forbidden %) (db/tables)))
                (str "these tables look like records rather than state: "
                     (pr-str (filter #(re-find forbidden %) (db/tables)))))))))))

;; ------------------------------------------------- quarantining is a race too
;;
;; EVERY CONNECTION RUNS THE JUDGEMENT, so several threads reach the quarantine at the
;; same moment and only one of them finds the files still there. What the others must
;; not do is fail, or record one incident as two.

(deftest a-quarantine-somebody-else-already-did-is-not-a-failure
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (db/with-transaction
          (fn [^Connection c]
            (with-open [st (.createStatement c)]
              (.execute st "CREATE TABLE before_the_cut (x INTEGER)"))))
        (let [db-file (home/db-file)]
          (truncate! db-file db-file 100)         ; past its header: a wreck, not a refusal
          (let [before     (count (db/recoveries))
                first-call (binding [*err* (StringWriter.)]
                             (#'db/quarantine! db-file "cut down to its header"))]
            (is (seq (:moved first-call)) "the first call takes the store")
            (is (= (inc before) (count (db/recoveries))) "one incident, one fact")
            (testing "and the second one, finding nothing to take, is not a failure"
              (let [said  (StringWriter.)
                    again (binding [*err* said]
                            (#'db/quarantine! db-file "cut down to its header"))]
                (is (empty? (:moved again)) "it moved nothing")
                (is (= (inc before) (count (db/recoveries)))
                    "and it did not record the same loss a second time")
                (is (str/includes? (str said) "already been moved aside")
                    (str "it said out loud what it found: " (str said)))))))))))

(deftest eight-threads-quarantining-one-store-neither-fail-nor-double-count
  (let [dir (fresh-root)]
    (with-root
      dir
      (fn []
        (db/migrate!)
        (truncate! (home/db-file) (home/db-file) 100)
        (let [before  (count (db/recoveries))
              gate    (support/start-gate 8)
              failed  (atom [])
              workers (mapv (fn [_]
                              (future
                                (try
                                  ((:arrive gate))
                                  (db/migrate!)
                                  (catch Throwable t (swap! failed conj (ex-message t))))))
                            (range 8))]
          (doseq [w workers] (is (not= ::timeout (deref w 60000 ::timeout)) "every thread finished"))
          (is (empty? @failed)
              (str "no thread failed to get a usable store: " (pr-str @failed)))
          (is (= (inc before) (count (db/recoveries)))
              (str "one store was lost, so one fact -- not one per thread: "
                   (pr-str (drop before (db/recoveries))))))))))
