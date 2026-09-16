(ns harness.kernel.llm-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.kernel.llm :as llm]))

(def ^:private fixture
  (slurp (io/resource "harness/fixtures/deepseek_sse.txt") :encoding "UTF-8"))

(defn- parse [lines]
  (let [seen (atom [])
        msg  (llm/consume-sse lines #(swap! seen conj %))]
    {:msg msg :seen @seen}))

(defn- joined [seen type] (apply str (map :text (filter #(= type (:type %)) seen))))

(deftest parses-a-streaming-body
  ;; This fixture is a REAL capture from OpenRouter (nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free)
  ;; via src/harness/llm.clj:consume-sse. The synthetic 3-chunk split test below
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
  ;; What is frozen is the OPENING of the system message -- prompt.md, read once.
  ;; (The message itself is assembled per run from it in harness.cap.system-prompt;
  ;; the part that must not drift is this one, because the provider's prefill
  ;; (prompt cache) keys on a byte-identical prefix, so a prompt.md edit must not
  ;; leak into served prompts until reset-prompt! deliberately thaws it.) The file
  ;; edit below is restored in finally, so the rest of the suite still sees the
  ;; real prompt.md.
  (let [original (slurp "prompt.md" :encoding "UTF-8")]
    (try
      (llm/reset-prompt!)
      (is (= original (llm/prompt)) "the first call reads prompt.md")
      (spit "prompt.md" (str original "\n<!-- drifted after freeze -->\n")
            :encoding "UTF-8")
      (is (= original (llm/prompt)) "a file edit does NOT leak into the frozen opening")
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
    (testing "a vendor that says nothing about reasoning leaves no key"
      ;; 'Said nothing' and 'said empty' are DIFFERENT FACTS, and the difference is
      ;; load-bearing: a thinking-mode vendor mentions the field on every round and
      ;; requires it back, so its empty value has to survive (see the tests below),
      ;; while a vendor that never mentions it must not have one invented for it.
      (is (not (contains? msg :reasoning_content)))
      (is (not (contains? msg :tool_calls)))))

  (testing "but a vendor that MENTIONS it keeps it, empty and all"
    (let [{:keys [msg]} (parse ["data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"\"}}]}"
                                "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}"])]
      (is (= "hi" (:content msg)))
      (is (= "" (:reasoning_content msg))
          "the empty value is the vendor telling us this round had no reasoning -- and
           asking for it back on the next request")))
  (testing "and the other spelling counts as mentioning it too"
    (let [{:keys [msg]} (parse ["data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\",\"reasoning\":\"\"}}]}"])]
      (is (= "" (:reasoning_content msg))))))

;; ------------------------------------------------- the thinking-mode requirement

(deftest a-thinking-mode-history-carries-the-field-on-every-assistant-message
  ;; The vendor's rule, and the reason it exists: a DeepSeek-compatible gateway
  ;; refuses a thinking-mode request whose history holds an assistant message with
  ;; no `reasoning_content` -- even the rounds that produced none, which is the case
  ;; that bit a real home (see .scratch/reasoning-round-trip/spec.md).
  (let [history [{:role "system"    :content "sys"}
                 {:role "user"      :content "hi"}
                 {:role "assistant" :content "" :tool_calls [{:id "c1"}]}
                 {:role "tool"      :tool_call_id "c1" :content "ok"}
                 {:role "assistant" :content "done" :reasoning_content "I thought about it"}]]
    (testing "every assistant message gets the field; nothing else is touched"
      (let [out (llm/thinking-mode-history history {:reasoning-effort "high"})]
        (is (= "" (:reasoning_content (nth out 2))) "the round with no reasoning gets the empty string")
        (is (= "I thought about it" (:reasoning_content (nth out 4)))
            "and a round that HAS reasoning keeps it, byte for byte")
        (is (nil? (:reasoning_content (nth out 3))) "a tool message is not an assistant message")
        (is (= (mapv :role history) (mapv :role out)) "and the shape is otherwise untouched")))

    (testing "an empty string is the fill -- never invented text"
      ;; Anything else would be putting words in the model's mouth and sending them
      ;; back as if it had thought them.
      (let [out (llm/thinking-mode-history [{:role "assistant" :content "x"}]
                                           {:reasoning-effort "low"})]
        (is (= "" (:reasoning_content (first out))))))

    (testing "a provider with no reasoning effort is untouched, byte for byte"
      (is (= history (llm/thinking-mode-history history {})))
      (is (= history (llm/thinking-mode-history history {:reasoning-effort nil}))))))
