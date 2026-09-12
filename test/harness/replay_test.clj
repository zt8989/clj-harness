(ns harness.replay-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.ag-ui :as ag]
            [harness.event :as ev]
            [harness.llm :as llm]
            [harness.memory :as mem]
            [harness.replay :as replay]
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

;; ------------------------------------------------------------------ seam B

(def ^:private dir (str (System/getProperty "java.io.tmpdir") "/harness-replay-test"))
(io/delete-file dir true)

(defn- write-log! [thread-id lines]
  (let [f (io/file dir (str thread-id ".jsonl"))]
    (.mkdirs (.getParentFile f))
    (spit f (str (str/join "\n" lines) "\n") :encoding "UTF-8")))

(defn- assistant-with-calls [history]
  (first (filter #(and (= "assistant" (:role %)) (:tool_calls %)) history)))

(deftest history-is-shaped-for-the-provider
  (write-log! "t-shape" (one-run-lines))
  (let [history (replay/history dir "t-shape")]
    (testing "the system prompt leads, freshly read rather than stored in the log"
      (is (= "system" (:role (first history))))
      (is (str/starts-with? (:content (first history)) (mem/prompt))))
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
  (let [history (replay/history dir "t-utf8")]
    (testing "the reasoning text survives the log round trip byte for byte"
      (is (= reasoning-text (:reasoning_content (assistant-with-calls history)))))
    (testing "so does the tool output"
      (is (= tool-text (:content (first (filter #(= "tool" (:role %)) history))))))
    (testing "and the user's own message"
      (is (= "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee"
             (:content (first (filter #(= "user" (:role %)) history))))))))

(deftest a-missing-log-says-which-file
  (let [e (try (replay/history dir "no-such-thread") nil (catch Exception e e))]
    (is (some? e))
    (is (str/includes? (str (ex-message e)) "no-such-thread"))))

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
        frames   (replay/resume! dir "t-resume" "\u518d\u89e3\u91ca\u4e00\u4e0b" provider)
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
      (is (wire/terminal? (peek frames)))
      (is (empty? (wire/violations frames))))
    (testing "and the answer is on the wire"
      (is (= "\u7ee7\u7eed\u7684\u56de\u7b54"
             (apply str (map :delta (filter #(= "TEXT_MESSAGE_CONTENT" (:type %)) frames))))))))

(deftest resume-refuses-a-truncated-log-rather-than-half-continuing
  (write-log! "t-cut" (concat [(input-line "r1" [seed])]
                              (event-lines "r1" [(ev/run-start) (ev/text-delta "\u534a\u53e5")])))
  (let [e (try (replay/resume! dir "t-cut" "继续" (recording-provider "x"))
               nil
               (catch Exception e e))]
    (is (some? e) "a truncated log must not be silently resumed")
    (is (re-find #"(?i)terminat|incomplete|truncat" (str (ex-message e))))))


