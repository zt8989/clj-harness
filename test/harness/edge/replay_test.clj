(ns harness.edge.replay-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.edge.ag-ui :as ag]
            [harness.kernel.event :as ev]
            [harness.kernel.frames :as frames]
            [harness.infra.home :as home]
            [harness.infra.logging :as logging]
            [harness.kernel.llm :as llm]
            [harness.edge.replay :as replay]
            [harness.test-support :as support]
            [harness.wire :as wire]))

;; The log lines below are produced by the REAL emitter, not hand-written frames. A
;; log the test invents could encode a frame shape the server never writes, and then
;; the test would pass while replay failed on every real log.

(defn- log-line
  "A record row AS THE WRITER EMITS IT since `.scratch/jsonl-two-kinds`: the file has exactly
  two kinds of row -- `message` (what a person said or an LLM returned) and `event` (every
  other fact) -- with `ts`/`runId` OUTSIDE the payload.

  THE FIXTURES BELOW SAY `{:ts .. :runId .. :kind .. :payload ..}` AS SHORTHAND, and this is
  the one place per file that turns it into the row the file holds: `kind` is the reader's
  own vocabulary (`replay/kind` -- `message`, `event`, or a fact's name), so a fixture reads
  the way an assertion does. The format itself has a test of its own
  (`the-record-has-two-kinds-of-row`) instead of being re-asserted at every call site."
  [{:keys [kind payload] :as m}]
  (json/write-str (merge (dissoc m :kind :payload)
                         (cond
                           (= "message" kind) {:type "message" :payload payload}
                           (= "event" kind)   {:type "event" :payload payload}
                           :else              {:type "event"
                                               :payload {:type "CUSTOM" :name kind
                                                         :value payload}}))))

(defn- client-lines
  "The lines one ACTION's own messages are written as (`.scratch/jsonl-two-kinds` 票 02):
  one `message` row per message that ENTERED the conversation, carrying the identity the
  client knows it by on the ENVELOPE (`:id`) and the verbatim provider message (id stripped,
  as `ag/inbound` strips it) as the payload. The payload stays the provider's own map --
  a `message` row IS an element of the array the model was handed."
  [run-id messages]
  (mapv (fn [m]
          (log-line (cond-> {:ts 1 :runId run-id :kind "message" :source "client"
                             :payload (dissoc m :id)}
                      (:id m) (assoc :id (:id m)))))
        messages))

(defn- prompt-line
  "The system message as the edge writes it (owner, 2026-09-21): a `message` row LIKE ANY
  OTHER element of the array the model was handed, whose envelope says who put it there
  (`:source` = `system-prompt`) and names those bytes (`:hash`)."
  [run-id text]
  (log-line {:ts 1 :runId run-id :kind "message" :source "system-prompt" :hash "h"
             :payload {:role "system" :content text}}))

(defn- event-lines [run-id events]
  (let [emit (ag/outbound "t1" run-id)]
    (mapv (fn [frame] (log-line {:ts 2 :runId run-id :kind "event" :payload frame}))
          (vec (mapcat emit events)))))

(def ^:private reasoning-text "\u7528\u6237\u60f3\u770b\u8fd9\u4e2a\u9879\u76ee\u3002")
(def ^:private tool-text ":paths [\"src\"]")
(def ^:private answer-text "\u8fd9\u662f\u4e00\u4e2a Clojure \u9879\u76ee\u3002")

(def ^:private seed {:id "u1" :role "user" :content "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee"})

(defn- action-lines
  "One action's lines as the edge writes them: the system message the run was handed (the
  row that opens a run, `replay/system-prompt?`) and then the client's own messages."
  [run-id messages]
  (concat [(prompt-line run-id "You are a coding agent.")]
          (client-lines run-id messages)))

(defn- one-run-lines []
  (concat (action-lines "r1" [seed])
          (event-lines "r1" [(ev/run-start)
                             (ev/reasoning-delta reasoning-text)
                             (ev/tool-call "c1" "read" "{\"path\":\"deps.edn\"}")
                             (ev/tool-result "c1" tool-text false)
                             (ev/text-delta answer-text)
                             (ev/run-end)])))

