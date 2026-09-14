(ns harness.ui.approval-test
  "Pre-tool approval, end to end through the real client. From
  ui/verify-approval.mjs.

  The turn that MARKS the session comes from a script like everything else, so
  nothing here depends on a model complying -- which was verify-approval.mjs's
  known weakness (it retried each compliance-dependent turn up to three times and
  printed a `note` when the model did not oblige). Here the script asks for the
  eval call and the write calls on purpose, and every turn lands.

  What this proves that the offline suite cannot: the shipped AG-UI client really
  turns our RUN_FINISHED+outcome into a resumable interrupt, and a real `resume`
  array really drives the server's replay. Everything from the park onward is
  what this file is for."
  (:require ["@ag-ui/client" :refer [HttpAgent]]
            [cljs.test :refer [deftest is]]
            [harness.ui.e2e :as e2e])
  (:require-macros [harness.ui.test-runner :refer [deftest-index]]))

(defn- new-agent []
  (let [agent (HttpAgent. #js {:url (e2e/url)})
        events (atom [])]
    (.subscribe agent
                #js {:onRunFinishedEvent
                     (fn [^js b]
                       (swap! events conj
                              (if (= "interrupt" (some-> (.-event b) (.-outcome) (.-type)))
                                "RUN_FINISHED/INTERRUPT"
                                "RUN_FINISHED")))
                     :onRunErrorEvent
                     (fn [^js b] (swap! events conj (str "RUN_ERROR:" (.-message (.-event b)))))
                     :onToolCallStartEvent
                     (fn [^js b] (swap! events conj (str "tool:" (.-toolCallName (.-event b)))))})
    [agent events]))

(defn- pending [^js agent] (vec (array-seq (or (.-pendingInterrupts agent) #js []))))

(defn- turn! [^js agent tid prompt]
  (.addMessage agent #js {:id (str "u-" (js/Date.now) "-" (.-random js/Math))
                          :role "user" :content prompt})
  (.runAgent agent #js {:threadId tid
                        :runId (str "run-" (js/Date.now) "-" (.-random js/Math))
                        :messages (.-messages agent)
                        :tools #js [] :context #js []}))

(defn- resume!
  "Answer every parked interrupt with STATUS and PAYLOAD, exactly as the UI's
  approval card does."
  [^js agent tid status payload]
  (let [decisions (into-array (map (fn [^js i]
                                     #js {:interruptId (.-id i) :status status :payload payload})
                                   (pending agent)))]
    (.runAgent agent #js {:threadId tid
                          :runId (str "run-" (js/Date.now) "-" (.-random js/Math))
                          :messages (.-messages agent)
                          :tools #js [] :context #js []
                          :resume decisions})))

(defn- tool-result-text [^js agent call-id]
  (some (fn [^js m] (when (and (= "tool" (.-role m)) (= call-id (.-toolCallId m)))
                  (e2e/content m)))
        (array-seq (.-messages agent))))

(defn- has-tool-message? [^js agent call-id]
  (some? (tool-result-text agent call-id)))

(def ^:private marked-turn
  "Turn one asks for the eval that marks the session; turn two is the reply to
  it. Written out because the eval code has to be EXACT -- it is the same call a
  model would make, and harness.memory's session API is what it names."
  #js {:content ""
       :tool-calls #js [#js {:id "mark" :name "eval"
                             :arguments
                             #js {:code "(harness.memory/session-require-approval! harness.memory/*thread-id* \"write\")"}}]})

(defn- write-turn [call-id path]
  #js {:content ""
       :tool-calls #js [#js {:id call-id :name "write"
                             :arguments #js {:path path :content "approved"}}]})

(deftest a-parked-write-runs-only-after-approval
  (let [approved (e2e/tmp-path "harness-approval-approved.txt")
        vetoed   (e2e/tmp-path "harness-approval-vetoed.txt")
        tid (e2e/thread-id "approval")
        [^js agent events] (new-agent)]
    (e2e/async!
     (fn [_]
       (doseq [p [approved vetoed]] (e2e/rm! p))
       (e2e/script! [marked-turn
                     #js {:content "marked"}
                     (write-turn "w1" approved)
                     #js {:content "wrote it"}
                     (write-turn "w2" vetoed)
                     #js {:content "done"}])
       (turn! agent tid "mark this session as requiring approval for write"))
     ;; -- the write parks
     (fn [_]
       (is (some #(= "tool:eval" %) @events) "the session was marked through an eval call")
       (reset! events [])
       (turn! agent tid (str "write " approved)))
     (fn [_]
       (let [parked (pending agent)]
         (is (some #(= "RUN_FINISHED/INTERRUPT" %) @events)
             "the write run ended on an interrupt, not a finish")
         (is (= 1 (count parked)) "exactly one call parked")
         (is (= "tool-approval" (.-reason ^js (first parked))) "the interrupt says why it parked")
         (is (some? (.-toolCallId ^js (first parked))) "and it names the parked call")
         (is (not (e2e/file-exists? approved)) "nothing was written yet")
         (is (not (has-tool-message? agent "w1")) "and the parked call has no answer yet")
         (reset! events [])
         (resume! agent tid "resolved" #js {:decision "approved"})))
     ;; -- approved
     (fn [_]
       (is (e2e/file-exists? approved) "the approved call ran on the resume run")
       (is (has-tool-message? agent "w1") "its result came back as the call's answer")
       (is (some #(= "RUN_FINISHED" %) @events) "the resume run finished normally")
       (reset! events [])
       (turn! agent tid (str "write " vetoed)))
     ;; -- a second write, vetoed
     (fn [_]
       (is (= 1 (count (pending agent))) "a second write parks again")
       (reset! events [])
       (resume! agent tid "cancelled" #js {:reason "test: exercising the veto path"}))
     (fn [_]
       (is (not (e2e/file-exists? vetoed)) "the vetoed call never ran")
       (is (.includes (str (tool-result-text agent "w2")) "vetoed by human")
           "the model was told the call was vetoed, reason included")
       (is (some #(= "RUN_FINISHED" %) @events) "and the run carried on to a normal end")))))

(deftest a-resume-for-an-unknown-interrupt-is-refused
  ;; Guessing an approval is the worst failure mode this feature has, so an id
  ;; the process never parked is refused rather than defaulted. The refusal is a
  ;; RUN_ERROR frame -- nothing thrown locally, because the server owns the
  ;; decision.
  (let [tid (e2e/thread-id "unknown-interrupt")
        [^js agent events] (new-agent)]
    (e2e/async!
     (fn [_]
       (e2e/script! [#js {:content "no tools here"}])
       (.runAgent agent #js {:threadId tid :tools #js [] :context #js []
                             :resume #js [#js {:interruptId "never-parked"
                                               :status "resolved"}]}))
     (fn [_]
       (let [failed (some #(when (>= (.indexOf % "RUN_ERROR:") 0) %) @events)]
         (is (some? failed) "the server refused the unknown interrupt")
         (is (.includes (str failed) "unknown interrupt")
             (str "and said what was wrong: " (pr-str failed))))))))

(deftest-index)
