(ns harness.edge.replay-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.edge.ag-ui :as ag]
            [harness.kernel.event :as ev]
            [harness.kernel.frames :as frames]
            [harness.infra.home :as home]
            [harness.kernel.llm :as llm]
            [harness.edge.replay :as replay]
            [harness.test-support :as support]
            [harness.wire :as wire]))

;; The log lines below are produced by the REAL emitter, not hand-written frames. A
;; log the test invents could encode a frame shape the server never writes, and then
;; the test would pass while replay failed on every real log.

(defn- log-line [m] (json/write-str m))

(defn- input-line
  "An input line as a client wrote it BEFORE ticket 03 of
  `.scratch/sessions-live-on-the-server`: the whole conversation it held, restated on
  every run. Kept because a log written then is a log somebody has, and reading one still
  has to work -- see `action-line` for the shape the edge writes now."
  [run-id messages]
  (log-line {:ts 1 :runId run-id :kind "input"
             :payload {:threadId "t1" :runId run-id :messages messages
                       :tools [] :context []}}))

(defn- action-line
  "An input line as the edge has written it since ticket 03: what the ACTION added
  (`:added`), not the conversation it added it to -- the server holds that."
  [run-id added]
  (log-line {:ts 1 :runId run-id :kind "input"
             :payload {:threadId "t1" :runId run-id :added added :tools []}}))

(defn- event-lines [run-id events]
  (let [emit (ag/outbound "t1" run-id)]
    (mapv (fn [frame] (log-line {:ts 2 :runId run-id :kind "event" :payload frame}))
          (vec (mapcat emit events)))))

(def ^:private reasoning-text "\u7528\u6237\u60f3\u770b\u8fd9\u4e2a\u9879\u76ee\u3002")
(def ^:private tool-text ":paths [\"src\"]")
(def ^:private answer-text "\u8fd9\u662f\u4e00\u4e2a Clojure \u9879\u76ee\u3002")

(def ^:private seed {:id "u1" :role "user" :content "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee"})

(defn- one-run-lines []
  (concat [(input-line "r1" [seed])]
          (event-lines "r1" [(ev/run-start)
                             (ev/reasoning-delta reasoning-text)
                             (ev/tool-call "c1" "read" "{\"path\":\"deps.edn\"}")
                             (ev/tool-result "c1" tool-text false)
                             (ev/text-delta answer-text)
                             (ev/run-end)])))

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
                               (when (= "event" (:kind r)) (:payload r))))
                      vec)]
      (is (empty? (wire/violations frames))))))

