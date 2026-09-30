(ns harness.edge.projection-test
  "The content projection (ADR 0008): the record is the truth, the store holds a copy, and the copy can
  be thrown away and made again.

  EVERY CASE BUILDS ITS OWN HOME (`support/with-temp-env`): the projection reads the log TREE and writes
  the store, so a shared root would mean a test reading the last one's logs."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.project :as project]
            [harness.edge.projection :as projection]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.infra.stream :as stream]
            [harness.test-support :as support]))

;; --------------------------------------------------------------- the furniture

(defn- line
  "One record row as the writer emits it: an envelope with `ts`/`runId` outside the payload."
  [m]
  (str (json/write-str m) "\n"))

(defn- message-line [run-id ts source role payload]
  (line (cond-> {:ts ts :runId run-id :type "message" :source source :payload payload}
          (contains? payload :id) (assoc :id (:id payload)))))

(defn- write-log!
  "Plant a conversation's record in the tree this home uses for an UNBOUND session, and register the
  session -- which is what makes the projection look at it at all (the store decides which
  conversations exist)."
  [session-id rows]
  (project/register-session! session-id)
  (let [f (home/log-file (io/file (home/projects-dir) "unbound") session-id)]
    (.mkdirs (.getParentFile f))
    (spit f (apply str rows) :encoding "UTF-8")
    f))

(defn- messages-of [session-id]
  (db/select "SELECT seq, run_id, source, role, content, reasoning, tool_calls, at
                FROM messages WHERE session_id = ? ORDER BY seq"
             session-id))

(defn- calls-of [session-id]
  (db/select "SELECT seq, call_id, name, arguments, result FROM tool_calls
                WHERE session_id = ? ORDER BY seq, call_id"
             session-id))

(defn- eventually
  "PRED, asked until it answers, up to ~2 seconds. THE TRIGGER IS ASYNCHRONOUS BY DESIGN (a
  coalesced round on a thread of its own), so a case about it must WAIT for the round rather than
  race it -- a fixed `Thread/sleep` here would be a test that goes red on a slow machine."
  [pred]
  (loop [tries 0]
    (cond
      (pred) true
      (>= tries 100) false
      :else (do (Thread/sleep 20) (recur (inc tries))))))

(use-fixtures :each
  (fn [f]
    (support/with-temp-env [_root _home]
      ;; A CASE THAT STARTS THE TRIGGER MUST NOT INHERIT THE PREVIOUS CASE'S MARKS, and a case
      ;; about coalescing has to be able to count the rounds IT caused (`projection/pending`).
      (projection/reset-trigger!)
      (f))))

;; --------------------------------------------------------------- the copy

(deftest the-copy-says-what-the-record-says
  (let [sid  "pj-basic"
        rows [(message-line "r1" 1 "client" "user" {:role "user" :content "hi"})
              (message-line "r1" 2 "model" "assistant" {:role "assistant" :content "the answer"
                                                        :reasoning_content "THINKING"})]]
    (write-log! sid rows)
    (let [{:keys [sessions rows]} (projection/project!)]
      (is (= 1 sessions) "one session had a log to read")
      (is (= 2 rows) "and both message rows were projected"))
    (testing "one row per message row, keyed by the line it arrived in"
      (is (= [{:seq 0 :run-id "r1" :source "client" :role "user" :content "hi"
               :reasoning nil :tool-calls 0 :at 1}
              {:seq 1 :run-id "r1" :source "model" :role "assistant" :content "the answer"
               :reasoning "THINKING" :tool-calls 0 :at 2}]
             (messages-of sid))))
    (testing "and NOTHING of the record's non-message rows"
      ;; an event row is not a message -- the projection is of what was said, not of the traffic.
      (is (= 2 (count (db/select "SELECT seq FROM messages WHERE session_id = ?" sid))))
      (is (= [] (calls-of sid))))))

