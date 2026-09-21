(ns scratch-multicall-frames
  "THE LOOP for the 2026-09-21 RUN_ERROR: 'this run's history leaves 4 tool calls
  unanswered'.

  Phase 1 of /diagnosing-bugs: one command, red while the bug lives, green once it is
  fixed. It asserts the ONE thing the log says was wrong -- that a conversation folded
  back from the record still answers every tool call of every assistant message --
  using the same reader the run itself uses before it calls a provider
  (harness.kernel.llm/unanswered-tool-calls).

  TWO PARTS, and the first is the user's own artifact rather than a model of it:
    A. fold the real session log, then ask the reader. The ids it names are the
       symptom in harness.infra.log, so this can only go green when the session
       itself would continue.
    B. the same failure MINIMISED to one scripted turn with two parallel calls --
       no log, no session, no disk. Every element is load-bearing: drop the second
       call and the loop goes green (the second call is what forces a new parent
       message).

  Run: clojure -M:dev -m scratch-multicall-frames [session-log]
  Exits 1 while the bug lives (that is the point), 0 once it is fixed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.ag-ui :as ag]
            [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]
            [harness.kernel.event :as ev]
            [harness.kernel.frames :as frames]
            [harness.kernel.llm :as llm]
            [harness.kernel.tools :as tools]
            [harness.test-runner :as runner]))

;; ABSOLUTELY FIRST: nothing below may resolve the real ~/.clj-harness. The session
;; log is read at its own path (the artifact this diagnosis is about), but every
;; harness resolver in this process points at a temp root.
(runner/isolate!)

;; THIS SCRIPT CANNOT READ THAT LOG ANY MORE, AND THAT IS THE INTENDED ANSWER: the file was
;; written before `.scratch/jsonl-two-kinds`, and a record of the old contract is refused BY
;; NAME (`harness.edge.replay/read-row`: "start a new conversation") rather than folded
;; quietly -- see the spec's 票 05 landing. What is below is kept as the diagnosis it was, to
;; be pointed at a log written since.
(def ^:private default-log
  (str (System/getProperty "user.home")
       "/.clj-harness/projects/_Users_zhouteng_Documents_workspace_clj-harness"
       "/2754772f-52f1-4bf2-9aea-af53e7c7eec1.jsonl"))

(defn- shape
  "One line per folded message: the role and, where it has them, its tool-call ids and
  the id it answers. This is what makes a split visible rather than inferred."
  [messages]
  (doseq [m messages
          :when (or (:tool_calls m) (= "tool" (:role m)) (= "assistant" (:role m)))]
    (println "   " (:role m)
             (when-let [cs (seq (map :id (:tool_calls m)))]
               (str "tool_calls=" (pr-str (vec cs))))
             (when (:tool_call_id m) (str "answers=" (:tool_call_id m))))))

(defn- report [label messages]
  (let [ids (vec (llm/unanswered-tool-calls messages))]
    (println)
    (println (format "%-28s %s" label (if (seq ids)
                                        (str "RED   unanswered " (pr-str ids))
                                        "green every call answered")))
    ids))

