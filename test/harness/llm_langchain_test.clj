(ns harness.llm-langchain-test
  "Offline tests for the :langchain4clj provider. Nothing here touches the
  network: lc/chat is stubbed to return ChatResponse objects we build locally.
  These lock the translation (history <-> LangChain4j messages, specs -> tool
  specs) and the response parse (text + tool_calls), plus a full drive through
  harness.loop with a real tool."
  (:require [clojure.test :refer [deftest is testing]]
            [harness.llm :as llm]
            [harness.llm-langchain :as lc]
            [harness.loop :as loop]
            [harness.tools :as tools]
            [langchain4clj.core :as lcc]
            [langchain4clj.messages :as lcmsg])
  (:import [dev.langchain4j.data.message AiMessage]
           [dev.langchain4j.agent.tool ToolExecutionRequest]
           [dev.langchain4j.model.chat.response ChatResponse]
           [java.util ArrayList]))

(defn- fake-response
  "Build a ChatResponse offline: text plus optional tool requests."
  [text tool-reqs]
  (let [ai (if (seq tool-reqs)
             (AiMessage. text
               (ArrayList.
                 (mapv (fn [{:keys [id name arguments]}]
                         (-> (ToolExecutionRequest/builder)
                             (.id id) (.name name) (.arguments arguments) (.build)))
                       tool-reqs)))
             (AiMessage/from text))]
    (-> (ChatResponse/builder) (.aiMessage ai) (.build))))

(defn- provider [] {:protocol :langchain4clj :api-key "x" :model "m"})

(deftest text-only-turn-emits-text-delta-and-plain-assistant
  (let [seen (atom [])]
    (with-redefs [lcc/chat (fn [_ _ _] (fake-response "echo:hi" nil))]
      (let [msg (llm/stream! (provider)
                             [{:role "user" :content "hi"}]
                             #(swap! seen conj %))]
        (testing "one text delta, then a plain assistant message"
          (is (= [{:type :text/delta :text "echo:hi"}] @seen))
          (is (= {:role "assistant" :content "echo:hi"} msg)))))))

(deftest tool-call-turn-emits-tool-call-and-assistant-with-tool-calls
  (let [seen (atom [])]
    (with-redefs [lcc/chat (fn [_ _ _]
                             (fake-response ""
                               [{:id "c1" :name "read" :arguments "{\"path\":\"/x\"}"}]))]
      (let [msg (llm/stream! (provider)
                             [{:role "user" :content "read /x"}]
                             #(swap! seen conj %))]
        (testing "tool/call event fired and assistant carries tool_calls"
          (is (= 1 (count (filter #(= :tool/call (:type %)) @seen))))
          (is (= :tool/call (:type (first (filter #(= :tool/call (:type %)) @seen)))))
          (is (= "c1" (:id (first (filter #(= :tool/call (:type %)) @seen)))))
          (is (= [{:id "c1" :type "function"
                   :function {:name "read" :arguments "{\"path\":\"/x\"}"}}]
                 (:tool_calls msg))))))))

(deftest history-translates-to-langchain4j-messages
  (let [captured (atom nil)
        hist [{:role "system" :content "sys"}
              {:role "user" :content "hi"}
              {:role "assistant" :content ""
               :tool_calls [{:id "c1" :type "function"
                             :function {:name "read" :arguments "{}"}}]}
              {:role "tool" :tool_call_id "c1" :content "file-body"}]]
    (with-redefs [lcc/chat (fn [_ msgs _] (reset! captured msgs) (fake-response "ok" nil))]
      (llm/stream! (provider) hist (fn [_]))
      (let [edn (lcmsg/messages->edn @captured)]
        (testing "system/user/assistant+tool_calls/tool round-trips to LC EDN"
          (is (= [{:type :system :text "sys"}
                  {:type :user :contents [{:type :text :text "hi"}]}
                  {:type :ai :text "" :tool-execution-requests
                   [{:id "c1" :name "read" :arguments "{}"}]}
                  {:type :tool-result :id "c1" :tool-name "read" :text "file-body"}]
                 edn)))))))

(deftest specs-convert-to-langchain4j-tool-specs
  (let [specs (lc/specs->tool-specs)]
    (testing "every harness tool becomes a ToolSpecification"
      (is (= (count (tools/specs)) (count specs)))
      (is (every? #(instance? dev.langchain4j.agent.tool.ToolSpecification %) specs))
      (is (= #{"bash" "edit" "eval" "read" "write"}
             (set (map #(.name %) specs)))))))

(deftest drives-a-real-tool-through-harness-loop
  (let [seen (atom [])
        calls (atom 0)
        resp1 (fake-response "" [{:id "c1" :name "eval" :arguments "{\"code\":\"(+ 1 2)\"}"}])
        resp2 (fake-response "answer" nil)]
    (with-redefs [lcc/chat (fn [_ _ _]
                            (let [i (swap! calls inc)]
                              (if (= i 1) resp1 resp2)))]
      (let [history (loop/run! (provider) [] #(swap! seen conj %))]
        (testing "langchain provider generates a call, harness runs the tool, run ends"
          (is (= "answer" (:content (last history))))
          (is (= ["c1"] (mapv :id (filter #(= :tool/call (:type %)) @seen))))
          (is (= ["3"] (mapv :content (filter #(= :tool/result (:type %)) @seen))))
          (is (= :run/end (last (mapv :type @seen)))))))))
