(ns harness.http-test
  "Integration: a real server, a real HTTP request, a real SSE body.

  This is the layer that catches what unit tests structurally cannot see -- a run that
  is generated and logged perfectly but never reaches the client, and converter state
  that is rebuilt per event. Both of those actually happened during development."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.fake :as fake]
            [harness.home :as home]
            [harness.http :as http]
            [harness.memory :as mem]
            [harness.opaque :as opaque]
            [harness.replay :as replay]
            [harness.tools :as tools]
            [harness.wire :as wire])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def ^:private ui-origin "http://localhost:5173")

(def ^:private reasoning "\u9700\u8981\u5148\u770b\u4e00\u773c deps.edn\u3002")

(def ^:private script
  [{:reasoning reasoning :content ""
    :tool-calls [{:id "c1" :name "read" :arguments {:path "deps.edn"}}
                 {:id "c2" :name "read" :arguments {:path "README.md"}}]}
   {:content "\u8fd9\u662f\u4e00\u4e2a Clojure \u9879\u76ee\u3002"}])

(defn- with-server
  "Run F against a live server on PORT, with a scripted provider pinned to each
  thread the test serves.

  THREADS names them and is one of:

    \"it-1\"                 one thread, using SCRIPT (the default script below)
    [\"a\" \"b\"]              several threads, all sharing SCRIPT
    {\"a\" SCRIPT-A \"b\" SCRIPT-B}  per-thread scripts -- for a test whose
                            threads must consume turns in a known order

  The pin is PER-THREAD, matching harness.opaque's resolution: a provider
  override is a session's, and the server looks it up by the request's threadId.
  There is deliberately no process-wide slot to fall back on -- a test that
  pinned globally would pass while the per-thread wiring was broken."
  ([port threads f]
   (with-server port threads script f))
  ([port threads turns f]
   (let [pins (cond
                (map? threads) threads
                (coll? threads) (into {} (map (fn [t] [t turns])) threads)
                :else           {threads turns})]
     (doseq [[t ts] pins] (opaque/use-provider! (str t) (fake/scripted ts)))
     (let [stop (http/start! {:port port})]
       (try (f) (finally (stop) (doseq [t (keys pins)] (opaque/use-provider! (str t) nil))))))))

(defn- post-run
  "A real request for THREAD-ID. The run id is random so that two runs -- whether for
  different threads or for the same thread at different times -- never share frame ids.
  A repeated run id would make two runs' frames collide in a rebuilt conversation.
  EXTRA is merged into the body, which is how a resume is sent."
  ([port thread-id] (post-run port thread-id {}))
  ([port thread-id extra]
   (let [body (json/write-str (merge {:threadId thread-id :runId (str (java.util.UUID/randomUUID))
                                      :messages [{:id "u1" :role "user"
                                                  :content "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee"}]
                                      :tools [] :context []}
                                     extra))
         req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/")))
                  (.header "Content-Type" "application/json")
                  (.header "Accept" "text/event-stream")
                  (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                  (.build))]
     (.send (HttpClient/newHttpClient) req
            (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)))))

(defn- log-dir
  "Where the server under test writes its logs. Derived from harness.home so it
  follows the config root -- which the test fixture rebinds to a temp directory,
  so these tests never touch the real one."
  []
  (str (home/logs-dir)))

(defn- header [resp name]
  (str (.orElse (.firstValue (.headers resp) name) "")))

(deftest serves-a-well-formed-run-over-real-http
  (with-server
   8097
   "it-1"
   (fn []
     (let [resp   (post-run 8097 "it-1")
           body   (.body resp)
           frames (wire/frames-from-sse body)]
       (testing "the headers a browser client needs, given it calls us directly"
         (is (= 200 (.statusCode resp)))
         (is (= "text/event-stream" (header resp "Content-Type")))
         (is (= ui-origin (header resp "Access-Control-Allow-Origin"))))
       (testing "a complete and structurally valid run reaches the CLIENT"
         (is (seq frames))
         (is (= "RUN_STARTED" (:type (first frames))))
         (is (= "RUN_FINISHED" (:type (last frames))))
         (is (= 1 (count (filter #(= "RUN_FINISHED" (:type %)) frames))))
         (is (empty? (wire/violations frames))))
       (testing "reasoning crossed the wire and reassembles intact"
         (is (= reasoning
                (apply str (map :delta (filter #(= "REASONING_MESSAGE_CONTENT" (:type %)) frames))))))
       (testing "both tool calls were answered on the wire, each keyed to its own call"
         (let [results (filter #(= "TOOL_CALL_RESULT" (:type %)) frames)]
           (is (= #{"c1" "c2"} (set (map :toolCallId results))))
           (is (= 2 (count results)))
           (is (= 2 (count (distinct (map :messageId results)))))))
       (testing "the read tool really ran on the server"
         (is (str/includes? (str (:content (first (filter #(and (= "TOOL_CALL_RESULT" (:type %))
                                                                (= "c1" (:toolCallId %)))
                                                          frames))))
                            ":paths")))))))

(defn- wait-for-recorded
  "Poll the thread's log, parsed, until PRED holds over the parsed lines or MS
  elapses. Needed because the returned side of the message record lands one beat
  after the terminal frame -- :run/done reaches the consumer only after the SSE
  has closed -- so a reader that races the consumer sees a file without it."
  [f pred ms]
  (let [read   (fn [] (mapv #(json/read-str % :key-fn keyword)
                            (str/split-lines (slurp f :encoding "UTF-8"))))
        finish (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [lines (read)]
        (if (or (pred lines) (> (System/currentTimeMillis) finish))
          lines
          (do (Thread/sleep 25) (recur)))))))

(deftest records-the-run-as-jsonl
  (with-server
   8098
   "it-1"
   (fn []
     ;; Delete first, like the replay e2e does: the assertions below use
     ;; first/last over the parsed lines, so leftover runs from earlier test
     ;; executions must not bleed in.
     (io/delete-file (io/file (log-dir) "it-1.jsonl") true)
     (post-run 8098 "it-1")
     (let [f     (io/file (log-dir) "it-1.jsonl")
           lines (wait-for-recorded f
                                    ;; The returned tail lands one line at a
                                    ;; time after the terminal frame -- wait
                                    ;; for its LAST line (the final answer),
                                    ;; not its first, or the reader races the
                                    ;; writer and sees half a tail.
                                    (fn [ls]
                                      (some (fn [l]
                                              (and (= "message" (:kind l))
                                                   (= "assistant" (get-in l [:payload :role]))
                                                   (= "\u8fd9\u662f\u4e00\u4e2a Clojure \u9879\u76ee\u3002"
                                                      (get-in l [:payload :content]))))
                                            ls))
                                    2000)
           msgs  (mapv :payload (filter #(= "message" (:kind %)) lines))]
       (testing "both the inbound input and every emitted frame are on disk"
         (is (contains? (set (map :kind lines)) "input"))
         (is (contains? (set (map :kind lines)) "event")))
       (testing "the record holds the raw RunAgentInput, not a summary"
         (is (some #(= "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee"
                       (get-in % [:payload :messages 0 :content]))
                   lines)))
       (testing "the submitted system prompt is on disk VERBATIM"
         (let [sys (first (filter #(= "system" (:role %)) msgs))]
           (is (some? sys))
           ;; Context never touches the system message -- it rides as a trailing
           ;; user message -- so what was submitted is the frozen prompt.md
           ;; and nothing else, in every run.
           (is (= (slurp "prompt.md" :encoding "UTF-8") (:content sys)))))
       (testing "the user's message is recorded in the provider's shape"
         (is (some #(and (= "user" (:role %))
                         (= "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee" (:content %)))
                   msgs))
         ;; ag/inbound strips the AG-UI-only fields, :id among them -- the record
         ;; holds what the model will see, not what the client sent.
         (is (some #(and (= "user" (:role %)) (not (contains? % :id))) msgs)))
       (testing "every LLM return is on disk VERBATIM"
         (let [assistants (filter #(= "assistant" (:role %)) msgs)]
           (is (= reasoning (:reasoning_content (first assistants))))
           ;; The whole tool_calls payload, not just the ids: the record holds
           ;; the provider message unrebuilt.
           (is (= [{:id "c1" :type "function"
                    :function {:name "read" :arguments "{\"path\":\"deps.edn\"}"}}
                   {:id "c2" :type "function"
                    :function {:name "read" :arguments "{\"path\":\"README.md\"}"}}]
                  (:tool_calls (first assistants))))
           (is (= "\u8fd9\u662f\u4e00\u4e2a Clojure \u9879\u76ee\u3002"
                  (:content (last assistants))))))
       (testing "tool results are recorded as the tool messages they became"
         ;; History appends tool messages in the provider's call order, whatever
         ;; the completion order on the wire was.
         (let [tools (filter #(= "tool" (:role %)) msgs)]
           (is (= ["c1" "c2"] (mapv :tool_call_id tools)))
           ;; The content is what the read tool actually returned.
           (is (some #(and (= "c1" (:tool_call_id %))
                           (= (slurp "deps.edn" :encoding "UTF-8") (:content %)))
                     tools))))))))

(deftest the-log-the-server-writes-is-one-replay-can-read
  ;; Every other replay test builds its log with the emitter directly. This one goes
  ;; through the real edge -- real server, real request, real file -- because that is
  ;; the only way to catch a disagreement about the log's name or its line format, and
  ;; the two sides live in different namespaces on different sides of dev/src.
  (with-server
   8095
   "replay-e2e"
   (fn []
     (io/delete-file (io/file (log-dir) "replay-e2e.jsonl") true)
     (post-run 8095 "replay-e2e")
     (let [history (replay/history (log-dir) "replay-e2e")]
       (testing "the reader found the file the writer wrote, and rebuilt a conversation"
         (is (= "system" (:role (first history))))
         (is (some #(= "user" (:role %)) history)))
       (testing "the reasoning the server emitted is folded back for the model"
         (is (= reasoning
                (:reasoning_content
                 (first (filter #(and (= "assistant" (:role %)) (:tool_calls %)) history))))))
       (testing "and the tools the server actually ran are in the rebuilt conversation"
         ;; Match c1 by its own id: README.md also contains ":paths", so content
         ;; alone could be satisfied by the other call's result.
         (is (some #(and (= "c1" (:tool_call_id %))
                         (str/includes? (str (:content %)) ":paths"))
                   (filter #(= "tool" (:role %)) history))))))))

(deftest records-the-tool-lifecycle-as-jsonl
  ;; ApplePi's ADR-0021 audit trio, keyed by toolCallId: every call enters
  ;; (pre-execute), executes, and closes (post-execute) -- a pass carries no
  ;; outcome key, and the wire is untouched by any of it.
  (with-server
   8094
   "lifecycle"
   (fn []
     (io/delete-file (io/file (log-dir) "lifecycle.jsonl") true)
     (post-run 8094 "lifecycle")
     (let [f     (io/file (log-dir) "lifecycle.jsonl")
           lines (wait-for-recorded f
                                    (fn [ls]
                                      (>= (count (filter #(= "tools/post-execute" (:kind %)) ls)) 2))
                                    2000)
           phases (group-by :kind (filter #(.startsWith ^String (str (:kind %)) "tools/") lines))
           pre    (mapv :payload (get phases "tools/pre-execute"))
           ex     (mapv :payload (get phases "tools/execute"))
           post   (mapv :payload (get phases "tools/post-execute"))]
       (testing "both calls entered, executed and closed the seam, keyed by id"
         (is (= #{"c1" "c2"} (set (map :toolCallId pre))))
         (is (= #{"c1" "c2"} (set (map :toolCallId ex))))
         (is (= #{"c1" "c2"} (set (map :toolCallId post)))))
       (testing "a passing call carries no outcome and no error"
         (is (every? #(not (contains? % :outcome)) pre))
         (is (every? #(not (contains? % :error)) ex)))
       (testing "every phase names the tool that ran"
         (is (every? #(= "read" (:toolName %)) post)))))))

(defn- assistant-with-call
  "The assistant message a client holds after a run parked: its tool call, never
  answered. A resume request carries it back, exactly as CopilotKit does."
  [id name args]
  {:id "m1" :role "assistant" :content ""
   :toolCalls [{:id id :type "function"
                :function {:name name :arguments (json/write-str args)}}]})

(deftest a-parked-run-asks-over-http-and-resumes
  ;; The whole pre-tool approval path over a real socket: run one parks and asks,
  ;; the client answers with resume, run two replays the answer. Approve and veto
  ;; both, each on its own thread -- the script's turns are consumed in run order,
  ;; so the two threads' turns are laid out alternately.
  (let [ok   (str (System/getProperty "java.io.tmpdir") "/harness-http-approved.txt")
        veto (str (System/getProperty "java.io.tmpdir") "/harness-http-vetoed.txt")
        call (fn [path] {:content ""
                         :tool-calls [{:id "c1" :name "write"
                                       :arguments {:path path :content "written"}}]})
        _    (io/delete-file ok true)
        _    (io/delete-file veto true)
        _    (mem/session-require-approval! "http-approve" "write")
        _    (mem/session-require-approval! "http-veto" "write")]
    (with-server
     8093
     {"http-approve" [(call ok) {:content "wrote it"}]
      "http-veto"    [(call veto) {:content "understood"}]}
     (fn []
       (let [asked   (wire/frames-from-sse (.body (post-run 8093 "http-approve")))
             term    (last asked)
             iid     (get-in term [:outcome :interrupts 0 :id])]
         (testing "the first run ends on an interrupt the client can act on"
           (is (empty? (wire/violations asked)))
           (is (= 1 (count (filter #(= "RUN_FINISHED" (:type %)) asked))))
           (is (= "interrupt" (get-in term [:outcome :type])))
           (is (= ["c1"] (mapv :toolCallId (get-in term [:outcome :interrupts]))))
           (is (not-any? #(= "TOOL_CALL_RESULT" (:type %)) asked)))
         (testing "and it did not write anything yet"
           (is (false? (.exists (io/file ok)))))

         (let [resumed (wire/frames-from-sse
                        (.body (post-run 8093 "http-approve"
                                         {:messages [{:id "u1" :role "user" :content "go"}
                                                     (assistant-with-call
                                                      "c1" "write" {:path ok :content "written"})]
                                          :resume [{:interruptId iid :status "resolved"
                                                    :payload {:decision "approved"}}]})))]
           (testing "the approved call really runs on the resume run"
             (is (true? (.exists (io/file ok))))
             (is (= "written" (slurp ok :encoding "UTF-8"))))
           (testing "the resumed run is a complete, valid run of its own"
             (is (empty? (wire/violations resumed)))
             (is (= "RUN_FINISHED" (:type (last resumed))))
             ;; A natural end carries no outcome at all.
             (is (not (contains? (last resumed) :outcome)))
             (is (some #(= "TOOL_CALL_RESULT" (:type %)) resumed))))

         ;; Second thread: same shape, a veto instead. Its parked run is turn 3.
         (let [asked-v (wire/frames-from-sse (.body (post-run 8093 "http-veto")))
               iid-v   (get-in (last asked-v) [:outcome :interrupts 0 :id])
               vetoed  (wire/frames-from-sse
                        (.body (post-run 8093 "http-veto"
                                         {:messages [{:id "u1" :role "user" :content "go"}
                                                     (assistant-with-call
                                                      "c1" "write" {:path veto :content "written"})]
                                          :resume [{:interruptId iid-v :status "cancelled"
                                                    :payload {:reason "not on my watch"}}]})))]
           (testing "a veto never runs the tool"
             (is (false? (.exists (io/file veto)))))
           (testing "the veto comes back as the call's answer, on the wire"
             (let [result (first (filter #(= "TOOL_CALL_RESULT" (:type %)) vetoed))]
               (is (= "c1" (:toolCallId result)))
               (is (str/includes? (str (:content result)) "vetoed by human"))
               (is (str/includes? (str (:content result)) "not on my watch"))))
           (testing "and the run carries on to a normal end"
             (is (= "RUN_FINISHED" (:type (last vetoed))))
             (is (not (contains? (last vetoed) :outcome)))
             (is (empty? (wire/violations vetoed))))

           (testing "both decisions are on disk: the audit line and the resumed transit"
             (let [lines (wait-for-recorded
                          (io/file (log-dir) "http-approve.jsonl")
                          (fn [ls] (some #(= "approval/decided" (:kind %)) ls))
                          2000)
                   decided (filter #(= "approval/decided" (:kind %)) lines)
                   pre     (filter #(and (= "tools/pre-execute" (:kind %))
                                          (= "c1" (get-in % [:payload :toolCallId])))
                                   lines)]
               ;; The verdict is a keyword in the event and a string on disk --
               ;; json has no keywords.
               (is (= "approved" (get-in (first decided) [:payload :verdict])))
               (is (= "c1" (get-in (first decided) [:payload :tool-call-id])))
               (is (= "approved" (get-in (first decided) [:payload :payload :decision])))
               (is (some #(= "needs-approval" (get-in % [:payload :outcome])) pre))
               (is (some #(= "approved" (get-in % [:payload :outcome])) pre))))))))))

(deftest an-unknown-interrupt-is-refused-over-http
  ;; A restart loses the parking; a client resuming an interrupt this process
  ;; never parked must be told so, never quietly granted.
  (with-server
   8092
   "http-unknown"
   [{:content "never reached"}]
   (fn []
     (let [frames (wire/frames-from-sse
                   (.body (post-run 8092 "http-unknown"
                                    {:resume [{:interruptId "never-parked"
                                               :status "resolved"}]})))]
       (is (= ["RUN_STARTED" "RUN_ERROR"] (mapv :type frames)))
       (is (str/includes? (:message (last frames)) "unknown interrupt"))))))

;; ----------------------------------------------------- the provider timeline

(defn- with-resolved-config
  "Install a config root that RESOLVES its provider -- a registry plus a default
  tier, no scripted pin -- and restore the previous one after. The registry's
  entries use :protocol :fake (and live in the shared test-script atom -- an
  atom cannot cross EDN), so the RESOLVED provider is a working fake:
  resolution runs for real while the LLM stays offline.

  Used by the two tests below. A pinned provider skips resolution, and the
  provider timeline is precisely about resolution, so these must not pin."
  [turns f]
  (let [cfg-file (io/file (log-dir) ".." "config.edn")
        reg-file (io/file (log-dir) ".." "providers.edn")
        read-back (fn [f] (when (.exists f) (slurp f :encoding "UTF-8")))
        old-cfg (read-back cfg-file) old-reg (read-back reg-file)
        old-script @fake/test-script]
    (try
      (reset! fake/test-script (vec turns))
      (spit cfg-file "{:provider :cheap}\n" :encoding "UTF-8")
      (spit reg-file
            (pr-str {:cheap {:protocol :fake :base-url "https://x/v1" :model "small"}
                     :smart {:protocol :fake :base-url "https://x/v1" :model "big"}})
            :encoding "UTF-8")
      (f)
      (finally
        (spit cfg-file (or old-cfg "{:protocol :fake}\n") :encoding "UTF-8")
        (io/delete-file reg-file true)
        (reset! fake/test-script old-script)))))

(deftest the-provider-timeline-is-init-once-then-changes
  ;; Ticket 03, over the real edge. A session's provider history lands as
  ;; exactly one init line and one line per change -- never a snapshot per run.
  (with-resolved-config
   [{:content "hello"} {:content "hello"}]
   (fn []
     (let [id   "http-prov"
           stop (http/start! {:port 8101})]
       (try
         (io/delete-file (io/file (log-dir) (str id ".jsonl")) true)
         ;; Run one: the init line lands. The scripted turn is a plain reply,
         ;; so the run does not touch the provider.
         (post-run 8101 id)
         (let [after-first (wait-for-recorded
                            (io/file (log-dir) (str id ".jsonl"))
                            (fn [ls] (some #(= "provider/init" (:kind %)) ls))
                            2000)]
           (testing "the first run lands exactly one init line, before any message"
             (let [kinds (mapv :kind after-first)]
               (is (= 1 (count (filter #(= "provider/init" %) kinds))))
               (is (< (.indexOf kinds "input") (.indexOf kinds "provider/init")))
               (is (< (.indexOf kinds "provider/init") (.indexOf kinds "message")))))
           (testing "it carries the four fields, the source, and NO api-key value"
             (let [p (:payload (first (filter #(= "provider/init" (:kind %)) after-first)))]
               (is (= "fake" (:protocol p)))
               (is (= "https://x/v1" (:base-url p)))
               (is (= "small" (:model p)))
               (is (= "default" (:source p)))
               (is (= "stripped" (:api-key p)))))
           ;; Run two of the same thread: no second init.
           (post-run 8101 id)
           (let [after-second (wait-for-recorded
                               (io/file (log-dir) (str id ".jsonl"))
                               (fn [ls] (>= (count (filter #(= "input" (:kind %)) ls)) 2))
                               2000)]
             (testing "a later run of the same thread does not repeat the init"
               (is (= 1 (count (filter #(= "provider/init" (:kind %)) after-second)))))))
         (finally (stop)))))))

(deftest a-session-configure-lands-as-a-changed-line
  ;; The write half of 04, end to end: the agent changes its reasoning effort,
  ;; the change is approved, and the jsonl shows a provider/changed line with
  ;; before -> after. The approval gate is what makes it land only after the
  ;; human's verdict.
  ;;
  ;; A session override holds ONLY the fields the session owns (not the resolved
  ;; provider's full shape -- that is what the init line is for). So the
  ;; change's before and after show the session's slice, and the chain between
  ;; consecutive changes is exactly the test of "what moved in this session".
  (with-resolved-config
   [{:content "hello"}]
   (fn []
     (let [id   "http-change"
           stop (http/start! {:port 8102})]
       (try
         (io/delete-file (io/file (log-dir) (str id ".jsonl")) true)
         ;; Seed the session with a baseline the change can stand on. The change
         ;; line is the session's own slice, not the full provider.
         (opaque/set-override! id {:model "small"})
         ;; Drive the change the way a run would: park, approve, resume-transit.
         (let [call (fn [] (tools/run! {:id "cfg1" :type "function"
                                        :function {:name "session-configure"
                                                   :arguments (json/write-str {:reasoning-effort "high"})}}
                                       id))
               {:keys [parked]} (call)]
           (mem/decide-approval! (:interrupt-id parked) :approved {})
           (call))
         (testing "the session now serves the changed value"
           (is (= "high" (:reasoning-effort (mem/active-provider id)))))
         ;; Run once so the edge drains the outbox to the log.
         (post-run 8102 id)
         (let [lines (wait-for-recorded
                      (io/file (log-dir) (str id ".jsonl"))
                      (fn [ls] (some #(= "provider/changed" (:kind %)) ls))
                      2000)
               changed (:payload (first (filter #(= "provider/changed" (:kind %)) lines)))]
           (testing "the change is on disk, before -> after, marked approved"
             (is (= "approved" (:verdict changed)))
             (is (= "small" (get-in changed [:before :model]))
                 "the session's pre-change slice is the baseline that stood")
             (is (= "small" (get-in changed [:after :model]))
                 "the model never moved; only the effort did")
             (is (= "high" (get-in changed [:after :reasoning-effort]))
                 "and the new field is the one the change named")
             (is (= "session-configure" (:trigger changed))
                 "the change names the path that pressed it")
             (is (= {:model "small" :reasoning-effort "high"}
                    (select-keys (:override changed) [:model :reasoning-effort]))
                 "the override is the full session slice after the change"))
           (testing "consecutive changes chain through the same slice"
             ;; One more approved change: reasoning-effort goes from high to
             ;; low. before on the new line MUST equal after on the previous.
             (let [call (fn [] (tools/run! {:id "cfg2" :type "function"
                                            :function {:name "session-configure"
                                                       :arguments (json/write-str {:reasoning-effort "low"})}}
                                           id))
                   {:keys [parked]} (call)]
               (mem/decide-approval! (:interrupt-id parked) :approved {})
               (call))
             (post-run 8102 id)
             (let [lines (wait-for-recorded
                          (io/file (log-dir) (str id ".jsonl"))
                          (fn [ls] (>= (count (filter #(= "provider/changed" (:kind %)) ls)) 2))
                          2000)
                   changes (filter #(= "provider/changed" (:kind %)) lines)
                   [a b]   (mapv :payload changes)]
               (is (= "high" (get-in a [:after :reasoning-effort])))
               (is (= "high" (get-in b [:before :reasoning-effort]))
                   "the second change starts where the first ended")
               (is (= "low" (get-in b [:after :reasoning-effort])))
               (is (every? #(= "session-configure" (:trigger %)) [a b])
                   "every chained change names its trigger")
               (is (= {:model "small"} (select-keys (:override a) [:model]))
                   "the first change's override is the full session slice")
               (is (= "low" (get-in (:override b) [:reasoning-effort]))
                   "the second change's override reflects the latest session state"))))
         (finally (stop) (opaque/set-override! id nil)))))))

(deftest answers-the-cors-preflight
  (with-server
   8099
   "preflight"
   (fn []
     (let [req  (-> (HttpRequest/newBuilder (URI/create "http://127.0.0.1:8099/"))
                    (.method "OPTIONS" (HttpRequest$BodyPublishers/noBody))
                    (.build))
           resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/discarding))]
       (is (= 204 (.statusCode resp)))
       (is (= ui-origin (header resp "Access-Control-Allow-Origin")))
       (is (str/includes? (header resp "Access-Control-Allow-Methods") "POST"))))))