(deftest projecting-again-writes-the-same-thing-and-reads-nothing
  (let [sid "pj-twice"]
    (write-log! sid [(message-line "r1" 1 "client" "user" {:role "user" :content "hi"})])
    (projection/project!)
    (let [first-pass (messages-of sid)]
      (testing "a second pass over a log with nothing new reads no rows AND TOUCHES NO SESSION"
        ;; `:sessions` COUNTS WHAT THE ROUND HAD SOMETHING TO DO FOR, which is what 'only the ones that
        ;; changed' means (`.scratch/memory-hygiene/` ticket 04). It used to count every conversation the
        ;; round could read, so 'nothing to do' and 'one conversation read' were the same answer -- and
        ;; every one of those reads cost two store connections.
        (is (= {:sessions 0 :rows 0 :bytes 0 :skipped 0} (projection/project!))))
      (is (= first-pass (messages-of sid))))
    (testing "and a line appended later is picked up ONCE, at its own offset"
      (let [f (home/log-file (io/file (home/projects-dir) "unbound") sid)]
        (spit f (message-line "r1" 3 "model" "assistant" {:role "assistant" :content "more"})
              :append true :encoding "UTF-8")
        (is (= 1 (:rows (projection/project!))))
        (is (= 0 (:rows (projection/project!))) "and the pass after it finds nothing new"))
      (is (= ["hi" "more"] (mapv :content (messages-of sid)))))))

