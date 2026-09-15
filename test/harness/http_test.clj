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
            [harness.providers :as providers]
            [harness.project :as project]
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

  The pin is PER-THREAD, matching harness.providers' resolution: a provider
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
     (doseq [[t ts] pins] (providers/use-provider! (str t) (fake/scripted ts)))
     (let [stop (http/start! {:port port})]
       (try (f) (finally (stop) (doseq [t (keys pins)] (providers/use-provider! (str t) nil))))))))

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
  has closed -- so a reader that races the consumer sees a file without it.

  A TRAILING LINE THAT DOES NOT PARSE IS SKIPPED, NOT AN ERROR. The file is being
  appended to while this reads it, so its last line may be half-written; that is a
  fact about reading a live log, not a corrupt log. Only an unparseable line
  BEFORE the last one is worth failing on, and the strict reader
  (harness.replay/lines->records) is the one that makes that call -- this helper
  only polls, so it reports what it could parse and lets the assertions judge."
  [f pred ms]
  (let [read   (fn []
                 (let [raw (try (slurp f :encoding "UTF-8") (catch Throwable _ ""))
                       ls  (str/split-lines raw)
                       n   (count ls)]
                   (into []
                         (keep-indexed
                          (fn [i line]
                            (try
                              (json/read-str line :key-fn keyword)
                              (catch Throwable t
                                ;; The LAST line may be half-written: the file is
                                ;; still growing. Anywhere else it is corruption,
                                ;; and swallowing that would turn this helper into
                                ;; a way to make a broken log look readable.
                                (when (< i (dec n)) (throw t))))))
                         ls)))
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
  ;;
  ;; It WAITS for the run's returned message lines before reading: the write that
  ;; ends a run lands after the SSE has closed, so reading straight afterwards
  ;; races the writer and can catch the log mid-line. (It used to do exactly that,
  ;; and failed intermittently with 'the log is truncated or corrupt' -- which is
  ;; what a reader racing an append-only file looks like.)
  (with-server
   8095
   "replay-e2e"
   (fn []
     (let [log (io/file (log-dir) "replay-e2e.jsonl")]
       (io/delete-file log true)
       (post-run 8095 "replay-e2e")
       (wait-for-recorded
        log
        (fn [ls] (and (some #(= "provider/init" (:kind %)) ls)
                      (>= (count (filter #(= "message" (:kind %)) ls)) 4)))
        3000)
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
                     (filter #(= "tool" (:role %)) history)))))))))

(deftest an-image-part-reaches-the-model-translated-and-the-log-says-so
  ;; Images end to end. The AG-UI spelling of a content part is not the provider's,
  ;; so the translation happens on the way IN -- which is what lets the "message"
  ;; line stay a truthful record of what the LLM was about to see. A log holding
  ;; AG-UI parts under a "message" kind would be lying about the one thing it
  ;; exists to record.
  ;;
  ;; The same messages are then rebuilt through replay/history, which re-derives
  ;; them from the input line: live and replay must agree, or a resumed
  ;; conversation would send the vendor a shape the live run never did.
  (with-server
   8093
   "images"
   (fn []
     (let [log    (io/file (log-dir) "images.jsonl")
           parts  [{:type "text" :text "what is this"}
                   {:type "image" :source {:type "url" :value "https://example.test/a.png"}}
                   {:type "image" :source {:type "data" :value "AAAB" :mimeType "image/jpeg"}}]
           expect [{:type "text" :text "what is this"}
                   {:type "image_url" :image_url {:url "https://example.test/a.png"}}
                   {:type "image_url"
                    :image_url {:url "data:image/jpeg;base64,AAAB"}}]]
       (io/delete-file log true)
       (post-run 8093 "images" {:messages [{:id "u1" :role "user" :content parts}]})
       (let [lines (wait-for-recorded
                    log
                    (fn [ls] (some #(= "message" (:kind %)) ls))
                    3000)
             sent  (->> lines
                        (filter #(= "message" (:kind %)))
                        (map :payload)
                        (filter #(= "user" (:role %))))]
         (testing "what the LLM saw is the provider's part shape, not the client's"
           (is (= [expect] (mapv :content sent)))
           (is (not-any? #(str/includes? (json/write-str %) "\"source\"")
                         sent)
               "no AG-UI source wrapper survives into the record"))
         (testing "and replay rebuilds the very same messages from the log"
           (let [history (replay/history (log-dir) "images")
                 rebuilt (mapv :content (filter #(= "user" (:role %)) history))]
             (is (= [expect] rebuilt)
                 "a resumed conversation sends what the live run sent"))))))))

(defn- with-declaring-server
  "Like with-server, but the scripted pin DECLARES an input modality set -- which
  is how a pinned provider can stand in for a model with a stated capability.

  The spec (as opposed to the pin) is what the guard reads, and a pin is a whole
  provider map, so the declaration rides it. Returns the script atom so a test can
  assert the provider was never called: a drained script is a provider that ran."
  [port thread-id declared turns f]
  (let [script (atom (vec turns))]
    (providers/use-provider! thread-id (assoc (fake/scripted turns) :input declared
                                        :script script))
    (let [stop (http/start! {:port port})]
      (try (f script) (finally (stop) (providers/use-provider! thread-id nil))))))

(deftest a-text-only-model-refuses-an-image-by-name-and-never-calls-the-vendor
  ;; The declaration is only worth anything if something enforces it. The vendor's
  ;; own answer to an undeclared modality is a 400 whose body names nothing useful,
  ;; arriving after the request was sent -- so the refusal happens here instead,
  ;; and the provider is not contacted at all.
  (with-declaring-server
   8089
   "guarded"
   #{:text}
   [{:content "should never be reached"}]
   (fn [script]
     (let [resp   (post-run 8089 "guarded"
                            {:messages [{:id "u1" :role "user"
                                         :content [{:type "text" :text "what is this"}
                                                   {:type "image"
                                                    :source {:type "url"
                                                             :value "https://x/i.png"}}]}]})
           frames (mapv #(json/read-str (str/trim (subs % 5)) :key-fn keyword)
                        (filter #(str/starts-with? % "data:") (str/split-lines (.body resp))))
           types  (mapv :type frames)
           error  (first (filter #(= "RUN_ERROR" (:type %)) frames))]
       (testing "the run terminates as an error, not as a success"
         (is (some #(= "RUN_ERROR" %) types))
         (is (not-any? #(= "RUN_FINISHED" %) types)))
       (testing "and the refusal names the model and the modality it will not take"
         (is (str/includes? (:message error) "image"))
         (is (str/includes? (:message error) "text"))
         (is (str/includes? (:message error) "does not accept")))
       (testing "NOTHING WAS SENT -- the scripted provider is untouched"
         (is (= 1 (count @script))
             "the vendor would have consumed its turn had the run reached it"))))))

(deftest a-model-that-declares-images-is-not-guarded
  ;; The other half: the guard must not become a blanket refusal of images.
  (with-declaring-server
   8090
   "unguarded"
   #{:text :image}
   [{:content "saw it"}]
   (fn [script]
     (let [resp (post-run 8090 "unguarded"
                          {:messages [{:id "u1" :role "user"
                                       :content [{:type "text" :text "what is this"}
                                                 {:type "image"
                                                  :source {:type "url"
                                                           :value "https://x/i.png"}}]}]})
           body (.body resp)]
       (is (str/includes? body "RUN_FINISHED"))
       (is (not (str/includes? body "RUN_ERROR")))
       (is (empty? @script) "and the run really did reach the provider")))))

(deftest a-text-only-model-takes-text-as-before
  ;; Regression: the guard must not touch the ordinary run.
  (with-declaring-server
   8091
   "plain"
   #{:text}
   [{:content "hello"}]
   (fn [script]
     (let [body (.body (post-run 8091 "plain"))]
       (is (str/includes? body "RUN_FINISHED"))
       (is (not (str/includes? body "RUN_ERROR")))
       (is (empty? @script))))))

(deftest a-model-that-declares-nothing-is-not-guarded
  ;; An inline provider that never stated a capability promises nothing, so there
  ;; is nothing to enforce. Guarding it would invent a rule the configuration never
  ;; wrote -- and break every deployment that describes its endpoint directly.
  (with-declaring-server
   8092
   "silent"
   nil
   [{:content "went through"}]
   (fn [script]
     (let [body (.body (post-run 8092 "silent"
                                 {:messages [{:id "u1" :role "user"
                                              :content [{:type "image"
                                                         :source {:type "url"
                                                                  :value "https://x/i.png"}}]}]}))]
       (is (str/includes? body "RUN_FINISHED"))
       (is (not (str/includes? body "RUN_ERROR")))
       (is (empty? @script) "and the provider was reached")))))

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
        _    (tools/session-require-approval! "http-approve" "write")
        _    (tools/session-require-approval! "http-veto" "write")]
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
  "Install a config root that RESOLVES its provider -- a catalog plus a default
  tier, no scripted pin -- and restore the previous one after. The catalog's
  providers use :protocol :fake (and live in the shared test-script atom -- an
  atom cannot cross EDN), so the RESOLVED provider is a working fake: resolution
  runs for real while the LLM stays offline.

  Two vendors with DIFFERENT endpoints, so a test can see a vendor switch move
  the endpoint rather than only the model id -- and OPPOSITE declarations, alpha
  taking images and beta not, so the input guard has a yes and a no to work with.
  Their models also carry DIFFERENT token counts, so that the answer followed the
  model is testable for the counts too, and one alpha model declares none at all,
  which is the entry that makes 'silence is not zero' testable.

  Used by the tests below. A pinned provider skips resolution, and the provider
  timeline is precisely about resolution, so these must not pin."
  [turns f]
  (let [cfg-file (io/file (log-dir) ".." "config.edn")
        reg-file (io/file (log-dir) ".." "providers.edn")
        read-back (fn [f] (when (.exists f) (slurp f :encoding "UTF-8")))
        old-cfg (read-back cfg-file) old-reg (read-back reg-file)
        old-script @fake/test-script]
    (try
      (reset! fake/test-script (vec turns))
      (spit cfg-file "{:provider :alpha}\n" :encoding "UTF-8")
      (spit reg-file
            (pr-str {:alpha {:protocol :fake :base-url "https://x/v1"
                             :model "alpha-small"
                             :models {"alpha-small" {:input #{:text :image} :output #{:text}
                                                     :context-window 200000
                                                     :max-output-tokens 8192}
                                      "alpha-big"   {:input #{:text :image} :output #{:text}
                                                     :context-window 1000000
                                                     :max-output-tokens 64000}
                                      ;; Declares no counts at all -- the entry that
                                      ;; makes 'silence is not zero' testable.
                                      "alpha-bare"  {:input #{:text} :output #{:text}}}}
                     :beta  {:protocol :fake :base-url "https://y/v1"
                             :model "beta-plain"
                             :models {"beta-plain" {:input #{:text} :output #{:text}
                                                    :context-window 128000
                                                    :max-output-tokens 4096}}}})
            :encoding "UTF-8")
      (f)
      (finally
        (spit cfg-file (or old-cfg "{:protocol :fake}\n") :encoding "UTF-8")
        (io/delete-file reg-file true)
        (reset! fake/test-script old-script)))))

(deftest the-guard-reads-the-catalogs-declaration-not-a-pin
  ;; Through REAL resolution, unlike the pinned guard tests above: the catalog
  ;; says beta-plain is text-only, so an image aimed at it is refused. This is the
  ;; wiring that matters -- a guard reading a declaration nothing populates would
  ;; pass every pinned test and do nothing in production.
  (with-resolved-config
   [{:content "unreachable"}]
   (fn []
     (let [id     "http-guard"
           stop   (http/start! {:port 8088})
           image  {:messages [{:id "u1" :role "user"
                               :content [{:type "text" :text "look"}
                                         {:type "image"
                                          :source {:type "url" :value "https://x/i.png"}}]}]}
           error  (fn [body]
                    (first (keep #(let [f (json/read-str (str/trim (subs % 5)) :key-fn keyword)]
                                    (when (= "RUN_ERROR" (:type f)) f))
                                 (filter #(str/starts-with? % "data:") (str/split-lines body)))))]
       (try
         (testing "aimed at the text-only model, it is refused by name"
           (providers/set-override! id {:provider :beta})
           (let [e (error (.body (post-run 8088 id image)))]
             (is (some? e))
             (is (str/includes? (:message e) "beta-plain") "names the model")
             (is (str/includes? (:message e) "image") "and the modality it will not take")))
         (testing "the SAME input is served by a model that declares images"
           (providers/set-override! id {:provider :alpha})
           (is (= #{:text :image} (:input (providers/active-provider id))))
           (is (nil? (error (.body (post-run 8088 id image))))))
         (finally (stop) (providers/set-override! id nil)))))))

(deftest a-run-naming-a-count-is-refused-rather-than-silently-dropped
  ;; The third entry, over the real edge. A run may name the three knobs; naming a
  ;; model's counts there is a mistake, and the honest answer is a terminated run
  ;; that says which field it could not use -- not a run that starts, ignores it,
  ;; and reports success. The client learns the same way it learns about a bad
  ;; model id: a RUN_ERROR frame naming the offending field.
  (with-resolved-config
   [{:content "unreachable"}]
   (fn []
     (let [id    "http-count-in"
           stop  (http/start! {:port 8089})
           error (fn [body]
                   (first (keep #(let [f (json/read-str (str/trim (subs % 5)) :key-fn keyword)]
                                   (when (= "RUN_ERROR" (:type f)) f))
                                (filter #(str/starts-with? % "data:") (str/split-lines body)))))]
       (try
         (io/delete-file (io/file (log-dir) (str id ".jsonl")) true)
         (let [e (error (.body (post-run 8089 id {:provider {:context-window 200000}})))]
           (is (some? e) "the run is terminated rather than served with the field dropped")
           (is (str/includes? (:message e) "context-window") "the field is named")
           (is (str/includes? (:message e) "providers.edn")
               "and the run says where it belongs instead"))
         (testing "and nothing was resolved or recorded for it"
           (is (nil? (providers/override-for id)))
           (let [lines (str/split-lines (slurp (io/file (log-dir) (str id ".jsonl")) :encoding "UTF-8"))]
             (is (not-any? #(str/includes? % "provider/init") lines)
                 "a run that could not resolve writes no init line")))
         (finally (stop)))))))

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
           (testing "it carries the selection, what it resolved to, the source, and NO key value"
             (let [p (:payload (first (filter #(= "provider/init" (:kind %)) after-first)))]
               (is (= "alpha" (:provider p)) "the provider that was selected")
               (is (= "alpha-small" (:model p)) "the model id that was selected")
               (is (= "fake" (:protocol p)) "and what the catalog resolved it to")
               (is (= "https://x/v1" (:base-url p)))
               (is (= ["image" "text"] (:input p)) "the model's modalities, as wire strings")
               (is (= ["text"] (:output p)))
               (is (= 200000 (:context-window p))
                   "and the model's counts, recorded rather than left to be re-derived")
               (is (= 8192 (:max-output-tokens p)))
               (is (= "default" (:source p)))
               (is (= "stripped" (:api-key p)))))
           ;; The endpoint above is RECORDED, not re-derived: the catalog can
           ;; change under an old log (a base-url moves, a model is added), so a
           ;; reader re-resolving would report today's answer as that run's.
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
  ;; The write half, end to end: the agent changes its reasoning effort, the
  ;; change is approved, and the jsonl shows a provider/changed line with
  ;; before -> after plus what it resolved to. The approval gate is what makes it
  ;; land only after the human's verdict.
  ;;
  ;; A session override holds ONLY the knobs the session owns (not the resolved
  ;; endpoint -- that is what :resolved and the init line are for). So the
  ;; change's before and after show the session's slice, and the chain between
  ;; consecutive changes is exactly the test of "what moved in this session".
  (with-resolved-config
   [{:content "hello"}]
   (fn []
     (let [id   "http-change"
           stop (http/start! {:port 8102})]
       (try
         (io/delete-file (io/file (log-dir) (str id ".jsonl")) true)
         ;; Seed the session with a baseline the change can stand on.
         (providers/set-override! id {:model "alpha-big"})
         ;; Drive the change the way a run would: park, approve, resume-transit.
         (let [call (fn [] (tools/run! {:id "cfg1" :type "function"
                                        :function {:name "session-configure"
                                                   :arguments (json/write-str {:reasoning-effort "high"})}}
                                       id))
               {:keys [parked]} (call)]
           (tools/decide-approval! (:interrupt-id parked) :approved {})
           (call))
         (testing "the session now serves the changed value"
           (is (= "high" (:reasoning-effort (providers/active-provider id)))))
         ;; Run once so the edge drains the outbox to the log.
         (post-run 8102 id)
         (let [lines (wait-for-recorded
                      (io/file (log-dir) (str id ".jsonl"))
                      (fn [ls] (some #(= "provider/changed" (:kind %)) ls))
                      2000)
               changed (:payload (first (filter #(= "provider/changed" (:kind %)) lines)))]
           (testing "the change is on disk, before -> after, marked approved"
             (is (= "approved" (:verdict changed)))
             (is (= "alpha-big" (get-in changed [:before :model]))
                 "the session's pre-change slice is the baseline that stood")
             (is (= "alpha-big" (get-in changed [:after :model]))
                 "the model never moved; only the effort did")
             (is (= "high" (get-in changed [:after :reasoning-effort]))
                 "and the new knob is the one the change named")
             (is (= "session-configure" (:trigger changed))
                 "the change names the path that pressed it")
             (is (= {:model "alpha-big" :reasoning-effort "high"}
                    (select-keys (:override changed) [:model :reasoning-effort]))
                 "the override is the full session slice after the change")
             (testing "and it records what that slice resolved to, so a reader
                       months later is not reading today's catalog"
               (is (= "https://x/v1" (get-in changed [:resolved :base-url])))
               (is (= "alpha-big" (get-in changed [:resolved :model])))
               (is (= ["image" "text"] (get-in changed [:resolved :input])))))
           (testing "consecutive changes chain through the same slice"
             ;; One more approved change: reasoning-effort goes from high to
             ;; low. before on the new line MUST equal after on the previous.
             (let [call (fn [] (tools/run! {:id "cfg2" :type "function"
                                            :function {:name "session-configure"
                                                       :arguments (json/write-str {:reasoning-effort "low"})}}
                                           id))
                   {:keys [parked]} (call)]
               (tools/decide-approval! (:interrupt-id parked) :approved {})
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
               (is (= {:model "alpha-big"} (select-keys (:override a) [:model]))
                   "the first change's override is the full session slice")
               (is (= "low" (get-in (:override b) [:reasoning-effort]))
                   "the second change's override reflects the latest session state"))))
         (finally (stop) (providers/set-override! id nil)))))))

(deftest a-vendor-switch-land-as-a-changed-line-that-moved-the-endpoint
  ;; The end-to-end proof of the feature: an agent naming a vendor gets that
  ;; vendor's ENDPOINT, and the change line says so. Under the old shape this
  ;; call was accepted, approved, and changed nothing -- the log line even
  ;; recorded {:before {} :after {}}.
  (with-resolved-config
   [{:content "hello"}]
   (fn []
     (let [id   "http-vendor"
           stop (http/start! {:port 8103})]
       (try
         (io/delete-file (io/file (log-dir) (str id ".jsonl")) true)
         (let [call (fn [] (tools/run! {:id "vsw" :type "function"
                                        :function {:name "session-configure"
                                                   :arguments (json/write-str {:provider "beta"})}}
                                       id))
               {:keys [parked]} (call)]
           (tools/decide-approval! (:interrupt-id parked) :approved {})
           (call))
         (testing "the session's served endpoint moved with the vendor"
           (let [a (providers/active-provider id)]
             (is (= :beta (:provider a)))
             (is (= "https://y/v1" (:base-url a)))
             (is (= "beta-plain" (:model a)) "and its default model came along")))
         (post-run 8103 id)
         (let [lines (wait-for-recorded
                      (io/file (log-dir) (str id ".jsonl"))
                      (fn [ls] (some #(= "provider/changed" (:kind %)) ls))
                      2000)
               changed (:payload (first (filter #(= "provider/changed" (:kind %)) lines)))]
           (is (= "beta" (get-in changed [:after :provider]))
               "the change line names the vendor that was selected")
           (is (not= {} (:after changed))
               "and is not an empty change -- which is what the old shape wrote")
           (is (= "https://y/v1" (get-in changed [:resolved :base-url]))
               "with the endpoint that vendor resolves to")
           (is (= "beta-plain" (get-in changed [:resolved :model])))
           (testing "and the counts moved with the model, not left at alpha's"
             (is (= 128000 (get-in changed [:resolved :context-window])))
             (is (= 4096 (get-in changed [:resolved :max-output-tokens])))))
         (finally (stop) (providers/set-override! id nil)))))))

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

;; ------------------------------------------------- the management edge

(def ^:private project-dir
  (str (System/getProperty "java.io.tmpdir") "/harness-http-project"))

(def ^:private project-dir-2
  (str (System/getProperty "java.io.tmpdir") "/harness-http-project-2"))

(defn- bound-lines
  "The thread's project/bound audit lines, oldest first."
  [tid]
  (->> (str/split-lines
        (slurp (io/file (log-dir) (str tid ".jsonl")) :encoding "UTF-8"))
       (mapv #(json/read-str % :key-fn keyword))
       (filterv #(= "project/bound" (:kind %)))))

(defn- api-call
  "A plain JSON call to the management edge -- the /api/* endpoints, not the
  AG-UI run endpoint. Returns the raw HttpResponse."
  [port method path body]
  (let [b (.header (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                   "Content-Type" "application/json")
        b (if (= :post method)
            (.POST b (HttpRequest$BodyPublishers/ofString (str body) StandardCharsets/UTF_8))
            (.GET b))]
    (.send (HttpClient/newHttpClient) (.build b)
           (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))))

(defn- read-json [resp]
  (json/read-str (.body resp) :key-fn keyword))

;; The scripted run that proves a bound thread's relative write lands in the
;; project: turn one writes a RELATIVE path, turn two replies.
(def ^:private bound-script
  [{:content "" :tool-calls [{:id "c1" :name "write"
                              :arguments {:path "e2e.txt" :content "landed"}}]}
   {:content "done"}])

(deftest the-project-endpoint-binds-records-and-refuses-cleanly
  ;; The management edge itself: bind through /api/project, see the binding on
  ;; GET, see exactly one project/bound audit line on disk -- and watch every
  ;; bad input leave no trace at all.
  (io/delete-file project-dir true)
  (.mkdirs (io/file project-dir))
  (with-server
   8103
   "it-proj"
   (fn []
     (let [tid (str "proj-" (java.util.UUID/randomUUID))]
       (testing "an unbound thread answers with dir nil, not an error"
         (let [resp (api-call 8103 :get (str "/api/project?threadId=" tid) nil)]
           (is (= 200 (.statusCode resp)))
           (is (= {:threadId tid :dir nil} (read-json resp)))
           (is (= ui-origin (header resp "Access-Control-Allow-Origin")))))
       (testing "a GET without threadId is a 400"
         (is (= 400 (.statusCode (api-call 8103 :get "/api/project" nil)))))
       (testing "binding a real directory answers with its absolute path"
         (let [resp  (api-call 8103 :post "/api/project"
                               (json/write-str {:threadId tid :dir project-dir}))
               reply (read-json resp)]
           (is (= 200 (.statusCode resp)))
           (is (.isAbsolute (io/file (:dir reply))))
           (is (str/ends-with? (:dir reply) "harness-http-project"))
           (testing "a GET now sees the binding"
             (is (= (:dir reply)
                    (:dir (read-json (api-call 8103 :get (str "/api/project?threadId=" tid) nil))))))))
       (testing "binding a missing directory is a NAMED 400"
         (let [resp (api-call 8103 :post "/api/project"
                              (json/write-str {:threadId tid :dir (str project-dir "/nope")}))
               reply (read-json resp)]
           (is (= 400 (.statusCode resp)))
           (is (str/includes? (:error reply) "no such directory"))))
       (testing "a malformed body is a 400"
         (is (= 400 (.statusCode (api-call 8103 :post "/api/project" "{not json")))))
       (let [lines (mapv #(json/read-str % :key-fn keyword)
                         (str/split-lines
                          (slurp (io/file (log-dir) (str tid ".jsonl")) :encoding "UTF-8")))
             bound (filterv #(= "project/bound" (:kind %)) lines)]
         (testing "exactly ONE project/bound audit line is on disk"
           (is (= 1 (count bound)))
           (is (nil? (:runId (first bound))) "a binding happens outside any run")
           (is (nil? (get-in (first bound) [:payload :before]))
               "a FIRST bind has no previous directory")
           (is (str/ends-with? (get-in (first bound) [:payload :after]) "harness-http-project"))
           (is (= "http" (get-in (first bound) [:payload :via]))))
         (testing "the two failed binds added no second line"
           (is (= 1 (count (filter #(= "project/bound" (:kind %)) lines))))))))))

(deftest the-model-endpoint-answers-what-this-session-can-send
  ;; The capability endpoint. A client asks what this session is served by and
  ;; what that model accepts, so it can decide whether to offer an image picker.
  ;;
  ;; Through REAL resolution rather than a pin, because the answer is precisely
  ;; the resolution's output -- a pinned provider would make this pass while the
  ;; endpoint reported nothing.
  (with-resolved-config
   [{:content "hello"}]
   (fn []
     (let [id   "http-model"
           stop (http/start! {:port 8087})]
       (try
         (testing "the default: the selection and what the catalog resolved it to"
           (let [resp (api-call 8087 :get (str "/api/model?threadId=" id) nil)
                 body (read-json resp)]
             (is (= 200 (.statusCode resp)))
             (is (= "alpha" (:provider body)))
             (is (= "alpha-small" (:model body)))
             (is (= "https://x/v1" (:base-url body)) "the resolved endpoint")
             (is (= ["image" "text"] (:input body)) "the modalities, sorted, as wire strings")
             (is (= ["text"] (:output body)))
             (testing "and the model's two counts, as NUMBERS rather than strings"
               (is (= 200000 (:context-window body)))
               (is (= 8192 (:max-output-tokens body)))
               (is (number? (:context-window body)))
               (is (number? (:max-output-tokens body))))
             (testing "and no api-key at any depth"
               (is (not-any? #(str/includes? (str %) "api-key")
                             (tree-seq coll? seq body))))))
         (testing "a session can move to a text-only model and the answer follows"
           (providers/set-override! id {:provider :beta})
           (let [body (read-json (api-call 8087 :get (str "/api/model?threadId=" id) nil))]
             (is (= "beta" (:provider body)))
             (is (= "beta-plain" (:model body)))
             (is (= "https://y/v1" (:base-url body)) "the endpoint followed the vendor")
             (is (= ["text"] (:input body)) "and the capability is the new model's")
             (testing "including the counts -- proving they are read from the catalog,
                       not written into this endpoint"
               (is (= 128000 (:context-window body)))
               (is (= 4096 (:max-output-tokens body))))))
         (testing "a model that declares no counts reports NONE -- absent, not null"
           (providers/set-override! id {:provider :alpha :model "alpha-bare"})
           (let [body (read-json (api-call 8087 :get (str "/api/model?threadId=" id) nil))]
             (is (= "alpha-bare" (:model body)))
             (is (not (contains? body :context-window))
                 "silence about a number is a fact, not a zero")
             (is (not (contains? body :max-output-tokens)))))
         (testing "an unbound/unknown thread is still an answer, not a 400"
           (let [resp (api-call 8087 :get "/api/model?threadId=who-is-this" nil)
                 body (read-json resp)]
             (is (= 200 (.statusCode resp)))
             (is (= "alpha-small" (:model body))
                 "the default tier resolves for any thread with no session in play")))
         (testing "a missing threadId is an answer too -- the process-wide slot"
           (is (= 200 (.statusCode (api-call 8087 :get "/api/model" nil))))
           (is (= ["image" "text"] (:input (read-json (api-call 8087 :get "/api/model" nil))))))
         (testing "it is READ-ONLY: no audit line of its own"
           (let [f (io/file (log-dir) (str id ".jsonl"))]
             (is (not (.exists f))
                 "asking a question must not write to the session's log")))
         (finally (stop) (providers/set-override! id nil)))))))

(deftest the-model-endpoint-reports-a-sparse-configuration-as-sparse
  ;; Absent is a fact, not a failure. A provider described inline that declared no
  ;; modalities has nothing to report, and saying so beats inventing a default or
  ;; returning an error the client has to interpret.
  (with-server
   8086
   "sparse"
   (fn []
     (let [tid  (str "sparse-" (java.util.UUID/randomUUID))
           ;; The seeded test config IS the inline form, and it declares nothing
           ;; -- which is exactly the sparse case.
           body (read-json (api-call 8086 :get (str "/api/model?threadId=" tid) nil))]
       (is (= "seeded" (:model body)))
       (is (= "fake" (:protocol body)))
       (is (not (contains? body :input)) "nothing was declared, so nothing is claimed")
       (is (not (contains? body :output)))
       (is (not (contains? body :provider)) "and no provider was named")))))

(deftest the-directory-picker-answers-but-binds-nothing
  ;; The picker exists because a browser has no absolute path to give: the file
  ;; dialog runs on the machine the harness lives on. It is a FILLER, not a
  ;; second way to mutate a binding -- so the binding it feeds is asserted to
  ;; be the ordinary POST's doing, with the ordinary audit line.
  ;; The chooser is stubbed for the whole test: the real one opens a window and
  ;; waits for a human, which a test run must never do. `alter-var-root`, NOT
  ;; `binding` -- a dynamic binding does not cross threads, and the server that
  ;; calls the chooser is running on its own thread, so a `binding` here would
  ;; leave the real dialog wired up and the stub silently unused (the config-home
  ;; ticket hit exactly this; see test_runner's root override).
  (doseq [d [project-dir-2]]
    (doseq [f (reverse (file-seq (io/file d)))]
      (io/delete-file f true))
    (.mkdirs (io/file d)))
  (let [stub! (fn [f] (alter-var-root #'http/*directory-chooser* (constantly f)))
        real  http/*directory-chooser*]
    (try
      (with-server
       8111
       "it-pick"
       (fn []
         (let [tid (str "pick-" (java.util.UUID/randomUUID))]
           (testing "picking answers the chosen absolute path"
             (stub! (fn [] project-dir-2))
             (let [resp (api-call 8111 :post "/api/project/pick" nil)]
               (is (= 200 (.statusCode resp)))
               (is (= ui-origin (header resp "Access-Control-Allow-Origin")))
               (is (= project-dir-2 (:dir (read-json resp))))))
           (testing "cancelling is an answer (dir nil), not an error"
             (stub! (fn [] nil))
             (let [resp (api-call 8111 :post "/api/project/pick" nil)]
               (is (= 200 (.statusCode resp)))
               (is (= {:dir nil} (read-json resp)))))
           (testing "a blank answer reads as a cancel too"
             (stub! (fn [] "   "))
             (is (= {:dir nil} (read-json (api-call 8111 :post "/api/project/pick" nil)))))
           (testing "GET is refused -- this call opens a window, so it is not cacheable"
             (is (= 405 (.statusCode (api-call 8111 :get "/api/project/pick" nil)))))
           (testing "picking binds NOTHING: the ordinary POST is still the only route"
             (stub! (fn [] project-dir-2))
             ;; Compare directories by IDENTITY, not by spelling. Two honest
             ;; differences are in play: `java.io.tmpdir` ends in a slash on
             ;; macOS, so the constant spells a doubled one and bind! normalizes
             ;; it; and /var is a symlink to /private/var, which canonicalization
             ;; chases and `bind!` deliberately does not (it keeps what was asked
             ;; for -- see harness.project/absolute). Neither is what this test is
             ;; about; the session landing in that directory is.
             (let [canonical (fn [p] (.getCanonicalPath (io/file p)))
                   picked    (:dir (read-json (api-call 8111 :post "/api/project/pick" nil)))]
               (is (= (canonical project-dir-2) (canonical picked))
                   "the picker hands back the directory it was told to")
               (is (nil? (project/binding-for tid)) "no binding until the POST lands")
               (is (= 200 (.statusCode (api-call 8111 :post "/api/project"
                                                 (json/write-str {:threadId tid :dir picked})))))
               (is (= (canonical picked) (canonical (project/binding-for tid)))
                   "binding lands the session in that same directory")
               (let [bounds (bound-lines tid)]
                 (is (= 1 (count bounds)) "exactly one audit line, from the POST")
                 (is (= "http" (get-in (first bounds) [:payload :via])))))))))
      (finally (alter-var-root #'http/*directory-chooser* (constantly real))))))

(deftest rebinding-moves-the-root-and-lands-a-timeline
  ;; Ticket 04: rebinding an already-bound thread is the ordinary case --
  ;; resolution moves to the new directory immediately (the relative write
  ;; lands THERE, not in the old one), GET answers the new binding, and each
  ;; audit line carries before -> after so the directory timeline reads
  ;; straight off the log. The UI switches with the same entry point: this
  ;; endpoint IS the entry point it uses.
  ;; Recursive wipe, not plain delete-file: a directory survives a previous
  ;; JVM with its e2e.txt inside, delete-file silently refuses non-empty
  ;; directories, and the not-in-the-OLD-directory assertion would trip on
  ;; that residue (deep-to-shallow file-seq delete, then mkdirs).
  (doseq [d [project-dir project-dir-2]]
    (doseq [f (reverse (file-seq (io/file d)))]
      (io/delete-file f true))
    (.mkdirs (io/file d)))
  (with-server
   8107
   {"rebind-run" bound-script}
   (fn []
     (let [tid "rebind-run"]
       (testing "the first bind's line carries before nil"
         (let [resp (api-call 8107 :post "/api/project"
                              (json/write-str {:threadId tid :dir project-dir}))]
           (is (= 200 (.statusCode resp))))
         (let [line (first (bound-lines tid))]
           (is (nil? (get-in line [:payload :before])))
           (is (str/ends-with? (get-in line [:payload :after]) "harness-http-project"))))
       (testing "rebinding answers and displays the new directory"
         (let [resp (api-call 8107 :post "/api/project"
                              (json/write-str {:threadId tid :dir project-dir-2}))]
           (is (= 200 (.statusCode resp)))
           (is (str/ends-with? (:dir (read-json resp)) "harness-http-project-2"))
           (testing "GET reflects the switch"
             (is (str/ends-with?
                  (:dir (read-json (api-call 8107 :get (str "/api/project?threadId=" tid) nil)))
                  "harness-http-project-2")))))
       (testing "a relative write after the switch lands in the NEW directory"
         (let [frames (wire/frames-from-sse (.body (post-run 8107 tid)))]
           (is (= "RUN_FINISHED" (:type (last frames))))
           (is (empty? (wire/violations frames))))
         (is (= "landed" (slurp (io/file project-dir-2 "e2e.txt") :encoding "UTF-8")))
         (is (not (.exists (io/file project-dir "e2e.txt")))))
       (testing "the log reads as a before -> after timeline"
         (let [bounds (bound-lines tid)]
           (is (= 2 (count bounds)))
           (is (str/ends-with? (get-in (nth bounds 1) [:payload :before]) "harness-http-project"))
           (is (str/ends-with? (get-in (nth bounds 1) [:payload :after]) "harness-http-project-2"))))))))

(deftest a-bound-thread-writes-into-its-project-over-the-real-edge
  ;; The full vertical: bind through the management edge the way the UI will,
  ;; then a real AG-UI run whose scripted tool call writes a RELATIVE path --
  ;; which must land inside the project directory, not the process cwd.
  (io/delete-file project-dir true)
  (.mkdirs (io/file project-dir))
  (with-server
   8104
   {"proj-run" bound-script}
   (fn []
     (let [resp (api-call 8104 :post "/api/project"
                          (json/write-str {:threadId "proj-run" :dir project-dir}))]
       (is (= 200 (.statusCode resp))))
     (let [frames (wire/frames-from-sse (.body (post-run 8104 "proj-run")))]
       (testing "the run itself completed normally"
         (is (= "RUN_FINISHED" (:type (last frames))))
         (is (empty? (wire/violations frames)))))
     (testing "the relative write landed INSIDE the project directory"
       (is (= "landed" (slurp (io/file project-dir "e2e.txt") :encoding "UTF-8")))))))

(deftest threads-listing-and-rebuild-over-the-real-edge
  ;; Ticket 05 over the real edge: a real run writes a real log; the listing
  ;; finds it with its metadata; the rebuild endpoint hands the conversation
  ;; back in the shape a client re-owns; and the action leaves its audit line.
  ;; The pin is keyed to the RANDOM thread id the run will use -- a pin under
  ;; any other name would let the run resolve config's bare :fake provider and
  ;; produce an empty stream (the per-thread-pin rule, learned the hard way).
  (let [tid (str "rebuild-" (java.util.UUID/randomUUID))]
    (with-server
     8105
     {tid script}
     (fn []
       ;; The log: one real run of the default script -- reasoning, two tool
       ;; calls, tool results, a final answer.
       (let [frames (wire/frames-from-sse (.body (post-run 8105 tid)))]
         (is (= "RUN_FINISHED" (:type (last frames)))))
       (testing "GET /api/threads lists the conversation with its metadata"
         (let [resp (api-call 8105 :get "/api/threads" nil)
               rows (->> (json/read-str (.body resp) :key-fn keyword)
                         (filterv #(= tid (:threadId %))))]
           (is (= 200 (.statusCode resp)))
           (is (= ui-origin (header resp "Access-Control-Allow-Origin")))
           (is (= 1 (count rows)))
           (is (pos? (:bytes (first rows))))
           (is (pos? (:lastActivity (first rows))))))
       (testing "POST rebuild returns a continuable message list"
         (let [resp  (api-call 8105 :post (str "/api/threads/" tid "/rebuild") nil)
               reply (json/read-str (.body resp) :key-fn keyword)]
           (is (= 200 (.statusCode resp)))
           (is (= tid (:threadId reply)))
           (is (= [] (:context reply)) "the seed run carried no context")
           (let [msgs (:messages reply)]
             (testing "the client's seed message opens the list"
               (is (= "user" (:role (first msgs)))))
             (testing "reasoning, tool calls, tool results are all there"
               (is (some #(= "reasoning" (:role %)) msgs))
               (is (some #(seq (:toolCalls %)) msgs))
               (is (some #(= "tool" (:role %)) msgs)))
             (testing "and the final assistant answer closes it"
               (is (= "assistant" (:role (last msgs))))))))
       (testing "the rebuild action landed its audit line"
         (let [lines (mapv #(json/read-str % :key-fn keyword)
                           (str/split-lines
                            (slurp (io/file (log-dir) (str tid ".jsonl")) :encoding "UTF-8")))
               rb    (filterv #(= "session/rebuilt" (:kind %)) lines)]
           (is (= 1 (count rb)))
           (is (nil? (:runId (first rb))) "a rebuild happens outside any run")
           (is (pos? (get-in (first rb) [:payload :messages])))
           (is (= "http" (get-in (first rb) [:payload :via])))))))))

(deftest rebuild-refuses-truncated-and-corrupt-logs-by-name
  (with-server
   8106
   "it-refuse"
   (fn []
     (testing "a log that ends mid-run is refused, naming the last frame"
       (let [tid (str "trunc-" (java.util.UUID/randomUUID))
             f   (io/file (log-dir) (str tid ".jsonl"))]
         (spit f (str (json/write-str
                       {:ts 1 :runId "r1" :kind "input"
                        :payload {:threadId tid :runId "r1"
                                  :messages [{:id "u1" :role "user" :content "hi"}]
                                  :tools [] :context []}})
                      "\n"
                      (json/write-str
                       {:ts 2 :runId "r1" :kind "event"
                        :payload {:type "RUN_STARTED" :threadId tid :runId "r1"}})
                      "\n")
               :encoding "UTF-8")
         (let [resp  (api-call 8106 :post (str "/api/threads/" tid "/rebuild") nil)
               reply (json/read-str (.body resp) :key-fn keyword)]
           (is (= 400 (.statusCode resp)))
           (is (re-find #"(?i)mid-run|RUN_FINISHED|RUN_ERROR" (:error reply)))
           (testing "the refusal left no audit line behind"
             (is (not-any? #(= "session/rebuilt" (:kind %))
                           (mapv #(json/read-str % :key-fn keyword)
                                 (str/split-lines (slurp f :encoding "UTF-8")))))))))
     (testing "a half-written line is refused, naming the line"
       (let [tid (str "corrupt-" (java.util.UUID/randomUUID))]
         (spit (io/file (log-dir) (str tid ".jsonl"))
               "{\"ts\":1,\"runId\":\"r1\",\"kin" :encoding "UTF-8")
         (let [resp  (api-call 8106 :post (str "/api/threads/" tid "/rebuild") nil)
               reply (json/read-str (.body resp) :key-fn keyword)]
           (is (= 400 (.statusCode resp)))
           (is (re-find #"line 1" (:error reply))))))
     (testing "a thread with no log at all is refused, naming the thread"
       (let [resp  (api-call 8106 :post "/api/threads/no-such-thread-xyz/rebuild" nil)
             reply (json/read-str (.body resp) :key-fn keyword)]
         (is (= 400 (.statusCode resp)))
         (is (str/includes? (:error reply) "no-such-thread-xyz")))))))

;; ------------------------------------------------- skills and instructions, end to end

(def ^:private skill-script
  [{:content ""
    :tool-calls [{:id "s1" :name "skill" :arguments {:name "alpha"}}]}
   {:content "followed it"}])

(defn- wipe-conventions! []
  (io/delete-file (io/file (home/user-home) ".agents") true)
  (io/delete-file (io/file (home/user-home) "AGENTS.md") true))

(deftest an-opening-block-reaches-the-model-and-never-the-client
  ;; The whole shape, through the real edge: the instruction files and the skills
  ;; catalog are in the RUN's message record (the model reads them) and absent
  ;; from every AG-UI frame (the client never does). "The front end shows
  ;; nothing" is not a filtering decision anywhere -- it is this.
  (let [proj      (str (System/getProperty "java.io.tmpdir")
                       "/harness-http-skills-" (System/nanoTime))
        skill-dir (str (io/file (home/user-home) ".agents" "skills" "alpha"))]
    (.mkdirs (io/file proj))
    (.mkdirs (io/file skill-dir))
    (spit (str (io/file (home/user-home) "AGENTS.md")) "STANDING RULE\n" :encoding "UTF-8")
    (spit (str (io/file proj "AGENTS.md")) "PROJECT RULE\n" :encoding "UTF-8")
    (spit (str skill-dir "/SKILL.md")
          "---\nname: alpha\ndescription: alpha does a thing\n---\n\nALPHA BODY\n"
          :encoding "UTF-8")
    (project/bind! "it-skills" proj)
    (try
      (with-server
       8112 "it-skills" skill-script
       (fn []
         (let [resp      (.body (post-run 8112 "it-skills"))
               frames    (wire/frames-from-sse resp)
               lines     (wait-for-recorded
                          (str (io/file (log-dir) "it-skills.jsonl"))
                          (fn [ls] (some #(and (= "message" (:kind %))
                                               (= "followed it" (get-in % [:payload :content])))
                                         ls))
                          2000)
               texts     (mapv #(str (get-in % [:payload :content]))
                               (filter #(= "message" (:kind %)) lines))
               wire-text (json/write-str frames)]

           (testing "the model is handed the rules and then the catalog, in that order"
             (let [user-texts (mapv #(get-in % [:payload :content])
                                    (filter #(and (= "message" (:kind %))
                                                  (= "user" (get-in % [:payload :role])))
                                            lines))]
               (is (str/includes? (first user-texts) "STANDING RULE"))
               (is (str/includes? (second user-texts) "PROJECT RULE"))
               (is (str/starts-with? (nth user-texts 2) "<skills>"))
               (is (str/includes? (nth user-texts 2) "- alpha: alpha does a thing"))))

           (testing "loading it mid-run puts the BODY into the conversation"
             (is (some #(and (str/includes? % "ALPHA BODY")
                             (str/starts-with? % "<skill name=\"alpha\">"))
                       texts)))

           (testing "and not one frame carries any of it -- a client cannot draw what it never receives"
             (is (not (str/includes? wire-text "STANDING RULE")))
             (is (not (str/includes? wire-text "PROJECT RULE")))
             (is (not (str/includes? wire-text "ALPHA BODY")))
             (is (not (str/includes? wire-text "- alpha:"))))

           (testing "the skill call itself IS on the wire, as an ordinary tool card"
             (is (some #(= "skill" (:toolCallName %))
                       (filter #(= "TOOL_CALL_START" (:type %)) frames)))))))
      (finally
        (project/bind! "it-skills" nil)
        (io/delete-file (io/file proj) true)
        (wipe-conventions!)))))

(deftest an-unreadable-instruction-file-stops-the-run-by-name
  ;; The contrast with a broken skill, asserted where it matters: at the edge, as
  ;; a RUN_ERROR the client sees, rather than a silently rule-less run.
  (let [f (io/file (home/user-home) "AGENTS.md")]
    (.mkdirs (io/file (home/user-home)))
    (spit (str f) "rules\n" :encoding "UTF-8")
    (.setReadable f false false)
    (try
      (if (.canRead f)
        (is true "permission bits do not apply to this user; nothing to assert")
        (with-server
         8113 "it-badrules" script
         (fn []
           (let [frames (wire/frames-from-sse (.body (post-run 8113 "it-badrules")))]
             (testing "the client gets a terminated run carrying the reason"
               (is (= "RUN_ERROR" (:type (last frames))))
               (is (str/includes? (str (:message (last frames)))
                                  "cannot read the instruction file")))
             (testing "and no LLM call was made at all"
               (is (not-any? #(= "TOOL_CALL_START" (:type %)) frames)))))))
      (finally (wipe-conventions!)))))
