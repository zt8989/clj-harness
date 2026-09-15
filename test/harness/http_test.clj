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
  "Where the server under test writes its logs, for a thread with NO project
  binding: the tree's reserved workspace.

  Derived from harness.home, so it follows the config root -- which the test
  fixture rebinds to a temp directory, so these tests never touch the real one.
  Almost every thread in this namespace is unbound (the routes under test are not
  the project one), so this is where their logs land; a test that BINDS a project
  computes that project's workspace itself, because that is the thing it is
  checking."
  []
  (str (io/file (home/projects-dir) http/unbound-workspace)))

(defn- log-dir-for
  "The workspace THREAD-ID's log is in, asked the way the server asks it: the
  session's project identity, sanitized, or the reserved workspace. For a thread
  whose binding changes mid-test, ask this AFTER the change -- which is exactly
  the behaviour under test in the project routes."
  [thread-id]
  (str (io/file (home/projects-dir)
                (if-let [identity (project/identity-for thread-id)]
                  (home/sanitize identity)
                  http/unbound-workspace))))

(defn- log-file
  "The file an UNBOUND thread's log lives in -- which is every thread in this
  namespace except the ones the project routes bind. Derived the way the writer
  derives it rather than hard-coded, so a change to the naming rule shows up
  here as a failure instead of a test that agrees with itself."
  [thread-id]
  (home/log-file (log-dir) thread-id))

