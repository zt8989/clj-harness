(ns scratch-repair-one-call
  "Diagnose the 2026-09-23 RUN_ERROR on thread 412afbd3: 'this run's history leaves
  1 tool call unanswered (call_b423964382994a3aa4977175)'.

  The record already carries the closing repair (a TOOL_CALL_RESULT appended by
  close-off-open-run!), so the question is whether the session-birth reader
  (`replay/sofar` -> `fold-frames`) and the rebuild reader (`replay/records->messages`)
  both fold that repair into an ANSWERED turn, or whether the call survives the fold.

  Run: clojure -M:dev -m scratch-repair-one-call [session-log]"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.ag-ui :as ag]
            [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]
            [harness.kernel.llm :as llm]
            [harness.test-runner :as runner]))

(runner/isolate!)

(def call-id "call_b423964382994a3aa4977175")

(def default-log
  (str (System/getProperty "user.home")
       "/.clj-harness/projects/_Users_zhouteng_Documents_workspace_clj-harness"
       "/412afbd3-60af-490d-b486-8ca31d9a3e64.jsonl"))

(defn- shape [messages]
  (doseq [m messages
          :when (or (:tool_calls m) (= "tool" (:role m)))]
    (when (or (:tool_calls m) (= call-id (:tool_call_id m)))
      (println "   " (format "[%d]" (.indexOf messages m)) (:role m)
               (when-let [cs (seq (map :id (:tool_calls m)))]
                 (str "tool_calls=" (pr-str (vec cs))))
               (when (:tool_call_id m) (str "answers=" (:tool_call_id m)))))))

(defn- report [label messages]
  (let [ids (vec (llm/unanswered-tool-calls messages))]
    (println)
    (println (format "%-34s %s" label
                     (if (seq ids)
                       (str "RED   unanswered " (pr-str ids))
                       "green every call answered")))
    ids))

(defn- provider-of [messages]
  (ag/inbound (sessions/model-view messages) "SYSTEM PROMPT" []))

(defn -main [& [path]]
  (let [path (or path (System/getenv "SESSION_LOG") default-log)
        f    (io/file path)]
    (when-not (.exists f)
      (println "SKIP no log at" path)
      (System/exit 2))
    (let [records (replay/lines->records (replay/read-lines f))]
      (println "records:" (count records))
      (println "open run:" (pr-str (replay/open-run records)))
      (println)
      (println "=== rebuild reader (records->messages / ensure-complete!) ===")
      (let [msgs (try (replay/records->messages records)
                      (catch Throwable t (println "  REFUSED:" (ex-message t)) nil))]
        (when msgs
          (println "folded" (count msgs) "messages")
          (report "A. records->messages" (provider-of msgs))))
      (println)
      (println "=== session-birth reader (sofar) ===")
      (let [{:keys [entries state]} (replay/sofar f)
            msgs (mapv :message entries)]
        (println "entries" (count msgs) "state" (pr-str state))
        (let [provider (provider-of msgs)]
          (println)
          (println "=== neighbourhood of the dead call, in the FOLDED list ===")
          (let [ci (first (keep-indexed (fn [i m]
                                         (when (some #(= call-id (:id %)) (:tool_calls m)) i))
                                       provider))]
            (println "assistant naming the call is at provider index" ci "of" (count provider))
            (doseq [i (range (max 0 (- ci 2)) (min (count provider) (+ ci 6)))]
              (let [m (nth provider i)]
                (println (format "  [%d] %-9s id=%s tc=%s answers=%s content=%s"
                                 i (:role m) (pr-str (:id m))
                                 (pr-str (vec (map :id (:tool_calls m))))
                                 (pr-str (:tool_call_id m))
                                 (pr-str (subs (str (:content m)) 0 (min 70 (count (str (:content m)))))))))))
          (report "B. sofar -> model-view -> inbound" provider)
          (println)
          (println "=== C. the same list, through llm/adjacent-answers ===")
          (report "C. after the run's own normalization"
                  (llm/adjacent-answers provider))
      (System/exit 0))))))