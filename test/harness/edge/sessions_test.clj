(ns harness.edge.sessions-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.claims :as claims]
            [harness.edge.ag-ui :as ag]
            [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.kernel.event :as ev]))

;; THE LOG LINES BELOW ARE PRODUCED BY THE REAL EMITTER, not hand-written frames (the
;; same rule harness.edge.replay-test spells out): a log this file invented could encode
;; a frame shape the server never writes, and then the test would pass while the build
;; failed on every real log.

(defn- log-line
  "A record row AS THE WRITER EMITS IT since `.scratch/jsonl-two-kinds`: the file has two
  kinds of row -- `message` and `event` -- with `ts`/`runId` outside the payload. The
  fixtures below still say `{:kind .. :payload ..}` because `row-json` here is the one place
  that knows the file spells a row otherwise."
  [{:keys [kind payload source id] :as m}]
  (json/write-str (cond-> (merge (select-keys m [:ts :runId])
                                 (cond
                                   (= "message" kind) {:type "message" :payload payload}
                                   (= "event" kind)   {:type "event" :payload payload}
                                   :else              {:type "event"
                                                       :payload {:type "CUSTOM" :name kind
                                                                 :value payload}}))
                    source (assoc :source source)
                    id     (assoc :id id))))

(defn- input-lines
  "WHAT ONE ACTION WROTE, as the rows the writer now leaves: the prompt the model was handed
  first (`:source` = `system-prompt`), then a `message` row per entry the client brought --
  the entry's own name on the envelope, the payload verbatim as the provider reads it.
  `.scratch/jsonl-two-kinds` 票 02 took the `input` row away."
  [run-id messages]
  (into [(log-line {:ts 0 :runId run-id :kind "message" :source "system-prompt" :hash "h1"
                    :payload {:role "system" :content "S"}})]
        (map (fn [m]
               (log-line (cond-> {:ts 1 :runId run-id :kind "message"
                                  :source (if (:id m) "client" "injection")
                                  :payload (dissoc m :id)}
                           (:id m) (assoc :id (:id m))))))
        messages))

(defn- event-lines [run-id events]
  (let [emit (ag/outbound "t" run-id)]
    (mapv (fn [frame] (log-line {:ts 2 :runId run-id :kind "event" :payload frame}))
          (vec (mapcat emit events)))))

(def ^:private seed {:id "u1" :role "user" :content "看看这个项目"})

(defn- write-log!
  "A settled conversation for THREAD-ID: one user turn, one answer. `extra` is spliced in
  as more frames before the run ends."
  [thread-id & [extra]]
  (let [f (io/file (home/projects-dir) "sessions-test"
                   (str (home/sanitize thread-id) ".jsonl"))]
    (.mkdirs (.getParentFile f))
    (spit f (str (str/join "\n"
                           (concat (input-lines "r1" [seed])
                                   (event-lines "r1" (concat [(ev/run-start)]
                                                             extra
                                                             [(ev/text-delta "这是一个 Clojure 项目。")
                                                              (ev/run-end)]))))
                "\n")
          :encoding "UTF-8")
    f))

(defn- forget-everything! []
  (doseq [tid (keys (sessions/live))] (sessions/drop! tid)))

(use-fixtures :each (fn [f]
                      (forget-everything!)
                      (sessions/watch-unflushed! (constantly false))
                      (try (f) (finally (forget-everything!)
                                 (sessions/watch-unflushed! (constantly false))))))

;; ------------------------------------------------------------------ what it holds

(deftest a-thread-that-never-ran-has-an-empty-conversation
  (testing "no log is not an error -- a page opening a session yet to run gets nothing"
    (is (= [] (sessions/messages "never-ran"))))
  (testing "and asking for it is what puts it in the table"
    (is (contains? (sessions/live) "never-ran"))))

(deftest a-session-is-built-once-from-the-record
  (write-log! "t-build")
  (let [built (sessions/messages "t-build")]
    (testing "the conversation is the seed plus what the frames folded into"
      (is (= ["user" "assistant"] (mapv :role built)))
      (is (= seed (first built)))
      (is (= "这是一个 Clojure 项目。" (:content (second built)))))
    (testing "and the second ask is answered from memory, byte for byte"
      (is (= built (sessions/messages "t-build"))))))

