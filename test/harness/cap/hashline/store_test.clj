(ns harness.cap.hashline.store-test
  "What anchor-based editing remembers, and the one promise the whole feature
  rests on: that it is still there after the process is gone.

  Every test here goes through `harness.infra.db` on the test runner's own temp home,
  so what they exercise is the real store with the real migration chain -- the
  four tables are built by migration, not by a fixture that could drift from it."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.infra.db :as db]
            [harness.cap.hashline.anchors :as anchors]
            [harness.cap.hashline.store :as store]
            [harness.infra.home :as home]))

(def ^:private path "/tmp/harness-hashline-store-test/file.txt")
(def ^:private other "/tmp/harness-hashline-store-test/other.txt")

;; The store lives at the test runner's root, which is shared with every other
;; namespace in the run. Wipe the hashline tables around each test so a leftover
;; session cannot answer for this one -- the file itself is left alone, because
;; none of these tests ever opens it.
(defn- clean-tables [f]
  (let [wipe (fn []
               (when (.exists (home/db-file))
                 (db/with-transaction
                   (fn [c]
                     (doseq [t ["hashline_snapshots" "hashline_ownership"
                                "hashline_sessions" "hashline_undo"]]
                       (db/execute! c (str "DELETE FROM " t)))))))]
    (wipe)
    (f)
    (wipe)))

(use-fixtures :each clean-tables)

(defn- a-change
  "The answer `anchors/align` gives, plus the two fields the caller adds: the
  file's new whole-file checksum and its new line checksums.

  SESSION is the seed, and it defaults to one fixed name so a test's edits chain
  onto each other -- two calls that mean 'the same session edited twice'. Pass a
  different name for a genuinely different session: a session's anchors come from
  its own seed, and one seed would make two sessions the same session."
  ([content] (a-change content {}))
  ([content {:keys [old-anchors old-checksums spans probe owned session]}]
   (let [checks (anchors/line-checksums content)
         r      (anchors/align {:old-anchors   old-anchors
                                :old-checksums old-checksums
                                :new-checksums checks
                                :spans         spans
                                :path          path
                                :owned         (or owned {})
                                :probe         (or probe (anchors/seed (or session "s")))})]
     (assoc r :file-checksum (anchors/file-checksum checks)
            :line-checksums checks))))

;; ------------------------------------------------------------ the migration

(deftest the-anchor-tables-arrive-by-migration-and-only-add
  ;; The cross-feature edge this ticket carried: the store already had the home's
  ;; two tables, and this step has to add four without disturbing them.
  (let [dir (io/file (System/getProperty "java.io.tmpdir") "hashline-migration")]
    (is (<= 2 (db/target-version)) "the anchor tables are a step in the chain")
    (is (contains? (set (db/tables)) "hashline_snapshots"))
    (is (contains? (set (db/tables)) "hashline_ownership"))
    (is (contains? (set (db/tables)) "hashline_sessions"))
    (is (contains? (set (db/tables)) "hashline_undo"))
    (is (contains? (set (db/tables)) "projects")
        "and the tables that were already there are still there")))

