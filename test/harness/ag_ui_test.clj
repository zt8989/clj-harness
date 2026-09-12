(ns harness.ag-ui-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.ag-ui :as ag]
            [harness.event :as ev]
            [harness.fake :as fake]
            [harness.loop :as loop]
            [harness.wire :as wire]))

(defn- wire [events]
  (let [emit (ag/outbound "thr-1" "run-1")]
    (vec (mapcat emit events))))

(defn- types [frames] (mapv :type frames))

(defn- run-events
  "Drive one scripted run through loop/run-chan and return the kernel events,
  the :run/done terminal dropped."
  [turns]
  (let [ch  (loop/run-chan (fake/scripted turns) [])
        out (atom [])]
    (loop []
      (when-let [ev (async/<!! ch)]
        (when-not (= :run/done (:type ev))
          (swap! out conj ev)
          (recur))))
    @out))

(deftest text-only-turn
  (let [frames (wire [(ev/run-start)
                      (ev/text-delta "he") (ev/text-delta "llo")
                      (ev/run-end)])]
    (is (= ["RUN_STARTED" "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT"
            "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END" "RUN_FINISHED"]
           (types frames)))
    (is (empty? (wire/violations frames)))))

(deftest reasoning-group-closes-before-text-opens
  (let [frames (wire [(ev/run-start)
                      (ev/reasoning-delta "想") (ev/reasoning-delta "一下")
                      (ev/text-delta "hi")
                      (ev/run-end)])]
    (is (= ["RUN_STARTED" "REASONING_START" "REASONING_MESSAGE_START"
            "REASONING_MESSAGE_CONTENT" "REASONING_MESSAGE_CONTENT"
            "REASONING_MESSAGE_END" "REASONING_END"
            "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END"
            "RUN_FINISHED"]
           (types frames)))
    (is (empty? (wire/violations frames)))))

