(ns harness.kernel.loop-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.fake :as fake]
            [harness.kernel.loop :as loop]
            [harness.cap.project :as project]
            [harness.kernel.tools :as tools]
            [harness.test-support :as support]))

(use-fixtures :once support/with-builtins)

(defn- drain-chan [ch]
  (loop [acc []]
    (if-let [ev (async/<!! ch)]
      (if (= :run/done (:type ev))
        {:history (:history ev) :seen acc}
        (recur (conj acc ev)))
      {:history nil :seen acc})))

(defn- drive [provider messages]
  (drain-chan (loop/run-chan provider messages)))

(defn- joined [seen type]
  (apply str (map :text (filter #(= type (:type %)) seen))))

(defn- without-audit [seen]
  "The audit events ride the same stream as the wire-relevant ones; the run's
  shape is asserted over the latter only. Five kinds are audit-only: the three
  tool-lifecycle ones and the two model-call boundaries (harness.kernel.event)."
  (remove #(contains? #{:tool/pre-execute :tool/execute :tool/post-execute
                        :model/start :model/end}
                      (:type %))
          seen))

(deftest text-only-run
  (let [{:keys [history seen]}
        (drive (fake/scripted [{:reasoning "thinking..." :content "hello world"}]) [])]
    (testing "streams reasoning then text, then ends"
      (is (= [:run/start :run/end]
             (mapv :type (without-audit
                          (remove #(#{:reasoning/delta :text/delta} (:type %)) seen)))))
      (is (= "thinking..." (joined seen :reasoning/delta)))
      (is (= "hello world" (joined seen :text/delta))))
    (testing "the assistant message is appended verbatim, reasoning_content included"
      (is (= 1 (count history)))
      (is (= "hello world" (:content (last history))))
      (is (= "thinking..." (:reasoning_content (last history)))))))

(deftest tool-turn-runs-serially-and-feeds-failures-back
  (let [{:keys [history seen]}
        (drive (fake/scripted [{:content ""
                                :tool-calls [{:id "c1" :name "no-such-tool" :arguments {}}]}
                               {:content "done"}])
               [])]
    (testing "one serial tool round, then a final answer"
      (is (= [:run/start :tool/call :tool/result :text/delta :run/end]
             (mapv :type (without-audit seen))))
      (testing "the failed call's lifecycle: pre-execute refused, no execute, post closes"
        (let [pre  (first (filter #(= :tool/pre-execute (:type %)) seen))
              post (first (filter #(= :tool/post-execute (:type %)) seen))]
          (is (= :unknown-tool (:outcome pre)))
          (is (empty? (filter #(= :tool/execute (:type %)) seen)))
          (is (= "c1" (:id post))))))
    (testing "the call is fully accumulated before it is emitted"
      (is (= "{}" (:args (first (filter #(= :tool/call (:type %)) seen))))))
    (testing "a failing tool does not end the run -- it is fed back as the result"
      (is (true? (:error (first (filter #(= :tool/result (:type %)) seen)))))
      (is (= {:role "tool" :tool_call_id "c1" :content "unknown tool: no-such-tool"}
             (second history))))
    (testing "the run continues to a final answer"
      (is (= "done" (:content (last history)))))))

(deftest multiple-tool-calls-run-concurrently
  (let [{:keys [history seen]}
        (drive (fake/scripted [{:content ""
                                :tool-calls [{:id "c1" :name "no-such-tool" :arguments {}}
                                             {:id "c2" :name "no-such-tool" :arguments {}}]}
                               {:content "done"}])
               [])]
    (testing "every call got exactly one result, in whatever order they finished"
      (is (= ["c1" "c2"] (sort (mapv :id (filter #(= :tool/result (:type %)) seen))))))
    (testing "the history answers the calls in call order, not completion order"
      (is (= ["c1" "c2"] (mapv :tool_call_id (filter #(= "tool" (:role %)) history)))))))

(deftest a-turn-of-slow-tools-finishes-in-the-max-not-the-sum
  ;; The slow tool lives on a session overlay, not the base registry: runtime
  ;; registration must never mutate the base other tests read.
  (tools/session-register! "t-slow" "slow"
                         {:description "Sleep MS then return."
                          :parameters  {:type "object"
                                        :properties {"ms" {:type "integer" :description "Millis."}}}
                          :required    [:ms]
                          :run         (fn [{:keys [ms]}] (Thread/sleep ms) "ok")})
  (let [turns [{:content ""
                :tool-calls [{:id "c1" :name "slow" :arguments {:ms 300}}
                             {:id "c2" :name "slow" :arguments {:ms 300}}]}
               {:content "done"}]
        t0    (System/nanoTime)
        {:keys [history seen]}
        (drain-chan (loop/run-chan (fake/scripted turns) [] {:thread-id "t-slow"}))
        ms    (/ (- (System/nanoTime) t0) 1e6)]
    (testing "wall clock is the slower tool, not the sum of both"
      ;; Concurrent: ~300ms plus scheduling slack. Serial would be >= 600ms.
      ;; History and events are still asserted below, so a timing flake here
      ;; cannot mask a broken run.
      (is (< ms 550)))
    (testing "both results came back clean"
      (is (= #{"c1" "c2"} (set (mapv :id (filter #(= :tool/result (:type %)) seen)))))
      (is (every? false? (map :error (filter #(= :tool/result (:type %)) seen)))))
    (testing "and the run still terminates with a well-formed history"
      (is (= ["c1" "c2"] (mapv :tool_call_id (filter #(= "tool" (:role %)) history))))
      (is (= "done" (:content (last history))))))
  (tools/session-unregister! "t-slow" "slow"))

(deftest every-model-call-is-bracketed-and-carries-the-vendors-report
  ;; The pair of audit lines the composer's status strip counts: one :model/start per
  ;; call, one :model/end per call, in that order, with the vendor's own usage on the
  ;; end. Two calls here (a tool round, then the answer), so the pairing is not
  ;; trivially one-and-done.
  (let [{:keys [seen]}
        (drive (fake/scripted [{:content ""
                                :tool-calls [{:id "c1" :name "no-such-tool" :arguments {}}]
                                :usage {:prompt_tokens 100 :completion_tokens 10
                                        :total_tokens 110
                                        :prompt_tokens_details {:cached_tokens 80}}}
                               {:content "done"
                                :usage {:prompt_tokens 120 :completion_tokens 4
                                        :total_tokens 124}}])
               [])
        calls (filter #(#{:model/start :model/end} (:type %)) seen)
        ends  (filter #(= :model/end (:type %)) seen)]
    (testing "one start and one end per call, alternating"
      (is (= [:model/start :model/end :model/start :model/end] (mapv :type calls))))
    (testing "each start names the call's identity, never its body"
      (doseq [start (filter #(= :model/start (:type %)) seen)]
        (is (not (contains? start :messages)))
        (is (not (contains? start :api-key)))
        ;; AND THE WINDOW IT RAN UNDER IS PART OF THAT IDENTITY. 128000 is what
        ;; `fake/scripted` declares (test/harness/fake.clj); what this pins is that
        ;; the resolve's own map -- not a second lookup at read time -- is what
        ;; reaches the line, which is what lets the context ring divide this call's
        ;; `usage.prompt_tokens` by the window that was in force for THIS call.
        (is (= 128000 (:context-window start)))))
    (testing "each end carries that call's OWN report -- the first call's, then the second's"
      (is (= [110 124] (mapv #(get-in % [:usage :total_tokens]) ends)))
      (is (= 80 (get-in (first ends) [:usage :prompt_tokens_details :cached_tokens]))))))

(deftest a-round-that-reports-nothing-gets-an-empty-model-end
  ;; A vendor that stays silent is not a vendor that reported zeroes, and the read
  ;; side has to be able to tell them apart.
  (let [{:keys [seen]} (drive (fake/scripted [{:content "hello"}]) [])
        end           (first (filter #(= :model/end (:type %)) seen))]
    (is (= {:type :model/end} end))))

(deftest transport-failure-ends-the-run
  (let [{:keys [seen]}
        (drive {:protocol :explodes} [])]
    (is (= [:run/start :model/start :model/end :run/error] (mapv :type seen)))
    (testing "the call that never answered still CLOSED its segment"
      ;; The distinction the record reader depends on: a segment with no end cannot be
      ;; told from one that is still running, so a call that dies must still leave a
      ;; :model/end -- with an empty payload, because nothing came back.
      (is (= {:type :model/end} (first (filter #(= :model/end (:type %)) seen)))))
    (is (string? (:message (last seen))))))

;; ------------------------------------------------------------- skill injection

(deftest a-loaded-skill-body-is-in-the-very-next-request
  ;; The point of deriving rather than remembering: a load has to be visible to
  ;; the NEXT call, because the model asked for the instructions in order to
  ;; follow them now. This asserts that through the real loop, not the function --
  ;; and it passes the step the EDGE passes (harness.cap.project/before-llm),
  ;; because the loop no longer requires one: it applies whatever it is handed.
  (let [root (str (System/getProperty "java.io.tmpdir")
                  "/harness-loop-skills-" (System/nanoTime))]
    ;; Under .agents/skills, which is the convention directory the default roots
    ;; point at -- laying it at the project root would make it a skill this
    ;; session never looks for.
    (.mkdirs (java.io.File. root ".agents/skills/alpha"))
    (spit (str root "/.agents/skills/alpha/SKILL.md")
          "---\nname: alpha\ndescription: a thing\n---\n\n# alpha\n\nALPHA BODY\n"
          :encoding "UTF-8")
    (project/bind! "t-skills" root)
    (let [{:keys [history]}
          (drain-chan (loop/run-chan (fake/scripted [{:content ""
                                                      :tool-calls [{:id "c1" :name "skill"
                                                                    :arguments {:name "alpha"}}]}
                                                     {:content "done"}])
                                     [{:role "user" :content "load alpha"}]
                                     {:thread-id "t-skills"
                                      :before-llm project/before-llm}))]
      (testing "the body is in the history, as a user message right after the tool result"
        (let [roles (mapv :role history)
              idx   (.indexOf roles "tool")]
          (is (some? idx))
          (is (str/includes? (str (:content (nth history (inc idx)))) "ALPHA BODY"))
          (is (str/starts-with? (str (:content (nth history (inc idx)))) "<skill name=")))))
    (project/bind! "t-skills" nil)))

(deftest a-run-with-no-skill-loads-is-untouched
  (let [{:keys [history]} (drive (fake/scripted [{:content "hi"}]) [{:role "user" :content "go"}])]
    (testing "nothing is spliced in when nothing was loaded"
      (is (= ["user" "assistant"] (mapv :role history))))))

(deftest a-slash-load-is-in-the-very-first-request
  ;; The human's path, and the same "now" requirement the model's has: the person
  ;; asked in the message they just sent, so the body has to be in the FIRST call
  ;; of that turn -- not after a round of tool calls, and not next turn.
  (let [root (str (System/getProperty "java.io.tmpdir")
                  "/harness-loop-slash-" (System/nanoTime))]
    (.mkdirs (java.io.File. root ".agents/skills/alpha"))
    (spit (str root "/.agents/skills/alpha/SKILL.md")
          "---\nname: alpha\ndescription: a thing\n---\n\n# alpha\n\nALPHA BODY\n"
          :encoding "UTF-8")
    (project/bind! "t-slash" root)
    (let [{:keys [history]}
          ;; A script with NO tool call: nothing here asks the model for anything,
          ;; which is the point -- the load came from the person.
          (drain-chan (loop/run-chan (fake/scripted [{:content "done"}])
                                     [{:role "user" :content "/alpha go"}]
                                     {:thread-id "t-slash"
                                      :before-llm project/before-llm}))]
      (testing "the body rides right behind the message that asked, both user-side"
        (is (= ["user" "user" "assistant"] (mapv :role history)))
        (is (= "/alpha go" (:content (first history))) "and the person's text is untouched")
        (is (str/starts-with? (str (:content (second history))) "<skill name=\"alpha\">"))
        (is (str/includes? (str (:content (second history))) "ALPHA BODY"))))
    (project/bind! "t-slash" nil)))

(deftest the-pre-llm-step-is-applied-before-every-call
  ;; THE LOOP'S HALF OF THE SKILL CONTRACT, and the half that is actually this
  ;; namespace's: it applies whatever step it was handed immediately before EACH
  ;; llm/stream!, for as many calls as the run makes, and applies nothing at all
  ;; when it was handed nothing. What the step DOES -- folding in a session's
  ;; loaded skill bodies -- is harness.cap.project/before-llm's business, and is
  ;; asserted above through this same loop.
  (let [mark (fn [history _thread-id] (conj history {:role "user" :content "STEP"}))
        {:keys [history]}
        (drain-chan (loop/run-chan (fake/scripted [{:content ""
                                                    :tool-calls [{:id "c1" :name "eval"
                                                                  :arguments {:code "40"}}]}
                                                   {:content "done"}])
                                   [{:role "user" :content "go"}]
                                   {:thread-id "t-step" :before-llm mark}))]
    (is (= 2 (count (filter #(= "STEP" (:content %)) history)))
        "two LLM calls, two applications -- a step applied once per RUN would show one"))
  (testing "and nothing handed in means nothing injected"
    (let [{:keys [history]}
          (drain-chan (loop/run-chan (fake/scripted [{:content "done"}])
                                     [{:role "user" :content "go"}]
                                     {:thread-id "t-plain"}))]
      (is (not-any? #(= "STEP" (:content %)) history))
      (is (= ["user" "assistant"] (mapv :role history))
          "the history is exactly what was passed in plus what the kernel appended"))))