(deftest a-later-action-adds-what-it-brought-and-nothing-twice
  ;; THIS TEST USED TO CLAIM THE OPPOSITE, and the reason it did is worth keeping: an
  ;; input line used to restate the whole conversation, so the fold seeded from the FIRST
  ;; line and ignored every later one, and a second run's QUESTION was therefore missing
  ;; from every rebuild -- the client's own message is in no frame. Ticket 03 of
  ;; `.scratch/sessions-live-on-the-server` made the line say what the action ADDED
  ;; (`:added`, `action-line`), which is exactly the thing the fold needed, and the
  ;; restatement is now handled the way the live conversation handles it: BY ID
  ;; (`harness.edge.sessions/append!`, and `append-new` here).
  (let [q2      {:id "u2" :role "user" :content "\u7b2c\u4e8c\u4e2a\u95ee\u9898"}
        run-two (fn [line entries]
                  (concat (one-run-lines)
                          [(line "r2" entries)]
                          (event-lines "r2" [(ev/run-start)
                                             (ev/text-delta "second turn")
                                             (ev/run-end)])))]
    (testing "the action's own entry lands in file order, behind the run it followed"
      (let [messages (replay/lines->messages (run-two action-line [q2]))]
        (is (= ["user" "reasoning" "assistant" "tool" "assistant" "user" "assistant"]
               (mapv :role messages)))
        (is (= "\u7b2c\u4e8c\u4e2a\u95ee\u9898" (:content (nth messages 5)))
            "the question this action brought, which no frame could have carried")
        (is (= "second turn" (:content (last messages))) "and the run's output is appended")))

    (testing "an entry the conversation already holds does not enter a second time"
      ;; The retry (a page re-sending its question after a socket died), and also every
      ;; line of a log written under the OLD contract, where the whole conversation was
      ;; restated on every run.
      (doseq [[shape line] {"the action's own entries" action-line
                            "the old restatement"       input-line}]
        (let [messages (replay/lines->messages (run-two line [seed q2]))]
          (is (= 7 (count messages)) (str shape ": seven entries, not eight"))
          (is (= 1 (count (filter #(= "u2" (:id %)) messages)))
              (str shape ": and the new entry entered once")))))))

(deftest a-run-that-never-terminated-fails-loudly
  (testing "a log cut off mid-run must not silently yield half a conversation"
    (let [lines (concat [(input-line "r1" [seed])]
                        (event-lines "r1" [(ev/run-start) (ev/text-delta "\u534a\u53e5\u8bdd")]))
          e     (try (replay/lines->messages lines) nil (catch Exception e e))]
      (is (some? e) "expected a failure, got a half-built conversation")
      (is (re-find #"(?i)terminat|incomplete|truncat" (str (ex-message e)))))))

(deftest a-log-that-ends-mid-run-says-what-would-close-it
  ;; The other half of the refusal above, and it asks the SAME walk: what the open
  ;; run left unsaid. This is what a continuation appends before handing the
  ;; conversation back, so the two must agree about which run is open.
  (testing "a call that never answered gets a result, then the terminal"
    (let [lines (concat [(input-line "r1" [seed])]
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
          (first (replay/closing-frames (replay/lines->records [(input-line "r1" [seed])])))]
      (is (nil? last-frame) "there is no frame to name")
      (is (= ["RUN_ERROR"] (mapv :type frames)))))

  (testing "once those frames are appended the conversation READS -- the point of it"
    (let [base     (concat [(input-line "r1" [seed])]
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
  (let [lines (concat [(input-line "r1" [seed])
                       (input-line "r2" [seed])]
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
  (let [lines (concat [(input-line "r1" [seed])]
                      (event-lines "r1" [(ev/run-start)
                                         (ev/tool-call "c1" "read" "{}")])
                      [(input-line "r2" [seed])]
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
  (let [lines (concat [(input-line "r1" [seed])]
                      (event-lines "r1" [(ev/run-start)
                                         (ev/tool-call "c1" "bash" "{}")
                                         (ev/run-interrupt [{:id "i1" :tool-call-id "c1"
                                                             :name "bash" :args "{}"}])]))
        records (replay/lines->records lines)]
    (is (nil? (replay/closing-frames records)) "a parked call is not a missing result")
    (is (seq (replay/lines->messages lines)) "and the log reads as it always did")))

(deftest a-half-written-line-fails-loudly
  (testing "a line killed mid-write names the line it choked on"
    (let [lines (conj (vec (one-run-lines)) "{\"ts\":3,\"runId\":\"r1\",\"kin")
          e     (try (replay/lines->messages lines) nil (catch Exception e e))]
      (is (some? e) "expected a failure, got a silently shortened log")
      (is (re-find #"(?i)line" (str (ex-message e)))))))

(deftest a-log-that-holds-no-run-is-an-empty-conversation
  ;; The state every session passes through: bound, or configured, or archived --
  ;; but never run. Its log holds audit lines and no input, and reading it must
  ;; give an empty conversation rather than a refusal. The refusal would name a
  ;; truncated run that does not exist, and the caller that hit it would be the
  ;; sidebar opening a session a person just created.
  (let [audit-lines [(json/write-str {:ts 1 :runId nil :kind "project/bound"
                                      :payload {:before nil :after "/tmp/a" :via "http"}})
                     (json/write-str {:ts 2 :runId nil :kind "provider/changed"
                                      :payload {:after {:provider :alpha}}})]]
    (testing "no input anywhere means there is nothing to be halfway through"
      (is (= [] (replay/lines->messages audit-lines))))
    (testing "but the SAME log with an input whose run never ended is still refused"
      ;; The line that separates the two cases is the input, and only the input:
      ;; it is the record that says a run began.
      (let [e (try (replay/lines->messages
                    (concat audit-lines
                            [(input-line "r1" [seed])]
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
  (write-log! "t-cut" (concat [(input-line "r1" [seed])]
                              (event-lines "r1" [(ev/run-start) (ev/text-delta "\u534a\u53e5")])))
  (let [e (try (replay/resume! (log-file "t-cut") "t-cut" "继续" (recording-provider "x"))
               nil
               (catch Exception e e))]
    (is (some? e) "a truncated log must not be silently resumed")
    (is (re-find #"(?i)terminat|incomplete|truncat" (str (ex-message e))))))


