(ns harness.edge.compaction-test
  "The compaction projection, asserted over HAND-WRITTEN RECORDS.

  Ticket 02 is the READ half of compaction and nothing else: a compaction the record
  declares changes what the MODEL is handed -- the shadowed messages drop out and one
  summary message stands where they stood -- while the record keeps every row and the
  client keeps reading the originals. So these cases pin three things:

    - the model view shrinks and the summary goes to the RIGHT PLACE (the head of the
      range, not the end of the log);
    - the conversation projection (`replay/entries`) is untouched;
    - a SECOND compaction that shadows the first summary works, and its range inverts
      (`start` greater than `end`) -- the case a numeric interval comparison gets wrong."
  (:require [clojure.test :refer [deftest is testing]]
            [harness.edge.replay :as replay]
            [harness.edge.ag-ui :as ag]
            [harness.edge.compaction :as compaction]
            [harness.kernel.frames :as frames]
            [clojure.string :as str]))

;; ------------------------------------------------------------------ the records

(defn- entry
  "A message the CLIENT sent: a `message` row whose envelope carries the id and the source
  `replay/entries` recognises a conversation entry by. Its record offset is the seq the
  compaction facts name."
  [ts id text]
  {:ts ts :runId "r1" :type "message" :payload {:role "user" :content text}
   :source "client" :id id})

(defn- compacted
  "A compaction FACT: an `event` row carrying the `context/compacted` CUSTOM frame. Its own
  record offset is the identity a later compaction shadows it by."
  ([ts shadowed] (compacted ts shadowed "S"))
  ([ts shadowed summary]
   {:ts ts :runId "r1" :type "event"
    :payload {:type "CUSTOM" :name "context/compacted"
              :value {:summary summary
                      :shadowed (vec shadowed)
                      :range (when (seq shadowed) {:start (first shadowed) :end (last shadowed)})}}}))

(defn- model-view [records]
  (replay/compacted-messages (replay/entries (vec records))
                             (replay/compaction-facts (vec records))))

(defn- conversation [records]
  (mapv (comp :content :message) (replay/entries (vec records))))


(defn- folded
  "SUMMARY -> the content the model reads in its place: the checkpoint's preamble, a blank line,
  and the summary inside its tags (`replay/compaction-summary`). Spelled once here so the cases
  below pin the SHAPE -- a reader can find the tag, and a model is told that what follows is the
  newer half -- instead of re-typing the sentence."
  [summary]
  (:content (replay/compaction-summary summary)))

(defn- lifecycle
  "One `compaction/start` or `compaction/end` row, as the edge writes it (`log!` wraps a kind in
  the CUSTOM frame whose name is that kind)."
  [ts name id]
  {:ts ts :runId "r1" :type "event"
   :payload {:type "CUSTOM" :name name :value {:compactionId id}}})

(defn- a-process-was-killed-here
  "One `session/closed-off` row -- the record's own boundary: a reader found the log ending
  MID-RUN, i.e. the process that owned those rows is gone (`harness.edge.http`)."
  [ts]
  {:ts ts :runId "r1" :type "event"
   :payload {:type "CUSTOM" :name "session/closed-off"
             :value {:run-id "r1" :last-frame "RUN_ERROR" :frames ["RUN_ERROR"]}}})

(deftest a-start-whose-process-is-gone-is-not-a-lock
  ;; THE CRASH CASE, and it is not hypothetical: a process killed between `compaction/start` and
  ;; `compaction/end` leaves an unmatched start, and the next process reads that record (and
  ;; writes `session/closed-off` for the run it found cut off). A lock inherited from a dead
  ;; process is PERMANENT -- every later compaction is refused, and nothing in the harness can
  ;; clear it.
  (let [start (lifecycle 1 "compaction/start" "c1")]
    (is (= "c1" (compaction/lock-active? [start])) "an unmatched start holds the lock")
    (is (nil? (compaction/lock-active? [start (a-process-was-killed-here 2)]))
        "a boundary written after it says that process is gone: the lock is not inherited")
    (is (= "c2" (compaction/lock-active? [start (a-process-was-killed-here 2)
                                          (lifecycle 3 "compaction/start" "c2")]))
        "while a start written after the boundary holds one of its own")
    (is (nil? (compaction/lock-active? [start (lifecycle 4 "compaction/end" "c1")]))
        "a matched pair is over, exactly as before")))

