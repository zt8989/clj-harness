(ns harness.edge.record-test
  "The record writer (ticket 02): the queue, the offset, the degraded state, and
  the fact that none of it loses a line.

  THE LINE IS THE UNIT HERE, not the frame: a case hands `append!` a string and
  reads the file, so what is under test is the writer and nothing above it. Two
  cases go further on purpose -- `a-session-with-unwritten-lines-is-not-put-away`
  crosses into harness.edge.sessions (the pin this exists for) and
  `a-prepare-that-changes-the-file-re-bases-the-offset` crosses into the seam the
  edge installs (the carry-back)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.edge.record :as record]
            [harness.edge.sessions :as sessions]
            [harness.test-support :as support])
  (:import (java.util.concurrent CountDownLatch TimeUnit)))

;; EACH CASE STARTS FROM A CLEAN WRITER: the writer is process-level (one consumer
;; serves every session), so a case that left a blocked thread or a held gate
;; behind would be a case the NEXT one inherits. The seams are reset on the way in
;; as well as on the way out for the same reason -- a fixture that only cleaned up
;; after itself would still be at the mercy of whatever ran before it.
(use-fixtures :each
  (fn [f]
    (record/reset-writer!)
    (record/reset-sink!)
    (record/reset-forcer!)
    (record/reset-prepare!)
    (record/start!)
    (try (f)
         (finally
           (record/reset-writer!)
           (record/reset-sink!)
           (record/reset-forcer!)
           (record/reset-prepare!)
           (doseq [tid (keys (sessions/live))] (sessions/drop! tid))))))

;; --------------------------------------------------------------- the furniture

(defn- tmp-dir [label] (io/file (support/temp-dir (str "record-" label))))

(defn- log-file-in
  "A file named log.jsonl inside a fresh temp directory made for LABEL."
  [label]
  (io/file (tmp-dir label) "log.jsonl"))

(defn- line
  "One record line as a JSON object -- the writer only ever sees a string, and a
  reader (or an assertion) only ever sees one too."
  [m]
  (str (json/write-str m) "\n"))

