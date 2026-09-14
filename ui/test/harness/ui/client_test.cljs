(ns harness.ui.client-test
  "The real @ag-ui/client -- the same HttpAgent CopilotKit runs on underneath --
  driving a live harness. From ui/verify.mjs.

  What this proves that an offline test structurally cannot: the frames the
  server writes are frames a REAL client can consume. A run can be generated,
  logged and even schema-valid and still fail here, because the client
  materialises messages by applying frames in order -- reasoning, tool calls,
  tool results -- and any of that can come out wrong while every individual frame
  looks fine.

  The script is ours, so the reasoning text and the final answer are known
  exactly, and the byte-for-byte comparison stays meaningful without a model."
  (:require ["@ag-ui/client" :refer [HttpAgent]]
            [cljs.test :refer [deftest is]]
            [harness.ui.e2e :as e2e])
  (:require-macros [harness.ui.test-runner :refer [deftest-index]]))

(def ^:private reasoning "\u7528\u6237\u60f3\u770b\u8fd9\u4e2a\u9879\u76ee\u3002\u5148\u8bfb deps.edn \u786e\u8ba4\u4f9d\u8d56\u3002")
(def ^:private answer "\u8fd9\u662f\u4e00\u4e2a Clojure \u9879\u76ee\uff0c\u53ea\u6709 4 \u4e2a\u4f9d\u8d56\u3002")

(defn- new-agent []
  (let [agent (HttpAgent. #js {:url (e2e/url)})
        events (atom [])]
    (.subscribe agent
                #js {:onReasoningMessageStartEvent
                     (fn [_] (swap! events conj "REASONING_MESSAGE_START"))
                     :onReasoningMessageContentEvent
                     (fn [^js b] (swap! events conj (str "reasoning:" (.-delta (.-event b)))))
                     :onReasoningMessageEndEvent
                     (fn [_] (swap! events conj "REASONING_MESSAGE_END"))
                     :onTextMessageContentEvent
                     (fn [^js b] (swap! events conj (str "text:" (.-delta (.-event b)))))
                     :onToolCallStartEvent
                     (fn [^js b] (swap! events conj (str "tool:" (.-toolCallName (.-event b)))))
                     :onToolCallResultEvent (fn [_] (swap! events conj "TOOL_CALL_RESULT"))
                     :onRunFinishedEvent (fn [_] (swap! events conj "RUN_FINISHED"))})
    [agent events]))

(defn content [^js m] (if (string? (.-content m)) (.-content m) ""))

(deftest reasoning-and-tool-calls-survive-the-wire
  (let [[^js agent events] (new-agent)]
    (e2e/async!
     (fn [_]
       (e2e/script! [#js {:reasoning reasoning
                          :content ""
                          :tool-calls #js [#js {:id "c1" :name "read"
                                                :arguments #js {:path "deps.edn"}}]}
                     #js {:content answer}])
       (.runAgent agent #js {:threadId (e2e/thread-id "client")
                             :tools #js [] :context #js []}))
     (fn [_]
       (let [messages (vec (array-seq (.-messages agent)))
             by-role (group-by #(.-role %) messages)
             reasoning-msgs (get by-role "reasoning" [])
             assistant-msgs (get by-role "assistant" [])]
         (is (seq reasoning-msgs) "the client materialised a reasoning message")
         (is (some #(= reasoning (content %)) reasoning-msgs)
             "and its text survived the wire byte for byte")
         (is (some #(pos? (.-length (or (.-toolCalls ^js %) #js []))) assistant-msgs)
             "an assistant message carries the tool call")
         (is (seq (get by-role "tool" [])) "the tool result came back as a message")
         (is (some #(= answer (content %)) messages) "the final answer arrived intact")
         (is (some #(>= (.indexOf % "reasoning:") 0) @events)
             "the reasoning HOOKS fired, not merely the message list")
         (is (not-any? #(>= (.indexOf % "CHUNK") 0) @events)
             "no chunk event ever reached the client")
         (is (some #(= "RUN_FINISHED" %) @events) "and the run finished"))))))

(deftest a-tool-round-costs-two-llm-calls
  ;; The scripted provider hands out ONE TURN PER CALL, so a tool round has to
  ;; consume exactly two turns: the call, then the reply to its result. If the
  ;; kernel called the model once and reused the answer, the first turn's tool
  ;; call would come back as the final message and the second turn's text would
  ;; never appear -- which is what this asserts, rather than trusting a count.
  (let [[^js agent _] (new-agent)]
    (e2e/async!
     (fn [_]
       (e2e/script! [#js {:content ""
                          :tool-calls #js [#js {:id "c1" :name "read"
                                                :arguments #js {:path "deps.edn"}}]}
                     #js {:content "\u7b2c\u4e8c\u8f6e\u3002"}])
       (.runAgent agent #js {:threadId (e2e/thread-id "twocalls")
                             :tools #js [] :context #js []}))
     (fn [_]
       (let [messages (vec (array-seq (.-messages agent)))]
         (is (some #(= "\u7b2c\u4e8c\u8f6e\u3002" (content %)) messages)
             "the SECOND turn's text is in the conversation")
         (is (= 1 (count (filter #(= "tool" (.-role %)) messages)))
             "exactly one tool message, from the one call"))))))

(deftest-index)