(deftest tool-call-with-no-text-still-gets-a-parent
  (let [frames (wire [(ev/run-start)
                      (ev/tool-call "c1" "read" "{}")
                      (ev/tool-result "c1" "file contents" false)
                      (ev/text-delta "done")
                      (ev/run-end)])]
    (is (empty? (wire/violations frames)))
    (testing "an empty text message is opened and closed around the call"
      (is (= ["RUN_STARTED"
              "TEXT_MESSAGE_START" "TEXT_MESSAGE_END"
              "TOOL_CALL_START" "TOOL_CALL_ARGS" "TOOL_CALL_END"
              "TOOL_CALL_RESULT"
              "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END"
              "RUN_FINISHED"]
             (types frames))))
    (testing "the call is parented to that message"
      (let [parent (:parentMessageId (first (filter #(= "TOOL_CALL_START" (:type %)) frames)))]
        (is (= (:messageId (first (filter #(= "TEXT_MESSAGE_START" (:type %)) frames))) parent))))
    (testing "the next turn opens a fresh message id rather than reopening"
      (let [ids (map :messageId (filter #(= "TEXT_MESSAGE_START" (:type %)) frames))]
        (is (= 2 (count ids)))
        (is (= 2 (count (distinct ids))))))))

(deftest reasoning-frames-carry-what-the-shipped-schema-demands
  ;; The shipped EventSchemas union is a zod discriminated union, and @ag-ui/client
  ;; parses every event through it BEFORE its applier runs. Two things it insists on
  ;; that the prose docs leave ambiguous: a messageId on the standalone REASONING_START
  ;; and REASONING_END lifecycle frames, and a literal role on REASONING_MESSAGE_START.
  ;; Omitting either is a hard failure in a real client.
  (let [frames  (wire [(ev/run-start) (ev/reasoning-delta "x") (ev/run-end)])
        at      (fn [t] (first (filter #(= t (:type %)) frames)))
        id      (fn [t] (:messageId (at t)))]
    (is (string? (id "REASONING_START")))
    (is (string? (id "REASONING_END")))
    (is (= "reasoning" (:role (at "REASONING_MESSAGE_START"))))
    (is (= "assistant" (:role (at "TEXT_MESSAGE_START"))))
    (testing "every frame in the group agrees on the id"
      (is (apply = (map id ["REASONING_START" "REASONING_MESSAGE_START"
                            "REASONING_MESSAGE_CONTENT" "REASONING_MESSAGE_END"
                            "REASONING_END"]))))
    (testing "a turn of reasoning and nothing else still emits an assistant message"
      ;; Otherwise the reasoning has nothing to fold back onto and DeepSeek 400s.
      (is (= ["RUN_STARTED" "REASONING_START" "REASONING_MESSAGE_START"
              "REASONING_MESSAGE_CONTENT" "REASONING_MESSAGE_END" "REASONING_END"
              "TEXT_MESSAGE_START" "TEXT_MESSAGE_END" "RUN_FINISHED"]
             (types frames))))))

(deftest an-error-closes-whatever-is-open
  (let [frames (wire [(ev/run-start)
                      (ev/reasoning-delta "x") (ev/text-delta "y")
                      (ev/run-error "boom")])]
    (is (= "RUN_ERROR" (last (types frames))))
    (is (empty? (wire/violations frames)))))

(deftest drives-the-real-loop-offline
  (let [emit   (ag/outbound "thr-9" "run-9")
        frames (atom [])]
    (doseq [event (run-events [{:reasoning "想一下" :content ""
                                :tool-calls [{:id "c1" :name "eval" :arguments {:code "(+ 1 2)"}}]}
                               {:content "等于 3"}])]
      (swap! frames into (emit event)))
    (is (empty? (wire/violations @frames)))
    (is (= "RUN_STARTED" (first (types @frames))))
    (is (= "RUN_FINISHED" (last (types @frames))))
    (testing "reasoning was rendered rather than dropped"
      (is (some #(= "REASONING_MESSAGE_CONTENT" (:type %)) @frames)))
    (testing "the eval tool really ran and its output came back as a tool result"
      (is (some #(and (= "TOOL_CALL_RESULT" (:type %))
                      (str/includes? (str (:content %)) "3"))
                @frames)))))

;; -------------------------------------------------------------------- inbound
;;
;; The round trip outbound -> client -> inbound is asserted here using the applier in
;; harness.wire -- the same one the replay tool uses to rebuild conversations. Testing
;; the shipped path rather than a test-local copy is the point: that round trip is the
;; only thing standing between this harness and a DeepSeek 400 on the second turn.

(deftest outbound-then-inbound-preserves-reasoning
  (let [emit   (ag/outbound "thr-1" "run-1")
        frames (atom [])]
    (doseq [event (run-events [{:reasoning "先算一下。" :content ""
                                :tool-calls [{:id "c1" :name "eval" :arguments {:code "(+ 1 2)"}}]}
                               {:content "等于 3"}])]
      (swap! frames into (emit event)))
    (let [client    (wire/apply-frames @frames)
          sent      (ag/inbound client "SYSTEM" nil)
          assistant (first (filter #(and (= "assistant" (:role %)) (:tool_calls %)) sent))]
      (testing "the client really did store reasoning as a message of its own"
        (is (some #(= "reasoning" (:role %)) client)))
      (testing "replaying it carries reasoning_content -- without this DeepSeek answers 400"
        (is (= "先算一下。" (:reasoning_content assistant))))
      (testing "tool calls are renamed to snake_case, arguments intact"
        (is (= [{:id "c1" :type "function"
                 :function {:name "eval" :arguments "{\"code\":\"(+ 1 2)\"}"}}]
               (:tool_calls assistant))))
      (testing "the tool result became a tool message keyed by tool_call_id"
        (is (= {:role "tool" :tool_call_id "c1" :content "3"}
               (last (filter #(= "tool" (:role %)) sent)))))
      (testing "the assistant turn that had no reasoning gets no reasoning_content"
        (is (not (contains? (last (filter #(= "assistant" (:role %)) sent))
                            :reasoning_content))))
      (testing "the system prompt leads"
        (is (= [{:role "system" :content "SYSTEM"}] (vec (take 1 sent))))))))

(deftest drops-activity-and-rebuilds-in-the-provider-shape
  (let [sent (ag/inbound [{:id "a" :role "activity" :activityType "x" :content "nope"}
                          {:id "r" :role "reasoning" :content "why "}
                          {:id "r2" :role "reasoning" :content "not"}
                          {:id "m" :role "assistant" :content "hi" :metadata {:k 1}
                           :toolCalls [{:id "c1" :type "function" :encryptedValue "zz"
                                        :function {:name "read" :arguments "{}"}}]}]
                         "S" nil)]
    (is (= ["system" "assistant"] (mapv :role sent)))
    (is (= "why not" (:reasoning_content (second sent))))
    (is (= [{:id "c1" :type "function" :function {:name "read" :arguments "{}"}}]
           (:tool_calls (second sent))))
    (testing "no AG-UI-only field survives"
      (is (not-any? #(contains? % :metadata) sent))
      (is (not-any? #(contains? % :encryptedValue) sent)))))

(deftest user-content-passes-through-untouched
  (let [parts [{:type "text" :text "看图"} {:type "image" :url "u"}]
        sent  (ag/inbound [{:id "u1" :role "user" :content parts}] "S" nil)]
    (testing "multimodal parts are not flattened by a whitelist"
      (is (= parts (:content (second sent)))))
    (is (not (contains? (second sent) :id)))))

(deftest a-leading-system-message-is-replaced
  (is (= [{:role "system" :content "S"}] (ag/inbound [{:role "system" :content "客户端的"}] "S" nil)))
  (is (= ["S"] (mapv :content (ag/inbound [] "S" nil)))))

(deftest context-rides-as-a-trailing-user-message
  (let [sent (ag/inbound [{:id "u1" :role "user" :content "hi"}]
                         "S" [{:description "repo" :value "lisp-harness"}])]
    ;; The system prompt is FROZEN -- the provider's prefill (prompt cache) keys
    ;; on it, so per-run context must never touch it.
    (is (= "S" (:content (first sent))))
    (testing "context is the last message, a user message after everything the client sent"
      (is (= {:role "user" :content "- repo: lisp-harness"} (last sent))))
    (testing "with no context nothing is appended"
      (is (= ["system" "user"]
             (mapv :role (ag/inbound [{:id "u1" :role "user" :content "hi"}] "S" nil)))))))