(deftest a-compaction-key-nobody-reads-is-refused-by-name
  ;; A KEY NOBODY READS IS A KNOB NOBODY TURNED: `:retain-ration` or `:maxTokens` beside a
  ;; correct-looking value is a silent no-op, and the reference refuses the like when its config
  ;; loads. Ours reads config.edn lazily, so the refusal happens at the first read.
  (is (= {:threshold-ratio 0.7 :retain-ratio 0.16}
         (compaction/check-keys! {:threshold-ratio 0.7 :retain-ratio 0.16}))
      "the keys the code reads pass straight through")
  (let [all (zipmap compaction/known-keys (repeat 1))]
    (is (= all (compaction/check-keys! all)) "and so does every one of them together"))
  (is (= {} (compaction/check-keys! {})) "a session that configured nothing is not a typo")
  (let [t (try (compaction/check-keys! {:retain-ratio 0.1 :retain-ration 0.2})
               nil
               (catch Throwable e e))]
    (is (instance? clojure.lang.ExceptionInfo t) "a key nothing reads is refused")
    (is (str/includes? (ex-message t) "retain-ration") "naming the offender")
    (is (str/includes? (ex-message t) "retain-ratio") "beside the key it was meant to be")))

;; -------------------------------------------------------------- the projection

(deftest a-compaction-hides-its-range-from-the-model-and-leaves-the-record-alone
  (let [records [(entry 0 "u1" "one")
                 (entry 1 "u2" "two")
                 (entry 2 "u3" "three")
                 (entry 3 "u4" "four")
                 (compacted 4 [0 1] "S1")
                 (entry 5 "u5" "five")]]
    (is (= ["one" "two" "three" "four" "five"] (conversation records))
        "the conversation keeps every original -- the client reads the source")
    (is (= [(folded "S1") "three" "four" "five"]
           (mapv :content (model-view records)))
        "the model reads the summary where the range stood, and the tail in order")
    (is (= 6 (count records)) "the record keeps every row, the fact included")))

(deftest a-second-compaction-can-shadow-the-first-summary-and-the-range-inverts
  (let [records [(entry 0 "u1" "one")
                 (entry 1 "u2" "two")
                 (entry 2 "u3" "three")
                 (entry 3 "u4" "four")
                 (compacted 4 [0 1] "S1")
                 (entry 5 "u5" "five")
                 (compacted 6 [4 2] "S2")]
        facts   (replay/compaction-facts (vec records))]
    (is (= [4 2] (:shadowed (second facts)))
        "the second range names the first summary (seq 4) and an OLDER node (seq 2): start > end")
    (is (= [(folded "S2") "four" "five"]
           (mapv :content (model-view records)))
        "the first summary is gone, the second stands at the range's head, the tail survives")))

(deftest a-single-node-range-still-lands-its-summary-in-place
  ;; WITH ONE NODE `start` AND `end` ARE THE SAME NUMBER, so a numeric comparison would
  ;; happen to work here -- which is exactly why the fold must not rely on it. This is the
  ;; case that passes for the wrong reason.
  (let [records [(entry 0 "u1" "one")
                 (entry 1 "u2" "two")
                 (entry 2 "u3" "three")
                 (compacted 3 [1] "S")]]
    (is (= ["one" (folded "S") "three"]
           (mapv :content (model-view records))))))

(deftest two-non-overlapping-compactions-both-stand-in-order
  (let [records [(entry 0 "u1" "one")
                 (entry 1 "u2" "two")
                 (entry 2 "u3" "three")
                 (entry 3 "u4" "four")
                 (entry 4 "u5" "five")
                 (entry 5 "u6" "six")
                 (compacted 6 [0] "HEAD")
                 (compacted 7 [4 5] "TAIL")]]
    (is (= [(folded "HEAD") "two" "three" "four"
            (folded "TAIL")]
           (mapv :content (model-view records)))
        "each summary stands where its own range stood, and the untouched middle survives")))

