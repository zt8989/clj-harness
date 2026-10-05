(ns harness.edge.compaction-run-test
  "Compaction's write half, asserted with its effects handed in.

  `harness.edge.compaction/perform!` takes `append` (write a row) and `summarize` (one model
  call) as arguments, so the lock, the range, the row order and the no-op case are ordinary
  function calls -- no provider, no file, no server. The rows it appends are folded back
  through the READ half (`harness.edge.replay`), which is the one assertion that matters:
  the writer and the reader agree on the record's shape."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.providers :as providers]
            [harness.edge.ag-ui :as ag]
            [harness.edge.compaction :as compaction]
            [harness.edge.pressure :as pressure]
            [harness.edge.http :as http]
            [harness.edge.replay :as replay]
            [harness.fake :as fake]
            [harness.infra.home :as home]
            [harness.kernel.event :as ev]))

;; ------------------------------------------------------------------ the records

(defn- entry [ts id text]
  {:ts ts :runId "r1" :type "message" :payload {:role "user" :content text}
   :source "client" :id id})

(defn- big [i]
  ;; 400 characters -> estimate-message says 400/4 + 4 (block) + 4 (role) = 108 tokens.
  (entry i (str "u" i) (apply str (repeat 400 "a"))))

(defn- six []
  (vec (map big (range 6))))

(defn- row
  "A [kind payload] pair as the record row it becomes -- what `append` hands the writer."
  [kind payload]
  {:ts 0 :runId nil :type "event"
   :payload {:type "CUSTOM" :name kind :value payload}})

(defn- write-rows [written] (mapv (fn [[k p]] (row k p)) written))

;; ---------------------------------------------------------------------- the range

(deftest the-plan-keeps-the-tail-verbatim-and-hands-over-the-head
  (let [plan (compaction/plan (six) 1000 0.16)]
    (is (= [0 1 2 3] (:shadowed plan))
        "the four OLDEST nodes are compacted; the two newest are the retained tail")
    (is (= 4 (count (:messages plan))))
    (is (= 432 (:head-tokens plan)) "4 x 108")))

(deftest the-whole-surface-in-the-retained-tail-is-nothing-to-do
  (is (nil? (compaction/plan [(entry 0 "u1" "hi")] 1000 0.16))))

;; ------------------------------------------------------------------------ the run

(deftest a-compaction-writes-three-rows-and-shortens-the-model-view
  (let [records (six)
        written (atom [])
        result  (compaction/perform! records {:window 1000 :retain-ratio 0.16
                                              :append    (fn [k p] (swap! written conj [k p]))
                                              :summarize (fn [msgs _] (str "SUMMARY of " (count msgs)))})
        all     (into records (write-rows @written))
        view    (replay/compacted-messages (replay/entries all) (replay/compaction-facts all))]
    (is (= [0 1 2 3] (:shadowed result)))
    (is (= 432 (:tokens result))
        "and the size the folded range was estimated at")
    (is (string? (:compactionId result)))
    (is (= (:compactionId result) (:compactionId (second (second @written))))
        (str "the answer names the pair of rows it just wrote -- which is what a card is folded"
             " under (`harness.edge.ag-ui/compacted-frame`)"))
    (is (= ["compaction/start" "context/compacted" "compaction/end"] (mapv first @written))
        "start first, end LAST -- that order is the lock")
    (is (= compaction/summary-instruction
           (:instruction (second (first @written))))
        (str "the PROMPT the summarizer was handed is on the start row -- the whole thing, not a"
             " hash of it, so a reader can reconstruct what was asked (owner, 2026-09-28)"))
    (is (= (:content (replay/compaction-summary "SUMMARY of 4")) (:content (first view)))
        (str "the projection stands one summary where the four compacted nodes stood, in the one",
             " shape the fold mints (preamble + tags; `replay/compaction-summary`)"))
    (is (= 3 (count view)) "one summary + the two retained")
    (is (= 6 (count (replay/entries all))) "the conversation still holds every original")))