(deftest a-half-written-last-line-is-not-a-row
  ;; The writer appends whole lines, but a reader can catch the newest one mid-flush. The projection
  ;; must leave it for the next pass: half a row is either a failure or -- worse -- a row of the wrong
  ;; shape, and the byte offset is what makes 'leave it' safe.
  (let [sid "pj-torn"
        f   (write-log! sid [(message-line "r1" 1 "client" "user" {:role "user" :content "hi"})])]
    (spit f (subs (message-line "r1" 2 "model" "assistant" {:role "assistant" :content "half"}) 0 20)
          :append true :encoding "UTF-8")
    (is (= {:sessions 1 :rows 1 :bytes 98 :skipped 0} (projection/project!))
        "the complete line is projected and nothing is claimed for the torn one")
    (is (= 1 (count (messages-of sid))))
    (let [offset (:byte-offset (first (db/select "SELECT byte_offset FROM projection_offsets
                                                    WHERE session_id = ?" sid)))]
      (spit f (subs (message-line "r1" 2 "model" "assistant" {:role "assistant" :content "half"}) 20)
            :append true :encoding "UTF-8")
      (is (= 1 (:rows (projection/project!))) "and the line becomes projectable once it is whole")
      (is (< (long offset) (:byte-offset (first (db/select "SELECT byte_offset FROM projection_offsets
                                                             WHERE session_id = ?" sid)))))
      (is (= ["hi" "half"] (mapv :content (messages-of sid)))))))

(deftest a-tool-call-and-its-result-find-each-other
  (let [sid  "pj-tools"
        call {:id "c1" :type "function" :function {:name "read" :arguments "{\"path\":\"a\"}"}}]
    (write-log! sid
                [(message-line "r1" 1 "model" "assistant"
                               {:role "assistant" :content "" :tool_calls [call]})
                 (message-line "r1" 2 "tool" "tool"
                               {:role "tool" :tool_call_id "c1" :content "the file"})])
    (projection/project!)
    (is (= [{:seq 0 :call-id "c1" :name "read" :arguments "{\"path\":\"a\"}" :result "the file"}]
           (calls-of sid)))))

(deftest a-log-that-shrank-or-moved-starts-over
  (let [sid "pj-moved"
        f   (write-log! sid [(message-line "r1" 1 "client" "user" {:role "user" :content "hi"})
                             (message-line "r1" 2 "model" "assistant" {:role "assistant" :content "one"})])]
    (projection/project!)
    (is (= 2 (count (messages-of sid))))
    (testing "a shorter file is not this projection's continuation"
      (spit f (message-line "r1" 1 "client" "user" {:role "user" :content "replaced"})
            :encoding "UTF-8")                     ; truncating write: one row, new content
      (is (= 1 (:rows (projection/project!))))
      (is (= ["replaced"] (mapv :content (messages-of sid)))
          "the old rows are gone, and the new file's are the whole story"))))

(deftest the-lag-is-a-number
  (let [sid "pj-lag"
        f   (write-log! sid [(message-line "r1" 1 "client" "user" {:role "user" :content "hi"})])]
    (testing "a session never projected is behind by its whole file"
      (is (= (.length f) (:total (projection/lag)))))
    (projection/project!)
    (is (= 0 (:total (projection/lag))) "and a projected one is not behind at all")
    (testing "a line appended since the last pass is what the number counts"
      (let [appended (message-line "r1" 2 "model" "assistant" {:role "assistant" :content "more"})]
        (spit f appended :append true :encoding "UTF-8")
        (is (= (alength (.getBytes appended "UTF-8"))
               (:total (projection/lag))))))))

;; --------------------------------------------------------------- rebuild

(deftest rebuilding-answers-row-for-row-what-was-there
  ;; ADR 0008 DECISION 6 IS THIS CASE. 'Recomputable' is the only thing that makes a second copy of a
  ;; conversation's content acceptable, so it is an ACTION here and a test rather than a claim in a
  ;; docstring.
  (let [sid  "pj-rebuild"
        rows [(message-line "r1" 1 "client" "user" {:role "user" :content "hi"})
              (message-line "r1" 2 "model" "assistant"
                            {:role "assistant" :content "the answer"
                             :tool_calls [{:id "c1" :type "function"
                                           :function {:name "read" :arguments "{}"}}]})
              (message-line "r1" 3 "tool" "tool"
                            {:role "tool" :tool_call_id "c1" :content "the file"})]]
    (write-log! sid rows)
    (projection/project!)
    (let [before (messages-of sid)
          calls  (calls-of sid)]
      (is (seq before))
      (projection/rebuild! sid)
      (is (= before (messages-of sid)))
      (is (= calls (calls-of sid)))
      (testing "and a whole-home rebuild answers the same thing too"
        (projection/rebuild!)
        (is (= before (messages-of sid)))
        (is (= calls (calls-of sid)))))))

;; --------------------------------------------------------------- what a round costs

(deftest a-round-touches-only-what-changed-and-asks-the-store-once
  ;; THE NUMBER THAT TURNED THIS CLOCK OFF (`.scratch/memory-hygiene/` ticket 04): a round used to
  ;; build TWO store connections PER CONVERSATION per tick -- one to ask the offset, then one to ask
  ;; it again while reading -- and every one of those opens the file and walks the migration chain.
  ;; Measured on a live process: 3,914 ms a round against a 2,000 ms interval, 46% of its CPU, for a
  ;; home where nothing had changed.
  (let [ids (mapv #(str "pj-cost-" %) (range 5))]
    (doseq [sid ids]
      (write-log! sid [(message-line "r1" 1 "client" "user" {:role "user" :content "hi"})]))
    (projection/project!)
    (let [before (db/connections-made)
          answer (projection/project!)
          spent  (- (db/connections-made) before)]
      (testing "nothing changed, so no conversation is touched"
        (is (= 0 (:sessions answer))))
      (testing "and the round's cost is ONE connection, whatever the number of conversations"
        (is (= 1 spent)
            (str "one, for the round's own listing -- five conversations listed, " spent " spent."
                 " A round used to build two per conversation."))))
    (testing "and a conversation whose log GREW is the one it touches"
      (let [f (home/log-file (io/file (home/projects-dir) "unbound") (first ids))]
        (spit f (message-line "r1" 2 "model" "assistant" {:role "assistant" :content "more"})
              :append true :encoding "UTF-8")
        (let [answer (projection/project!)]
          (is (= 1 (:sessions answer)) "one conversation had new bytes")
          (is (= 1 (:rows answer)))
          (is (= 0 (:sessions (projection/project!))) "and the round after it touches none"))))))

;; --------------------------------------------------------------- the trigger is the write stream

(deftest a-written-line-is-copied-with-nobody-calling-project!
  ;; TICKET 02 OF `.scratch/record-window/`: the 2-second clock is gone and the TRIGGER is the write
  ;; stream. This case writes through the WRITER -- so the record's own doorbell rings -- starts the
  ;; projection, and asserts the row reaches the store WITHOUT a `project!` anywhere in the case.
  (let [sid "pj-listened"
        f   (home/log-file (io/file (home/projects-dir) "unbound") sid)]
    (project/register-session! sid)
    (.mkdirs (.getParentFile f))
    (let [stop (projection/start!)]
      (try
        (is (= {:dirty [] :scheduled? false :rounds 0} (projection/pending))
            "nothing is marked and no round is waiting before anything is written")
        (stream/push! sid f (message-line "r1" 1 "client" "user" {:role "user" :content "hi"}))
        (is (eventually #(= ["hi"] (mapv :content (messages-of sid))))
            "the written row reached the store with nobody calling project!")
        (testing "and the triggered round left the store exactly where a full pass would"
          (is (= {:sessions 0 :rows 0 :bytes 0 :skipped 0} (projection/project!))
              "a whole-store pass right after the round finds NOTHING new -- the same place")
          (let [copied (messages-of sid)]
            (projection/rebuild! sid)
            (is (= copied (messages-of sid))
                "and a full rebuild lands row for row what the round had copied")))
        (testing "a second line rings the bell again, with no pass to catch it"
          (stream/push! sid f (message-line "r1" 2 "model" "assistant"
                                            {:role "assistant" :content "more"}))
          (is (eventually #(= ["hi" "more"] (mapv :content (messages-of sid))))))
        (finally (stop))))))

(deftest an-idle-projection-does-not-walk-the-store
  ;; THE CLAIM THE CLOCK COULD NOT MAKE (`.scratch/record-window/` ticket 02). A 2-second
  ;; `scheduleAtFixedRate` ran a whole-store pass whether or not anything had been written --
  ;; measured 100-127 ms a round, ~5% of one core, for ever on an idle process. With the trigger on
  ;; the write stream nobody writing means NOTHING runs, and this asserts that on two counters that
  ;; a slow machine cannot fool: the trigger's OWN round count (zero) and the store's connection
  ;; count (unchanged) over several coalesce windows.
  (let [sid "pj-idle"]
    (project/register-session! sid)
    (let [f (home/log-file (io/file (home/projects-dir) "unbound") sid)]
      (.mkdirs (.getParentFile f))
      ;; WRITTEN BY HAND, NOT THROUGH THE WRITER: bytes appearing in a file are not the write stream
      ;; telling this namespace anything -- a listener hears what is WRITTEN, not what is there.
      (spit f (message-line "r1" 1 "client" "user" {:role "user" :content "never asked"})
            :encoding "UTF-8")
      (let [stop (projection/start!)]
        (try
          (let [before (db/connections-made)]
            (Thread/sleep (* 3 projection/coalesce-ms))
            (is (= 0 (:rounds (projection/pending))) "no round ran while nobody wrote")
            (is (= [] (:dirty (projection/pending))) "and nothing was marked dirty")
            (is (false? (:scheduled? (projection/pending))) "and no round is waiting")
            (is (= before (db/connections-made))
                "and not one store connection was opened -- the store was never walked"))
          (is (= [] (messages-of sid))
              "and the hand-written bytes are left unprojected: nobody wrote, so nobody was told")
          (finally (stop)))))))

(deftest a-burst-of-lines-is-one-round-not-one-per-line
  ;; THE OTHER HALF OF TICKET 02. A streaming answer writes thousands of lines, so the LISTENER must
  ;; not buy a transaction per line -- it only marks, and the round coalesces. Twenty lines written
  ;; back to back inside one `coalesce-ms` window are one round (two if a slow machine slips one in
  ;; between), and never twenty.
  (let [sid "pj-burst"
        f   (home/log-file (io/file (home/projects-dir) "unbound") sid)]
    (project/register-session! sid)
    (.mkdirs (.getParentFile f))
    (let [stop (projection/start!)]
      (try
        (doseq [i (range 20)]
          (stream/push! sid f (message-line "r1" (inc i) "model" "assistant"
                                            {:role "assistant" :content (str "line-" i)})))
        (is (eventually #(= 20 (count (messages-of sid)))) "all twenty lines are copied")
        (let [{:keys [dirty scheduled? rounds]} (projection/pending)]
          (is (= [] dirty) "and the marks are drained")
          (is (false? scheduled?) "and no round is left waiting")
          (is (<= 1 rounds 3)
              (str "twenty lines made " rounds " round(s) -- one or two is the design,"
                   " twenty would be one transaction per line")))
        (finally (stop))))))
