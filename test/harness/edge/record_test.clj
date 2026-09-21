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
    (record/reset-prepare!)
    (record/start!)
    (try (f)
         (finally
           (record/reset-writer!)
           (record/reset-sink!)
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

(deftest the-backlog-and-the-offset-are-both-readable
  (let [f       (log-file-in "lag")
        release (gated-sink!)]
    (try
      (record/append! "lag" f (line {:n 1}))
      (record/append! "lag" f (line {:n 2}))
      (record/append! "lag" f (line {:n 3}))
      (testing "the writer is parked inside the first write, so nothing is durable
                -- the watermark is behind the three lines that were handed over"
        (is (<= (or (record/flushed-seq "lag") 0) 0)
            "`flushed-seq` is nil before the writer has looked at the file, and 0 once
             it has: either way it has written nothing of this thread's")
        (is (< (or (record/flushed-seq "lag") 0) 3)))
      (testing "a line in the queue is a line that is NOT in the record"
        (is (= 3 (record/pending-count "lag")))
        (is (record/pending? "lag")))
      (testing "and the offset the next entry will get counts what is queued"
        (is (= 3 (+ (or (record/flushed-seq "lag") 0) (record/pending-count "lag")))))
      (finally (deliver release true)))
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
  (let [f       (log-file-in "pin")
        release (gated-sink!)]
    (try
      (record/append! "pinned" f (line {:n 1}))
      (sessions/touch! "pinned")
      (let [touched (:touched-at (get (sessions/live) "pinned"))
            later   (+ touched (* 100 sessions/idle-ttl-ms))]
        (is (record/pending? "pinned"))
        (is (= [] (sessions/sweep! later)) "the backlog holds it")
        (is (contains? (sessions/live) "pinned")))
      (finally (deliver release true)))
    (record/flush! 10000)
    (let [touched (:touched-at (get (sessions/live) "pinned"))]
      (is (= ["pinned"] (sessions/sweep! (+ touched (* 100 sessions/idle-ttl-ms))))
          "and once the bytes landed it goes"))))
