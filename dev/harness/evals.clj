(ns harness.evals
  "Read back every `eval` a thread ever ran -- its code and what came back.

  This exists because of the promotion path in eval-self-extension: an agent can
  grow new tools at runtime through `eval`, but that growth dies with the process.
  When something it built is worth keeping, a human reads it out of the log, copies
  the source into `src/harness`, and commits it. Version control, rollback and
  poisoning control are git's job -- nothing here is ever replayed automatically,
  because replaying a log would turn the record into executable input.

  It lives under dev/ for the same reason harness.replay does: the kernel never
  reads its own log. This is a reader, and it is for the author, not the agent --
  the agent already sees its own eval calls in the rebuilt conversation.

  WHERE THE CODE LIVES. The audit lines (tools/pre-execute|execute|post-execute)
  carry only toolCallId / toolName / outcome / error -- they have NO arguments, by
  design (an audit line is a lifecycle marker, not a payload). The code of an eval
  call is in the assistant message's tool_calls[].function.arguments, which is a
  JSON STRING that has to be decoded a second time to get at :code. The return
  value is the content of the tool message whose tool_call_id matches. Both
  findings are locked by tests, so nobody later 'fixes' this reader by reaching
  for the audit lines.

  The read discipline is harness.replay's: a corrupt or truncated log is a hard
  failure that names the line, never a shorter result that reads as a small answer."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness.home]
            [harness.replay :as replay]))

(def ^:private eval-tool-name "eval")

(defn- tool-messages
  "Records -> {tool-call-id content}, the returned side. A tool message carries
  :tool_call_id (the provider's snake_case casing) and its :content is the value."
  [records]
  (into {}
        (comp (filter #(= "message" (:kind %)))
              (map :payload)
              (filter #(= "tool" (:role %)))
              (filter :tool_call_id)
              (map (fn [m] [(:tool_call_id m) (:content m)])))
        records))

(defn- decode-arguments
  "The arguments of a tool call. It arrives as a JSON string -- the provider's wire
  shape -- so it needs a second decode before :code is reachable. A call whose
  arguments will not decode is reported as undecodable rather than dropped: the
  reader's job is to show what happened, not to tidy it away."
  [raw]
  (try
    {:args (json/read-str (if (str/blank? raw) "{}" raw) :key-fn keyword)}
    (catch Exception e
      {:undecodable (ex-message e)})))

(defn evals-in
  "Every eval call in RECORDS, in the order the calls appear.

  Each entry is {:tool-call-id .. :code .. :result .. :error? ..}, plus
  :undecodable (the parse error) when the arguments could not be read. :code is
  the code VERBATIM as submitted -- whitespace and all. :error? is true when the
  matching tool message's content is absent or the seam recorded a failure for
  that call id; the content itself is left untouched either way."
  [records]
  (let [results (tool-messages records)
        errored (into #{}
                      (comp (filter #(= "tools/execute" (:kind %)))
                            (map :payload)
                            (filter :error)
                            (map :toolCallId))
                      records)
        calls   (into []
                      (comp (filter #(= "message" (:kind %)))
                            (map :payload)
                            (filter #(= "assistant" (:role %)))
                            (mapcat #(get % :tool_calls []))
                            (filter #(= eval-tool-name (get-in % [:function :name]))))
                      records)]
    (mapv (fn [call]
            (let [id  (:id call)
                  raw (get-in call [:function :arguments])
                  {:keys [args undecodable]} (decode-arguments raw)]
              {:tool-call-id id
               :code         (:code args)
               :result       (get results id)
               :error?       (boolean (contains? errored id))
               :undecodable  undecodable}))
          calls)))

(defn evals
  "A log FILE -> its eval history: (evals-in records) over the log's records.

  Takes the file, not a directory and a thread id: since the logs became a projects
  tree, 'which workspace this conversation lives in' is a question with an owner
  (the /api/threads listing answers it by walking), and reimplementing that lookup
  here would be a third copy of a rule with two callers already. Use
  `replay/locate`, or a path you already have.

  Uses harness.replay for reading, so a corrupt log fails the same way it does
  everywhere else -- naming the offending line -- and the filename rule is not
  reimplemented a third time."
  [^java.io.File f]
  (evals-in (replay/lines->records (replay/read-lines f))))

(defn describe
  "One eval as a human-readable block: the code it ran and what came back. For a
  person deciding what is worth promoting into src/."
  [{:keys [tool-call-id code result error? undecodable]}]
  (str "--- " tool-call-id (when error? "  [errored]") "\n"
       (if undecodable
         (str "<arguments did not decode: " undecodable ">\n")
         (str code "\n"))
       "=> " (if (nil? result) "<no result recorded>" result) "\n"))

(defn -main
  "  clojure -M:evals <thread-id> [log-dir]

  Prints every eval THREAD-ID ran, in order: the code verbatim, then its result.

  LOG-DIR is the TREE's root and defaults to the process's projects directory
  (harness.home/projects-dir); the thread's file is LOCATED under it, because the
  logs are a tree of workspaces now and this tool has no business knowing which
  project a session belonged to. Pass a directory to search somewhere else
  entirely.

  What this is FOR: promoting runtime growth into the repository. Read the output,
  decide which eval is worth keeping, copy its code into src/harness as a real
  tool, and commit it -- version control is the rollback story. Nothing here ever
  re-runs the code."
  [thread-id & [dir]]
  (let [dir   (or dir (str (harness.home/projects-dir)))
        found (try {:ok (evals (replay/locate dir thread-id))}
                   (catch Exception e {:error (ex-message e)}))]
    (if-some [error (:error found)]
      (binding [*out* *err*] (println error))
      (if (empty? (:ok found))
        (println (str "no eval calls recorded for thread " thread-id " in " dir))
        (doseq [e (:ok found)] (print (describe e)))))))
