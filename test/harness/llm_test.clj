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
  ;; This fixture is a REAL capture from OpenRouter (nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free)
  ;; via lisp-harness/src/harness/llm.clj:consume-sse. The synthetic 3-chunk split test below
  ;; preserves the edge case (arguments diced inside a JSON token) that the real body
  ;; happens to exercise as 2 chunks. Both are assertions against the same parser.
  (let [{:keys [msg seen]} (parse (str/split-lines fixture))]
    (testing "content and reasoning are concatenated across chunks, in order"
      (is (= "" (:content msg)))
      (is (str/includes? (str (:reasoning_content msg)) "deps.edn"))
      (is (= "" (joined seen :text/delta)))
      (is (str/includes? (joined seen :reasoning/delta) "deps.edn")))
    (testing "a tool call split across chunks comes back assembled"
      (is (= 1 (count (:tool_calls msg))))
      (is (= "read" (get-in (first (:tool_calls msg)) [:function :name])))
      (is (str/includes? (get-in (first (:tool_calls msg)) [:function :arguments]) "deps.edn")))
    (testing "the tool call is emitted exactly once, fully accumulated"
      (let [calls (filter #(= :tool/call (:type %)) seen)]
        (is (= 1 (count calls)))
        (is (= "read" (:name (first calls))))
        (is (str/includes? (:args (first calls)) "deps.edn"))))
    (testing "reasoning arrives before the tool call, as the wire does"
      (is (= [:reasoning/delta :tool/call]
             (distinct (map :type seen)))))))

(deftest prompt-is-frozen
  ;; The system prompt is read ONCE and frozen -- the provider's prefill (prompt
  ;; cache) keys on a byte-identical first message, so a prompt.md edit must not
  ;; leak into served prompts until reset-prompt! deliberately thaws it. The
  ;; file edit below is restored in finally, so the rest of the suite still sees
  ;; the real prompt.md.
  (let [original (slurp "prompt.md" :encoding "UTF-8")]
    (try
      (llm/reset-prompt!)
      (is (= original (llm/prompt)) "the first call reads prompt.md")
      (spit "prompt.md" (str original "\n<!-- drifted after freeze -->\n")
            :encoding "UTF-8")
      (is (= original (llm/prompt)) "a file edit does NOT leak into the frozen prompt")
      (finally
        (spit "prompt.md" original :encoding "UTF-8")
        (llm/reset-prompt!)))))

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