(deftest putting-one-away-and-building-it-again-gives-the-same-bytes
  (write-log! "t-again")
  (let [built (sessions/messages "t-again")]
    (sessions/drop! "t-again")
    (is (not (contains? (sessions/live) "t-again"))
        "the drop is the reason the next ask has to read the record")
    (is (= built (sessions/messages "t-again"))
        "ONE ASSERTION, and it is the joint between 'the log is still the record' and
         'memory is the authority': the fold has to land on the same bytes or putting
         a session away would be a silent edit")))

(deftest an-injected-card-becomes-the-message-the-model-read
  ;; A card is a message the log carries so the screen can draw it again, and its bytes
  ;; are the ones the model READ (`harness.edge.ag-ui/injection-value`). The model view
  ;; REALISES them back, so an injection stays in the conversation once instead of being
  ;; dropped and re-derived (and re-drawn) on every turn. Handing the raw `data` part to
  ;; a provider is still a named refusal (`harness.edge.ag-ui/provider-part` has no case
  ;; for one) -- which is the half this keeps from happening.
  (write-log! "t-cards" [(ev/context-injected {:role "user"
                                               :content "<instructions path=\"AGENTS.md\">规矩</instructions>"})])
  (let [built (sessions/messages "t-cards")]
    (testing "the injection is a user message the model is handed"
      (is (= ["user" "user" "assistant"] (mapv :role built)))
      (is (= "<instructions path=\"AGENTS.md\">规矩</instructions>"
             (:content (second built)))))
    (testing "no message carries a data part"
      (is (not-any? (fn [m] (some #(= "data" (:type %)) (:content m))) built)))))

;; ------------------------------------------------------------------- its lifetime

(deftest an-idle-session-is-put-away
  (sessions/touch! "t-idle")
  (let [touched (:touched-at (get (sessions/live) "t-idle"))]
    (testing "not yet -- a hair under the TTL it stays"
      (is (= [] (sessions/sweep! (+ touched (dec sessions/idle-ttl-ms)))))
      (is (contains? (sessions/live) "t-idle")))
    (testing "and at the TTL it goes, and the sweep says which one"
      (is (= ["t-idle"] (sessions/sweep! (+ touched sessions/idle-ttl-ms))))
      (is (not (contains? (sessions/live) "t-idle"))))))

(deftest a-session-with-a-run-going-is-not-put-away
  (sessions/run-started! "t-running" "r1")
  (let [touched (:touched-at (get (sessions/live) "t-running"))
        later   (+ touched (* 100 sessions/idle-ttl-ms))]
    (testing "however long it has been going, the session stays"
      (is (= [] (sessions/sweep! later)))
      (is (contains? (sessions/live) "t-running")))
    (testing "and the idle clock starts again when the run ends"
      (sessions/run-finished! "t-running" "r1")
      (let [ended (:touched-at (get (sessions/live) "t-running"))]
        (is (= [] (sessions/sweep! (+ ended (dec sessions/idle-ttl-ms))))
            "the run's END is the touch: a long run must not be eligible the instant it
             stops, and `later` is 100 TTLs past `touched`")
        (is (= ["t-running"] (sessions/sweep! (+ ended sessions/idle-ttl-ms))))))))

(deftest two-runs-of-one-session-and-only-one-of-them-ends
  ;; Two runs of one session is .scratch/session-after-refresh ticket 05's refusal to
  ;; make. Until it lands the table must still be right about the fact it owns.
  (sessions/run-started! "t-two" "r1")
  (sessions/run-started! "t-two" "r2")
  (sessions/run-finished! "t-two" "r1")
  (let [touched (:touched-at (get (sessions/live) "t-two"))]
    (is (= #{"r2"} (:runs (get (sessions/live) "t-two"))))
    (is (= [] (sessions/sweep! (+ touched (* 100 sessions/idle-ttl-ms))))
        "r1 ending must not unpin a session r2 is still running")))

(deftest a-session-somebody-is-watching-is-not-put-away
  ;; A CONNECTED WINDOW IS A PIN (ticket 06). Without it a page left open on a
  ;; conversation is swept every thirty seconds: the feed ends, the client reopens the
  ;; tail, and the same thing happens again -- a reader who never asked for anything
  ;; watching their conversation get rebuilt on a timer. The pin ends where the
  ;; connection does (`unwatch!`, which is http-kit's close handler).
  (sessions/touch! "t-watched")
  (sessions/watch! "t-watched" (fn [_ _] nil))
  (let [touched (:touched-at (get (sessions/live) "t-watched"))
        later   (+ touched (* 100 sessions/idle-ttl-ms))]
    (testing "however long nobody touches it, a watched session stays"
      (is (= [] (sessions/sweep! later)))
      (is (contains? (sessions/live) "t-watched")))
    (testing "and when the window closes it is eligible again"
      (sessions/unwatch! "t-watched" (first (get (deref (var-get #'sessions/watchers)) "t-watched")))
      (is (= ["t-watched"] (sessions/sweep! later))))))

(deftest bytes-that-have-not-reached-the-record-hold-a-session
  (sessions/touch! "t-pending")
  (sessions/watch-unflushed! #(= % "t-pending"))
  (let [touched (:touched-at (get (sessions/live) "t-pending"))
        later   (+ touched (* 100 sessions/idle-ttl-ms))]
    (testing "putting it away would mean rebuilding from a record behind it, silently"
      (is (= [] (sessions/sweep! later)))
      (is (contains? (sessions/live) "t-pending")))
    (testing "and once the bytes land it goes"
      (sessions/watch-unflushed! (constantly false))
      (is (= ["t-pending"] (sessions/sweep! later))))))

;; ------------------------------------------------------------ the running ceiling

(deftest the-running-ceiling-is-refused-by-name
  (dotimes [i sessions/max-running]
    (sessions/run-started! (str "t-limit-" i) (str "r" i)))
  (is (= sessions/max-running (sessions/running-count)))
  (testing "one more is a named refusal, not a queue"
    (let [e (try (sessions/run-started! "t-limit-over" "r-over") nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "the run does not start")
      (is (= :too-many-running-sessions (:reason (ex-data e))))
      (is (= sessions/max-running (:limit (ex-data e))))
      (is (str/includes? (ex-message e) (str sessions/max-running))
          "the sentence names the number, because that is the thing to argue with")))
  (testing "and it is not counted, nor pinned"
    (is (= sessions/max-running (sessions/running-count)))
    (is (not (contains? (sessions/live) "t-limit-over"))))
  (testing "a run the table already holds is not a second one"
    (sessions/run-started! "t-limit-0" "r0")
    (is (= sessions/max-running (sessions/running-count)))))

;; ----------------------------------------------------------- and it claims them
;;
;; A SESSION'S LIFETIME IS A CLAIM ON THE CONVERSATION (ticket 04, ADR 0002 decision
;; 7): born claiming, put away handing it back. What that buys is on the far side of a
;; process boundary -- two processes serving one conversation interleave their frames
;; into one append-only file, and the result reads as a conversation that happened --
;; so the cases here are about the LIFETIME. The cross-process half, and what the OS
;; says about a pid, live in harness.cap.claims-test.

(defn- claim-row [thread-id]
  (first (db/select "SELECT * FROM session_claims WHERE thread_id = ?" (str thread-id))))

(defn- claimed-by-somebody-else!
  "Leave the row a LIVE OTHER PROCESS would leave: this process's own pid and start
  instant, so the OS says a process is there, under an instance that is not ours.
  That pair is exactly what a second process serving a conversation puts in the store,
  and it needs no second process to write it."
  [thread-id]
  (let [us (claims/this-process)]
    (db/with-transaction
      (fn [c]
        (db/execute! c "INSERT INTO session_claims
                          (thread_id, instance, token, pid, started_at, since)
                        VALUES (?, ?, ?, ?, ?, ?)"
                     (str thread-id) "another-process" (str (java.util.UUID/randomUUID))
                     (:pid us) (:started-at us) (System/currentTimeMillis))))))

(deftest a-session-claims-its-conversation-as-it-is-born
  (sessions/messages "t-claim")
  (let [row (claim-row "t-claim")]
    (is (some? row) "asking for a session is what claims it")
    (is (= (:instance (claims/this-process)) (:instance row)))
    (is (= (:pid (claims/this-process)) (:pid row)))
    (testing "and the entry carries the claim's token, so the put-away can hand it back"
      (is (= (:token row) (:claim (get (sessions/live) "t-claim")))))
    (testing "asking again does not open a second claim"
      (sessions/messages "t-claim")
      (is (= 1 (count (db/select "SELECT * FROM session_claims WHERE thread_id = ?" "t-claim")))))))

(deftest putting-one-away-hands-the-claim-back
  (testing "explicitly"
    (sessions/touch! "t-hand-back")
    (is (some? (claim-row "t-hand-back")))
    (sessions/drop! "t-hand-back")
    (is (nil? (claim-row "t-hand-back")) "a conversation this process stopped serving is free"))
  (testing "and by going idle"
    (sessions/touch! "t-hand-back-idle")
    (let [touched (:touched-at (get (sessions/live) "t-hand-back-idle"))]
      (is (= ["t-hand-back-idle"] (sessions/sweep! (+ touched sessions/idle-ttl-ms))))
      (is (nil? (claim-row "t-hand-back-idle"))))))

(deftest a-conversation-another-process-is-serving-cannot-be-born-here
  (claimed-by-somebody-else! "t-taken")
  (let [e (try (sessions/touch! "t-taken") nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) "a live claim elsewhere is a refusal, not a shrug")
    (is (= :session-claimed (:reason (ex-data e))))
    (is (= "another-process" (:instance (:holder (ex-data e)))))
    (testing "and the refused birth left nothing behind in this process's table"
      (is (not (contains? (sessions/live) "t-taken")))))
  (testing "the row is still theirs -- a refused ask must not steal it"
    (is (= "another-process" (:instance (claim-row "t-taken"))))))

(deftest a-claim-left-by-a-process-that-is-gone-is-taken-over
  ;; The pid is LIVE -- it is this process's own -- and the start instant is not: that
  ;; is what a reused pid looks like, and it is the reason a claim records when its
  ;; owner started rather than only which number it had.
  (let [us (claims/this-process)]
    (db/with-transaction
      (fn [c]
        (db/execute! c "INSERT INTO session_claims
                          (thread_id, instance, token, pid, started_at, since)
                        VALUES (?, ?, ?, ?, ?, ?)"
                     "t-stale" "the-previous-owner" (str (java.util.UUID/randomUUID))
                     (:pid us) 1 (System/currentTimeMillis))))
    (sessions/touch! "t-stale")
    (is (= (:instance us) (:instance (claim-row "t-stale")))
        "the session was born here, over a claim whose owner is gone")))

;; ------------------------------------------------------------- and it writes nothing

(deftest asking-for-a-session-writes-nothing
  (let [f        (write-log! "t-quiet")
        before   [(.length f) (.lastModified f)]
        fileseq  #(vec (sort (map str (file-seq (home/projects-dir)))))]
    (let [files-before (fileseq)]
      (sessions/messages "t-quiet")
      (sessions/touch! "t-never-ran")
      (sessions/run-started! "t-quiet" "r1")
      (sessions/run-finished! "t-quiet" "r1")
      (sessions/sweep! (System/currentTimeMillis))
      (testing "the log it read is the log it left"
        (is (= before [(.length f) (.lastModified f)])))
      (testing "and no file appeared anywhere under the log tree"
        (is (= files-before (fileseq)))))))

;; ------------------------------------------------------------------ the sweeper

(deftest the-sweeper-is-started-once
  (let [stop  (sessions/start!)
        stop2 (sessions/start!)]
    (try
      (is (fn? stop))
      (is (fn? stop2) "a second start answers a stop fn rather than a second clock")
      (finally (stop) (stop2)))))

;; -------------------------------------------------------- the window (ticket 05)

(deftest an-entry-is-numbered-by-the-record-line-it-arrived-in
  ;; ADR 0003 DECISION 1 AND 9, AS ONE ASSERTION: the number is monotonic per entry, and
  ;; it is REPLAYABLE -- derived from the record rather than remembered. So the test does
  ;; not compare against numbers it chose; it reads the FILE's lines and says which line
  ;; each entry came from.
  (let [f       (write-log! "t-seq")
        entries (sessions/display "t-seq")
        lines   (str/split-lines (slurp f :encoding "UTF-8"))]
    (testing "nothing is unnumbered in a session built from a finished record"
      (is (every? some? (map :seq entries))))
    (testing "the action's own entry is numbered by the message row it arrived in"
      ;; THE PROMPT COMES FIRST (`harness.edge.http` writes the action's rows and then the
      ;; run's own): line 0 is the system prompt, and the client's message is line 1.
      (is (= 1 (:seq (first entries))))
      (is (= seed (:message (first entries)))
          "the entry is the message the client sent, byte for byte"))
    (testing "and a run's entries by the line the run ENDED on -- its terminal frame"
      (is (= (dec (count lines)) (:seq (last entries)))
          "the record's last line is the run's terminal, and the answer is numbered there")
      (is (= 1 (count (distinct (map :seq (rest entries)))))
          "everything the run produced shares that one number: it arrived together"))
    (testing "and the numbers agree with the fold a replay would do"
      ;; The same conversation, read the OTHER way: `harness.edge.replay/entries` is the
      ;; call the session itself was built from, so this is really 'the session did not
      ;; mangle what it was handed' -- which is the whole of the replayability claim.
      (is (= (mapv :seq entries)
             (mapv :seq (replay/entries (replay/lines->records (replay/read-lines f)))))))))

(deftest a-number-arrives-when-the-line-that-carries-it-lands
  (let [tid "t-land"]
    (sessions/append! tid "r9" [{:id "u1" :role "user" :content "hi"}])
    (testing "an entry still in the writer's queue carries NO number, not a guess"
      (is (= [nil] (mapv :seq (sessions/display tid)))))
    (testing "and the writer's answer is what numbers it"
      (sessions/land! tid "r9" 7)
      (is (= [7] (mapv :seq (sessions/display tid)))))
    (testing "a second landing cannot renumber an entry that already has one"
      ;; The retry path: the same line reported twice, or a later line's number arriving
      ;; for a group that had already landed. Numbers only ever get FILLED IN.
      (sessions/land! tid "r9" 99)
      (is (= [7] (mapv :seq (sessions/display tid)))))
    (testing "a landing for a group this session never had changes nothing"
      (sessions/land! tid "r-other" 3)
      (is (= [7] (mapv :seq (sessions/display tid)))))
    (testing "and one action's entries enter once, however many times it is sent"
      ;; The identity is the message's own id -- the retry, the double-submit, and the
      ;; record's own dedupe all read the same name.
      (let [again (sessions/append! tid "r9" [{:id "u1" :role "user" :content "hi"}])]
        (is (= [] again) "nothing entered")
        (is (= 1 (count (sessions/display tid))))))))

(defn- fill-window!
  "Put GROUPS arrivals of TEN entries each into THREAD-ID, each landed at its own line
  offset -- 30 arrivals, 300 entries. A window of `page-size` is meant to be a slice of
  a conversation that is too long to send, so the test has to HAVE one."
  [tid groups]
  (dotimes [g groups]
    (let [run (str "r" g)]
      (sessions/append! tid run (mapv (fn [i] {:id (str run "-" i)
                                               :role "user"
                                               :content (str run "/" i)})
                                      (range 10)))
      (sessions/land! tid run (* 10 g)))))

(deftest the-tail-is-a-page-and-never-begins-mid-arrival
  (let [tid "t-window"]
    (fill-window! tid 30)
    (let [page (sessions/tail tid)]
      (is (= 50 (count (:entries page))) "a page is `page-size` entries")
      (is (true? (:hasMore page)))
      (is (= 250 (:baseSeq page))
          "the page starts at the oldest entry it holds, which is an ARRIVAL's first")
      (is (= (vec (for [g (range 25 30) i (range 10)] (str "r" g "/" i)))
             (mapv (comp :content :message) (:entries page)))
          "and it begins at an arrival boundary rather than ten entries into one"))))

(deftest loading-earlier-neither-overlaps-nor-skips
  (let [tid   "t-before"
        _     (fill-window! tid 30)
        tail  (sessions/tail tid)
        front (sessions/before tid (:baseSeq tail))]
    (testing "the page in front of the window ends exactly where the window begins"
      (is (= 50 (count (:entries front))))
      (is (= 200 (:baseSeq front)))
      (is (true? (:hasMore front)))
      (is (= (vec (for [g (range 20 25) i (range 10)] (str "r" g "/" i)))
             (mapv (comp :content :message) (:entries front)))
          "the twenty-fifth arrival is not in it: the client already has that one")
      (is (empty? (filter (set (map (comp :id :message) (:entries tail)))
                          (map (comp :id :message) (:entries front))))
          "NO OVERLAP: nothing is handed back twice")
      (is (= 100 (count (distinct (map (comp :id :message)
                                       (concat (:entries front) (:entries tail))))))
          "and nothing is lost either: the two pages are 100 distinct entries")
      (is (let [next-page (sessions/before tid (:baseSeq front))]
            (and (= 50 (count (:entries next-page)))
                 (= 150 (:baseSeq next-page))))
          "the page in front of THAT one follows the same rule, all the way up"))
    (testing "the first page has no more in front of it"
      (let [first-page (sessions/before tid 10)]
        (is (= 10 (count (:entries first-page))) "one arrival's worth")
        (is (false? (:hasMore first-page)))
        (is (= 0 (:baseSeq first-page)))))))

(deftest since-answers-only-what-the-reader-is-missing
  (let [tid "t-since"
        _   (fill-window! tid 30)]
    (is (= 50 (count (sessions/since tid 249)))
        "everything after the twenty-fifth arrival's number: the tail page's own entries")
    (is (= 10 (count (sessions/since tid 289)))
        "the reader holds through 289: the arrival numbered 290 is missing")
    (is (= [] (sessions/since tid 290)) "a reader that holds 290 is current")
    (is (= [] (sessions/since tid 299)) "the reader is current")
    (is (= 300 (count (sessions/since tid nil)))
        "a reader that holds nothing is missing everything")
    (testing "an entry that has not landed yet counts as after anything"
      ;; The newest arrival is in the writer's queue: it has no number yet, and a reader
      ;; that was current a moment ago has not been told about it. It may be handed the
      ;; same entry twice across a reconnect -- which is why every entry carries its id.
      (sessions/append! tid "r30" [{:id "r30-0" :role "user" :content "r30/0"}])
      (let [delta (sessions/since tid 299)]
        (is (= ["r30-0"] (mapv (comp :id :message) delta)))
        (is (nil? (:seq (first delta))) "and it is handed over without a number")))))

(deftest a-watcher-is-rung-when-there-is-something-to-read
  ;; THE DOORBELL (`watch!`): a fn, no cursor, no identity -- rung by the writer's
  ;; LANDING rather than by the entry going in, because what a window addresses is the
  ;; record's numbering and an unlanded entry cannot be addressed yet.
  (let [tid    "t-bell"
        rings  (atom [])
        f      (fn [id event] (swap! rings conj [id event]))]
    (sessions/watch! tid f)
    (try
      (sessions/append! tid "r1" [{:id "u1" :role "user" :content "hi"}])
      (sessions/land! tid "r1" 0)
      (sessions/append! tid "r2" [{:id "u2" :role "user" :content "again"}])
      (sessions/run-started! tid "r2")
      (sessions/run-finished! tid "r2")
      (sessions/drop! tid)
      (is (= [:entries :entries :entries :entries :entries :gone]
             (mapv (comp :kind second) @rings)))
      (is (= tid (ffirst @rings)))
      (is (= :put-away (:reason (second (last @rings))))
          "the last ring says WHY the window is over -- a reader is told, not dropped")
      (finally (sessions/unwatch! tid f)))
    (testing "and an unwatched id is not rung again"
      (reset! rings [])
      (sessions/append! tid "r3" [{:id "u3" :role "user" :content "quiet"}])
      (is (= [] @rings)))))

(deftest the-generation-is-the-token-of-the-claim-that-holds-the-session
  ;; A window is only meaningful while the SAME claim serves the conversation (ADR 0003
  ;; decision 6), so the generation IS the claim's token rather than a second thing kept
  ;; in step with it.
  (sessions/touch! "t-gen")
  (let [first-generation (sessions/generation "t-gen")]
    (is (some? first-generation))
    (is (= first-generation (:claim (sessions/live-entry "t-gen"))))
    (sessions/drop! "t-gen")
    (is (nil? (sessions/generation "t-gen")) "nobody holds it, so there is no window")
    (sessions/touch! "t-gen")
    (is (not= first-generation (sessions/generation "t-gen"))
        "a new hold is a new window, and every number read under the old one is stale")))

(deftest settling-writes-down-what-the-frame-that-ended-the-run-said
  (let [tid "t-settle-state"]
    (sessions/append! tid "r1" [seed])
    (sessions/settle! tid "r1" ((ag/outbound tid "r1") (ev/run-end)))
    (is (= :settled (sessions/state tid)))
    (testing "an interrupted run leaves it parked, and keeps the card's own bytes"
      ;; The terminal is built here the way the run path builds it (http-test reads the
      ;; same shape off a real run: `:outcome {:type \"interrupt\" :interrupts [..]}`);
      ;; what is under test is what `settle!` DOES with it, not the shape.
      (let [terminal {:type "RUN_FINISHED"
                      :outcome {:type "interrupt"
                                :interrupts [{:id "i1" :toolCallId "c1"
                                              :reason "may I?"}]}}]
        (sessions/append! tid "r2" [{:id "u2" :role "user" :content "second"}])
        (sessions/settle! tid "r2" [terminal])
        (is (= :parked (sessions/state tid)))
        (is (= [{:id "i1" :toolCallId "c1" :reason "may I?"}]
               (:interrupts (sessions/live-entry tid)))
            "the card survives a refresh because it is part of the conversation's state")))))