(deftest a-compaction-that-shadows-nothing-changes-nothing
  (testing "a seq that is not on the surface"
    (let [records [(entry 0 "u1" "one") (entry 1 "u2" "two") (compacted 2 [99] "S")]]
      (is (= ["one" "two"] (mapv :content (model-view records))))))
  (testing "an empty shadowed list"
    (let [records [(entry 0 "u1" "one") (compacted 1 [] "S")]]
      (is (= ["one"] (mapv :content (model-view records)))))))

(deftest the-fold-does-not-touch-what-it-reads
  (let [records [(entry 0 "u1" "one") (entry 1 "u2" "two") (compacted 2 [0] "S")]
        entries (replay/entries (vec records))
        facts   (replay/compaction-facts (vec records))]
    (replay/compacted-messages entries facts)
    (is (= 2 (count entries)) "the entries vector is unchanged")
    (is (= [0 1] (mapv :seq entries)))
    (is (= [2] (mapv :seq facts)))
    (is (= 3 (count records)) "and the records vector is unchanged")))

;; --------------------------------------------------- the card never reaches the model

(defn- injected
  "A run's own injection: the `injected-context` CUSTOM frame `frames/apply-frames` folds
  into one CARD-ONLY message, whose `:data` carries the role and text it views."
  [ts run-id message-id role text]
  {:ts ts :runId run-id :type "event"
   :payload {:type "CUSTOM" :name "injected-context" :messageId message-id
             :value {:role role :text text}}})

(defn- big [i] (entry i (str "u" i) (apply str (repeat 400 "a"))))