(deftest nothing-to-compact-writes-nothing
  (let [written (atom [])]
    (is (nil? (compaction/perform! [(entry 0 "u1" "hi")]
                                   {:window 1000 :retain-ratio 0.16
                                    :append    (fn [k p] (swap! written conj [k p]))
                                    :summarize (fn [_ _] "S")})))
    (is (= [] @written) "no range, no rows -- not an empty pair")))

(deftest a-held-lock-refuses-a-second-compaction
  (let [records (conj (six) (row "compaction/start" {:compactionId "open"}))
        written (atom [])]
    (is (= "open" (compaction/lock-active? records)))
    (is (nil? (compaction/perform! records {:window 1000 :retain-ratio 0.16
                                            :append    (fn [k p] (swap! written conj [k p]))
                                            :summarize (fn [_ _] "S")})))
    (is (= [] @written) "a held lock writes nothing")))

(deftest a-failed-summary-still-closes-its-end-with-the-error
  (let [written (atom [])
        ;; THE EFFECTS ARE NAMED RATHER THAN INLINED where the shape gets deep: a `summarize`
        ;; written inline inside a `try` inside a `let` is where a stray paren hides.
        boom    (fn [_ _] (throw (ex-info "no model" {})))
        thrown  (try
                  (compaction/perform! (six) {:window 1000 :retain-ratio 0.16
                                              :append    (fn [k p] (swap! written conj [k p]))
                                              :summarize boom})
                  nil
                  (catch Throwable t t))]
    (is (instance? Throwable thrown) "the failure is rethrown untouched")
    (is (= ["compaction/start" "compaction/end"] (mapv first @written)))
    (is (= "no model" (:error (second (second @written))))
        "the end carries the error: a failure is recorded as a failure, not left unrecorded")))

(deftest a-second-compaction-names-the-first-summary
  (let [records   (six)
        written   (atom [])
        append    (fn [k p] (swap! written conj [k p]))]
    (compaction/perform! records {:window 1000 :retain-ratio 0.16
                                  :append append :summarize (fn [_ _] "S1")})
    (let [all   (into records (write-rows @written))
          plan2 (compaction/plan all 1000 0.16)]
      (is (= [7] (:shadowed plan2))
          "the second range names the first SUMMARY's fact seq (7), not the four originals it replaced"))))

;; -------------------------------------------------------------------- the route