(deftest the-prompt-is-a-row-and-not-a-speaking-part
  ;; THE OWNER'S LINE ON `message` (2026-09-21): a `message` row is an ELEMENT of the
  ;; messages array the model was handed -- the system prompt included, with `:source` =
  ;; `system-prompt` and the bytes' `:hash` on its envelope -- while the CONVERSATION is the
  ;; part of that array the SESSION holds. So the reader that rebuilds the conversation
  ;; (`entries`, and with it `records->messages`, the window, the snapshot and the rebuild)
  ;; must never hand the prompt back: the client never had it, and a client that got one
  ;; would be holding a message no transport ever sent it. ONE rule, in the fold.
  (let [lines    (concat [(prompt-line "r1" "You are a coding agent.")]
                         (one-run-lines))
        rows     (replay/lines->records lines)
        messages (replay/records->messages rows)
        entries  (replay/entries rows)]
    (is (= "You are a coding agent." (get-in (first rows) [:payload :content]))
        "the record HOLDS the prompt -- it is what the model read")
    (is (= "system-prompt" (:source (first rows))) "and says who put it in the array")
    (is (not-any? #(= "system" (:role %)) messages)
        "the rebuilt conversation has no system message in it")
    (is (= seed (first messages)) "the seed is still the first thing the conversation says")
    (is (not-any? #(= "system" (:role (:message %))) entries)
        "and neither have the entries -- what a window, a snapshot or a rebuild hands out")
    (is (= seed (:message (first entries)))
        "the conversation still opens with the seed, in the place the prompt would have taken")))

;; ------------------------------------------------------------------ seam A

(deftest rebuilds-a-conversation-from-its-frames
  (let [messages (replay/lines->messages (one-run-lines))]
    (testing "the seed message opens the conversation"
      (is (= seed (first messages))))
    (testing "every subsequent message is a reconstruction of the recorded frames"
      (is (= ["user" "reasoning" "assistant" "tool" "assistant"]
             (mapv :role messages))))
    (testing "the reasoning message is rebuilt as its own message, content intact"
      (is (= reasoning-text (:content (second messages)))))
    (testing "the tool call is reattached to the assistant message that asked for it"
      (is (= [{:id "c1" :type "function"
               :function {:name "read" :arguments "{\"path\":\"deps.edn\"}"}}]
             (:toolCalls (nth messages 2)))))
    (testing "the tool result keeps its tool_call_id"
      (is (= {:id (:id (nth messages 3)) :role "tool" :toolCallId "c1" :content tool-text}
             (nth messages 3))))
    (testing "the final answer is intact"
      (is (= answer-text (:content (last messages)))))))

(deftest the-whole-log-round-trips-through-the-wire-contract
  (testing "rebuilding a log produces a message list a real client would accept"
    (let [frames (->> (one-run-lines)
                      (keep #(let [r (json/read-str % :key-fn keyword)]
                               (when (= "event" (replay/kind r)) (replay/payload r))))
                      vec)]
      (is (empty? (wire/violations frames))))))

(deftest a-later-action-adds-what-it-brought-and-nothing-twice
  ;; THIS TEST USED TO CLAIM THE OPPOSITE, and the reason it did is worth keeping: an
  ;; input line used to restate the whole conversation, so the fold seeded from the FIRST
  ;; line and ignored every later one, and a second run's QUESTION was therefore missing
  ;; from every rebuild -- the client's own message is in no frame. Ticket 03 of
  ;; `.scratch/sessions-live-on-the-server` made the line say what the action ADDED
  ;; (its own `message` row), which is exactly the thing the fold needed, and a repeat is
  ;; handled the way the live conversation handles it: BY ID
  ;; (`harness.edge.sessions/append!`, and this fold).
  (let [q2      {:id "u2" :role "user" :content "\u7b2c\u4e8c\u4e2a\u95ee\u9898"}
        run-two (fn [line entries]
                  (concat (one-run-lines)
                          (line "r2" entries)
                          (event-lines "r2" [(ev/run-start)
                                             (ev/text-delta "second turn")
                                             (ev/run-end)])))]
    (testing "the action's own entry lands in file order, behind the run it followed"
      (let [messages (replay/lines->messages (run-two client-lines [q2]))]
        (is (= ["user" "reasoning" "assistant" "tool" "assistant" "user" "assistant"]
               (mapv :role messages)))
        (is (= "\u7b2c\u4e8c\u4e2a\u95ee\u9898" (:content (nth messages 5)))
            "the question this action brought, which no frame could have carried")
        (is (= "second turn" (:content (last messages))) "and the run's output is appended")))

    (testing "an entry the conversation already holds does not enter a second time"
      ;; The retry: a page re-sending its question after a socket died sends the same id
      ;; again, and the fold -- which is what the live session does too
      ;; (`harness.edge.sessions/append!`) -- drops it by name. THE ID IS THE ONLY WAY TO
      ;; KNOW: the bytes could differ (a typo fixed and re-sent is a new question), so a
      ;; content comparison would answer a different question.
      (let [messages (replay/lines->messages (run-two client-lines [seed q2]))]
        (is (= 7 (count messages)) "seven entries, not eight")
        (is (= 1 (count (filter #(= "u2" (:id %)) messages)))
            "and the new entry entered once")))))

(deftest a-run-that-never-terminated-fails-loudly
  (testing "a log cut off mid-run must not silently yield half a conversation"
    (let [lines (concat (action-lines "r1" [seed])
                        (event-lines "r1" [(ev/run-start) (ev/text-delta "\u534a\u53e5\u8bdd")]))
          e     (try (replay/lines->messages lines) nil (catch Exception e e))]
      (is (some? e) "expected a failure, got a half-built conversation")
      (is (re-find #"(?i)terminat|incomplete|truncat" (str (ex-message e)))))))

(deftest a-log-that-ends-mid-run-says-what-would-close-it
  ;; The other half of the refusal above, and it asks the SAME walk: what the open
  ;; run left unsaid. This is what a continuation appends before handing the
  ;; conversation back, so the two must agree about which run is open.
  (testing "a call that never answered gets a result, then the terminal"
    (let [lines (concat (action-lines "r1" [seed])
                        (event-lines "r1" [(ev/run-start)
                                           (ev/tool-call "c1" "read" "{}")]))
          {:keys [run-id last-frame frames]}
          (first (replay/closing-frames (replay/lines->records lines)))]
      (is (= "r1" run-id))
      (is (= "TOOL_CALL_END" last-frame))
      (is (= ["TOOL_CALL_RESULT" "RUN_ERROR"] (mapv :type frames)))
      (testing "the result answers the call that hung, in the true words"
        (is (= "c1" (:toolCallId (first frames))))
        (is (re-find #"cut off" (:content (first frames)))))
      (testing "and the terminal is RUN_ERROR, saying why it exists"
        (is (re-find #"TOOL_CALL_END" (:message (second frames))))
        (is (re-find #"continued" (:message (second frames)))))))

  (testing "a run that died before its first frame is closed too"
    (let [{:keys [last-frame frames]}
          (first (replay/closing-frames (replay/lines->records (action-lines "r1" [seed]))))]
      (is (nil? last-frame) "there is no frame to name")
      (is (= ["RUN_ERROR"] (mapv :type frames)))))

  (testing "once those frames are appended the conversation READS -- the point of it"
    (let [base     (concat (action-lines "r1" [seed])
                           (event-lines "r1" [(ev/run-start)
                                              (ev/tool-call "c1" "read" "{}")
                                              (ev/text-delta "\u534a\u53e5\u8bdd")]))
          {:keys [run-id frames]} (first (replay/closing-frames (replay/lines->records base)))
          closed   (concat base (map #(log-line {:ts 3 :runId run-id :kind "event"
                                                 :payload %})
                                     frames))
          messages (replay/lines->messages closed)]
      (is (seq messages))
      (is (= "c1" (:toolCallId (last messages)))
          "the appended result is the tool message the provider requires")
      (testing "and the invariant the vendors enforce now holds end to end"
        (let [calls   (mapcat #(map :id (:toolCalls %)) messages)
              answers (set (keep :toolCallId messages))]
          (is (seq calls))
          (is (every? answers calls)
              "every tool call in the history has an answering tool message")))))

  (testing "a log that ends where it should has nothing missing"
    (is (nil? (replay/closing-frames (replay/lines->records (one-run-lines))))))

  (testing "and neither has a log that never ran"
    (is (nil? (replay/closing-frames
               (replay/lines->records
                [(log-line {:ts 1 :runId nil :kind "project/bound"
                            :payload {:before nil :after "/tmp/p" :via "http"}})]))))))

(deftest an-earlier-open-run-beside-a-later-finished-one-is-not-a-dead-end
  ;; ONE THREAD, TWO RUNS IN FLIGHT, and the process dies between them: an earlier run
  ;; that never ended, and a later one that did. The last frame in the file is the
  ;; finished run's terminal, so 'was the last frame terminal?' answers yes -- while
  ;; counting inputs against terminals answers no. This log used to be refused FOREVER:
  ;; the refusal counted, the repair asked about the last input and the last terminal,
  ;; found that run closed, and closed nothing at all.
  (let [lines (concat (action-lines "r1" [seed])
                      (action-lines "r2" [seed])
                      (event-lines "r1" [(ev/run-start)
                                         (ev/reasoning-delta reasoning-text)])
                      (event-lines "r2" [(ev/run-start)
                                         (ev/text-delta answer-text)
                                         (ev/run-end)]))]
    (testing "the refusal names the run nobody closed, not the file's last frame"
      (let [msg (str (ex-message (try (replay/lines->messages lines)
                                     nil
                                     (catch Exception e e))))]
        (is (str/includes? msg "r1"))
        (is (str/includes? msg "REASONING_MESSAGE_CONTENT")
            "the frame it names is the open run's own last frame")))

    (testing "and the repair closes that run, under its own id"
      (let [closures (replay/closing-frames (replay/lines->records lines))]
        (is (= ["r1"] (mapv :run-id closures)))
        (is (= ["REASONING_MESSAGE_CONTENT"] (mapv :last-frame closures)))
        (is (= [["RUN_ERROR"]] (mapv #(mapv :type (:frames %)) closures)))))

    (testing "once it is appended the log reads -- the whole conversation, both runs"
      (let [closures (replay/closing-frames (replay/lines->records lines))
            closed   (reduce (fn [ls {:keys [run-id frames]}]
                               (concat ls (map #(log-line {:ts 9 :runId run-id
                                                         :kind "event" :payload %})
                                             frames)))
                             lines closures)
            messages (replay/lines->messages closed)]
        (is (= ["user" "reasoning" "assistant"] (mapv :role messages)))
        (is (= answer-text (:content (last messages)))
            "the later run's own output survives the repair")))))

(deftest every-open-run-is-closed-not-just-the-one-that-ran-last
  ;; A process killed with TWO runs in flight leaves two open ones, and the log is only
  ;; readable once both have ended -- so the repair answers for all of them rather than
  ;; swapping one refusal for the next.
  (let [lines (concat (action-lines "r1" [seed])
                      (event-lines "r1" [(ev/run-start)
                                         (ev/tool-call "c1" "read" "{}")])
                      (action-lines "r2" [seed])
                      (event-lines "r2" [(ev/run-start)
                                         (ev/text-delta answer-text)]))
        records (replay/lines->records lines)]
    (testing "the refusal says how many runs are open"
      (let [msg (str (ex-message (try (replay/lines->messages lines)
                                     nil
                                     (catch Exception e e))))]
        (is (str/includes? msg "r1"))
        (is (str/includes? msg "2 runs are open"))))

    (testing "every open run gets its own closure, oldest first, with its own calls"
      (let [closures (replay/closing-frames records)]
        (is (= ["r1" "r2"] (mapv :run-id closures)))
        (is (= ["c1"] (:unanswered (replay/open-run records))))
        (is (= [["TOOL_CALL_RESULT" "RUN_ERROR"] ["RUN_ERROR"]]
               (mapv #(mapv :type (:frames %)) closures)))
        (is (= "c1" (:toolCallId (first (:frames (first closures))))))))

    (testing "and appending both is what makes the log whole"
      (let [closures (replay/closing-frames records)
            closed   (reduce (fn [ls {:keys [run-id frames]}]
                               (concat ls (map #(log-line {:ts 9 :runId run-id
                                                         :kind "event" :payload %})
                                             frames)))
                             lines closures)]
        (is (seq (replay/lines->messages closed)))
        (is (nil? (replay/closing-frames (replay/lines->records closed)))
            "nothing is left open")))))
(deftest a-park-is-not-a-run-that-never-terminated
  ;; A call awaiting a human sits INSIDE a run that closed properly: :run/interrupt
  ;; ends the run and the answer arrives on the resume run. Reading 'this call has
  ;; no result' as damage would append a result to a call nobody has answered yet --
  ;; and the approval would arrive to find its call already spoken for.
  (let [lines (concat (action-lines "r1" [seed])
                      (event-lines "r1" [(ev/run-start)
                                         (ev/tool-call "c1" "bash" "{}")
                                         (ev/run-interrupt [{:id "i1" :tool-call-id "c1"
                                                             :name "bash" :args "{}"}])]))
        records (replay/lines->records lines)]
    (is (nil? (replay/closing-frames records)) "a parked call is not a missing result")
    (is (seq (replay/lines->messages lines)) "and the log reads as it always did")))

(deftest a-rebuilt-park-carries-what-the-card-is-drawn-from
  ;; TICKET 06 of `.scratch/session-after-refresh`: the client's card is drawn from the
  ;; LAST assistant message's `metadata.custom.agui.interrupts` -- the live aggregator
  ;; writes it, and a rebuilt conversation has only this fold.
  (let [lines (concat (action-lines "r1" [seed])
                      (event-lines "r1" [(ev/run-start)
                                         (ev/tool-call "c1" "bash" "{}")
                                         (ev/run-interrupt [{:id "i1" :tool-call-id "c1"
                                                             :name "bash" :args "{}"}])]))
        msgs (replay/lines->messages lines)
        assistant (last (filter #(= "assistant" (:role %)) msgs))
        interrupts (get-in assistant [:metadata :custom "agui" :interrupts])]
    (is (= 1 (count interrupts)) "the parked run was rebuilt without its interrupt")
    (is (= "i1" (:id (first interrupts))))
    (is (= "tool-approval" (:reason (first interrupts))))
    (is (= "c1" (:toolCallId (first interrupts))))))

(deftest a-rebuilt-run-that-finished-carries-no-interrupt
  ;; The other half: 'the last assistant' must not be blanket-marked parked.
  (is (not-any? #(get-in % [:metadata :custom "agui" :interrupts])
                (replay/lines->messages (one-run-lines)))))

(deftest a-half-written-line-fails-loudly
  (testing "a line killed mid-write names the line it choked on"
    (let [lines (conj (vec (one-run-lines)) "{\"ts\":3,\"runId\":\"r1\",\"kin")
          e     (try (replay/lines->messages lines) nil (catch Exception e e))]
      (is (some? e) "expected a failure, got a silently shortened log")
      (is (re-find #"(?i)line" (str (ex-message e)))))))

(deftest the-record-has-two-kinds-of-row-and-anything-else-is-refused-by-name
  ;; 拍定 2026-09-21 (`.scratch/jsonl-two-kinds`): a row is `message` -- what a person said or
  ;; an LLM returned -- or `event`, every other fact. An old-contract row is refused BY NAME
  ;; rather than read leniently: a record this build cannot fold honestly is worse than a
  ;; refusal that says which line and why.
  (testing "a message row: the payload is the provider's own map, verbatim"
    (let [[r] (replay/lines->records
               [(json/write-str {:type "message" :ts 7 :runId "r1"
                                 :payload {:role "user" :content "hi"}})])]
      (is (= "message" (replay/kind r)))
      (is (= {:role "user" :content "hi"} (replay/payload r)) "no envelope key leaks into it")
      (is (= 7 (:ts r)) "and the row still says when")
      (is (= "r1" (:runId r)) "and for which run")))
  (testing "a frame row: the payload IS the frame"
    (let [frame {:type "RUN_STARTED" :threadId "t" :runId "r1"}
          [r]   (replay/lines->records
                 [(json/write-str {:type "event" :runId "r1" :payload frame})])]
      (is (= "event" (replay/kind r)))
      (is (= frame (replay/payload r)))))
  (testing "a harness fact: an event whose CUSTOM frame is named after the fact"
    (let [[r] (replay/lines->records
               [(json/write-str {:type "event" :runId nil
                                 :payload {:type "CUSTOM" :name "model/start"
                                           :value {:model "scripted"}}})])]
      (is (= "model/start" (replay/kind r)) "the fact's name comes back as the reader's :kind")
      (is (= {:model "scripted"} (replay/payload r)))))
  (testing "the WIRE's own CUSTOM name is a frame, not a fact"
    ;; `injected-context` is the one CUSTOM name the protocol uses; reading it as a fact would
    ;; hide a card from the conversation.
    (let [frame {:type "CUSTOM" :name "injected-context" :messageId "session-opening-0"
                 :value {:role "text"}}
          [r]   (replay/lines->records [(json/write-str {:type "event" :payload frame})])]
      (is (= "event" (replay/kind r)))
      (is (= frame (replay/payload r)))))
  (testing "and every other row is refused, naming the line and the reason"
    (doseq [[line reason] [["{\"ts\":1,\"runId\":\"r1\",\"kind\":\"input\",\"payload\":{}}"
                            :old-contract]
                           ["{\"type\":\"input\",\"payload\":{}}" :unknown-type]
                           ["{\"type\":\"event\"}" :missing-payload]
                           ["[1,2,3]" :not-an-object]
                           ["{\"type\":" :not-json]]]
      ;; `doall` FORCES THE LAZY PARSE: `lines->records` reads nothing until something is
      ;; asked for it (ticket 06), and the refusal happens DURING the parse.
      (let [e (try (doall (replay/lines->records ["{\"type\":\"message\",\"payload\":{}}" line]))
                   nil
                   (catch Exception e e))]
        (is (some? e) (str line " must be refused, not folded"))
        (is (= 2 (:line (ex-data e))) "the line that is wrong, not the first one")
        (is (= reason (:reason (ex-data e))))
        (is (re-find #"line 2" (ex-message e)) "and the sentence says which line"))))
  (testing "an old record says what to do about it, in one sentence"
    (let [e (try (doall (replay/lines->records
                  ["{\"ts\":1,\"runId\":\"r1\",\"kind\":\"input\",\"payload\":{}}"]))
                 nil
                 (catch Exception e e))]
      (is (re-find #"old contract" (ex-message e)))
      (is (re-find #"start a new conversation" (ex-message e))
          "a refusal a person can act on -- 决定 3 of the spec"))))

(deftest a-log-that-holds-no-run-is-an-empty-conversation
  ;; The state every session passes through: bound, or configured, or archived --
  ;; but never run. Its log holds audit lines and no input, and reading it must
  ;; give an empty conversation rather than a refusal. The refusal would name a
  ;; truncated run that does not exist, and the caller that hit it would be the
  ;; sidebar opening a session a person just created.
  (let [audit-lines [(log-line {:ts 1 :runId nil :kind "project/bound"
                                :payload {:before nil :after "/tmp/a" :via "http"}})
                     (log-line {:ts 2 :runId nil :kind "provider/changed"
                                :payload {:after {:provider :alpha}}})]]
    (testing "no input anywhere means there is nothing to be halfway through"
      (is (= [] (replay/lines->messages audit-lines))))
    (testing "but the SAME log with an input whose run never ended is still refused"
      ;; The line that separates the two cases is the input, and only the input:
      ;; it is the record that says a run began.
      (let [e (try (replay/lines->messages
                    (concat audit-lines
                            (action-lines "r1" [seed])
                            (event-lines "r1" [(ev/run-start) (ev/text-delta "半句话")])))
                   nil
                   (catch Exception e e))]
        (is (some? e))
        (is (re-find #"(?i)terminat|incomplete|truncat" (str (ex-message e))))))))

;; ------------------------------------------------------------------ seam B

(def ^:private dir (support/temp-dir "replay"))

(defn- write-log! [thread-id lines]
  (let [f (io/file dir (str thread-id ".jsonl"))]
    (.mkdirs (.getParentFile f))
    (spit f (str (str/join "\n" lines) "\n") :encoding "UTF-8")))

(defn- log-file
  "The file a thread's log lives in, built the way the writer builds it. The
  directory is the caller's now, so this test composes the two -- which is the
  same division of labour the routes use."
  [thread-id]
  (home/log-file dir thread-id))

(defn- assistant-with-calls [history]
  (first (filter #(and (= "assistant" (:role %)) (:tool_calls %)) history)))

(deftest history-is-shaped-for-the-provider
  (write-log! "t-shape" (one-run-lines))
  (let [history (replay/history (log-file "t-shape"))]
    (testing "the system message leads, ASSEMBLED rather than read out of the log"
      ;; The opening is derived from prompt.md and the appended text from the
      ;; hooks, per run -- a copy out of the log would be a stale sentence. Replay
      ;; has no hook sink, so nothing is appended here and the result is the frozen
      ;; opening byte for byte; the appended case is harness.cap.system-prompt-test's
      ;; and harness.edge.http-test's. What matters here is that the source is the
      ;; opening plus the derivations, not the recorded message.
      (is (= "system" (:role (first history))))
      (is (= (llm/prompt) (:content (first history)))))
    (testing "reasoning is folded back onto the assistant message -- the whole point"
      (is (= reasoning-text (:reasoning_content (assistant-with-calls history)))))
    (testing "calls are in the provider's casing, not AG-UI's"
      (is (= [{:id "c1" :type "function"
               :function {:name "read" :arguments "{\"path\":\"deps.edn\"}"}}]
             (:tool_calls (assistant-with-calls history)))))
    (testing "the result became a tool message keyed by tool_call_id"
      (is (= "c1" (:tool_call_id (first (filter #(= "tool" (:role %)) history))))))
    (testing "no AG-UI-only shape survives into what we send the model"
      (is (not-any? #(contains? % :toolCalls) history))
      (is (not-any? #(contains? % :toolCallId) history))
      (is (not-any? #(= "reasoning" (:role %)) history)))))

;;; ---------------------------------------------------------------------------
;;; THE TOOL CALL'S SHAPE SURVIVES THE RECORD

(def ^:private hostile-args
  "The vendor's `arguments` TEXT, chosen so that only a round trip which never PARSES it can
  hand it back: escaped quotes and a backslash, a `\\n` escape, a solidus a writer may or may
  not escape, non-ASCII, whitespace that matters, keys deliberately NOT in sorted order, and
  -- the reason this is about BYTES and not only about shape -- MORE THAN EIGHT KEYS inside
  one object, which is exactly where a Clojure map stops keeping the order it was written in
  (`PersistentArrayMap` becomes `PersistentHashMap`, and the 9th key lands wherever its hash
  says). No raw newline: these fixtures are line-delimited JSON."
  "{\"path\":\"/tmp/a b\",\"note\":\"line\\nbreak \\\"quoted\\\" back\\\\slash\",\"emoji\":\"\u5de5\u4f5c\",\"z\":1,\"a\":2,\"k9\":9,\"k8\":8,\"k7\":7,\"k6\":6,\"k5\":5,\"k4\":4,\"k3\":3}")

(def ^:private live-assistant
  "THE MESSAGE THE LIVE FOLD PRODUCES, key order and all: `harness.kernel.llm` builds
  `{:role .. :content ..}` and then assocs `:reasoning_content` BEFORE `:tool_calls`, and that
  order is part of the bytes a vendor's prefix cache keys on. Spelled out here rather than
  produced by calling the fold, so that a change to the live construction shows up as a failure
  in this file: the record has to hand back what the live run sent, not merely something a
  provider can read. An explicit `array-map` because insertion order is the whole point.

  THE OBSERVED SHAPE, from five real request bodies in `~/.clj-harness/logs/llm-debug.jsonl`:
  every assistant message with calls carried exactly these four keys in this order (10
  occurrences), every call `id, type, function` (14), every function `name, arguments` (14),
  and every `arguments` was a string (14) -- never an object."
  (array-map
   :role "assistant" :content answer-text :reasoning_content reasoning-text
   :tool_calls [{:id "c1" :type "function"
                 :function {:name "read" :arguments hostile-args}}]))

(def ^:private one-turn-frames
  "THE FRAMES OF ONE TOOL-CALLING TURN, from the REAL emitter -- the same events
  `harness.kernel.loop` hands the wire, so a fixture cannot encode a frame shape the server
  never writes.

  THIS IS THE PATH A TOOL CALL COMES BACK BY, and that is a fact about the record rather than
  a preference: `replay/entries`' `ours?` makes a `message` row a conversation entry only when
  its `:source` is one of `conversation-sources` (the client's own, and the birth's), because
  the model's return and a tool's answer are \"already drawn from the frames\". A model turn's
  `message` row is in the record and NOT in the conversation."
  [(ev/run-start)
   (ev/reasoning-delta reasoning-text)
   ;; THE TEXT COMES BEFORE THE CALL, which is the order a model speaks in -- and the order
   ;; that matters: a tool call is patched onto the assistant message by `:parentMessageId`
   ;; (`frames/apply-frames`), so the words and the call land on ONE message only when the
   ;; words were already open. This is the fixture's job, not a convenience: getting it
   ;; backwards models a turn the fold would split in two.
   (ev/text-delta answer-text)
   (ev/tool-call "c1" "read" hostile-args)
   (ev/tool-result "c1" "ok" false)
   (ev/run-end)])

(defn- log-one-turn [thread-id]
  (write-log! thread-id
              (concat [(prompt-line "r1" "You are a coding agent.")]
                      (event-lines "r1" one-turn-frames)))
  (log-file thread-id))

(deftest a-tool-call-comes-back-out-of-the-record-byte-for-byte
  ;; WHY THIS IS A TEST AND NOT A HOPE: the table of tools sits in the request's HEAD and the
  ;; messages behind it, so ONE byte that changes when a record is read back does not cost one
  ;; message -- it throws away the whole prefix, the conversation included. A rebuild happens
  ;; on every refresh and every continuation, so a drift here is not an edge case: it is the
  ;; difference between a cache that grows and one that is paid for again each time.
  (let [history (replay/history (log-one-turn "t-toolcall-bytes"))
        back    (assistant-with-calls history)]
    (testing "the message we would send is the one the live run sent, BYTE FOR BYTE"
      (is (= (json/write-str live-assistant) (json/write-str back))
          (str "the record round trip moved bytes a prefix cache keys on\n"
               "  live: " (json/write-str live-assistant) "\n"
               "  back: " (json/write-str back))))
    (testing "including the key order, because the vendor sees insertion order"
      (is (= [:role :content :reasoning_content :tool_calls] (vec (keys back))))
      (is (= [:id :type :function] (vec (keys (first (:tool_calls back))))))
      (is (= [:name :arguments] (vec (keys (get-in back [:tool_calls 0 :function]))))))
    (testing "and the arguments are still the vendor's TEXT -- a string, never an object"
      (let [args (get-in back [:tool_calls 0 :function :arguments])]
        (is (string? args))
        (is (= hostile-args args))
        ;; if anything had parsed and re-serialized them, this ordering is what would go first
        (is (str/includes? args "\"z\":1,\"a\":2,"))))
    (testing "a second round trip is the same fixed point, so restores do not compound"
      (is (= (json/write-str back)
             (json/write-str (json/read-str (json/write-str back))))))))

(deftest the-recorded-model-row-and-the-frames-describe-the-same-call
  ;; TWO RECORDINGS OF ONE FACT, AND ONLY ONE OF THEM IS THE CONVERSATION. The writer stores
  ;; the model's returned message as a `model` row -- for readers that want the message as the
  ;; PROVIDER read it -- and the frames the client was sent; a rebuild uses the FRAMES alone,
  ;; because `conversation-sources` says a `message` row is the conversation's own only when it
  ;; is the client's or the birth's. So this is not a second source of the bytes: it is a check
  ;; that the two recordings cannot drift, since a reader that trusted the other one would hand
  ;; a provider a different history than the run did.
  (let [;; THE ROW COMES AFTER THE FRAMES, which is the order the WRITER uses: the returned side is
        ;; written at `:run/done`, when the run's frames are already on disk. A record in the other
        ;; order has a row with no message to pair with, and the fold says so (`unpaired-model-row?`).
        lines   (concat [(prompt-line "r1" "You are a coding agent.")]
                        (event-lines "r1" one-turn-frames)
                        [(log-line {:ts 2 :runId "r1" :kind "message" :source "model" :id "e1"
                                    :payload live-assistant})])
        rows    (replay/lines->records lines)
        stored  (->> rows (filter #(= "model" (:source %))) first)
        rebuilt (assistant-with-calls (replay/history (log-one-turn "t-toolcall-pair")))]
    (is (some? stored) "the model's row is in the record")
    (is (not-any? #(contains? % :tool_calls) (map :message (replay/entries rows)))
        "and it is NOT a conversation entry -- a model turn comes back from the frames")
    (testing "the row holds the message the run sent, and the frames rebuild to those bytes"
      (is (= (json/write-str live-assistant) (json/write-str (replay/payload stored))))
      (is (= (json/write-str (replay/payload stored)) (json/write-str rebuilt))))))

(deftest history-carries-non-ascii-through-unchanged
  (write-log! "t-utf8" (one-run-lines))
  (let [history (replay/history (log-file "t-utf8"))]
    (testing "the reasoning text survives the log round trip byte for byte"
      (is (= reasoning-text (:reasoning_content (assistant-with-calls history)))))
    (testing "so does the tool output"
      (is (= tool-text (:content (first (filter #(= "tool" (:role %)) history))))))
    (testing "and the user's own message"
      (is (= "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee"
             (:content (first (filter #(= "user" (:role %)) history))))))))

(deftest a-missing-log-says-which-file
  ;; The refusal names the FILE, not the thread: since the logs became a tree, the
  ;; file is the whole identity of a conversation's record -- a stem can name two
  ;; files in two workspaces, and this reader has no way to pick between them. So
  ;; the assertion is the absolute path, which is what a reader can go look at.
  (let [f (log-file "no-such-thread")
        e (try (replay/history f) nil (catch Exception e e))]
    (is (some? e))
    (is (str/includes? (str (ex-message e)) (.getAbsolutePath f)))))

(deftest locate-finds-a-stem-anywhere-in-the-tree
  (let [tdir (support/temp-dir "replay-locate")
        ;; NOTHING TO CLEAR FIRST, and on this test that is not tidiness: a tree
        ;; this one did not make reads as a SECOND WORKSPACE holding the same stem,
        ;; which is precisely the case the last half below creates on purpose. The
        ;; deepest-first delete that used to stand here was guarding against a
        ;; previous run's tree; mkdtemp is what makes it unnecessary.
        nested (io/file tdir "_Users_me_proj")]
    (.mkdirs nested)
    (spit (io/file nested "t-deep.jsonl") "x" :encoding "UTF-8")
    (testing "a stem is found at depth, without the caller knowing the workspace name"
      (is (= (.getCanonicalPath (io/file nested "t-deep.jsonl"))
             (.getCanonicalPath ^java.io.File (replay/locate tdir "t-deep")))))
    (testing "a stem that is nowhere is refused BY NAME, naming where it looked"
      (let [e (try (replay/locate tdir "t-nowhere") nil (catch Exception e e))]
        (is (some? e))
        (is (str/includes? (str (ex-message e)) "t-nowhere"))))
    (testing "logs-for answers the same question without throwing -- none is an answer"
      (is (= [] (replay/logs-for tdir "t-nowhere")))
      (is (= 1 (count (replay/logs-for tdir "t-deep")))))
    (testing "a stem in TWO workspaces is refused rather than guessed at"
      ;; The split conversation. Neither half is the whole, and quietly choosing
      ;; the newest would hand back half a conversation looking like a clean
      ;; rebuild.
      (let [other (io/file tdir "_Users_me_other")]
        (.mkdirs other)
        (spit (io/file other "t-deep.jsonl") "y" :encoding "UTF-8"))
      (let [e (try (replay/locate tdir "t-deep") nil (catch Exception e e))]
        (is (some? e))
        (is (str/includes? (str (ex-message e)) "_Users_me_proj"))
        (is (str/includes? (str (ex-message e)) "_Users_me_other")))
      (testing "and logs-for hands BOTH back, so a mover can refuse for its own reason"
        (is (= 2 (count (replay/logs-for tdir "t-deep"))))))))

(deftest the-thread-listing-reads-the-tree
  ;; A tree of its OWN: the other tests in this namespace write logs into dir, and
  ;; a listing test that shared it would count their lines.
  (let [ldir (support/temp-dir "replay-listing")]
    ;; Empty by construction: the first assertion below is that an empty tree answers
    ;; an empty list, and one file left by a previous run would make it a test about
    ;; something else. That is what the deepest-first delete here used to be for --
    ;; see locate, above, for the same reasoning.
    (testing "an empty tree is an empty list, not an error"
      (is (= [] (replay/threads ldir))))
    (testing "a MISSING tree is also an empty list -- a fresh install is normal"
      (is (= [] (replay/threads (str ldir "/does-not-exist")))))
    ;; Two logs in two DIFFERENT workspaces, written in order: newest first, and
    ;; the walk covers the whole tree rather than its top level.
    (let [write (fn [workspace tid]
                  (let [f (io/file ldir workspace (str tid ".jsonl"))]
                    (.mkdirs (.getParentFile f))
                    (spit f (str (str/join "\n" (one-run-lines)) "\n") :encoding "UTF-8")))]
      (write "ws-a" "t-list-a")
      (Thread/sleep 20)
      (write "ws-b" "t-list-b")
      (let [rows (replay/threads ldir)]
        (testing "every .jsonl file at any depth is a row carrying its stem and its size"
          (is (= ["t-list-b" "t-list-a"] (mapv :thread-id rows)))
          (is (every? #(pos? (:bytes %)) rows))
          (is (every? #(pos? (:last-activity %)) rows))
          (is (apply > (mapv :last-activity rows)) "newest first"))))))

;; ------------------------------------------------------------------ seam C

;; A provider that records what it was asked. The scripted fake ignores its input, so it
;; cannot show that the rebuilt history actually reached the model -- which is the only
;; thing worth asserting here.
(defmethod llm/stream! :recording
  [{:keys [seen reply]} messages on-event _thread-id]
  (reset! seen messages)
  (on-event (ev/text-delta reply))
  {:message {:role "assistant" :content reply}
   ;; a recorder that reports nothing about the call, like a vendor that does not
   :telemetry {}})

(defn- recording-provider [reply]
  {:protocol :recording :reply reply :seen (atom nil)})

(deftest resume-continues-an-interrupted-conversation
  (write-log! "t-resume" (one-run-lines))
  (let [provider (recording-provider "\u7ee7\u7eed\u7684\u56de\u7b54")
        frames   (replay/resume! (log-file "t-resume") "t-resume" "\u518d\u89e3\u91ca\u4e00\u4e0b" provider)
        seen     @(:seen provider)]
    (testing "the model was handed the rebuilt history, not a blank slate"
      (is (some? seen))
      (is (some #(= "system" (:role %)) seen))
      (is (some #(= "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee" (:content %)) seen)))
    (testing "including the earlier reasoning, folded back onto its assistant message"
      (is (= reasoning-text (:reasoning_content (assistant-with-calls seen)))))
    (testing "and the earlier tool result"
      (is (some #(and (= "tool" (:role %)) (= tool-text (:content %))) seen)))
    (testing "with the new turn appended last"
      (is (= {:role "user" :content "\u518d\u89e3\u91ca\u4e00\u4e0b"} (last seen))))
    (testing "the continuation produced a complete, structurally valid run"
      (is (seq frames))
      (is (= "RUN_STARTED" (:type (first frames))))
      (is (frames/terminal? (peek frames)))
      (is (empty? (wire/violations frames))))
    (testing "and the answer is on the wire"
      (is (= "\u7ee7\u7eed\u7684\u56de\u7b54"
             (apply str (map :delta (filter #(= "TEXT_MESSAGE_CONTENT" (:type %)) frames))))))))

(deftest resume-refuses-a-truncated-log-rather-than-half-continuing
  (write-log! "t-cut" (concat (action-lines "r1" [seed])
                              (event-lines "r1" [(ev/run-start) (ev/text-delta "\u534a\u53e5")])))
  (let [e (try (replay/resume! (log-file "t-cut") "t-cut" "继续" (recording-provider "x"))
               nil
               (catch Exception e e))]
    (is (some? e) "a truncated log must not be silently resumed")
    (is (re-find #"(?i)terminat|incomplete|truncat" (str (ex-message e))))))



;; ------------------------------------------------- the numbers a window hands out

(deftest every-entry-wears-the-record-offset-of-the-line-it-arrived-in
  ;; THE NUMBER IS THE WINDOW'S WHOLE COORDINATE SYSTEM (ticket 05 of
  ;; `.scratch/sessions-live-on-the-server`, ADR 0003 decisions 1 and 9): a page cuts at
  ;; it, a delta is filtered by it, and a replica rebuilds its window from it. So it has
  ;; to be REPLAYABLE -- the same record has to give the same numbers twice -- and it has
  ;; to be the number the LIVE session minted, or a refresh would renumber the
  ;; conversation under a client that is holding the old numbers.
  (let [records (replay/lines->records (one-run-lines))
        run-end (dec (count records))
        entries (replay/entries records)]
    (testing "one entry per message, in order, and each carries the message itself"
      (is (= (mapv :role (replay/lines->messages (one-run-lines)))
             (mapv (comp :role :message) entries))))
    (testing "an action's own entry is numbered by ITS line -- the line it was written on"
      ;; THE PROMPT IS LINE 0 and is not an entry (it is the run's, not the
      ;; conversation's, and `conversation-sources` says so): the person's own message is
      ;; line 1 -- ITS OWN line, which is what `sessions/land-at!` gives the live session
      ;; for the same row.
      (is (= 1 (:seq (first entries))) "the second line of the thread, not the first")
      (is (= "u1" (:id (:message (first entries))))))
    (testing "a run's entries are numbered by the run's TERMINAL line, all of them"
      ;; One action, one run, ONE number: everything the run produced arrived in the
      ;; same batch, which is what lets a page cut between runs instead of through one.
      (is (= (repeat 4 run-end) (mapv :seq (rest entries)))
          (str "expected the run's four entries at line " run-end))
      (is (= 1 (count (distinct (map :seq (rest entries))))))
      (is (< (long (:seq (first entries))) (long (:seq (second entries))))
          "an earlier action's number is strictly smaller"))
    (testing "reading the same record again gives the same numbers"
      (is (= (mapv :seq entries)
             (mapv :seq (replay/entries (replay/lines->records
                                              (one-run-lines)))))))))

(deftest a-second-run-is-numbered-by-its-own-line-not-by-the-first-runs
  (let [q2  {:id "u2" :role "user" :content "second question"}
        raw (vec (concat (one-run-lines)
                         (action-lines "r2" [q2])
                         (event-lines "r2" [(ev/run-start)
                                            (ev/text-delta "second turn")
                                            (ev/run-end)])))
        records (replay/lines->records raw)
        entries (replay/entries records)
        ;; The second run's ATTACHED lines are its system row and then the entry the person
        ;; sent -- one line each (票 02) -- and the second run's terminal is the last line of
        ;; the file.
        action2 (inc (count (one-run-lines)))
        end2    (dec (count raw))
        by-seq  (group-by :seq entries)]
    (is (= ["second question"] (mapv (comp :content :message) (get by-seq action2)))
        "the second action's entry carries the second action's line")
    (is (= ["second turn"] (mapv (comp :content :message) (get by-seq end2)))
        "and the second run's answer carries its own terminal, not the first run's")
    (is (= (sort (map :seq entries)) (map :seq entries))
        "the numbers come out in file order, so a reader can walk them forward")))

(deftest a-log-that-stops-mid-run-numbers-its-partial-answer-last
  ;; THE LENIENT READING, and it is `entries` rather than `rebuild` on purpose: an
  ;; unfinished run has no terminal line to be numbered by, and the honest number for it
  ;; is the last line the record holds -- where the writing stopped. A window can then
  ;; still show a partial answer, and when the run is closed later its entries move to
  ;; the closing line exactly once.
  (let [raw     (vec (concat (action-lines "r1" [seed])
                             (event-lines "r1" [(ev/run-start)
                                                (ev/text-delta "half a thought")])))
        entries (replay/entries (replay/lines->records raw))]
    (is (= [1 (dec (count raw))] (mapv :seq entries)))
    (is (= "half a thought" (:content (:message (last entries)))))))

;; --------------------------------------------------- seam C: the streaming read (票 06)

(deftest read-records-drops-only-a-half-written-LAST-line
  ;; THE CONTRACT THE STREAMED READER MUST KEEP, spelled with a file on disk because that
  ;; is where the lag-one trick lives (`rows-tolerating-a-torn-last-line`).
  (testing "a torn last line is dropped, not refused"
    (write-log! "t-torn-last" (concat (one-run-lines) ["{\"ts\":9,\"runId\":\"r1\",\"ki"]))
    (let [rows (replay/read-records (log-file "t-torn-last"))]
      (is (vector? rows) "the vector reader still answers a vector")
      (is (= (count (one-run-lines)) (count rows)) "the half line is simply not there")))
  (testing "a torn line in the MIDDLE is corruption, refused by name"
    (let [lines (one-run-lines)
          broken (concat (take 1 lines) ["{\"ts\":9,\"ki"] (drop 1 lines))]
      (write-log! "t-torn-mid" broken)
      (let [e (try (replay/read-records (log-file "t-torn-mid")) nil (catch Exception e e))]
        (is (some? e) "a reader that swallowed it would hand back a shorter conversation")
        (is (= 2 (:line (ex-data e))) "and it names the line it choked on"))))
  (testing "a file that is not there is a named failure, not an empty conversation"
    (let [e (try (replay/read-records (log-file "t-absent")) nil (catch Exception e e))]
      (is (some? e))
      (is (some? (:path (ex-data e))) "the error carries the path nobody could read")))
  (testing "an empty file reads as no records at all"
    (write-log! "t-empty" [])
    (is (= [] (replay/read-records (log-file "t-empty"))))))

(deftest fold-records-streams-the-same-records-read-records-answers
  ;; TICKET 06: `fold-records` is the fold path -- the reader lives inside it and the rows
  ;; are never held together -- and it must answer exactly what the vector reader does.
  (write-log! "t-stream" (one-run-lines))
  (let [f     (log-file "t-stream")
        rows  (replay/read-records f)
        again (replay/fold-records f [] (fn [acc [_ row]] (conj acc row)))]
    (is (= rows again) "the same records, in the same order, line index ignored here")
    (is (= (map vector (range) rows)
           (replay/fold-records f [] (fn [acc [i row]] (conj acc [i row]))))
        "and the fold hands each record its OWN line index")))

(deftest fold-entries-is-entries-on-a-stream
  ;; The entries fold, driven from the file rather than from an array: the numbers and the
  ;; messages must come out identical (`entries` is the same `entries-step`).
  (write-log! "t-fold-entries" (one-run-lines))
  (let [f (log-file "t-fold-entries")]
    (is (= (replay/entries (replay/read-records f))
           (replay/fold-entries f)))))

(deftest sofar-is-one-walk-and-the-same-answer
  ;; TICKET 01 of `.scratch/session-as-kernel`: a session's build used to fold the record
  ;; five or six times and materialize it; `sofar` is one streaming walk now. THE WALK
  ;; CHANGED AND THE ANSWER DID NOT, so the answer is pinned here against the folds the one
  ;; walk replaced -- each public fold, one call, and every field of the born session.
  (write-log! "t-sofar-walk" (one-run-lines))
  (let [f       (log-file "t-sofar-walk")
        answer  (replay/sofar f)
        records (replay/read-records f)
        state   (replay/record-state records)]
    (is (= (replay/entries records) (:entries answer)))
    (is (= (mapv :message (replay/entries records)) (:messages answer)))
    (is (= (:state state) (:state answer)))
    (is (= (replay/compaction-facts records) (:compactions answer)))
    (is (= (replay/prune-facts records) (:prunes answer)))
    (is (= [] (:context answer)))))

(defn- reasoning-frames-of [run-id message-id text]
  "The per-token REASONING frames a run used to write (ticket 03 of `.scratch/event-persistence`),
  which are no longer recorded at all: `harness.edge.http/runner` drops the whole family."
  [{:ts 1 :runId run-id :type "event" :payload {:type "REASONING_START" :messageId message-id}}
   {:ts 1 :runId run-id :type "event" :payload {:type "REASONING_MESSAGE_START"
                                                    :messageId message-id :role "reasoning"}}
   {:ts 1 :runId run-id :type "event" :payload {:type "REASONING_MESSAGE_CONTENT"
                                                    :messageId message-id :delta text}}
   {:ts 1 :runId run-id :type "event" :payload {:type "REASONING_MESSAGE_END"
                                                    :messageId message-id}}
   {:ts 1 :runId run-id :type "event" :payload {:type "REASONING_END" :messageId message-id}}])

(deftest a-run-without-its-reasoning-frames-rebuilds-the-same-conversation
  ;; THE JUDGE FOR TICKET 03 OF `.scratch/event-persistence`. The per-token REASONING frames are
  ;; 82% of a log's bytes and are no longer written; the same text is on the run's OWN `message`
  ;; row, and the fold reads it back (`reasoning-row?` / `attach-reasoning`, paired by order
  ;; within the run). So THE SAME RUN, WRITTEN BOTH WAYS, HAS TO REBUILD THE SAME CONVERSATION --
  ;; entries and provider-shaped messages -- byte for byte. That is what this pins.
  (let [run-id "r1"
        user   {:ts 1 :runId run-id :type "message" :source "client" :id "u1"
                :payload {:role "user" :content "hi"}}
        head   [{:ts 1 :runId run-id :type "event"
                 :payload {:type "RUN_STARTED" :threadId "t" :runId run-id}}
                {:ts 1 :runId run-id :type "event"
                 :payload {:type "TEXT_MESSAGE_START" :messageId "r1-m0" :role "assistant"}}
                {:ts 1 :runId run-id :type "event"
                 :payload {:type "TEXT_MESSAGE_CONTENT" :messageId "r1-m0" :delta "the answer"}}
                {:ts 1 :runId run-id :type "event"
                 :payload {:type "TOOL_CALL_START" :messageId "r1-m0" :toolCallId "c1"
                           :toolCallName "read" :parentMessageId "r1-m0"}}
                {:ts 1 :runId run-id :type "event"
                 :payload {:type "TOOL_CALL_END" :messageId "r1-m0" :toolCallId "c1"}}]
        tail   [{:ts 1 :runId run-id :type "event" :payload {:type "RUN_FINISHED"
                                                             :threadId "t" :runId run-id}}]
        ;; THE MODEL'S OWN ROW, which is where the reasoning lives now. Its `:reasoning_content`
        ;; is the same text the frames used to carry, delta by delta.
        model  {:ts 1 :runId run-id :type "message" :source "model"
                :payload {:role "assistant" :content "the answer"
                          :reasoning_content "THINKING"
                          :tool_calls [{:id "c1" :type "function"
                                        :function {:name "read" :arguments "{}"}}]}}
        tool   {:ts 1 :runId run-id :type "message" :source "tool"
                :payload {:role "tool" :tool_call_id "c1" :content "result"}}
        ;; the OLD way: the frames carry the thinking, the row does not have to
        old-way (into (vec (concat [user] (reasoning-frames-of run-id "r1-r0" "THINKING")
                                   head tail))
                      [model tool])
        ;; the NEW way: no reasoning frames at all
        new-way (into (vec (concat [user] head tail)) [model tool])]
    (testing "the entries are the same conversation"
      ;; THE MESSAGES, NOT THEIR NUMBERS: an entry's `:seq` is the RECORD LINE it arrived in, and the
      ;; two records are written differently -- the old one spends five lines on the reasoning frames
      ;; and the new one spends none. The numbering is a fact about each file (ADR 0003 decision 9),
      ;; so comparing it across two files would be comparing the files, not the conversation.
      (is (= (mapv :message (replay/entries old-way))
             (mapv :message (replay/entries new-way)))))
    (testing "and so is what a provider is handed -- the reasoning reaches the assistant"
      (is (= (replay/records->messages old-way) (replay/records->messages new-way)))
      ;; AND IT IS WHERE A PROVIDER READS IT: `records->messages` is AG-UI's spelling, and the
      ;; reasoning reaches `:reasoning_content` one step later (`ag/provider-messages`, which is what
      ;; `replay/history` applies).
      (is (= "THINKING"
             (:reasoning_content
              (first (filter #(and (= "assistant" (:role %)) (:tool_calls %))
                              (ag/provider-messages (replay/records->messages new-way))))))))))

;;; ---------------------------------------------------------------------------
;;; TICKET 02 OF `.scratch/reasoning-out-of-the-record`: the match, made real

(defn- reasoning-frame-line?
  "Is LINE one of the per-token REASONING frames? PARSED, and spelled here rather than asked of the
  writer: a case that asked would agree with a writer that dropped the wrong ones."
  [line]
  (let [row (json/read-str line :key-fn keyword)]
    (and (= "event" (:type row))
         (str/starts-with? (str (get-in row [:payload :type])) "REASONING"))))

(defn- reasoning-ids-of [records]
  "The ids the fold gave the thinking messages of RECORDS, in order."
  (mapv (comp :id :message)
        (filter #(= "reasoning" (:role (:message %))) (replay/entries records))))

(deftest a-two-call-runs-second-thought-lands-on-the-second-message
  ;; TICKET 02, WHERE THE MATCHING RULE HAS TO BE REAL: TWO calls in one run. There is no call id in
  ;; the record and there must not be one (`model/start` refused the same field for the same reason:
  ;; 'a counter in the record would be the same fact written a second time, and two copies drift'), so
  ;; the pairing is BY ORDER within the run and IN THE FOLD: the k-th assistant message the run's
  ;; frames built is the k-th assistant row the run wrote.
  ;;
  ;; AND THE ID THE REBUILT THOUGHT WEARS IS THE WIRE'S OWN. `<run>-r<n>` is not the thought's ordinal:
  ;; `<n>` comes off the run's ONE counter, which every group takes from -- text, reasoning, a TOOL
  ;; RESULT and AN INJECTED CARD alike. So a run that had a card spliced in before its first call
  ;; spells its ids `ctx1, r1, m2, t3, r4`, and its SECOND thought of two is `r4`, not `r1`. An id is
  ;; what a client keys a message by, and a rebuilt conversation that named that thought something else
  ;; would draw one thought twice. (This exact case -- a run with a card in it -- is what the real-log
  ;; walkthrough in `evidence/read_routes.txt` caught after the fixtures had passed: 164 rebuilt
  ;; thoughts wore an id one off the wire's.)
  (let [run-id "r-two"
        seed   {:id "u1" :role "user" :content "hi"}
        frames (event-lines run-id [(ev/run-start)
                                   ;; A CARD IS SPLICED IN BEFORE THE FIRST CALL, which is what the
                                   ;; pre-LLM step does (`harness.cap.project/before-llm`) and what
                                   ;; spends one of the run's numbers.
                                   (ev/context-injected {:role "user"
                                                         :content "a skill body nobody asked for"})
                                   (ev/model-start {:model "m"} nil)
                                   (ev/reasoning-delta "first thought")
                                   (ev/model-end nil)
                                   (ev/text-delta "one")
                                   (ev/tool-call "c1" "read" "{}")
                                   (ev/tool-result "c1" "ok" false)
                                   (ev/model-start {:model "m"} nil)
                                   (ev/reasoning-delta "second thought")
                                   (ev/model-end nil)
                                   (ev/text-delta "two")
                                   (ev/run-end)])
        ;; THE RUN'S OWN ROWS, written at `:run/done` -- AFTER its terminal frame, which is where the
        ;; record puts them (`http_test` has the measurement: 'it lands one beat AFTER the terminal').
        rows   [(log-line {:ts 3 :runId run-id :kind "message" :source "model"
                           :payload {:role "assistant" :content "one"
                                     :reasoning_content "first thought"
                                     :tool_calls [{:id "c1" :type "function"
                                                   :function {:name "read" :arguments "{}"}}]}})
                (log-line {:ts 3 :runId run-id :kind "message" :source "model"
                           :payload {:role "assistant" :content "two"
                                     :reasoning_content "second thought"}})]
        old-way (replay/lines->records (concat (action-lines run-id [seed]) frames rows))
        new-way (replay/lines->records (concat (action-lines run-id [seed])
                                               (remove reasoning-frame-line? frames)
                                               rows))]
    (testing "the frames of this very run name its two thoughts r1 and r4"
      ;; THE GROUND THE CASE BELOW STANDS ON, read off the run's frames rather than asserted from a
      ;; fixture somebody typed: 'the same id' means the id THIS run's wire carried.
      (is (= ["r-two-r1" "r-two-r4"] (reasoning-ids-of old-way))))
    (testing "and a record without those frames rebuilds the same two thoughts, ids and all"
      (is (= ["r-two-r1" "r-two-r4"] (reasoning-ids-of new-way))))
    (testing "the conversation is the same one, message for message"
      (is (= (mapv :message (replay/entries old-way))
             (mapv :message (replay/entries new-way)))))
    (testing "and so is what a provider is handed"
      (is (= (replay/records->messages old-way) (replay/records->messages new-way))))))

(deftest a-run-whose-rows-outrun-its-messages-is-not-paired-and-not-silent
  ;; TICKET 02'S OTHER HALF: WHEN THE TWO LISTS DISAGREE, THE READER MUST NOT GUESS -- AND MUST NOT
  ;; PASS OVER IT IN SILENCE. A call that returned nothing builds no message of its own, so a run's own
  ;; rows can outnumber what its frames built; the extra row has nothing to attach to. The answer is
  ;; 'attach nothing' (never the wrong message) PLUS a line somebody reading the process log can find.
  (let [run-id  "r-loud"
        seed    {:id "u1" :role "user" :content "hi"}
        frames  (event-lines run-id [(ev/run-start)
                                    (ev/model-start {:model "m"} nil)
                                    (ev/reasoning-delta "the only thought")
                                    (ev/model-end nil)
                                    (ev/text-delta "the only answer")
                                    (ev/run-end)])
        ;; TWO ROWS, ONE MESSAGE: the second call returned nothing the frames could build.
        rows    [(log-line {:ts 3 :runId run-id :kind "message" :source "model"
                            :payload {:role "assistant" :content "the only answer"
                                      :reasoning_content "the only thought"}})
                 (log-line {:ts 3 :runId run-id :kind "message" :source "model"
                            :payload {:role "assistant" :content ""
                                      :reasoning_content "a thought with nowhere to go"}})]
        ;; A NEW RECORD: the reasoning family is NOT in it, so the run's own rows are the only place its
        ;; thinking could come from -- which is what makes the second row's lack of a message matter.
        records (replay/lines->records (concat (action-lines run-id [seed])
                                               (remove reasoning-frame-line? frames)
                                               rows))
        ;; THE LINE IS ASSERTED WHERE IT LANDS. `harness.infra.log` writes to the console and to a
        ;; file under the root, and this is the repo's own way of standing where it claims to stand
        ;; (`harness.infra.log-test`'s `with-capture`): move the root, point the console at a
        ;; StringWriter, and read what the fold said.
        out     (java.io.StringWriter.)
        root    (support/temp-dir "replay-unpaired")]
    (binding [home/*root-override* root]
      (logging/configure! {:root root :console out})
      (let [entries (replay/entries records)]
        (testing "nothing was attached for the row that has no message"
          (is (= ["r-loud-r0"] (mapv (comp :id :message)
                                      (filter #(= "reasoning" (:role (:message %))) entries))))
          (is (not-any? #(str/includes? (str (:content (:message %))) "nowhere to go") entries)
              "the unpaired thought was attached to something after all"))
        (testing "and the fold said so out loud rather than passing over it"
          (is (str/includes? (str out) "unpaired-model-row")
              (str "the fold kept a mispaired record to itself. it said: " (pr-str (str out))))
          (is (str/includes? (str out) run-id)))))))

(deftest a-thought-of-nothing-is-not-drawn-as-an-empty-message
  ;; ADR 0009'S ONE DELIBERATE ASYMMETRY, pinned because a REAL LOG found it (four times in one
  ;; conversation). A reasoning group whose whole text is a space gets a MESSAGE from the frames --
  ;; an empty one -- and NOTHING from the rows: `attach-reasoning` reads a blank row as 'this call
  ;; reported no reasoning', which is the honest answer, and a message saying nothing is not one.
  ;; So the two spellings are NOT byte-identical here, on purpose, and the rebuilt conversation is the
  ;; better of the two. The frames are the old spelling; nothing on the wire changes.
  (let [run-id "r-blank"
        seed   {:id "u1" :role "user" :content "hi"}
        frames (event-lines run-id [(ev/run-start)
                                    (ev/model-start {:model "m"} nil)
                                    (ev/reasoning-delta " ")
                                    (ev/model-end nil)
                                    (ev/text-delta "the answer")
                                    (ev/run-end)])
        rows   [(log-line {:ts 3 :runId run-id :kind "message" :source "model"
                           :payload {:role "assistant" :content "the answer"
                                     :reasoning_content " "}})]
        roles  (fn [records] (mapv (comp :role :message)
                                   (filter #(some? (:role (:message %)))
                                           (replay/entries records))))
        old-way (replay/lines->records (concat (action-lines run-id [seed]) frames rows))
        new-way (replay/lines->records (concat (action-lines run-id [seed])
                                               (remove reasoning-frame-line? frames)
                                               rows))]
    (testing "the frames' way draws it -- an empty message, which is what the old record held"
      (is (= ["user" "reasoning" "assistant"] (roles old-way)))
      (is (= " " (:content (:message (second (replay/entries old-way)))))))
    (testing "the rows' way draws nothing, which is the honest answer"
      (is (= ["user" "assistant"] (roles new-way))))
    (testing "and the answer itself is untouched either way"
      (is (= (last (roles old-way)) (last (roles new-way)))))))