(deftest a-data-card-never-reaches-a-compaction-plan
  ;; THE BUG THIS PINS (2026-09-24, thread `bbcd4ae4-…`): the plan handed the summarizer the
  ;; record's own CARD (a `data` part) and the vendor refused the whole request --
  ;; HTTP 422 `unknown variant \`data\``. The model view REALISES the card into the message
  ;; it views, and the node is KEPT, so the range `:shadowed` names does not move.
  (let [records [(big 0)
                 (injected 1 "r1" "r1-pre0" "user" "SKILL BODY")
                 (big 2) (big 3) (big 4) (big 5)]
        entries (replay/entries (vec records))
        nodes   (replay/model-nodes entries (replay/compaction-facts (vec records)))
        plan    (compaction/plan records 1000 0.16)]
    (is (= (count entries) (count nodes))
        "an injected card realises to its text; the node it was is not lost")
    (is (not-any? (fn [n] (some #(= "data" (:type %)) (:content (:message n)))) nodes)
        "no node's message carries a `data` part")
    (is (some? plan) "six nodes, a retained tail of two: there is a head to compact")
    (is (not-any? (fn [m] (some #(= "data" (:type %)) (:content m))) (:messages plan))
        "what the summarizer is handed carries no `data` card -- the 422's own shape")
    (is (some #(= "SKILL BODY" (:content %)) (:messages plan))
        "the card's own text is what the model reads in its place")
    (is (= 4 (count (:shadowed plan))) "four oldest nodes, ids unchanged")))

(deftest the-opening-is-never-in-a-compaction-head
  ;; THE FIX OF 2026-09-27: the opening blocks are NOT the conversation's first nodes -- the
  ;; edge writes system, then the person's question, THEN the opening blocks -- so the old
  ;; `take-while` over a protected prefix stopped at node 0 and folded the opening into
  ;; every summary. A protected node is protected WHEREVER it sits.
  (let [records [(entry 0 "u1" "the first question of this session")
                 (assoc (entry 1 (str ag/opening-entry-prefix "0") "<instructions/>")
                        :source "opening")
                 (entry 2 "u2" "the second question of this session")
                 (entry 3 "u3" "the third question of this session")
                 (entry 4 "u4" "the fourth question of this session")
                 (entry 5 "u5" "the fifth question of this session")]
        plan    (compaction/plan (vec records) 1000 0.001)]
    (is (some? plan) "six nodes and a one-token tail: there is a head to compact")
    (is (not-any? #{1} (:shadowed plan)) "the opening node is never shadowed")
    (is (<= 2 (first (:shadowed plan))) "the head starts after the opening")))

(deftest the-card-is-still-in-the-conversation-the-client-reads
  (let [records [(entry 0 "u1" "one")
                 (injected 1 "r1" "r1-pre0" "user" "SKILL BODY")
                 (entry 2 "u2" "two")]]
    (is (some (fn [e] (some #(= "data" (:type %)) (:content (:message e))))
             (replay/entries (vec records)))
        "the client's view keeps the card -- only the model's view realises it")))

;; ------------------------------------------------- the compaction CARD (a frame)

(defn- compaction-card
  "A compaction's CARD as the record keeps it: an `event` row whose payload is the CUSTOM frame
  `harness.edge.ag_ui/compacted-frame` builds (`.scratch/compaction-frames`)."
  [ts compaction-id summary tokens shadowed]
  {:ts ts :runId "r1" :type "event"
   :payload (ag/compacted-frame {:compactionId compaction-id
                                 :summary      summary
                                 :tokens       tokens
                                 :shadowed     shadowed})})

(deftest a-compaction-card-folds-into-a-message-the-model-never-reads
  ;; THE OTHER HALF OF THE CARD RULES (`the-card-is-still-in-the-conversation-the-client-reads`,
  ;; and `.scratch/compaction-frames`): the frame folds into ONE card-only assistant message --
  ;; which is what a rebuild hands the page, and what the UI puts the part back into -- while
  ;; `model-view` drops it. Both halves matter: a card the model could read would be a summary
  ;; paid for twice, and a card the client could not read would be a compaction nobody sees.
  (let [records [(entry 0 "u1" "one")
                 (compaction-card 1 "c-1" "the summary" 1234 [0 1])
                 (entry 2 "u2" "two")]
        entries (replay/entries (vec records))
        card    (second entries)]
    (is (= 3 (count entries)) "the client's conversation keeps the card")
    (is (= "assistant" (:role (:message card))))
    (is (= [{:type "data" :name ag/compacted-part-name
             :data {:summary "the summary" :tokens 1234 :messages 2}}]
           (:content (:message card)))
        "one `data` part, named for the card, carrying the summary and what it replaced")
    (is (= [{:id "c-1"
             :role "assistant"
             :content [{:type "data" :name ag/compacted-part-name
                        :data {:summary "the summary" :tokens 1234 :messages 2}}]}]
           (frames/apply-frames [(:payload (second records))]))
        "and the fold is what makes that message -- one card per frame, under the frame's id")
    (is (= ["one" "two"] (mapv :content (model-view records)))
        "and the model's view is the two entries: the card is never handed to anyone")))

(deftest the-frame-of-a-real-compaction-is-the-whole-answer
  ;; `perform!` -> the frame, END TO END: the id the rows carry is the id the card is folded
  ;; under, which is what makes a rebuild hand back the SAME card (`sessions/append!` dedupes
  ;; by id, first-wins), and what keeps two compactions from sharing one card.
  (let [records (vec (map big (range 6)))
        written (atom [])
        result  (compaction/perform! records {:window 1000 :retain-ratio 0.16
                                              :append    (fn [k p] (swap! written conj [k p]))
                                              :summarize (fn [msgs _] (str "SUMMARY of " (count msgs)))})
        fact    (second (second @written))
        frame   (ag/compacted-frame result)]
    (is (= (:compactionId fact) (:messageId frame))
        "the card is folded under the compaction's own id")
    (is (= (:summary fact) (get-in frame [:value :summary])))
    (is (= (:tokens fact) (get-in frame [:value :tokens])))
    (is (= 4 (get-in frame [:value :messages])) "and it says how many nodes went into it")
    (is (= (:compactionId result) (:compactionId fact))
        "the answer names the rows it wrote, not a second id")))


(defn- step-row
  "A step boundary as the record holds it: a CUSTOM fact named `step/start` or `step/end`."
  [ts name]
  {:ts ts :runId "r1" :type "event"
   :payload {:type "CUSTOM" :name name :value {}}})

(deftest the-head-only-ends-where-a-step-ends
  ;; ticket 02 of `.scratch/compaction-by-step`: a cut between a model call and the tools it
  ;; asked for is a request a vendor refuses (HTTP 400). The tail starts on a step's first
  ;; node, so every earlier step stays whole.
  (let [records [(entry 0 "u1" "one") (step-row 1 "step/start") (entry 2 "u2" "two")
                 (step-row 3 "step/end") (entry 4 "u3" "three") (step-row 5 "step/start")
                 (entry 6 "u4" "four") (step-row 7 "step/end")]
        nodes   (replay/model-nodes (replay/entries (vec records))
                                    (replay/compaction-facts (vec records))
                                    [])
        starts  (#'compaction/step-start-indexes nodes records)]
    (is (= [1 3] starts) "each step's first node, in order")
    (is (= 0 (#'compaction/tail-anchor nodes records 0)) "the first user message")
    (is (= 1 (#'compaction/tail-anchor nodes records 2))
        "a tail asked to start inside the second node backs off to the step it falls inside")
    (is (= 3 (#'compaction/tail-anchor nodes records 3)))))

(deftest the-summary-skeleton-ends-at-the-seam
  ;; COPIED FROM THE REFERENCE (`dsh-compaction-basic`'s `COMPACTION_INSTRUCTION`): the sections
  ;; are fixed and the last two are Current Work / Next Step, an empty section is still a
  ;; section, and the rules forbid editorialising about provenance. A free-form instruction is
  ;; what produced the summary of the owner's incident (thread `a0621fce-...`, 2026-10-01): a
  ;; `# State` block naming an OLD commit and tickets 'not yet done', plus 'not created by me'
  ;; -- which the model then read as the present.
  (let [instruction compaction/summary-instruction
        sections    ["## Primary Request and Intent" "## Key Technical Concepts"
                     "## Files and Code" "## Errors and Fixes" "## Pending Jobs"
                     "## Current Work" "## Next Step" "## Critical Context"]
        positions   (mapv #(.indexOf ^String instruction %) sections)]
    (is (every? #(>= (long %) 0) positions) "every section is asked for")
    (is (= (sort positions) positions) "in the reference's own order")
    (is (< (.indexOf ^String instruction "## Current Work")
           (.indexOf ^String instruction "## Next Step"))
        "and the last two are where the work stands now and what comes next")
    (is (str/includes? instruction "Write \"(none)\" for an empty section")
        "an empty section is written, never dropped")
    (is (str/includes? instruction
                       "Do NOT mention this summarization request or that the context was compacted.")
        "no editorialising about provenance -- the rule the incident's summary broke")
    (is (str/includes? instruction "Do not copy it forward verbatim")
        "a second fold merges the first checkpoint instead of copying it forward")
    (is (not (str/includes? instruction "Already produced"))
        (str "and NOTHING of ours rides on it: the summarizer gets the reference's message and",
             " nothing else (owner, 2026-10-01)"))))


(deftest the-summary-request-puts-a-late-answer-behind-its-call
  ;; THE BUG OF 2026-09-28, measured on a live session: 23 compactions, 23 refusals, every
  ;; one `tool calls and tool results do not match`. A run cut off mid-call is repaired by
  ;; `closing-frames`, whose TOOL_CALL_RESULT is APPENDED -- so in the record the answer sits
  ;; behind LATER messages. The run path moves it back (`llm/adjacent-answers`); the summary
  ;; path did not, and could never compact.
  (let [msgs [{:role "assistant" :content ""
               :tool_calls [{:id "c1" :type "function"
                             :function {:name "read" :arguments "{}"}}]}
              {:role "user" :content "meanwhile, something else"}
              {:role "tool" :tool_call_id "c1" :content "the answer, late"}
              {:role "user" :content "and later still"}]
        out  (compaction/summary-messages msgs "PROMPT")]
    (is (= "PROMPT" (:content (last out))) "the instruction rides last")
    (is (= ["assistant" "tool" "user" "user"] (mapv :role (butlast out)))
        "the late answer now sits directly behind the call that named it")))

;; ------------------------------------------------- the surface the plan plans over

(deftest the-plan-folds-the-surface-the-caller-hands-it
  ;; TICKET 05 OF `.scratch/compaction-shape`. The trigger measures the array the MODEL is handed
  ;; (`pressure/live-surface`: the session's own messages + the system message + this run's
  ;; injections). The record's fold is NOT that array -- it drops every run's injections except
  ;; the last -- so a plan over the record fold answers a head for a surface nobody measured:
  ;; on the real thread `62f30024-…` (2026-09-30) that head was ONE summary, the fold re-wrote a
  ;; summary the same size, the surface did not move, and the trigger fired again 2 min 22 s worth
  ;; of compactions later. A SURFACE HANDED IN IS WHAT IS PLANNED OVER, ids and all.
  (let [records [(big 0) (big 1) (big 2) (big 3) (big 4) (big 5)]
        ;; THE LIVE ARRAY: the same entries PLUS what only a live conversation has -- the
        ;; injections of earlier runs, which carry the record lines they arrived in as their ids
        ;; and are several times the size of the whole record fold.
        live    (into (mapv (fn [i r] {:id i :message (:payload r)}) (range) records)
                      (map (fn [i] {:id (+ 1000 i)
                                     :message {:role "user" :content (apply str (repeat 4000 "b"))}})
                           (range 4)))
        record-plan (compaction/plan records 2000 0.16)
        live-plan   (compaction/plan records 2000 0.16 live)]
    (testing "the record's own fold answers a head from ITS nodes"
      (is (= [0 1 2] (:shadowed record-plan)) "the three oldest record lines")
      (is (= 324 (:head-tokens record-plan)) "three 400-character entries, framing included"))
    (testing "and a surface handed in is what is planned over -- the ids come from THAT array"
      (is (= 3672 (:head-tokens live-plan))
          "nine nodes: the six of the record fold AND three the live array has besides")
      (is (= [0 1 2 3 4 5 1000 1001 1002] (:shadowed live-plan))
          "the live array's own lines: this head removes nodes the record's fold would have kept")
      (is (> (:head-tokens live-plan) (* 10 (:head-tokens record-plan)))
          "eleven times the relief the record's own fold would have bought for the same window")
      (is (every? some? (:shadowed live-plan))))
    (testing "the guard: a head under the relief the caller needs is no head at all"
      (is (nil? (compaction/plan records 2000 0.16 nil {:min-head-tokens 1000}))
          "324 tokens cannot bring a request that is 1000 tokens over its threshold back down")
      (is (some? (compaction/plan records 2000 0.16 nil {:min-head-tokens 100}))))))

(deftest a-head-that-cannot-be-named-is-not-a-head
  ;; `:shadowed` addresses entries by the record line they arrived in. A live surface's newest
  ;; entries are the ones whose line has not landed yet (`:seq` nil) -- they belong to the
  ;; retained tail -- but a surface that is ENTIRELY unlanded has no addressable head at all,
  ;; and folding it would write rows naming nothing.
  (let [records [(big 0) (big 1) (big 2) (big 3) (big 4) (big 5)]
        unlanded (mapv (fn [i] {:id nil :message {:role "user" :content (apply str (repeat 4000 "c"))}})
                       (range 6))]
    (is (nil? (compaction/plan records 2000 0.16 unlanded)))
    (is (some? (compaction/plan records 2000 0.16 nil)) "the record's fold is still addressable")))

(deftest a-compaction-under-the-asked-relief-writes-nothing-and-calls-nobody
  ;; THE WHOLE POINT OF THE GUARD, asserted where it costs money: `perform!` must not write a
  ;; `compaction/start` row and must not make the summary call.
  (let [records [(big 0) (big 1) (big 2) (big 3) (big 4) (big 5)]
        written (atom [])
        asked   (atom 0)]
    (is (nil? (compaction/perform! records
                                   {:window 2000 :retain-ratio 0.16
                                    :min-head-tokens 1000
                                    :append (fn [k p] (swap! written conj [k p]) nil)
                                    :summarize (fn [_ _] (swap! asked inc) "S")}))
        "a head that cannot relieve anything is no compaction")
    (is (= [] @written) "no rows at all")
    (is (zero? @asked) "and the summarizer was never asked")
    (testing "while the same records compact happily when no relief is demanded"
      (is (some? (compaction/perform! records
                                      {:window 2000 :retain-ratio 0.16
                                       :append (fn [k p] (swap! written conj [k p]) nil)
                                       :summarize (fn [_ _] "S")}))))
      (is (= 3 (count @written)) "start, the fact, end")))