(defn- real-log [path]
  (let [f (io/file path)]
    (if-not (.exists f)
      (do (println "SKIP  no session log at" path) nil)
      (let [messages (replay/records->messages
                      (replay/lines->records (replay/read-lines f)))]
        (println "folded" (count messages) "AG-UI messages from" (.getName f))
        ;; THE SAME DROP THE SESSION VIEW MAKES: an injected-context card is a message
        ;; the screen draws and the client never sends back, so the list a run is
        ;; assembled from does not carry it (sessions/without-cards).
        (let [provider (ag/inbound (#'sessions/without-cards messages) "SYSTEM PROMPT" [])]
          (shape provider)
          (report "A. the real record" provider))))))

(defn- one-turn-with-two-calls []
  (let [emit (ag/outbound "thr-1" "run-1")
        wire (vec (mapcat emit [(ev/run-start)
                                (ev/tool-call "c1" "read" "{}")
                                (ev/tool-call "c2" "bash" "{}")
                                (ev/tool-result "c1" "one" false)
                                (ev/tool-result "c2" "two" false)
                                (ev/run-end)]))]
    (println)
    (println "minimal turn, frames:"
             (pr-str (mapv :type wire)))
    (println "parents:"
             (pr-str (mapv (juxt :toolCallId :parentMessageId)
                           (filter #(= "TOOL_CALL_START" (:type %)) wire))))
    (let [provider (ag/inbound (frames/apply-frames wire) "SYSTEM PROMPT" [])]
      (shape provider)
      (report "B. one turn, two calls" provider))))

(defn- run-id-by-prefix [records prefix]
  (some #(when (str/starts-with? (str (:runId %)) prefix) (:runId %)) records))

(defn- submitted-messages
  "The `message` records a run SUBMITTED -- what the kernel was handed, logged before
  any of the run's frames -- read out of the log in file order. The returned tail is
  written after the terminal, so the split is the first terminal line of that run."
  [records run-id]
  (let [terminal (first (keep-indexed
                         (fn [i r]
                           (when (and (= run-id (:runId r))
                                      (= "event" (:kind r))
                                      (frames/terminal? (:payload r)))
                             i))
                         records))]
    (->> records
         (take (or terminal (count records)))
         (filter #(and (= run-id (:runId %)) (= "message" (:kind %))))
         (mapv :payload))))

(defn- replayed-result
  "The tool message `loop/replay!` appends for a decided call: role tool, keyed by the
  parked record's tool-call id. Content does not matter to the reader."
  [call-id]
  {:role "tool" :tool_call_id call-id :content "(the replayed call's result)"})

(defn- second-finding
  "PART C: does the resume path's OWN result ever land behind the call it answers?

  The live run is handed: the conversation (ending in the parked assistant message),
  then the session's opening blocks at the very end (ag-ui/tail-blocks), and only THEN
  does drive! append the replayed tool message. So the answer is glued on after the
  blocks. This prints what the run's own reader makes of that -- and, for contrast,
  what it makes of the same list with the result moved up behind the call."
  [path]
  (let [records (replay/lines->records (replay/read-lines (io/file path)))
        run-id  (run-id-by-prefix records "2375c98b")
        handed  (submitted-messages records run-id)
        call-id "call_00_ET_cGSmMaGvZxslMhi8R3TN8683"
        parked  (vec (concat handed [(replayed-result call-id)]))]
    (println)
    (println "=== C. the resume's own result, where drive! puts it =======================")
    (println "assistant[called] is at index"
             (first (keep-indexed (fn [i m] (when (some #(= call-id (:id %)) (:tool_calls m)) i))
                                  handed))
             "of" (count handed) "-- the handed-in list")
    (println "its tool message lands at" (dec (count parked)) "(appended last)")
    (println "   as handed + replay  ->"
             (pr-str (vec (llm/unanswered-tool-calls parked))))
    ;; THE SAME LIST, with the result spliced directly behind the call it answers.
    (let [i        (first (keep-indexed (fn [idx m]
                                          (when (some #(= call-id (:id %)) (:tool_calls m)) idx))
                                        handed))
          moved    (vec (concat (subvec handed 0 (inc i))
                                [(replayed-result call-id)]
                                (subvec handed (inc i))))]
      (println "   result behind the call ->"
               (pr-str (vec (llm/unanswered-tool-calls moved)))))
    ;; ...AND WHAT THE RUN DOES WITH THAT, replayed with the kernel's own functions.
    ;; `still` is the half that decides the shape of the ending: a call with a park
    ;; record is asked about AGAIN (never refused), and the record survives its own
    ;; verdict -- take-decision! marks it consumed, parked-interrupts never looks.
    ;; So the answer the run just collected is invisible AND the question comes back.
    (let [thread-id "the-thread"
          interrupt "the-interrupt"]
      (tools/park-approval! interrupt {:thread-id thread-id :tool-call-id call-id
                                       :name "read" :args "{}" :reason :tool-declares})
      (tools/decide-approval! interrupt :approved {})
      (let [consumed (tools/take-decision! interrupt)
            stalled  (vec (llm/unanswered-tool-calls parked))
            still    (tools/parked-interrupts thread-id stalled)
            dead     (vec (remove (set (map :id still)) stalled))]
        (println)
        (println "drive!'s decision, with the real functions:")
        (println "   the replay took the verdict      ->" (pr-str consumed))
        (println "   stalled (what the vendor would see) ->" (pr-str stalled))
        (println "   still   (the run ASKS AGAIN)       ->" (pr-str (mapv :id still)))
        (println "   dead    (the run is refused)       ->" (pr-str dead))))))

(defn -main [& [path]]
  (println "=== A. the archived incident, folded back ==================================")
  (println "    (the record was written BEFORE the fix, so its frames stay as they are:")
  (println "     this part re-enacts the refusal, it is not the pass/fail signal.)")
  (let [real (real-log (or path (System/getenv "SESSION_LOG") default-log))
        mini (do (println)
                 (println "=== B. the same failure, minimised ========================================")
                 (one-turn-with-two-calls))]
    (when (.exists (io/file (or path (System/getenv "SESSION_LOG") default-log)))
      (second-finding (or path (System/getenv "SESSION_LOG") default-log)))
    (println)
    (println "  A answered:" (if (seq real) "no (archived, pre-fix record)" "yes")
             "-- B answered:" (if (seq mini) "no" "yes"))
    (println)
    (if (seq mini)
      (do (println "VERDICT red: the writer still splits a turn's parallel calls")
          (System/exit 1))
      (do (println "VERDICT green: one turn's parallel calls fold back to one assistant")
          (println "               message, and every call is answered")
          (System/exit 0)))))