(defn- plant!
  "Write THREAD-ID's log under the projects tree `replay/locate` searches, so a route can
  find it without a run having happened."
  [thread-id rows]
  (let [f (home/log-file (#'http/unbound-dir) thread-id)]
    (.mkdirs (.getParentFile f))
    (spit f (str (str/join "\n" (map json/write-str rows)) "\n") :encoding "UTF-8")
    f))

(defn- wait-for-rows
  "RECORDS of LOG, polled until KIND appears (the writer appends off-thread) or ~2s."
  [log kind]
  (loop [n 0]
    (let [rows (replay/read-records log)
          ks   (mapv replay/kind rows)]
      (if (or (some #{kind} ks) (>= n 200))
        rows
        (do (Thread/sleep 10) (recur (inc n)))))))

(deftest the-compact-route-summarizes-and-records-one-compaction
  ;; THE WHOLE WRITE PATH ONCE: a planted record, the route deciding, a scripted provider
  ;; standing in for the summarizer, and the rows it wrote read back off disk. A SERVER RUNS
  ;; ONLY TO DRIVE THE RECORD WRITER -- `log!` appends off-thread.
  (let [thread-id "compact-route"
        rows      (mapv (fn [i] (entry i (str "u" i) (apply str (repeat 4000 "a")))) (range 25))
        log       (plant! thread-id rows)
        stop      (http/start! {:port 0})]
    (providers/use-provider! thread-id (fake/scripted [{:content "THE SUMMARY"}]))
    (try
      (let [resp (#'http/compact-post nil thread-id)
            body (json/read-str (String. ^bytes (:body resp) "UTF-8") :key-fn keyword)]
        (is (= 200 (:status resp)) (pr-str body))
        (is (true? (:compacted body)))
        (is (= 4 (count (:shadowed body))) "25 x 1008 tokens, retain 20480 -> the 4 oldest go")
        (let [ks (mapv replay/kind (wait-for-rows log "compaction/end"))]
          (is (some #{"context/compacted"} ks))
          (is (some #{"model/start"} ks) "the summary is its own bracketed model call")
          (is (some #{"model/end"} ks))
          (is (< (.indexOf ks "compaction/start") (.indexOf ks "compaction/end"))
              "start first, end last")))
      (finally
        (stop)
        (io/delete-file log true)
        (providers/use-provider! thread-id nil)))))

(deftest the-session-opening-is-never-compacted-away
  ;; The opening's instruction files, skills catalog and birth context are what every later
  ;; request is read against; a summary is not a substitute. The head starts after them.
  (let [opening  (assoc (entry 0 "session-opening-0" (apply str (repeat 400 "o")))
                        :source "opening")
        records  (into [opening]
                       (map (fn [i] (entry (inc i) (str "u" i) (apply str (repeat 4000 "a"))))
                            (range 25)))
        written  (atom [])
        result   (compaction/perform! records {:window 128000 :retain-ratio 0.16
                                               :append    (fn [k p] (swap! written conj [k p]))
                                               :summarize (fn [_ _] "S")})
        all      (into records (write-rows @written))
        view     (replay/compacted-messages (replay/entries all) (replay/compaction-facts all))
        opening-content (apply str (repeat 400 "o"))]
    (is (some? result))
    (is (= 4 (count (:shadowed result))) "25 big nodes, 21 retained -> 4 shadowed")
    (is (not (some #{0} (:shadowed result))) "the opening's seq is NOT among them")
    (is (some #(= opening-content (:content %)) view)
        "the opening is still in the model view, verbatim")))

(defn- auto-rows
  ;; Six 4000-character entries (each ~1008 tokens) and a call whose own line declares WINDOW --
  ;; WHAT THE RECORD SAYS, which is the PREVIOUS call's model. The pre-run trigger no longer
  ;; divides by this number (`pressure/with-window`); `pin!` is what hands it the window this
  ;; run goes out under, and a fixture that wants the two to agree names the same one twice.
  [window]
  (conj (vec (map (fn [i] (entry i (str "u" i) (apply str (repeat 4000 "a")))) (range 6)))
        ;; THE CALL CARRIES THE RUN'S OWN ID: that is what a real `model/start` row does, and
        ;; the meter tells a run's own call from one the harness wrote for itself (a compaction's
        ;; summarizer, logged with no run id) by exactly this. A fixture that left it nil was
        ;; describing a row the harness never writes.
        (assoc (row "model/start" {:model "scripted" :context-window window}) :runId "r1")
        (assoc (row "model/end" {:usage {:prompt_tokens 100 :completion_tokens 5 :total_tokens 105}})
               :runId "r1")))

(defn- pin!
  "Pin THREAD-ID to a scripted provider that declares WINDOW -- the window THIS RUN goes out
  under, which is the one the pre-run trigger measures against. TURNS is its script."
  [thread-id window turns]
  (providers/use-provider! thread-id (assoc (fake/scripted turns) :context-window window)))

(deftest the-pressure-trigger-compacts-at-the-threshold-and-not-below
  (let [stop (http/start! {:port 0})]
    (try
      (testing "over the threshold: it compacts by itself"
        (let [thread-id "auto-over"
              log       (plant! thread-id (auto-rows 8000))]
          (pin! thread-id 8000 [{:content "AUTO SUMMARY"}])
          (try
            (#'http/compact-if-pressured! thread-id)
            (let [ks (mapv replay/kind (wait-for-rows log "compaction/end"))]
              (is (some #{"compaction/start"} ks))
              (is (some #{"context/compacted"} ks))
              (is (some #{"compaction/end"} ks)))
            (finally
              (providers/use-provider! thread-id nil)
              (io/delete-file log true)))))
      (testing "below it: nothing at all"
        (let [thread-id "auto-under"
              log       (plant! thread-id (auto-rows 100000))]
          (pin! thread-id 100000 [{:content "SHOULD NOT BE USED"}])
          (try
            (#'http/compact-if-pressured! thread-id)
            (let [ks (mapv replay/kind (wait-for-rows log "compaction/start"))]
              (is (not (some #{"context/compacted"} ks)) "no rows, no model call"))
            (finally
              (providers/use-provider! thread-id nil)
              (io/delete-file log true)))))
      (finally (stop)))))

(deftest a-compaction-the-vendor-refused-is-announced-as-well-as-one-that-worked
  ;; THE OWNER'S INCIDENT (2026-10-05), asserted as the function that now carries it. The trigger
  ;; fired at 74% of a 1M window, the summary call was refused (HTTP 429 rate_limit_exceeded on a
  ;; real session, the words of an exhausted quota), compaction/end recorded the failure -- and the
  ;; page showed a red context ring and NOTHING else for two whole runs. The gap was that each of
  ;; the three call sites wrapped a BARE call: a throw unwound past the announce, so the failure was
  ;; recorded in the log and nowhere a person could see it.
  (let [stop (http/start! {:port 0})]
    (try
      (testing "a refused one announces the start and then the failure, in that order"
        (let [thread-id "auto-refused"
              log       (plant! thread-id (auto-rows 8000))]
          ;; A TURN THAT REFUSES IS A VENDOR SAYING NO BEFORE THE STREAM OPENS (harness.fake), which
          ;; is exactly the shape a 429 on the summary call has.
          (pin! thread-id 8000 [{:refuse {:status 429 :body "rate_limit_exceeded"}}])
          (try
            (let [said   (atom [])
                  answer (#'http/announce-compaction! thread-id (providers/current-provider thread-id)
                                                         (replay/read-records log) 8000
                                                         (compaction/config thread-id)
                                                         (fn [frame] (swap! said conj frame))
                                                         nil)]
              (is (nil? answer) "a caller that folded nothing has no shorter view to be handed")
              ;; THE ORDER IS THE FEATURE (owner, 2026-10-05): the start row goes out BEFORE the
              ;; summary call and the result after it, so the page shows two rows about one compaction
              ;; in the order they happened. A compaction took 96 SECONDS on the session this was
              ;; measured on, and a page that said nothing until the answer arrived was unreadable
              ;; for all of it.
              (is (= ["compacted-context" "compacted-context"] (mapv :name @said))
                  "two rows, and both are the card the success path already uses -- same part name")
              (is (= ["pending" "failed"] (mapv #(get-in % [:value :outcome]) @said))
                  "the first says it is happening, the second says it did not land")
              (is (nil? (get-in (first @said) [:value :error]))
                  "and the start row names no reason, because at that moment there is not one yet")
              (is (str/includes? (str (get-in (second @said) [:value :error])) "rate_limit_exceeded")
                  "carrying the vendor's own words, which are the difference between try-later and buy-more")
              (let [ids (mapv :messageId @said)]
                ;; ONE COMPACTION, ONE ID, on both rows: two ids for one compaction is two card
                ;; identities, and a rebuild draws those as two things rather than one.
                (is (= 2 (count ids)))
                (is (= 1 (count (distinct ids))))
                (is (every? string? ids)
                    (str "AND BOTH HAVE A NAME: a failure never gets one from perform!, and a card "
                         "folded under no id is one a rebuild draws a second time"))))
            (finally
              (providers/use-provider! thread-id nil)
              (io/delete-file log true)))))
      (testing "the record still says what happened, whatever the card says"
        (let [thread-id "auto-refused-record"
              log       (plant! thread-id (auto-rows 8000))]
          (pin! thread-id 8000 [{:refuse {:status 429 :body "rate_limit_exceeded"}}])
          (try
            (#'http/announce-compaction! thread-id (providers/current-provider thread-id)
                                        (replay/read-records log) 8000
                                        (compaction/config thread-id)
                                        (fn [_]) nil)
            (let [rows  (wait-for-rows log "compaction/end")
                  ends  (filter #(= "compaction/end" (replay/kind %)) rows)
                  kinds (mapv replay/kind rows)]
              (is (some #{"compaction/start"} kinds) "the attempt is on the record")
              (is (not (some #{"context/compacted"} kinds))
                  "and nothing was landed: the conversation keeps its history")
              (is (str/includes? (str (:error (replay/payload (last ends)))) "rate_limit_exceeded")
                  "the failure is recorded as one, which it always was"))
            (finally
              (providers/use-provider! thread-id nil)
              (io/delete-file log true)))))
      (testing "and a compaction that worked announces the same way, with an unchanged result frame"
        (let [thread-id "auto-folded"
              log       (plant! thread-id (auto-rows 8000))]
          (pin! thread-id 8000 [{:content "A SUMMARY"}])
          (try
            (let [said   (atom [])
                  answer (#'http/announce-compaction! thread-id (providers/current-provider thread-id)
                                                         (replay/read-records log) 8000
                                                         (compaction/config thread-id)
                                                         (fn [frame] (swap! said conj frame))
                                                         nil)]
              (is (some? answer) "the compaction's own map, which the caller goes on with")
              ;; THE SUCCESS ROW'S OUTCOME IS NIL, NOT "folded", and that is the compatibility
              ;; guarantee stated as a test: `compacted-frame` has carried no `outcome` key since the
              ;; card existed, so a reader that knows nothing about this change reads it as before.
              (is (= ["pending" nil] (mapv #(get-in % [:value :outcome]) @said))
                  "the start row, then the result -- and the result says nothing about its outcome")
              (is (= "A SUMMARY" (get-in (second @said) [:value :summary])))
              (is (nil? (get-in (second @said) [:value :outcome]))
                  "and NO outcome key on the result, so the success frame is byte-for-byte what it always was")
              (is (= (:compactionId answer) (:messageId (second @said)))
                  "and it is folded under the compaction's own id, the one a rebuild hands back"))
            (finally
              (providers/use-provider! thread-id nil)
              (io/delete-file log true)))))
      (finally (stop)))))

(deftest the-trigger-measures-the-window-the-run-goes-out-under-not-the-records
  ;; THE 2026-10-02 INCIDENT, thread `88f8d8eb-…`: the record's newest call declared 256k (the
  ;; session's in-memory override was dropped by a restart, so the record still ends on the
  ;; model it had left), the run about to go out declared 1M, and the pre-run trigger divided
  ;; 215,524 tokens by the 256k and folded 160k of them into a summary at 84% of a window the
  ;; run was not using. THE RECORD'S WINDOW IS THE PREVIOUS CALL'S MODEL -- so both halves
  ;; below read the SAME kind of record under two different windows, and the window that
  ;; decides is the run's.
  (let [stop (http/start! {:port 0})]
    (try
      (testing "the record says 8k and the run says 128k: nothing happens"
        (let [thread-id "window-run-wider"
              log       (plant! thread-id (auto-rows 8000))]
          (pin! thread-id 128000 [{:content "SHOULD NOT BE USED"}])
          (try
            (#'http/compact-if-pressured! thread-id)
            (let [ks (mapv replay/kind (wait-for-rows log "compaction/start"))]
              (is (not-any? #{"compaction/start" "context/compacted"} ks)
                  "sixteen times the room the record described: there is nothing to fold"))
            (finally
              (providers/use-provider! thread-id nil)
              (io/delete-file log true)))))
      (testing "the record says 100k and the run says 8k: it compacts"
        (let [thread-id "window-run-narrower"
              log       (plant! thread-id (auto-rows 100000))]
          (pin! thread-id 8000 [{:content "AUTO SUMMARY"}])
          (try
            (#'http/compact-if-pressured! thread-id)
            (let [ks (mapv replay/kind (wait-for-rows log "compaction/end"))]
              (is (some #{"compaction/start"} ks)
                  "the record says there is room; the run about to go out has none"))
            (finally
              (providers/use-provider! thread-id nil)
              (io/delete-file log true)))))
      (finally (stop)))))

(deftest the-compaction-proportions-default-and-refuse-a-broken-pair
  (testing "nobody said anything: the endorsed defaults"
    (is (= pressure/default-ratios (compaction/config "cfg-default"))))
  (testing "a retain that is not strictly below the threshold cannot work"
    (is (thrown? clojure.lang.ExceptionInfo
                 (compaction/check-ratios! {:threshold-ratio 0.5 :retain-ratio 0.6}))))
  (testing "a value that is not a fraction is not a proportion"
    (is (thrown? clojure.lang.ExceptionInfo
                 (compaction/check-ratios! {:threshold-ratio 2 :retain-ratio 0.1}))))
  (testing "a legal pair passes through"
    (is (= {:threshold-ratio 0.8 :retain-ratio 0.2}
           (compaction/check-ratios! {:threshold-ratio 0.8 :retain-ratio 0.2})))))

;; ------------------------------------------------------- the overflow plan (ticket 05)

(deftest the-overflow-plan-keeps-only-the-newest-unit
  ;; After the vendor has refused the request for LENGTH, the budget is ignored: everything
  ;; before the newest `user` message goes, and only that turn is kept verbatim.
  (let [plan (compaction/overflow-plan (six))]     ;; six 108-token user entries u0..u5
    (is (= [0 1 2 3 4] (:shadowed plan)) "the newest unit (u5) is the only thing kept")
    (is (= 5 (count (:messages plan))))
    (is (= 540 (:head-tokens plan)) "5 x 108")))

(deftest the-overflow-plan-has-nothing-to-remove-when-only-the-unit-is-left
  (is (nil? (compaction/overflow-plan [(entry 0 "u1" "hi")]))))

(deftest the-overflow-plan-never-takes-the-session-opening
  (let [opening (assoc (entry 0 "session-opening-0" (apply str (repeat 400 "o")))
                       :source "opening")
        records (into [opening]
                      (map (fn [i] (entry (inc i) (str "u" i) (apply str (repeat 400 "a"))))
                           (range 6)))
        plan    (compaction/overflow-plan records)]
    (is (some? plan))
    (is (not (some #{0} (:shadowed plan))) "the opening's seq is not among them")))

(deftest the-overflow-plan-keeps-a-tool-group-whole
  ;; A suffix may not BEGIN with a `tool` result -- an OpenAI-shaped vendor refuses a tool
  ;; message whose assistant `tool_calls` are not in the request -- so when the surface holds no
  ;; user message the unit is the smallest LEGAL suffix.
  (let [row (fn [ts id payload]
              {:ts ts :runId "r1" :type "message" :source "client" :id id :payload payload})
        call (fn [i] {:id i :type "function" :function {:name "read" :arguments "{}"}})
        records [(row 0 "a0" {:role "assistant" :content "" :tool_calls [(call "c1")]})
                 (row 1 "t1" {:role "tool" :tool_call_id "c1" :content "ok"})
                 (row 2 "a2" {:role "assistant" :content "" :tool_calls [(call "c2")]})
                 (row 3 "t2" {:role "tool" :tool_call_id "c2" :content "ok"})]
        plan    (compaction/overflow-plan records)]
    (is (= [0 1] (:shadowed plan)) "the last legal group is kept, the older tool round goes")))

;; ------------------------------------- a thought the record kept (ticket 01)

(defn- frame-rows
  "One run's AG-UI frames AS THE RECORD KEEPS THEM: the edge's OWN converter builds them
  (`ag/outbound`), so a fixture cannot spell a frame the server never writes."
  [events]
  (let [emit (ag/outbound "reasoning" "r1")]
    (mapv (fn [f] {:ts 0 :runId "r1" :type "event" :payload f}) (mapcat emit events))))

(defn- model-row
  "The RUN's own row for one model call -- what `harness.edge.http/log-messages!` writes:
  the assistant message the vendor returned, REASONING INCLUDED, under the `model` source.
  It is where the record's copy of a thought lives (`harness.edge.replay/reasoning-row?`)."
  [ts content reasoning]
  {:ts ts :runId "r1" :type "message" :source "model" :id "r1-m0"
   :payload {:role "assistant" :content content :reasoning_content reasoning}})

(deftest a-summary-call-goes-out-in-a-shape-the-vendor-reads
  ;; A RECORD THAT KEPT ITS THOUGHT. The frames carry the answer; the run's own row carries
  ;; the reasoning; the fold puts the thought back in FRONT of that answer as a message of its
  ;; own (`harness.edge.replay/attach-reasoning`). The plan hands exactly those messages to the
  ;; summarizer -- and a provider refuses the ROLE they are spelled with (`messages[N].role:
  ;; unknown variant `reasoning`; measured on a real log: 15 compactions of one session, 15
  ;; refusals, and not one `context/compacted`). A summary call IS a provider call, so it goes
  ;; out folded -- by the same rule a run's call goes out by.
  (let [thought (apply str (repeat 4000 "想"))
        big     (fn [i] (entry i (str "u" i) (apply str (repeat 4000 "a"))))
        ;; THE THOUGHT SITS EARLY AND THE CONVERSATION CONTINUES PAST IT -- the only shape in
        ;; which the head the summarizer is handed still CONTAINS it: the plan keeps the newest
        ;; words verbatim, so a thought at the very end is never summarized at all.
        rows    (vec (concat (map big (range 3))
                             (frame-rows [(ev/run-start)
                                          (ev/text-delta "an answer")
                                          (ev/run-end)])
                             [(model-row 4 "an answer" thought)]
                             (map big (range 5 35))))
        log     (plant! "compact-reasoning" rows)
        stop    (http/start! {:port 0})]
    (providers/use-provider! "compact-reasoning" (fake/scripted [{:content "THE SUMMARY"}]))
    (try
      (is (some #(= "reasoning" (:role %))
                (:messages (compaction/plan (replay/read-records log) 128000 0.16)))
          "the fixture really does fold a thought of its own -- or this proves nothing")
      (let [resp (#'http/compact-post nil "compact-reasoning")
            body (json/read-str (String. ^bytes (:body resp) "UTF-8") :key-fn keyword)]
        (is (= 200 (:status resp)) (pr-str body))
        (is (true? (:compacted body)) "the summary call was accepted and answered")
        (let [rows (wait-for-rows log "compaction/end")]
          (is (some #{"context/compacted"} (mapv replay/kind rows))
              "the summary reached the record")
          (is (not-any? (fn [r] (and (= "compaction/end" (replay/kind r))
                                     (:error (replay/payload r))))
                        rows)
              "and the pair closed with no error on it")))
      (finally
        (stop)
        (io/delete-file log true)
        (providers/use-provider! "compact-reasoning" nil)))))

;; ---------------------------------- nothing of ours rides on the prompt (owner, 2026-10-01)

(deftest the-router-hands-the-summarizer-the-instruction-and-nothing-else
  ;; FOLLOW THE REFERENCE'S POLICY EXACTLY. Three things of ours used to ride on the summary
  ;; request, and this route is where they were attached (`:environment` / `:blocks`):
  ;;
  ;;   - git facts (`Where this work is happening`, owner's addition of 2026-09-27) -- read in the
  ;;     BOUND checkout, so a session working in a worktree was told about somebody else's
  ;;     uncommitted files (thread `a0621fce-...`, 2026-10-01);
  ;;   - the `Already produced` list, which covered only the range being folded -- never the part
  ;;     of the conversation the model actually mis-read;
  ;;   - a `:pre-compact` hook's words, which the reference's request does not carry at all.
  ;;
  ;; What tells a continuing model who it is now: the retained tail verbatim, the checkpoint's
  ;; preamble, and `## Current Work` / `## Next Step` at the foot of the summary.
  (let [thread-id "compact-plain"
        rows      (mapv (fn [i] (entry i (str "u" i) (apply str (repeat 4000 "a")))) (range 25))
        log       (plant! thread-id rows)
        stop      (http/start! {:port 0})]
    (providers/use-provider! thread-id (fake/scripted [{:content "THE SUMMARY"}]))
    (try
      (is (= 200 (:status (#'http/compact-post nil thread-id))))
      (let [asked (:instruction (replay/payload
                                 (first (filter #(= "compaction/start" (replay/kind %))
                                                (wait-for-rows log "compaction/end")))))]
        (is (= compaction/summary-instruction asked)
            "the prompt on the row is the instruction, byte for byte")
        (is (not (str/includes? asked "Already produced")))
        (is (not (str/includes? asked "worktree:"))))
      (finally
        (stop)
        (io/delete-file log true)
        (providers/use-provider! thread-id nil)))))

;; ------------------------------------- the exit guards (ticket 05) and the prefix (ticket 07)

(deftest a-summary-that-is-not-smaller-is-refused
  ;; THE REFERENCE'S EXIT GUARD (`dsh-compaction-basic`'s `summarizeCompaction`: a framed
  ;; checkpoint that does not price below the shadowed range is refused). We had only the ENTRY
  ;; guard; without this one a summary that is no shorter lands, the pressure stays where it
  ;; was, and a model call was burnt discovering it (`.scratch/compaction-shape`: four folds in
  ;; two and a half minutes, every one folding a summary into another).
  (let [written (atom [])
        huge    (apply str (repeat 40000 "x"))
        thrown  (try
                  (compaction/perform! (six) {:window 1000 :retain-ratio 0.16
                                              :append    (fn [k p] (swap! written conj [k p]))
                                              :summarize (fn [_ _] huge)})
                  nil
                  (catch Throwable t t))]
    (is (instance? Throwable thrown) "the compaction fails")
    (is (str/includes? (ex-message thrown) "not smaller"))
    (is (= ["compaction/start" "compaction/end"] (mapv first @written))
        "the pair closes as a failure -- a compaction that failed is recorded as one")
    (is (not-any? #(= "context/compacted" (first %)) @written)
        "and NOTHING was landed: the conversation keeps its history")))

(deftest the-output-cap-defaults-and-refuses-a-number-that-is-not-one
  ;; The reference caps the summary call (8192 output tokens) and treats a truncated checkpoint
  ;; as a hard error. The cap is configurable, so it is validated like every other number this
  ;; harness takes from config.edn: by name.
  (testing "nobody said anything: the reference's own 8192"
    (is (= 8192 compaction/default-max-tokens))
    (is (= compaction/default-max-tokens (compaction/max-tokens "cfg-default"))))
  (testing "a legal cap passes through"
    (is (= 4096 (compaction/check-max-tokens! 4096))))
  (testing "a number that is not a positive whole one is refused, by name"
    (doseq [bad [0 -1 1.5 :none "8192"]]
      (let [t (try (compaction/check-max-tokens! bad) nil (catch Throwable e e))]
        (is (instance? clojure.lang.ExceptionInfo t) (pr-str bad))
        (is (str/includes? (ex-message t) "max-tokens") (pr-str bad))))))

(deftest a-summary-call-rides-the-conversations-own-prefix
  ;; THE REFERENCE'S CACHE TRICK (`dsh-compaction-basic`: 'makes the auxiliary call a genuine
  ;; prefix of the last routed request, so the provider's warm prefix cache is reused'): the
  ;; system message and the tool table, then the shadowed region, then the instruction.
  ;;
  ;; WHAT IS ASSERTED IS THE ARRAY THAT WENT OUT, read off the double's own record of it
  ;; (`harness.fake`'s opt-in `:calls`): a request's shape is otherwise only observable on the
  ;; wire.
  (let [thread-id "compact-prefix"
        rows      (mapv (fn [i] (entry i (str "u" i) (apply str (repeat 4000 "a")))) (range 25))
        log       (plant! thread-id rows)
        stop      (http/start! {:port 0})
        calls     (atom [])
        tools     [{:type "function" :function {:name "read"
                                                 :description "d"
                                                 :parameters {:type "object" :properties {}}}}]]
    (providers/use-provider! thread-id (fake/scripted [{:content "THE SUMMARY"}] {:calls calls}))
    (try
      (let [result (#'http/run-compaction! thread-id (providers/current-provider thread-id)
                                           (replay/read-records log) 128000
                                           (compaction/config thread-id)
                                           {:prefix [{:role "system" :content "THE PROMPT"}]
                                            :tools  tools})
            {:keys [messages tools] :as _asked} (last @calls)]
        (is (some? result) "the compaction ran")
        (is (some? (last @calls)) "the summary call reached the provider")
        (is (= "THE PROMPT" (:content (first messages)))
            "the summary call OPENS with the conversation's own system message")
        (is (= ["read"] (mapv #(get-in % [:function :name]) tools))
            "and carries the session's tool table -- the rest of the request's prefix")
        (is (str/includes? (str (:content (last messages))) "compaction engine")
            "with the instruction last, as always")
        (is (not-any? #(= "system" (:role %)) (rest messages))
            "and the conversation itself is not re-framed: the prefix is what a run is handed"))
      (finally
        (stop)
        (io/delete-file log true)
        (providers/use-provider! thread-id nil)))))