(defn- written
  "The lines of F, parsed. A line that does not parse FAILS the caller's read
  rather than being dropped: this file is written by one thread, one line at a
  time, so a torn line is not a fact about reading a live log -- there is no such
  thing here -- it is the bug the old lock was holding off."
  [f]
  (if (.exists f)
    (mapv #(json/read-str % :key-fn keyword) (str/split-lines (slurp f :encoding "UTF-8")))
    []))

(defn- working-sink!
  "The real write, installed explicitly. Tests that need to hold, fail, or count
  a write replace this; a test that just wants the file to appear uses it."
  []
  (record/set-sink! (fn [^java.io.File f line]
                      (spit f line :append true :encoding "UTF-8"))))

(defn- gated-sink!
  "A sink that blocks until the returned promise is delivered, and then writes.
  THIS IS HOW 'queued but not written' IS MADE OBSERVABLE without a sleep: the
  consumer is parked inside the write, so the queue behind it is a fact."
  []
  (let [release (promise)]
    (record/set-sink! (fn [^java.io.File f line]
                        (deref release 10000 nil)
                        (spit f line :append true :encoding "UTF-8")))
    release))

;; ----------------------------------------------- 1. in the order they were handed over

(deftest lines-land-in-the-order-they-were-handed-over
  ;; EIGHT WRITERS, ONE SESSION. Nothing about an interleaving is asserted --
  ;; which of two simultaneous calls goes first is not a fact anybody can have --
  ;; but each writer's OWN order is, and that is the property a second writer (or
  ;; a lost line) would break. Every line is also parsed, so a torn append shows
  ;; up as a failure rather than as a shorter file.
  (let [f       (log-file-in "order")
        writers 8
        per     25
        go      (CountDownLatch. 1)
        done    (CountDownLatch. writers)
        threads (mapv (fn [w]
                        (doto (Thread.
                               (fn []
                                 (.await go)
                                 (dotimes [i per]
                                   (record/append! "order" f (line {:writer w :i i})))
                                 (.countDown done)))
                          (.setDaemon true)
                          (.start)))
                      (range writers))]
    (.countDown go)
    (is (.await done 10 TimeUnit/SECONDS) "every writer finished handing lines over")
    (record/flush! 10000)
    (let [lines (written f)]
      (testing "every line that was handed over is on disk, once"
        (is (= (* writers per) (count lines))))
      (testing "and each writer's lines keep that writer's order"
        (doseq [w (range writers)]
          (is (= (vec (range per))
                 (->> lines (filter #(= w (:writer %))) (mapv :i)))
              (str "writer " w " wrote its lines out of order"))))
      (testing "no line was merged with its neighbour"
        (is (every? map? lines))))))

;; ---------------------------------------------- 2 & 3. the backlog, and the offset

(deftest the-offset-comes-back-with-the-write
  ;; REWRITTEN FOR ADR 0007 (the record is written synchronously). What used to be 'the
  ;; backlog is readable behind a parked writer' is now 'the write is done when the call
  ;; returns, and the offset comes back WITH it'. The gate that pinned the async behaviour
  ;; is gone with it: there is no queue to park behind any more.
  (let [f       (log-file-in "lag")
        offsets [(record/append! "lag" f (line {:n 1}))
                 (record/append! "lag" f (line {:n 2}))
                 (record/append! "lag" f (line {:n 3}))]]
    (testing "every call answered the offset its own line got"
      (is (= [0 1 2] offsets)))
    (testing "and nothing is behind the record -- nothing was queued"
      (is (= 0 (record/pending-count "lag")))
      (is (false? (record/pending? "lag"))))
    (testing "once the writer is let go, the backlog drains and the offset catches up"
      (is (= {:pending 0 :degraded {}} (record/flush! 10000))))
    (testing "THE OFFSET IS THE RECORD'S OWN OFFSET: the file holds exactly that
              many lines, so the nth entry's seq is the nth line"
      (is (= 3 (record/flushed-seq "lag")))
      (is (= 3 (count (written f))))
      (is (= 0 (record/pending-count "lag"))))
    (testing "and the lines are the ones handed over, in order"
      (is (= [1 2 3] (mapv :n (written f)))))))

(deftest the-offset-counts-what-was-already-in-the-file
  ;; A session is rebuilt from a record that already holds lines, so the first
  ;; line this process writes is NOT offset 1. This is the half of ticket 05's
  ;; contract that has to be true before ticket 05 exists.
  (let [f (log-file-in "base")]
    (working-sink!)
    (spit f (str (line {:old 1}) (line {:old 2})) :encoding "UTF-8")
    (record/append! "base" f (line {:new 3}))
    (record/flush! 10000)
    (is (= 3 (record/flushed-seq "base"))
        "two lines were already there; the line this process wrote is the third")
    (is (= 3 (count (written f))))))

(deftest a-prepare-that-changes-the-file-re-bases-the-offset
  ;; The carry-back appends a leftover segment (and an audit line about it) BEFORE
  ;; the line being written. If the offset did not move with it, every sequence
  ;; number after a carry would name the wrong line -- which is the shape of bug
  ;; ticket 05's replay-by-offset cannot survive.
  (let [f     (log-file-in "prepare")
        first? (atom true)]
    (working-sink!)
    (record/prepare-with! (fn [_tid ^java.io.File file]
                            ;; once: the "leftover segment" arrives
                            (when (compare-and-set! first? true false)
                              (spit file (line {:carried 1}) :append true :encoding "UTF-8")
                              true)))
    (record/append! "prepare" f (line {:mine 2}))
    (record/flush! 10000)
    (testing "the carried line is counted, so the offset still names the last line"
      (is (= 2 (record/flushed-seq "prepare")))
      (is (= 2 (count (written f))))
      (is (= [{:carried 1} {:mine 2}] (written f))))
    (testing "and a prepare that changed nothing does not re-base again"
      (record/append! "prepare" f (line {:mine 3}))
      (record/flush! 10000)
      (is (= 3 (record/flushed-seq "prepare")))
      (is (= 3 (count (written f)))))))

;; --------------------------------------------------------- 4. a write that fails

(deftest a-write-that-fails-degrades-the-thread-and-keeps-its-lines
  (let [f (log-file-in "degraded")]
    (working-sink!)
    (record/append! "bad" f (line {:n 1}))
    (record/flush! 10000)
    (record/set-sink! (fn [_f _line] (throw (java.io.IOException. "disk is full"))))
    (record/append! "bad" f (line {:n 2}))
    (record/append! "bad" f (line {:n 3}))
    (record/flush! 500)
    (testing "the thread is degraded, by name, with how much is waiting behind it"
      (let [d (record/degraded "bad")]
        (is (some? d))
        (is (str/includes? (str (:reason d)) "disk is full"))
        (is (= 2 (:pending d)))
        (is (= (.getAbsolutePath f) (:file d)))))
    (testing "NOTHING IS SKIPPED: the failed line and everything behind it are still
              queued, so the record is an ordered prefix and not a hole"
      (is (= [1] (mapv :n (written f))))
      (is (= 2 (record/pending-count "bad"))))
    (testing "and the session is held, because its bytes are not all on disk"
      (is (record/pending? "bad")))
    (testing "a healthy disk lets it resume AT the failed line"
      (working-sink!)
      (record/retry! "bad")
      (is (= {:pending 0 :degraded {}} (record/flush! 10000)))
      (is (nil? (record/degraded "bad")))
      (is (= [1 2 3] (mapv :n (written f)))
          "no line lost, none duplicated, and none out of order"))))

(deftest a-broken-thread-does-not-hold-up-a-healthy-one
  ;; The failure is per thread: one session's record directory being unwritable is
  ;; not every session's -- and a writer that stopped on the first failure would
  ;; make it so.
  (let [bad  (log-file-in "one-bad")
        good (log-file-in "one-good")]
    (working-sink!)
    (record/append! "good" good (line {:n 1}))
    (record/flush! 10000)
    (record/set-sink! (fn [^java.io.File f line]
                        (if (= (.getParentFile f) (.getParentFile bad))
                          (throw (java.io.IOException. "unwritable"))
                          (spit f line :append true :encoding "UTF-8"))))
    (record/append! "bad" bad (line {:n 1}))
    (record/append! "good" good (line {:n 2}))
    (record/flush! 10000)
    (is (some? (record/degraded "bad")))
    (is (= 2 (count (written good))) "the healthy session was written while the other failed")))

;; ------------------------------------------------------- 5. the process exits

(deftest shutdown-drains-what-is-queued
  ;; A process that exits normally must not lose its tail. The write is made a
  ;; little slow so that the drain has something to drain -- otherwise the test
  ;; would pass on an empty queue.
  (let [f (log-file-in "shutdown")]
    (record/set-sink! (fn [^java.io.File file l]
                        (Thread/sleep 5)
                        (spit file l :append true :encoding "UTF-8")))
    (dotimes [i 40] (record/append! "exit" f (line {:n i})))
    (let [outcome (record/shutdown!)]
      (is (= 0 (:pending outcome)) "the queue was emptied before the process let go")
      (is (empty? (:degraded outcome)))
      (is (= (vec (range 40)) (mapv :n (written f)))))))

;; -------------------------------------- 6. one writer, so a line cannot be torn

(deftest the-half-line-guarantee-outlives-the-lock
  ;; `log-lock`'s docstring said a half-written line is not a smaller record, it is
  ;; a broken file. That guarantee is now the single consumer's: `spit` with
  ;; `:append true` is one write, and only one thread ever makes it. Two writers
  ;; handing over lines at the same moment is the case that would break it, so it
  ;; is the case here.
  (let [f       (log-file-in "torn")
        writers 4
        per     10
        done    (CountDownLatch. writers)]
    (working-sink!)
    (dotimes [w writers]
      (doto (Thread. (fn []
                       (dotimes [i per] (record/append! "torn" f (line {:w w :i i})))
                       (.countDown done)))
        (.setDaemon true)
        (.start)))
    (is (.await done 10 TimeUnit/SECONDS) "every writer finished handing lines over")
    (record/flush! 10000)
    (let [text (slurp f :encoding "UTF-8")]
      (testing "every line of the file parses on its own"
        (is (= (* writers per) (count (str/split-lines text))))
        (is (every? map? (mapv #(json/read-str % :key-fn keyword)
                               (str/split-lines text)))))
      (testing "and it ends at a line boundary"
        (is (str/ends-with? text "\n"))))))

;; ---------------------------------------------------- the pin ticket 01 asked for

(deftest a-session-with-unwritten-lines-is-not-put-away
  ;; THIS IS THE JOINT BETWEEN TICKETS 01 AND 02. `sweep!` may only put a session
  ;; away when its bytes are all on disk; the writer is the only thing that knows,
  ;; and `start!` is where it tells the table. Without this, putting a session away
  ;; would mean rebuilding from a record behind it -- silently.
  (let [f (log-file-in "pin")]
    ;; REWRITTEN FOR ADR 0007: a HEALTHY thread has nothing pending (the write is done when
    ;; the call returns), so what holds a session away from `sweep!` is the one case that
    ;; still holds lines -- a write that FAILED. Same joint, same property: 'put away' may
    ;; not mean rebuilding from a record that is missing bytes.
    (record/set-sink! (fn [_ _] (throw (ex-info "the disk is full" {}))))
    (record/append! "pinned" f (line {:n 1}))
    (sessions/touch! "pinned")
    ;; WHAT THIS CASE OWNS IS THE RECORD'S HALF: a line that could not be written is HELD (so
    ;; `pending?` is the pin `sweep!` asks about) and the failure is nameable. WHAT THE SWEEP
    ;; DOES WITH THE PIN IS `harness.edge.sessions-test`'s case -- it asserts the joint from
    ;; the table's side, and it stays green through this rewrite.
    (is (record/pending? "pinned") "the line that could not be written is held")
    (is (= 1 (record/pending-count "pinned")))
    (is (some? (record/degraded "pinned")) "and the failure is nameable")
    ;; THE DISK COMES BACK: `retry!` lands the held line, in place, and nothing is behind any
    ;; more -- the offset it lands at is the one the failed line would have got.
    (working-sink!)
    (record/retry! "pinned")
    (is (false? (record/pending? "pinned")) "the held line landed")
    (is (nil? (record/degraded "pinned")) "and the thread is healthy again")
    (is (= 1 (record/flushed-seq "pinned")) "one line in the file, at offset 0")
    (is (= {:pending 0 :degraded {}} (record/flush! 10000)))))

;; ------------------------------------------------- 7. the promise (ticket 04)

(defn- counting-forcer!
  "A forcer that touches no disk and REMEMBERS every file it was asked about. The promise leaves
  no trace a file can show -- the bytes are in the page cache either way -- so the seam
  (`set-forcer!`) is the only place the THREE MOMENTS can be told apart." []
  (let [asked (atom [])]
    (record/set-forcer! (fn [^java.io.File f] (swap! asked conj (.getAbsolutePath f)) nil))
    asked))

(deftest the-write-asks-for-the-promise-every-n-lines
  ;; MOMENT ONE (ADR 0007 decision 2). `fsync-every` is moved DOWN rather than writing 64 lines:
  ;; what is under test is WHEN the promise is asked for, not the number.
  (let [f     (log-file-in "every")
        asked (counting-forcer!)]
    (with-redefs [record/fsync-every 3]
      (dotimes [i 7] (record/append! "every" f (line {:n i}))))
    (testing "one promise per three lines, and none for the tail that is not due yet"
      (is (= 2 (count @asked)))
      (is (every? #(= (.getAbsolutePath f) %) @asked)
          "the promise is about the FILE, which is what an fsync has always been about"))
    (testing "and the promise is not the write: all seven lines are in the record"
      (is (= 7 (count (written f)))))))

(deftest the-session-going-away-is-when-the-promise-is-asked-for-by-hand
  ;; MOMENT TWO: the verb the `:put-away!` seam calls -- what the sweeper and `drop!` reach. THAT THE
  ;; SEAM IS REACHED IS `harness.edge.sessions-test`'s case; this is what the verb does.
  (let [f     (log-file-in "put-away")
        asked (counting-forcer!)]
    (record/append! "away" f (line {:n 1}))
    (is (empty? @asked) "nothing was asked for yet: the pace is N lines, not every line")
    (is (true? (record/fsync! "away")) "the promise is asked for, and answered")
    (is (= [(.getAbsolutePath f)] @asked))
    (testing "a thread this process wrote nothing for is not a failure -- there is nothing to promise"
      (is (true? (record/fsync! "never-written")))
      (is (= 1 (count @asked))))))

(deftest the-process-leaving-promises-every-file-it-wrote
  ;; MOMENT THREE: `shutdown!`. Two threads, a file each -- and each file asked about ONCE, because a
  ;; file is what a promise is about rather than a thread.
  (let [a     (log-file-in "exit-a")
        b     (log-file-in "exit-b")
        asked (counting-forcer!)]
    (record/append! "a" a (line {:n 1}))
    (record/append! "b" b (line {:n 2}))
    (is (empty? @asked))
    (is (= {:pending 0 :degraded {}} (record/shutdown!)))
    (is (= #{(.getAbsolutePath a) (.getAbsolutePath b)} (set @asked)))))

(deftest a-refused-promise-does-not-fail-the-line-and-is-not-forgotten
  ;; THE PROMISE IS A SECOND, WEAKER FACT, and these are the two answers it must not be confused
  ;; with: the line IS in the record (a reader sees it, the file's length says so) and the file would
  ;; not survive a crash. `:fsync` carries the second and the LEVEL stays at L0 -- the record is
  ;; complete, and 'complete' is what the levels are about.
  (let [f (log-file-in "unforced")]
    (record/set-forcer! (fn [_] "the platter said no"))
    (is (some? (record/append! "unforced" f (line {:n 1}))) "the line landed")
    (is (= 1 (count (written f))))
    (is (false? (record/fsync! "unforced")) "and the promise was refused, by name")
    (let [h (record/health "unforced")]
      (is (= 0 (:level h)) "the record is complete: nothing is held")
      (is (= "the platter said no" (get-in h [:fsync :why])))
      (is (= 1 (get-in h [:fsync :failures]))))))

(deftest the-four-levels-and-the-sentence-each-one-says
  ;; TICKET 04's GRADED ANSWER, walked from L0 to L3 in one thread. A hiccup is not an incident (L1
  ;; vs L2), and L3 is not an age at all: it is the moment the lines stop being recoverable, which is
  ;; why it can only be said on the way out.
  (let [f (log-file-in "levels")]
    (testing "L0 -- nothing is held, so there is nothing to say about it"
      (record/append! "lvl" f (line {:n 1}))
      (is (= 0 (:level (record/health "lvl"))))
      (is (= :ok (:state (record/health "lvl")))))
    (testing "L1 -- behind, and the sentence names the reason the disk gave"
      (record/set-sink! (fn [_ _] (throw (java.io.IOException. "disk is full"))))
      (record/append! "lvl" f (line {:n 2}))
      (let [h (record/health "lvl")]
        (is (= 1 (:level h)))
        (is (= :behind (:state h)))
        (is (= 1 (:pending h)))
        (is (str/includes? (:says h) "disk is full"))))
    (testing "L2 -- the door was used and the disk refused AGAIN"
      (record/retry! "lvl")
      (let [h (record/health "lvl")]
        (is (= 2 (:level h)))
        (is (= :stuck (:state h)))
        (is (= 1 (:retries h)))
        (is (str/includes? (:says h) "stuck"))))
    (testing "L3 -- torn down with the lines still held"
      (record/shutdown!)
      (let [h (record/health "lvl")]
        (is (= 3 (:level h)))
        (is (= :lost (:state h)))
        (is (str/includes? (:says h) "SHORTER THAN THE CONVERSATION"))))))

(deftest a-retry-that-goes-through-clears-the-incident
  ;; THE OTHER SIDE OF L2: the door was used, the disk took the lines, and the thread is not merely
  ;; behind again -- it is well. A level that only ever went up would be a level nobody could act
  ;; on.
  (let [f (log-file-in "recovers")]
    (record/set-sink! (fn [_ _] (throw (java.io.IOException. "disk is full"))))
    (record/append! "recovers" f (line {:n 1}))
    (record/retry! "recovers")
    (is (= 2 (:level (record/health "recovers"))))
    (working-sink!)
    (record/retry! "recovers")
    (let [h (record/health "recovers")]
      (is (= 0 (:level h)))
      (is (= 0 (:retries h)) "a thread that caught up is not on its second strike")
      (is (= [1] (mapv :n (written f)))))))

(deftest the-table-says-what-this-process-put-in-the-record
  ;; TICKET 05's TABLE. Each number is asserted where it comes from rather than as one whole-map
  ;; equality: the shape may grow, the facts may not drift.
  (let [f (log-file-in "metrics")]
    (dotimes [i 3] (record/append! "m" f (line {:n i})))
    (let [t   (record/metrics)
          row (get-in t [:threads "m"])]
      (is (= 3 (:lines row)))
      (is (= 3 (get-in t [:totals :lines])))
      (is (= (.length f) (:bytes row))
          "the file's own length: the table asks the filesystem, not the frame loop")
      (is (= 0 (:level row)))
      (is (= 0 (:pending row)))
      (is (= (.getAbsolutePath f) (:file row))))
    (testing "a held line is in the same table, at the level it puts the thread at"
      (record/set-sink! (fn [_ _] (throw (java.io.IOException. "disk is full"))))
      (record/append! "m" f (line {:n 9}))
      (let [row (get-in (record/metrics) [:threads "m"])]
        (is (= 1 (:pending row)))
        (is (= 1 (:level row)))
        (is (= 3 (:lines row)) "a line that was never written is not counted as written")))
    (testing "and a promise that went through is counted, apart from one that was refused"
      (record/set-forcer! (fn [_] nil))
      (record/fsync! "m")
      (record/set-forcer! (fn [_] "the platter said no"))
      (record/fsync! "m")
      (let [row (get-in (record/metrics) [:threads "m"])]
        (is (= 1 (:promises row)))
        (is (= 1 (:fsync-failures row)))))
    (testing "and every thread this process wrote has a row"
      (record/append! "other" (log-file-in "metrics-other") (line {:n 1}))
      (is (= #{"m" "other"} (set (keys (:threads (record/metrics))))))
      (is (= 2 (get-in (record/metrics) [:totals :threads]))))))
