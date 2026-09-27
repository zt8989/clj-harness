(ns harness.kernel.loop-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.fake :as fake]
            [harness.cap.jobs :as jobs]
            [harness.kernel.llm :as llm]
            [harness.kernel.event :as ev]
            [harness.kernel.loop :as loop]
            [harness.cap.project :as project]
            [harness.kernel.tools :as tools]
            [harness.test-support :as support]))

(use-fixtures :once support/with-builtins)

(defn- drain-chan [ch]
  (loop [acc []]
    (if-let [ev (async/<!! ch)]
      (if (= :run/done (:type ev))
        {:history  (:history ev)
         :added    (:added ev)
         :unplaced (:unplaced ev)
         :seen     acc}
        (recur (conj acc ev)))
      {:history nil :added nil :unplaced nil :seen acc})))

(defn- drive
  ([provider messages] (drive provider messages nil))
  ([provider messages opts] (drain-chan (loop/run-chan provider messages opts))))

(defn- interrupt-id
  "The interrupt id of a park run's terminal event."
  [park-run]
  (:id (first (:interrupts (last (:seen park-run))))))

(defn- call-index
  "Where the assistant message that named CALL-ID sits in HISTORY, or nil."
  [history call-id]
  (first (keep-indexed (fn [i message]
                         (when (some #(= call-id (:id %)) (:tool_calls message)) i))
                       history)))

(defn- parked-write
  "A run that parks one `write` call, for THREAD-ID, and answers the interrupt id."
  [thread-id path]
  (let [_    (tools/session-require-approval! thread-id "write")
        park (drive (fake/scripted [{:content ""
                                     :tool-calls [{:id "c1" :name "write"
                                                   :arguments {:path path :content "placed!"}}]}])
                    []
                    {:thread-id thread-id})]
    [park (interrupt-id park)]))

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
  ;;
  ;; THE OVERLAP IS WITNESSED, NOT TIMED, and that is the difference between a case
  ;; about the loop and a case about the machine. A wall-clock ceiling was the obvious
  ;; spelling -- "concurrent is ~300ms, serial is >=600" -- and it leaves only the
  ;; slack for JIT, the thread pool and the drain loop: a loaded Windows machine spent
  ;; 656ms on one run, which is inside the band where concurrent-with-overhead and
  ;; serial-with-less-overhead are indistinguishable. So each call records when it
  ;; entered and when it left, and the claim asserted is the one that MAKES the wall
  ;; clock the slower tool: the two windows overlap. Serial execution cannot satisfy
  ;; it, however fast the machine (test_support's own note -- a race case that does
  ;; not witness the interleaving passes while the bug is present).
  (let [windows (atom [])]
    (tools/session-register! "t-slow" "slow"
                             {:description "Sleep MS then return."
                              :parameters  {:type "object"
                                            :properties {"ms" {:type "integer" :description "Millis."}}}
                              :required    [:ms]
                              :run         (fn [{:keys [ms]}]
                                             (let [entered (System/nanoTime)]
                                               (Thread/sleep ms)
                                               (let [left (System/nanoTime)]
                                                 (swap! windows conj [entered left])
                                                 "ok")))})
    (try
      (let [turns [{:content ""
                    :tool-calls [{:id "c1" :name "slow" :arguments {:ms 300}}
                                 {:id "c2" :name "slow" :arguments {:ms 300}}]}
                   {:content "done"}]
            {:keys [history seen]}
            (drain-chan (loop/run-chan (fake/scripted turns) [] {:thread-id "t-slow"}))
            calls  (vec (sort-by first @windows))]
        (testing "wall clock is the slower tool, not the sum of both"
          (is (= 2 (count calls)) "both calls ran")
          (when (= 2 (count calls))
            (let [[first-entered first-left] (first calls)
                  [second-entered _]        (second calls)]
              (is (< second-entered first-left)
                  (str "the second call started " (/ (- second-entered first-left) 1e6)
                       "ms before the first finished: the turn cost the slower tool,"
                       " not the sum")))))
        (testing "both results came back clean"
          (is (= #{"c1" "c2"} (set (mapv :id (filter #(= :tool/result (:type %)) seen)))))
          (is (every? false? (map :error (filter #(= :tool/result (:type %)) seen)))))
        (testing "and the run still terminates with a well-formed history"
          (is (= ["c1" "c2"] (mapv :tool_call_id (filter #(= "tool" (:role %)) history))))
          (is (= "done" (:content (last history))))))
      (finally (tools/session-unregister! "t-slow" "slow")))))

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

(deftest a-loaded-skill-is-in-the-request-as-the-result-itself
  ;; THE MODEL'S PATH, THROUGH THE REAL LOOP. It used to be a derived body spliced
  ;; beside the tool result; the body IS the result now, so the run that answers the
  ;; call needs no derivation at all -- and this asserts both halves of that: the
  ;; bytes are in the conversation the NEXT call carries, and nothing was injected to
  ;; put them there. (A `/name` is the other half, asserted just below.)
  (let [root (support/temp-dir "loop-skills")]
    ;; Under .agents/skills, which is the convention directory the default roots
    ;; point at -- laying it at the project root would make it a skill this
    ;; session never looks for.
    (.mkdirs (java.io.File. root ".agents/skills/alpha"))
    (spit (str root "/.agents/skills/alpha/SKILL.md")
          "---\nname: alpha\ndescription: a thing\n---\n\n# alpha\n\nALPHA BODY\n"
          :encoding "UTF-8")
    (project/bind! "t-skills" root)
    (let [{:keys [history seen]}
          (drain-chan (loop/run-chan (fake/scripted [{:content ""
                                                      :tool-calls [{:id "c1" :name "skill"
                                                                    :arguments {:name "alpha"}}]}
                                                     {:content "done"}])
                                     [{:role "user" :content "load alpha"}]
                                     {:thread-id "t-skills"
                                      :before-llm project/before-llm}))]
      (testing "the body is in the history, as the tool result"
        (let [results (filter #(= "tool" (:role %)) history)]
          (is (some #(str/includes? (str (:content %)) "ALPHA BODY") results))
          (is (some #(str/includes? (str (:content %)) "is the skill's directory") results))))

      (testing "and nothing was injected to put it there"
        (is (not-any? #(= :context/injected (:type %)) seen)
            (str "saw " (pr-str (mapv :type seen))))
        (is (not-any? #(str/starts-with? (str (:content %)) "<skill name=") history))))
    (project/bind! "t-skills" nil)))

(deftest a-run-with-no-skill-loads-is-untouched
  (let [{:keys [history]} (drive (fake/scripted [{:content "hi"}]) [{:role "user" :content "go"}])]
    (testing "nothing is spliced in when nothing was loaded"
      (is (= ["user" "assistant"] (mapv :role history))))))

(deftest a-slash-load-is-in-the-very-first-request
  ;; The human's path, and the same "now" requirement the model's has: the person
  ;; asked in the message they just sent, so the body has to be in the FIRST call
  ;; of that turn -- not after a round of tool calls, and not next turn.
  (let [root (support/temp-dir "loop-slash")]
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

(deftest a-job-that-ends-mid-run-is-in-front-of-the-model-at-the-next-call
  ;; THE FEATURE, seen from the loop: a background job is started, the model goes off to
  ;; do something else, the job finishes while it is busy -- and the ending is in the
  ;; history of the NEXT call, not the one already in flight and not "next turn".
  ;;
  ;; WHAT EACH CALL WAS SENT is recorded by wrapping the seam the loop calls, because
  ;; the interesting half of the claim is the NEGATIVE one: the first call went out
  ;; without the notice, since the job had not finished yet.
  (let [t "t-notice"
        {:keys [path]} (jobs/start! t {:command "sleep 1; echo JOB-SAYS-DONE; exit 0"})
        orig llm/stream!
        sent (atom [])]
    (try
      (with-redefs [llm/stream! (fn [provider messages emit thread-id]
                                  (swap! sent conj messages)
                                  (orig provider messages emit thread-id))]
        (let [{:keys [history]}
              (drain-chan (loop/run-chan (fake/scripted [{:content ""
                                                          :tool-calls [{:id "c1" :name "bash"
                                                                        :arguments {:command "sleep 2"}}]}
                                                         {:content "done"}])
                                         [{:role "user" :content "off you go"}]
                                         {:thread-id t :before-llm project/before-llm}))]
          (testing "the first call went out without it"
            (is (not (str/includes? (str (first @sent)) "job-ended"))
                "the job was still running when that call was made"))
          (testing "and the second one had it, without anybody asking"
            ;; THE NOTICE CARRIES THE JOB'S ID, THE COMMAND IT RAN, HOW IT WENT, AND THE LINE
            ;; THAT READS IT -- what the command SAID is not in it (`job_output`, or the
            ;; file, is a call away), and neither is the record's path (the `job` answer
            ;; had it), so the output line is deliberately NOT one of the markers here.
            (is (str/includes? (str (second @sent)) "job-ended"))
            (is (str/includes? (str (second @sent)) (str "[exit 0]"))))
          (testing "exactly once in the history the run ends with"
            (is (= 1 (count (filter #(str/includes? (str (:content %)) "job-ended")
                                    history)))))
          (testing "and there is nothing left to say"
            (is (= [] (jobs/take-notices! t))))))
      (finally (jobs/shutdown!)))))

(deftest what-the-step-injects-is-said-out-loud
  ;; THE LOOP'S OTHER HALF OF THE SAME CONTRACT: whatever the step adds is emitted as
  ;; :context/injected, ONCE per message, in the order it was added. The edge turns
  ;; that into the CUSTOM frame a person sees in the conversation; here it is asserted
  ;; where it is made.
  (let [inject (fn [history _thread-id]
                 (conj history {:role "user" :content "<skill name=\"tdd\">red green</skill>"}))
        {:keys [seen]} (drain-chan (loop/run-chan (fake/scripted [{:content ""
                                                                  :tool-calls [{:id "c1" :name "eval"
                                                                                :arguments {:code "40"}}]}
                                                                 {:content "done"}])
                                                [{:role "user" :content "go"}]
                                                {:thread-id "t-inject" :before-llm inject}))
        injected (filter #(= :context/injected (:type %)) seen)]
    (testing "one event per injected message, per call"
      (is (= 2 (count injected))
          "two calls, and the step adds the same message to each -- each addition is said")
      (is (every? #(str/includes? (:text %) "red green") injected))
      (is (every? #(= "user" (:role %)) injected)
          "an injection is a user message; the frame says which role it took"))
    (testing "and a run nobody injects into says nothing"
      (let [{:keys [seen]} (drive (fake/scripted [{:content "hi"}]) [{:role "user" :content "go"}])]
        (is (empty? (filter #(= :context/injected (:type %)) seen)))))))

(deftest the-pre-llm-step-is-applied-before-every-call
  ;; THE LOOP'S HALF OF THE SKILL CONTRACT, and the half that is actually this
  ;; namespace's: it applies whatever step it was handed immediately before EACH
  ;; llm/stream!, for as many calls as the run makes, and applies nothing at all
  ;; when it was handed nothing. What the step DOES -- folding in the body a
  ;; `/name` asked for -- is harness.cap.project/before-llm's business, and is
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

(deftest a-replayed-answer-lands-behind-the-call-that-asked-for-it
  ;; TICKET 02 of `.scratch/session-opening`. The edge hands a run the history with its
  ;; pre-LLM step ALREADY APPLIED, so a skill body or a job's ending can be sitting
  ;; behind the parked assistant message. Answering at the end of the list leaves the
  ;; call unanswered by the vendor's adjacency rule (`llm/unanswered-tool-calls`): the
  ;; run asks the same question over again, and approving it a second time runs the tool
  ;; a second time.
  (let [thr  "thr-replay-behind"
        path (str (support/temp-dir "loop-replay-behind") "/placed.txt")
        [park iid] (parked-write thr path)
        ;; WHAT THE EDGE HANDS OVER: the parked turn, and behind it the injection the
        ;; session's pre-LLM step derived when the run was set up.
        handed (conj (:history park) {:role "user"
                                      :content "<skill name=\"alpha\">\nBODY\n</skill>"})
        {:keys [seen history added unplaced]}
        (drive (fake/scripted [{:content "finished"}])
               handed
               {:thread-id thr :resume [{:interrupt-id iid :verdict :approved}]})]

    (testing "the approved call runs"
      (is (= "placed!" (slurp path :encoding "UTF-8"))))

    (testing "its answer sits DIRECTLY behind the assistant message that named it"
      (let [i (call-index history "c1")]
        (is (some? i))
        (is (= ["tool" "c1"] [(:role (nth history (inc i)))
                              (:tool_call_id (nth history (inc i)))]))
        (is (= "user" (:role (nth history (+ i 2))))
            "and the injection it was behind stays behind both")))

    (testing "so the run reaches the provider instead of asking the same question again"
      (is (= :run/end (:type (last seen))))
      (is (= "finished" (:content (last history)))))

    (testing "and the run says what it ADDED, which is not the history's tail"
      ;; The whole reason :run/done carries this: with the answer inserted behind its
      ;; call, the slice past the messages this run was HANDED holds one it was GIVEN
      ;; (the injection) and misses the one it added (the answer) -- so a reader that
      ;; counted would file the injection as the kernel's own and lose the answer.
      (is (= ["tool" "assistant"] (mapv :role added)))
      (is (= ["user" "assistant"] (mapv :role (subvec history (count handed))))
          "what counting past what was handed in would have called the kernel's own")
      (is (empty? unplaced)))))

(deftest a-replayed-answer-with-no-call-to-sit-behind-goes-to-the-end-and-says-so
  ;; The other half of the same judgement: a history that does not carry the assistant
  ;; message at all (a client whose window was rebuilt without it) has nowhere to put the
  ;; answer. It goes to the end -- findable, and still not an adjacency this process
  ;; invented -- and the run reports it, so the edge can write a line about it.
  (let [thr  "thr-replay-nowhere"
        path (str (support/temp-dir "loop-replay-nowhere") "/placed.txt")
        [park iid] (parked-write thr path)
        orphaned (vec (remove #(= "assistant" (:role %)) (:history park)))
        {:keys [seen history added unplaced]}
        (drive (fake/scripted [{:content "finished"}])
               orphaned
               {:thread-id thr :resume [{:interrupt-id iid :verdict :approved}]})]

    (testing "the answer goes to the end, and nothing is guessed about where it belongs"
      (is (= ["tool" "assistant"] (mapv :role history)))
      (is (= "placed!" (slurp path :encoding "UTF-8"))))

    (testing "and the run names the call it could not place"
      (is (= ["c1"] unplaced))
      (is (= ["tool" "assistant"] (mapv :role added))))

    (testing "the run still finishes -- nothing about it is unanswered"
      (is (= :run/end (:type (last seen)))))))

;; ------------------------------------------------ overflow recovery (ticket 05)

(defn- refusing
  "A scripted provider whose FIRST call the vendor refuses for LENGTH, then TURNS."
  [turns]
  (fake/scripted (into [{:refuse {:status 400
                                  :body "This model's maximum context length is 128000 tokens."}}]
                       turns)))

(deftest an-overflow-refusal-recovers-in-the-same-turn-and-retries
  (let [sent      (atom [])
        on-overflow (fn [history _t]
                      (swap! sent conj history)
                      [{:role "user" :content "compacted"}])
        {:keys [history seen]} (drive (refusing [{:content "answered after recovery"}])
                                      [{:role "user" :content "u1"} {:role "user" :content "u2"}]
                                      {:on-overflow on-overflow :overflow-retries 1})]
    (testing "the run finishes, in ONE run, on the retry's answer"
      (is (= :run/end (:type (last seen))))
      (is (= "answered after recovery" (:content (last history)))))
    (testing "the recovery saw the history that was actually sent"
      (is (= [{:role "user" :content "u1"} {:role "user" :content "u2"}] (first @sent))))
    (testing "and the history the run ended with is the SHORTER one"
      (is (= [{:role "user" :content "compacted"}
              {:role "assistant" :content "answered after recovery"}]
             history)))))

(deftest a-recovery-that-shrinks-nothing-hands-the-refusal-out-untouched
  (let [{:keys [seen]} (drive (refusing [])
                              [{:role "user" :content "u1"}]
                              {:on-overflow (fn [_ _] nil) :overflow-retries 1})
        terminal (last seen)]
    (is (= :run/error (:type terminal)))
    (is (str/includes? (:message terminal) "maximum context length")
        "the vendor's own words, not a recovery's")))

(deftest the-retry-ceiling-of-zero-disables-the-recovery
  (let [called (atom 0)
        {:keys [seen]} (drive (refusing [])
                              [{:role "user" :content "u1"}]
                              {:on-overflow (fn [_ _] (swap! called inc) [{:role "user" :content "x"}])
                               :overflow-retries 0})]
    (is (= 0 @called) "0 means no recovered call at all")
    (is (= :run/error (:type (last seen))))))

(deftest a-400-that-is-not-about-length-is-not-recovered
  (let [called (atom 0)
        provider (fake/scripted [{:refuse {:status 400 :body "unknown parameter: max_tokens"}}])
        {:keys [seen]} (drive provider
                              [{:role "user" :content "u1"}]
                              {:on-overflow (fn [_ _] (swap! called inc) [{:role "user" :content "x"}])
                               :overflow-retries 1})]
    (is (= 0 @called) "an unrelated refusal is not the one recovery is for")
    (is (= :run/error (:type (last seen))))))

;; ----------------------------- the question asked BEFORE every call (ticket 04)

(defn- short-view
  "A seam that answers a two-message view the FIRST time it is asked, and nil after -- a
  compaction with nothing left to relieve."
  [asked]
  (fn [history]
    (swap! asked conj history)
    (when (< 2 (count history))
      [{:role "system" :content "s"}
       {:role "assistant" :content "SUMMARY"}])))

(deftest a-call-the-pressure-seam-shortened-is-the-one-that-goes-out
  ;; `:on-overflow` is asked AFTER the vendor refused. `:on-pressure` is the same relief
  ;; asked BEFORE the request exists -- which is the whole point of it
  ;; (`.scratch/compaction-shape` ticket 04).
  (let [asked (atom [])
        start [{:role "system" :content "s"}
               {:role "user" :content "u1"}
               {:role "user" :content "u2"}]
        {:keys [history]} (drive (fake/scripted [{:content "done"}])
                                 start
                                 {:on-pressure (short-view asked)})]
    (testing "the seam saw the array that was about to go out"
      (is (= [start] @asked)))
    (testing "and the run went on with the shorter one -- plus what the call returned"
      (is (= [{:role "system" :content "s"}
              {:role "assistant" :content "SUMMARY"}
              {:role "assistant" :content "done"}]
             history)))))

(deftest a-pressure-answer-that-is-not-shorter-is-ignored
  ;; Shorter is the edge's measurement -- only it has a token estimator. The kernel refuses
  ;; to swap an array for itself (or for a bigger one), which is what an edge that answered
  ;; about some other array would look like from here.
  (let [called (atom 0)
        {:keys [history]} (drive (fake/scripted [{:content "done"}])
                                 [{:role "user" :content "u1"}]
                                 {:on-pressure (fn [h] (swap! called inc) h)})]
    (is (= 1 @called))
    (is (= [{:role "user" :content "u1"}
            {:role "assistant" :content "done"}]
           history))))

(deftest a-pressure-answer-that-would-leave-a-call-unanswered-is-refused
  ;; A view rebuilt from the record can be a beat behind the turn in flight, and a history
  ;; whose `tool_calls` have no results is refused by every OpenAI-shaped vendor. The kernel
  ;; would rather send the big request than one it knows the vendor will reject.
  (let [provider (fake/scripted [{:content ""
                                  :tool-calls [{:id "c1" :name "read" :arguments {:path "deps.edn"}}]}
                                 {:content "after the tool"}])
        broken   [{:role "assistant" :content ""
                   :tool_calls [{:id "c9" :type "function"
                                 :function {:name "read" :arguments "{}"}}]}]
        {:keys [history]} (drive provider
                                 [{:role "user" :content "u1"}]
                                 {:on-pressure (fn [_h] broken)})]
    (is (= "after the tool" (:content (last history)))
        "the run carried on with its own array, tool result and all")
    (is (some #(= "tool" (:role %)) history)
        "and the call the broken view would have cut off was answered")))

;; ------------------------------------------------------------- the idle guard
;;
;; THE LOOP'S HALF OF `.scratch/llm-idle-timeout`: a model call that goes QUIET is given up
;; on, and -- when it had said nothing at all -- tried again. The provider layer's own
;; disconnection is asserted in `harness.kernel.llm-test` against a real socket; what is
;; pinned HERE is the decision: which timeouts are retried, how many times, what the wire
;; is told, and that the abandoned attempt writes no frames of its own.

(defn- timeout! [] (ex-info "the model produced no data for 500 ms, so the call was cut off"
                            {:llm/idle-timeout true :idle-ms 500}))

(defn- timeouts [seen] (filter #(= :model/timeout (:type %)) seen))

(defn- ends [seen] (filter #(= :model/end (:type %)) seen))

(deftest an-idle-timeout-with-nothing-emitted-is-retried
  ;; THE ORDINARY RECOVERY, and the shape of the record it leaves: a start and an end per
  ;; ATTEMPT. The first attempt's end came from the CALL itself (the provider layer threw,
  ;; so `model-call!` closed its own segment); the second's is the successful one. Nothing
  ;; is left unbalanced -- a reader pairing the nth start with the nth end reads two calls,
  ;; which is what happened.
  (let [calls (atom 0)]
    (with-redefs [llm/stream! (fn [_provider _messages _emit _thread-id]
                                (if (= 1 (swap! calls inc))
                                  (throw (timeout!))
                                  {:message {:role "assistant" :content "hello"}
                                   :telemetry {}}))]
      (let [{:keys [history seen]} (drive {} [] {:thread-id "t-idle-retry"
                                                 :idle-timeout-ms 500
                                                 :idle-timeout-retries 3})]
        (is (= "hello" (:content (last history))) "the retry's answer is the run's answer")
        (is (= 2 @calls))
        (testing "the call's own segments are closed, in order, one pair per attempt"
          (is (= [:run/start :model/start :model/end :model/timeout
                  :model/start :model/end :run/end]
                 (mapv :type seen))))
        (testing "and the frame says what a person needs: which try it was, and that another follows"
          (is (= [{:type :model/timeout :idle-ms 500 :attempt 1 :limit 3
                   :retrying true :emitted false}]
                 (vec (timeouts seen)))))))))

(deftest an-idle-timeout-spends-the-budget-and-then-ends-the-run
  ;; THREE RETRIES IS FOUR ATTEMPTS, and the fourth one's failure is the run's: the frame
  ;; announces it with `:retrying false` and the terminal frame follows, so the client is
  ;; never left waiting for a fifth call nobody is going to make.
  (let [calls (atom 0)]
    (with-redefs [llm/stream! (fn [_provider _messages _emit _thread-id]
                                (swap! calls inc)
                                (throw (timeout!)))]
      (let [{:keys [seen]} (drive {} [] {:thread-id "t-idle-give-up"
                                         :idle-timeout-ms 500
                                         :idle-timeout-retries 3})
            ts (vec (timeouts seen))]
        (is (= 4 @calls) "the first attempt, then the three retries")
        (is (= [1 2 3 4] (mapv :attempt ts)))
        (is (= [true true true false] (mapv :retrying ts)))
        (is (= [3 3 3 3] (mapv :limit ts)))
        (is (= 4 (count (ends seen))) "every attempt's segment is closed")
        (testing "the run ends on the same failure, named"
          (is (= :run/error (:type (last seen))))
          (is (str/includes? (:message (last seen)) "500 ms"))
          (is (str/includes? (:message (last seen)) "4 attempts")))))))

(deftest a-timeout-after-the-answer-had-begun-is-not-retried
  ;; THE HALF-WRITTEN ANSWER DECIDES IT. The client has been shown part of a message, and a
  ;; second attempt would append to it -- two answers where the model gave one. So the run
  ;; ends, and the frame says WHICH of the two endings this was rather than leaving a person
  ;; to wonder why the retries did not happen.
  (let [calls (atom 0)]
    (with-redefs [llm/stream! (fn [_provider _messages emit _thread-id]
                                (swap! calls inc)
                                (emit (ev/text-delta "half"))
                                (throw (timeout!)))]
      (let [{:keys [seen]} (drive {} [] {:thread-id "t-idle-partial"
                                         :idle-timeout-ms 500
                                         :idle-timeout-retries 3})]
        (is (= 1 @calls) "no second attempt")
        (is (= [{:type :model/timeout :idle-ms 500 :attempt 1 :limit 3
                 :retrying false :emitted true}]
               (vec (timeouts seen))))
        (is (= :run/error (:type (last seen))))
        (is (str/includes? (:message (last seen)) "half-written"))
        (is (= 1 (count (ends seen))))))))

(deftest a-call-that-says-nothing-at-all-is-cut-off-by-the-deadline
  ;; THE OTHER HALF OF THE GUARD, and the one no provider layer can do: the call never
  ;; throws and never reports -- it simply says nothing (a scripted provider mid-step).
  ;; The deadline wins the wait, the attempt is ABANDONED, and what it says later is
  ;; dropped rather than written into a run that has moved on.
  (let [calls (atom 0)
        released (promise)]
    (with-redefs [llm/stream! (fn [_provider _messages _emit _thread-id]
                                (if (= 1 (swap! calls inc))
                                  (do @released
                                      ;; A LATE ANSWER, and it is a perfectly good one: the
                                      ;; point is that nobody is listening for it any more.
                                      {:message {:role "assistant" :content "too late"}
                                       :telemetry {}})
                                  {:message {:role "assistant" :content "second"}
                                   :telemetry {}}))]
      (let [{:keys [history seen]} (drive {} [] {:thread-id "t-idle-silent"
                                                 ;; THE PRODUCTION DEADLINE, so the test is
                                                 ;; not racing a thread pool with a number
                                                 ;; nobody ships.
                                                 :idle-timeout-ms 500
                                                 :idle-timeout-retries 3})]
        (is (= "second" (:content (last history))))
        (is (= 1 (count (timeouts seen))) "the deadline fired for a call that never threw")
        (is (= 2 @calls))
        ;; THE ABANDONED ATTEMPT'S OWN END, written by the loop because that call's thread
        ;; is still running: one per attempt, in order, pairing with its start.
        (is (= 2 (count (ends seen))))
        (deliver released nil) ;; let the abandoned call finish, so the test leaves nothing behind
        (is (= 2 @calls) "and its late frames changed nothing")))))

(deftest a-run-handed-no-knobs-is-not-guarded
  ;; 'NOBODY SAID' IS NOT 'ZERO MILLISECONDS': a run handed no idle knobs has no retry
  ;; budget, and the provider's own failure is what ends it. The frame is still emitted --
  ;; a timeout with no retry is exactly what a person needs told -- and it carries the
  ;; deadline it was given, which is none.
  (let [calls (atom 0)]
    (with-redefs [llm/stream! (fn [_provider _messages _emit _thread-id]
                                (swap! calls inc)
                                (throw (timeout!)))]
      (let [{:keys [seen]} (drive {} [] {:thread-id "t-idle-off"})]
        (is (= 1 @calls))
        (is (= [false] (mapv :retrying (timeouts seen))))
        (is (= :run/error (:type (last seen))))
        (is (str/includes? (:message (last seen)) "0 retries"))))))

(deftest the-deadline-does-not-count-what-happens-before-the-call
  ;; ARMING, and it is the difference between watching a vendor and watching the harness.
  ;; `model-call!` resolves the session's TOOL TABLE before it emits `:model/start`, and
  ;; resolving that table is what STARTS THE SESSION'S MCP SERVERS: a server whose command
  ;; does not exist takes as long as the OS needs to say so, which is over half a second
  ;; and has nothing to do with the vendor. Measured on the real thing -- a run whose broken
  ;; MCP server took ~600 ms to fail ended on 'the model produced no data for 500 ms'
  ;; before a single byte was sent (`mcp-wired-test/a-server-that-will-not-start-...`).
  ;;
  ;; SO THE CLOCK IS ARMED BY THE CALL AND NOT BY THE ATTEMPT: here the setup takes three
  ;; times the deadline and the call answers immediately -- one attempt, no timeout frame.
  (let [calls (atom 0)]
    (with-redefs [tools/specs (fn [_thread-id] (Thread/sleep 300) [])
                  llm/stream! (fn [_provider _messages _emit _thread-id]
                                (swap! calls inc)
                                {:message {:role "assistant" :content "answered"}
                                 :telemetry {}})]
      (let [{:keys [history seen]} (drive {} [] {:thread-id "t-idle-setup"
                                                 :idle-timeout-ms 100
                                                 :idle-timeout-retries 3})]
        (is (= "answered" (:content (last history))))
        (is (= 1 @calls) "the setup window cost no attempt")
        (is (empty? (timeouts seen)))
        (is (= [:run/start :model/start :model/end :run/end] (mapv :type seen)))))))