(defn- log-file-for
  "The file THREAD-ID's log is in, wherever its binding says that is. The only
  difference from log-file is the workspace, and that difference is the thing the
  project routes are tested for."
  [thread-id]
  (home/log-file (log-dir-for thread-id) thread-id))

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
     (io/delete-file (log-file "it-1") true)
     (post-run 8098 "it-1")
     (let [f     (log-file "it-1")
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
     (let [log (log-file "replay-e2e")]
       (io/delete-file log true)
       (post-run 8095 "replay-e2e")
       (wait-for-recorded
        log
        (fn [ls] (and (some #(= "provider/init" (:kind %)) ls)
                      (>= (count (filter #(= "message" (:kind %)) ls)) 4)))
        3000)
       (let [history (replay/history (log-file "replay-e2e"))]
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
     (let [log    (log-file "images")
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
           (let [history (replay/history (log-file "images"))
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
     (io/delete-file (log-file "lifecycle") true)
     (post-run 8094 "lifecycle")
     (let [f     (log-file "lifecycle")
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
  answered. A resume request carries it back, the way the browser client does."
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
                          (log-file "http-approve")
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
  ;; The config files live in the HOME, not beside the logs; ask harness.home for
  ;; it rather than walking up from the log directory, whose depth is the tree's
  ;; business and has already changed once.
  (let [cfg-file (home/config-file)
        reg-file (home/providers-file)
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
         (io/delete-file (log-file id) true)
         (let [e (error (.body (post-run 8089 id {:provider {:context-window 200000}})))]
           (is (some? e) "the run is terminated rather than served with the field dropped")
           (is (str/includes? (:message e) "context-window") "the field is named")
           (is (str/includes? (:message e) "providers.edn")
               "and the run says where it belongs instead"))
         (testing "and nothing was resolved or recorded for it"
           (is (nil? (providers/override-for id)))
           (let [lines (str/split-lines (slurp (log-file id) :encoding "UTF-8"))]
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
         (io/delete-file (log-file id) true)
         ;; Run one: the init line lands. The scripted turn is a plain reply,
         ;; so the run does not touch the provider.
         (post-run 8101 id)
         (let [after-first (wait-for-recorded
                            (log-file id)
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
                               (log-file id)
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
         (io/delete-file (log-file id) true)
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
                      (log-file id)
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
                          (log-file id)
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
         (io/delete-file (log-file id) true)
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
                      (log-file id)
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
  "The thread's project/bound audit lines, oldest first, read from wherever the
  thread's log IS.

  Since the logs became a projects tree, a bound thread's file lives in its
  project's workspace -- which is why the audit line for a bind is written INTO
  the new directory: the bind moved the session, and the very next line it writes
  is already in the place it moved to. So this asks the same question the server
  asks (harness.project/identity-for, then sanitize), rather than assuming the
  reserved workspace. A test that assumed would pass while the audit trail was in
  the wrong project."
  [tid]
  (->> (str/split-lines
        (slurp (log-file-for tid) :encoding "UTF-8"))
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
       (let [bound (bound-lines tid)]
         (testing "exactly ONE project/bound audit line is on disk"
           (is (= 1 (count bound)))
           (is (nil? (:runId (first bound))) "a binding happens outside any run")
           (is (nil? (get-in (first bound) [:payload :before]))
               "a FIRST bind has no previous directory")
           (is (str/ends-with? (get-in (first bound) [:payload :after]) "harness-http-project"))
           (is (= "http" (get-in (first bound) [:payload :via]))))
         (testing "the two failed binds added no second line"
           (is (= 1 (count (filter #(= "project/bound" (:kind %)) bound)))))
         (testing "and the line landed in the workspace the bind moved the session to"
           ;; The audit line is written AFTER the bind, so it is the first thing
           ;; this session writes in its new home -- which is the whole reason the
           ;; log is moved rather than left behind: a session's timeline is one file.
           (is (empty? (filter #(= "project/bound" (:kind %))
                               (try (mapv #(json/read-str % :key-fn keyword)
                                          (str/split-lines
                                           (slurp (log-file tid) :encoding "UTF-8")))
                                    (catch java.io.FileNotFoundException _ []))))
               "the reserved workspace holds no binding line for this thread")))))))

(def ^:private listing-dir
  ;; Its own directory pair, not the shared project-dir: other tests in this
  ;; namespace bind sessions into those, and this one asserts on the WHOLE content
  ;; of a project's session list. Sharing would make it pass or fail depending on
  ;; which tests ran first.
  (str (System/getProperty "java.io.tmpdir") "/harness-http-listing"))

(def ^:private listing-dir-2 (str listing-dir "-2"))

(def ^:private archive-dir
  ;; Its own pair as well -- see listing-dir's note. This test asserts on the whole
  ;; session list of a project, so a shared directory would make it pass or fail
  ;; depending on which test ran first.
  (str (System/getProperty "java.io.tmpdir") "/harness-http-archive"))

(def ^:private archive-dir-2 (str archive-dir "-2"))

(deftest the-projects-listing-joins-the-store-with-the-disk
  ;; Ticket 04's data source. The sidebar cannot be built from either side alone:
  ;; the store says which projects and sessions exist and which are archived, the
  ;; tree says how big each log is and when it last changed. So this checks the
  ;; join -- and the states that only exist BECAUSE the two sides are different:
  ;; a session in the store whose file is not there, and a log in the tree that no
  ;; session owns.
  (doseq [d [listing-dir listing-dir-2]]
    (run! #(io/delete-file % true) (reverse (file-seq (io/file d))))
    (.mkdirs (io/file d)))
  (with-server
   8110
   {"listing-a" script "listing-b" script "listing-unbound" script}
   (fn []
     (let [list-projects (fn [] (json/read-str (.body (api-call 8110 :get "/api/projects" nil))
                                               :key-fn keyword))
           project-named (fn [dir]
                           ;; By last path segment, matched EXACTLY: `-2` is a
                           ;; different project, and "ends with" would confuse the
                           ;; two -- which is the collision sanitize exists to avoid.
                           (let [want (last (str/split (.getCanonicalPath (io/file dir)) #"/"))]
                             (first (filterv #(= want (last (str/split (:path %) #"/")))
                                             (list-projects)))))
           row-for       (fn [dir tid]
                           (->> (:sessions (project-named dir))
                                (filter #(= tid (:threadId %)))
                                first))
           bind!         (fn [tid dir]
                           (api-call 8110 :post "/api/project"
                                     (json/write-str {:threadId tid :dir dir})))]
       (testing "before anything is bound, this directory is not a project at all"
         (is (nil? (project-named listing-dir))))
       (testing "binding a session makes a project row for its directory"
         (is (= 200 (.statusCode (bind! "listing-a" listing-dir))))
         (let [project (project-named listing-dir)
               session (row-for listing-dir "listing-a")]
           (is (= (.getCanonicalPath (io/file listing-dir)) (:path project))
               "the project is named by its canonical path -- its identity, not a spelling")
           (is (some? (:projectId project)))
           (is (= "listing-a" (:threadId session)))
           (is (false? (:archived session)))
           (testing "and the bind itself wrote the session's first line -- its own audit trail"
             ;; Worth locking, because it is WHY a freshly bound session already has
             ;; a file: the audit line lands in the workspace the bind just created
             ;; (see project-post's ordering note). So the disk facts are present.
             (is (pos? (:bytes session)))
             (is (= (.length (log-file-for "listing-a")) (:bytes session))))))
       (testing "after a real run the facts follow the file, read fresh from disk"
         (let [before (:bytes (row-for listing-dir "listing-a"))]
           (is (= "RUN_FINISHED"
                  (:type (last (wire/frames-from-sse (.body (post-run 8110 "listing-a")))))))
           (let [session (row-for listing-dir "listing-a")
                 f       (log-file-for "listing-a")]
             (is (< before (:bytes session)) "the run appended to the same file")
             (is (= (.length f) (:bytes session)))
             (is (= (.lastModified f) (:lastActivity session))))))
       (testing "a session whose file is NOT there is still a row, with null facts"
         ;; The state the sidebar must survive: the store says this session exists,
         ;; the disk says nothing about it. Null is the honest answer and it is NOT
         ;; a zero-byte file -- a real 0-byte log would be a broken one.
         (bind! "listing-b" listing-dir)
         (let [f (log-file-for "listing-b")]
           (is (.delete f) "the file is removed by hand, as a person tidying the tree would")
           (let [session (row-for listing-dir "listing-b")]
             (is (= "listing-b" (:threadId session)) "the row is still there")
             (is (nil? (:bytes session)))
             (is (nil? (:lastActivity session)))
             (testing "and it sorts FIRST, because 'no log yet' is the newest state there is"
               (is (= ["listing-b" "listing-a"]
                      (map :threadId (:sessions (project-named listing-dir)))))))))
       (testing "a run does NOT outrank a session that has never started"
         ;; The rule is 'no log sorts first', not 'newest file wins', because a
         ;; session with no log is one that has not started -- which is newer than
         ;; any activity, however recent. So a just-created session stays at the
         ;; top of its project instead of sinking under a busy conversation.
         (Thread/sleep 20)
         (post-run 8110 "listing-a")
         (is (= ["listing-b" "listing-a"]
                (map :threadId (:sessions (project-named listing-dir))))))
       (testing "a different directory is a different project"
         (bind! "listing-other" listing-dir-2)
         (is (= (.getCanonicalPath (io/file listing-dir-2)) (:path (project-named listing-dir-2))))
         (is (= 1 (count (:sessions (project-named listing-dir-2))))))
       (testing "an unbound session is in no project -- there is no row to put it in"
         (is (nil? (row-for listing-dir "not-bound-anywhere"))))
       (testing "and an unbound session that RUNS is in no project either"
         ;; It has a log, in the reserved workspace. GET /api/threads is the raw
         ;; tree view that shows it; the sidebar's listing is about projects, and a
         ;; session nobody owns has no project to be listed under.
         (is (= "RUN_FINISHED"
                (:type (last (wire/frames-from-sse (.body (post-run 8110 "listing-unbound")))))))
         (is (not-any? #(= "listing-unbound" (:threadId %))
                       (mapcat :sessions (list-projects))))
         (is (some #(= "listing-unbound" (:threadId %))
                   (json/read-str (.body (api-call 8110 :get "/api/threads" nil)) :key-fn keyword))
             "and the raw tree view still shows it, so nothing is hidden, only unowned"))))))

(deftest archiving-a-session-is-a-row-write-and-never-a-file-write
  ;; Ticket 06. The whole claim is a NEGATIVE one -- archiving touches no log --
  ;; so the assertions are the two disk numbers, taken around the call. A route
  ;; that helpfully wrote an audit line (the habit every other mutating route here
  ;; has) would still flip the flag and still answer 200; only those two numbers
  ;; catch it.
  ;;
  ;; Its own directory pair, for the same reason the listing test has one: this
  ;; asserts on the WHOLE content of a project's session list, so a shared
  ;; directory would make it pass or fail depending on which test ran first.
  (doseq [d [archive-dir archive-dir-2]]
    (run! #(io/delete-file % true) (reverse (file-seq (io/file d))))
    (.mkdirs (io/file d)))
  (with-server
   8114
   {"arch-a" script "arch-b" script "arch-other" script}
   (fn []
     (let [archive! (fn [tid flag]
                      (api-call 8114 :post (str "/api/threads/" tid "/archive")
                                (json/write-str {:archived flag})))
           bind!    (fn [tid dir]
                      (api-call 8114 :post "/api/project"
                                (json/write-str {:threadId tid :dir dir})))
           sessions (fn [dir]
                      (->> (json/read-str (.body (api-call 8114 :get "/api/projects" nil))
                                          :key-fn keyword)
                           (filter #(= (.getCanonicalPath (io/file dir)) (:path %)))
                           first
                           :sessions))
           row      (fn [dir tid]
                      (first (filter #(= tid (:threadId %)) (sessions dir))))
           flag     (fn [dir tid] (:archived (row dir tid)))
           ;; [bytes mtime] by IDENTITY, like the test runner's own isolation
           ;; check: a rewrite that kept the length would still move the mtime,
           ;; and vice versa.
           snap     (fn [tid]
                      (let [f (log-file-for tid)]
                        [(.length f) (.lastModified f)]))]
       (bind! "arch-a" archive-dir)
       (bind! "arch-b" archive-dir)
       (bind! "arch-other" archive-dir-2)
       (post-run 8114 "arch-a")
       (Thread/sleep 20)                         ; so a file write would move the mtime

       (testing "the flag round-trips through the listing"
         (is (false? (flag archive-dir "arch-a")) "a fresh session is not archived")
         (let [body (read-json (archive! "arch-a" true))]
           (is (= "arch-a" (:threadId body)))
           (is (true? (:archived body)) "the answer is the value now on disk"))
         (is (true? (flag archive-dir "arch-a")))
         (is (= 200 (.statusCode (archive! "arch-a" true)))
             "idempotent: asking twice is not an error")
         (is (true? (flag archive-dir "arch-a")))
         (is (false? (:archived (read-json (archive! "arch-a" false))))
             "and the direction goes back")
         (is (false? (flag archive-dir "arch-a"))))

       (testing "one session's flag is its own -- neither of the other two moves"
         (is (false? (flag archive-dir "arch-b")) "the sibling in the SAME project")
         (is (false? (flag archive-dir-2 "arch-other")) "and a session in ANOTHER project")
         (archive! "arch-other" true)
         (is (true? (flag archive-dir-2 "arch-other")))
         (archive! "arch-other" false)
         (is (false? (flag archive-dir-2 "arch-other")))
         (is (false? (flag archive-dir "arch-b"))
             "and none of that reached back into the first project"))

       (testing "the log is byte-for-byte and mtime-for-mtime what it was"
         (let [before (snap "arch-a")]
           (archive! "arch-a" true)
           (archive! "arch-a" false)
           (archive! "arch-a" true)
           (is (= before (snap "arch-a"))
               "three writes to the ROW, and not one to the file")))

       (testing "an archived session still lists -- flagged -- with its disk facts"
         ;; The server does not decide what to DO with the flag: grouping is the
         ;; screen's business, and a listing that dropped archived rows would make
         ;; 'where did my session go' a question with no server-side answer.
         (let [r (row archive-dir "arch-a")]
           (is (true? (:archived r)))
           (is (pos? (:bytes r)))
           (is (= (.length (log-file-for "arch-a")) (:bytes r)))))

       (testing "archiving a session with no log at all is legal"
         ;; The flag is a property of the CONVERSATION, not of the file -- and a
         ;; session created on the sidebar has not run yet, so it has no file.
         (let [f (log-file-for "arch-b")]
           (is (.delete f) "the file is removed by hand")
           (is (true? (:archived (read-json (archive! "arch-b" true))))))
         (is (true? (flag archive-dir "arch-b")))
         (archive! "arch-b" false))

       (testing "a session this home has never heard of is refused BY NAME"
         (let [resp (archive! "no-such-session-here" true)
               body (read-json resp)]
           (is (= 404 (.statusCode resp)) "nothing to archive is not a malformed request")
           (is (str/includes? (:error body) "no-such-session-here")
               "and the reason names the id the caller asked about")))

       (testing "a body that carries no boolean is a 400, not a silent no-op"
         (is (= 400 (.statusCode (api-call 8114 :post "/api/threads/arch-a/archive"
                                           (json/write-str {:archived "yes"})))))
         (is (= 400 (.statusCode (api-call 8114 :post "/api/threads/arch-a/archive"
                                           (json/write-str {})))))
         (is (= 400 (.statusCode (api-call 8114 :post "/api/threads/arch-a/archive"
                                           "{not json")))))

       (testing "GET is refused -- this route has an effect"
         (is (= 405 (.statusCode (api-call 8114 :get "/api/threads/arch-a/archive" nil)))))

       (testing "and the archive route does not swallow the rebuild route beside it"
         ;; The two share their path SHAPE. A matcher that answered for the wrong
         ;; verb would show up as a rebuild that archived something.
         (let [body (read-json (api-call 8114 :post "/api/threads/arch-a/rebuild" ""))]
           (is (seq (:messages body)) "the rebuild route still rebuilds")
           (is (true? (flag archive-dir "arch-a")) "and archiving never changed it")))))))

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
           (let [f (log-file id)]
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

(deftest a-rebind-carries-the-log-because-a-conversation-is-one-file
  ;; The reason move-log! exists. A session that has already RUN and is then
  ;; rebound would otherwise leave its history in the old project's workspace and
  ;; start a new file in the new one -- and replay, rebuild and the eval reader
  ;; each reconstruct a conversation from a SINGLE file, so the split conversation
  ;; would be unreadable by all three. So the bind carries the file.
  ;;
  ;; The second half is the refusal: a destination that ALREADY holds a log for
  ;; this session. Merging is not an option (replay folds frames in file order, so
  ;; appending would read as one conversation out of order) and neither is
  ;; overwriting (it would destroy a run's record), so the move is refused by name
  ;; and the human decides which file is the conversation.
  (let [adir  (io/file project-dir)
        bdir  (io/file project-dir-2)
        ;; The two workspaces' files for this one session stem, computed the way
        ;; the server computes them (canonical path -> sanitize), so this test
        ;; names the same paths the route does.
        in-ws (fn [^java.io.File d tid]
                (home/log-file (io/file (home/projects-dir)
                                        (home/sanitize (.getCanonicalPath d)))
                               tid))
        old   (in-ws adir "move-run")
        moved (in-ws bdir "move-run")]
    (doseq [d [adir bdir]] (run! #(io/delete-file % true) (reverse (file-seq d))))
    (run! #(.mkdirs ^java.io.File %) [adir bdir])
    (with-server
     8108
     {"move-run" bound-script}
     (fn []
       (let [tid "move-run"]
         (api-call 8108 :post "/api/project" (json/write-str {:threadId tid :dir project-dir}))
         (testing "the run leaves a log in the FIRST project's workspace"
           (is (= "RUN_FINISHED" (:type (last (wire/frames-from-sse (.body (post-run 8108 tid)))))))
           (is (.exists old)))
         (let [before-lines (count (str/split-lines (slurp old :encoding "UTF-8")))]
           (testing "the rebind answers OK and the log MOVED with it"
             (let [resp (api-call 8108 :post "/api/project"
                                  (json/write-str {:threadId tid :dir project-dir-2}))]
               (is (= 200 (.statusCode resp))))
             (is (str/includes? (log-dir-for tid) "harness-http-project-2")
                 "the workspace the server reports is the new project's")
             (is (.exists moved) "the log is in the NEW project's workspace")
             (testing "and nothing was left behind in the old one -- no second half"
               (is (not (.exists old)))
               (testing "one file still holds the whole conversation"
                 (is (< before-lines
                        (count (str/split-lines (slurp moved :encoding "UTF-8"))))))))
           (testing "so the listing sees exactly ONE log for this session, and rebuild works"
             (let [rows (->> (json/read-str (.body (api-call 8108 :get "/api/threads" nil))
                                            :key-fn keyword)
                             (filterv #(= tid (:threadId %))))]
               (is (= 1 (count rows))))
             (let [resp (api-call 8108 :post (str "/api/threads/" tid "/rebuild") nil)]
               (is (= 200 (.statusCode resp))))))
         (testing "a destination that already holds this session's log is refused BY NAME"
           ;; Put a log back where the first project's workspace was -- which is what
           ;; a quarantined-and-rebuilt store, a hand-edited tree or a restored
           ;; backup all look like: a file in a workspace the store no longer knows
           ;; about. Now bind there, and both ends are occupied.
           (.mkdirs (.getParentFile old))
           (spit old "{\"ts\":1,\"runId\":\"r0\",\"kind\":\"input\",\"payload\":{}}\n"
                 :encoding "UTF-8")
           (let [resp  (api-call 8108 :post "/api/project"
                                 (json/write-str {:threadId tid :dir project-dir}))
                 reply (read-json resp)]
             (is (= 400 (.statusCode resp)))
             (is (str/includes? (:error reply) "harness-http-project"))
             (testing "neither file was touched"
               (is (.exists moved) "the conversation is still where the last bind put it")
               (is (= "{\"ts\":1,\"runId\":\"r0\",\"kind\":\"input\",\"payload\":{}}\n"
                      (slurp old :encoding "UTF-8"))
                   "the destination is byte-for-byte what was there -- nothing appended"))
             (testing "the binding is NOT rolled back -- the 400 carries where the store went"
               ;; Rolling back would leave the store and the tree disagreeing anyway,
               ;; and the human's next step is the same in both cases: decide which
               ;; log is this conversation.
               (is (str/ends-with? (:dir reply) "harness-http-project")))
             (testing "and from here the split is VISIBLE, not silent -- two files, one stem"
               (let [rows (->> (json/read-str (.body (api-call 8108 :get "/api/threads" nil))
                                              :key-fn keyword)
                               (filterv #(= tid (:threadId %))))]
                 (is (= 2 (count rows))))
               (let [rb (api-call 8108 :post (str "/api/threads/" tid "/rebuild") nil)]
                 (is (= 404 (.statusCode rb)))
                 (is (str/includes? (:error (read-json rb)) "harness-http-project")
                     "the refusal names the other half's directory"))))))))))

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
                           (str/split-lines (slurp (log-file tid) :encoding "UTF-8")))
               rb    (filterv #(= "session/rebuilt" (:kind %)) lines)]
           (is (= 1 (count rb)))
           (is (nil? (:runId (first rb))) "a rebuild happens outside any run")
           (is (pos? (get-in (first rb) [:payload :messages])))
           (is (= "http" (get-in (first rb) [:payload :via])))))))))

(deftest rebuilding-a-session-that-has-never-run-answers-an-empty-conversation
  ;; The sidebar's very first click on a brand-new session. Binding wrote an audit
  ;; line and nothing else, so the log exists and holds no run -- and a 400 here
  ;; would make the session a person just created un-openable, for a reason
  ;; ('truncated') that is not true of it.
  (let [dir (str (System/getProperty "java.io.tmpdir") "/harness-http-unrun")]
    (run! #(io/delete-file % true) (reverse (file-seq (io/file dir))))
    (.mkdirs (io/file dir))
    (with-server
     8112
     "never-run"
     (fn []
       (is (= 200 (.statusCode (api-call 8112 :post "/api/project"
                                         (json/write-str {:threadId "never-run" :dir dir})))))
       (let [resp  (api-call 8112 :post "/api/threads/never-run/rebuild" nil)
             reply (json/read-str (.body resp) :key-fn keyword)]
         (is (= 200 (.statusCode resp)))
         (is (= [] (:messages reply)) "nothing has happened in this conversation yet")
         (is (= [] (:context reply))))
       (testing "and the audit line it did write is still on disk, untouched"
         (is (some #(= "project/bound" (:kind %))
                   (mapv #(json/read-str % :key-fn keyword)
                         (str/split-lines (slurp (log-file-for "never-run") :encoding "UTF-8"))))))))))

(def ^:private remove-dir-a
  (str (System/getProperty "java.io.tmpdir") "/harness-http-remove-a"))
(def ^:private remove-dir-b
  (str (System/getProperty "java.io.tmpdir") "/harness-http-remove-b"))

(defn- workspace-of
  "The workspace directory for a project's CANONICAL path -- the naming rule read
  the way the server reads it.

  Derived rather than asked, and that is forced: the test below is about what
  happens AFTER the project row is gone, when `identity-for` has nothing to answer
  with and `log-dir-for` would hand back the unbound workspace. The files did not
  move, so the directory a person would go look in is still this one."
  [canonical]
  (str (io/file (home/projects-dir) (home/sanitize canonical))))

(defn- file-facts
  "Every regular file under DIR as {absolute path [bytes mtime]}, read the way the
  test runner reads a home it must not disturb. TWO numbers rather than 'exists',
  because neither alone is evidence: a rewrite that kept the length still moves
  the mtime, and a truncation that kept the mtime still moves the length."
  [dir]
  (let [root (io/file dir)]
    (if-not (.exists root)
      {}
      (into {} (for [f (file-seq root) :when (.isFile ^java.io.File f)]
                 [(.getAbsolutePath ^java.io.File f)
                  [(.length ^java.io.File f) (.lastModified ^java.io.File f)]])))))

(deftest removing-a-project-unbinds-it-and-leaves-every-log-where-it-was
  ;; Ticket 07, at the edge. The ticket's hard claim is a NEGATIVE one -- the jsonl
  ;; under projects/<workspace>/ keeps its bytes AND its mtime -- so the assertions
  ;; are those two numbers, taken around the call, for every file in the tree. A
  ;; route that helpfully tidied up, or that carried the logs back into the unbound
  ;; workspace, would answer 200 and unbind its sessions exactly as this one does;
  ;; only the snapshot catches it.
  ;;
  ;; Its own directory pair, for the reason the listing and archive tests have
  ;; theirs: the assertions are about the WHOLE content of a project's session list
  ;; and of a workspace tree, so a shared directory would make this pass or fail
  ;; depending on which test ran first.
  (doseq [d [remove-dir-a remove-dir-b]]
    (run! #(io/delete-file % true) (reverse (file-seq (io/file d))))
    (.mkdirs (io/file d)))
  (with-server
   8115
   {"rm-a" script "rm-b" script "rm-other" script "rm-never-run" script}
   (fn []
     (let [canon    (fn [d] (.getCanonicalPath (io/file d)))
           remove!  (fn [d]
                      (api-call 8115 :post
                                (str "/api/projects/"
                                     (java.net.URLEncoder/encode (canon d) "UTF-8")
                                     "/remove")
                                nil))
           add!     (fn [d] (api-call 8115 :post "/api/projects" (json/write-str {:dir d})))
           bind!    (fn [tid d]
                      (api-call 8115 :post "/api/project"
                                (json/write-str {:threadId tid :dir d})))
           archive! (fn [tid flag]
                      (api-call 8115 :post (str "/api/threads/" tid "/archive")
                                (json/write-str {:archived flag})))
           listing  (fn [] (json/read-str (.body (api-call 8115 :get "/api/projects" nil))
                                          :key-fn keyword))
           project  (fn [d] (first (filter #(= (canon d) (:path %)) (listing))))
           session  (fn [d tid] (first (filter #(= tid (:threadId %))
                                               (:sessions (project d)))))
           ws-a     (workspace-of (canon remove-dir-a))
           ws-b     (workspace-of (canon remove-dir-b))
           unbound  (str (io/file (home/projects-dir) http/unbound-workspace))]

       (is (= 200 (.statusCode (bind! "rm-a" remove-dir-a))))
       (is (= 200 (.statusCode (bind! "rm-b" remove-dir-a))))
       (is (= 200 (.statusCode (bind! "rm-other" remove-dir-b))))
       (is (= 200 (.statusCode (add! remove-dir-a))))
       (post-run 8115 "rm-a")
       (post-run 8115 "rm-b")
       (post-run 8115 "rm-other")
       ;; A session with NO log at all. Binding one writes an audit line, so the
       ;; file would otherwise exist -- it is removed by hand, which is the state
       ;; the listing calls "null facts" rather than a zero-byte file. The removal
       ;; must not special-case it: the verb is about a project row, and whether a
       ;; conversation has a file is not that row's business.
       (is (= 200 (.statusCode (add! remove-dir-b))))
       (is (= 200 (.statusCode (bind! "rm-never-run" remove-dir-b))))
       (is (.delete (log-file-for "rm-never-run")) "the file is removed by hand")
       (is (nil? (:bytes (session remove-dir-b "rm-never-run")))
           "the listing calls that null facts, not a zero-byte file")
       (is (= 200 (.statusCode (archive! "rm-b" true))))
       (Thread/sleep 20)                        ; so a file write would move an mtime

       (let [before-a (file-facts ws-a)
             before-b (file-facts ws-b)]
         (is (seq before-a) "the first project has logs on disk to protect")
         (is (seq before-b))

         (testing "the removal answers with the path and the count it released"
           (let [resp  (remove! remove-dir-a)
                 reply (read-json resp)]
             (is (= 200 (.statusCode resp)))
             (is (= (canon remove-dir-a) (:path reply)))
             (is (= 2 (:unbound reply)) "both of its sessions, and no others")))

         (testing "the project is gone from the listing"
           (is (nil? (project remove-dir-a)))
           (is (some? (project remove-dir-b)) "the other project is untouched"))

         (testing "and its sessions are listed nowhere -- they are unbound now"
           ;; Not "gone from the sidebar": the sessions still exist as ROWS, they
           ;; simply belong to no project, and the listing is grouped BY project.
           ;; GET /api/threads is the raw tree view and still finds their files --
           ;; an unowned jsonl is not a session this interface claims, but it is
           ;; still a file.
           (let [everywhere (set (mapcat #(map :threadId (:sessions %)) (listing)))]
             (is (not (contains? everywhere "rm-a")))
             (is (not (contains? everywhere "rm-b")))
             (is (contains? everywhere "rm-other"))
             (is (contains? everywhere "rm-never-run")))
           (is (some #(= "rm-a" (:threadId %))
                     (json/read-str (.body (api-call 8115 :get "/api/threads" nil))
                                    :key-fn keyword))))

         (testing "EVERY FILE is byte-for-byte and mtime-for-mtime what it was"
           (is (= before-a (file-facts ws-a))
               "the removed project's whole workspace, untouched")
           (is (= before-b (file-facts ws-b))
               "and a project nobody removed is no more touched than it was")
           (is (every? #(.exists (home/log-file ws-a %)) ["rm-a" "rm-b"])
               "the named files are still there, which is what 'keeps the logs' means")
           (is (not (.exists (home/log-file unbound "rm-a")))
               "and nothing was carried into the unbound workspace on the way out")
           (is (not (.exists (home/log-file unbound "rm-b")))))

         (testing "a directory this home does not know is a NAMED 404"
           (let [resp  (remove! (str remove-dir-a "/never-added"))
                 reply (read-json resp)]
             (is (= 404 (.statusCode resp)))
             (is (str/includes? (:error reply) "never-added")
                 "the reason names the path the caller asked about")))
         (testing "removing it a second time is that same refusal, not a silent 200"
           ;; The list somebody was looking at can be stale, and a 200 about a
           ;; project that is not there would tell them their click worked.
           (let [resp (remove! remove-dir-a)]
             (is (= 404 (.statusCode resp)))
             (is (str/includes? (:error (read-json resp)) "nothing to remove"))))

         (testing "GET on the remove shape is refused -- this route has an effect"
           ;; The same guard the thread verbs needed: without it a GET falls
           ;; through to the AG-UI run endpoint and dies in a body that is not
           ;; there.
           (is (= 405 (.statusCode
                       (api-call 8115 :get
                                 (str "/api/projects/"
                                      (java.net.URLEncoder/encode (canon remove-dir-b) "UTF-8")
                                      "/remove")
                                 nil)))))

         (testing "a session that has never run is released like any other"
           (let [reply (read-json (remove! remove-dir-b))]
             (is (= (canon remove-dir-b) (:path reply)))
             (is (= 2 (:unbound reply)) "the session that ran and the one that never did"))
           (is (nil? (project remove-dir-b))))

         (testing "re-adding the same directory brings the sessions back"
           (let [reply (read-json (add! remove-dir-a))]
             (is (= (canon remove-dir-a) (:path reply)))
             (is (= 2 (:adopted reply)) "both of them remembered where they were"))
           (is (= #{"rm-a" "rm-b"}
                  (set (map :threadId (:sessions (project remove-dir-a))))))
           (testing "INCLUDING the archive flag, which the removal never touched"
             (is (true? (:archived (session remove-dir-a "rm-b"))))
             (is (false? (:archived (session remove-dir-a "rm-a")))))
           (testing "and with the disk facts read off the very same files"
             (is (= before-a (file-facts ws-a))
                 "re-adding is a row write too: not one byte moved either way")
             (is (pos? (:bytes (session remove-dir-a "rm-a"))))
             (is (= (.length (log-file-for "rm-a")) (:bytes (session remove-dir-a "rm-a"))))))
         (testing "and so does the pair where one of them never ran"
           (is (= 2 (:adopted (read-json (add! remove-dir-b)))))
           (is (= #{"rm-other" "rm-never-run"}
                  (set (map :threadId (:sessions (project remove-dir-b))))))
           (is (nil? (:bytes (session remove-dir-b "rm-never-run")))
               "still no log facts -- null, which is not a zero-byte file")
           (is (pos? (:bytes (session remove-dir-b "rm-other")))))

         (testing "and the history is intact -- the same file rebuilds the conversation"
           ;; The ticket's last promise, and the one a "tidy up while we are here"
           ;; implementation would break invisibly: same directory, same file name,
           ;; same run inside it.
           (let [resp  (api-call 8115 :post "/api/threads/rm-a/rebuild" "")
                 reply (read-json resp)]
             (is (= 200 (.statusCode resp)))
             (is (seq (:messages reply))
                 "the conversation came back out of the file the removal left alone"))))))))

(deftest adding-a-project-makes-a-project-with-no-session
  ;; Ticket 05's other half. Without this verb the ONLY way to get a project was
  ;; to bind a session to a directory -- backwards for a product whose sessions
  ;; must belong to a project, because the first project would need a session
  ;; that had nowhere to go. So this route makes the DIRECTORY the project.
  (let [adir (str (System/getProperty "java.io.tmpdir") "/harness-http-added")]
    (run! #(io/delete-file % true) (reverse (file-seq (io/file adir))))
    (.mkdirs (io/file adir))
    (with-server
     8113
     "add-project-unused"
     (fn []
       (let [add!  (fn [dir] (api-call 8113 :post "/api/projects" (json/write-str {:dir dir})))
             named (fn [dir]
                     (let [want (last (str/split (.getCanonicalPath (io/file dir)) #"/"))]
                       (first (filterv #(= want (last (str/split (:path %) #"/")))
                                       (json/read-str (.body (api-call 8113 :get "/api/projects" nil))
                                                      :key-fn keyword)))))]
         (testing "a real directory becomes a project with NO sessions in it"
           (let [resp  (add! adir)
                 reply (read-json resp)]
             (is (= 200 (.statusCode resp)))
             (is (= (.getCanonicalPath (io/file adir)) (:path reply))
                 "the answer is the CANONICAL path -- the identity, not the spelling")
             (is (some? (:projectId reply)))
             (let [project (named adir)]
               (is (some? project))
               (is (= [] (:sessions project))
                   "an empty list, not a missing key: the project exists and holds nothing"))))
         (testing "adding the SAME directory again answers the same project, not a second row"
           (let [first-id  (:projectId (read-json (add! adir)))
                 second-id (:projectId (read-json (add! adir)))]
             (is (= first-id second-id) "find-or-create, keyed on the canonical path")
             (is (= 1 (count (filterv #(= (.getCanonicalPath (io/file adir)) (:path %))
                                      (json/read-str
                                       (.body (api-call 8113 :get "/api/projects" nil))
                                       :key-fn keyword)))))))
         (testing "a different SPELLING of the same directory is still the same project"
           ;; The whole point of keying on the canonical form: `dir/.` is the same
           ;; directory, and a person typing it must not get a second project.
           (is (= (:projectId (read-json (add! adir)))
                  (:projectId (read-json (add! (str adir "/.")))))))
         (testing "a missing directory is a NAMED 400 and writes nothing"
           (let [resp  (add! (str adir "/nope"))
                 reply (read-json resp)]
             (is (= 400 (.statusCode resp)))
             (is (str/includes? (:error reply) "no such directory"))
             (is (nil? (named (str adir "/nope"))) "and no row appeared for the path it was given")))
         (testing "a FILE is not a directory, and is refused in its own words"
           (let [f (io/file adir "a-file.txt")]
             (spit f "x" :encoding "UTF-8")
             (let [resp (add! (str f))]
               (is (= 400 (.statusCode resp)))
               (is (str/includes? (:error (read-json resp)) "not a directory")))))
         (testing "no dir and a malformed body are each a 400"
           (is (= 400 (.statusCode (api-call 8113 :post "/api/projects" (json/write-str {})))))
           (is (= 400 (.statusCode (api-call 8113 :post "/api/projects" "{not json"))))))))))

(deftest the-retired-logs-directory-is-invisible-three-ways
  ;; Ticket 03's other half: `~/.clj-harness/logs/` retires. NOT imported, NOT
  ;; migrated, NOT migrated away -- the bytes stay exactly where they are, and
  ;; those files simply stop being part of this product's view. The three ways
  ;; that could quietly stop being true are checked one by one, because each has
  ;; a different mechanism that could resurrect it:
  ;;
  ;;   1. a listing that scans too widely (the walk is rooted at projects/);
  ;;   2. a store that back-fills itself from the disk (nothing derives ownership
  ;;      from a filename -- a session row only exists because something asked
  ;;      for it);
  ;;   3. a startup scan with the same effect, one process later.
  ;;
  ;; And the file itself is checked afterwards, byte for byte: a refusal that
  ;; still rewrote or moved the file would be no refusal at all.
  (let [tid  "old-logs-thread"
        old  (io/file (home/root) "logs")
        file (io/file old (str tid ".jsonl"))
        body (str (json/write-str
                   {:ts 1 :runId "r0" :kind "input"
                    :payload {:threadId tid :runId "r0"
                              :messages [{:id "u1" :role "user" :content "old"}]
                              :tools [] :context []}})
                  "\n")]
    (.mkdirs old)
    (spit file body :encoding "UTF-8")
    ;; A FRESH server: this is the "started a process" half of the claim, and it
    ;; is why the store is asked about before the route below can have created
    ;; anything for this id.
    (with-server
     8109
     "old-logs-list"
     (fn []
       (testing "the store has no row for it -- nothing reads ownership off the disk"
         (is (nil? (project/binding-for tid)))
         (is (nil? (project/identity-for tid))))
       (testing "the listing does not show it -- the walk is rooted at projects/"
         (let [resp (api-call 8109 :get "/api/threads" nil)
               rows (json/read-str (.body resp) :key-fn keyword)]
           (is (= 200 (.statusCode resp)))
           (is (not-any? #(= tid (:threadId %)) rows))))
       (testing "and its id resolves to nothing, so a rebuild cannot reach it either"
         (is (= 404 (.statusCode (api-call 8109 :post (str "/api/threads/" tid "/rebuild") nil)))))
       (testing "the file is exactly as it was left -- unread, unmoved, unimported"
         (is (.exists file))
         (is (= body (slurp file :encoding "UTF-8")))
         (is (not (.exists (io/file (log-dir) (str tid ".jsonl"))))))))))

(deftest rebuild-refuses-truncated-and-corrupt-logs-by-name
  (with-server
   8106
   "it-refuse"
   (fn []
     (testing "a log that ends mid-run is refused, naming the last frame"
       (let [tid (str "trunc-" (java.util.UUID/randomUUID))
             f   (log-file tid)]
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
         (spit (log-file tid)
               "{\"ts\":1,\"runId\":\"r1\",\"kin" :encoding "UTF-8")
         (let [resp  (api-call 8106 :post (str "/api/threads/" tid "/rebuild") nil)
               reply (json/read-str (.body resp) :key-fn keyword)]
           (is (= 400 (.statusCode resp)))
           (is (re-find #"line 1" (:error reply))))))
     (testing "a thread with no log anywhere in the tree is a 404, naming the thread"
       ;; NOT a 400: nothing was rebuilt and nothing was wrong with a log -- the
       ;; stem simply names nothing, which is what 404 means. It is also the answer
       ;; a client that deleted its own session and reloaded the page needs.
       (let [resp  (api-call 8106 :post "/api/threads/no-such-thread-xyz/rebuild" nil)
             reply (json/read-str (.body resp) :key-fn keyword)]
         (is (= 404 (.statusCode resp)))
         (is (str/includes? (:error reply) "no-such-thread-xyz"))))
     (testing "a stem with a log in TWO workspaces is a 404 too, naming both files"
       ;; The split conversation. Quietly rebuilding the newest would hand back
       ;; half a conversation looking like a clean rebuild, so the route refuses
       ;; and says where both halves are.
       (let [tid  (str "split-" (java.util.UUID/randomUUID))
             one  (log-file tid)
             two  (home/log-file (io/file (home/projects-dir)
                                          (home/sanitize (.getCanonicalPath (io/file project-dir))))
                                 tid)
             line (json/write-str
                   {:ts 1 :runId "r1" :kind "input"
                    :payload {:threadId tid :runId "r1"
                              :messages [{:id "u1" :role "user" :content "hi"}]
                              :tools [] :context []}})]
         (.mkdirs (.getParentFile ^java.io.File two))
         (spit one (str line "\n") :encoding "UTF-8")
         (spit two (str line "\n") :encoding "UTF-8")
         (let [resp  (api-call 8106 :post (str "/api/threads/" tid "/rebuild") nil)
               reply (json/read-str (.body resp) :key-fn keyword)]
           (is (= 404 (.statusCode resp)))
           (is (str/includes? (:error reply) "harness-http-project"))
           (is (str/includes? (:error reply) "unbound"))))))))
