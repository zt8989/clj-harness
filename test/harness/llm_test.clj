(ns harness.llm-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.llm :as llm]))

(def ^:private fixture
  (slurp (io/resource "harness/fixtures/deepseek_sse.txt") :encoding "UTF-8"))

(defn- parse [lines]
  (let [seen (atom [])
        msg  (llm/consume-sse lines #(swap! seen conj %))]
    {:msg msg :seen @seen}))

(defn- joined [seen type] (apply str (map :text (filter #(= type (:type %)) seen))))

(deftest parses-a-streaming-body
  (let [{:keys [msg seen]} (parse (str/split-lines fixture))]
    (testing "content and reasoning are concatenated across chunks, in order"
      (is (= "我先读一下。" (:content msg)))
      (is (= "用户想要看 deps.edn。" (:reasoning_content msg)))
      (is (= "我先读一下。" (joined seen :text/delta)))
      (is (= "用户想要看 deps.edn。" (joined seen :reasoning/delta))))
    (testing "a tool call split across three chunks comes back assembled"
      (is (= [{:id "call_00_abc" :type "function"
               :function {:name "read" :arguments "{\"path\": \"deps.edn\"}"}}]
             (:tool_calls msg))))
    (testing "the tool call is emitted exactly once, fully accumulated"
      (let [calls (filter #(= :tool/call (:type %)) seen)]
        (is (= 1 (count calls)))
        (is (= {:type :tool/call :id "call_00_abc" :name "read"
                :args "{\"path\": \"deps.edn\"}"}
               (first calls)))))
    (testing "reasoning arrives before content, as the wire does"
      (is (= [:reasoning/delta :text/delta :tool/call]
             (distinct (map :type seen)))))))

(deftest the-opening-empty-chunk-emits-nothing
  (testing "the opening chunk is {\"role\":\"assistant\",\"content\":\"\"}. An empty
            string is truthy in Clojure, so a naive guard would emit a text delta
            ahead of the reasoning -- and ag-ui would open a text message first,
            leaving the reasoning with no assistant message to fold back onto."
    (let [{:keys [seen]} (parse ["data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}"])]
      (is (empty? seen)))))

(deftest ignores-non-data-lines-and-the-done-sentinel
  (let [{:keys [msg seen]} (parse ["" ": a comment" "event: ping" "data: [DONE]"])]
    (is (empty? seen))
    (is (= "" (:content msg)))
    (is (nil? (:tool_calls msg)))))

(deftest omits-empty-fields
  (let [{:keys [msg]} (parse ["data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}"])]
    (is (= "hi" (:content msg)))
    (testing "no reasoning and no tool calls means no such keys, not empty ones"
      (is (not (contains? msg :reasoning_content)))
      (is (not (contains? msg :tool_calls))))))
