(ns harness.evals-test
  "The author's read side for eval: what code a thread ran and what came back.

  The records below encode the REAL shapes: the code is in an assistant message's
  tool_calls[].function.arguments as a JSON STRING, the return value is the content
  of the tool message with the matching tool_call_id, and the audit lines carry no
  arguments at all. One of the tests locks that last fact, so a future reader does
  not 'simplify' this extractor by pulling code out of the audit trail it is not in."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [harness.evals :as evals]))

(defn- record [kind payload] {:ts 1 :runId "r1" :kind kind :payload payload})

(defn- assistant-with-eval [call-id code]
  (record "message"
          {:role "assistant" :content ""
           :tool_calls [{:id call-id :type "function"
                         :function {:name "eval"
                                    ;; arguments is a JSON STRING, the provider's wire shape.
                                    :arguments (json/write-str {:code code})}}]}))

(defn- tool-result [call-id content]
  (record "message" {:role "tool" :tool_call_id call-id :content content}))

(defn- audit-lines [call-id]
  [(record "tools/pre-execute" {:toolCallId call-id :toolName "eval" :outcome "pass"})
   (record "tools/execute" {:toolCallId call-id :toolName "eval" :error nil})
   (record "tools/post-execute" {:toolCallId call-id :toolName "eval"})])

(defn- with-audit
  "Records with the three audit lines spliced in before the message pair."
  [before call-id code content]
  (vec (concat before (audit-lines call-id)
               [(assistant-with-eval call-id code) (tool-result call-id content)])))

;; ------------------------------------------------------------------- happy path

(def ^:private spaced-code
  "(do (harness.kernel.tools/session-register! harness.kernel.tools/*thread-id* \"note\"
         {:description \"note\"
          :required    []
          :run         (fn [_] \"hi\")})
       :added)")

(deftest reads-each-eval-in-order-with-its-code-verbatim
  (let [records [(record "input" {:messages []})
                 (record "tools/pre-execute" {:toolCallId "c1" :toolName "eval" :outcome "pass"})
                 (record "tools/execute" {:toolCallId "c1" :toolName "eval" :error nil})
                 (record "tools/post-execute" {:toolCallId "c1" :toolName "eval"})
                 (assistant-with-eval "c1" spaced-code)
                 (tool-result "c1" ":added")]
        result  (evals/evals-in records)]
    (is (= 1 (count result)))
    (let [{:keys [tool-call-id code result error?]} (first result)]
      (testing "the code comes back VERBATIM -- internal whitespace and all"
        (is (= spaced-code code)))
      (testing "the tool call id joins the two sides"
        (is (= "c1" tool-call-id)))
      (testing "the return value is the matching tool message's content"
        (is (= ":added" result)))
      (testing "a clean call is not marked as errored"
        (is (false? error?))))))

(deftest the-code-is-NOT-in-the-audit-lines
  ;; Documentation-as-test: the audit line schema carries no arguments. If someone
  ;; later adds them, this reader can be simplified -- but until then, any attempt
  ;; to read code off the audit trail is reading a field that does not exist.
  (let [audit (audit-lines "c1")]
    (is (every? (fn [r] (not (contains? (:payload r) :arguments))) audit))
    (is (every? (fn [r] (not (contains? (:payload r) :args))) audit))
    (is (every? (fn [r] (not (contains? (:payload r) :code))) audit))))

(deftest multiple-evals-come-back-in-call-order
  ;; NB: build the list with vector literals, not (into [] (concat m1 m2)) -- into
  ;; uses conj, and concat treats a MAP as a seq of its own entries, so each record
  ;; would be spliced into key/value pairs.
  (let [records [(record "input" {:messages []})
                 (assistant-with-eval "a" "(+ 1 2)")
                 (tool-result "a" "3")
                 (assistant-with-eval "b" "(* 3 4)")
                 (tool-result "b" "12")]
        result  (evals/evals-in records)]
    (is (= ["a" "b"] (mapv :tool-call-id result)))
    (is (= ["(+ 1 2)" "(* 3 4)"] (mapv :code result)))
    (is (= ["3" "12"] (mapv :result result)))))

;; ------------------------------------------------------------------- error path

(deftest an-errored-eval-is-identifiable
  (testing "via the audit line's error"
    (let [records (-> (with-audit [(record "input" {:messages []})]
                                  "c1" "(unclosed" "Syntax error")
                    ;; records[2] is the tools/execute audit line -- give it the error.
                    (assoc-in [2 :payload :error] "Syntax error"))
          result  (first (evals/evals-in records))]
      (is (true? (:error? result)))
      (testing "and the value the model saw is still shown untouched"
        (is (= "Syntax error" (:result result)))))))

;; ------------------------------------------------------------------ edge cases

(deftest a-thread-with-no-evals-reads-as-an-empty-collection
  (is (= [] (evals/evals-in [])))
  (is (= [] (evals/evals-in [(record "input" {:messages []})
                             (record "message" {:role "assistant" :content "no calls"})
                             (record "message" {:role "tool" :tool_call_id "x" :content "y"})]))))

(deftest non-eval-tool-calls-are-skipped
  (let [records [(record "message"
                         {:role "assistant" :content ""
                          :tool_calls [{:id "c1" :type "function"
                                        :function {:name "read"
                                                   :arguments "{\"path\":\"deps.edn\"}"}}]})
                 (tool-result "c1" "file contents")
                 (assistant-with-eval "c2" ":kept")
                 (tool-result "c2" ":kept")]]
    (is (= ["c2"] (mapv :tool-call-id (evals/evals-in records))))))

(deftest an-eval-without-a-recorded-return-still-lists
  ;; The log can be read mid-run; the call is there before its result is. The reader
  ;; shows the gap rather than dropping the call -- hiding it would look like the
  ;; eval never happened.
  (let [result (first (evals/evals-in [(assistant-with-eval "c1" ":work-in-progress")]))]
    (is (= ":work-in-progress" (:code result)))
    (is (nil? (:result result)))))

(deftest unreadable-arguments-are-reported-not-dropped
  (let [records [(record "message"
                         {:role "assistant" :content ""
                          :tool_calls [{:id "c1" :type "function"
                                        :function {:name "eval"
                                                   :arguments "{not json"}}]})
                 (tool-result "c1" "boom")]
        result  (first (evals/evals-in records))]
    (is (= "c1" (:tool-call-id result)))
    (is (some? (:undecodable result)))
    (is (nil? (:code result)))))

(deftest describe-renders-one-eval-for-a-human
  (let [text (evals/describe (first (evals/evals-in
                                     [(assistant-with-eval "c1" "(+ 1 2)")
                                      (tool-result "c1" "3")])))]
    (is (clojure.string/includes? text "c1"))
    (is (clojure.string/includes? text "(+ 1 2)"))
    (is (clojure.string/includes? text "=> 3"))))

;; ----------------------------------------------------------- the read discipline

(deftest a-corrupt-log-fails-hard-and-names-the-line
  ;; Delegated to harness.edge.replay -- asserted here so a reader of THIS namespace
  ;; knows the guarantee holds on its path too.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"line 2"
                        (evals/evals-in
                         (let [good (json/write-str (assistant-with-eval "c1" "(+ 1 2)"))]
                           (harness.edge.replay/lines->records [good "{truncated"]))))))
