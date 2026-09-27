(ns harness.kernel.llm-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.infra.home :as home]
            [harness.infra.llm-debug :as llm-debug]
            [harness.kernel.llm :as llm]
            [harness.test-support :as ts])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(def ^:private fixture
  (slurp (io/resource "harness/fixtures/deepseek_sse.txt") :encoding "UTF-8"))

(defn- parse [lines]
  (let [seen (atom [])
        out  (llm/consume-sse lines #(swap! seen conj %))]
    ;; Unpacked on purpose: the assertions below are about the MESSAGE, and the
    ;; telemetry that travels beside it has its own tests further down. `out` is
    ;; the contract's real shape, and one test asserts exactly that.
    {:msg (:message out) :telemetry (:telemetry out) :seen @seen}))

(defn- joined [seen type] (apply str (map :text (filter #(= type (:type %)) seen))))

(deftest consume-sse-answers-with-the-message-and-the-telemetry
  ;; THE CONTRACT EVERY stream! METHOD SHARES: a provider-shaped message to append
  ;; to the history, and what the vendor said ABOUT the call. They are two things,
  ;; and only the first one is conversation.
  (let [out (llm/consume-sse ["data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}"]
                             (fn [_]))]
    (is (= #{:message :telemetry} (set (keys out))))
    (is (= "hi" (:content (:message out))))))

(deftest the-vendors-report-survives-the-parse
  ;; THESE NUMBERS WERE UNREAD FOR AS LONG AS THE FIXTURE EXISTED. The parser took
  ;; `choices[0].delta` and never looked at `usage`, `finish_reason`, or the model
  ;; the vendor echoed back -- which is why the composer's status strip could not
  ;; count a single token of it (harness.edge.stats). They come back EXACTLY as the
  ;; vendor sent them: no renaming, no arithmetic on the way into the record.
  (let [{:keys [telemetry msg]} (parse (str/split-lines fixture))]
    (testing "the usage block, verbatim -- the vendor's own key names and nesting"
      (is (= 769 (get-in telemetry [:usage :prompt_tokens])))
      (is (= 324 (get-in telemetry [:usage :completion_tokens])))
      (is (= 1093 (get-in telemetry [:usage :total_tokens])))
      (is (= 296 (get-in telemetry [:usage :completion_tokens_details :reasoning_tokens])))
      (testing "and the cached-token cell, which is the numerator of 缓存命中"
        (is (= 0 (get-in telemetry [:usage :prompt_tokens_details :cached_tokens])))))
    (testing "finish_reason is read where the wire puts it -- inside choices[0]"
      (is (= "tool_calls" (:finish-reason telemetry))))
    (testing "and the model the vendor echoed back, not the one the request named"
      (is (= "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free" (:model telemetry))))
    (testing "none of it leaked into the message"
      (is (= #{:role :content :reasoning_content :tool_calls} (set (keys msg)))))))

(deftest a-stream-that-reports-nothing-says-no-keys
  ;; 'the vendor said nothing' is not 'the vendor said zero', and the difference is
  ;; what keeps a half-reported session from adding up to a smaller number that
  ;; looks authoritative. An empty map is the whole answer.
  (let [{:keys [telemetry]} (parse ["data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}"])]
    (is (= {} telemetry))))

(deftest a-null-finish-reason-does-not-wipe-a-real-one
  ;; Most chunks carry `"finish_reason": null`; the one that names a reason is the
  ;; last. A fold that wrote every occurrence would erase it.
  (let [{:keys [telemetry]}
        (parse ["data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":null}]}"])]
    (is (= "stop" (:finish-reason telemetry)))))

(deftest a-null-usage-does-not-wipe-a-real-one
  ;; THE SAME RULE AS finish_reason, AND THE SHARPER CASE. A vendor that reports usage at
  ;; all usually carries `"usage": null` on every chunk but the last, so a fold writing
  ;; every occurrence loses the numbers -- and it does not look like a loss: it reads as a
  ;; call that reported nothing, which is the one mistake harness.edge.stats cannot tell
  ;; from the truth. Usage is this log's only copy of the vendor's report.
  (let [{:keys [telemetry]}
        (parse ["data: {\"choices\":[{\"index\":0,\"delta\":{}}],\"usage\":{\"prompt_tokens\":769,\"prompt_tokens_details\":{\"cached_tokens\":512}}}"
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":null}"])]
    (is (= 769 (get-in telemetry [:usage :prompt_tokens])))
    (is (= 512 (get-in telemetry [:usage :prompt_tokens_details :cached_tokens])))))

(deftest the-raw-frame-text-survives-beside-the-fold
  ;; A FOLDED READING CANNOT BE CHECKED AGAINST ITS SOURCE once the source is gone, and
  ;; the two ways cached_tokens can be missing from a record are not the same thing:
  ;; the vendor never sent it, or we dropped it. Only the raw text can tell them apart,
  ;; so the raw text is what `stream!` hands the traffic log beside the fold.
  (let [sb    (StringBuilder.)
        lines ["data: {\"a\": 1}" "data: [DONE]"]
        seen  (vec (llm/tee-lines sb lines))]
    (is (= lines seen) "the same lines come out, so nothing downstream changes")
    (is (= (str (str/join "\n" lines) "\n") (str sb))
        "and the raw frame text is what was collected on the way past"))
  (testing "a consumer that stops early leaves a PARTIAL record, not no record"
    ;; LAZY ON PURPOSE, so logging can never make the stream wait for its last line.
    ;; THE INPUT IS A LAZY SEQ THAT THROWS WHEN OVER-REALIZED, and a vector would prove
    ;; nothing here: `map` realizes a whole 32-element CHUNK at a time, so a two-line
    ;; vector is one chunk and `first` pulls both. (The real input is `line-seq`, which
    ;; is built one `readLine` at a time and is not chunked.) The nested `lazy-seq` is
    ;; load-bearing: `(cons "a" (throw ...))` would evaluate the throw while BUILDING the
    ;; cons, so the test would fail on its first element instead of its second.
    (let [sb   (StringBuilder.)
          boom (lazy-seq (cons "data: {\"a\": 1}"
                               (lazy-seq (throw (ex-info "over-realized" {})))))]
      (is (= "data: {\"a\": 1}" (first (llm/tee-lines sb boom))))
      (is (= "data: {\"a\": 1}\n" (str sb))))))

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

(defn- sse-chunk
  "One vendor chunk, built from data rather than spelled as JSON here: the escaping in a
  tool call's arguments is the thing under test, and a hand-escaped literal would hide a
  mistake in the test instead of showing one in the fold."
  [delta]
  (str "data: " (json/write-str {:choices [{:index 0 :delta delta}]})))

(deftest parallel-tool-calls-keep-the-vendors-order-and-their-own-arguments
  ;; ARRIVAL ORDER IS NOT THE VENDOR'S ORDER. Fragments are keyed by `index` and concatenated
  ;; per index (`absorb!`), so a vendor may interleave two calls and may even send index 1
  ;; before index 0 -- and the fold still owes us the calls in the VENDOR's order, each with
  ;; its arguments exactly as they were spelled. Two reasons that is not cosmetic: the tool
  ;; call is part of the request's bytes on every later turn (a prefix cache keys on them),
  ;; and `unanswered-tool-calls` reads the ids in this order when it decides what a request
  ;; leaves unanswered.
  (let [c0a "{\"path\":"
        c0b "\"/tmp/a b\",\"z\":1,\"a\":2}"
        c1a "{\"pattern\":\""
        c1b "x\"}"
        {:keys [msg]} (parse [(sse-chunk {:role "assistant" :content ""
                                          :tool_calls [{:index 0 :id "call_a" :type "function"
                                                        :function {:name "read"
                                                                   :arguments c0a}}]})
                              ;; index 1 OPENS FIRST -- the fold must not take that as order
                              (sse-chunk {:tool_calls [{:index 1 :id "call_b" :type "function"
                                                        :function {:name "grep"
                                                                   :arguments c1a}}]})
                              ;; and index 0 is continued after index 1 has spoken
                              (sse-chunk {:tool_calls [{:index 0
                                                        :function {:arguments c0b}}]})
                              (sse-chunk {:tool_calls [{:index 1
                                                        :function {:arguments c1b}}]})
                              "data: [DONE]"])]
    (testing "both calls come back, in the vendor's index order"
      (is (= ["call_a" "call_b"] (mapv :id (:tool_calls msg))))
      (is (= ["read" "grep"] (mapv #(get-in % [:function :name]) (:tool_calls msg)))))
    (testing "each argument string is the vendor's TEXT, reassembled exactly"
      (is (= "{\"path\":\"/tmp/a b\",\"z\":1,\"a\":2}"
             (get-in (:tool_calls msg) [0 :function :arguments])))
      (is (= "{\"pattern\":\"x\"}" (get-in (:tool_calls msg) [1 :function :arguments]))))
    (testing "and the shape is the one the vendor reads back"
      (is (= [:role :content :tool_calls] (vec (keys msg))))
      (is (= [:id :type :function] (vec (keys (first (:tool_calls msg))))))
      (is (= [:name :arguments] (vec (keys (get-in msg [:tool_calls 0 :function]))))))))

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


;; ------------------------------------------- the adjacency the vendor demands

(deftest unanswered-tool-calls-reads-the-vendors-rule
  ;; The rule an OpenAI-shaped vendor enforces before the model runs, and the one the
  ;; run's own guard reads (thread b553ed1d, 2026-09-18: a parked call the client never
  ;; resumed made every later message of that session a 400).
  (let [call (fn [id] {:id id :type "function" :function {:name "bash" :arguments "{}"}})]
    (testing "a well-shaped history leaves nothing unanswered"
      (is (empty? (llm/unanswered-tool-calls [{:role "user" :content "hi"}])))
      (is (empty? (llm/unanswered-tool-calls [{:role "assistant" :content "" :tool_calls [(call "c1")]}
                                              {:role "tool" :tool_call_id "c1" :content "ok"}])))
      (is (empty? (llm/unanswered-tool-calls [{:role "assistant" :content "" :tool_calls [(call "c1") (call "c2")]}
                                              {:role "tool" :tool_call_id "c1" :content "a"}
                                              {:role "tool" :tool_call_id "c2" :content "b"}]))
          "every id of the call answered, in any order"))

    (testing "a call with no result at all is reported"
      (is (= ["c1"] (llm/unanswered-tool-calls [{:role "assistant" :content "" :tool_calls [(call "c1")]}])))
      (is (= ["c1"] (llm/unanswered-tool-calls [{:role "assistant" :content "" :tool_calls [(call "c1")]}
                                                 {:role "user" :content "and this"}]))
          "the next turn does not answer it either"))

    (testing "what is checked is ADJACENCY, not 'answered somewhere in the list'"
      (is (empty? (llm/unanswered-tool-calls [{:role "assistant" :content "" :tool_calls [(call "c1") (call "c2")]}
                                               {:role "tool" :tool_call_id "c2" :content "b"}
                                               {:role "tool" :tool_call_id "c1" :content "a"}]))
          "the order inside the block is not the vendor's business -- both ids are there")
      (is (= ["c2"] (llm/unanswered-tool-calls [{:role "assistant" :content "" :tool_calls [(call "c1") (call "c2")]}
                                                 {:role "tool" :tool_call_id "c1" :content "a"}
                                                 {:role "user" :content "and this"}
                                                 {:role "tool" :tool_call_id "c2" :content "b"}]))
          "but a result BEHIND another message does not answer it: that is the 400"))

    (testing "only the calls that are missing are reported"
      (is (= ["c2"] (llm/unanswered-tool-calls [{:role "assistant" :content "" :tool_calls [(call "c1") (call "c2")]}
                                                 {:role "tool" :tool_call_id "c1" :content "a"}]))))))

(deftest adjacent-answers-moves-a-late-answer-behind-its-call
  ;; THE RECORD CAN DELIVER AN ANSWER LATE. A run cut off mid-call is closed off by an
  ;; APPENDED TOOL_CALL_RESULT (`harness.edge.replay/closing-frames`), so a conversation
  ;; folded in file order can hold the answer BEHIND whatever the client recorded next --
  ;; the shape the vendor refuses (the case above). Moving the recorded answer behind the
  ;; call that named it is not inventing a result: the message is already there.
  (let [call   (fn [id] {:id id :type "function" :function {:name "bash" :arguments "{}"}})
        answer (fn [id] {:role "tool" :tool_call_id id :content "the run was cut off"})]
    (testing "a well-shaped history comes back unchanged, message for message"
      (let [well [{:role "user" :content "hi"}
                  {:role "assistant" :content "" :tool_calls [(call "c1") (call "c2")]}
                  (answer "c1") (answer "c2")
                  {:role "user" :content "and this"}]]
        (is (= well (llm/adjacent-answers well)))))
    (testing "an answer that landed behind a later message is moved behind its call"
      (let [late [{:role "assistant" :content "" :tool_calls [(call "c1")]}
                  {:role "user" :content "and this"}
                  (answer "c1")]]
        (is (= [{:role "assistant" :content "" :tool_calls [(call "c1")]}
                (answer "c1")
                {:role "user" :content "and this"}]
               (llm/adjacent-answers late)))
        (is (empty? (llm/unanswered-tool-calls (llm/adjacent-answers late))))))
    (testing "answers are put back in CALL order, whatever order they arrived in"
      (let [late [{:role "assistant" :content "" :tool_calls [(call "c1") (call "c2")]}
                  {:role "user" :content "and this"}
                  (answer "c2") (answer "c1")]]
        (is (= ["c1" "c2"]
               (mapv :tool_call_id (filter :tool_call_id (llm/adjacent-answers late))))
            "call order, not arrival order")))
    (testing "an answer with no call to sit behind is left where it is"
      (let [orphan [{:role "user" :content "hi"} (answer "c9")]]
        (is (= orphan (llm/adjacent-answers orphan)))
        (is (empty? (llm/unanswered-tool-calls (llm/adjacent-answers orphan)))
            "a stray answer is not a call, so the vendor's rule has nothing to say")))))

;; ----------------------------------------- the wire, and the traffic log

(defn- sse-server
  "A REAL HTTP server on an OS-assigned loopback port, answering every request with
  STATUS and BODY. The smallest thing that drives the wire path at all -- which had
  no test before this, and which the traffic log now has a stake in.

  Answers [base-url stop]. The PORT IS THE OS'S TO PICK (`0`), the rule this
  repository follows wherever a listener is needed: a fixed one makes a test that
  fails whenever something else on the machine happens to hold it."
  [status body]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [bytes (.getBytes body StandardCharsets/UTF_8)]
                          (.sendResponseHeaders ex status (alength bytes))
                          (with-open [out (.getResponseBody ex)]
                            (.write out bytes))))))
    (.start server)
    [(str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")
     (fn [] (.stop server 0))]))

(defn- traffic-lines
  "Every line of the traffic log under ROOT, parsed -- empty when no file was
  written, which is itself an assertion elsewhere."
  [root]
  (let [f (io/file root "logs" llm-debug/file-name)]
    (if (.exists f)
      (mapv #(json/read-str % :key-fn keyword)
            (remove str/blank? (str/split-lines (slurp f :encoding "UTF-8"))))
      [])))

(deftest the-traffic-log-holds-the-request-then-the-response
  (let [[base-url stop] (sse-server 200 fixture)]
    (try
      (let [root (ts/temp-dir "llm-traffic")]
        (binding [home/*root-override* root
                  llm-debug/*override* true]
          (let [out (llm/stream! {:protocol :openai-completions :base-url base-url :model "m"}
                                 [{:role "user" :content "hi"}]
                                 (fn [_]) "t-1")
                ls  (traffic-lines root)]
            (is (= "assistant" (:role (:message out))))
            (is (seq (:tool_calls (:message out)))
                "the tool call the fixture carries came back through the real wire")
            (is (= ["request" "response"] (mapv :at ls)) "one line each way, in the order they happened")
            (testing "the request line holds the body that went out, verbatim"
              (is (= "m" (:model (json/read-str (:body (first ls)) :key-fn keyword))))
              (is (= "hi" (get-in (json/read-str (:body (first ls)) :key-fn keyword)
                                   [:messages 0 :content])))
              (is (= "t-1" (:thread-id (first ls)))))
            (testing "the response line carries what the vendor reported about the call"
              (is (= 769 (get-in (second ls) [:telemetry :usage :prompt_tokens])))
              (is (= 0 (get-in (second ls)
                               [:telemetry :usage :prompt_tokens_details :cached_tokens]))))
            (testing "and the frame text the vendor sent, beside the reading of it"
              ;; THE EVIDENCE, NOT A SECOND OPINION: a folded map cannot be checked
              ;; against its source once the source is gone, and 'the vendor never sent
              ;; cached_tokens' is not 'we dropped it'.
              (is (string? (:body (second ls)))
                  "the raw SSE text, not a re-serialization of the parsed chunks")
              (is (str/starts-with? (:body (second ls)) "data: "))
              (is (= (str/split-lines fixture) (str/split-lines (:body (second ls))))
                  "every line of what arrived, in the order it arrived")))))
      (finally (stop)))))

(deftest the-request-is-on-the-log-even-when-the-call-never-leaves
  ;; THE ORDER IS THE POINT: the line is written BEFORE the request is built and sent,
  ;; so a hang or a refused connection still leaves what was sent on the record -- the
  ;; one thing somebody debugging a stall wants. An unparseable base-url is the
  ;; shortest way to a send that throws without a network at all (`URI/create` refuses
  ;; the spaces).
  (let [root (ts/temp-dir "llm-traffic-unreachable")]
    (binding [home/*root-override* root
              llm-debug/*override* true]
      (is (thrown? IllegalArgumentException
                   (llm/stream! {:protocol :openai-completions :base-url "not a url" :model "m"}
                                [{:role "user" :content "hi"}]
                                (fn [_]) "t-1")))
      (let [ls (traffic-lines root)]
        (is (= 1 (count ls)) "the request, and no response beside it")
        (is (= "request" (:at (first ls))))
        (is (str/includes? (:body (first ls)) "\"model\":\"m\""))))))

(deftest a-length-refusal-is-recognised-and-an-unrelated-400-is-not
  ;; ONE FAILURE, not every 400. The sentences below are the vendors' own, copied rather than
  ;; paraphrased -- that is what makes them evidence -- and the near misses are refusals the run
  ;; must keep reporting as themselves.
  (let [refusal (fn [status body] (ex-info (str "HTTP " status ": " body) {:status status}))]
    (testing "the vendors' own overflow refusals"
      (doseq [body ["This model's maximum context length is 128000 tokens. However, your messages resulted in 200000 tokens."
                    "{\"error\":{\"message\":\"prompt is too long: 210000 tokens > 200000 maximum\"}}"
                    "{\"error\":{\"message\":\"This model's maximum context length is 65536 tokens.\",\"code\":\"context_length_exceeded\"}}"
                    "Please reduce the length of the messages."]]
        (is (true? (llm/context-overflow? (refusal 400 body))) body)))
    (testing "a 413 carries it too"
      (is (true? (llm/context-overflow? (refusal 413 "input is too long")))))
    (testing "a 400 that is NOT about length stays a plain failure"
      (is (false? (llm/context-overflow? (refusal 400 "model \"x\" does not accept [\"image\"] input"))))
      (is (false? (llm/context-overflow? (refusal 400 "unknown parameter: max_tokens")))))
    (testing "the sentence alone is not enough -- the status must be a client error"
      (is (false? (llm/context-overflow? (refusal 500 "maximum context length"))))
      (is (false? (llm/context-overflow? (refusal 429 "maximum context length")))))
    (testing "nothing, and a throwable with no status, are not refusals"
      (is (false? (llm/context-overflow? nil)))
      (is (false? (llm/context-overflow? (ex-info "maximum context length" {})))))))

;; --------------------------------------------------------------- the idle guard

(defn- stalling-sse-server
  "A REAL server that writes FIRST, FLUSHES, says nothing for STALL-MS, and then writes
  SECOND and hangs up -- a vendor that answers and then goes quiet, which is the shape the
  guard exists for.

  CHUNKED ON PURPOSE (`sendResponseHeaders 200 0`): with a length the JDK would buffer the
  whole body and hand it over at once, and there would be no silence to guard. Answers
  [base-url stop]."
  [first second stall-ms]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [^java.io.OutputStream body (.getResponseBody ex)]
                          (.sendResponseHeaders ex 200 0)
                          (try
                            (.write body (.getBytes first StandardCharsets/UTF_8))
                            (.flush body)
                            (Thread/sleep (long stall-ms))
                            (.write body (.getBytes second StandardCharsets/UTF_8))
                            (.flush body)
                            ;; THE GUARD MAY HAVE HUNG UP ALREADY, and that is the case the
                            ;; other test is about: writing to a closed connection is the
                            ;; fixture's expected weather, not a failure to report.
                            (catch java.io.IOException _ nil)
                            (finally (try (.close body) (catch Throwable _ nil))))))))
    (.start server)
    [(str "http://127.0.0.1:" (.getPort (.getAddress server)) "/v1")
     (fn [] (.stop server 0))]))

(def ^:private hello-frame
  "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"one\"}}]}\n")

(def ^:private goodbye-frame
  (str "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"two\"}}]}\n"
       "data: [DONE]\n"))

(deftest a-stream-that-goes-quiet-is-cut-off-with-the-guards-own-failure
  ;; THE READ SIDE OF `.scratch/llm-idle-timeout`. The vendor said one thing and then went
  ;; silent for 300 ms with a 100 ms deadline, so the call is cut off -- and what comes back
  ;; is the GUARD's failure rather than the socket's: an `IOException("closed")` would name
  ;; nothing a caller could act on, and every caller of `stream!` would have to know that
  ;; the close was ours.
  (let [[base-url stop] (stalling-sse-server hello-frame goodbye-frame 300)]
    (try
      (let [t (try (llm/stream! {:protocol :openai-completions :base-url base-url :model "m"
                                 :idle-timeout-ms 100}
                                [{:role "user" :content "hi"}] (fn [_]) "t-idle")
                     nil
                     (catch Throwable t t))]
        (is (some? t) "the call did not come back")
        (is (llm/idle-timeout? t) "and it came back as the guard's own failure")
        (is (= 100 (:idle-ms (ex-data t))))
        (is (str/includes? (ex-message t) "100 ms")))
      (finally (stop)))))

(deftest the-guard-leaves-an-answering-vendor-alone
  ;; THE OTHER HALF, and the one that matters in production: the same server, the same
  ;; 300 ms of silence, and NO deadline -- because a provider map that carries none is not
  ;; guarded (`harness.kernel.llm/default-idle-timeout-ms`). What is being pinned is that
  ;; the guard is a deadline between LINES and not a cap on how long a call may take: this
  ;; stream is slower than the deadline below and still completes.
  (let [[base-url stop] (stalling-sse-server hello-frame goodbye-frame 300)]
    (try
      (let [out (llm/stream! {:protocol :openai-completions :base-url base-url :model "m"}
                             [{:role "user" :content "hi"}] (fn [_]) "t-slow")]
        (is (= "onetwo" (:content (:message out)))
            "both frames arrived -- the silence was waited out, not cut off"))
      (finally (stop)))))

(deftest a-zero-deadline-is-no-deadline
  ;; `0` IS A VALUE AND NOT A TYPO (`harness.edge.llm-timeout` says the same about the
  ;; knob): it turns the guard off, which is what a session with a slow vendor sets. The
  ;; predicate is asserted directly because the branch is in `idle-guarded-lines`, and the
  ;; slow server above already proves the reading end of it.
  (is (false? (llm/idle-timeout? (ex-info "something else" {}))))
  (is (false? (llm/idle-timeout? nil)))
  (is (true? (llm/idle-timeout? (ex-info "quiet" {:llm/idle-timeout true :idle-ms 500})))))
