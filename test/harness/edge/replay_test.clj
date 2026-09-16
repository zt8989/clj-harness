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
            [harness.wire :as wire]))

;; The log lines below are produced by the REAL emitter, not hand-written frames. A
;; log the test invents could encode a frame shape the server never writes, and then
;; the test would pass while replay failed on every real log.

(defn- log-line [m] (json/write-str m))

(defn- input-line [run-id messages]
  (log-line {:ts 1 :runId run-id :kind "input"
             :payload {:threadId "t1" :runId run-id :messages messages
                       :tools [] :context []}}))

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

(deftest seeds-from-the-first-input-and-ignores-later-ones
  ;; A client's second input already contains the first run's output, so folding it in
  ;; would duplicate the conversation. The frames are the source; the inputs are only
  ;; the starting point.
  (let [stale {:id "u2" :role "user" :content "STALE-CLIENT-VIEW"}
        lines (concat (one-run-lines)
                      [(input-line "r2" [seed stale])]
                      (event-lines "r2" [(ev/run-start)
                                         (ev/text-delta "second turn")
                                         (ev/run-end)]))]
    (let [messages (replay/lines->messages lines)]
      (testing "the stale client view never appears"
        (is (not-any? #(= "STALE-CLIENT-VIEW" (:content %)) messages)))
      (testing "the second run's output is appended"
        (is (= "second turn" (:content (last messages)))))
      (testing "no message was duplicated"
        (is (= 6 (count messages)))))))

(deftest a-run-that-never-terminated-fails-loudly
  (testing "a log cut off mid-run must not silently yield half a conversation"
    (let [lines (concat [(input-line "r1" [seed])]
                        (event-lines "r1" [(ev/run-start) (ev/text-delta "\u534a\u53e5\u8bdd")]))
          e     (try (replay/lines->messages lines) nil (catch Exception e e))]
      (is (some? e) "expected a failure, got a half-built conversation")
      (is (re-find #"(?i)terminat|incomplete|truncat" (str (ex-message e)))))))

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

(def ^:private dir (str (System/getProperty "java.io.tmpdir") "/harness-replay-test"))
(io/delete-file dir true)

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
  (let [tdir (str (System/getProperty "java.io.tmpdir") "/harness-replay-locate")
        ;; java.io.tmpdir outlives this JVM, so a previous run's tree would still
        ;; be here and this test would count another run's files -- which is
        ;; exactly the two-workspaces case it is about to create on purpose.
        ;; Deepest-first, then fresh, the same discipline the listing test uses.
        _    (run! #(.delete ^java.io.File %)
                   (sort-by (fn [^java.io.File f] (count (.getPath f))) >
                            (file-seq (io/file tdir))))
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
  (let [ldir (str (System/getProperty "java.io.tmpdir") "/harness-replay-listing")]
    ;; io/delete-file cannot remove a NON-EMPTY directory, and java.io.tmpdir
    ;; outlives this JVM -- the files this test writes on one run would sit
    ;; there on the next and break the empty-directory assertion. Remove the
    ;; tree deepest-first, then start fresh.
    (run! #(.delete ^java.io.File %)
          (sort-by (fn [^java.io.File f] (count (.getPath f))) >
                   (file-seq (io/file ldir))))
    (.mkdirs (io/file ldir))
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
  {:role "assistant" :content reply})

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