(deftest a-store-with-the-home-tables-migrates-without-losing-a-row
  ;; The version walk, on the chain a real store would have taken: build at
  ;; version 1, put a project in it, migrate to the version this harness speaks,
  ;; and the project is still there with the anchor tables beside it.
  ;;
  ;; This is the one test here that needs a store of its own at a version BELOW
  ;; what the harness speaks, and there is no per-call root to give it one: the
  ;; store's path comes from harness.infra.home. So it moves the root -- and puts back
  ;; exactly what it found, which is the test runner's temp home and not nil. The
  ;; difference matters: restoring nil would send every namespace that runs AFTER
  ;; this one at the developer's real ~/.clj-harness, which is a failure the
  ;; runner detects at the end of the run and reports as an isolation failure.
  (let [step-1  [(first db/migrations)]
        full    db/migrations
        previous (var-get #'home/*root-override*)]
    (let [dir (io/file (System/getProperty "java.io.tmpdir")
                       (str "hashline-migration-walk-" (System/currentTimeMillis)))]
      (.mkdirs dir)
      (alter-var-root #'home/*root-override* (constantly (str dir)))
      (try
        (db/migrate! step-1)
        (is (= 1 (db/schema-version)))
        (is (= ["projects" "schema_steps" "sessions"] (db/tables step-1))
            "on the first step's own chain, the store holds only the home's tables
             -- plus `schema_steps`, which is not a step: every store has it")
        (db/with-transaction
          (fn [c]
            (db/execute! c "INSERT INTO projects (canonical_path, created_at) VALUES ('/kept', 7)")))
        (db/migrate! full)
        (is (= (count full) (db/schema-version)))
        (testing "the row that was already there is untouched"
          (is (= [{:canonical-path "/kept" :created-at 7}]
                 (db/select "SELECT canonical_path, created_at FROM projects"))))
        (testing "and the anchor tables arrived beside it"
          (is (= ["hashline_ownership" "hashline_sessions" "hashline_snapshots"
                  "hashline_undo" "projects" "schema_steps" "sessions" "todos"]
                 (db/tables)))
          (is (= ["hashline_ownership" "hashline_sessions" "hashline_snapshots"
                  "hashline_undo" "projects" "schema_steps" "sessions" "todos"]
                 (db/tables full))))
        (finally
          (alter-var-root #'home/*root-override* (constantly previous))
          (doseq [f (reverse (file-seq dir))] (io/delete-file f true)))))))

;; -------------------------------------------------------------- the reading

(deftest a-session-that-has-seen-nothing-gets-nothing
  ;; The everyday first answer: NOT an error, and not an empty structure that a
  ;; caller has to distinguish from a real one.
  (is (nil? (store/state "never" path)))
  (is (nil? (store/probe-of "never")))
  (is (= {} (store/ownership "never")))
  (is (nil? (store/undo-for path))))

;; -------------------------------------------------------------- the writing

(deftest a-file-is-remembered-and-read-back-line-for-line
  (let [change (a-change "alpha\nbeta\ngamma\n")]
    (store/advance! "t1" path change)
    (let [seen (store/state "t1" path)]
      (is (= (:file-checksum change) (:file-checksum seen)))
      (is (= 3 (:line-count seen)))
      (is (= (:anchors change) (:anchors seen)))
      (is (= (:line-checksums change) (:line-checksums seen)))
      (testing "and the anchors it handed out are recorded as owned"
        (is (= (set (:anchors change)) (set (keys (store/ownership "t1"))))))
      (testing "with the probe it advanced to"
        (is (= (:probe change) (store/probe-of "t1")))))))

(deftest the-stored-view-is-two-sessions-wide-not-one-file-wide
  ;; The key decision in the schema: an anchor is minted for ONE session, so a
  ;; snapshot keyed by path alone would hand session B the anchors A is holding.
  (let [a (a-change "alpha\nbeta\n" {:session "seq-a"})
        b (a-change "alpha\nbeta\n" {:session "seq-b"})]
    (store/advance! "seq-a" path a)
    (store/advance! "seq-b" path b)
    (testing "the two sessions hold different anchors for the same file"
      (is (not= (:anchors a) (:anchors b))
          "they seeded differently, so they minted differently")
      (is (= (set (:anchors a)) (set (keys (store/ownership "seq-a")))))
      (is (= (set (:anchors b)) (set (keys (store/ownership "seq-b"))))))
    (testing "and neither session's read reaches the other's row"
      (is (= (:anchors a) (:anchors (store/state "seq-a" path))))
      (is (= (:anchors b) (:anchors (store/state "seq-b" path)))))
    (testing "advancing one does not disturb the other"
      ;; A real second edit by seq-a, since `:added` may only contain anchors the
      ;; session does not already own -- the store's primary key enforces that,
      ;; which is the exclusivity rule itself.
      (let [second (a-change "alpha\nbeta\ndelta\n"
                             {:session       "seq-a"
                              :old-anchors   (:anchors a)
                              :old-checksums (:line-checksums a)
                              :spans         [{:old-start 2 :old-end 2
                                               :new-start 2 :new-end 3}]
                              :owned         (:owned a)
                              :probe         (:probe a)})]
        (store/advance! "seq-a" path second)
        (is (= (:file-checksum b) (:file-checksum (store/state "seq-b" path)))
            "seq-b's row still says what seq-b last saw")
        (is (= (:file-checksum second) (:file-checksum (store/state "seq-a" path))))))))

(deftest an-edit-frees-what-it-removed-and-owns-what-it-added
  (let [first-change (a-change "alpha\nbeta\ngamma\n")]
    (store/advance! "t1" path first-change)
    (let [checks  (:line-checksums first-change)
          second  (a-change "alpha\nBETA\ngamma\n"
                            {:old-anchors   (:anchors first-change)
                             :old-checksums checks
                             :spans         [{:old-start 1 :old-end 2
                                              :new-start 1 :new-end 2}]
                             :owned         (:owned first-change)
                             :probe         (:probe first-change)})]
      (store/advance! "t1" path second)
      (let [owned (store/ownership "t1")]
        (testing "the anchors that survived are still owned"
          (doseq [a (remove (:freed second) (:anchors second))]
            (is (contains? owned a) (str "survivor " a))))
        (testing "the freed one is gone"
          (doseq [a (:freed second)]
            (is (not (contains? owned a)))))
        (testing "and the newly minted one is owned"
          (doseq [a (:added second)]
            (is (contains? owned a))))))))

(deftest ownership-is-claimed-by-the-schema-not-by-hope
  ;; (thread_id, anchor) is the primary key, so a double claim is refused by the
  ;; store rather than by a check somebody could forget to write.
  (let [change (a-change "alpha\n")]
    (store/advance! "t1" path change)
    (is (thrown? Exception
                 (db/with-transaction
                   (fn [c]
                     (db/execute! c "INSERT INTO hashline_ownership
                                       (thread_id, anchor, path) VALUES (?, ?, ?)"
                                  "t1" (first (:anchors change)) other))))
        "the primary key refuses a second claim on one anchor")))

(deftest the-whole-edit-lands-in-one-transaction
  ;; Ticket 09 rests on this. The ownership and the view are two halves of one
  ;; fact, and a failure after the first half must leave the store exactly as it
  ;; was -- including the probe, which is the third thing the same transaction
  ;; writes.
  (let [first-change (a-change "alpha\nbeta\n")]
    (store/advance! "t1" path first-change)
    (let [before-state (store/state "t1" path)
          before-owned (store/ownership "t1")
          before-probe (store/probe-of "t1")
          second       (a-change "alpha\nBETA\ngamma\n"
                                 {:old-anchors   (:anchors first-change)
                                  :old-checksums (:line-checksums first-change)
                                  :spans         [{:old-start 0 :old-end 2
                                                   :new-start 0 :new-end 3}]
                                  :owned         (:owned first-change)
                                  :probe         (:probe first-change)})]
      (testing "a failure part-way through rolls the whole thing back"
        (is (thrown? Exception
                     (store/advance-with-undo!
                      "t1" path second
                      ;; A row whose own NOT NULL is what fails -- deliberately a
                      ;; failure at the END of the transaction, after the view and
                      ;; the ownership have already been written.
                      {:prior-text nil :bom false :ending "\n" :anchors []
                       :resulting-text "x" :mode nil})))
        (is (= before-state (store/state "t1" path)) "the view is where it was")
        (is (= before-owned (store/ownership "t1")) "so is the ownership")
        (is (= before-probe (store/probe-of "t1")) "and so is the probe"))
      (testing "and the same call with a good record commits all three"
        (store/advance-with-undo!
         "t1" path second
         {:prior-text "alpha\nbeta\n" :bom false :ending "\n"
          :anchors (:anchors first-change) :resulting-text "alpha\nBETA\ngamma\n"
          :mode nil})
        (is (= (:anchors second) (:anchors (store/state "t1" path))))
        (is (= (:probe second) (store/probe-of "t1")))
        (is (= "alpha\nbeta\n" (:prior-text (store/undo-for path))))))))

;; ----------------------------------------------------------- the restart

(deftest anchors-survive-a-restart
  ;; WHY THIS TICKET EXISTS. A session outlives the process that served it -- the
  ;; conversation is in a jsonl log -- so an anchor handed out before a restart
  ;; has to still name its line afterwards. Nothing here simulates a restart: the
  ;; point is that there is no in-memory state to lose, so the assertion is that
  ;; the data a second process would read is the data the first one wrote.
  (let [change (a-change "alpha\nbeta\n")]
    (store/advance! "t1" path change)
    ;; A LATER edit, computed by "another process" from what it read back.
    (let [reloaded (store/state "t1" path)
          checks   (:line-checksums reloaded)
          second   (a-change "alpha\nBETA\n"
                             {:old-anchors   (:anchors reloaded)
                              :old-checksums checks
                              :spans         [{:old-start 1 :old-end 2
                                               :new-start 1 :new-end 2}]
                              :owned         (store/ownership "t1")
                              :probe         (store/probe-of "t1")})]
      (testing "the anchor read back from disk still names its own line"
        (is (= (nth (:anchors change) 0) (nth (:anchors second) 0))
            "line 1 was not touched, so its anchor came through the reload intact"))
      (testing "and a SECOND edit computed from what was read back works"
        (store/advance! "t1" path second)
        (is (= (:anchors second) (:anchors (store/state "t1" path))))
        (testing "with the line the reloaded anchor named left alone"
          (is (= (nth (:anchors change) 0) (nth (:anchors second) 0))))))))

(deftest a-write-forgets-the-file-and-every-anchor-it-had
  ;; `write` is the anchor boundary (ticket 07): the content changed underneath the
  ;; anchors, so every one of them has to go -- not just the ones the write
  ;; happened to change -- or they stay out of circulation for a session that can
  ;; no longer use them.
  (let [change (a-change "alpha\nbeta\n")]
    (store/advance! "t1" path change)
    (is (seq (store/ownership "t1")))
    (store/forget-file! "t1" path)
    (is (nil? (store/state "t1" path)) "no view to validate against")
    (is (= {} (store/ownership "t1")) "and nothing still owned")
    (testing "but the probe is left alone -- it is the session's, not the file's"
      (is (= (:probe change) (store/probe-of "t1"))))
    (testing "and another file's anchors are untouched"
      (let [other-change (a-change "x\n")]
        (store/advance! "t1" other other-change)
        (store/forget-file! "t1" path)
        (is (= (set (:anchors other-change)) (set (keys (store/ownership "t1")))))))))

(deftest undo-is-per-file-and-cleared-on-its-own
  (store/record-undo! path {:prior-text "a\n" :bom false :ending "\n"
                            :anchors ["AAAA"] :resulting-text "b\n" :mode nil})
  (store/record-undo! other {:prior-text "x\n" :bom true :ending "\r\n"
                             :anchors ["AAAB"] :resulting-text "y\n" :mode 1})
  (testing "each file keeps its own"
    (is (= "a\n" (:prior-text (store/undo-for path))))
    (is (true? (:bom (store/undo-for other))))
    (is (= "\r\n" (:ending (store/undo-for other))))
    (is (= 1 (:mode (store/undo-for other)))))
  (testing "only the last edit per file is kept"
    (store/record-undo! path {:prior-text "b\n" :bom false :ending "\n"
                              :anchors ["AAAC"] :resulting-text "c\n" :mode nil})
    (is (= "b\n" (:prior-text (store/undo-for path)))))
  (testing "clearing one leaves the other"
    (store/clear-undo! path)
    (is (nil? (store/undo-for path)))
    (is (some? (store/undo-for other)))))

(deftest a-bom-and-a-mode-round-trip
  (doseq [[bom ending] [[true "\r\n"] [false "\n"]]]
    (store/record-undo! path {:prior-text "x" :bom bom :ending ending
                              :anchors ["AAAA"] :resulting-text "y" :mode 0})
    (is (= bom (:bom (store/undo-for path))))
    (is (= ending (:ending (store/undo-for path))))))

;; ------------------------------------------------------------- the locking

(deftest the-path-lock-is-per-canonical-path-and-reentrant
  (let [dir (io/file (System/getProperty "java.io.tmpdir") "hashline-lock-test")]
    (.mkdirs dir)
    (let [a (str dir "/one.txt")
          b (str dir "/two.txt")]
      (testing "a nested call on the same path does not deadlock"
        (is (= :inner (store/with-path-lock a (fn [] (store/with-path-lock a (fn [] :inner)))))))
      (testing "a different path is a different lock, so it does not wait"
        (is (= :both (store/with-path-lock a
                       (fn [] (store/with-path-lock b (fn [] :both)))))))
      (testing "two spellings of one file take the SAME lock"
        (is (= (store/canonical a) (store/canonical (str dir "/./one.txt"))))
        (is (= (store/canonical a) (store/canonical (str dir "/sub/../one.txt"))))))))

(deftest one-file-is-edited-by-one-thread-at-a-time
  ;; The reason the lock exists: `harness.kernel.loop/drive!` runs a turn's tool calls
  ;; concurrently, so two edits to one file in one turn would otherwise each read
  ;; the same base state and one of them would vanish with both reporting success.
  (let [dir  (io/file (System/getProperty "java.io.tmpdir") "hashline-lock-concurrency")
        file (io/file dir "counter.txt")]
    (io/delete-file dir true)
    (.mkdirs dir)
    (spit file "0" :encoding "UTF-8")
    (let [overlaps (atom 0)
          inside   (atom 0)
          workers  8
          threads  (mapv (fn [_]
                           (Thread.
                            (fn []
                              (dotimes [_ 25]
                                (store/with-path-lock (str file)
                                  (fn []
                                    ;; If the lock works, the count never rises
                                    ;; above 1 -- it is 1 on the first thread in,
                                    ;; which is why the test is `> 1` and not
                                    ;; `pos?`.
                                    (when (> (swap! inside inc) 1)
                                      (swap! overlaps inc))
                                    (Thread/sleep 1)
                                    (swap! inside dec)))))))
                         (range workers))]
      (doseq [^Thread t threads] (.start t))
      (doseq [^Thread t threads] (.join t 30000))
      (is (zero? @overlaps)
          "two threads were inside the same path's critical section at once"))))
