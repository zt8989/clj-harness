(ns harness.edge.http-test
  "Integration: a real server, a real HTTP request, a real SSE body.

  This is the layer that catches what unit tests structurally cannot see -- a run that
  is generated and logged perfectly but never reaches the client, and converter state
  that is rebuilt per event. Both of those actually happened during development."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.fake :as fake]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.kernel.event :as ev]
            [harness.kernel.frames :as frames]
            [harness.kernel.hooks :as hooks]
            [harness.kernel.llm :as llm]
            [harness.kernel.loop :as loop]
            [harness.edge.ag-ui :as ag]
            [harness.edge.http :as http]
            [harness.cap.claims :as claims]
            [harness.cap.jobs :as jobs]
            [harness.cap.providers :as providers]
            [harness.cap.project :as project]
            [harness.edge.replay :as replay]
            [harness.edge.record :as record]
            [harness.edge.sessions :as sessions]
            [harness.edge.trajectory :as trajectory]
            [harness.infra.shell :as shell]
            [harness.test-support :as support]
            [harness.kernel.tools :as tools]
            [harness.wire :as wire])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

;; NO ORIGIN LITERAL HERE, AND NO PORT IN ONE. This file used to declare its own
;; copy of the value the edge answers a request that names no page with
;; (`http://localhost:5173`) -- a second place deciding one fact, and a port named
;; in a test, which is what `*port*` below spends a paragraph refusing. What the
;; cases below assert is the RELATIONSHIP: an answer carries the origin this
;; PROCESS was started with, so the value is read from the one place that owns it
;; (`harness.edge.http/ui-origin`, which `start!` uses when the caller names none).
;; A page on this machine is answered by RULE instead and needs no value at all --
;; see `says-which-origin-per-request-and-not-once-per-process`.

(defn- drained!
  "Wait for the record writer to have written everything logged so far (ticket 02:
  the write is QUEUED, so a case that reads the file straight after an action has
  to say so). The timeout is a deadline for a failure, not a duration -- an empty
  queue returns at once -- and it is deliberately short: a test that needs the
  writer to be slow is a different test."
  []
  (record/flush! 5000))

(defn- wait-degraded!
  "Wait until the record writer has given up on THREAD-ID, answering what it says
  (or nil if that never happens). A case that wants to assert the DEGRADED state
  cannot read the queue's failure the instant it made the write fail: the write is
  the consumer's, and it has not been attempted yet."
  [tid]
  (let [deadline (+ (System/currentTimeMillis) 3000)]
    (loop []
      (or (record/degraded tid)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 10)
            (recur))))))

(defn- read-lines
  "What `read` returned, as plain lines -- with the `anchor|` prefix stripped when
  this session's mode puts one there. The row shape is the mode's business and has
  its own tests; a case about the LOG or the WIRE wants the content."
  [content]
  (mapv (fn [line]
          (if-let [i (str/index-of line "\u2502")]
            (subs line (inc i))
            line))
        (str/split-lines content)))

(defn- row-json
  "A record row AS THE WRITER EMITS IT since `.scratch/jsonl-two-kinds`: the file has two
  kinds of row -- `message` (what a person said or an LLM returned) and `event` (every other
  fact) -- with `ts`/`runId` OUTSIDE the payload.

  THE FIXTURES HERE SPELL A ROW THE WAY THE READER READS IT -- `{:kind .. :payload ..}` -- and
  this is the one place that knows the FILE writes it otherwise, so a case can lay down a
  record the writer would recognize without re-stating an envelope every time."
  [{:keys [kind payload source id hash] :as m}]
  (json/write-str (cond-> (merge (select-keys m [:ts :runId])
                                 (cond
                                   (= "message" kind) {:type "message" :payload payload}
                                   (= "event" kind)   {:type "event" :payload payload}
                                   :else              {:type "event"
                                                       :payload {:type "CUSTOM" :name kind
                                                                 :value payload}}))
                    source (assoc :source source)
                    id     (assoc :id id)
                    hash   (assoc :hash hash))))

(defn- action-rows
  "ONE ACTION'S OWN ROWS, as the writer lays them down since `.scratch/jsonl-two-kinds` 票 02:
  a `message` row per entry the action brought -- the entry's own name on the envelope, the
  payload the provider reads. The prompt row is the RUN's and a fixture that wants one says so
  (a run's own copy: kind `message`, source `system-prompt`, its bytes' `hash`); a fixture that
  only has to be readable as 'this run was open' needs neither."
  [ts run-id msgs]
  (mapv (fn [m]
          (row-json (cond-> {:ts ts :runId run-id :kind "message"
                             :source (if (:id m) "client" "injection")
                             :payload (dissoc m :id)}
                      (:id m) (assoc :id (:id m)))))
        msgs))

(defn- client-rows
  "The rows an action brought into the conversation: the `message` rows the CLIENT sent. 票 02
  took the `input` row away, so 'the action's line' is the row that carries its entry."
  [records]
  (filter #(and (= "message" (replay/kind %)) (= "client" (:source %))) records))

(def ^:private test-probe
  "A NAME FOR A LINE THAT IS ONLY A LINE, in the cases about where a record is written and
  how it is moved (`log!` routing, the carry-back, the overlap refusal). Those cases never fold
  the line -- they count it and look at its payload -- so it carries no meaning; spelling it
  after a fact the harness really emits would invite a reader to think it does, and spelling it
  `input` would name the row `.scratch/jsonl-two-kinds` 票 02 deleted."
  "test/probe")

(def ^:private reasoning "\u9700\u8981\u5148\u770b\u4e00\u773c deps.edn\u3002")

(def ^:private script
  [{:reasoning reasoning :content ""
    :tool-calls [{:id "c1" :name "read" :arguments {:path "deps.edn"}}
                 {:id "c2" :name "read" :arguments {:path "README.md"}}]}
   {:content "\u8fd9\u662f\u4e00\u4e2a Clojure \u9879\u76ee\u3002"}])

(def ^:dynamic *port*
  "The port the server under test is listening on, bound by `with-server` for the
  body of a test.

  THE OS PICKS IT (`{:port 0}`), so no test names a port. A literal would mean two
  runs on one machine cannot coexist -- and they routinely do: a developer with a
  session open beside a test run, an e2e server left up by an earlier ticket, two
  worktrees running the suite at once. Those collisions arrive as
  `BindException: Address already in use` in a test that has nothing to do with
  the culprit, which is the worst way to learn about them.

  The var rather than a threaded argument because tests define helper closures
  that call `api-call`/`post-run`; those would each need the port passed down.
  A test that wants the port to build a URL by hand reads this var. See AGENTS.md."
  nil)

;; Declared rather than moved up to it: each is used by a case near the top of the file
;; and belongs with its own kind further down (the bare fixture with the fixtures, the
;; two log readers with the cases about reading a log).
(declare with-bare-server process-log log-lines-for feed-open! feed-first-frame until)

(defn- start-session!
  "A session of this home with nothing in it -- see `harness.test-support/start-session!`,
  which is the same verb for every namespace that drives the edge."
  [thread-id]
  (support/start-session! thread-id))

(defn- ensure-session!
  "Make sure this home knows THREAD-ID, the way the page's new-task action would have.

  SINCE TICKET 03 A RUN OF AN ID THE STORE HAS NEVER HEARD OF IS REFUSED
  (`refuse-unknown-session!`), and a test that had to remember that at every call site
  would be a test written half about sessions. So the two request helpers say it: the
  premise of `post-run`/`fire-run!` is 'the page sends a run for a session that exists',
  and that is what they set up. The refusal itself is asserted by a case that goes
  around them (`a-run-aimed-at-a-session-this-home-does-not-know-is-refused-by-name`).

  IT DOES NOT RESET ANYTHING, deliberately: this is the door the request helpers use, and
  a helper that emptied the conversation it was asked to continue would break every case
  about a second run. Starting clean is `start-session!`, and that is the fixture's call,
  not the request's."
  [thread-id]
  (when-not (project/session-exists? (str thread-id))
    (sessions/drop! (str thread-id))
    (project/register-session! (str thread-id))))

(defn- with-server
  "Run F against a live server, with a scripted provider pinned to each thread
  the test serves.

  THREADS names them and is one of:

    \"it-1\"                 one thread, using SCRIPT (the default script below)
    [\"a\" \"b\"]              several threads, all sharing SCRIPT
    {\"a\" SCRIPT-A \"b\" SCRIPT-B}  per-thread scripts -- for a test whose
                            threads must consume turns in a known order

  The pin is PER-THREAD, matching harness.cap.providers' resolution: a provider
  override is a session's, and the server looks it up by the request's threadId.
  There is deliberately no process-wide slot to fall back on -- a test that
  pinned globally would pass while the per-thread wiring was broken.

  EVERY THREAD IT NAMES IS ALSO REGISTERED AS A SESSION OF THIS HOME, which since
  ticket 03 is what a run needs to exist: the run edge refuses an id the store has
  never heard of (`refuse-unknown-session!`), and in the real page that row is made by
  the new-task action before a word is typed. Doing it here is the same premise, and
  it is one line instead of one per case. A case that wants an id the home does NOT
  know asks for one directly -- that refusal is asserted by naming an id no test
  registers.

  The port is ASKED OF THE OS after the bind, which is why it can be neither an
  argument nor a literal -- see *port*."
  ([threads f]
   (with-server threads script f))
  ([threads turns f]
   (let [pins (cond
                (map? threads) threads
                (coll? threads) (into {} (map (fn [t] [t turns])) threads)
                :else           {threads turns})]
     (doseq [[t ts] pins]
       (providers/use-provider! (str t) (fake/scripted ts))
       (start-session! t))
     (let [stop (http/start! {:port 0})
           port (:local-port (meta stop))]
       (try (binding [*port* port] (f))
            (finally (stop) (doseq [t (keys pins)] (providers/use-provider! (str t) nil))))))))

(defn- post-run
  "A real request for THREAD-ID -- which must be a session this home knows, and
  `with-server` registers every thread it names so that every caller's premise holds.
  EXTRA is merged into the body, which is how a resume is sent.

  ORIGIN, when given, is sent as the page this request comes from -- which is the
  header a browser always sends on a cross-origin call and the one this file's
  other callers leave out. The run edge is the case worth asking about that way:
  its headers ride on the frames rather than on the ring response (see
  harness.edge.http/runner), so a CORS rule that held for the management edge could
  still be wrong here."
  ([thread-id] (post-run thread-id {} nil))
  ([thread-id extra] (post-run thread-id extra nil))
  ([thread-id extra origin]
   (let [_    (ensure-session! thread-id)
         body (json/write-str (merge {:threadId thread-id
                                      ;; THE ACTION'S OWN ENTRIES, not the conversation: the
                                      ;; server holds that (ticket 03). The id is stable so
                                      ;; that posting the same run twice means "the same
                                      ;; question again" -- which is what a retry IS, and the
                                      ;; conversation must not grow a second copy of it.
                                      :append [{:id "u1" :role "user"
                                                :content "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee"}]
                                      :tools []}
                                     extra))
         req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* "/api/agent")))
                  (.header "Content-Type" "application/json")
                  (.header "Accept" "text/event-stream")
                  (cond-> origin (.header "Origin" origin))
                  (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                  (.build))]
     (.send (HttpClient/newHttpClient) req
            (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)))))

(defn- log-dir
  "Where the server under test writes its logs, for a thread with NO project
  binding: the tree's reserved workspace.

  Derived from harness.infra.home, so it follows the config root -- which the test
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
   "it-1"
   (fn []
     (let [resp   (post-run "it-1")
           body   (.body resp)
           frames (wire/frames-from-sse body)]
       (testing "the headers a browser client needs, given it calls us directly"
         (is (= 200 (.statusCode resp)))
         (is (= "text/event-stream" (header resp "Content-Type")))
         (is (= http/ui-origin (header resp "Access-Control-Allow-Origin"))))
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
  (harness.edge.replay/lines->records) is the one that makes that call -- this helper
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
                              ;; THROUGH THE RECORD'S OWN READER, not `json/read-str`: a row
                              ;; of the file is `{:type "event"|"message" :payload ..}` and
                              ;; reading it is what validates it and hands back the row itself
                              ;; (`.scratch/jsonl-two-kinds` 票 02: there is no derived
                              ;; `{:kind ..}` shape any more -- `replay/kind` asks the row).
                              (first (harness.edge.replay/lines->records [line]))
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
   "it-1"
   (fn []
     ;; Delete first, like the replay e2e does: the assertions below use
     ;; first/last over the parsed lines, so leftover runs from earlier test
     ;; executions must not bleed in.
     (io/delete-file (log-file "it-1") true)
     (post-run "it-1")
     (let [f     (log-file "it-1")
           lines (wait-for-recorded f
                                    ;; The returned tail lands one line at a
                                    ;; time after the terminal frame -- wait
                                    ;; for its LAST line (the final answer),
                                    ;; not its first, or the reader races the
                                    ;; writer and sees half a tail.
                                    (fn [ls]
                                      (some (fn [l]
                                              (and (= "message" (replay/kind l))
                                                   (= "assistant" (get-in (replay/payload l) [:role]))
                                                   (= "\u8fd9\u662f\u4e00\u4e2a Clojure \u9879\u76ee\u3002"
                                                      (get-in (replay/payload l) [:content]))))
                                            ls))
                                    2000)
           msgs  (mapv replay/payload (filter #(= "message" (replay/kind %)) lines))]
       (testing "the entries and every emitted frame are on disk"
         (is (contains? (set (map replay/kind lines)) "message"))
         (is (contains? (set (map replay/kind lines)) "event")))
       (testing "the client's own message is a row of its own, in the provider's shape"
         ;; 票 02: the raw RunAgentInput is NOT a row any more -- that whole inbound vector,
         ;; written once per run, was the second copy this ticket deletes. What entered the
         ;; conversation is what is kept, one row per element, named by its own id.
         (let [mine (first (filter #(and (= "message" (replay/kind %))
                                         (= "client" (:source %)))
                                   lines))]
           (is (= "u1" (:id mine)) "the entry keeps the name the client gave it")
           (is (= "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee"
                  (get-in (replay/payload mine) [:content]))
               "and the bytes it carried")))
       (testing "the submitted system message is on disk VERBATIM -- as a message row"
         ;; A MESSAGE ROW AND THE ARRAY'S FIRST ELEMENT (owner, 2026-09-21): what a run reads
         ;; from prompt.md plus its SystemPrompt hooks IS what it hands the model, so it is
         ;; stored the way the model got it -- verbatim -- with `:source` saying the prompt
         ;; put it there and `:hash` naming those bytes, and it is kept EVERY run rather than
         ;; once per conversation, because a stable prefix is what the provider's cache reads.
         (let [row (->> lines (filter replay/system-prompt?) first)
               sys (replay/payload row)]
           (is (some? row) "the run recorded the prompt it handed the model")
           (is (= "system" (:role sys)) "as a system message, the array's first element")
           (is (= 64 (count (:hash row))) "named by its SHA-256, on the row's envelope")
           ;; Context never touches the system message -- it rides as a trailing
           ;; user message -- so what the row carries is prompt.md's frozen opening
           ;; with the SystemPrompt hooks' text behind it. The opening is compared
           ;; TRIMMED because the assembly normalises its trailing newlines before
           ;; adding a block.
           (is (str/starts-with? (:content sys)
                                 (str/trimr (slurp "prompt.md" :encoding "UTF-8"))))
           (is (str/includes? (:content sys) "<env>")
               "and the kernel's own text is behind it")
           (is (every? (fn [f] (< (.indexOf lines row) (.indexOf lines f)))
                       (filter replay/frame? lines))
               "the prompt is written before the run's first frame: the array's first element --
                and the action's own row (the client's message) sits in front of the whole run")
           (is (some #(= "system" (:role %)) msgs)
               "and it reaches the reader as the message it is -- the CLIENT filter is elsewhere (see
                the-assembled-system-message-reaches-the-model-and-never-the-client)")))
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
           ;; The content is what the read tool actually returned -- compared as
           ;; LINES WITH THE ROW PREFIX STRIPPED, so this case stays a case about
           ;; the log holding the tool result rather than about the shape of a
           ;; row, which the default mode changed.
           (is (some #(and (= "c1" (:tool_call_id %))
                           (= (str/split-lines (slurp "deps.edn" :encoding "UTF-8"))
                              (read-lines (:content %))))
                     tools))))))))

;; ------------------------------------- the assembled system message, at the edge

(defn- system-texts
  "Every system message a thread's log holds, in order -- ONE PER RUN.

  READ OFF ITS OWN `message` ROW (owner, 2026-09-21: a `message` row IS an element of the
  array the model was handed, the prompt is that array's first element, and its envelope
  says so -- `:source` = `system-prompt`, `:hash` naming those bytes). Every run writes its
  own copy, so the list is one text per run, in the order the runs froze them."
  [thread-id]
  (->> (wait-for-recorded (log-file thread-id)
                          (fn [ls] (some #(and (= "message" (replay/kind %))
                                               (= "assistant" (get-in (replay/payload %) [:role])))
                                         ls))
                          2000)
       (filter replay/system-prompt?)
       (mapv #(get-in (replay/payload %) [:content]))
       (mapv str)))

(deftest the-assembled-system-message-reaches-the-model-and-never-the-client
  ;; The whole shape, through the real edge: prompt.md's opening plus what the
  ;; hooks appended is what the run hands the model and it is recorded as the
  ;; `system-prompt` event -- and in NO AG-UI frame (the client never sees it).
  ;; "The front end shows nothing" is not a filtering decision anywhere -- it is the
  ;; absence of a frame, and this is the only layer that can prove it.
  ;;
  ;; A hooks.edn declaration is in the mix too, so the test covers all three
  ;; sources at once: the kernel's own rows, a file's, and (in the test below) the
  ;; session's.
  (support/write-hooks! {:system-prompt [{:command "printf 'A DECLARED BLOCK\\n'"}]})
  (try
    (with-server
     "it-system"
     (fn []
       (io/delete-file (log-file "it-system") true)
       (let [resp   (.body (post-run "it-system"))
             frames (wire/frames-from-sse resp)
             text   (first (system-texts "it-system"))
             on-wire (json/write-str frames)]
         (testing "the model is handed the frozen opening first, byte for byte"
           (is (str/starts-with? text (str/trimr (slurp "prompt.md" :encoding "UTF-8")))))
         (testing "then the kernel's own rows, each stating a live fact"
           (is (str/includes? text "<project>"))
           (is (str/includes? text "not bound to any project directory"))
           (is (str/includes? text "<env>"))
           (is (str/includes? text "platform: "))
           (is (str/includes? text "available: ")))
         (testing "and then what the FILE declared -- all three sources, in order"
           (is (str/includes? text "A DECLARED BLOCK"))
           (is (< (str/index-of text "<env>") (str/index-of text "A DECLARED BLOCK"))))
         (testing "the trigger is on the record, with what it collected"
           (let [line (first (filter #(= "hook/SystemPrompt" (replay/kind %))
                                     (wait-for-recorded (log-file "it-system")
                                                        (fn [ls] (some #(= "hook/SystemPrompt" (replay/kind %)) ls))
                                                        2000)))]
             (is (some? line))
             ;; FOUR DECLARATIONS NOW, and the fourth is the interesting one: the
             ;; subagents capability contributes a row at this point (the <subagent>
             ;; block), so a real composition root has one more than the kernel's
             ;; two and the file's one. It MATCHES on an ordinary thread -- this one
             ;; -- and says NOTHING there: `join-blocks` drops a block whose stdout
             ;; is empty, which is why the assertions above about the message's
             ;; exact text still hold with the row installed. Counting it here is
             ;; what keeps this number a fact about the wiring rather than a number
             ;; that quietly followed the code.
             (is (= 4 (get-in (replay/payload line) [:matched]))
                 "the kernel's two rows, the file's one, and the subagent block's")))

         (testing "and the TOOL TABLE rides the row's ENVELOPE, out of the message"
           ;; OWNER'S RULING (2026-09-24): the tools go into the system ROW -- but NOT into
           ;; the message's content, where the model would read them a second time and pay
           ;; for them. The envelope is where `log!` puts a row's own fields (`:source`,
           ;; `:hash`), and `replay/payload` keeps them out of the message.
           (let [row   (first (filter replay/system-prompt?
                                      (replay/read-records (log-file "it-system"))))
                 table (:tools row)]
             (is (vector? table) "the table is on the row")
             ;; NAME FOR NAME, not map-for-map: the row went through JSON on the way to
             ;; disk, so comparing the whole nested shape would be asserting the round trip.
             (is (= (map (comp :name :function) (tools/specs "it-system"))
                    (map (comp :name :function) table))
                 "the same table this session serves, name for name")
             (is (every? #(string? (get-in % [:function :description])) table)
                 "and the descriptions rode along -- the whole table, not just the names)")
             (is (not (contains? (replay/payload row) :tools))
                 "and the message the model read does not carry it")
             (is (not (str/includes? (str (:content (replay/payload row))) "<tools>"))
                 "nor a <tools> block in the text")))
         (testing "and not one frame carries any of it"
           ;; The markers are the ones only THIS run's assembly could have
           ;; written. The blocks' own tags are deliberately not among them: the
           ;; scripted run reads this repository's own README, which talks about
           ;; <tools> and <project> too, so those strings can reach the wire
           ;; legitimately. What is checked is a line no README has.
           (is (not (str/includes? on-wire "A DECLARED BLOCK")))
           (is (not (str/includes? on-wire "not bound to any project directory")))
           (is (not (str/includes? on-wire "available: "))))
         (testing "while the run itself is complete and well-formed"
           (is (= "RUN_STARTED" (:type (first frames))))
           (is (= "RUN_FINISHED" (:type (last frames))))
           (is (empty? (wire/violations frames)))))))
    (finally (support/wipe-hooks!))))

(deftest a-finished-job-is-announced-to-the-model-and-never-to-the-client
  ;; THE WHOLE PATH, over real HTTP. A background job belongs to a session, it ends while
  ;; nobody is looking, and the next run of that session carries its ending in the
  ;; history the model is sent -- with no frame carrying a word of it, because the
  ;; injection is the server's and the conversation stays the client's.
  ;;
  ;; AND IT IS SAID ONCE: the thing the client resends next time has no notice in it (it
  ;; never had one), so "have we told it?" is a fact about the registry and not about the
  ;; history. A run that re-derived the notice from the client's messages would announce
  ;; the same ending on every turn for the rest of the session, which is the failure this
  ;; case is here to make impossible.
  (let [t "it-jobs"
        {:keys [id path]} (jobs/start! t {:command "echo JOB-SAYS-$((6*7)); exit 0"})]
    (is (support/holds-within? #(re-find #"\[exit" (slurp path :encoding "UTF-8")) 10000)
        "the job finished before the run was even asked for")
    (try
      (with-server t (into script script)
                   (fn []
                     (io/delete-file (log-file t) true)
                     (let [first-body   (.body (post-run t))
                           first-lines  (wait-for-recorded (log-file t)
                                                           (fn [ls] (some #(= "message" (replay/kind %)) ls))
                                                           2000)
                           notices      (fn [lines]
                                          (filter #(and (= "message" (replay/kind %))
                                                        (= "user" (get-in (replay/payload %) [:role]))
                                                        (str/includes? (str (get-in (replay/payload %) [:content]))
                                                                       "<job-ended"))
                                                  lines))]
                       (testing "the model is sent the ending, without anybody asking"
                         (let [sent (notices first-lines)]
                           (is (= 1 (count sent)) "one job, one notice")
                           (is (str/includes? (str (get-in (replay/payload (first sent)) [:content]))
                                              "[exit 0]")
                               "how it went -- and nothing of what it said")
                           (is (str/includes? (str (get-in (replay/payload (first sent)) [:content]))
                                              (str "id=\"" id "\"")))))
                       (testing "and the client is never told"
                         ;; The same judgement the system message gets: it is the server's
                         ;; injection, so it is in the record and in no frame.
                         ;; THE MARKERS HAVE TO BE ONES ONLY THIS RUN'S INJECTION COULD
                         ;; CARRY. The block's TAG is not one of them: this repository's
                         ;; own README talks about `<job-ended …>`, the scripted run reads
                         ;; it, and a tool result is a legitimate way for those bytes to
                         ;; reach the wire -- the same trap the system-message case named.
                         ;; What is checked is the notice's own second line -- the read
                         ;; sentence naming THIS job's id, which nothing else in this run
                         ;; writes -- and the OUTPUT LINE of the command. The command's
                         ;; own text rides in the notice now (that is how it says which
                         ;; job), so the marker has to be what the command PRINTED, not
                         ;; what it was written as: `$((6*7))` is the shell's answer.
                         (is (not (str/includes? first-body (str "{\"job\": \"" id "\"}")))
                             "not even the read line, which is the notice's own")
                         (is (not (str/includes? first-body path)))
                         (is (not (str/includes? first-body "JOB-SAYS-42"))
                             "and nothing of what the job said"))
                       ;; A second run of the same session: the client resends its whole
                       ;; conversation, which has no notice in it.
                       (io/delete-file (log-file t) true)
                       (.body (post-run t))
                       (let [second-lines (wait-for-recorded (log-file t)
                                                             (fn [ls] (some #(= "message" (replay/kind %)) ls))
                                                             2000)]
                         (testing "while the second run is not told again"
                           (is (= [] (vec (notices second-lines)))
                               "the ending was handed over once, and that is the whole memory"))))))
      (finally (jobs/shutdown!)))))

(deftest switching-a-row-off-takes-its-text-out-of-the-next-runs-message
  ;; Both halves of the switch, at the edge and on a SECOND run of the same thread:
  ;; the declared row and the kernel's own row behave identically, because they are
  ;; rows in one table. Nothing is restarted and nothing is reset.
  (support/write-hooks! {:system-prompt [{:command "printf 'A DECLARED BLOCK\\n'"}]})
  (try
    (with-server
     "it-system-off"
     (fn []
       (io/delete-file (log-file "it-system-off") true)
       (post-run "it-system-off")
       (let [before (first (system-texts "it-system-off"))]
         (is (str/includes? before "A DECLARED BLOCK"))
         (is (str/includes? before "<env>"))
         (testing "switching the declared row and one of the kernel's off"
           (hooks/session-disable! "it-system-off" "system-prompt#0")
           (hooks/session-disable! "it-system-off" "builtin:project")
           (io/delete-file (log-file "it-system-off") true)
           (post-run "it-system-off")
           (let [after (first (system-texts "it-system-off"))]
             (is (not (str/includes? after "A DECLARED BLOCK")))
             (is (not (str/includes? after "<project>")))
             (testing "the rows that were NOT switched off are still there"
               (is (str/includes? after "<env>")))
             (testing "and the opening is untouched -- it is not in a hook's hands"
               (is (str/starts-with? after (str/trimr (slurp "prompt.md" :encoding "UTF-8")))))))
         (testing "switching them back on brings both blocks back"
           (hooks/session-enable! "it-system-off" "system-prompt#0")
           (hooks/session-enable! "it-system-off" "builtin:project")
           (io/delete-file (log-file "it-system-off") true)
           (post-run "it-system-off")
           (let [again (first (system-texts "it-system-off"))]
             (is (str/includes? again "A DECLARED BLOCK"))
             (is (str/includes? again "<env>")))))))
    (finally (support/wipe-hooks!))))

(deftest a-system-prompt-hook-that-says-no-stops-the-run-over-http
  ;; Exit 2 at this point is a HARD failure, not fail-open: what these hooks write
  ;; is what the system message is supposed to say, so a run that could not be told
  ;; it does not start. The client gets the hook's own words as the RUN_ERROR, and
  ;; no model was ever called.
  (support/write-hooks!
   {:system-prompt [{:command "echo 'no system message for you' >&2; exit 2"}]})
  (try
    (with-server
     "it-system-block"
     (fn []
       (let [frames (wire/frames-from-sse (.body (post-run "it-system-block")))]
         (testing "the run terminates as an error carrying the hook's stderr verbatim"
           (is (= "RUN_ERROR" (:type (last frames))))
           (is (str/includes? (str (:message (last frames))) "no system message for you")))
         (testing "and the vendor was never called -- the run never started"
           (is (not-any? #(= "TOOL_CALL_START" (:type %)) frames))
           (is (not-any? #(= "TEXT_MESSAGE_START" (:type %)) frames))))))
    (finally (support/wipe-hooks!))))

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
   "replay-e2e"
   (fn []
     (let [log (log-file "replay-e2e")]
       (io/delete-file log true)
       (post-run "replay-e2e")
       (wait-for-recorded
        log
        (fn [ls] (and (some #(= "provider/init" (replay/kind %)) ls)
                      (>= (count (filter #(= "message" (replay/kind %)) ls)) 4)))
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
       (post-run "images" {:append [{:id "u1" :role "user" :content parts}]})
       (let [lines (wait-for-recorded
                    log
                    (fn [ls] (some #(= "message" (replay/kind %)) ls))
                    3000)
             sent  (->> lines
                        (filter #(= "message" (replay/kind %)))
                        (map replay/payload)
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

(deftest a-picture-in-the-record-does-not-stop-the-next-run
  ;; 2026-09-21, the owner's own session (title 你是谁): 第一次可以发送图片，第二次继续报错 --
  ;; `unsupported content part type "image_url"; this harness carries "text" and "image"`.
  ;;
  ;; A `message` row IS the message the provider was handed (票 02 of `.scratch/jsonl-two-kinds`),
  ;; so a conversation born from the record holds the VENDOR's parts -- and the fold stamps the
  ;; entry's own `:id` on each message it folds. A session is born from the record whenever
  ;; nothing has asked for it for thirty seconds (the sweeper puts it away -- a person thinking
  ;; between two messages is enough) or this process restarted, so it is the SECOND
  ;; picture-bearing send that meets a rebuilt conversation, and a run that refuses leaves a
  ;; session that can never be continued again: every later birth reads the same row.
  ;;
  ;; THE ROW IS ASSERTED TO BE THERE BEFORE THE SESSION IS PUT AWAY. Without that, a rebuild
  ;; that raced the record writer would be missing the very row this case is about -- and the
  ;; test would pass for the wrong reason.
  (with-server
   {"images-reborn" [{:content "我看到这张图了。"}
                     {:content "第二句也答上了。"}]}
   (fn []
     (let [log   (log-file "images-reborn")
           parts [{:type "text" :text "这个布局怎么样"}
                  {:type "image" :source {:type "url" :value "https://example.test/a.png"}}]]
       (io/delete-file log true)
       (testing "the picture enters the conversation and the run answers"
         (is (= "RUN_FINISHED"
                (:type (last (wire/frames-from-sse
                            (.body (post-run "images-reborn"
                                             {:append [{:id "u1" :role "user" :content parts}]}))))))))
       (let [rows (wait-for-recorded
                  log
                  (fn [ls] (some #(and (= "message" (replay/kind %))
                                       (= "client" (:source %)))
                                  ls))
                  5000)]
         (is (= ["text" "image_url"]
                (->> rows
                     (filter #(and (= "message" (replay/kind %))
                                   (= "client" (:source %))))
                     (mapcat #(map :type (:content (replay/payload %))))))
             "the row a session is born from holds the vendor's part, not AG-UI's"))
       (testing "and a session put away and asked again still runs"
         ;; THE RUN HAS TO BE OVER *AND* ITS OWN HALF FOLDED IN before the session is put away.
         ;; `post-run` returns when the SSE body closes, while the frames are folded in at the
         ;; terminal (`harness.edge.sessions/settle!`) -- and on a path that throws after the
         ;; client has been told the run ended, that fold happens even later (the emitter's
         ;; catch, which settles again). A drop! that beat it would put the session away
         ;; underneath the fold, and the fold would then build it back out of the run's own
         ;; frames alone -- the picture arrived with the CLIENT and is not among them -- and
         ;; this case would read green without ever meeting the conversation it is about.
         (is (until #(and (not (http/running? "images-reborn"))
                          (some (fn [m] (= "assistant" (:role m)))
                                (sessions/messages "images-reborn")))
                    5000)
             "the run is over, and what it said is in the conversation")
         (sessions/drop! "images-reborn")
         ;; AND WHAT THE SESSION IS BORN FROM AGAIN IS THE ROW'S SHAPE, not the client's bytes:
         ;; the record keeps the message the PROVIDER was handed (票 02), and the fold stamps the
         ;; entry's own `:id` back onto it. Vendor parts under an AG-UI identity is the whole of
         ;; this case, so the premise is asserted rather than assumed -- the run below would
         ;; otherwise be free to answer the wrong question quietly.
         (let [reborn (sessions/messages "images-reborn")]
           (is (some (fn [m] (some #(= "image_url" (:type %))
                                   (when (sequential? (:content m)) (:content m))))
                     reborn)
               (str "the conversation is born from the record again, holding the vendor's part: "
                    (pr-str (mapv #(select-keys % [:id :role :content]) reborn)))))
         (let [frames (wire/frames-from-sse
                       (.body (post-run "images-reborn"
                                        {:append [{:id "u2" :role "user"
                                                   :content "还有这一张"}]})))]
           (is (= "RUN_FINISHED" (:type (last frames)))
               (str "the rebuilt conversation is handed a provider shape: "
                    (pr-str (mapv :message (filter #(= "RUN_ERROR" (:type %)) frames)))))))))))

(defn- sse-frames
  "The frames an SSE answer carried, in order -- what the CLIENT was handed."
  [body]
  (->> (str/split-lines (str body))
       (keep #(when (str/starts-with? % "data:")
                (json/read-str (str/trim (subs % 5)) :key-fn keyword)))
       vec))

(deftest the-frames-a-run-sends-are-the-frames-its-record-rebuilds
  ;; TICKET 03 OF `.scratch/jsonl-two-kinds` -- THE WHOLE POINT OF THE TWO ROWS. One real
  ;; run over a real socket, and the answer is two comparisons that say the record lost
  ;; nothing: the frames the client was handed are, frame for frame, the frames the record
  ;; holds (the harness's own facts filtered out -- `model/start` and `hook/*` are rows and
  ;; were never on the wire), and the conversation the fold rebuilds out of the rows alone
  ;; is the conversation the session was holding when the run ended. The deleted `input` row
  ;; used to be the second source of truth; if any case in this suite could be satisfied by
  ;; it, this is the one that would fail without it.
  (with-server
   "frames-rebuilt"
   (fn []
     (let [log (log-file "frames-rebuilt")]
       (io/delete-file log true)
       (let [sent (sse-frames (.body (post-run "frames-rebuilt")))]
         (is (seq sent) "the run answered with frames at all")
         (is (some #(= "TOOL_CALL_START" (:type %)) sent)
             "and with the tool round-trip in them, not just text")
         (wait-for-recorded
          log
          (fn [ls] (>= (count (filter #(= "event" (replay/kind %)) ls)) (count sent)))
          5000)
         (testing "what the socket carried is what the record kept, frame for frame"
           (is (= sent (mapv replay/payload
                             (filter replay/frame?
                                     (replay/lines->records (replay/read-lines log)))))))
         (testing "and the record alone rebuilds the conversation the session held"
           (let [rebuilt (mapv #(select-keys % [:role :content])
                               (replay/lines->messages (replay/read-lines log)))
                 live    (mapv #(select-keys % [:role :content])
                               (sessions/messages "frames-rebuilt"))]
             (is (= live rebuilt)))))))))

(defn- with-declaring-server
  "Like with-server, but the scripted pin DECLARES an input modality set -- which
  is how a pinned provider can stand in for a model with a stated capability.

  The spec (as opposed to the pin) is what the guard reads, and a pin is a whole
  provider map, so the declaration rides it. Returns the script atom so a test can
  assert the provider was never called: a drained script is a provider that ran."
  [thread-id declared turns f]
  (let [script (atom (vec turns))]
    (start-session! thread-id)
    (providers/use-provider! thread-id (assoc (fake/scripted turns) :input declared
                                        :script script))
    (let [stop (http/start! {:port 0})
          port (:local-port (meta stop))]
      (try (binding [*port* port] (f script))
           (finally (stop) (providers/use-provider! thread-id nil))))))

(deftest a-text-only-model-refuses-an-image-by-name-and-never-calls-the-vendor
  ;; The declaration is only worth anything if something enforces it. The vendor's
  ;; own answer to an undeclared modality is a 400 whose body names nothing useful,
  ;; arriving after the request was sent -- so the refusal happens here instead,
  ;; and the provider is not contacted at all.
  (with-declaring-server
   "guarded"
   #{:text}
   [{:content "should never be reached"}]
   (fn [script]
     (let [resp   (post-run "guarded"
                            {:append [{:id "u1" :role "user"
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
   "unguarded"
   #{:text :image}
   [{:content "saw it"}]
   (fn [script]
     (let [resp (post-run "unguarded"
                          {:append [{:id "u1" :role "user"
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
   "plain"
   #{:text}
   [{:content "hello"}]
   (fn [script]
     (let [body (.body (post-run "plain"))]
       (is (str/includes? body "RUN_FINISHED"))
       (is (not (str/includes? body "RUN_ERROR")))
       (is (empty? @script))))))

(deftest a-model-that-declares-nothing-is-not-guarded
  ;; An inline provider that never stated a capability promises nothing, so there
  ;; is nothing to enforce. Guarding it would invent a rule the configuration never
  ;; wrote -- and break every deployment that describes its endpoint directly.
  (with-declaring-server
   "silent"
   nil
   [{:content "went through"}]
   (fn [script]
     (let [body (.body (post-run "silent"
                                 {:append [{:id "u1" :role "user"
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
   "lifecycle"
   (fn []
     (io/delete-file (log-file "lifecycle") true)
     (post-run "lifecycle")
     (let [f     (log-file "lifecycle")
           lines (wait-for-recorded f
                                    (fn [ls]
                                      (>= (count (filter #(= "tools/post-execute" (replay/kind %)) ls)) 2))
                                    2000)
           phases (group-by replay/kind (filter #(.startsWith ^String (str (replay/kind %)) "tools/") lines))
           pre    (mapv replay/payload (get phases "tools/pre-execute"))
           ex     (mapv replay/payload (get phases "tools/execute"))
           post   (mapv replay/payload (get phases "tools/post-execute"))]
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
  (let [markers (support/temp-dir "http-parked")
        ok   (str (io/file markers "approved.txt"))
        veto (str (io/file markers "vetoed.txt"))
        call (fn [path] {:content ""
                         :tool-calls [{:id "c1" :name "write"
                                       :arguments {:path path :content "written"}}]})
        _    (tools/session-require-approval! "http-approve" "write")
        _    (tools/session-require-approval! "http-veto" "write")]
    (with-server
     {"http-approve" [(call ok) {:content "wrote it"}]
      "http-veto"    [(call veto) {:content "understood"}]}
     (fn []
       (let [asked   (wire/frames-from-sse (.body (post-run "http-approve")))
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
                        (.body (post-run "http-approve"
                                         ;; THE ACTION IS THE DECISION. Nothing is appended:
                                         ;; the parked assistant message with its unanswered
                                         ;; call is in the SESSION's history (settled at the end
                                         ;; of run one), and re-sending it would put a SECOND
                                         ;; copy of the same call in front of the vendor -- the
                                         ;; shape the history guard refuses.
                                         {:append []
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
         (let [asked-v (wire/frames-from-sse (.body (post-run "http-veto")))
               iid-v   (get-in (last asked-v) [:outcome :interrupts 0 :id])
               vetoed  (wire/frames-from-sse
                        (.body (post-run "http-veto"
                                         {:append []
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
                          (fn [ls] (some #(= "approval/decided" (replay/kind %)) ls))
                          2000)
                   decided (filter #(= "approval/decided" (replay/kind %)) lines)
                   pre     (filter #(and (= "tools/pre-execute" (replay/kind %))
                                          (= "c1" (get-in (replay/payload %) [:toolCallId])))
                                   lines)]
               ;; The verdict is a keyword in the event and a string on disk --
               ;; json has no keywords.
               (is (= "approved" (get-in (replay/payload (first decided)) [:verdict])))
               (is (= "c1" (get-in (replay/payload (first decided)) [:tool-call-id])))
               (is (= "approved" (get-in (replay/payload (first decided)) [:payload :decision])))
               (is (some #(= "needs-approval" (get-in (replay/payload %) [:outcome])) pre))
               (is (some #(= "approved" (get-in (replay/payload %) [:outcome])) pre))))))))))

(deftest an-answer-lands-behind-its-call-even-with-a-message-behind-the-call
  ;; TICKET 02 of `.scratch/session-opening`, over a real socket and a real record.
  ;;
  ;; The edge hands a run the session's history with its pre-LLM step ALREADY APPLIED,
  ;; so something the session holds -- a derived skill body, a job's ending -- can be
  ;; sitting behind the parked assistant message when the resume arrives. This case puts
  ;; a NEW ENTRY there as well (the action that approves carries a message of its own),
  ;; which is the plainest form of the same shape.
  ;;
  ;; What used to happen: the replayed answer was appended to the END of the list, where
  ;; the vendor's adjacency rule (`harness.kernel.llm/unanswered-tool-calls`) cannot see
  ;; it. The run then read its own history as still-parked and asked the same question
  ;; over again -- an interrupt, not an answer -- and a human approving it a second time
  ;; would have run the tool a second time.
  (let [markers (support/temp-dir "http-answer")
        ok      (str (io/file markers "answered.txt"))]
    (tools/session-require-approval! "http-answer" "write")
    (with-server
     {"http-answer" [{:content ""
                      :tool-calls [{:id "c1" :name "write"
                                    :arguments {:path ok :content "written"}}]}
                     {:content "wrote it"}]}
     (fn []
       (let [asked (wire/frames-from-sse (.body (post-run "http-answer")))
             iid   (get-in (last asked) [:outcome :interrupts 0 :id])]
         (testing "the first run parks, as it always did"
           (is (= "interrupt" (get-in (last asked) [:outcome :type])))
           (is (false? (.exists (io/file ok)))))

         (let [resumed (wire/frames-from-sse
                        (.body (post-run "http-answer"
                                         {:append [{:id "u2" :role "user" :content "and hurry"}]
                                          :resume [{:interruptId iid :status "resolved"
                                                    :payload {:decision "approved"}}]})))]
           (testing "the resumed run ANSWERS the parked call instead of asking again"
             (is (empty? (wire/violations resumed)))
             (is (= "RUN_FINISHED" (:type (last resumed))))
             (is (not (contains? (last resumed) :outcome))
                 "a natural end carries no outcome; a re-park would carry the interrupt")
             (is (some #(= "TOOL_CALL_RESULT" (:type %)) resumed))
             (is (= 1 (count (filter #(= "RUN_FINISHED" (:type %)) resumed)))))

           (testing "and the tool ran once, not twice"
             (is (= "written" (slurp ok :encoding "UTF-8"))))

           (testing "the record's returned side is what the KERNEL added, not the tail"
             ;; The submitted side is everything handed in; the returned side is what the
             ;; run put in. Counting past the first would file the person's own message
             ;; ("and hurry") as the kernel's answer AND lose the tool message that
             ;; really was one -- which is why the kernel says which messages it added
             ;; (:added on :run/done) and the edge does not work it out.
             (let [records  (wait-for-recorded
                             (log-file "http-answer")
                             (fn [ls] (some #(and (= "message" (replay/kind %))
                                                  (= "wrote it" (get-in (replay/payload %) [:content])))
                                            ls))
                             5000)
                   runs     (trajectory/run-segments records)
                   returned (:returned (last runs))]
               (is (= 2 (count runs)) "the park and the resume are two runs")
               (is (= ["tool" "assistant"] (mapv :role returned)))
               (is (= "c1" (:tool_call_id (first returned))))
               (is (= "wrote it" (:content (second returned))))
               (is (not-any? #(= "and hurry" (:content %)) returned)
                   "the person's message is not this run's own")))))))))

(deftest an-unknown-interrupt-is-refused-over-http
  ;; A restart loses the parking; a client resuming an interrupt this process
  ;; never parked must be told so, never quietly granted.
  (with-server
   "http-unknown"
   [{:content "never reached"}]
   (fn []
     (let [frames (wire/frames-from-sse
                   (.body (post-run "http-unknown"
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
  timeline is precisely about resolution, so these must not pin.

  IT ALSO STARTS THE SERVER, on a port the OS picks, and binds *port* for F. Every
  use of this wrapper needs exactly one server and none of them should name a port;
  owning the lifecycle next to the config it is paired with keeps the tests about
  what they are actually testing. See *port* for why no port is written down."
  [turns f]
  ;; The config file lives in the HOME, not beside the logs; ask harness.infra.home for
  ;; it rather than walking up from the log directory, whose depth is the tree's
  ;; business and has already changed once.
  (let [cfg-file (home/config-file)
        reg-file (home/providers-file)
        read-back (fn [f] (when (.exists f) (slurp f :encoding "UTF-8")))
        old-cfg (read-back cfg-file)
        old-script @fake/test-script]
    (try
      (reset! fake/test-script (vec turns))
      ;; ONE FILE, TWO SECTIONS: the default tier names alpha, and the catalog that
      ;; defines alpha and beta is its :providers section.
      (spit cfg-file
            (support/config-text
             "{:provider :alpha}"
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
                                                     :max-output-tokens 4096}}}}))
            :encoding "UTF-8")
      (io/delete-file reg-file true)
      (let [stop (http/start! {:port 0})
            port (:local-port (meta stop))]
        (try (binding [*port* port] (f))
             (finally (stop))))
      (finally
        (spit cfg-file (or old-cfg "{:default {:protocol :fake}}\n") :encoding "UTF-8")
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
           image  {:append [{:id "u1" :role "user"
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
           (let [e (error (.body (post-run id image)))]
             (is (some? e))
             (is (str/includes? (:message e) "beta-plain") "names the model")
             (is (str/includes? (:message e) "image") "and the modality it will not take")))
         (testing "the SAME input is served by a model that declares images"
           (providers/set-override! id {:provider :alpha})
           (is (= #{:text :image} (:input (providers/active-provider id))))
           (is (nil? (error (.body (post-run id image))))))
         (finally (providers/set-override! id nil)))))))

(deftest a-configuration-naming-a-count-is-refused-rather-than-silently-dropped
  ;; A TIER MAY NOT CARRY A MODEL'S COUNTS, and after ticket 03 that rule is about the
  ;; CONFIGURATION rather than about a request: a run cannot name a provider any more, so
  ;; the mistake is made where a session's selection is made -- config.edn (or the
  ;; session override, which POST /api/model writes), both of which meet the same
  ;; `selection`.
  ;; The rule is unchanged, and so is what it costs to meet it: the run is TERMINATED
  ;; with the catalog's own sentence, naming the field it could not use and where the
  ;; field belongs instead -- not a run that starts, ignores it, and reports success.
  ;; The run is the one that resolves the configuration, so the refusal surfaces here.
  ;;
  ;; A HOME OF ITS OWN, because the mistake is planted in config.edn, and NO PIN, because
  ;; a pinned provider wins resolution outright -- a case that pinned would never resolve
  ;; and would pass while the rule was broken.
  (support/with-temp-env [root _home]
   (spit (io/file root "config.edn")
         "{:default {:provider :beta :context-window 200000}}\n"
         :encoding "UTF-8")
   (with-bare-server
    (fn []
      (let [id    "count-in"
            error (fn [body]
                    (first (keep #(let [f (json/read-str (str/trim (subs % 5)) :key-fn keyword)]
                                    (when (= "RUN_ERROR" (:type f)) f))
                                 (filter #(str/starts-with? % "data:") (str/split-lines body)))))]
        (ensure-session! id)
        (let [e (error (.body (post-run id)))]
          (is (some? e) "the run is terminated rather than served with the field dropped")
          (is (str/includes? (:message e) "context-window") "the field is named")
          (is (str/includes? (:message e) ":providers")
              "and the run says where it belongs instead"))
        (testing "and nothing was resolved or recorded for it"
          (is (nil? (providers/override-for id)))
          (let [lines (str/split-lines (slurp (log-file id) :encoding "UTF-8"))]
            (is (not-any? #(str/includes? % "provider/init") lines)
                "a run that could not resolve writes no init line"))))))))

(deftest the-provider-timeline-is-init-once-then-changes
  ;; Ticket 03, over the real edge. A session's provider history lands as
  ;; exactly one init line and one line per change -- never a snapshot per run.
  (with-resolved-config
   [{:content "hello"} {:content "hello"}]
   (fn []
     (let [id   "http-prov"]
       (io/delete-file (log-file id) true)
       ;; Run one: the init line lands. The scripted turn is a plain reply,
       ;; so the run does not touch the provider.
       (post-run id)
       (let [after-first (wait-for-recorded
                          (log-file id)
                          (fn [ls] (some #(= "provider/init" (replay/kind %)) ls))
                          2000)]
         (testing "the first run lands exactly one init line, before the run's own messages"
           (let [kinds    (mapv replay/kind after-first)
                 prompt-i (first (keep-indexed (fn [i r]
                                                 (when (= "system-prompt" (:source r)) i))
                                               after-first))]
             (is (= 1 (count (filter #(= "provider/init" %) kinds))))
             ;; THE RECORD'S FIRST `message` ROW IS THE PROMPT (`:source "system-prompt"`),
             ;; because a `message` row IS an element of the array the model read and the
             ;; prompt is that array's first element. The action's own rows follow it. The
             ;; init line is an EVENT row above both, so it still comes first for a reader
             ;; of the timeline.
             (is (some? prompt-i) "the run recorded the prompt it assembled")
             (is (= prompt-i (.indexOf kinds "message"))
                 "the first message row in the record is the prompt")
             (is (< (.indexOf kinds "provider/init") prompt-i)
                 "and the init line is in front of it: a reader meets what served the
                  conversation before it meets the conversation")))
         (testing "it carries the selection, what it resolved to, the source, and NO key value"
           (let [p (replay/payload (first (filter #(= "provider/init" (replay/kind %)) after-first)))]
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
         (post-run id)
         (let [after-second (wait-for-recorded
                             (log-file id)
                             (fn [ls] (>= (count (filter #(and (= "message" (replay/kind %)) (= "client" (:source %))) ls)) 2))
                             2000)]
           (testing "a later run of the same thread does not repeat the init"
             (is (= 1 (count (filter #(= "provider/init" (replay/kind %)) after-second)))))))))))

(deftest a-recorded-change-is-drained-into-the-log
  ;; THE DRAIN, end to end. The provider outbox has had no producer since the
  ;; `session-configure` tool was removed, so no production path records a
  ;; `provider/changed` line today -- but the EDGE still drains the outbox on every
  ;; run (harness.edge.context reads those lines back), and that is what this
  ;; drives: record the change the way the tool body did, run, read the line.
  ;;
  ;; A session override holds ONLY the knobs the session owns (not the resolved
  ;; endpoint -- that is what :resolved and the init line are for). So the change's
  ;; before and after show the session's tier, and the chain between consecutive
  ;; changes is exactly the test of "what moved in this session".
  (with-resolved-config
   [{:content "hello"}]
   (fn []
     (let [id "http-change"]
       (try
         (io/delete-file (log-file id) true)
         ;; Seed the session with a baseline the change can stand on. Then record
         ;; two changes -- one knob, then a vendor switch -- through the same two
         ;; calls the tool body made: one atom operation, then the fact left in
         ;; the outbox for the edge to write down.
         (providers/set-override! id {:model "alpha-big"})
         (doseq [change [{:reasoning-effort "high"} {:provider "beta" :model "beta-plain"}]]
           (let [{:keys [before after resolved]} (providers/swap-override! id change)]
             (providers/record-provider-change! id before after "a-tool" after resolved)))
         (testing "the session now serves the changed values"
           (is (= "high" (:reasoning-effort (providers/active-provider id)))))
         ;; Run once so the edge drains the outbox to the log.
         (post-run id)
         (let [lines (wait-for-recorded
                      (log-file id)
                      (fn [ls] (>= (count (filter #(= "provider/changed" (replay/kind %)) ls)) 2))
                      2000)
               changes (mapv replay/payload (filter #(= "provider/changed" (replay/kind %)) lines))
               [a b]   changes]
           (testing "the change is on disk, before -> after, marked approved"
             (is (= "approved" (:verdict a)))
             (is (= "alpha-big" (get-in a [:before :model]))
                 "the session's pre-change tier is the baseline that stood")
             (is (= "alpha-big" (get-in a [:after :model]))
                 "the model never moved; only the effort did")
             (is (= "high" (get-in a [:after :reasoning-effort])))
             (is (= "a-tool" (:trigger a))
                 "the trigger is whatever path the recorder was told -- here, a test")
             (is (= {:model "alpha-big" :reasoning-effort "high"}
                    (select-keys (:override a) [:model :reasoning-effort]))
                 "the override is the full session tier after the change")
             (testing "and it records what that tier resolved to, so a reader
                       months later is not reading today's catalog"
               (is (= "https://x/v1" (get-in a [:resolved :base-url])))
               (is (= "alpha-big" (get-in a [:resolved :model])))
               (is (= ["image" "text"] (get-in a [:resolved :input])))))
           (testing "and a vendor switch lands as a change that moved the endpoint"
             ;; The change NAMES the new vendor's model: the tier still held alpha's
             ;; id, and a vendor that does not declare it is refused rather than
             ;; carried across (see providers/resolve-override). Under the old shape
             ;; this change was recorded as {:before {} :after {}} -- a line that
             ;; documents nothing.
             (is (= "high" (get-in b [:before :reasoning-effort]))
                 "the second change starts where the first ended")
             (is (= "beta" (get-in b [:after :provider]))
                 "the line names the vendor that was selected")
             (is (not= {} (:after b)) "and is not an empty change")
             (is (= "https://y/v1" (get-in b [:resolved :base-url]))
                 "with the endpoint that vendor resolves to")
             (is (= "beta-plain" (get-in b [:resolved :model])))
             (testing "and the counts moved with the model, not left at alpha's"
               (is (= 128000 (get-in b [:resolved :context-window])))
               (is (= 4096 (get-in b [:resolved :max-output-tokens])))))
           (testing "and the outbox is empty: draining is once, and it happened"
             (is (empty? (providers/take-provider-changes! id)))))
         (finally (providers/set-override! id nil)))))))

(deftest answers-the-cors-preflight
  (with-server
   "preflight"
   (fn []
     (let [req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* "/")))
                    (.method "OPTIONS" (HttpRequest$BodyPublishers/noBody))
                    (.build))
           resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/discarding))]
       (is (= 204 (.statusCode resp)))
       (is (= http/ui-origin (header resp "Access-Control-Allow-Origin")))
       (is (str/includes? (header resp "Access-Control-Allow-Methods") "POST"))))))

;; ---------------------------------------------------- which origin is answered

(defn- ask-from
  "A request to PATH carrying the `Origin` a browser sends from a page at ORIGIN --
  the header every other caller in this file leaves out, because every other caller
  is standing in for a tool or a curl rather than for a page."
  [path origin method]
  (let [b (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* path)))
              (.header "Origin" origin))
        b (if (= :options method)
            (.method b "OPTIONS" (HttpRequest$BodyPublishers/noBody))
            (.GET b))]
    (.send (HttpClient/newHttpClient) (.build b)
           (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))))

(defn- answered-origins
  "EVERY value of the answer's Access-Control-Allow-Origin, as a vector -- [] when
  the answer names none.

  A VECTOR RATHER THAN THE FIRST VALUE, because one header and two headers saying
  the same thing are not the same answer. This route's headers are written on the
  FIRST FRAME (see harness.edge.http/runner) while `handler` merges onto the ring
  response, and a browser is entitled to refuse an answer that names an origin
  twice. The `header` helper above reads `.firstValue` and cannot see that."
  [resp]
  (vec (.allValues (.headers resp) "Access-Control-Allow-Origin")))

(deftest says-which-origin-per-request-and-not-once-per-process
  ;; A PAGE ON THIS MACHINE IS ANSWERED WHATEVER PORT IT IS ON. The dev UI moves
  ;; between ports on its own -- vite picks one, `--ui-port` moves it, the backend
  ;; moves with `--port` -- and the rule used to be ONE origin settled at startup,
  ;; which meant the launcher had to tell this process the port it had just handed
  ;; vite, and go on telling it. What the rule reads now is the HOST in the
  ;; request's own Origin, so there is no port for the two sides to keep in step.
  ;;
  ;; IT IS STILL A BOUNDARY. `localhost.example.com` is not `localhost`, which is
  ;; why the host is matched WHOLE and never by suffix -- matching the tail of a
  ;; name is how a rule becomes a hole. A page that is neither on this machine nor
  ;; the one named at startup gets NO ORIGIN AT ALL, and refusing it is the
  ;; browser's job once it has been told nothing: naming some third origin would
  ;; only be a lie about who this process talks to.
  (with-server
   {"cors-origin" [{:content "\u597d\u3002"}]}
   (fn []
     (testing "every port on this machine is answered, and answered as itself"
       ;; ARBITRARY PORTS, UNLIKE EACH OTHER ON PURPOSE, and one row written with no
       ;; port at all -- none of these is the port of anything running, and the
       ;; case is that none of it is READ.
       (doseq [origin ["http://localhost" "http://localhost:5211"
                       "http://127.0.0.1:8080" "https://localhost:443"
                       "http://[::1]:3000"]]
         (is (= [origin] (answered-origins (ask-from "/api/threads" origin :get)))
             (str origin " is a page on this machine"))))

     (testing "a name that merely ENDS in a local one is not on this machine"
       (doseq [origin ["http://localhost.example.com" "http://127.0.0.1.example.com"
                       "http://notlocalhost:1234"]]
         (is (= [] (answered-origins (ask-from "/api/threads" origin :get)))
             (str origin " is somewhere else"))))

     (testing "and an opaque origin is not a page this edge can name"
       (is (= [] (answered-origins (ask-from "/api/threads" "null" :get)))))

     (testing "a preflight from this machine names it too"
       (let [resp (ask-from "/api/threads" "http://localhost:5211" :options)]
         (is (= 204 (.statusCode resp)))
         (is (= ["http://localhost:5211"] (answered-origins resp)))))

     (testing "the run edge answers on its FIRST FRAME, and exactly once"
       (let [resp (post-run "cors-origin" {} "http://localhost:5211")]
         (is (= 200 (.statusCode resp)))
         (is (= ["http://localhost:5211"] (answered-origins resp)))
         (is (str/includes? (.body resp) "RUN_FINISHED")))))))

;; ------------------------------------------------- the management edge

(def ^:private project-dir
  (support/temp-dir "http-project"))

(def ^:private project-dir-2
  (support/temp-dir "http-project-2"))

(defn- wipe-dir!
  "Empty DIR, making it if it is absent.

  STILL WORTH HAVING WITH TEMP DIRECTORIES: the project directories below are one
  per GROUP of tests rather than one per test, so a test that asserts on the WHOLE
  content of a project's session list still has to start from nothing -- what it
  must no longer do is clear against a previous RUN, which is what these blocks used
  to be for and what mkdtemp does by construction (see
  harness.test-support/temp-dir). Deepest-first, because `io/delete-file` does not
  recurse: it calls File.delete, which refuses a non-empty directory and says
  nothing when `silently` is true, so the one-line version leaves the tree exactly
  where it was.

  AND WHAT IT MAKES IS REGISTERED, because two of the directories it is handed are
  COMPOSED beside a temp tree -- `(str listing-dir \"-2\")` and the archive one -- so
  `temp-dir` never heard about them and the sweep at the end of a run did not either:
  they were 2 of the 9 trees a full run still left behind once `temp-dir`'s own trees
  were being taken (measured 2026-09-22). See harness.test-support/track-temp-dir!."
  [d]
  (run! #(io/delete-file % true) (reverse (file-seq (io/file d))))
  (.mkdirs (io/file d))
  (support/track-temp-dir! d))

(defn- same-dir?
  "Are A and B two spellings of one directory?

  BY CANONICAL PATH, NOT BY STRING, and not by NAME. Three honest differences are
  in play and only the first is about spelling: `bind!` keeps what it was asked
  for (harness.cap.project/absolute) while the server's answers come back through
  canonicalization; on macOS the temp directory is reached through `/var` ->
  `/private/var`; and these scratch directories are mkdtemp's, so their NAME ends
  in a random run of digits -- an `ends-with?` check against the label, which is
  what the assertions below used to say, stopped being true the day the directories
  stopped being composed. None of the three is what any of them was about; the
  directory the answer names is."
  [a b]
  (= (.getCanonicalPath (io/file a)) (.getCanonicalPath (io/file b))))

(defn- bound-lines
  "The thread's project/bound audit lines, oldest first, read from wherever the
  thread's log IS.

  Since the logs became a projects tree, a bound thread's file lives in its
  project's workspace -- which is why the audit line for a bind is written INTO
  the new directory: the bind moved the session, and the very next line it writes
  is already in the place it moved to. So this asks the same question the server
  asks (harness.cap.project/identity-for, then sanitize), rather than assuming the
  reserved workspace. A test that assumed would pass while the audit trail was in
  the wrong project."
  [tid]
  (->> (str/split-lines
        (slurp (log-file-for tid) :encoding "UTF-8"))
       (replay/lines->records)
       (filterv #(= "project/bound" (replay/kind %)))))

(defn- api-call
  "A plain JSON call to the management edge -- the /api/* endpoints, not the
  AG-UI run endpoint. Returns the raw HttpResponse."
  [method path body]
  (let [b (.header (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* path)))
                   "Content-Type" "application/json")
        b (if (= :post method)
            (.POST b (HttpRequest$BodyPublishers/ofString (str body) StandardCharsets/UTF_8))
            (.GET b))]
    (.send (HttpClient/newHttpClient) (.build b)
           (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))))

(defn- read-json [resp]
  (json/read-str (.body resp) :key-fn keyword))

(defn- sidebar-listing
  "GET /api/projects as the sidebar reads it: {:projects [..] :tasks [..]}.

  ONE READER FOR THE SHAPE, because the shape itself is what these cases are about
  and five call sites each digging into it by hand is five places to update the day it
  moves -- which it did: the listing used to BE the vector of projects."
  []
  (read-json (api-call :get "/api/projects" nil)))

(defn- listed-projects
  "The projects in the sidebar's listing, in the order the server sent them."
  []
  (:projects (sidebar-listing)))

(defn- listed-tasks
  "The tasks in the sidebar's listing, in the order the server sent them."
  []
  (:tasks (sidebar-listing)))

(defn- all-listed-rows
  "EVERY row the sidebar would draw, both halves in one sequence: the sessions of every
  project, then the flat tasks. For a question about the listing as a WHOLE -- 'did that
  request add a conversation this home now lists' -- which the two halves would otherwise
  each answer wrongly."
  []
  (concat (mapcat :sessions (listed-projects)) (listed-tasks)))

(defn- listed-row
  "The row the sidebar draws for TID, wherever it is drawn: a project's session list
  or the flat task list. Nil when this home lists no such conversation.

  ASKED THE WAY THE SIDEBAR ASKS IT: one GET, the whole listing, no per-thread
  endpoint -- a row is the client's whole view of the fact."
  [tid]
  (or (some (fn [project] (first (filter #(= tid (:threadId %)) (:sessions project))))
            (listed-projects))
      (first (filter #(= tid (:threadId %)) (listed-tasks)))))

(defn- register-session!
  "The route that makes one conversation a session of this home, called the way the
  sidebar calls it. TID may be nil, which is the body a caller sends when it wants the
  server to name the conversation (ticket 03)."
  [tid]
  (api-call :post "/api/sessions" (json/write-str {:threadId tid})))

(defn- task?
  "Whether the sidebar draws TID in the flat task list."
  [tid]
  (some? (first (filter #(= tid (:threadId %)) (listed-tasks)))))

(defn- projects-holding
  "The canonical paths of the projects whose session list contains TID -- the other
  half of 'where is this conversation drawn', which is never a task."
  [tid]
  (->> (listed-projects)
       (filter #(some (fn [s] (= tid (:threadId s))) (:sessions %)))
       (mapv :path)))

(deftest the-run-edge-is-one-route-under-the-api-prefix
  ;; THE RUN USED TO BE THE CATCH-ALL. It sat at the server ROOT and every path the
  ;; table did not know fell to it, so a mistyped management route was read as a run
  ;; -- a request with no RunAgentInput in it -- and answered with whatever that
  ;; produced. It is now one route among the rest, and what is left over says so.
  ;;
  ;; `api-call` (just above) is the plain JSON caller, which is the point here: none
  ;; of these is a run, and none of them should reach the kernel.
  (with-server
   "routes"
   (fn []
     (testing "the run edge is POST /api/agent, and only POST"
       (is (= 405 (.statusCode (api-call :get "/api/agent" nil)))))
     (testing "the server root is not a run any more"
       (is (= 404 (.statusCode (api-call :post "/" "{}")))))
     (testing "a mistyped management path is a 404 that names itself, not a run"
       (let [resp (api-call :get "/api/thread" nil)]
         (is (= 404 (.statusCode resp)))
         (is (str/includes? (str (:error (read-json resp))) "no such route"))))
     (testing "and so is anything else the table does not know"
       (is (= 404 (.statusCode (api-call :get "/nope" nil))))))))

;; ------------------------------------------------------------ the action's face
;;
;; WHAT A RUN MAY CARRY, after ADR 0002 decision 9 landed in ticket 03 of
;; `.scratch/sessions-live-on-the-server`: `threadId`, `append`, `tools` and `resume`.
;; THREE THINGS LEFT that face -- the accumulated `messages`, the client's own `runId`,
;; and a per-request `provider` -- and the cases below are about what the door does with
;; each. They go AROUND `post-run` on purpose: its premise is "the page sends a run for a
;; session that exists", and every question here is about the door itself.

(defn- raw-run
  "POST BODY to /api/agent exactly as written: no session made for it, no field added.
  Answers the raw HttpResponse -- the caller decides whether it is a refusal or a stream."
  [body]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* "/api/agent")))
                (.header "Content-Type" "application/json")
                (.header "Accept" "text/event-stream")
                (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body)
                                                            StandardCharsets/UTF_8))
                (.build))]
    (.send (HttpClient/newHttpClient) req
           (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))))

(deftest a-run-of-a-session-this-home-does-not-know-is-refused-by-name
  ;; THE SILENT CREATE IS GONE, and the silence was the worse half of it: the edge used to
  ;; register whatever id arrived, so a typo, a stale bookmark or a hand-written curl
  ;; produced a NEW conversation that looked exactly like the one that was meant. A run is
  ;; an action ON a conversation, and an action on something that does not exist is an
  ;; error -- the one that says which (ticket 03, judgement 2).
  (with-server
   "the-one-session-this-home-knows"
   (fn []
     (let [resp (raw-run {:threadId "a-conversation-nobody-made"
                          :append   [{:id "u1" :role "user" :content "go"}]
                          :tools    []})
           body (read-json resp)]
       (testing "404, and the answer names the id it could not find"
         (is (= 404 (.statusCode resp)) (str "got " (.statusCode resp)))
         (is (str/includes? (str (:error body)) (pr-str "a-conversation-nobody-made"))
             (str "the id is not in the refusal: " (pr-str (:error body)))))
       (testing "and it says what DOES make one"
         (is (str/includes? (str (:error body)) "POST /api/sessions")))
       (testing "the refusal creates NOTHING: no row, no record, no conversation"
         (is (false? (project/session-exists? "a-conversation-nobody-made")))
         (is (not (.exists (log-file "a-conversation-nobody-made")))))))))

(deftest a-run-body-that-still-carries-messages-is-refused-by-name
  ;; NOT IGNORED, AND NOT MERGED, which is the whole point of retiring the field: a client
  ;; written against the old contract sends the conversation it holds, and a server that
  ;; read that as an append would DOUBLE the conversation -- the failure this feature
  ;; exists to end, dressed up as compatibility. So the door says which field it did not
  ;; take and what to send instead.
  (with-server
   "carries-the-old-face"
   (fn []
     (let [resp (raw-run {:threadId "carries-the-old-face"
                          ;; both fields, because that is what an old client sends
                          :messages [{:id "u1" :role "user" :content "看看这个项目"}]
                          :append   [{:id "u2" :role "user" :content "看看这个项目"}]
                          :tools    []})
           body (read-json resp)]
       (testing "400, by name"
         (is (= 400 (.statusCode resp)) (str "got " (.statusCode resp)))
         (is (= "messages" (:field body))
             "the field is named as a FIELD, so a client can go and find it in its code")
         (is (str/includes? (str (:error body)) "append")
             "and the answer points at what replaced it"))
       (testing "no run started: the record is empty and the conversation is untouched"
         (is (not (.exists (log-file "carries-the-old-face"))))
         (is (= [] (sessions/messages "carries-the-old-face"))))))))

;; ------------------------------------------------- a conversation another process serves
;;
;; THE CROSS-PROCESS HALF OF 'ONE AUTHORITY PER CONVERSATION' (ADR 0002 decision 7).
;; `refuse-second-run!` below is the same rule INSIDE one process; the in-process
;; registry cannot see another JVM at all, and this is what does -- a row in the store
;; (`harness.cap.claims`) that says which process is serving the conversation. What a
;; client gets is a 409 naming that process, and what it does NOT get is a run: the
;; record is a file two writers interleave into, and nothing in a line says whose it is.
;;
;; THE LIVE-OTHER-PROCESS ROWS HERE ARE WRITTEN BY HAND, and that is honest rather than
;; a shortcut: a claim row is pid + start instant + instance id, and this process's own
;; pid with somebody else's instance is exactly what a live second process leaves.
;; harness.cap.claims-test forks the real thing; what THIS file owes is the edge's own
;; behavior -- the status, the sentence, the body, and what stays open.

(defn- claim-row [thread-id]
  (first (db/select "SELECT * FROM session_claims WHERE thread_id = ?" (str thread-id))))

(defn- served-elsewhere!
  "Leave the row a LIVE OTHER PROCESS would leave for THREAD-ID, REPLACING whatever
  this process had: the question these cases ask is what the EDGE does when somebody
  else holds the conversation, and a case that had just run a conversation of its own
  holds a claim too."
  [thread-id]
  (let [us (claims/this-process)]
    (db/with-transaction
      (fn [c]
        (db/execute! c "DELETE FROM session_claims WHERE thread_id = ?" (str thread-id))
        (db/execute! c "INSERT INTO session_claims
                          (thread_id, instance, token, pid, started_at, since)
                        VALUES (?, ?, ?, ?, ?, ?)"
                     (str thread-id) "another-process" (str (java.util.UUID/randomUUID))
                     (:pid us) (:started-at us) (System/currentTimeMillis))))))

(defn- settled-file
  "F's [bytes mtime] once two reads 50ms apart agree, or the last reading after MS.

  THE RECORD LAGS THE SESSION BY DESIGN, and the writer flushes in its own time: the
  run's own frames reach the client before every line has landed on disk, so 'what the
  file holds' is only a fact once it stops changing. `record/pending?` is not enough --
  it counts what has been handed to the writer, and the writer's own write can still be
  in flight behind an empty queue (measured: a 20KB `model/start` line landed 3ms after
  the queue went quiet)."
  [f ms]
  (loop [last nil
         left (long ms)]
    (let [now [(.length f) (.lastModified f)]]
      (if (or (= now last) (pos? left))
        (if (= now last)
          now
          (do (Thread/sleep 50) (recur now (- left 50))))
        now))))

(defn- with-no-claims [f]
  (let [wipe #(db/with-transaction (fn [c] (db/execute! c "DELETE FROM session_claims")))]
    (wipe)
    (try (f) (finally (wipe)))))

(deftest a-run-against-a-conversation-another-process-serves-is-refused-and-says-by-whom
  (with-no-claims
   (fn []
     (with-server
      "served-elsewhere"
      (fn []
        ;; A CONVERSATION THAT EXISTS, so the read-only half below has something to
        ;; read: a session with no log at all answers 404 on every log route, which
        ;; would make "reads still work" pass for the wrong reason.
        (is (= 200 (.statusCode (post-run "served-elsewhere"))))
        (served-elsewhere! "served-elsewhere")
        (let [before (settled-file (log-file "served-elsewhere") 10000)
              resp (raw-run {:threadId "served-elsewhere"
                             :append   [{:id "u2" :role "user" :content "go"}]
                             :tools    []})
              body (read-json resp)]
          (testing "409, and the answer names the PROCESS that is in the way"
            (is (= 409 (.statusCode resp)) (str "got " (.statusCode resp)))
            (is (= (:pid (claims/this-process)) (get-in body [:holder :pid])))
            (is (pos? (get-in body [:holder :since])))
            (is (str/includes? (str (:error body)) (str (:pid (claims/this-process))))
                "a person reading the refusal can find the process to stop"))
          (testing "and it says the way out -- stop it, or wait"
            (is (str/includes? (str (:error body)) "Stop that process")))
          (testing "NO RUN STARTED: the record did not grow a byte"
            (is (= before (settled-file (log-file "served-elsewhere") 10000))
                "the log is the thing two writers would interleave into")
            (is (= "another-process" (:instance (claim-row "served-elsewhere")))
                "and the refusal did not take the claim over"))
          (testing "READING IS STILL OPEN -- the rule is about actions, not access"
            (is (= 200 (.statusCode (api-call :get "/api/threads" nil))))
            (is (= 200 (.statusCode (api-call :post (str "/api/threads/served-elsewhere/rebuild") nil))))
          (testing "and once nobody is serving it, the same run is served here"
            ;; Both halves of 'nobody', because they are two different facts: the row
            ;; is gone from the store, and this process's own view of the session --
            ;; which the refused run never touched -- is put away, so the next ask
            ;; builds it again and claims it.
            (db/with-transaction
              (fn [c] (db/execute! c "DELETE FROM session_claims WHERE thread_id = ?" "served-elsewhere")))
            (sessions/drop! "served-elsewhere")
            (let [again (post-run "served-elsewhere")]
              (is (= 200 (.statusCode again)))
              (is (claims/mine? (claims/holder "served-elsewhere"))
                  "the run that was served is the one that claimed it"))))))))))

(deftest a-claim-whose-process-is-gone-does-not-refuse-anything
  ;; THE OTHER HALF OF THE DECISION, and the one that keeps the feature from turning a
  ;; crash into a dead conversation: a process that was killed leaves its row behind,
  ;; and the next process takes it over. The pid here is this process's own and the
  ;; start instant is not -- which is exactly what a REUSED pid looks like, and the
  ;; reason a claim records when its owner started rather than only its number.
  (with-no-claims
   (fn []
     (with-server
      "stale-claim"
      (fn []
        (let [us (claims/this-process)]
          (db/with-transaction
            (fn [c]
              (db/execute! c "INSERT INTO session_claims
                                (thread_id, instance, token, pid, started_at, since)
                              VALUES (?, ?, ?, ?, ?, ?)"
                           "stale-claim" "the-previous-owner" (str (java.util.UUID/randomUUID))
                           (:pid us) 1 (System/currentTimeMillis))))
          (let [resp (post-run "stale-claim")]
            (is (= 200 (.statusCode resp)) (str "got " (.statusCode resp)))
            (is (= (:instance us) (:instance (claim-row "stale-claim")))
                "the run took the claim over from a process that is gone"))))))))

(deftest a-rebind-does-not-move-a-log-another-process-is-serving
  ;; BINDING IS THE OTHER THING THAT ACTS ON A LOG: the rebind direction MOVES the
  ;; session's file, and a writer in the serving process holds the path it opened, so a
  ;; move underneath it splits one conversation across two files. Same read-only rule,
  ;; same sentence, one route over.
  (wipe-dir! project-dir)
  (with-no-claims
   (fn []
     (with-server
      "bind-elsewhere"
      (fn []
        (let [tid  "bind-elsewhere"
              bind (api-call :post "/api/project" (json/write-str {:threadId tid :dir project-dir}))]
          (is (= 200 (.statusCode bind)) "the premise: it binds while nobody is serving it")
          (let [bound (:dir (read-json bind))]
            (served-elsewhere! tid)
            (let [resp (api-call :post "/api/project"
                                 (json/write-str {:threadId tid :dir project-dir}))
                  body (read-json resp)]
              (testing "409 by name, and the binding it would have moved is untouched"
                (is (= 409 (.statusCode resp)) (str "got " (.statusCode resp)))
                (is (= (:pid (claims/this-process)) (get-in body [:holder :pid])))
                (is (= bound (project/binding-for tid))
                    "the binding it would have moved is exactly where it was"))))))))))
(deftest a-provider-in-the-run-body-is-not-consulted
  ;; CHOOSING A MODEL IS AN ACTION (`POST /api/model`, which writes the session's slice
  ;; and records the change), NOT A FIELD A RUN CARRIES (ticket 03, judgement 4). The
  ;; strongest form of that claim is a body naming a vendor THIS HOME DOES NOT HAVE: if
  ;; the request tier were still layered on top, the run would be refused by that name.
  (with-server
   "request-tier"
   [{:content "the session's own model answered"}]
   (fn []
     (let [resp (post-run "request-tier" {:provider {:provider :no-such-vendor
                                                     :model    "theirs"}})]
       (testing "the run is served by the SESSION's tier"
         (is (= 200 (.statusCode resp)) (str "got " (.statusCode resp)))
         (is (str/includes? (str (:content (last (sessions/messages "request-tier"))))
                            "the session's own model answered")
             "the answer in the conversation is not the pinned model's"))
       (testing "and the request's tier is never resolved"
         (is (not (str/includes? (process-log) "no-such-vendor"))))
       (testing "and the record names the tier that WAS used, never the body's"
         ;; 票 02 TOOK THE `input` ROW AWAY, and the request's own fields with it: a run no
         ;; longer logs the whole inbound vector, only the entries it was handed and the facts
         ;; about itself. What this guard needs is still here -- the provider/init row says
         ;; which tier ran, and it is not the one the body named.
         ;; THE RUN'S OWN CALL LINE NAMES WHAT IT WENT TO: `model/start` is written from the
         ;; provider the SERVER resolved, so the body's tier appearing nowhere on the record is
         ;; the same statement as 'it was never resolved'.
         (let [call (first (filter #(= "model/start" (replay/kind %))
                                   (wait-for-recorded
                                    (log-file-for "request-tier")
                                    (fn [ls] (some #(= "model/start" (replay/kind %)) ls))
                                    2000)))]
           (is (some? call) "the run recorded the call it made")
           (is (not (str/includes? (json/write-str (replay/payload call)) "no-such-vendor"))
               "the body's tier appears nowhere: it was never resolved")
           (is (not (str/includes? (json/write-str (replay/payload call)) "theirs"))
               "nor its model")))))))

(deftest the-run-id-is-minted-here-and-the-record-is-what-names-it
  ;; JUDGEMENT 3: a client that can NAME a run can collide with one and replay one, so the
  ;; server mints the id -- and because the record is what carries it, a rebuild answers
  ;; the SAME names back rather than minting look-alikes.
  (with-server
   "minted-run"
   [{:content "first"} {:content "second"}]
   (fn []
     (let [resp (raw-run {:threadId "minted-run"
                          :append   [{:id "u1" :role "user" :content "go"}]
                          :tools    []
                          :runId    "the-name-the-client-wanted"})]
       (is (= 200 (.statusCode resp))
           "a body carrying runId is not refused -- the field simply has no say")
       (let [runs (fn [] (mapv :runId (filter #(and (= "message" (replay/kind %))
                                                    (= "client" (:source %)))
                                              (log-lines-for "minted-run"))))
             first-run (first (runs))]
         (testing "the run on the record is named by the server, not by the body"
           (is (string? first-run))
           (is (not= "the-name-the-client-wanted" first-run))
           (is (not (str/includes? first-run "the-name-the-client-wanted"))))
         (testing "every run of the session gets its own name"
           (post-run "minted-run" {:append [{:id "u2" :role "user" :content "again"}]})
           (is (= 2 (count (runs))))
           (is (apply distinct? (runs)) "two runs share one name, and the record cannot tell them apart"))
         (testing "and a rebuild answers those names back -- twice the same"
           ;; THE STABLE IDS COME FROM THE RECORD, not from an id that could be minted
           ;; again: the frames carry the run they belong to, and the rebuild only reads.
           (let [rebuild (fn [] (read-json (api-call :post "/api/threads/minted-run/rebuild" nil)))
                 r1      (rebuild)
                 r2      (rebuild)]
             (is (seq (:messages r1)))
             (is (= (:messages r1) (:messages r2)) "two rebuilds of one log disagreed about the ids")
             (let [assistants (filter #(= "assistant" (:role %)) (:messages r1))]
               (is (= 2 (count assistants)) "one answer per run, so both names are under test")
               (is (str/starts-with? (str (:id (first assistants))) first-run)
                   "the first run's answer is not named after the run the record names")
               (is (every? (fn [m] (some #(str/starts-with? (str (:id m)) %) (runs)))
                           assistants)
                   "an answer is named after a run this record does not have")))))))))

(deftest the-session-s-opening-context-enters-the-conversation-once
  ;; JUDGEMENT 5, and the reason it is about CACHING as much as about tidiness: the
  ;; context used to be rendered as a trailing user message on EVERY run, so the prefix a
  ;; provider caches grew a new tail each turn and missed every time. It is a message the
  ;; SESSION is born with now, and a later action that still sends one is ignored -- its
  ;; conversation has a beginning already.
  (with-server
   "born-with-context"
   [{:content "one"} {:content "two"} {:content "three"}]
   (fn []
     (post-run "born-with-context" {:context [{:description "repo" :value "x"}]})
     (testing "the birth action puts it in the conversation, once"
       (let [messages (sessions/messages "born-with-context")]
         (is (= ["u1" "session-context"]
                (mapv :id (take 2 messages)))
             "the context rides in the conversation, behind the client's own message")
         (is (= "- repo: x" (:content (second messages))))
         (is (= 1 (count (filter #(= "session-context" (:id %)) messages))))))
     (testing "and no later action adds a second one, however loudly it asks"
       (post-run "born-with-context" {:append  [{:id "u2" :role "user" :content "two"}]
                                      :context [{:description "repo" :value "x"}]})
       (post-run "born-with-context" {:append [{:id "u3" :role "user" :content "three"}]})
       (let [messages (sessions/messages "born-with-context")]
         (is (= 1 (count (filter #(= "session-context" (:id %)) messages)))
             "the context entered a second time: the birth action is the only one that may")
         (is (= ["u1" "session-context" "u2" "u3"]
                (mapv :id (filter #(= "user" (:role %)) messages)))
             "the conversation is the two questions and the one context, in order")))
     (testing "and the record says what each action BROUGHT, not what it typed"
       ;; `:added` is the fold's field: the birth action brought two entries (its own
       ;; message and the context, which the client never sent), and each later one
       ;; brought exactly its own message.
       ;; THE ROWS THEMSELVES SAY IT (票 02): an entry is a `message` row whose envelope
       ;; carries its `id`, so 'what did this action bring' is 'which id-bearing rows did it
       ;; write'. The birth action brought two -- its own message and the context, which the
       ;; client never sent -- and every later one brought exactly its own.
       (let [brought (->> (log-lines-for "born-with-context")
                          (filter #(and (= "message" (replay/kind %)) (:id %)))
                          (partition-by :runId)
                          (mapv #(mapv :id %)))]
         (is (= 3 (count brought)))
         (is (= ["u1" "session-context"] (first brought)))
         (is (= ["u2"] (second brought)))
         (is (= ["u3"] (nth brought 2))))))))

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
  (wipe-dir! project-dir)
  (with-server
   "it-proj"
   (fn []
     (let [tid (str "proj-" (java.util.UUID/randomUUID))]
       (testing "an unbound thread answers with dir nil, not an error"
         (let [resp (api-call :get (str "/api/project?threadId=" tid) nil)]
           (is (= 200 (.statusCode resp)))
           (is (= {:threadId tid :dir nil} (read-json resp)))
           (is (= http/ui-origin (header resp "Access-Control-Allow-Origin")))))
       (testing "a GET without threadId is a 400"
         (is (= 400 (.statusCode (api-call :get "/api/project" nil)))))
       (testing "binding a real directory answers with its absolute path"
         (let [resp  (api-call :post "/api/project"
                               (json/write-str {:threadId tid :dir project-dir}))
               reply (read-json resp)]
           (is (= 200 (.statusCode resp)))
            (is (.isAbsolute (io/file (:dir reply))))
            (is (same-dir? (:dir reply) project-dir))
           (testing "a GET now sees the binding"
             (is (= (:dir reply)
                    (:dir (read-json (api-call :get (str "/api/project?threadId=" tid) nil))))))))
       (testing "binding a missing directory is a NAMED 400"
         (let [resp (api-call :post "/api/project"
                              (json/write-str {:threadId tid :dir (str project-dir "/nope")}))
               reply (read-json resp)]
           (is (= 400 (.statusCode resp)))
           (is (str/includes? (:error reply) "no such directory"))))
       (testing "a malformed body is a 400"
         (is (= 400 (.statusCode (api-call :post "/api/project" "{not json")))))
       (let [bound (bound-lines tid)]
         (testing "exactly ONE project/bound audit line is on disk"
           (is (= 1 (count bound)))
           (is (nil? (:runId (first bound))) "a binding happens outside any run")
           (is (nil? (get-in (replay/payload (first bound)) [:before]))
               "a FIRST bind has no previous directory")
           (is (same-dir? (get-in (replay/payload (first bound)) [:after]) project-dir))
           (is (= "http" (get-in (replay/payload (first bound)) [:via]))))
         (testing "the two failed binds added no second line"
           (is (= 1 (count (filter #(= "project/bound" (replay/kind %)) bound)))))
         (testing "and the line landed in the workspace the bind moved the session to"
           ;; The audit line is written AFTER the bind, so it is the first thing
           ;; this session writes in its new home -- which is the whole reason the
           ;; log is moved rather than left behind: a session's timeline is one file.
           (is (empty? (filter #(= "project/bound" (replay/kind %))
                               (try (replay/lines->records
                                          (str/split-lines
                                           (slurp (log-file tid) :encoding "UTF-8")))
                                    (catch java.io.FileNotFoundException _ []))))
               "the reserved workspace holds no binding line for this thread")))))))

(deftest a-new-session-in-a-project-is-named-by-the-server
  ;; TICKET 03, the other half of the id question: 'start a conversation in this
  ;; directory' is ONE action. The body names no thread, so the server names it AND
  ;; binds it -- which is why the page does not call /api/sessions and then bind what
  ;; came back: a bind that failed would leave an UNBOUND conversation behind, and an
  ;; empty session is the one thing a client may no longer make (ADR 0002 decision 9).
  ;;
  ;; WHAT THE PAGE LEANS ON is asserted here: the id it is handed is a session of this
  ;; home the moment the answer lands -- bound to the directory that was asked for, in
  ;; the listing the sidebar draws -- because the next thing the page does is switch to
  ;; it and let a run be sent under that id.
  (wipe-dir! project-dir)
  (with-server
   "it-proj-mint"
   (fn []
     (testing "a body with no threadId is GIVEN one, bound to the directory"
       (let [resp   (api-call :post "/api/project" (json/write-str {:dir project-dir}))
             body   (read-json resp)
             minted (:threadId body)]
         (is (= 200 (.statusCode resp)))
         (is (string? minted))
         (is (not= "" minted))
         (is (same-dir? (:dir body) project-dir))
         (testing "it is a session OF THE PROJECT, not a task"
           (is (not (task? minted)))
           (is (= [(.getCanonicalPath (io/file project-dir))] (projects-holding minted))))
         (testing "and it is a row in the listing the sidebar reads"
           ;; It has a log ALREADY, and that is not a mistake: the bind lands a
           ;; project/bound audit line, so the file exists before a word is typed (a
           ;; row's figures come from the tree). What matters here is that the row IS
           ;; there, in the project, the moment the answer landed.
           (is (some? (listed-row minted)))
           (is (false? (:running (listed-row minted)))))))
     (testing "the next ask is a SECOND conversation"
       ;; MINTING IS PER REQUEST: two clicks on the project's button are two
       ;; conversations, and neither of them is a conversation the page made up.
       (let [again (:threadId (read-json (api-call :post "/api/project"
                                                   (json/write-str {:dir project-dir}))))]
         (is (string? again))
         (is (not= "" again))
         (is (= 1 (count (filter #(= again (:threadId %)) (all-listed-rows))))
             "and each of them is listed exactly once")))
     (testing "a body that DOES name one still binds that one"
       (let [named "proj-named-by-the-caller"]
         (is (= named (:threadId (read-json (api-call :post "/api/project"
                                                      (json/write-str {:threadId named
                                                                       :dir project-dir}))))))
         (is (= [(.getCanonicalPath (io/file project-dir))] (projects-holding named)))))
     (testing "a body with no dir is still a 400, and mints nothing"
       (let [before (count (all-listed-rows))]
         (is (= 400 (.statusCode (api-call :post "/api/project" (json/write-str {})))))
         (is (= before (count (all-listed-rows)))
             (str "a refused bind leaves no conversation behind -- which is the whole"
                  " reason the id is minted inside this route rather than by a caller"
                  " who would then bind it")))))))

(def ^:private listing-dir
  ;; Its own directory pair, not the shared project-dir: other tests in this
  ;; namespace bind sessions into those, and this one asserts on the WHOLE content
  ;; of a project's session list. Sharing would make it pass or fail depending on
  ;; which tests ran first.
  (support/temp-dir "http-listing"))

(def ^:private listing-dir-2 (str listing-dir "-2"))

(def ^:private archive-dir
  ;; Its own pair as well -- see listing-dir's note. This test asserts on the whole
  ;; session list of a project, so a shared directory would make it pass or fail
  ;; depending on which test ran first.
  (support/temp-dir "http-archive"))

(def ^:private archive-dir-2 (str archive-dir "-2"))

(deftest the-projects-listing-joins-the-store-with-the-disk
  ;; Ticket 04's data source. The sidebar cannot be built from either side alone:
  ;; the store says which projects and sessions exist and which are archived, the
  ;; tree says how big each log is and when it last changed. So this checks the
  ;; join -- and the states that only exist BECAUSE the two sides are different:
  ;; a session in the store whose file is not there, and a log in the tree that no
  ;; session owns.
  (wipe-dir! listing-dir)
  (wipe-dir! listing-dir-2)
  (with-server
   {"listing-a" script "listing-b" script "listing-unbound" script}
   (fn []
     (let [list-projects listed-projects
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
                           (api-call :post "/api/project"
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
           (testing "and nothing has been said in it yet, so it has no name"
             ;; `:firstUserText` is the store's answer rather than the log's, and a
             ;; session that has never run has nothing in the store to answer with.
             ;; The sidebar falls back to the id for these (the client's business).
             (is (nil? (:firstUserText session))))
                      (testing "and the listing says NOTHING about the file the bind just wrote"
            ;; The bind does write one line -- its own audit trail, in the workspace it just
            ;; created (see project-post's ordering note). What changed is that the LISTING
            ;; reports nothing read off a log: the two disk facts it used to carry are gone
            ;; from the payload entirely, and this is the case that would notice if either
            ;; came back.
            (is (.exists (log-file-for "listing-a")) "the audit line is on disk")
            (is (not (contains? session :bytes)))
            (is (not (contains? session :lastActivity)))
            (is (nil? (:lastSentAt session))
                "and nothing has been SENT to it yet -- a bind is not a send"))
           (testing "after a real run the row carries the name and the send time, both store facts"
            (let [before (System/currentTimeMillis)]
              (is (= "RUN_FINISHED"
                     (:type (last (wire/frames-from-sse (.body (post-run "listing-a")))))))
              (testing "and the session has acquired a NAME from the run that carried its first message"
                ;; `post-run` sends '看看这个项目' as the run's user message, and that is what
                ;; `remember-send!` writes -- once. Read back through the LISTING rather than
                ;; through any endpoint of its own, because the listing is the only place a
                ;; client ever sees this fact.
                (is (= "看看这个项目" (:firstUserText (row-for listing-dir "listing-a")))))
              (let [sent (:lastSentAt (row-for listing-dir "listing-a"))]
                (is (integer? sent) "the send is stamped in the store")
                (is (<= before sent (System/currentTimeMillis))
                    "and it is the moment of THIS send, not a file's mtime"))))
           (testing "a session whose file is NOT there is still a row -- and the store never noticed"
            ;; The state the sidebar must survive: the store says this session exists, the disk
            ;; says nothing about it. It used to be visible as two nulls in the listing; now the
            ;; row's facts are the store's, so the only thing that changed is that the log is gone.
            (bind! "listing-b" listing-dir)
            (let [f (log-file-for "listing-b")]
              (is (.delete f) "the file is removed by hand, as a person tidying the tree would")
              (let [session (row-for listing-dir "listing-b")]
                (is (= "listing-b" (:threadId session)) "the row is still there")
                (is (nil? (:lastSentAt session))
                    "nothing has been sent to it -- null, which is not a zero-byte file")
                (testing "and it sorts LAST: 'never sent to' is not 'brand new'"
                  ;; The old order put a row with no log FIRST, because such a row was one somebody
                  ;; had just asked for. A session is created by its first send now, so a row with
                  ;; no time is an abandoned one.
                  (is (= ["listing-a" "listing-b"]
                         (map :threadId (:sessions (project-named listing-dir)))))))))
           (testing "and a run moves a session back to the top -- the order is the store's clock"
            ;; What replaced 'no log sorts first': two sends, in order, and the row sent to most
            ;; recently is first. Nothing here reads a file's mtime.
            (Thread/sleep 20)
            (post-run "listing-b")
            (is (= ["listing-b" "listing-a"]
                   (map :threadId (:sessions (project-named listing-dir)))))
            (Thread/sleep 20)
            (post-run "listing-a")
            (is (= ["listing-a" "listing-b"]
                   (map :threadId (:sessions (project-named listing-dir))))))))
       (testing "a different directory is a different project"
         (bind! "listing-other" listing-dir-2)
         (is (= (.getCanonicalPath (io/file listing-dir-2)) (:path (project-named listing-dir-2))))
         (is (= 1 (count (:sessions (project-named listing-dir-2))))))
       (testing "a conversation nobody has ever owned is in no project AND is no task"
         ;; The store decides which conversations exist, and it has never been told
         ;; about this one: not a project session, not a task, nothing to draw -- and
         ;; GET /api/threads still shows the file to anyone diagnosing.
         (is (nil? (row-for listing-dir "not-bound-anywhere")))
         (is (not (task? "not-bound-anywhere"))))
       (testing "and an unbound session that RUNS is a TASK -- flat, outside every project"
         ;; THIS IS WHAT CHANGED when the sidebar grew its second half. It used to be
         ;; in no list at all (it has a log but nothing owned it); now the run itself
         ;; is what makes it a session of this home, and the sidebar draws it in the
         ;; flat task list. Still in no PROJECT -- that half is unchanged.
         (is (= "RUN_FINISHED"
                (:type (last (wire/frames-from-sse (.body (post-run "listing-unbound")))))))
         (is (not-any? #(= "listing-unbound" (:threadId %))
                       (mapcat :sessions (list-projects))))
         (is (task? "listing-unbound"))
         (is (some #(= "listing-unbound" (:threadId %))
                   (json/read-str (.body (api-call :get "/api/threads" nil)) :key-fn keyword))
             "and the raw tree view still shows it, so nothing is hidden"))))))

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
  (wipe-dir! archive-dir)
  (wipe-dir! archive-dir-2)
  (with-server
   {"arch-a" script "arch-b" script "arch-other" script}
   (fn []
     (let [archive! (fn [tid flag]
                      (api-call :post (str "/api/threads/" tid "/archive")
                                (json/write-str {:archived flag})))
           bind!    (fn [tid dir]
                      (api-call :post "/api/project"
                                (json/write-str {:threadId tid :dir dir})))
           sessions (fn [dir]
                      (->> (listed-projects)
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
       (post-run "arch-a")
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

       (testing "an archived session still lists -- flagged -- and its send time did not move"
           ;; The server does not decide what to DO with the flag: grouping is the
           ;; screen's business, and a listing that dropped archived rows would make
           ;; 'where did my session go' a question with no server-side answer. Flipping
           ;; the flag is a ROW write, and the clock is the row's other fact -- if the
           ;; archival path had re-derived it from the file, this is where it would show.
           (let [before (:lastSentAt (row archive-dir "arch-a"))]
             (is (true? (:archived (row archive-dir "arch-a"))))
             (archive! "arch-a" false)
             (is (false? (:archived (row archive-dir "arch-a"))))
             (is (= before (:lastSentAt (row archive-dir "arch-a"))))
             (archive! "arch-a" true)
             (is (true? (:archived (row archive-dir "arch-a"))))
             (is (= before (:lastSentAt (row archive-dir "arch-a"))))))

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
         (is (= 400 (.statusCode (api-call :post "/api/threads/arch-a/archive"
                                           (json/write-str {:archived "yes"})))))
         (is (= 400 (.statusCode (api-call :post "/api/threads/arch-a/archive"
                                           (json/write-str {})))))
         (is (= 400 (.statusCode (api-call :post "/api/threads/arch-a/archive"
                                           "{not json")))))

       (testing "GET is refused -- this route has an effect"
         (is (= 405 (.statusCode (api-call :get "/api/threads/arch-a/archive" nil)))))

       (testing "and the archive route does not swallow the rebuild route beside it"
         ;; The two share their path SHAPE. A matcher that answered for the wrong
         ;; verb would show up as a rebuild that archived something.
         (let [body (read-json (api-call :post "/api/threads/arch-a/rebuild" ""))]
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
     (let [id   "http-model"]
       (try
         (testing "the default: the selection and what the catalog resolved it to"
           (let [resp (api-call :get (str "/api/model?threadId=" id) nil)
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
           (let [body (read-json (api-call :get (str "/api/model?threadId=" id) nil))]
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
           (let [body (read-json (api-call :get (str "/api/model?threadId=" id) nil))]
             (is (= "alpha-bare" (:model body)))
             (is (not (contains? body :context-window))
                 "silence about a number is a fact, not a zero")
             (is (not (contains? body :max-output-tokens)))))
         (testing "an unbound/unknown thread is still an answer, not a 400"
           (let [resp (api-call :get "/api/model?threadId=who-is-this" nil)
                 body (read-json resp)]
             (is (= 200 (.statusCode resp)))
             (is (= "alpha-small" (:model body))
                 "the default tier resolves for any thread with no session in play")))
         (testing "a missing threadId is an answer too -- the process-wide slot"
           (is (= 200 (.statusCode (api-call :get "/api/model" nil))))
           (is (= ["image" "text"] (:input (read-json (api-call :get "/api/model" nil))))))
         (testing "it is READ-ONLY: no audit line of its own"
           (let [f (log-file id)]
             (is (not (.exists f))
                 "asking a question must not write to the session's log")))
         (finally (providers/set-override! id nil)))))))

(deftest the-model-endpoint-reports-a-sparse-configuration-as-sparse
  ;; Absent is a fact, not a failure. A provider described inline that declared no
  ;; modalities has nothing to report, and saying so beats inventing a default or
  ;; returning an error the client has to interpret.
  (with-server
   "sparse"
   (fn []
     (let [tid  (str "sparse-" (java.util.UUID/randomUUID))
           ;; The seeded test config IS the inline form, and it declares nothing
           ;; -- which is exactly the sparse case.
           body (read-json (api-call :get (str "/api/model?threadId=" tid) nil))]
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
  (wipe-dir! project-dir-2)
  (let [stub! (fn [f] (alter-var-root #'http/*directory-chooser* (constantly f)))
        real  http/*directory-chooser*]
    (try
      (with-server
       "it-pick"
       (fn []
         (let [tid (str "pick-" (java.util.UUID/randomUUID))]
           (testing "picking answers the chosen absolute path"
             (stub! (fn [] project-dir-2))
             (let [resp (api-call :post "/api/project/pick" nil)]
               (is (= 200 (.statusCode resp)))
               (is (= http/ui-origin (header resp "Access-Control-Allow-Origin")))
               (is (= project-dir-2 (:dir (read-json resp))))))
           (testing "cancelling is an answer (dir nil), not an error"
             (stub! (fn [] nil))
             (let [resp (api-call :post "/api/project/pick" nil)]
               (is (= 200 (.statusCode resp)))
               (is (= {:dir nil} (read-json resp)))))
           (testing "a blank answer reads as a cancel too"
             (stub! (fn [] "   "))
             (is (= {:dir nil} (read-json (api-call :post "/api/project/pick" nil)))))
           (testing "GET is refused -- this call opens a window, so it is not cacheable"
             (is (= 405 (.statusCode (api-call :get "/api/project/pick" nil)))))
             (testing "picking binds NOTHING: the ordinary POST is still the only route"
              (stub! (fn [] project-dir-2))
              ;; By IDENTITY (same-dir? -- see its note above), not by spelling: what
              ;; this test is about is the session landing in the directory the picker
              ;; answered with, not how that directory is spelled.
             (let [picked (:dir (read-json (api-call :post "/api/project/pick" nil)))]
               (is (same-dir? project-dir-2 picked)
                   "the picker hands back the directory it was told to")
               (is (nil? (project/binding-for tid)) "no binding until the POST lands")
               (is (= 200 (.statusCode (api-call :post "/api/project"
                                                 (json/write-str {:threadId tid :dir picked})))))
               (is (same-dir? picked (project/binding-for tid))
                   "binding lands the session in that same directory")
               (let [bounds (bound-lines tid)]
                 (is (= 1 (count bounds)) "exactly one audit line, from the POST")
                 (is (= "http" (get-in (replay/payload (first bounds)) [:via])))))))))
      (finally (alter-var-root #'http/*directory-chooser* (constantly real))))))

(deftest the-folder-picker-knows-which-dialog-this-platform-has
  ;; Which dialog there is, is a fact about the machine -- and so is there being
  ;; none. The table answers both, because a platform with no dialog must not
  ;; fall through to another platform's command and fail there: that fall-through
  ;; is how "cannot open" used to reach the endpoint as "the human cancelled".
  ;;
  ;; The process is stubbed for every case: the real one opens a window and waits
  ;; for a person, which a test run must never do.
  (let [real   http/*dialog-launcher*
        calls  (atom [])
        stub!  (fn [answer]
                 (reset! calls [])
                 (alter-var-root #'http/*dialog-launcher*
                                 (constantly (fn [argv] (swap! calls conj argv) answer))))
        throw! (fn [ex]
                 (reset! calls [])
                 (alter-var-root #'http/*dialog-launcher*
                                 (constantly (fn [argv] (swap! calls conj argv) (throw ex)))))
        by-exe! (fn [m]
                  (reset! calls [])
                  (alter-var-root #'http/*dialog-launcher*
                                  (constantly (fn [argv]
                                                (swap! calls conj argv)
                                                (let [answer (get m (first argv))]
                                                  (if (instance? Throwable answer)
                                                    (throw answer)
                                                    answer))))))]
    (try
      (testing "the table: windows and macOS have a dialog, a third platform has none"
        (is (some? (#'http/chooser-for :windows)))
        (is (some? (#'http/chooser-for :macos)))
        (is (nil? (#'http/chooser-for :linux))
            "'none' is an answer of its own, not a missing case"))
      (testing "this JVM's own platform reads as one of the three"
        (is (contains? #{:windows :macos :other} (#'http/this-platform))))
      (testing "a platform with no dialog answers UNAVAILABLE, and says why"
        (let [original @#'http/this-platform]
          (try
            (alter-var-root #'http/this-platform (constantly (fn [] :other)))
            (let [answer (#'http/platform-directory!)]
              (is (= :unavailable (:status answer)))
              (is (string? (:reason answer))
                  "the reason is the sentence the UI shows, not a code"))
            (finally (alter-var-root #'http/this-platform (constantly original))))))

      (testing "windows: asks PowerShell for the native dialog, in an STA"
        (stub! {:out "C:\\work\\项目\r\n" :exit 0})
        (is (= {:status :picked :dir "C:\\work\\项目"} (#'http/powershell-directory!))
            "a Chinese directory name survives the round trip")
        (let [argv (first @calls)]
          (is (= "powershell.exe" (first argv)))
          (is (contains? (set argv) "-STA")
              "WinForms needs a single-threaded apartment")
          (is (str/includes? (last argv) "FolderBrowserDialog"))
          (is (str/includes? (last argv) "OutputEncoding")
              "the path is written UTF-8, not in this console's code page")))
      (testing "windows: a BOM and the trailing newline come off, the spaces stay"
        (stub! {:out "\uFEFFC:\\work\\my project\r\n" :exit 0})
        (is (= "C:\\work\\my project" (:dir (#'http/powershell-directory!)))))
      (testing "windows: empty output is a cancellation -- somebody pressed Cancel"
        (stub! {:out "" :exit 0})
        (is (= {:status :cancelled} (#'http/powershell-directory!))))
      (testing "windows: a dialog that could not be shown is UNAVAILABLE (exit 2)"
        (stub! {:out "" :exit 2})
        (is (= :unavailable (:status (#'http/powershell-directory!)))))
      (testing "windows: a missing powershell.exe tries pwsh before giving up"
        (by-exe! {"powershell.exe" (java.io.IOException. "CreateProcess error=2")
                  "pwsh.exe"      {:out "D:\\工作\r\n" :exit 0}})
        (is (= {:status :picked :dir "D:\\工作"} (#'http/powershell-directory!)))
        (is (= ["powershell.exe" "pwsh.exe"] (mapv first @calls))
            "one name's absence is not an answer -- the next is tried"))
      (testing "windows: neither here is UNAVAILABLE, not a cancellation"
        (throw! (java.io.IOException. "CreateProcess error=2"))
        (is (= :unavailable (:status (#'http/powershell-directory!)))))

      (testing "macOS: the chosen path comes back as picked"
        (stub! {:out "/Users/me/proj\n" :exit 0})
        (is (= {:status :picked :dir "/Users/me/proj"} (#'http/osascript-directory!))))
      (testing "macOS: a nonzero exit is a cancellation -- how osascript says Cancel"
        (stub! {:out "User canceled" :exit 1})
        (is (= {:status :cancelled} (#'http/osascript-directory!))))
      (testing "macOS: no osascript on this machine is UNAVAILABLE, not a cancel"
        (throw! (java.io.IOException. "CreateProcess error=2"))
        (is (= :unavailable (:status (#'http/osascript-directory!)))
            "THE OLD BUG: this used to answer nil, which read as a cancellation"))
      (finally (alter-var-root #'http/*dialog-launcher* (constantly real))))))

(deftest a-machine-with-no-folder-dialog-says-so-instead-of-cancelling
  ;; Three answers, not two. Cancelled is silence; unavailable is an answer --
  ;; and the endpoint has to be able to tell a human which one happened,
  ;; because "the window never appeared" and "you dismissed the window" ask for
  ;; two different next moves.
  (let [stub! (fn [f] (alter-var-root #'http/*directory-chooser* (constantly f)))
        real  http/*directory-chooser*]
    (try
      (with-server
       "it-pick-unavailable"
       (fn []
         (testing "no dialog here: 501, and the reason is a sentence for the UI"
           (stub! (fn [] {:status :unavailable :reason "no dialog here"}))
           (let [resp (api-call :post "/api/project/pick" nil)
                 body (read-json resp)]
             (is (= 501 (.statusCode resp)))
             (is (= "no dialog here" (:error body)))
             (is (not (contains? body :dir))
                 "and it does not also look like a chosen path")))
         (testing "cancelling is STILL silence: 200, dir nil, nothing said"
           (stub! (fn [] {:status :cancelled}))
           (let [resp (api-call :post "/api/project/pick" nil)]
             (is (= 200 (.statusCode resp)))
             (is (= {:dir nil} (read-json resp)))))
         (testing "an unavailable pick binds nothing, exactly like a cancellation"
           (stub! (fn [] {:status :unavailable :reason "no dialog here"}))
           (let [tid (str "pick-none-" (java.util.UUID/randomUUID))]
             (is (= 501 (.statusCode (api-call :post "/api/project/pick" nil))))
             (is (nil? (project/binding-for tid))
                 "the ordinary POST is still the only route that binds")))))
      (finally (alter-var-root #'http/*directory-chooser* (constantly real))))))

(deftest rebinding-moves-the-root-and-lands-a-timeline
  ;; Ticket 04: rebinding an already-bound thread is the ordinary case --
  ;; resolution moves to the new directory immediately (the relative write
  ;; lands THERE, not in the old one), GET answers the new binding, and each
  ;; audit line carries before -> after so the directory timeline reads
  ;; straight off the log. The UI switches with the same entry point: this
  ;; endpoint IS the entry point it uses.
  ;; Between tests, not against a previous run: both directories belong to this
  ;; namespace and more than one test drives them, so the not-in-the-OLD-directory
  ;; assertion starts from nothing. (A previous JVM's residue used to be the other
  ;; half of this, and mkdtemp is what removed it.)
  (wipe-dir! project-dir)
  (wipe-dir! project-dir-2)
  (with-server
   {"rebind-run" bound-script}
   (fn []
     (let [tid "rebind-run"]
       (testing "the first bind's line carries before nil"
         (let [resp (api-call :post "/api/project"
                              (json/write-str {:threadId tid :dir project-dir}))]
           (is (= 200 (.statusCode resp))))
         (let [line (first (bound-lines tid))]
           (is (nil? (get-in (replay/payload line) [:before])))
            (is (same-dir? (get-in (replay/payload line) [:after]) project-dir))))
        (testing "rebinding answers and displays the new directory"
          (let [resp (api-call :post "/api/project"
                               (json/write-str {:threadId tid :dir project-dir-2}))]
            (is (= 200 (.statusCode resp)))
            (is (same-dir? (:dir (read-json resp)) project-dir-2))
            (testing "GET reflects the switch"
              (is (same-dir?
                   (:dir (read-json (api-call :get (str "/api/project?threadId=" tid) nil)))
                   project-dir-2)))))
       (testing "a relative write after the switch lands in the NEW directory"
         (let [frames (wire/frames-from-sse (.body (post-run tid)))]
           (is (= "RUN_FINISHED" (:type (last frames))))
           (is (empty? (wire/violations frames))))
         (is (= "landed" (slurp (io/file project-dir-2 "e2e.txt") :encoding "UTF-8")))
         (is (not (.exists (io/file project-dir "e2e.txt")))))
       (testing "the log reads as a before -> after timeline"
         (let [bounds (bound-lines tid)]
           (is (= 2 (count bounds)))
            (is (same-dir? (get-in (replay/payload (nth bounds 1)) [:before]) project-dir))
            (is (same-dir? (get-in (replay/payload (nth bounds 1)) [:after]) project-dir-2))))))))

(deftest a-bound-thread-writes-into-its-project-over-the-real-edge
  ;; The full vertical: bind through the management edge the way the UI will,
  ;; then a real AG-UI run whose scripted tool call writes a RELATIVE path --
  ;; which must land inside the project directory, not the process cwd.
  (io/delete-file project-dir true)
  (.mkdirs (io/file project-dir))
  (with-server
   {"proj-run" bound-script}
   (fn []
     (let [resp (api-call :post "/api/project"
                          (json/write-str {:threadId "proj-run" :dir project-dir}))]
       (is (= 200 (.statusCode resp))))
     (let [frames (wire/frames-from-sse (.body (post-run "proj-run")))]
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
     {"move-run" bound-script}
     (fn []
       (let [tid "move-run"]
         (api-call :post "/api/project" (json/write-str {:threadId tid :dir project-dir}))
         (testing "the run leaves a log in the FIRST project's workspace"
           (is (= "RUN_FINISHED" (:type (last (wire/frames-from-sse (.body (post-run tid)))))))
           (is (.exists old)))
         (let [before-lines (count (str/split-lines (slurp old :encoding "UTF-8")))]
           (testing "the rebind answers OK and the log MOVED with it"
             (let [resp (api-call :post "/api/project"
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
             (let [rows (->> (json/read-str (.body (api-call :get "/api/threads" nil))
                                            :key-fn keyword)
                             (filterv #(= tid (:threadId %))))]
               (is (= 1 (count rows))))
             (let [resp (api-call :post (str "/api/threads/" tid "/rebuild") nil)]
               (is (= 200 (.statusCode resp))))))
         (testing "a destination that already holds this session's log is refused BY NAME"
           ;; Put a log back where the first project's workspace was -- which is what
           ;; a quarantined-and-rebuilt store, a hand-edited tree or a restored
           ;; backup all look like: a file in a workspace the store no longer knows
           ;; about. Now bind there, and both ends are occupied.
           (.mkdirs (.getParentFile old))
           (spit old "{\"ts\":1,\"runId\":\"r0\",\"kind\":\"input\",\"payload\":{}}\n"
                 :encoding "UTF-8")
           (let [resp  (api-call :post "/api/project"
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
               (is (same-dir? (:dir reply) project-dir)))
             (testing "and from here the split is VISIBLE, not silent -- two files, one stem"
               (let [rows (->> (json/read-str (.body (api-call :get "/api/threads" nil))
                                              :key-fn keyword)
                               (filterv #(= tid (:threadId %))))]
                 (is (= 2 (count rows))))
               (let [rb (api-call :post (str "/api/threads/" tid "/rebuild") nil)]
                 (is (= 404 (.statusCode rb)))
                 (is (str/includes? (:error (read-json rb)) "harness-http-project")
                     "the refusal names the other half's directory"))))))))))

;; ------------------------------------- records that landed in the reserved workspace

(defn- project-log
  "The file a thread bound to PROJECT-DIR writes to, computed the way the writer
  computes it: the project's canonical path, sanitized, under the projects tree."
  [project-dir tid]
  (home/log-file (io/file (home/projects-dir)
                          (home/sanitize (.getCanonicalPath (io/file project-dir))))
                 tid))

(defn- unbound-log
  "The file an unbound thread's records land in -- the tree's reserved workspace."
  [tid]
  (home/log-file (io/file (home/projects-dir) http/unbound-workspace) tid))

(defn- carried-files
  "The evidence a carry leaves behind under DIR: files named
  `<thread>.jsonl.carried-<stamp>`. They deliberately do not end in .jsonl, so the
  listing does not read them as a second conversation."
  [dir tid]
  (->> (file-seq (io/file dir))
       (filter #(and (.isFile ^java.io.File %)
                     (str/starts-with? (.getName ^java.io.File %)
                                       (str (home/sanitize tid) ".jsonl.carried-"))
                     (not (str/ends-with? (.getName ^java.io.File %) ".jsonl"))))))

(deftest records-that-landed-unbound-are-carried-back-into-the-project-file
  ;; The 2026-09-18 accident, as a case: the store was moved aside and rebuilt
  ;; empty, so project/identity-for answered nil for every session and the records a
  ;; live run kept producing landed in projects/.unbound/<thread>.jsonl. When the
  ;; store was restored the binding was back, so later records went to the project's
  ;; workspace again -- ONE CONVERSATION IN TWO FILES. Replay, rebuild and the eval
  ;; reader each read ONE file, so the unbound segment was invisible to all of them.
  ;;
  ;; The store is emptied HERE by dropping the binding (the same answer
  ;; identity-for gives after a quarantine: nil) and restored by binding again --
  ;; NOT through /api/project, which would itself have called move-log!. This is the
  ;; WRITER that has to notice the leftover, which is the whole point of the case.
  ;; Both homes are this test's own, and the project directory is planted under its
  ;; root, so nothing here can reach the developer's real library.
  (support/with-temp-env [root _home]
    (let [proj-dir (io/file root "carry-project")
          tid      (str "carry-" (java.util.UUID/randomUUID))
          plog     (project-log proj-dir tid)
          ulog     (unbound-log tid)]
      (.mkdirs proj-dir)
      (try
        (testing "a bound session's records land in the project's workspace"
          (project/bind! tid (str proj-dir))
          (#'http/log! tid "r1" test-probe {:n 1})
          (#'http/log! tid "r1" test-probe {:n 2})
          (drained!)
          (is (.exists plog))
          (is (not (.exists ulog))))
        (testing "the store answers nil for a while: records land in .unbound"
          (project/bind! tid nil)
          (#'http/log! tid "r2" test-probe {:n 3})
          (#'http/log! tid "r2" test-probe {:n 4})
          (drained!)
          (is (.exists ulog))
          (is (= 2 (count (str/split-lines (slurp ulog :encoding "UTF-8"))))))
        (testing "the binding comes back and the writer carries the segment home"
          (project/bind! tid (str proj-dir))
          (#'http/log! tid "r3" test-probe {:n 5})
          (drained!)
          (let [lines  (replay/lines->records
                             (str/split-lines (slurp plog :encoding "UTF-8")))
                inputs (filterv #(= test-probe (replay/kind %)) lines)
                ns     (mapv #(get-in (replay/payload %) [:n]) inputs)]
            (testing "ONE file holds the whole conversation, in order"
              (is (= [1 2 3 4 5] ns) "no line lost and none duplicated")
              (is (= 5 (count inputs))))
            (testing "and the timestamps do not go backwards"
              (let [ts (mapv :ts inputs)]
                (is (= ts (vec (sort ts))))))
            (testing "the leftover source is renamed, not read as a second conversation"
              (is (not (.exists ulog)))
              (let [kept (carried-files (home/projects-dir) tid)]
                (is (= 1 (count kept))))))
          (testing "the listing sees exactly ONE log for this session"
            (is (= 1 (count (replay/logs-for (home/projects-dir) tid))))))
        (testing "and the carry is SAID OUT LOUD"
          (let [lines (replay/lines->records
                            (str/split-lines (slurp plog :encoding "UTF-8")))
                line  (first (filter #(= "log/carried-back" (replay/kind %)) lines))]
            (is (some? line) "an audit line says the segment was carried back")
            (is (= (.getAbsolutePath plog) (get-in (replay/payload line) [:to])))
            (is (= (.getAbsolutePath ulog) (get-in (replay/payload line) [:from])))
            (is (= 2 (get-in (replay/payload line) [:lines])) "and how many lines moved")))
        (finally
          (run! #(io/delete-file % true) (reverse (file-seq proj-dir))))))))

(deftest a-bind-that-arrives-while-the-writer-is-mid-run-leaves-one-file
  ;; THE CASE 'BIND AT FIRST SEND' ADDED. The sidebar no longer binds a new session when
  ;; the row is minted; it binds when the session's first message arrives -- i.e. WHILE THE
  ;; RUN IS ALREADY WRITING (`app.tsx`, `reportTitle`). So a bind can now land in the middle
  ;; of a conversation's records, and it does two things that same writer is looking at: it
  ;; changes the binding the writer asks where a line goes, and it MOVES the file the writer
  ;; is appending to. The two are serialized on `log-lock` (see `log!` and the /api/project
  ;; handler), and this case pins what that buys, in the only form that is checkable: no
  ;; matter who gets there first, the whole conversation ends in ONE file -- the last
  ;; project's -- with every line in it, in order, and no fragment left behind anywhere.
  ;;
  ;; The writer is a real one (`#'http/log!`, the function every record goes through), on its
  ;; own thread, because a case that binds first and writes afterwards would only exercise
  ;; the ordinary first-bind shape. It REBINDS, twelve times, between two directories: the
  ;; window this is about is a few microseconds wide, and one bind usually misses it -- a
  ;; dozen of them, against a writer that never stops, is what makes the case able to fail.
  ;; (It did: the pre-fix code answered 400 "could not move the log" -- the writer's own
  ;; carrying of the leftover segment and the bind's move raced over one rename.)
  (wipe-dir! project-dir)
  (wipe-dir! project-dir-2)
  (with-server
   "it-proj"
   (fn []
     (let [tid    (str "mid-write-" (java.util.UUID/randomUUID))
           dirs   [project-dir project-dir-2]
           ulog   (unbound-log tid)
           writer (future
                    (try
                      (dotimes [i 120]
                        (#'http/log! tid "r1" test-probe {:n i})
                        (Thread/sleep 1))
                      (catch Throwable _ nil)))]
       ;; THE FILE APPEARS AS THE CONSUMER WRITES IT (ticket 02), and it can be seen the
       ;; instant it exists and before a byte is on it -- so the premise this case needs
       ;; is a file with a LINE in it. Waiting for a positive length is what makes the
       ;; assertion below a statement about the run rather than about a scheduler.
       (let [deadline (+ (System/currentTimeMillis) 5000)]
         (while (and (or (not (.exists ulog)) (zero? (.length ulog)))
                     (< (System/currentTimeMillis) deadline))
           (Thread/sleep 2)))
       (testing "the run got its first lines down before any bind arrived"
         (is (.exists ulog) "the session's log is in the reserved workspace")
         (is (pos? (.length ulog))))
       (testing "every bind is accepted while the run is still writing"
         (dotimes [i 12]
           (let [dir  (nth dirs (mod i 2))
                 resp (api-call :post "/api/project"
                                (json/write-str {:threadId tid :dir (str dir)}))]
             (is (= 200 (.statusCode resp))
                 (str "bind " i " to " (.getName (io/file dir)) ": " (:error (read-json resp))))
             (is (same-dir? (:dir (read-json resp)) dir)))))
       @writer
       ;; AND THE WRITER'S OWN TAIL IS WAITED FOR. `log!` only HANDS a line to the
       ;; record writer now (ticket 02): the bytes are the consumer's, so the read
       ;; below would be whatever happened to have landed at that instant -- a shorter
       ;; conversation, asserted as if a line had been lost. `drained!` is the honest
       ;; wait, and it changes nothing about the guarantee this case pins.
       (drained!)
       ;; THE LAST BIND IS THE ONE IN FORCE, so the conversation has to be in that
       ;; directory's workspace -- and only there.
       (let [plog (project-log (nth dirs (mod 11 2)) tid)
             other (project-log (nth dirs (mod 12 2)) tid)
             ns   (->> (str/split-lines (slurp plog :encoding "UTF-8"))
                       (keep (fn [line]
                               (try (:n (replay/payload (first (replay/lines->records [line]))))
                                    (catch Throwable _ nil))))
                       vec)]
         (testing "EVERY line of the conversation is in the last project's file, in order"
           (is (= (vec (range 120)) ns)))
         (testing "and the directory it left kept nothing"
           (is (not (.exists other))))
         (testing "and the reserved workspace kept no fragment of it either"
           (is (not (.exists ulog))))
         (testing "so the listing reads this session as ONE conversation"
           (is (= 1 (count (replay/logs-for (home/projects-dir) tid))))))))))

(deftest a-carry-that-would-interleave-two-histories-is-refused-by-name
  ;; The carry may only run when the conversation's file ENDS at or before the
  ;; leftover segment begins. If the two ranges overlap -- here the segment begins
  ;; before the file has ended -- appending would fold one history into another out
  ;; of order (replay reads in FILE order), so the implementation refuses, names both
  ;; files, and changes neither. This is the check the 2026-09-18 hand-repair did
  ;; with `cat`: the ranges had to be shown disjoint first, and it is not assumed.
  ;;
  ;; The refused carry is said ONCE, not beside every record line that follows.
  (support/with-temp-env [root _home]
    (let [proj-dir (io/file root "carry-overlap")
          tid      (str "overlap-" (java.util.UUID/randomUUID))
          plog     (project-log proj-dir tid)
          ulog     (unbound-log tid)
          line     (fn [ts n] (str (row-json {:ts ts :runId "r" :kind test-probe
                                                     :payload {:n n}}) "\n"))]
      (.mkdirs proj-dir)
      (try
        (project/bind! tid (str proj-dir))
        (.mkdirs (.getParentFile plog))
        (spit plog (str (line 2000 1) (line 3000 2)) :encoding "UTF-8")
        (.mkdirs (.getParentFile ulog))
        (spit ulog (str (line 2500 3) (line 3500 4)) :encoding "UTF-8")
        (let [before-plog (slurp plog :encoding "UTF-8")
              before-ulog (slurp ulog :encoding "UTF-8")]
          (#'http/log! tid "r3" test-probe {:n 5})
          (drained!)
          (let [lines   (replay/lines->records
                              (str/split-lines (slurp plog :encoding "UTF-8")))
                refused (first (filter #(= "log/carry-refused" (replay/kind %)) lines))]
            (testing "the refusal is on the record, naming BOTH paths"
              (is (some? refused))
              (is (= (.getAbsolutePath plog) (get-in (replay/payload refused) [:to])))
              (is (= (.getAbsolutePath ulog) (get-in (replay/payload refused) [:from])))
              (is (str/includes? (str (get-in (replay/payload refused) [:reason])) "overlap")))
            (testing "neither file was merged or moved"
              (is (.exists ulog) "the leftover is still where it was")
              (is (= before-ulog (slurp ulog :encoding "UTF-8"))
                  "byte for byte -- not appended to, not renamed")
              (is (not-any? #(= 3 (get-in (replay/payload %) [:n])) lines)
                  "not one of the segment's lines was folded in")
              (is (not-any? #(= 4 (get-in (replay/payload %) [:n])) lines))
              (is (str/starts-with? (slurp plog :encoding "UTF-8") before-plog)
                  "the conversation's own lines are untouched; only the audit and this write followed"))
            (testing "a refused carry is said once, not beside every record"
              (#'http/log! tid "r3" test-probe {:n 6})
              (#'http/log! tid "r3" test-probe {:n 7})
              (drained!)
              (let [n (count (filter #(= "log/carry-refused" (replay/kind %))
                                     (replay/lines->records
                                           (str/split-lines (slurp plog :encoding "UTF-8")))))]
                (is (= 1 n) "one audit line for one refusal")))))
        (finally
          (run! #(io/delete-file % true) (reverse (file-seq proj-dir))))))))

(deftest threads-listing-and-rebuild-over-the-real-edge
  ;; Ticket 05 over the real edge: a real run writes a real log; the listing
  ;; finds it with its metadata; the rebuild endpoint hands the conversation
  ;; back in the shape a client re-owns; and the action leaves its audit line.
  ;; The pin is keyed to the RANDOM thread id the run will use -- a pin under
  ;; any other name would let the run resolve config's bare :fake provider and
  ;; produce an empty stream (the per-thread-pin rule, learned the hard way).
  (let [tid (str "rebuild-" (java.util.UUID/randomUUID))]
    (with-server
     {tid script}
     (fn []
       ;; The log: one real run of the default script -- reasoning, two tool
       ;; calls, tool results, a final answer.
       (let [frames (wire/frames-from-sse (.body (post-run tid)))]
         (is (= "RUN_FINISHED" (:type (last frames)))))
       (testing "GET /api/threads lists the conversation with its metadata"
         (let [resp (api-call :get "/api/threads" nil)
               rows (->> (json/read-str (.body resp) :key-fn keyword)
                         (filterv #(= tid (:threadId %))))]
           (is (= 200 (.statusCode resp)))
           (is (= http/ui-origin (header resp "Access-Control-Allow-Origin")))
           (is (= 1 (count rows)))
           (is (pos? (:bytes (first rows))))
           (is (pos? (:lastActivity (first rows))))))
       (testing "POST rebuild returns a continuable message list"
         (let [resp  (api-call :post (str "/api/threads/" tid "/rebuild") nil)
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
         (let [lines (replay/lines->records
                           (str/split-lines (slurp (log-file tid) :encoding "UTF-8")))
               rb    (filterv #(= "session/rebuilt" (replay/kind %)) lines)]
           (is (= 1 (count rb)))
           (is (nil? (:runId (first rb))) "a rebuild happens outside any run")
           (is (pos? (get-in (replay/payload (first rb)) [:messages])))
           (is (= "http" (get-in (replay/payload (first rb)) [:via])))))))))

(deftest rebuilding-a-session-that-has-never-run-answers-an-empty-conversation
  ;; The sidebar's very first click on a brand-new session. Binding wrote an audit
  ;; line and nothing else, so the log exists and holds no run -- and a 400 here
  ;; would make the session a person just created un-openable, for a reason
  ;; ('truncated') that is not true of it.
  (let [dir (support/temp-dir "http-unrun")]
    (with-server
     "never-run"
     (fn []
       (is (= 200 (.statusCode (api-call :post "/api/project"
                                         (json/write-str {:threadId "never-run" :dir dir})))))
       (let [resp  (api-call :post "/api/threads/never-run/rebuild" nil)
             reply (json/read-str (.body resp) :key-fn keyword)]
         (is (= 200 (.statusCode resp)))
         (is (= [] (:messages reply)) "nothing has happened in this conversation yet")
         (is (= [] (:context reply))))
       (testing "and the audit line it did write is still on disk, untouched"
         (is (some #(= "project/bound" (replay/kind %))
                   (replay/lines->records
                         (str/split-lines (slurp (log-file-for "never-run") :encoding "UTF-8"))))))))))

(def ^:private remove-dir-a
  (support/temp-dir "http-remove-a"))
(def ^:private remove-dir-b
  (support/temp-dir "http-remove-b"))

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
  (wipe-dir! remove-dir-a)
  (wipe-dir! remove-dir-b)
  (with-server
   {"rm-a" script "rm-b" script "rm-other" script "rm-never-run" script}
   (fn []
     (let [canon    (fn [d] (.getCanonicalPath (io/file d)))
           remove!  (fn [d]
                      (api-call :post
                                (str "/api/projects/"
                                     (java.net.URLEncoder/encode (canon d) "UTF-8")
                                     "/remove")
                                nil))
           add!     (fn [d] (api-call :post "/api/projects" (json/write-str {:dir d})))
           bind!    (fn [tid d]
                      (api-call :post "/api/project"
                                (json/write-str {:threadId tid :dir d})))
           archive! (fn [tid flag]
                      (api-call :post (str "/api/threads/" tid "/archive")
                                (json/write-str {:archived flag})))
           listing  listed-projects
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
       (post-run "rm-a")
       (post-run "rm-b")
       (post-run "rm-other")
       ;; A session nothing was ever SENT to. Binding one writes an audit line, so a
       ;; file would otherwise exist -- it is removed by hand, which is the state the
       ;; listing used to call "null facts". The listing does not look at the file at
       ;; all now: the row has no send time because no send ever happened, and the
       ;; removal must not special-case it (the verb is about a project row, and
       ;; whether a conversation has a file is not that row's business).
       (is (= 200 (.statusCode (add! remove-dir-b))))
       (is (= 200 (.statusCode (bind! "rm-never-run" remove-dir-b))))
       (is (.delete (log-file-for "rm-never-run")) "the file is removed by hand")
       (is (nil? (:lastSentAt (session remove-dir-b "rm-never-run")))
           "no send, so no time -- null, which is not a zero-byte file")
       (is (= 200 (.statusCode (archive! "rm-b" true))))
       (Thread/sleep 20)                        ; so a file write would move an mtime

       (let [sent-a   (:lastSentAt (session remove-dir-a "rm-a"))
             before-a (file-facts ws-a)
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
                     (json/read-str (.body (api-call :get "/api/threads" nil))
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
                       (api-call :get
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
           (testing "and the send times came back with them, exactly as they were"
             ;; Removing and re-adding a project writes ROWS. A time re-derived from the
             ;; files on the way back would be an mtime here; a store fact survives the
             ;; trip unchanged.
             (is (= before-a (file-facts ws-a))
                 "re-adding is a row write too: not one byte moved either way")
             (is (= sent-a (:lastSentAt (session remove-dir-a "rm-a"))))))
         (testing "and so does the pair where one of them never ran"
           (is (= 2 (:adopted (read-json (add! remove-dir-b)))))
           (is (= #{"rm-other" "rm-never-run"}
                  (set (map :threadId (:sessions (project remove-dir-b))))))
           (is (nil? (:lastSentAt (session remove-dir-b "rm-never-run")))
               "still no time -- nothing was ever sent to it")
           (is (integer? (:lastSentAt (session remove-dir-b "rm-other")))))

         (testing "and the history is intact -- the same file rebuilds the conversation"
           ;; The ticket's last promise, and the one a "tidy up while we are here"
           ;; implementation would break invisibly: same directory, same file name,
           ;; same run inside it.
           (let [resp  (api-call :post "/api/threads/rm-a/rebuild" "")
                 reply (read-json resp)]
             (is (= 200 (.statusCode resp)))
             (is (seq (:messages reply))
                 "the conversation came back out of the file the removal left alone"))))))))

(def ^:private task-dir
  (support/temp-dir "http-tasks"))

(def ^:private task-dir-2
  (support/temp-dir "http-tasks-2"))


(deftest a-task-is-a-session-with-no-project-and-no-memory
  ;; Ticket 01: the sidebar's second half. Two different questions live here, and
  ;; they are answered by two different owners -- WHICH conversations are tasks (the
  ;; store's `project/tasks`, one WHERE clause) and WHERE a task's log facts come
  ;; from (the tree's, by stem, because a task has no project to derive a workspace
  ;; from).
  ;;
  ;; Every case below is a way of getting it wrong that LOOKS right: treating any
  ;; unbound row as a task (removing a project would empty its sessions into the task
  ;; list), registering a conversation that already belongs somewhere (which would
  ;; unbind it), or reading a task's size out of projects/.unbound (which would say
  ;; 'no log yet' about a conversation whose log is sitting in a project workspace).
  (wipe-dir! task-dir)
  (wipe-dir! task-dir-2)
  (with-server
   {"task-ran" script}
   (fn []
     (let [bind! (fn [tid dir]
                   (api-call :post "/api/project"
                             (json/write-str {:threadId tid :dir dir})))
           remove! (fn [dir]
                     (api-call :post
                               (str "/api/projects/"
                                    (java.net.URLEncoder/encode (.getCanonicalPath (io/file dir)) "UTF-8")
                                    "/remove")
                               nil))]
       (testing "one snapshot carries BOTH halves, and both keys are always there"
         (let [listing (sidebar-listing)]
           (is (contains? listing :projects))
           (is (contains? listing :tasks) "an empty list, not a missing key")
           (is (vector? (:tasks listing)))))
       (testing "registering a conversation makes it a TASK, before a word is typed"
         (let [resp (register-session! "task-a")]
           (is (= 200 (.statusCode resp)))
           (is (= "task-a" (:threadId (read-json resp))) "the id it was handed, back")
           (is (task? "task-a"))
           (is (= [] (projects-holding "task-a")) "in no project: that is what a task is")
           (let [row (listed-row "task-a")]
             (is (false? (:archived row)))
             (is (false? (:running row)))
             (is (nil? (:bytes row)) "no log yet -- null, which is not a zero-byte file")
             (is (nil? (:lastActivity row))))))
       (testing "registering the SAME conversation again answers the same row"
         ;; Both callers can race themselves: the button registers an id it just
         ;; minted, and a second turn of the same conversation registers it again.
         (is (= 200 (.statusCode (register-session! "task-a"))))
         (is (= 1 (count (filter #(= "task-a" (:threadId %)) (listed-tasks))))))
       (testing "registering a conversation that already has a project leaves it alone"
         ;; The verb is 'exist', not 'be a task': it must be safe to say about a
         ;; conversation that belongs somewhere, or a caller would have to know.
         (is (= 200 (.statusCode (bind! "task-bound" task-dir))))
         (is (= 200 (.statusCode (register-session! "task-bound"))))
         (is (not (task? "task-bound")))
         (is (= [(.getCanonicalPath (io/file task-dir))] (projects-holding "task-bound"))
             "still bound to its directory -- registering does not unbind anybody"))
       (testing "a body that is not JSON registers nothing"
         (is (= 400 (.statusCode (api-call :post "/api/sessions" "{not json"))))
         (is (not-any? #(= "" (:threadId %)) (listed-tasks))
             "in particular the empty id is not a conversation"))
       (testing "and a body that names no conversation is GIVEN one"
         ;; TICKET 03: the id is the server's to mint. A conversation is named here,
         ;; by the process that keeps it, so a new task is one request instead of the
         ;; page inventing a name in a namespace it does not own -- and the ANSWER is
         ;; the name to use from then on.
         (let [resp   (register-session! nil)
               minted (:threadId (read-json resp))]
           (is (= 200 (.statusCode resp)))
           (is (string? minted) "the answer names the conversation")
           (is (not= "" minted))
           (is (task? minted) "a task, before a word has been typed")
           (is (some #(= minted (:threadId %)) (listed-tasks))
               "and the row is in the same listing the sidebar reads")))

       (testing "a registered conversation that RUNS gets its figures from the log"
         ;; TICKET 03 CHANGED WHAT THIS CASE IS ABOUT. It used to be "a conversation
         ;; that RUNS becomes a session of this home" -- because the run edge quietly
         ;; registered whatever id arrived. That silent create is gone: the run refuses
         ;; an id this home does not know by name, and the registration is a step of its
         ;; own (`post-run` makes it here, as the page does before it can send anything).
         ;; What is left to assert is the half that never changed: WHERE a task's row
         ;; facts come from -- the tree, by stem, because a task has no project to
         ;; derive a workspace from.
         (is (= "RUN_FINISHED"
                (:type (last (wire/frames-from-sse (.body (post-run "task-ran")))))))
         (is (task? "task-ran"))
         (let [f (log-file "task-ran")]
           ;; The returned side of the message record lands AFTER the terminal
           ;; frame, so the size is measured once the writer is done.
           (wait-for-recorded
            f
            (fn [ls] (and (some #(= "message" (replay/kind %)) ls)
                          (some #(and (= "event" (replay/kind %))
                                      (frames/terminal? (replay/payload %)))
                                ls)))
            5000)
           (let [row (listed-row "task-ran")]
             (is (integer? (:lastSentAt row)) "the run stamped the send")
             (is (= "看看这个项目" (:firstUserText row))
                 "and named the session from the first message it carried"))))

       (testing "a jsonl nobody owns is NOT a task -- the store decides, not the tree"
         ;; The store's rule, the one the whole sidebar rests on: a file in the tree
         ;; that nothing ever asked to keep is not a conversation this interface
         ;; lists. GET /api/threads still shows it to anyone diagnosing.
         (spit (log-file "nobody-owns-this") "{}")
         (is (not (task? "nobody-owns-this")))
         (is (some #(= "nobody-owns-this" (:threadId %))
                   (json/read-str (.body (api-call :get "/api/threads" nil)) :key-fn keyword))))

       (testing "a session released on purpose IS a task, and it keeps every fact it had"
         ;; `bind! id nil` is not reachable over HTTP (the route refuses a blank dir),
         ;; so this goes through the verb the route wraps. What it leaves behind is
         ;; the interesting part: the log does NOT move, so the file stays in the PROJECT's
         ;; workspace while the session stops having a project -- and a row that READ THE
         ;; TREE would have to know where to look (`.unbound` would tell it 'no log yet'
         ;; about a conversation whose log is right there). A row that reads the STORE
         ;; has nothing to get wrong, which is the point of this whole change.
         (is (= 200 (.statusCode (bind! "task-released" task-dir))))
         (post-run "task-released")
         (project/bind! "task-released" nil)
         (let [f (home/log-file (workspace-of (.getCanonicalPath (io/file task-dir)))
                                "task-released")]
           (is (.exists f) "releasing a session is not a file operation")
           (is (task? "task-released"))
           (is (= [] (projects-holding "task-released")))
           (let [row (listed-row "task-released")]
             (is (= "task-released" (:threadId row)))
             (is (and (= "看看这个项目" (:firstUserText row))
                      (integer? (:lastSentAt row)))
                 "the facts it earned while bound, kept after the release"))))

       (testing "a session released by REMOVING ITS PROJECT is not a task"
         ;; It remembers where it was -- that memory is what re-adding the directory
         ;; matches on -- so it is waiting for its project, not a conversation
         ;; without one.
         (is (= 200 (.statusCode (bind! "task-orphan" task-dir-2))))
         (is (= 200 (.statusCode (remove! task-dir-2))))
         (is (not (task? "task-orphan")))
         (is (= [] (projects-holding "task-orphan")) "listed nowhere, as it was before this feature")
         (is (= 1 (:adopted (read-json (api-call :post "/api/projects"
                                                  (json/write-str {:dir task-dir-2}))))))
         (is (= [(.getCanonicalPath (io/file task-dir-2))] (projects-holding "task-orphan"))
             "and adding the directory back brings it home"))

       (testing "a task whose stem has TWO logs is still ONE row -- the store counts sessions"
         (register-session! "task-two-logs")
         (bind! "task-two-logs" task-dir)
         (project/bind! "task-two-logs" nil)
         (spit (log-file "task-two-logs") "{}")
         (let [row (listed-row "task-two-logs")]
           (is (= "task-two-logs" (:threadId row)))
           (is (nil? (:lastSentAt row)) "nothing was ever sent to it"))
         (let [resp (api-call :post "/api/threads/task-two-logs/rebuild" "")]
           (is (= 404 (.statusCode resp))
               "the door that must pick one conversation still refuses")
           (is (str/includes? (:error (read-json resp)) "2 logs"))))
       ))))
(deftest adding-a-project-makes-a-project-with-no-session
  ;; Ticket 05's other half. Without this verb the ONLY way to get a project was
  ;; to bind a session to a directory -- backwards for a product whose sessions
  ;; must belong to a project, because the first project would need a session
  ;; that had nowhere to go. So this route makes the DIRECTORY the project.
  (let [adir (support/temp-dir "http-added")]
    (with-server
     "add-project-unused"
     (fn []
       (let [add!  (fn [dir] (api-call :post "/api/projects" (json/write-str {:dir dir})))
             named (fn [dir]
                     (let [want (last (str/split (.getCanonicalPath (io/file dir)) #"/"))]
                       (first (filterv #(= want (last (str/split (:path %) #"/")))
                                       (listed-projects)))))]
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
                                      (listed-projects)))))))
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
           (is (= 400 (.statusCode (api-call :post "/api/projects" (json/write-str {})))))
           (is (= 400 (.statusCode (api-call :post "/api/projects" "{not json"))))))))))

(def ^:private settings-sentinel
  "A value shaped like a real key and recognisable anywhere it turns up. It goes
  into the SHARED test home's .env for the length of the test below and comes back
  out again in a finally: leaving it there would be a key every later namespace's
  run would carry, which is a fact no later assertion should have to know about."
  "sk-or-v1-SENTINEL-DO-NOT-PUBLISH-9f3c2a")

(def ^:private settings-providers
  "Two vendors, so a config edit can be seen to change BOTH the name and the
  endpoint it resolves to."
  (pr-str {:alpha {:protocol :openai-completions :base-url "https://alpha/v1"
                   :model "alpha-large"
                   :models {"alpha-large" {:input #{:text :image} :output #{:text}
                                           :context-window 1000 :max-output-tokens 100}}}
           :beta  {:protocol :openai-completions :base-url "https://beta/v1"
                   :model "beta-plain"
                   :models {"beta-plain" {:input #{:text} :output #{:text}}}}}))

(deftest the-settings-endpoint-reports-the-live-config-and-never-the-key
  ;; Ticket 08 at the edge. Three claims, and each one is a thing that could be
  ;; true of the code and false of the running process: the endpoint answers the
  ;; configuration THE FILES hold right now, every knob says which tier chose it,
  ;; and the key does not appear in the response body in any form.
  ;;
  ;; It edits the SHARED test home (there is one root, and the server thread reads
  ;; it through harness.infra.home like everything else), saving and restoring both
  ;; files around the test. A second home would need a second server, and the
  ;; point here is the route, not the isolation -- harness.cap.providers-test covers
  ;; the answer itself against homes of its own.
  (let [dir      (home/root)
        config   (io/file dir "config.edn")
        provs    (io/file dir "providers.edn")
        dotenv   (io/file dir ".env")
        saved    (into {} (for [f [config dotenv]]
                            [(.getName f) (when (.exists f) (slurp f :encoding "UTF-8"))]))
        settings (fn [] (api-call :get "/api/settings?threadId=set-1" nil))
        parse    (fn [] (read-json (settings)))
        restore! (fn []
                   (doseq [[nm f] [["config.edn" config] [".env" dotenv]]]
                     (if-some [was (get saved nm)]
                       (spit f was :encoding "UTF-8")
                       (io/delete-file f true)))
                   ;; ...and this home holds no retired catalog file, whatever it
                   ;; held before this test.
                   (io/delete-file provs true))
        facts    (fn [] (into {} (for [f (reverse (file-seq (io/file dir)))
                                       :when (.isFile ^java.io.File f)]
                                  [(.getName ^java.io.File f)
                                   [(.length ^java.io.File f) (.lastModified ^java.io.File f)]])))]
    (try
      ;; EDN, not JSON: config.edn is read by clojure.edn like every other
      ;; config file here, and `{"provider":"alpha"}` in it is a parse error
      ;; rather than a configuration.
      ;;
      ;; The default tier and the catalog are the TWO SECTIONS of this one file,
      ;; and the test edits each in turn below -- which is also why a stray
      ;; providers.edn would be worse than useless here: the catalog refuses a
      ;; home that still holds one.
      (spit config (support/config-text (pr-str {:provider :alpha :reasoning-effort "low"})
                                        settings-providers)
            :encoding "UTF-8")
      (spit dotenv (str "HARNESS_API_KEY=" settings-sentinel "\n") :encoding "UTF-8")
      (with-server
       "settings-unused"
       (fn []
           (testing "the live configuration, knob by knob"
             (let [resp  (settings)
                   reply (parse)]
               (is (= 200 (.statusCode resp)))
               (is (= "alpha" (:provider reply)))
               (is (= "alpha-large" (:model reply)) "the provider's own default model")
               (is (= "https://alpha/v1" (:base-url reply)))
               (is (= "low" (:reasoning-effort reply)))
               (is (= ["image" "text"] (:input reply))
                   "modality sets arrive as sorted string vectors, like the model endpoint's")
               (is (= 1000 (:context-window reply)))
               (is (= "default" (:source reply)) "config.edn is where this resolution started")
               (is (= {:provider "config" :reasoning-effort "config" :model "catalog"}
                      (:tiers reply))
                   "every knob config named is credited to it, and the model NOBODY
                    named -- the provider's default -- is credited to the catalog")))

           (testing "asking is read-only: not one byte or mtime under the home moves"
             ;; Taken here, around pure reads, because the later cases deliberately
             ;; EDIT config.edn -- which is a write this test does, not one the
             ;; route did.
             (let [before (facts)]
               (dotimes [_ 4] (settings))
               (is (= before (facts)))))

           (testing "the api-key is presence and origin, and NOTHING else"
             (let [body  (.body (settings))
                   reply (parse)]
               (is (= {:present? true :source "env-file" :name "HARNESS_API_KEY"}
                      (:key reply))
                   "presence, origin, AND the line: this home's .env sets the global
                    one, and alpha -- the provider :default names -- has no
                    ALPHA_API_KEY, so that is the line this session reads")
               (is (not (contains? reply :api-key)))
               (is (not (str/includes? body settings-sentinel))
                   "the whole body, searched as a string -- not a field checked for emptiness")
               (is (not (str/includes? body (subs settings-sentinel 0 12)))
                   "not even the prefix")))

           (testing "the home is named, with the rule that produced it and its files"
             (let [reply (parse)]
               (is (= dir (:path (:home reply))))
               (is (contains? #{"override" "environment" "default"} (:origin (:home reply))))
               (is (= ["config.edn" "hooks.edn" ".env" "harness.db"]
                      (mapv :name (:files (:home reply)))))
               (is (true? (:present? (first (filter #(= ".env" (:name %))
                                                    (:files (:home reply))))))
                   "the file the key comes from is on the list, and only its presence is")))

           (testing "editing config.edn is visible on the NEXT call, with no restart"
             ;; The ticket's sharpest acceptance, and it is the same question the
             ;; store's namespace asks from the other end: config is files, read
             ;; fresh -- never a start-up snapshot and never a database row.
             (spit config (support/config-text (pr-str {:provider :beta :model "beta-plain"
                                                        :reasoning-effort "high"})
                                               settings-providers)
                   :encoding "UTF-8")
             (let [reply (parse)]
               (is (= "beta" (:provider reply)) "the by-name choice moved")
               (is (= "https://beta/v1" (:base-url reply)) "and so did the endpoint it carries")
               (is (= "high" (:reasoning-effort reply)))
               (is (= "config" (:provider (:tiers reply)))
                   "and the tier report follows the file rather than a cached answer")))

           (testing "a configuration that cannot be resolved is a 400 with the reason"
             ;; A half-edited config.edn is how a person meets this in practice,
             ;; and the sentence is what the panel will be showing.
             (spit config (support/config-text (pr-str {:provider :nope}) settings-providers)
                   :encoding "UTF-8")
             (let [resp  (settings)
                   reply (parse)]
               (is (= 400 (.statusCode resp)))
               (is (str/includes? (:error reply) "no provider named"))))

           (testing "and a config.edn that is not there is the SAME ANSWER as an empty
                     one: the file's absence and its emptiness are one fact, so the
                     sentence a person meets names what to write and where"
             (io/delete-file config true)
             (let [resp (settings)
                   body (:error (read-json resp))]
               (is (= 400 (.statusCode resp)))
               (is (str/includes? body "no provider"))
               (is (str/includes? body "config.edn")
                   "with the path, which is the useful half of the refusal that used
                    to carry it")))

           (testing "only GET is served -- this route has no effect to POST"
             (is (= 405 (.statusCode (api-call :post "/api/settings" "{}")))))))
      (finally (restore!)))))

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
        body (str (row-json
                   {:ts 1 :runId "r0" :kind "message" :source "client" :id "u1"
                    :payload {:role "user" :content "old"}})
                  "\n")]
    (.mkdirs old)
    (spit file body :encoding "UTF-8")
    ;; A FRESH server: this is the "started a process" half of the claim, and it
    ;; is why the store is asked about before the route below can have created
    ;; anything for this id.
    (with-server
     "old-logs-list"
     (fn []
       (testing "the store has no row for it -- nothing reads ownership off the disk"
         (is (nil? (project/binding-for tid)))
         (is (nil? (project/identity-for tid))))
       (testing "the listing does not show it -- the walk is rooted at projects/"
         (let [resp (api-call :get "/api/threads" nil)
               rows (json/read-str (.body resp) :key-fn keyword)]
           (is (= 200 (.statusCode resp)))
           (is (not-any? #(= tid (:threadId %)) rows))))
       (testing "and its id resolves to nothing, so a rebuild cannot reach it either"
         (is (= 404 (.statusCode (api-call :post (str "/api/threads/" tid "/rebuild") nil)))))
       (testing "the file is exactly as it was left -- unread, unmoved, unimported"
         (is (.exists file))
         (is (= body (slurp file :encoding "UTF-8")))
         (is (not (.exists (io/file (log-dir) (str tid ".jsonl"))))))))))

(deftest rebuild-closes-a-mid-run-log-and-refuses-a-corrupt-one
  (with-server
   "it-refuse"
   (fn []
     (testing "a log that ends mid-run is CLOSED OFF, and the conversation comes back"
       ;; The state a killed process leaves: a run whose last frame is a tool call
       ;; that never returned. Refusing it left the thread unopenable for good, so
       ;; the rebuild closes the run instead -- and WRITES, which is why the frames
       ;; are not the only thing to assert here.
       (let [tid     (str "trunc-" (java.util.UUID/randomUUID))
             f       (log-file tid)
             frames  (vec (mapcat (ag/outbound tid "r1")
                                  [(ev/run-start)
                                   (ev/tool-call "c1" "read" "{}")]))
             records #(replay/lines->records
                            (str/split-lines (slurp f :encoding "UTF-8")))]
         (.mkdirs (.getParentFile f))          ; the writer creates it; a fixture must not assume
         (spit f (str (str/join "\n"
                                (concat (action-rows 1 "r1" [{:id "u1" :role "user" :content "hi"}])
                                        (map (fn [frame]
                                               (row-json
                                                {:ts 2 :runId "r1" :kind "event"
                                                 :payload frame}))
                                             frames)))
                       "\n")
               :encoding "UTF-8")
         (let [resp  (api-call :post (str "/api/threads/" tid "/rebuild") nil)
               reply (json/read-str (.body resp) :key-fn keyword)]
           (is (= 200 (.statusCode resp)) "a log one frame short of readable is not a refusal")
           (is (seq (:messages reply)))
           (testing "the record says who closed it, and what was appended"
             (let [kinds   (mapv replay/kind (records))
                   closing (first (filter #(= "session/closed-off" (replay/kind %)) (records)))]
               (is (= 1 (count (filter #(= "session/closed-off" %) kinds))))
               (is (= {:run-id "r1" :last-frame "TOOL_CALL_END"
                       :frames ["TOOL_CALL_RESULT" "RUN_ERROR"]}
                      (replay/payload closing)))
               (testing "and the frames follow it, the terminal frame last"
                 (is (= ["session/closed-off" "event" "event" "session/rebuilt"]
                        (mapv replay/kind (take-last 4 (records)))))
                 (is (= ["TOOL_CALL_RESULT" "RUN_ERROR"]
                        (->> (records)
                             (filter #(= "event" (replay/kind %)))
                             (take-last 2)
                             (mapv #(get-in (replay/payload %) [:type]))))))))
           (testing "so the next reader gets a whole conversation"
             (is (seq (replay/lines->messages (str/split-lines (slurp f :encoding "UTF-8"))))))))
       (testing "and a second rebuild appends nothing: the log closed once"
         (let [tid (str "trunc2-" (java.util.UUID/randomUUID))
               f   (log-file tid)]
           (.mkdirs (.getParentFile f))
           (spit f (str (str/join "\n" (action-rows 1 "r1" [{:id "u1" :role "user" :content "hi"}]))
                        "\n")
                 :encoding "UTF-8")
           (let [first-lines (count (str/split-lines (slurp f :encoding "UTF-8")))]
             (api-call :post (str "/api/threads/" tid "/rebuild") nil)
             (let [after-first (str/split-lines (slurp f :encoding "UTF-8"))]
               (api-call :post (str "/api/threads/" tid "/rebuild") nil)
               (let [after-second (str/split-lines (slurp f :encoding "UTF-8"))]
                 (is (> (count after-first) first-lines) "the first rebuild closed the run")
                 (is (= 1 (count (filter #(str/includes? % "closed-off") after-second)))
                     "the second found nothing to close")
                 (is (= (inc (count after-first)) (count after-second))
                     "and appended only its own rebuild line")))))))

     (testing "an earlier run left open beside a later one that FINISHED is repaired too"
       ;; The shape one thread with two runs in flight leaves when the process is
       ;; stopped between them: r1 never ended, r2 did. So the file's last frame IS a
       ;; terminal and the LAST input's run is closed -- asking about those two found
       ;; nothing to repair, while the refusal counted two runs against one terminal.
       ;; This thread was refused for good; now the rebuild closes the run nobody
       ;; closed and the conversation comes back.
       (let [tid   (str "trunc-earlier-" (java.util.UUID/randomUUID))
             f     (log-file tid)
             input (fn [ts run-id] (action-rows ts run-id [{:id "u1" :role "user" :content "hi"}]))
             frames (fn [ts run-id evs]
                      (map (fn [frame]
                             (row-json {:ts ts :runId run-id :kind "event" :payload frame}))
                           (mapcat (ag/outbound tid run-id) evs)))
             log-lines (concat (input 1 "r1")
                               (frames 2 "r1" [(ev/run-start)
                                               (ev/tool-call "c1" "read" "{}")])
                               (input 3 "r2")
                               (frames 4 "r2" [(ev/run-start)
                                               (ev/text-delta "继续")
                                               (ev/run-end)]))]
         (.mkdirs (.getParentFile f))
         (spit f (str (str/join "\n" log-lines) "\n") :encoding "UTF-8")
         (let [resp  (api-call :post (str "/api/threads/" tid "/rebuild") nil)
               reply (json/read-str (.body resp) :key-fn keyword)
               rs    (replay/lines->records
                           (str/split-lines (slurp f :encoding "UTF-8")))]
           (is (= 200 (.statusCode resp)) "a log the repair can close is not a refusal")
           (is (seq (:messages reply)))
           (testing "the closed line names r1 -- the run that never ended"
             (let [closing (first (filter #(= "session/closed-off" (replay/kind %)) rs))]
               (is (some? closing))
               (is (= {:run-id "r1" :last-frame "TOOL_CALL_END"
                       :frames ["TOOL_CALL_RESULT" "RUN_ERROR"]}
                      (replay/payload closing)))))
           (testing "and the frames appended for it carry ITS run id"
             (is (= ["TOOL_CALL_RESULT" "RUN_ERROR"]
                    (->> rs
                         (filter #(and (= "r1" (:runId %)) (= "event" (replay/kind %))))
                         (take-last 2)
                         (mapv #(get-in (replay/payload %) [:type]))))
                 "how a reader pairs the appended terminal with the run it ended"))
           (testing "so the next reader gets a whole conversation"
             (is (seq (replay/lines->messages
                       (str/split-lines (slurp f :encoding "UTF-8")))))))))
     (testing "a half-written line is refused, naming the line"
       (let [tid (str "corrupt-" (java.util.UUID/randomUUID))]
         (spit (log-file tid)
               "{\"ts\":1,\"runId\":\"r1\",\"kin" :encoding "UTF-8")
         (let [resp  (api-call :post (str "/api/threads/" tid "/rebuild") nil)
               reply (json/read-str (.body resp) :key-fn keyword)]
           (is (= 400 (.statusCode resp)))
           (is (re-find #"line 1" (:error reply))))))
     (testing "a thread with no log anywhere in the tree is a 404, naming the thread"
       ;; NOT a 400: nothing was rebuilt and nothing was wrong with a log -- the
       ;; stem simply names nothing, which is what 404 means. It is also the answer
       ;; a client that deleted its own session and reloaded the page needs.
       (let [resp  (api-call :post "/api/threads/no-such-thread-xyz/rebuild" nil)
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
             line (row-json
                   {:ts 1 :runId "r1" :kind "message" :source "client" :id "u1"
                    :payload {:role "user" :content "hi"}})]
         (.mkdirs (.getParentFile ^java.io.File two))
         (spit one (str line "\n") :encoding "UTF-8")
         (spit two (str line "\n") :encoding "UTF-8")
         (let [resp  (api-call :post (str "/api/threads/" tid "/rebuild") nil)
               reply (json/read-str (.body resp) :key-fn keyword)]
           (is (= 404 (.statusCode resp)))
           (is (str/includes? (:error reply) "harness-http-project"))
           (is (str/includes? (:error reply) "unbound"))))))))

;; ------------------------------------------------- skills and instructions, end to end

(def ^:private skill-script
  [{:content ""
    :tool-calls [{:id "s1" :name "skill" :arguments {:name "alpha"}}]}
   {:content "followed it"}])

(def ^:private slash-script
  "One plain reply and NO tool call: a slash-load has to reach the conversation
  without the model asking for anything, which is the whole difference between it
  and the load above."
  [{:content "done"}])


(deftest an-opening-block-reaches-the-model-and-the-client-can-see-it
  ;; The whole shape, through the real edge, AFTER `.scratch/session-opening`: the
  ;; instruction files and the skills catalog are part of what the CONVERSATION was
  ;; born with -- they are written into it once, in front of the question, and every
  ;; later run continues from them. A person still sees each one as an
  ;; injected-context card, but the card now travels with that entry rather than being
  ;; re-emitted as a CUSTOM frame on every run; what a frame carries is what a run
  ;; DERIVED for itself (here: the skill body the model asked for). The card is a
  ;; `data` part, which the client's outgoing conversion never sends back.
  ;;
  ;; BOTH HOMES ARE THIS TEST'S OWN (support/with-temp-env): the conventions go in a
  ;; temp OS home and the project in a temp root, so nothing is planted in the pair
  ;; every test in this JVM shares -- a skill left there is an `alpha` every later
  ;; catalog lists, and the wipe that used to clean it up was a delete against the OS
  ;; home itself.
  (support/with-temp-env
   [_root home]
   (let [proj      (support/temp-dir "http-skills")
         skill-dir (str (io/file home ".agents" "skills" "alpha"))]
     (.mkdirs (io/file skill-dir))
     (spit (str (io/file home "AGENTS.md")) "STANDING RULE\n" :encoding "UTF-8")
     (spit (str (io/file proj "AGENTS.md")) "PROJECT RULE\n" :encoding "UTF-8")
     (spit (str skill-dir "/SKILL.md")
           "---\nname: alpha\ndescription: alpha does a thing\n---\n\nALPHA BODY\n"
           :encoding "UTF-8")
     (project/bind! "it-skills" proj)
     (try
      (with-server
       "it-skills" skill-script
       (fn []
         (let [resp      (.body (post-run "it-skills"))
               frames    (wire/frames-from-sse resp)
               lines     (wait-for-recorded
                          (log-file-for "it-skills")
                          (fn [ls] (some #(and (= "message" (replay/kind %))
                                               (= "followed it" (get-in (replay/payload %) [:content])))
                                         ls))
                          2000)
               texts     (mapv #(str (get-in (replay/payload %) [:content]))
                               (filter #(= "message" (replay/kind %)) lines))
               wire-text (json/write-str frames)]

            (testing "the order the model reads is: the question, then the opening"
              ;; THE QUESTION COMES FIRST (ticket 03 of `.scratch/session-opening`): the
              ;; opening is written into the conversation at its birth and read by every
              ;; run after it -- ONE TIME, which is the half that mattered -- but it is
              ;; written BEHIND the question that caused it, which is the order
              ;; `.scratch/context-frames` decision 7 and `CONTEXT.md`'s 注入 entry
              ;; state. Ticket 01 of this feature had it in front of the question; this
              ;; ticket is the correction. Per-run material (the skill body the model
              ;; asks for) still lands behind the whole conversation.
              ;; The reading is by TEXT because an opening entry carries parts (a card for
              ;; the screen, this text for the model).
              (let [reading    (fn [content]
                                 (if (sequential? content)
                                   (str/join "\n" (keep :text content))
                                   (str content)))
                    user-texts (mapv #(reading (get-in (replay/payload %) [:content]))
                                     (filter #(and (= "message" (replay/kind %))
                                                   (= "user" (get-in (replay/payload %) [:role])))
                                             lines))]
                (is (str/includes? (first user-texts) "看看这个项目")
                    "the person's own words open the conversation")
                (is (str/includes? (second user-texts) "STANDING RULE")
                    "then the OS home's rules")
                (is (str/includes? (nth user-texts 2) "PROJECT RULE")
                    "then the project's -- the more specific statement is the nearer one")
                (is (str/starts-with? (nth user-texts 3) "<skills>"))
                (is (str/includes? (nth user-texts 3) "- alpha: alpha does a thing")
                    "and the catalog is the last thing the opening says")))

           (testing "loading it mid-run puts the BODY into the conversation"
             (is (some #(and (str/includes? % "ALPHA BODY")
                             (str/starts-with? % "<skill name=\"alpha\">"))
                       texts)))

           (testing "the opening is on the wire TOO, as the CONVERSATION this run wrote"
             ;; WHY IT IS HERE AT ALL: the page that MINTED this session holds no window
             ;; and follows no feed, so the run that wrote the opening is the only wire
             ;; those messages can arrive on -- without them that page draws the question
             ;; and nothing else. AND WHY IT IS A MESSAGE LIST: a card frame is a PART,
             ;; and the adapter hangs a part on the message it is streaming (`CUSTOM`'s
             ;; `messageId` is dropped on the way in, by the parser and by the aggregator
             ;; alike), so a card for a message the client never held lands under the
             ;; answer. A `MESSAGES_SNAPSHOT` carries the messages themselves
             ;; (`ag-ui/conversation-snapshot`).
             (let [cards    (filterv #(and (= "CUSTOM" (:type %))
                                           (= "injected-context" (:name %)))
                                     frames)
                   derived  (filterv #(re-find #"-ctx\d+$" (str (:messageId %))) cards)
                   snapshot (first (filter #(= "MESSAGES_SNAPSHOT" (:type %)) frames))
                   messages (:messages snapshot)
                   opening  (filterv #(str/starts-with? (str (:id %)) "session-opening-")
                                     messages)
                   ;; THE SNAPSHOT'S MESSAGES ARE PROJECTED (text, not parts): the wire's
                   ;; schema takes no `data` part, so this reads the entry's text either way.
                   text-of  (fn [m] (let [c (:content m)]
                                      (if (sequential? c)
                                        (str/join "\n" (keep :text c))
                                        (str c))))
                   card-text (fn [card] (str (get-in card [:value :text])))]
               (is (= 1 (count cards))
                   "the only CUSTOM card is the body this run derived for itself")
               (is (str/includes? (card-text (first derived)) "ALPHA BODY"))
               (is (some? snapshot) "and the birth's opening rides as a snapshot")
               (is (= ["session-opening-0" "session-opening-1" "session-opening-2"]
                      (mapv :id opening))
                   "one message per entry, under the entry's OWN id, in the order the model read them")
               (is (some #(= "看看这个项目" (:content %))
                         (filter #(= "user" (:role %)) messages))
                   "and the person's own message is in it, in front of the opening")
               (is (str/includes? (text-of (first opening)) "STANDING RULE"))
               (is (str/includes? (text-of (nth opening 1)) "PROJECT RULE"))
               (is (str/starts-with? (text-of (nth opening 2)) "<skills>"))
               (is (str/includes? (text-of (nth opening 2)) "- alpha: alpha does a thing")
                   "the text is the same bytes the model read -- the other reading of one entry")))

            (testing "while the opening is the SESSION's own -- one entry per block, card and all"
              ;; WHERE A PERSON SEES IT. The entry carries the same `data` part a frame
              ;; does (`injected-part-name`), so the page draws the same card; the model
              ;; view drops that part and keeps the text (`sessions/model-view`). The ids
              ;; are FIXED, so a session born twice owns one opening rather than two.
              (let [messages (:messages (read-json
                                         (api-call :get "/api/threads/it-skills/sofar" nil)))
                    opening  (filterv ag/opening-entry? messages)]
                (is (= 3 (count opening))
                    "the two instruction files and the catalog are the session's opening")
                (is (= ["session-opening-0" "session-opening-1" "session-opening-2"]
                       (mapv :id opening)))
                (is (every? #(= "injected-context" (get-in % [:content 0 :name])) opening))
                (is (str/includes? (str (get-in (first opening) [:content 0 :data :text]))
                                   "STANDING RULE")
                    "the card carries the bytes the model read")
                (is (= "看看这个项目"
                       (:content (first (filter #(and (= "user" (:role %))
                                                      (not (ag/opening-entry? %)))
                                                messages))))
                    "and the person's own message is an entry of its own, IN FRONT of the opening")))

           (testing "the skill call itself IS on the wire, as an ordinary tool card"
             (is (some #(= "skill" (:toolCallName %))
                       (filter #(= "TOOL_CALL_START" (:type %)) frames)))))))
      (finally
        (project/bind! "it-skills" nil)
        (support/wipe-tree! proj))))))

(deftest an-unreadable-instruction-file-stops-the-run-by-name
  ;; The contrast with a broken skill, asserted where it matters: at the edge, as
  ;; a RUN_ERROR the client sees, rather than a silently rule-less run.
  (support/with-temp-env
   [_root home]
   (let [f (io/file home "AGENTS.md")]
     (spit (str f) "rules\n" :encoding "UTF-8")
     (.setReadable f false false)
     (if (.canRead f)
       (is true "permission bits do not apply to this user; nothing to assert")
       (with-server
        "it-badrules" script
        (fn []
          (let [frames (wire/frames-from-sse (.body (post-run "it-badrules")))]
            (testing "the client gets a terminated run carrying the reason"
              (is (= "RUN_ERROR" (:type (last frames))))
              (is (str/includes? (str (:message (last frames)))
                                 "cannot read the instruction file")))
            (testing "and no LLM call was made at all"
              (is (not-any? #(= "TOOL_CALL_START" (:type %)) frames))))))))))

(deftest the-skill-list-endpoint-answers-what-a-person-may-pick
  ;; The person's way in is a menu, and this is the one question it asks: which
  ;; roots does this session read, which of them is which layer, and who won a name
  ;; conflict. Every one of those is a fact only the server has -- a client that
  ;; re-derived any of them from the wire would be a second answer, free to drift
  ;; from the one the model's catalog is built from.
  (support/with-temp-env
   [_root home]
   (let [proj      (support/temp-dir "http-picker")
         user-root (str (io/file home ".agents" "skills"))
         proj-root (str (io/file proj ".agents" "skills"))
         ;; spit does not make parents, and a skill IS a directory holding a
         ;; SKILL.md -- so the fixture makes both, the way lay-skill! does in
         ;; harness.cap.skills-test.
         skill!    (fn [root name frontmatter]
                     (let [f (io/file root name "SKILL.md")]
                       (.mkdirs (.getParentFile f))
                       (spit (str f) frontmatter :encoding "UTF-8")))]
     (skill! user-root "shared"
             "---\nname: shared\ndescription: the MACHINE's shared\n---\n\nbody\n")
     (skill! user-root "manual-only"
             "---\nname: manual-only\ndescription: only a human runs this\ndisable-model-invocation: true\n---\n\nbody\n")
     (skill! user-root "misnamed"
             "---\nname: something-else\ndescription: says a different name\n---\n\nbody\n")
     (skill! proj-root "shared"
             "---\nname: shared\ndescription: the PROJECT's shared\n---\n\nbody\n")
     (skill! proj-root "only-project"
             "---\nname: only-project\ndescription: project only\n---\n\nbody\n")
     (project/bind! "it-picker" proj)
     (try
      (with-server
       "it-picker" script
       (fn []
         (let [body   (read-json (api-call :get "/api/skills?threadId=it-picker" nil))
               groups (:groups body)
               rows   (fn [group] (into {} (map (juxt :name identity)) (:skills group)))]

           (testing "one group per root, in precedence order, each naming its layer and its path"
             (is (= ["system" "project"] (mapv :layer groups)))
             (is (= [user-root proj-root] (mapv :root groups))))

           (testing "a row is what a menu draws: a name, a description, and whether it can be used"
             (is (= {:name "shared" :description "the MACHINE's shared"
                     :available? true :reason nil}
                    ((rows (first groups)) "shared"))
                 "the machine's copy won the name conflict...")
             (is (not (contains? (rows (second groups)) "shared"))
                 "...so the project's copy is not on the list -- it cannot be loaded")
             (is (= ["only-project"] (keys (rows (second groups))))))

           (testing "the list holds what a PERSON may load, not what the model may use"
             (let [machine (rows (first groups))]
               (is (true? (:available? (machine "manual-only")))
                   "a person typing /name is the person deciding; the server loads it for them")
               (is (false? (:available? (machine "misnamed")))
                   "a broken skill is still listed -- it says why it cannot be used")
               (is (= "manual-only" (:name (machine "manual-only"))))
               ;; Over the wire a reason is a STRING: read-json keywordizes the
               ;; keys and leaves the values alone, and `scan`'s vocabulary is
               ;; what the client compares against.
               (is (= "name-mismatch" (:reason (machine "misnamed"))))))

           (testing "an unbound session is not an error: the machine's skills, and no project ones"
             (let [unbound (read-json (api-call :get "/api/skills?threadId=it-picker-unbound" nil))]
               (is (= ["system"] (mapv :layer (:groups unbound))))
               (is (= [user-root] (mapv :root (:groups unbound))))))

           (testing "the route is read-only, and a GET that changes nothing leaves no trace"
             (is (not (.exists (io/file (log-dir) "it-picker-unbound.jsonl")))))

           (testing "only GET is served, and the refusal has the shape every other route's has"
             (let [resp (api-call :post "/api/skills?threadId=it-picker" "{}")]
               (is (= 405 (.statusCode resp)))
               (is (= "method not allowed" (:error (read-json resp))))))

           (testing "a session with no skills at all answers an empty list, not a 404"
             ;; An UNBOUND thread, because that is the session with no project root:
             ;; the machine's is now empty (wiped below, in this test's OWN home), so
             ;; there is nothing to pick and the route still says so in the ordinary way.
             (support/wipe-tree! (io/file home ".agents"))
             (let [resp (api-call :get "/api/skills?threadId=it-picker-empty" nil)]
               (is (= 200 (.statusCode resp)))
               (is (= [] (:groups (read-json resp)))))))))
      (finally
        (project/bind! "it-picker" nil)
        (support/wipe-tree! proj))))))

(deftest a-slash-load-reaches-the-model-and-is-a-card-in-the-conversation
  ;; The SECOND source of an injected body, asserted at the edge for the reason the
  ;; first one is: what the client HOLDS is decided by which frames go out, and a new
  ;; way for a body to enter is exactly what could put one in the conversation. IT USED
  ;; TO SAY "AND NEVER THE CLIENT" (there was no frame at all): since 2026-09-18 a body
  ;; is an injected-context CUSTOM frame -- so it is SEEN, and the assertion that
  ;; matters is that no OTHER frame carries it (the card is a `data` part, which
  ;; `toAgUiMessages` does not send back). The provider here is scripted with NO tool
  ;; call, so anything in the conversation got there because a person typed it.
  (support/with-temp-env
   [_root home]
   (let [proj      (support/temp-dir "http-slash")
         skill-dir (str (io/file home ".agents" "skills" "alpha"))]
     (.mkdirs (io/file skill-dir))
     (spit (str skill-dir "/SKILL.md")
           "---\nname: alpha\ndescription: alpha does a thing\n---\n\nALPHA BODY\n"
           :encoding "UTF-8")
     (project/bind! "it-slash" proj)
     (try
      (with-server
       "it-slash" slash-script
       (fn []
         (let [resp      (.body (post-run "it-slash"
                                          {:append [{:id "u1" :role "user"
                                                       :content "/alpha fix the bug"}]}))
               frames    (wire/frames-from-sse resp)
               lines     (wait-for-recorded
                          (log-file-for "it-slash")
                          (fn [ls] (some #(and (= "message" (replay/kind %))
                                               (= "done" (get-in (replay/payload %) [:content])))
                                         ls))
                          2000)
               texts     (mapv #(str (get-in (replay/payload %) [:content]))
                               (filter #(= "message" (replay/kind %)) lines))]

           (testing "the person's own words reach the model exactly as typed"
             ;; NOT asserted against the frames: the client sent those words, so
             ;; the AG-UI stream has nothing to echo -- an assertion that they
             ;; appear there would be asserting an invention. "The trigger is not
             ;; rewritten" is a claim about the MODEL's record, and it has to hold
             ;; there because that record is what the next turn re-reads to know
             ;; this skill was loaded.
             (is (some #(= "/alpha fix the bug" %) texts)))

           (testing "the body is a USER message in the run's record, right after the ask"
             (is (some #(and (str/includes? % "ALPHA BODY")
                             (str/starts-with? % "<skill name=\"alpha\">"))
                       texts)))

            (testing "and it is a CARD -- one CUSTOM frame, carrying exactly those bytes"
              ;; THE `/name` PATH'S HALF OF THE FEATURE, and the one a person actually sees:
              ;; the trigger is still the message they typed, the body is behind it, and the
              ;; card is how a reader learns the model was handed it at all. IT IS THE EDGE'S
              ;; OWN FRAME here (the ask that pulled the body is in the history the client
              ;; sent, so the body is folded in before the first call) -- hence `-pre<i>`,
              ;; where the kernel's mid-run splices are `-ctx<n>`.
              ;;
              ;; THE SESSION'S OPENING RIDES AS THE CONVERSATION HERE TOO, and that is
              ;; the other half of the fix: this run BIRTHS the conversation, and the page
              ;; that minted it follows no feed -- so the messages the birth wrote travel
              ;; with this run's start (`ag-ui/conversation-snapshot`). The CATALOG is not
              ;; a derived card any more (it is the opening entry the conversation was born
              ;; with), which is what the two assertions below now say separately.
              (let [cards    (filterv #(and (= "CUSTOM" (:type %))
                                            (= "injected-context" (:name %)))
                                      frames)
                    derived  (filterv #(re-find #"-pre\d+$" (str (:messageId %))) cards)
                    snapshot (first (filter #(= "MESSAGES_SNAPSHOT" (:type %)) frames))
                    opening  (filterv #(str/starts-with? (str (:id %)) "session-opening-")
                                      (:messages snapshot))
                    texts    (mapv #(str (get-in % [:value :text])) derived)
                    body     (first (filter #(str/starts-with? % "<skill name=\"alpha\">")
                                            texts))]
                (is (= 1 (count derived))
                    "the body the person asked for -- and nothing else this run derived")
                (is (some? body))
                (is (str/includes? (str body) "ALPHA BODY"))
                (is (= ["session-opening-0"] (mapv :id opening))
                    "and the birth's own opening, as the message the entry is")
                (is (str/starts-with? (if (sequential? (:content (first opening)))
                                        (str/join "\n" (keep :text (:content (first opening))))
                                        (str (:content (first opening))))
                                      "<skills>"))))

            (testing "and no OTHER frame carries it -- the card is the only way out"
              (let [others  (remove #(and (= "CUSTOM" (:type %))
                                          (= "injected-context" (:name %)))
                                    frames)
                    as-text (json/write-str others)]
                (is (not (str/includes? as-text "ALPHA BODY")))
                (is (not (str/includes? as-text "<skill name=")))))

           (testing "no skill tool call happened -- nothing asked the model for anything"
             (is (not-any? #(= "skill" (:toolCallName %))
                           (filter #(= "TOOL_CALL_START" (:type %)) frames)))))))
     (finally
       (project/bind! "it-slash" nil)
       (support/wipe-tree! proj))))))

(deftest a-slash-load-is-in-the-side-the-run-really-submitted
  ;; Ticket 02's record half, end to end. The body a `/name` asks for is folded in BEFORE
  ;; the `message` record's submitted side is written, so the two halves of that record
  ;; line up with the boundary the kernel starts from -- an injection the kernel made on
  ;; its own would shift the boundary and file a client message as part of its answer.
  ;; The same bytes are then folded by the trajectory, because 'what the record says' and
  ;; 'what the view draws' are two halves of one promise.
  (support/with-temp-env
   [_root home]
   (let [proj      (support/temp-dir "http-slash-side")
         skill-dir (str (io/file home ".agents" "skills" "alpha"))
         ask       {:id "u1" :role "user" :content "/alpha fix the bug"}]
     (.mkdirs (io/file skill-dir))
     (spit (str skill-dir "/SKILL.md")
           "---\nname: alpha\ndescription: alpha does a thing\n---\n\nALPHA BODY\n"
           :encoding "UTF-8")
     (project/bind! "it-slash-side" proj)
     (try
      (with-server
       "it-slash-side" [{:content "first"} {:content "second"}]
       (fn []
         (post-run "it-slash-side" {:append [ask]})
         ;; Wait for the FIRST run to be written through its returned side before sending
         ;; the second: the second run's input has to land after that tail in the file.
         (wait-for-recorded (log-file-for "it-slash-side")
                            (fn [ls] (some #(and (= "message" (replay/kind %))
                                                 (= "first" (get-in (replay/payload %) [:content])))
                                           ls))
                            2000)
         ;; THE SECOND RUN'S OWN FRAMES: this run derives NOTHING. The body the first run
         ;; folded in is part of the conversation by now -- `sessions/model-view` realises
         ;; the injection card back into the message the model read -- so the pre-LLM step
         ;; finds it already loaded and the edge has no card to send.
         (let [frames2 (wire/frames-from-sse
                              (.body (post-run "it-slash-side"
                                               ;; ONLY WHAT THIS ACTION ADDS (ticket 03). The
                                               ;; ask that pulled the body is in the SESSION's
                                               ;; history -- appended by run one -- and the body
                                               ;; it pulled is in the conversation too (folded
                                               ;; back by `sessions/model-view`), so this run
                                               ;; RE-USES it instead of deriving it again: no
                                               ;; re-injection and no second card.
                                               {:append [{:id "u2" :role "user" :content "and another thing"}]})))
               records (wait-for-recorded
                        (log-file-for "it-slash-side")
                        (fn [ls] (some #(and (= "message" (replay/kind %))
                                             (= "second" (get-in (replay/payload %) [:content])))
                                       ls))
                        2000)
               text-of (fn [r] (str (get-in (replay/payload r) [:content])))
               body?   (fn [r] (and (= "message" (replay/kind r))
                                    (= "user" (get-in (replay/payload r) [:role]))
                                    (str/starts-with? (text-of r) "<skill name=\"alpha\">")))
               input?  (fn [r] (and (= "message" (replay/kind r)) (= "client" (:source r))))
               event?  (fn [r] (= "event" (replay/kind r)))
               i1      (first (keep-indexed (fn [i r] (when (input? r) i)) records))
               i2      (second (keep-indexed (fn [i r] (when (input? r) i)) records))
               e1      (first (keep-indexed (fn [i r] (when (and (event? r) (> i i1)) i))
                                            records))
               e2      (first (keep-indexed (fn [i r] (when (and (event? r) (> i i2)) i))
                                            records))]
           (testing "the body is folded into the FIRST run's submitted side -- its real prompt"
             (is (= 1 (count (filter body? (subvec records i1 e1)))))
             (is (empty? (filter body? (subvec records e1)))
                 "and it is NOT filed past the split, as if the kernel had added it"))
           (testing "the SECOND run derives no body at all -- the ask is not re-folded"
             (is (empty? (filter body? (subvec records i2 e2))))
             (is (empty? (filter body? (subvec records e2)))
                 "the body lives in the conversation now, not in a per-run injection"))

           (testing "and the second run draws NO card -- the skill was injected once"
             ;; THE RUN THAT MUST NOT RE-INJECT. The body is already in the conversation
             ;; (`sessions/model-view` realises the card back into the message the model
             ;; read), so the pre-LLM step finds it loaded: the edge's diff is empty, the
             ;; kernel adds nothing, and the conversation keeps the ONE card the first run
             ;; drew. Before this, every run re-derived the body and re-showed its card.
             (let [cards (filter #(and (= "CUSTOM" (:type %))
                                       (= "injected-context" (:name %)))
                                 frames2)]
               (is (empty? cards)
                   "no injection card on a run that loaded nothing")))

           (testing "the trajectory then draws the ask's turn carrying it, and nothing else"
             ;; THE ORDER THE VIEW DRAWS IS THE ORDER THE MODEL READ: the question first
             ;; (ticket 03: the opening sits BEHIND it, `.scratch/session-opening`), then
             ;; the session's opening -- here the catalog -- and the body the ask pulled
             ;; in, both of them as context. And the SECOND turn carries no context: the
             ;; body is history by then, injected once rather than re-shown.
             (let [turns (:turns (trajectory/records->trajectory (vec records)))
                   ctx   (fn [turn] (filter #(= "context" (:kind %)) (:items turn)))]
               (is (= 2 (count turns)))
               (is (= ["system" "user" "context" "context" "assistant"]
                      (mapv :kind (:items (first turns))))
                   "the ask, then the two blocks the model read behind it")
               (is (some #(str/includes? (str (:text %)) "<skills>") (ctx (first turns)))
                   "the catalog")
               (is (some #(str/includes? (str (:text %)) "ALPHA BODY") (ctx (first turns)))
                   "the body the ask pulled in, both of them as the bytes they are")
               (is (every? #(nil? (:source %)) (mapcat ctx turns))
                   "and neither of them claims to have come from a place")
               (is (= ["user" "assistant"] (mapv :kind (:items (second turns))))
                   "no context this run did not carry -- and the client's own message is not
                    drawn as one instead"))))))
      (finally
        (project/bind! "it-slash-side" nil)
        (support/wipe-tree! proj))))))

;; ------------------------------------------------- the composer's own edge
;;
;; The routes the composer strip is built on: what it may offer (/api/choices),
;; what it may change (/api/model), and the working tree it draws (/api/git).
;; Exercised WITHOUT a scripted pin, deliberately -- a pin answers for the
;; provider outright, and every one of these asks a question about resolution.

(defn- with-bare-server
  "Like `with-server`, but pins NOTHING: real resolution, from the seeded
  config.edn upwards."
  [f]
  (let [stop (http/start! {:port 0})
        port (:local-port (meta stop))]
    (try (binding [*port* port] (f))
         (finally (stop)))))

(defn- log-lines-for
  "Every line of THREAD-ID's log, in order, read from wherever its log IS."
  [tid]
  (replay/lines->records
        (str/split-lines (slurp (log-file-for tid) :encoding "UTF-8"))))

(deftest the-choices-endpoint-offers-the-catalog-and-no-secret
  (with-bare-server
   (fn []
     (let [resp (api-call :get "/api/choices?threadId=composer-choices" nil)
           body (read-json resp)
           raw  (.body resp)
           by-name (into {} (map (juxt :name identity) (:providers body)))]
       (is (= 200 (.statusCode resp)))
       (testing "the whole built-in catalog, by provider, each with its models"
         (is (contains? by-name "deepseek"))
         (is (contains? (set (:models (by-name "deepseek"))) "deepseek-flash"))
         (is (contains? by-name "ollama"))
         (is (contains? by-name "openrouter")))
       (testing "the efforts worth offering, which are a menu and not a guard"
         (is (= ["low" "medium" "high"] (:reasoning-efforts body))))
       (testing "and no secret anywhere in the body"
         (is (not (str/includes? raw "api-key")))
         (is (not (str/includes? raw "api_key"))))))))

(deftest the-model-endpoint-changes-this-session-and-nothing-else
  (with-bare-server
   (fn []
     (let [id "composer-model"]
       (testing "before any choice, the session has no tier of its own"
         (is (nil? (providers/override-for id))))
       (testing "choosing a provider and model takes effect on THIS thread"
         (let [resp (api-call :post "/api/model"
                              (json/write-str {:threadId id :provider "deepseek"
                                               :model "deepseek-flash"}))
               body (read-json resp)]
           (is (= 200 (.statusCode resp)))
           (is (= "deepseek" (:provider body)))
           (is (= "deepseek-flash" (:model body)))
           (is (= {:provider :deepseek :model "deepseek-flash"}
                  (providers/override-for id)))))
       (testing "and a DIFFERENT thread is untouched -- 'only this session' is a fact"
         (is (nil? (providers/override-for "composer-other"))))
       (testing "choosing an effort leaves the model where it was"
         (let [body (read-json (api-call :post "/api/model"
                                         (json/write-str {:threadId id
                                                          :reasoning-effort "high"})))]
           (is (= "high" (:reasoning-effort body)))
           (is (= "deepseek-flash" (:model body)))))))))

(deftest the-model-endpoint-refuses-what-it-cannot-serve-and-writes-nothing
  (with-bare-server
   (fn []
     (let [id "composer-refuse"]
       (testing "a model the chosen provider does not declare"
         (let [resp (api-call :post "/api/model"
                              (json/write-str {:threadId id :provider "deepseek"
                                               :model "no-such-model"}))]
           (is (= 400 (.statusCode resp)))
           (is (str/includes? (:error (read-json resp)) "no-such-model"))
           (is (nil? (providers/override-for id))
               "a refused change leaves the session exactly where it was")))
       (testing "a provider that is not in the catalog"
         (is (= 400 (.statusCode (api-call :post "/api/model"
                                           (json/write-str {:threadId id
                                                            :provider "nope"}))))))
       (testing "a knob outside the three -- a model's counts are the catalog's"
         (let [resp (api-call :post "/api/model"
                              (json/write-str {:threadId id :model "x"
                                               :context-window 1000}))]
           (is (= 400 (.statusCode resp)))
           (is (str/includes? (:error (read-json resp)) "context-window"))))
       (testing "nothing named at all"
         (let [resp (api-call :post "/api/model" (json/write-str {:threadId id}))]
           (is (= 400 (.statusCode resp)))
           (is (str/includes? (:error (read-json resp)) "nothing to change"))))
       (testing "no thread"
         (is (= 400 (.statusCode (api-call :post "/api/model"
                                           (json/write-str {:model "x"}))))))
       (testing "and a body that is not JSON"
         (is (= 400 (.statusCode (api-call :post "/api/model" "not json at all")))))))))

(deftest clearing-the-session-tier-puts-it-back-on-the-tiers-below
  (with-bare-server
   (fn []
     (let [id "composer-clear"]
       (api-call :post "/api/model" (json/write-str {:threadId id :provider "deepseek"
                                                     :model "deepseek-flash"}))
       (is (some? (providers/override-for id)))
       (let [resp (api-call :post "/api/model" (json/write-str {:threadId id :clear true}))
             body (read-json resp)]
         (is (= 200 (.statusCode resp)))
         (is (nil? (providers/override-for id)) "the session's own tier is gone")
         (is (= "seeded" (:model body))
             "back on the config.edn provider the seed put there")
         (is (nil? (:reasoning-effort body))))))))

(def ^:private git-repo
  ;; A DIRECTORY OF THIS RUN'S OWN, and never deleted. `io/delete-file` cannot
  ;; reliably remove a `.git` directory -- it reports failure, and with `:silently
  ;; true` that failure is swallowed -- so a fixture that reuses one path would
  ;; sometimes run `git init` inside the previous run's repository and assert
  ;; against its leftovers. mkdtemp is the whole of the fix now; the per-JVM name
  ;; this used to compose was the same idea and only closed half the window.
  (support/temp-dir "http-git"))

(defn- make-git-repo
  "A real repository, so the route meets git rather than a story about git.
  Renamed rather than `init -b`: see harness.cap.git-test for why."
  []
  (let [dir git-repo
        run (fn [c] (shell/run {:command c :dir dir}))]
    (.mkdirs (io/file dir))
    (run "git init -q")
    (run "git config user.email test@example.invalid")
    (run "git config user.name 'harness test'")
    (run "git config commit.gpgsign false")
    (spit (io/file dir "README.md") "hello\n" :encoding "UTF-8")
    (run "git add README.md")
    (run "git commit -q -m first")
    (run "git branch -m main")
    (run "git branch side")
    dir))

(deftest the-git-endpoint-reads-and-moves-the-sessions-working-tree
  (make-git-repo)
  (with-bare-server
   (fn []
     (let [id "composer-git"]
       (testing "a session with no directory has no branch, and that is not an error"
         (let [body (read-json (api-call :get (str "/api/git?threadId=" id) nil))]
           (is (false? (:repo? body)))
           (is (nil? (:dir body)))))
       (testing "and switching is refused by name, because there is nothing to switch"
         (let [resp (api-call :post "/api/git"
                              (json/write-str {:threadId id :branch "main"}))]
           (is (= 400 (.statusCode resp)))
           (is (str/includes? (:error (read-json resp)) "no project directory"))))
       (api-call :post "/api/project" (json/write-str {:threadId id :dir git-repo}))
       (testing "bound, the strip reads the branch and the branches"
         (let [body (read-json (api-call :get (str "/api/git?threadId=" id) nil))]
           (is (true? (:repo? body)))
           (is (= "main" (:branch body)))
           (is (= #{"main" "side"} (set (:branches body))))
           (is (zero? (:dirty body)))))
       (testing "switching moves the real repository"
         (let [resp (api-call :post "/api/git"
                              (json/write-str {:threadId id :branch "side"}))
               body (read-json resp)]
           (is (= 200 (.statusCode resp)))
           (is (= "side" (:branch body)))
           (is (= "side" (str/trim (:out (shell/run {:command "git rev-parse --abbrev-ref HEAD"
                                                    :dir git-repo})))))))
       (testing "and the audit line records the move, before -> after"
         (let [lines (filterv #(= "git/branch" (replay/kind %)) (log-lines-for id))]
           (is (= 1 (count lines)))
           (is (= "main" (get-in (replay/payload (first lines)) [:before])))
           (is (= "side" (get-in (replay/payload (first lines)) [:after])))))
       (testing "a branch the worktree does not have is refused, and writes no line"
         (let [resp (api-call :post "/api/git"
                              (json/write-str {:threadId id :branch "nope"}))]
           (is (= 400 (.statusCode resp)))
           (is (str/includes? (:error (read-json resp)) "nope")))
         (is (= 1 (count (filterv #(= "git/branch" (replay/kind %)) (log-lines-for id))))
             "the refusal left no trace on disk"))))))

;; ------------------------------------------------ the provider catalog, over HTTP

(def ^:private provider-sentinel "sk-or-v1-PROVIDER-SENTINEL-DO-NOT-PUBLISH-4242")

(defn- home-facts
  "Every file in this home, by name, with its bytes and mtime -- the shape a claim
  like 'only these files moved' can be checked against."
  []
  (into {} (for [f (reverse (file-seq (io/file (home/root))))
                 :when (.isFile ^java.io.File f)]
             [(.getName ^java.io.File f) [(.length ^java.io.File f) (.lastModified ^java.io.File f)]])))

(def ^:private a-provider-body
  {"id"          "acme-gateway"
   "display-name" "Acme Gateway"
   "protocol"    "openai-completions"
   "base-url"    "https://gateway.example/v1"
   "model"       "gpt-x"
   "models"      [{"id" "gpt-x" "input" ["text"] "output" ["text"] "context-window" 128000}]})

(deftest the-provider-catalog-is-readable-and-writable-over-the-edge
  (with-resolved-config
   []
   (fn []
     (let [get-providers (fn [] (api-call :get "/api/providers" nil))
           row           (fn [body n] (first (filter #(= n (:name %)) (:providers body))))
           dotenv        (io/file (home/root) ".env")
           cfg           (io/file (home/root) "config.edn")
           ;; The .env is not part of the fixture: it is created here and taken away
           ;; afterwards so this test does not hand a key to whatever runs next.
           old-env       (when (.exists dotenv) (slurp dotenv :encoding "UTF-8"))]
       (try
         (testing "GET answers the catalog from the files, with no session in the URL"
           (let [resp (get-providers)
                 body (read-json resp)]
             (is (= 200 (.statusCode resp)))
             (is (= ["alpha" "beta" "deepseek" "ollama" "openrouter"]
                    (sort (mapv :name (:providers body))))
                 "the two from config.edn and the three built-ins")
             (is (= "user" (:origin (row body "alpha"))) "config.edn's entry is yours")
             (is (= "builtin" (:origin (row body "openrouter"))))
             (is (= (sort (map name (keys (methods llm/stream!))))
                    (:protocols body))
                 "exactly the protocols this PROCESS implements, read off the
                  multimethod -- so a new implementation is offerable without a second
                  edit, and this test process truthfully offers the fake too")
             (is (some #{"openai-completions"} (:protocols body)))
             (is (= {:provider "alpha"} (:default body))
                 "the default tier as the file writes it, for the page that edits it")
             (is (= "ALPHA_API_KEY" (:credential (row body "alpha"))))
             (is (false? (get-in (row body "alpha") [:key :present?])))))

         (testing "POST creates one, and answers the catalog it now has"
           (let [before (home-facts)
                 resp   (api-call :post "/api/providers"
                                  (json/write-str (assoc a-provider-body "api-key" provider-sentinel)))
                 body   (read-json resp)
                 after  (home-facts)]
             (is (= 200 (.statusCode resp)))
             (is (= "user" (:origin (row body "acme-gateway"))))
             (is (= "Acme Gateway" (:display-name (row body "acme-gateway"))))
             (is (= [{:id "gpt-x" :input ["text"] :output ["text"] :context-window 128000}]
                    (:models (row body "acme-gateway"))))
             (is (true? (get-in (row body "acme-gateway") [:key :present?])))
             (is (= "ACME_GATEWAY_API_KEY" (get-in (row body "acme-gateway") [:key :name])))

             (testing "the key's VALUE is nowhere in the answer"
               (is (not (str/includes? (.body resp) provider-sentinel)))
               (is (not (str/includes? (.body resp) (subs provider-sentinel 0 12)))))

             (testing "and exactly three files moved: the config, its backup, the .env"
               (is (= #{"config.edn" "config.edn.bak" ".env"}
                      (set (filter #(not= (get before %) (get after %)) (keys after))))
                   "no log, no store, no other file -- the route writes configuration"))

             (testing "and the next resolution serves it, with no restart"
               (providers/set-override! "edge-write" {:provider :acme-gateway})
               (let [p (providers/effective-provider "edge-write")]
                 (is (= "https://gateway.example/v1" (:base-url p)))
                 (is (= provider-sentinel (:api-key p)) "including the key that just landed"))
               (providers/set-override! "edge-write" nil))))

         (testing "a change that cannot be served is refused, and the file does not move"
           (let [before (.length cfg)
                 stamp  (.lastModified cfg)
                 resp   (api-call :post "/api/providers"
                                  (json/write-str (assoc a-provider-body "models" [])))
                 body   (read-json resp)]
             (is (= 400 (.statusCode resp)))
             (is (str/includes? (:error body) "lists no models")
                 "the server's own sentence, which is what the form will show")
             (is (= before (.length cfg)))
             (is (= stamp (.lastModified cfg)) "byte for byte, mtime included")))

         (testing "an id the form may not create is refused with the rule in the sentence"
           (let [resp (api-call :post "/api/providers"
                                (json/write-str (assoc a-provider-body "id" "Acme Gateway")))]
             (is (= 400 (.statusCode resp)))
             (is (str/includes? (:error (read-json resp)) "lowercase letter"))))

         (testing "a body that is not JSON, and one that is not an object, are both 400s"
           (is (= 400 (.statusCode (api-call :post "/api/providers" "{not json"))))
           (is (= 400 (.statusCode (api-call :post "/api/providers" "[1,2]"))))
           (is (= 400 (.statusCode (api-call :post "/api/providers" "{}")))
               "and an entry with no id has nothing to derive a credential name from"))

         (testing "a built-in is not an entry the file holds, so there is nothing to remove"
           (let [resp (api-call :post "/api/providers/openrouter/remove" nil)]
             (is (= 400 (.statusCode resp)))
             (is (str/includes? (:error (read-json resp)) "nothing to remove"))))

         (testing "and removing what IS there takes it out of the catalog"
           (let [resp (api-call :post "/api/providers/acme-gateway/remove" nil)
                 body (read-json resp)]
             (is (= 200 (.statusCode resp)))
             (is (= "acme-gateway" (:removed body)))
             (is (nil? (row body "acme-gateway")) "the answer already reflects the removal")
             (is (nil? (row (read-json (get-providers)) "acme-gateway")))
             (is (not (str/includes? (slurp cfg) "acme-gateway")))))

         (testing "the verb shape is closed"
           ;; A verb nobody serves is NOT 405'd here: it falls through to the AG-UI
           ;; run endpoint, which is what the closed verb set is for (see
           ;; stem-verb-route). What this collection answers is the GET on the
           ;; collection and the POST on a verb it serves.
           (is (= 405 (.statusCode (api-call :get "/api/providers/acme-gateway/remove" nil)))))

         (finally
           (if (nil? old-env)
             (io/delete-file dotenv true)
             (spit dotenv old-env :encoding "UTF-8"))))))))

(deftest a-home-with-no-config-edn-is-given-one-by-the-server-that-starts-in-it
  ;; The composition root seeds it: a process about to serve from a home hands the
  ;; person a file to edit. The reader does not -- see providers/ensure-config!.
  (let [dir (io/file (support/temp-dir "http-noconfig"))
        old (home/root)]
    (try
      (with-redefs [home/root (constantly (str dir))]
        (let [stop (http/start! {:port 0})]
          (try
            ;; *port* is what `api-call` reads, and the wrappers that normally bind
            ;; it are the ones that START the server -- this test starts its own, so
            ;; it binds the port itself. See *port* for why no port is written down.
            (binding [*port* (:local-port (meta stop))]
              (let [f (io/file dir "config.edn")]
                (is (.exists f) "the boot wrote one")
                (is (str/includes? (slurp f :encoding "UTF-8") ":default")
                    "with the sections named in a comment, and an empty map")
                (is (= 200 (.statusCode (api-call :get "/api/providers" nil)))
                    "and the catalog answers from a home that says nothing")))
            (finally (stop)))))
      (finally (doseq [f (reverse (file-seq dir))] (io/delete-file f true))))))

(deftest asking-a-vendor-what-it-serves-stays-offline-in-this-suite
  ;; The ONE route in this feature that leaves the machine, tested with the seam at
  ;; a stub -- because the real one would make this suite depend on a vendor
  ;; answering, and a suite that needs the network is a suite that fails on a train.
  (with-resolved-config
   []
   (fn []
     (let [real      providers/*list-models*
           seen      (atom [])
           stub!     (fn [f] (alter-var-root #'providers/*list-models* (constantly f)))
           ask       (fn [body] (api-call :post "/api/providers/models" (json/write-str body)))]
       (try
         (stub! (fn [vendor]
                  (swap! seen conj vendor)
                  ["gpt-x" "gpt-y"]))
         (testing "the ids the vendor lists come back"
           (let [resp (ask {"base-url" "https://gateway.example/v1"
                            "protocol" "openai-completions"
                            "api-key"  "sk-typed-into-the-form"})
                 body (read-json resp)]
             (is (= 200 (.statusCode resp)))
             (is (= ["gpt-x" "gpt-y"] (:models body)))
             (is (= "https://gateway.example/v1" (:asked body)))
             (testing "and the key the FORM typed is the one that was sent"
               (is (= "sk-typed-into-the-form" (:api-key (last @seen))))
               (testing "while the answer carries it nowhere"
                 (is (not (str/includes? (.body resp) "sk-typed-into-the-form")))))))

         (testing "an :id alone asks the endpoint the catalog knows, with ITS key"
           ;; A built-in, so the assertions read as production behaviour rather than
           ;; as this fixture's offline provider: openrouter's own endpoint, and its
           ;; own credential name resolved out of .env.
           (spit (io/file (home/root) ".env") "OPENROUTER_API_KEY=from-the-file\n" :encoding "UTF-8")
           (let [resp (ask {"id" "openrouter"})
                 body (read-json resp)]
             (is (= 200 (.statusCode resp)))
             (is (= ["gpt-x" "gpt-y"] (:models body)))
             (is (= "https://openrouter.ai/api/v1" (:asked body))
                 "the catalog's endpoint, not one the caller guessed")
             (is (= :openai-completions (:protocol (last @seen))))
             (is (= "from-the-file" (:api-key (last @seen)))
                 "resolved exactly as a run resolves it: the provider's own name first"))
           (io/delete-file (io/file (home/root) ".env") true))

         (testing "a vendor that refuses comes back in its OWN words"
           (stub! (fn [_] (throw (ex-info "the vendor at https://x/v1 answered 401: invalid api key"
                                          {:status 401}))))
           (let [resp (ask {"base-url" "https://x/v1" "protocol" "openai-completions"})]
             (is (= 400 (.statusCode resp)))
             (is (str/includes? (:error (read-json resp)) "invalid api key")
                 "the vendor's sentence, not a paraphrase of it")))

         (testing "and a question that cannot be asked is refused before anything is sent"
           (stub! (fn [_] (throw (ex-info "the stub must not be reached" {}))))
           (is (= 400 (.statusCode (ask {"protocol" "openai-completions"})))
               "no endpoint to ask and no id to look one up")
           (is (= 400 (.statusCode (ask {"base-url" "https://x/v1" "protocol" "anthropic-messages"}))
               )
               "and a protocol nothing implements")
           (is (= 400 (.statusCode (ask "[1,2]"))))
           (is (= 400 (.statusCode (api-call :post "/api/providers/models" "{not json")))))

         (testing "and it writes nothing: no file, no store, no log line"
           (stub! (fn [_] ["gpt-x"]))
           (let [before (home-facts)]
             (ask {"base-url" "https://x/v1" "protocol" "openai-completions"})
             (is (= (keys before) (keys (home-facts)))
                 "not one file appeared")))

         (finally (alter-var-root #'providers/*list-models* (constantly real))))))))

(deftest the-default-tier-is-set-over-the-edge
  (with-resolved-config
   []
   (fn []
     (let [ask-defaults (fn [knobs] (api-call :post "/api/defaults" (json/write-str knobs)))
           cfg          (io/file (home/root) "config.edn")]
       (testing "naming a vendor and a model lands in :default, and the answer says so"
         (let [resp (ask-defaults {"provider" "beta" "model" "beta-plain"})
               body (read-json resp)]
           (is (= 200 (.statusCode resp)))
           (is (= {:provider "beta" :model "beta-plain"} (:default body))
               "the same answer GET gives, so a client parses one shape")
           (is (str/includes? (slurp cfg) ":beta"))))

       (testing "a body with no provider patches the tier rather than replacing it"
         (let [resp (ask-defaults {"reasoning-effort" "high"})]
           (is (= 200 (.statusCode resp)))
           (is (= {:provider "beta" :model "beta-plain" :reasoning-effort "high"}
                  (:default (read-json resp))))))

       (testing "and an explicit null removes that key"
         (let [resp (ask-defaults {"model" nil})]
           (is (= 200 (.statusCode resp)))
           (is (nil? (:model (:default (read-json resp)))))
           (is (= {:provider :beta :reasoning-effort "high"}
                  (:default (read-string (slurp cfg))))
               "the model is gone from the TIER (the provider entry still declares it
                as one of its models), so a new session lands on the vendor's own
                default")))

       (testing "a default nobody can be served from is refused, file untouched"
         (let [before (home-facts)
               resp   (ask-defaults {"provider" "beta" "model" "no-such-model"})]
           (is (= 400 (.statusCode resp)))
           (is (str/includes? (:error (read-json resp)) "no-such-model"))
           (is (= before (home-facts))))
         (let [resp (ask-defaults {"provider" "nope"})]
           (is (= 400 (.statusCode resp)))
           (is (str/includes? (:error (read-json resp)) "no provider named"))))

       (testing "and the tier report follows the file, so the page can show which
                 tier a knob came from"
         (ask-defaults {"provider" "alpha" "model" "alpha-small"})
         (let [reply (read-json (api-call :get "/api/settings?threadId=t-defaults" nil))]
           (is (= "alpha" (:provider reply)))
           (is (= "config" (:provider (:tiers reply)))
               "the file is where this resolution started, and the panel says so")))

       (testing "only POST, and it takes no thread: the default tier is this home's"
         (is (= 405 (.statusCode (api-call :get "/api/defaults" nil)))))))))

;; ---------------------------------------- a thinking-mode vendor, end to end

(def ^:private a-reasoning-less-tool-round
  "A turn that calls a tool WITHOUT producing reasoning, but still MENTIONS the field
  (its :reasoning is an empty string) -- the shape the real gateway answers with, and
  the shape that used to make the next request 400."
  [{:reasoning "" :content "" :tool-calls [{:id "c1" :name "read" :arguments {:path "deps.edn"}}]}
   {:reasoning "" :content "done reading"}])

(deftest a-thinking-mode-vendor-gets-the-field-back-on-the-next-request
  ;; THE BUG THIS FEATURE EXISTS FOR, end to end and offline: the first round returns a
  ;; tool call whose reasoning is EMPTY -- the shape the real gateway answers with, and
  ;; the shape that used to make the next request 400. The strict fake (`harness.fake`
  ;; :thinking) is that vendor, sentence and all, so this can no longer only be met in
  ;; production.
  (let [thread "t-think-echo"
        pin    (assoc (fake/scripted a-reasoning-less-tool-round {:thinking true})
                      :reasoning-effort "high")
        assistant-messages (fn []
                             (->> (str/split-lines (slurp (log-file thread) :encoding "UTF-8"))
                                  (replay/lines->records)
                                  (filter #(= "message" (replay/kind %)))
                                  (map replay/payload)
                                  (filter #(= "assistant" (:role %)))))]
    (providers/use-provider! thread pin)
    (let [stop (http/start! {:port 0})]
      (try
        (binding [*port* (-> stop meta :local-port)]
          (let [body (.body (post-run thread {:append [{:id "u1" :role "user" :content "read deps.edn"}]}))]
            (is (str/includes? body "RUN_FINISHED")
                "the run finishes -- so the SECOND request was accepted")
            (is (not (str/includes? body "must be passed back"))
                "with no sign of the vendor's refusal")))

        (testing "the second round really happened, and the log shows what was sent"
          ;; Two assistant messages on disk = two LLM rounds: the tool call, and the
          ;; answer that follows it. The second one exists only because the vendor
          ;; accepted the request carrying the first.
          ;;
          ;; WAITED FOR, not read immediately: the returned side of the message record
          ;; lands one beat after the terminal frame (see wait-for-recorded), so a
          ;; reader that races the consumer sees half a conversation.
          (wait-for-recorded (log-file thread)
                             #(<= 2 (count (filter (fn [r] (and (= "message" (replay/kind r))
                                                               (= "assistant" (get-in (replay/payload r) [:role]))))
                                                   %)))
                             5000)
          (let [assistant (assistant-messages)]
            (is (= 2 (count assistant)) "both rounds are on disk")
            (is (contains? (first assistant) :reasoning_content)
                "and the tool-call round carries the field the request carried: the
                 log does not disagree with the wire")
            (is (= "" (:reasoning_content (first assistant)))
                "empty, because that is what the vendor said -- not invented text")))
        (finally (stop) (providers/use-provider! thread nil))))))

(deftest a-client-history-that-lost-the-field-is-repaired-on-the-way-out
  ;; THE OTHER HALF, and the reason the padding lives at the edge rather than in
  ;; `stream!`: a history that comes BACK from a client need not carry a field only a
  ;; provider cares about. The client is the source of truth for WHICH messages exist;
  ;; this repair only puts the vendor's requirement back on them.
  (let [history [{:id "u1" :role "user" :content "hi"}
                 {:id "a1" :role "assistant" :content "hello"}]      ; no reasoning_content
        run     (fn [thread provider]
                  (providers/use-provider! thread provider)
                  (let [stop (http/start! {:port 0})]
                    (try
                      (binding [*port* (-> stop meta :local-port)]
                        (.body (post-run thread {:append history})))
                      (finally (stop) (providers/use-provider! thread nil))))
                  (wait-for-recorded
                   (log-file thread)
                   #(some (fn [r] (and (= "message" (replay/kind r))
                                       (= "assistant" (get-in (replay/payload r) [:role]))))
                          %)
                   5000)
                  (->> (str/split-lines (slurp (log-file thread) :encoding "UTF-8"))
                       (replay/lines->records)
                       (filter #(= "message" (replay/kind %)))
                       (map replay/payload)
                       (filter #(= "assistant" (:role %)))))
        reply (fn [] [{:content "ok"}])]

    (testing "a thinking-mode provider gets the field added on the way OUT"
      ;; The strict vendor is in the room: without the pad this run is a 400.
      (let [body (run "t-pad-on" (assoc (fake/scripted (reply) {:thinking true})
                                        :reasoning-effort "high"))]
        (is (not (str/includes? body "must be passed back"))
            "the request the client sent would have been refused as-is")
        (is (not (contains? (first body) :reasoning_content))
            "and the RECORD keeps the client's own message: the pad is the vendor's requirement
             met on the wire (`llm/thinking-mode-history`), not something that entered the
             conversation -- an empty field nobody sent is not a fact about what was said")))

    (testing "a provider with no reasoning effort is untouched"
      (let [logged (run "t-pad-off" (fake/scripted (reply)))]
        (is (not (contains? (first logged) :reasoning_content))
            "nothing is invented onto a request that never needed it")))

    (testing "but a field the client DID send survives, thinking mode or not"
      ;; No reasoning effort here, so nothing can be padded: what the vendor is shown
      ;; is the client's own message, and the field on it must still be there. This is
      ;; the whitelist rebuild, not the pad -- and the two are easy to confuse.
      (let [with-field [{:id "u1" :role "user" :content "hi"}
                        {:id "a1" :role "assistant" :content "hello"
                         :reasoning_content "I looked it up"}]]
        (providers/use-provider! "t-client-field" (fake/scripted (reply)))
        (let [stop (http/start! {:port 0})]
          (try
            (binding [*port* (-> stop meta :local-port)]
              (post-run "t-client-field" {:append with-field}))
            (finally (stop) (providers/use-provider! "t-client-field" nil))))
        (wait-for-recorded (log-file "t-client-field")
                           #(some (fn [r] (and (= "message" (replay/kind r))
                                               (= "assistant" (get-in (replay/payload r) [:role]))))
                                  %)
                           5000)
        (let [assistant (->> (str/split-lines (slurp (log-file "t-client-field") :encoding "UTF-8"))
                             (replay/lines->records)
                             (filter #(= "message" (replay/kind %)))
                             (map replay/payload)
                             (filter #(= "assistant" (:role %))))]
          (is (= "I looked it up" (:reasoning_content (first assistant)))
              "the client's own words reached the vendor"))))))

;; ----------------------------------------------------- the process log
;;
;; The per-thread jsonl is the RUN's record; this is the PROCESS's, and it is the
;; file a person is pointed at when a session broke and the browser only says
;; something about a stream. Where the record stops mid-sentence, these lines are
;; what make the stopping readable: `run/start` with no `run/terminal` beside it is
;; a run that did not get to say goodbye, and whether a `:shutdown` line follows it
;; is the difference between a run still going and a process that was stopped.
;;
;; The ABNORMAL endings are the ones worth the lines, and each has a test below
;; because each is otherwise silent: a call that never ran, a stream that ended
;; without a terminal frame, and a run whose own body threw.

(defn- process-log
  "The process log under the test's own root -- not the per-thread jsonl. Derived
  from harness.infra.home rather than from a literal, so it follows the fixture's
  temp root like everything else the server writes."
  []
  (let [f (io/file (home/root) "logs" "harness.infra.log")]
    (if (.exists f) (slurp f :encoding "UTF-8") "")))

(defn- until
  "Poll F until it answers truthy, or MS elapses -- answering F's last value either
  way. THE ONE WAY THIS FILE ASKS A QUESTION OF ANOTHER THREAD: a run is a go block,
  the log is written by whoever got there first, and http-kit calls back on its own
  -- so a fact that has just been made true is asserted by waiting for it to become
  visible, never by sleeping a fixed amount and hoping.

  IT DOES NOT THROW ON TIMEOUT. A caller whose assertion is `(is (until ...))` gets
  the failure it wrote; a caller that wants to see what it was waiting FOR reads the
  answer and puts it in the message. `await-log` below is the first kind applied to
  the process log."
  [f ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (let [v (f)]
        (if (or v (< deadline (System/currentTimeMillis)))
          v
          (do (Thread/sleep 25) (recur)))))))

(defn- await-log
  "Wait for PAT to appear in the process log -- true once it is there, false after 5s.
  Far more than a log line takes to land, and the deadline is a fixed one on purpose:
  a case that needs to witness a line has no other way to ask, and a case that never
  sees it has a real failure rather than a slow machine."
  [pat]
  (boolean (until #(re-find pat (process-log)) 5000)))

(defn- fire-run!
  "Send a run over a socket of our OWN and do not read the answer.

  For the cases where the server has nothing to say: a run that never reaches a
  terminal frame sends no frames at all, so `post-run` -- which waits for the body
  to end, and a body only ends with a terminal frame -- would block forever. The
  caller closes the socket; the server does not care (nothing cancels a run when a
  client goes), which is what makes walking away a legitimate client."
  [thread-id]
  (let [_     (ensure-session! thread-id)
        body  (json/write-str {:threadId thread-id
                               :append [{:id "u1" :role "user" :content "hi"}]
                               :tools []})
        bytes (.getBytes body StandardCharsets/UTF_8)
        sock  (java.net.Socket. "127.0.0.1" (int *port*))
        out   (.getOutputStream sock)]
    (.write out (.getBytes (str "POST /api/agent HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                                "Content-Type: application/json\r\n"
                                "Accept: text/event-stream\r\n"
                                "Content-Length: " (count bytes) "\r\n\r\n")
                           StandardCharsets/UTF_8))
    (.write out bytes)
    (.flush out)
    sock))

(deftest a-run-says-where-it-started-and-where-it-ended
  ;; The ordinary story, and the shape everything else is read against. Asserted as
  ;; three lines rather than as "something was logged": a file with a start and no
  ;; terminal is exactly the broken case, so a test that cannot tell them apart
  ;; would pass on the bug.
  (with-server
   "diag-story"
   (fn []
     (post-run "diag-story")
     (is (await-log #"start .*thread-id=diag-story") "the run's start is in the file")
     (is (await-log #"terminal event=RUN_FINISHED .*thread-id=diag-story")
         "and the frame that ended it")
     (is (await-log #"stream-closed .*terminal=RUN_FINISHED .*thread-id=diag-story")
         "and the close, carrying http-kit's own reason"))))

(deftest a-call-that-does-not-run-is-named-in-the-process-log
  ;; `:outcome` lands on this line ONLY when the call did not simply pass, which is
  ;; what keeps 'why didn't my tool run' out of the jsonl-only drawer. An unknown
  ;; tool is the cheapest way to reach a non-:pass outcome; the gate outcomes
  ;; (hook-blocked, needs-approval) come through the same line.
  (with-server
   "diag-refused"
   [{:content "" :tool-calls [{:id "c1" :name "no-such-tool" :arguments {}}]}
    {:content "gave up"}]
   (fn []
     (post-run "diag-refused")
     (is (await-log #"tool-not-run .*outcome=unknown-tool .*thread-id=diag-refused")))))

(deftest a-stream-that-ends-without-a-terminal-frame-is-a-warning
  ;; The hole: a run whose event channel closes without a terminal frame leaves a
  ;; record that stops mid-sentence and a client waiting on frames that will never
  ;; come. A channel that arrives already closed is what every version of that looks
  ;; like from the edge -- a consumer that died, a producer that went with it -- and
  ;; saying so is the edge's job. Explaining it is not: this line is a pointer, not
  ;; a diagnosis.
  (with-server
   "diag-lost-stream"
   (fn []
     (with-redefs [loop/run-chan (fn [& _] (doto (async/chan) async/close!))]
       (let [sock (fire-run! "diag-lost-stream")]
         (try
           (is (await-log #"events-closed-without-terminal .*thread-id=diag-lost-stream"))
           (finally (.close sock))))))))

(deftest a-run-whose-own-body-throws-says-so-and-ends-the-stream
  ;; A go block's exception goes into the block's own channel, which nobody reads:
  ;; the run vanishes, the kernel's producer blocks on a put that will never be
  ;; taken, and the client is parked on a stream that will never send another frame.
  ;; THIS is the failure the wrapper exists for, and here the FRAME CONVERSION is
  ;; what breaks -- the first event kills the consumer.
  (with-server
   "diag-crashed"
   (fn []
     (with-redefs [ag/outbound (fn [& _] (fn [_] (throw (ex-info "no frames today" {}))))]
       (let [sock (fire-run! "diag-crashed")]
         (try
           (is (await-log #"crashed .*thread-id=diag-crashed") "the crash is in the log")
           (is (await-log #"no frames today") "with the throwable, not just a kind")
           (finally (.close sock))))))))


;; ------------------------------------ a history that leaves a call unanswered
;;
;; THE 400 THIS EXISTS FOR (thread b553ed1d, 2026-09-18). A model call asked for a
;; `write`; the seam parked it for approval; the run ended on that interrupt, so the
;; call - deliberately - has no tool message. The client then sent its next ordinary
;; run over the same conversation without resuming anything (the parked card was gone
;; after a refresh), and the request went out carrying an assistant message whose
;; tool_calls nothing answers. The vendor refused it before the model ran:
;;
;;   HTTP 400: An assistant message with 'tool_calls' must be followed by tool
;;   messages responding to each 'tool_call_id'. (insufficient tool messages
;;   following tool_calls message)
;;
;; - and it kept refusing it: the client's history is the client's, so every later
;; message of that session re-sent the same block and died the same way. The
;; conversation was bricked, and nothing in the answer named the call or the park.

(defn- with-vendor-shaped
  "Like `with-server`, but the pinned provider IS the vendor's validator: it refuses a
  request whose assistant message with tool_calls is not answered adjacently, with a
  real gateway's own 400 (see the section at the end of harness.test-support). A test
  about that shape needs a provider that would die on it, not a fake that would happily
  answer."
  [thread f]
  (start-session! thread)
  (providers/use-provider! thread (assoc (fake/scripted [{:content "done"}])
                                         :protocol :vendor-shaped))
  (let [stop (http/start! {:port 0})]
    (try (binding [*port* (:local-port (meta stop))] (f))
         (finally (stop) (providers/use-provider! thread nil)))))

(defn- parked-history
  "The client's message list for a run that carries a call NOTHING answered - the shape
  the record above holds. The model asked for a tool, the seam parked it, the run ended
  on the interrupt, and the tool message therefore never landed; what the client re-sends
  is exactly this list plus whatever the human typed next."
  [call-id]
  [{:id "u1" :role "user" :content "run it"}
   {:id "a1" :role "assistant" :content ""
    :toolCalls [{:id call-id :type "function"
                 :function {:name "read" :arguments "{\"path\": \"deps.edn\"}"}}]}
   {:id "u2" :role "user" :content "and this"}])

(defn- terminal-frame [frames]
  (last (filter #(= "RUN_FINISHED" (:type %)) frames)))

(deftest a-parked-call-the-run-is-not-resuming-is-asked-again-not-sent
  (with-vendor-shaped
   "park-ask"
   (fn []
     (tools/park-approval! "int-park-ask"
                           {:thread-id "park-ask" :tool-call-id "c1" :name "read"
                            :args "{}" :reason "tool-approval"})
     (let [resp   (post-run "park-ask" {:append (parked-history "c1")})
           frames (wire/frames-from-sse (.body resp))
           fin    (terminal-frame frames)]
       (testing "the vendor is never asked a question it refuses before the model runs"
         (is (= 200 (.statusCode resp)))
         (is (not-any? #(= "RUN_ERROR" (:type %)) frames)
             (str "saw " (pr-str (mapv :type frames))))
         (is (seq frames)))
       (testing "the run ends on the SAME interrupt, so the client can answer it"
         (is (= "interrupt" (get-in fin [:outcome :type])))
         (is (= ["int-park-ask"] (mapv :id (get-in fin [:outcome :interrupts]))))
         (is (= ["c1"] (mapv :toolCallId (get-in fin [:outcome :interrupts])))))))))

(deftest a-call-nobody-can-answer-is-refused-by-name
  (testing "no park in this process holds it, so no decision can ever arrive for it"
    (with-vendor-shaped
     "park-dead"
     (fn []
       (let [resp   (post-run "park-dead" {:append (parked-history "call_dead1")})
             frames (wire/frames-from-sse (.body resp))
             err    (last (filter #(= "RUN_ERROR" (:type %)) frames))]
         (is (= 200 (.statusCode resp)))
         (is (some? err) (str "saw " (pr-str (mapv :type frames))))
         (testing "the sentence names the call and the shape -- it does not relay the vendor"
           (is (str/includes? (str (:message err)) "call_dead1"))
           (is (not (str/includes? (str (:message err)) "insufficient tool messages"))))
         (testing "and nothing was sent to the scripted model -- it never got a turn"
           (is (not-any? #(= "TEXT_MESSAGE_CONTENT" (:type %)) frames))))))))


;; ------------------------------------ a run that is alive is a fact, not a file
;;
;; A RECORD CANNOT SAY WHETHER IT IS STILL BEING WRITTEN. An input line with no
;; terminal frame after it is a run that is still going OR a process that was killed
;; mid-flight -- the two leave the same file, and every reader that has had to choose
;; between them (the sidebar row, the composer's gate, the read side deciding whether
;; it may close a record off) used to be guessing. `harness.edge.http/live-runs` is
;; the process's own answer, and the three cases below are the three ways a run can
;; end: an ordinary one, one that never started, and one that died in its own body.
;;
;; EVERY CASE HERE HOLDS THE RUN OPEN ON PURPOSE. `loop/run-chan` is called after the
;; registration and before any frame, so wrapping it (harness.test-support/window-gate)
;; gives the test a window in which the run is certainly alive -- which is what makes
;; "the row says running" an assertion rather than a race against a fast script.

(def ^:private alive-dir  (support/temp-dir "http-alive"))
(def ^:private refused-dir (support/temp-dir "http-refused"))
(def ^:private crashed-dir (support/temp-dir "http-crashed"))
(def ^:private lost-dir    (support/temp-dir "http-lost"))
(def ^:private rebuild-dir (support/temp-dir "http-rebuild"))

(defn- session-row-of
  "The row GET /api/projects gives for TID, under the project whose canonical path is
  DIR -- nil when this home has no such project, or no such session in it.

  ASKED THE WAY THE SIDEBAR ASKS IT: one GET, the whole listing, no thread endpoint of
  its own. The row is the client's whole view of the fact, so a case that read the
  registry directly would be testing something no client can see."
  [dir tid]
  (let [want (.getCanonicalPath (io/file dir))]
    (some (fn [project]
            (when (= want (:path project))
              (first (filter #(= tid (:threadId %)) (:sessions project)))))
          (listed-projects))))

(defn- row-running?
  [dir tid]
  (boolean (:running (session-row-of dir tid))))

(defn- bind!
  "Bind TID to DIR through the ordinary route, asserting it worked."
  [tid dir]
  (is (= 200 (.statusCode (api-call :post "/api/project"
                                    (json/write-str {:threadId tid :dir dir}))))
      (str "binding " tid " to " dir)))

(deftest a-run-that-is-alive-says-so-and-stops-when-it-ends
  (wipe-dir! alive-dir)
  (with-server
   "alive-a"
   (fn []
     (bind! "alive-a" alive-dir)
     (let [gate (support/window-gate #'loop/run-chan 20000)
           sock (fire-run! "alive-a")]
       (try
         (testing "while the run is being answered, the session's own row says so"
           (is (until #(row-running? alive-dir "alive-a") 5000)
               "the row never said running while the run was held")
           (is (await-log #"start .*thread-id=alive-a")
               "and the registry was told before the line that witnesses it"))
         (finally (.close sock) ((:release gate)))))
     (testing "and the moment the run reaches its terminal frame, it stops"
       (is (until #(false? (row-running? alive-dir "alive-a")) 5000)
           "the row still said running after the run had ended")
       (is (await-log #"terminal event=RUN_FINISHED .*thread-id=alive-a"))))))

(deftest a-rebuild-of-a-running-session-says-it-is-running
  ;; THE DOOR A CLIENT CAME THROUGH DECIDES WHETHER IT FOLLOWS THE RUN. `rebuild` hands
  ;; over a SNAPSHOT and opens no feed, so a page that opened a RUNNING session from the
  ;; sidebar had no server word to close the composer's gate with -- and its Send was
  ;; answered by the run edge's 409 (`refuse-second-run!`). The state on the answer is
  ;; what lets the client LOOK through the window instead, so it is asserted here, in the
  ;; same held-open window the row cases use.
  (wipe-dir! rebuild-dir)
  (with-server
   "alive-rebuild"
   (fn []
     (bind! "alive-rebuild" rebuild-dir)
     (let [gate (support/window-gate #'loop/run-chan 20000)
           sock (fire-run! "alive-rebuild")]
       (try
         (testing "a rebuild of a conversation this process is answering names the live run"
           (is (until #(row-running? rebuild-dir "alive-rebuild") 5000)
               "the run was never held open")
           (is (= "running"
                  (:state (read-json (api-call :post
                                             "/api/threads/alive-rebuild/rebuild" nil))))
               "the rebuild snapshot did not name the run that was going"))
         (finally (.close sock) ((:release gate)))))
     (testing "and once the run has ended the same door says the conversation settled"
       (is (until #(false? (row-running? rebuild-dir "alive-rebuild")) 5000)
           "the run never ended")
       (is (= "settled"
              (:state (read-json (api-call :post
                                         "/api/threads/alive-rebuild/rebuild" nil))))
           "a finished conversation was still named running")))))

(deftest a-task-s-row-answers-the-same-registry-question
  ;; The third fact a row carries, for the second kind of row: `:running` comes from
  ;; THIS PROCESS's registry, not from disk and not from the kind of conversation it is.
  ;; A task has no project to ask, so a row that answered "not running" for the want of
  ;; one would leave the spinner off exactly the sessions the page just made.
  ;;
  ;; THE WINDOW IS HELD OPEN ON PURPOSE -- same reason as the bound-session cases below
  ;; (see `window-gate`): `loop/run-chan` is called after the thread is registered, so
  ;; holding there makes "the row says running" an assertion rather than a race against a
  ;; fast script.
  (with-server
   "task-alive"
   (fn []
     (register-session! "task-alive")
     (let [gate (support/window-gate #'loop/run-chan 20000)
           sock (fire-run! "task-alive")]
       (try
         (testing "a task being answered right now says so on its own row"
           (is (until #(true? (:running (listed-row "task-alive"))) 5000)
               "the task's row never said running while its run was held"))
         (finally (.close sock) ((:release gate)))))
     (testing "and it stops when the run reaches its terminal frame"
       (is (until #(false? (:running (listed-row "task-alive"))) 5000)
           "the task's row still said running after the run had ended")))))


(deftest a-run-that-never-started-is-never-running
  ;; WHERE THE BAD PROVIDER COMES FROM CHANGED WITH TICKET 03, and the case moved with
  ;; it: a run cannot name a provider any more (the selection is the SESSION's, changed
  ;; by its own actions), so the unresolvable name is planted in the home's default
  ;; tier -- the same tier a session's selection is merged onto. What is under test is
  ;; unchanged and is why the case exists: a run that never got as far as a provider
  ;; must never be in the registry, and must answer with the refusal rather than a
  ;; finished run. A HOME OF ITS OWN, because writing config.edn is how it is planted.
  (wipe-dir! refused-dir)
  (support/with-temp-env [root _home]
   (spit (io/file root "config.edn")
         "{:default {:provider \"no-such-provider\"}}\n"
         :encoding "UTF-8")
   (with-server
    ;; THIS THREAD IS PINNED AND THE ONE UNDER TEST IS NOT, deliberately: a pin wins
    ;; provider resolution outright, and this case needs resolution to FAIL.
    "refused-other"
    (fn []
     (bind! "refused-a" refused-dir)
     (let [gate (support/window-gate #'providers/resolve-provider 20000)
           resp (future (post-run "refused-a"))]
       (try
         (testing "a run held inside setup -- before it has a provider -- is not running"
           (is (until #(pos? (long ((:entered gate)))) 5000)
               "the run never reached provider resolution")
           ;; THIS IS THE ASSERTION THAT MATTERS, and it is why the window is held: a
           ;; registration made above the refusal would be visible exactly here, and
           ;; nothing would ever remove it -- the refusal path emits its two frames and
           ;; returns without passing the emitter, so that session would claim to be
           ;; running until the process restarted.
           (is (false? (row-running? refused-dir "refused-a"))
               "a run that has not started is in the registry"))
         (finally ((:release gate))))
       (let [frames (wire/frames-from-sse (.body ^HttpResponse @resp))]
         (testing "the run is refused by name, before any model call"
           (is (= ["RUN_STARTED" "RUN_ERROR"] (mapv :type frames))
               (str "saw " (pr-str (mapv :type frames))))
           (is (str/includes? (str (:message (last frames))) "no provider named")))
         (testing "and the run that never started never appears in the registry"
           (is (false? (row-running? refused-dir "refused-a"))))))))))

(deftest a-crashed-run-stops-claiming-to-be-running
  (wipe-dir! crashed-dir)
  (with-server
   "crashed-a"
   (fn []
     (bind! "crashed-a" crashed-dir)
     ;; THE REDEF IS IN PLACE FOR THE WHOLE CASE, not just around `fire-run!`: the
     ;; frame conversion happens on the server's own thread, whenever it gets there,
     ;; and a redef that had already been lifted would leave this case racing to
     ;; crash. Same seam as the `diag-crashed` case above -- the first frame kills the
     ;; consumer.
     (with-redefs [ag/outbound (fn [& _] (fn [_] (throw (ex-info "no frames today" {}))))]
       (let [gate (support/window-gate #'loop/run-chan 20000)
             sock (fire-run! "crashed-a")]
         (try
           (testing "the run is registered while it is alive"
             ;; ASSERTED BEFORE THE CRASH ON PURPOSE: "false afterwards" would also
             ;; pass for a run that had never registered at all, which is the failure
             ;; the other half of this case pins.
             (is (until #(row-running? crashed-dir "crashed-a") 5000)
                 "the row never said running, so the run never registered"))
           (finally (.close sock) ((:release gate)))))
       (testing "and a death in its own body takes the registration with it"
         (is (await-log #"crashed .*thread-id=crashed-a") "the death is in the log")
         (is (until #(false? (row-running? crashed-dir "crashed-a")) 5000)
             "the thread still claims to be running after its run died"))))))


(deftest a-run-whose-channel-closed-without-a-terminal-still-stops-running
  ;; THE THIRD WAY OUT. A run that registers and then finds its event channel
  ;; already closed never emits a terminal frame and never throws -- the go loop's own
  ;; else branch is the only thing that runs -- so this is the exit neither the
  ;; emitter nor the catch covers. Left unregistered, the thread would say it was
  ;; running for the life of the process; that is the failure this pins, and it is
  ;; asserted AFTER the warning line, because `await-log` is what proves the branch
  ;; was reached rather than the run having crashed on its way somewhere else.
  (wipe-dir! lost-dir)
  (with-server
   "lost-a"
   (fn []
     (bind! "lost-a" lost-dir)
     (with-redefs [loop/run-chan (fn [& _] (doto (async/chan) async/close!))]
       (let [sock (fire-run! "lost-a")]
         (try
           (is (await-log #"events-closed-without-terminal .*thread-id=lost-a")
               "the run's ending left a warning rather than a terminal frame")
           (is (until #(false? (row-running? lost-dir "lost-a")) 5000)
               "and the registration it made before that went with it")
           (finally (.close sock))))))))


;; ------------------------ reading a conversation that has not stopped yet
;;
;; `rebuild` HANDS A CONVERSATION OVER, and that is why it cannot be used to LOOK at
;; one: it closes off a log that ends mid-run (a write), and it refuses a log whose run
;; has not ended at all. A client that has just landed on a session -- a refresh, where
;; the run belongs to the process and not to the tab -- needs the other thing: what has
;; been recorded so far, said to be partial, with nothing touched. That is the `sofar`
;; verb, and the four cases below are its edges: a run in flight, a run that settled, a
;; log that was cut off, and a rebuild aimed at a live run (which must write nothing).
;;
;; THE LIVE CASES HOLD THE RUN AT THE TOOL SEAM (`tools/run!`). That is the half-written
;; state worth reading: an assistant message carrying a call whose result has not been
;; written yet. Holding it before the run starts would make every assertion about an
;; empty log, which is the shape of test that passes while the feature is broken.

(def ^:private sofar-dir  (support/temp-dir "http-sofar"))
(def ^:private sofar-dir-2 (support/temp-dir "http-sofar-2"))
(def ^:private parked-dir  (support/temp-dir "http-sofar-parked"))

(defn- sofar
  "GET /api/threads/<TID>/sofar -- the raw answer, so a case can assert the status too."
  [tid]
  (api-call :get (str "/api/threads/" tid "/sofar") nil))

(defn- log-records
  "TID's log parsed AS IT IS RIGHT NOW -- no waiting, no retrying.

  This section is about reading a file that is still being written, so the caller says
  what it expects to already be true (`until`) and then looks; a helper that polled
  would hide exactly the race these cases are about. A trailing half-written line is
  dropped rather than failed on: the file may be mid-append, which is a fact about
  reading a live log and not a corrupt log."
  [tid]
  ;; THROUGH THE RECORD'S OWN READER (`.scratch/jsonl-two-kinds` 票 02): a row is
  ;; `{:type .. :payload ..}`; reading it is what validates it and hands back the row
  ;; itself, and the assertions here ask `replay/kind` what that row is. The `try` is for
  ;; the half-written last line.
  (->> (str/split-lines (slurp (log-file-for tid) :encoding "UTF-8"))
       (keep (fn [line] (try (first (replay/lines->records [line])) (catch Throwable _ nil))))
       (vec)))

(defn- log-frames [tid]
  (mapv replay/payload (filter #(= "event" (replay/kind %)) (log-records tid))))

(defn- terminals [tid]
  (filterv frames/terminal? (log-frames tid)))

;; ------------------------ stopping a run somebody asked to stop
;;
;; Tickets 07 and 08 of `.scratch/session-after-refresh`. The Stop in the composer used to
;; be the browser closing its OWN fetch: the interface looked stopped and the server ran
;; on -- the run kept writing frames into the record. These cases are the server's half,
;; which is where the stop had to be built: the route, the terminal that does not lie
;; about it, and the command that dies with the run it belonged to.
;;
;; EACH CASE WAITS FOR THE FACT IT NEEDS TO BE TRUE FIRST, never for a clock: the first
;; holds its run at `loop/run-chan` (which the edge calls AFTER the thread is
;; registered), and the command cases wait for the command to have written its own pid
;; down -- the record's own rule, one level down: a command's answer is read from a FILE.

(def ^:private stopped-dir   (support/temp-dir "http-stopped"))
(def ^:private stopped-dir-2 (support/temp-dir "http-stopped-2"))

(defn- cancel!
  "POST /api/threads/<tid>/cancel -- what the composer's Stop sends."
  [tid]
  (api-call :post (str "/api/threads/" tid "/cancel") nil))

(deftest a-stop-reaches-the-run-this-process-has-going
  (wipe-dir! stopped-dir)
  (with-server
   "stop-1"
   (fn []
     (bind! "stop-1" stopped-dir)
     (let [gate (support/window-gate #'loop/run-chan 20000)
           sock (fire-run! "stop-1")]
       (try
         (is (until #(row-running? stopped-dir "stop-1") 5000)
             "the run was never held open")
         (testing "the route rings the switch and names the run it rang"
           (let [resp (cancel! "stop-1")]
             (is (= 200 (.statusCode resp))
                 (str "the stop was refused: " (.body resp)))
             (let [body (read-json resp)]
               (is (= "stop-1" (:threadId body)))
               (is (string? (:runId body)) "the answer does not name the run it stopped"))))
         (finally (.close sock) ((:release gate))))
     (testing "and the run ends on a terminal that says a person stopped it"
       (is (until #(false? (row-running? stopped-dir "stop-1")) 5000)
           "the row still said running after the stop")
       (is (await-log #"terminal event=RUN_ERROR .*thread-id=stop-1"))
       (is (str/includes? (process-log) "a person pressed stop")
           "the process log's terminal reason does not say who stopped it")
       (is (str/includes? (str (:message (peek (terminals "stop-1"))))
                          "a person pressed stop")
           "the record's own terminal does not say it either"))
     (testing "and the conversation is usable again -- a stop is not a lock"
       (is (str/includes? (.body (post-run "stop-1")) "RUN_FINISHED")))
     (testing "a conversation no run is going in is refused BY NAME"
       (let [resp (cancel! "stop-1")]
         (is (= 409 (.statusCode resp)) (str "got " (.statusCode resp)))
         (is (str/includes? (str (:error (read-json resp))) "nothing to cancel"))))))))


(deftest a-stop-does-not-wait-for-a-vendor-that-is-still-talking
  (wipe-dir! stopped-dir)
  (with-server
   "stop-vendor"
   (fn []
     (bind! "stop-vendor" stopped-dir)
     ;; THE VENDOR IS HELD MID-CALL. A model call has NO PROCESS TO KILL (`harness.kernel.loop`'s
     ;; own note), so the only correct stop is to ABANDON the wait -- and the terminal must
     ;; arrive anyway, not wait for a request that is no longer wanted.
     (let [gate (support/window-gate #'llm/stream! 20000)
           sock (fire-run! "stop-vendor")]
       (try
         (is (until #(pos? ((:entered gate))) 5000) "the run never reached the vendor")
         (is (until #(row-running? stopped-dir "stop-vendor") 5000)
             "the run was never held open on the vendor")
         (is (= 200 (.statusCode (cancel! "stop-vendor"))))
         ;; WHILE THE VENDOR IS STILL HELD: the run ends without waiting for it.
         (is (until #(false? (row-running? stopped-dir "stop-vendor")) 5000)
             "the stop waited for a vendor that was still talking")
         (is (await-log #"terminal event=RUN_ERROR reason=this run was stopped: a person pressed stop .*thread-id=stop-vendor"))
         (finally ((:release gate)) ((:restore gate)) (.close sock)))
       (testing "and the conversation is usable again"
         (is (str/includes? (.body (post-run "stop-vendor")) "RUN_FINISHED")))))))
(defn- command-turn
  "One scripted turn whose single call runs a command that writes ITS OWN PID to FILE
  and then hangs -- the shape somebody presses Stop in front of.

  THE COMMAND IS `harness.test-support/child-command`'S, and that is the whole point rather
  than a detail: it is the one spelling of 'a shell with a live child, and that child's OS
  pid on disk' that holds on Windows. `echo $$ > <a Windows path>` failed there twice over --
  `$$` is MSYS's OWN number, which `ProcessHandle/of` cannot resolve (so `alive?` answers
  false about a command that is running: see `child-command`'s note), and the backslashes in
  the path are eaten on the way through bash, so the file was never written at all. The case
  read both as 'the scripted commands never started'."
  [file]
  [{:content ""
    :tool-calls [{:id "c1" :name "bash"
                  :arguments {:command (support/child-command (io/file file))}}]}
   {:content "done"}])

(defn- pid-in
  "The pid the command wrote down, or nil while the file is not there yet."
  [file]
  (when (.exists (io/file file))
    (let [s (str/trim (slurp file :encoding "UTF-8"))]
      (when (re-matches #"\d+" s) (Long/parseLong s)))))

(defn- alive?
  "Is the process PID still there? Asked of the OS, which is the whole point of the
  assertion: 'the call returned' and 'the command's tree is gone' are different facts."
  [pid]
  (and (some? pid)
       (boolean (some-> (java.lang.ProcessHandle/of pid) (.orElse nil) (.isAlive)))))

(deftest a-stop-kills-the-command-the-run-was-waiting-on
  (wipe-dir! stopped-dir-2)
  (let [pid-a (str (io/file stopped-dir-2 "command-a.pid"))
        pid-b (str (io/file stopped-dir-2 "command-b.pid"))]
    (with-server
     {"kill-a" (command-turn pid-a) "kill-b" (command-turn pid-b)}
     (fn []
       (bind! "kill-a" stopped-dir-2)
       (bind! "kill-b" stopped-dir-2)
       (let [a (future (post-run "kill-a"))
             b (future (post-run "kill-b"))]
         (try
           (testing "both commands are really running"
             (is (until #(and (alive? (pid-in pid-a)) (alive? (pid-in pid-b))) 15000)
                 "the scripted commands never started"))
           (testing "the stop kills ITS command and leaves the other session's alone"
             (is (= 200 (.statusCode (cancel! "kill-a"))))
             (is (until #(not (alive? (pid-in pid-a))) 15000)
                 "the stopped run's command is still alive")
             (is (alive? (pid-in pid-b))
                 "stopping one session killed another session's command"))
           (testing "and the record keeps no open call for it"
             (let [answer (deref a 20000 nil)]
               (is (some? answer) "the stopped run's stream never ended")
               (is (str/includes? (str (.body answer)) "a person pressed stop"))
               ;; AND THE CUT-OFF ANSWER IS THE RECORD'S ALONE: the live client must see the call
               ;; as cancelled, not as one that returned (see
               ;; `harness.kernel.event/cut-off-result`).
               (is (not (str/includes? (str (.body answer)) "no result was recorded"))
                   "the cut-off result was sent on the wire, so the live page would draw it Done"))
             (drained!)
             (let [frames   (log-frames "kill-a")
                   starts   (filter #(= "TOOL_CALL_START" (:type %)) frames)
                   results  (filter #(= "TOOL_CALL_RESULT" (:type %)) frames)]
               (is (= (count starts) (count results))
                   "a tool call was left without a result")
               (is (str/includes? (str (:content (first results))) "cut off")
                   "the abandoned call's result is not the cut-off sentence")))
           (finally
             ;; THE OTHER SESSION IS STOPPED TOO, so nothing is left running: the case
             ;; above is about ONE run's command, not about being able to walk away.
             (cancel! "kill-b")
             (is (until #(not (alive? (pid-in pid-b))) 15000)
                 "the second command outlived its own stop")
             (deref b 20000 nil))))))))

;; ------------------------ one session, one run at a time
;;
;; The door standing in front of the run edge. TWO RUNS OF ONE THREAD INTERLEAVE their
;; frames into one append-only file, and everything downstream is written for one run at
;; a time (`replay/ensure-complete!` counts inputs against terminals, `open-run` takes the
;; LAST of each, the fold reads the input lines in file order) -- so the second run is refused
;; BY NAME before it can register, log or start. What must NOT be refused is a second
;; SESSION: `.scratch/parallel-sessions/` is built on two threads genuinely running at
;; once, and that feature's docs ticket asks for the case below and says so in one place.

(deftest a-session-answers-one-run-at-a-time-and-two-sessions-still-both-run
  ;; THE REFUSAL AND THE PERMISSION ARE ONE CASE, deliberately: "two requests for one
  ;; session are not both answered" and "two requests for two sessions are both answered"
  ;; are the same assertion about the same server at the same moment. Split into two
  ;; cases, a GLOBAL lock would pass both.
  ;;
  ;; BOTH RUNS ARE HELD AT `loop/run-chan`, which is called AFTER the thread is
  ;; registered -- so "both are alive right now" is a state this case establishes rather
  ;; than one it races a fast script for. The gate holds the first two calls and lets
  ;; every later one through, which is what the re-send at the end needs.
  (with-server
   ["gate-a" "gate-b"]
   [{:content "the first run's answer"} {:content ""}]
   (fn []
     (let [gate (support/window-gate #'loop/run-chan 20000 2)
           a    (fire-run! "gate-a")
           b    (fire-run! "gate-b")]
       (try
         (testing "two sessions are two runs, both alive at once"
           (is (until #(and (http/running? "gate-a") (http/running? "gate-b")) 5000)
               "the second session's run never started -- a gate that refuses THIS is a
                global lock, and every parallel-session case in the repo is built on it"))
         (testing "the second run asked for one of them is refused by name"
           (let [refused (post-run "gate-a")]
             (is (= 409 (.statusCode refused)) (str "got " (.statusCode refused)))
             (let [body (read-json refused)]
               (is (str/includes? (str (:error body)) "gate-a")
                   (str "the refusal does not name the session: " (pr-str (:error body))))
               (is (str/includes? (str (:error body)) "one run per session")
                   "and does not say what the rule is")
               (is (= "gate-a" (:threadId body))))))
         (finally
           (.close a)
           (.close b)
           ((:release gate))
           ((:restore gate)))))
     (testing "the refused run wrote nothing at all -- not even its input line"
       ;; ON THE FILE, not on the response: a run that is refused but logs an input is
       ;; refused in the answer and started anyway, which is the bug this pins.
       (is (= 1 (count (client-rows (log-lines-for "gate-a"))))
           "the refused run left no row of its own in gate-a's record"))
     (testing "both runs that were let through reach their own terminal"
       (is (until #(and (not (http/running? "gate-a")) (not (http/running? "gate-b"))) 5000))
       (is (= 1 (count (terminals "gate-a"))) "gate-a has one run's ending")
       (is (= 1 (count (terminals "gate-b"))) "gate-b has one run's ending")
       ;; THE OTHER SESSION'S RUN ID, read out of ITS record rather than named by this
       ;; case: the server mints run ids now (ticket 03), so the only way to ask "did
       ;; gate-b's run bleed into gate-a's file" is to look up what gate-b's run is
       ;; called. A leaked frame would carry it.
       (let [b-run (->> (log-lines-for "gate-b")
                        client-rows
                        first
                        :runId)]
         (is (string? b-run) "gate-b's run has no name on the record")
         (is (not (str/includes? (slurp (log-file-for "gate-a") :encoding "UTF-8") (str b-run)))
             "the other session's run leaked into gate-a's record")))
     (testing "and the session that was refused can run again once its run has ended"
       (let [again (post-run "gate-a")]
         (is (= 200 (.statusCode again)) (str "got " (.statusCode again)))
         (is (str/includes? (.body again) "RUN_FINISHED")
             "the second run of gate-a did not finish"))))))

(deftest a-crashed-run-does-not-close-its-session-for-good
  ;; THE DOOR IS ONLY AS GOOD AS ITS UNREGISTRATION. A gate keyed on "this process has a
  ;; run going" turns a leaked registration into a session nobody can ever run again --
  ;; the sidebar would spin forever and the only way out would be a restart. This is the
  ;; same exit `a-crashed-run-stops-claiming-to-be-running` pins from the other side.
  (with-server
   "crash-gate"
   [{:content "after the crash"}]
   (fn []
     ;; In place for the whole crash, not just around `fire-run!`: the frame conversion
     ;; happens on the server's own thread (same seam as the case above).
     (with-redefs [ag/outbound (fn [& _] (fn [_] (throw (ex-info "no frames today" {}))))]
       ;; THE RUN IS HELD FIRST, and that is not decoration: the crash lands four
       ;; milliseconds after the registration, so a case that merely waits for `running?`
       ;; is racing the death it is about to assert. Holding at `loop/run-chan` -- called
       ;; after the registration and before the first frame is converted -- makes "it
       ;; registered" a state rather than a coincidence; the release is what kills it.
       (let [gate (support/window-gate #'loop/run-chan 20000)
             sock (fire-run! "crash-gate")]
         (try
           (is (until #(http/running? "crash-gate") 5000)
               "the run never registered, so this case cannot tell anything")
           (finally (.close sock) ((:release gate))))))
     (is (await-log #"crashed .*thread-id=crash-gate") "the death is in the process log")
     (is (until #(not (http/running? "crash-gate")) 5000)
         "the thread still claims to be running after its run died")
     (testing "so the door opens again"
       (is (= 200 (.statusCode (post-run "crash-gate")))
           "the session was locked out by a run that crashed")))))

(deftest an-old-record-is-refused-by-name-over-http
  ;; 拍定 3 OF `.scratch/jsonl-two-kinds`: an old-contract record is NOT migrated, not read
  ;; leniently, and not quietly made to work -- the sentence a person meets sends them to a
  ;; new conversation. The READER's refusal is pinned in `replay_test`; what is pinned HERE is
  ;; that a client meets it as an ANSWER (`400` and a reason) rather than as a 500, an empty
  ;; conversation, or a repair that rewrites a file this build cannot honestly fold.
  (let [tid  (str "old-contract-" (java.util.UUID/randomUUID))
        f    (log-file tid)
        body (str (json/write-str {:ts 1 :runId "r1" :kind "input"
                                   :payload {:threadId tid
                                             :append [{:id "u1" :role "user" :content "hi"}]}})
                  "\n")]
    (.mkdirs (.getParentFile f))
    (spit f body :encoding "UTF-8")
    (with-server
     "old-contract-read"
     (fn []
       (testing "reading it is a 400 that names the contract and says what to do"
         (let [resp (api-call :get (str "/api/threads/" tid "/page") nil)
               reply (read-json resp)]
           (is (= 400 (.statusCode resp)))
           (is (str/includes? (str (:error reply)) "old contract"))
           (is (str/includes? (str (:error reply)) "start a new conversation"))))
       (testing "and the rebuild door refuses the same file for the same reason"
         (let [resp (api-call :post (str "/api/threads/" tid "/rebuild") nil)
               reply (read-json resp)]
           (is (= 400 (.statusCode resp)))
           (is (str/includes? (str (:error reply)) "old contract"))))
       (testing "and the file is left EXACTLY as it was: unread, unmoved, unmigrated"
         (is (= body (slurp f :encoding "UTF-8"))))))))

(defn- write-truncated-log!
  "A record that stops mid-run, written by hand: an input, the run's start, and nothing
  else. HAND-WRITTEN RATHER THAN PRODUCED BY A REAL KILL because the case is about what
  a READER does with that shape -- a test that spawned a process in order to kill it
  would be testing its own timing, and this is the same bytes either way."
  [tid run-id]
  (let [f (log-file tid)]
    (io/delete-file f true)
    (.mkdirs (.getParentFile f))
    (let [line! (fn [kind payload & [extra]]
                  (spit f (str (row-json (merge {:ts (System/currentTimeMillis)
                                                 :runId run-id :kind kind :payload payload}
                                                extra))
                               "\n")
                        :append true :encoding "UTF-8"))]
      ;; THE SHAPE THE EDGE WRITES (票 02): the run's own first message row -- the prompt it
      ;; assembled -- and then a `message` row per entry the action brought. The `input` row
      ;; that used to carry the request as it arrived is gone; what ENTERED the conversation
      ;; is the rows themselves, which is why the fold reads them and not a field.
      (line! "message" {:role "system" :content "S"} {:source "system-prompt" :hash "h1"})
      (line! "message" {:role "user" :content "看看这个项目"} {:source "client" :id "u1"})
      (doseq [frame ((ag/outbound tid run-id) (ev/run-start))]
        (line! "event" frame)))))

(deftest a-running-session-reads-what-has-arrived-and-nothing-is-written
  (wipe-dir! sofar-dir)
  (with-server
   "sofar-a"
   (fn []
     (bind! "sofar-a" sofar-dir)
     (let [gate (support/window-gate #'tools/run! 20000)
           sock (fire-run! "sofar-a")]
       (try
         ;; WAIT FOR THE TOOL SEAM TO BE REACHED, not merely for the file to have
         ;; something in it: `seq` is satisfied by RUN_STARTED alone, and a read taken
         ;; then races the model call that is still streaming its frames out. Entered
         ;; gate + a recorded TOOL_CALL_START together say 'the turn is on the record and
         ;; the call is now held', which is the state this case is about -- and the file
         ;; stops growing exactly there, which is what the byte/mtime assertion needs.
         (is (until #(and (pos? (long ((:entered gate))))
                          (some (fn [f] (= "TOOL_CALL_START" (:type f))) (log-frames "sofar-a")))
                    5000)
             "the model's turn is on the record and its call is held at the seam")
         (let [resp   (sofar "sofar-a")
               answer (read-json resp)]
           (testing "the read says a run is in flight here, and names it"
             (is (= 200 (.statusCode resp)))
             (is (= "running" (:state answer)))
             ;; THE ID IS THE SERVER'S NOW (ticket 03 mints it), so the assertion reads
             ;; the name out of the record instead of naming one: what matters is that
             ;; the two doors -- this read and the sidebar row below -- are answering
             ;; about the SAME run, and the record is where the name is written down.
             (is (= [(->> (log-records "sofar-a") client-rows first :runId)]
                    (:openRuns answer))
                 "the read names the run the record says is open")
             (is (true? (:running (session-row-of sofar-dir "sofar-a")))
                 "and the sidebar row agrees with it: one registry, two doors"))
           (testing "and it returns the half turn as it stands"
             (let [calls (mapcat #(map :id (:toolCalls %)) (:messages answer))]
               (is (= ["c1" "c2"] (vec calls))
                   "the calls the model made a moment ago are readable")
               (is (not-any? #(= "tool" (:role %)) (:messages answer))
                   "no result has been written yet, and the read invents none")))
           (testing "and a window that arrives NOW sees the same half turn"
             ;; TICKET 04 OF `.scratch/session-opening`. `settle!` folds a run's frames at its
             ;; END ('half an answer is not a turn'), so a page that read the session table
             ;; mid-run used to show the question and none of the work -- a refresh that landed
             ;; on a long turn looked like an empty turn. The frames are on the RECORD the
             ;; moment they are written (`log!` is a per-frame append), so the window reads the
             ;; record while a run of this session is in flight, and this is that promise.
             (let [resp (api-call :get "/api/threads/sofar-a/page" nil)
                   body (read-json resp)]
               (is (= 200 (.statusCode resp)) "a window can be read while the run is going")
               (is (true? (:live body)) "and it says this process is serving the session")
               (is (= "running" (:state body))
                   "and that the turn on screen is still being written")
               (is (= (:messages answer) (mapv :message (:entries body)))
                   "the page carries every message `sofar` does -- one record, two doors")
               (testing "and the FEED opens on that same page"
                 (let [opened (feed-first-frame "sofar-a")]
                   (is (= "window" (:type opened)))
                   (is (= "running" (:state opened)))
                   (is (= (mapv :seq (:entries body)) (mapv :seq (:entries opened)))
                       "the stream and the one-shot read agree entry for entry")
                   (is (false? (:hasMore opened))
                       "and nothing older lies beyond this page: the turn is the start")))))
           (testing "NOTHING WAS WRITTEN for it: no terminal, no close-off"
             (is (empty? (terminals "sofar-a")))
             (is (not-any? #(= "session/closed-off" (replay/kind %)) (log-records "sofar-a")))))
         (testing "and polling it does not touch the file at all"
           ;; THE JUDGEMENT A POLLING CLIENT DEPENDS ON: bytes and mtime are the two
           ;; numbers a write cannot avoid moving.
           (let [f      (log-file-for "sofar-a")
                 before [(.length f) (.lastModified f)]]
             (dotimes [_ 10]
               (is (= 200 (.statusCode (sofar "sofar-a")))))
             (is (= before [(.length f) (.lastModified f)]))))
         (finally (.close sock) ((:release gate)))))
     (testing "once the run ends, the same log reads exactly as rebuild gives it"
       (is (until #(false? (row-running? sofar-dir "sofar-a")) 5000))
       (let [answer  (read-json (sofar "sofar-a"))
             rebuilt (read-json (api-call :post "/api/threads/sofar-a/rebuild" "{}"))]
         (is (= "settled" (:state answer)))
         (is (nil? (:openRuns answer)))
         (is (nil? (:interrupts answer)))
         (is (= (:messages rebuilt) (:messages answer))
             "two readers of one settled conversation must not diverge")
         (is (= (:context rebuilt) (:context answer))))
       (testing "and the window shows the conversation ONCE -- the half turn was not a copy"
         ;; THE OTHER HALF OF TICKET 04: mid-run the window answered from the RECORD, and at
         ;; settle it answers from the session table. Same entries, same ids -- so the switch
         ;; must not put a second copy of the turn on screen (the reader dedupes by id), and
         ;; this is the record read back through the window saying so.
         (let [resp    (api-call :get "/api/threads/sofar-a/page" nil)
               body    (read-json resp)
               ;; THE ID IS THE MESSAGE'S OWN (the table's entries are `{:seq .. :message ..}`
               ;; and the record's carry one too): it is what the reader dedupes by, which is
               ;; the whole reason the source changing mid-conversation is safe.
               ids     (mapv (comp :id :message) (:entries body))
               settled (read-json (sofar "sofar-a"))]
           (is (= 200 (.statusCode resp)))
           (is (= (count ids) (count (distinct ids)))
               "no entry appears twice after the source changed")
           (is (= (:messages settled) (mapv :message (:entries body)))
               "and the window says what `sofar` says about the settled conversation")))
       (testing "and the run kept exactly ONE terminal frame"
         (is (= 1 (count (terminals "sofar-a")))))))))

(deftest a-parked-session-reads-as-parked-and-says-what-it-waits-on
  ;; THE SECOND WAY A CONVERSATION IS UNFINISHED, and the one a client has to treat
  ;; differently from a run in flight: nothing is executing, a HUMAN is being waited on.
  ;; The record ends with its terminal -- a RUN_FINISHED carrying the interrupt -- which
  ;; is why the file alone can answer this one and the registry is not asked.
  (wipe-dir! parked-dir)
  (tools/session-require-approval! "sofar-parked" "write")
  (with-server
   "sofar-parked"
   [{:content "" :tool-calls [{:id "c1" :name "write"
                               :arguments {:path (str (io/file parked-dir "asked.txt"))
                                           :content "written"}}]}
    {:content "done"}]
   (fn []
     (bind! "sofar-parked" parked-dir)
     (let [asked (wire/frames-from-sse (.body (post-run "sofar-parked")))
           iid   (get-in (last asked) [:outcome :interrupts 0 :id])]
       (is (= "interrupt" (get-in (last asked) [:outcome :type])))
       (let [resp   (sofar "sofar-parked")
             answer (read-json resp)]
         (testing "the read says the conversation is parked, and on what"
           (is (= 200 (.statusCode resp)))
           (is (= "parked" (:state answer)))
           (is (= ["c1"] (mapv :toolCallId (:interrupts answer))))
           (is (= [iid] (mapv :id (:interrupts answer)))))
         (testing "the call it is waiting on is in the message list, unanswered"
           (is (some #(= "c1" (:id %)) (mapcat :toolCalls (:messages answer))))
           (is (not-any? #(= "c1" (:toolCallId %)) (:messages answer)))))
       (testing "and a parked run is not a running one -- nothing to wait for in the process"
         (is (false? (row-running? parked-dir "sofar-parked"))))))))

(deftest a-log-that-was-cut-off-is-refused-by-name-and-rebuild-still-closes-it
  (let [tid (str "sofar-dead-" (java.util.UUID/randomUUID))]
    (with-server
     tid
     (fn []
       ;; PLANTED HERE, not before the fixture: `with-server` starts every thread it
       ;; names from nothing -- row, conversation and record -- so a log written before
       ;; it would be the file the case is about to look for, deleted on the way in.
       (write-truncated-log! tid "r1")
       (let [resp   (sofar tid)
             answer (read-json resp)]
         (testing "the READ refuses a cut-off log by name, and points at the door that repairs it"
           (is (= 400 (.statusCode resp)))
           (is (str/includes? (str (:error answer)) "r1") "the run is named")
           (is (str/includes? (str (:error answer)) "rebuild") "and so is the way out"))
         (is (not-any? #(= "session/closed-off" (replay/kind %)) (log-records tid))
             "a refused read writes nothing -- it is not the repairing door"))
       (testing "rebuild still closes it off, exactly as it always did"
         (let [rebuilt (read-json (api-call :post (str "/api/threads/" tid "/rebuild") "{}"))]
           (is (seq (:messages rebuilt)))
           (is (some #(= "session/closed-off" (replay/kind %)) (log-records tid))
               "the repair is unconditional for a log nobody is writing"))
         (testing "and after that the same read returns it, settled"
           (let [resp   (sofar tid)
                 answer (read-json resp)]
             (is (= 200 (.statusCode resp)))
             (is (= "settled" (:state answer)))
             (is (= (:messages (read-json (api-call :post (str "/api/threads/" tid "/rebuild")
                                                    "{}")))
                    (:messages answer))))))))))

(deftest a-repair-that-arrived-behind-later-messages-still-answers-its-call
  ;; THE 2026-09-23 BRICKING (thread 412afbd3). A run was cut off with a tool call in
  ;; flight; the person kept typing before anything closed that run off; and so the
  ;; CLOSING REPAIR -- `close-off-open-run!`'s TOOL_CALL_RESULT -- lands at the END of the
  ;; file, behind those later messages. Folded in file order the call reads as UNANSWERED,
  ;; and the vendor refuses the rebuilt conversation: every later run of that session dies
  ;; with `unanswered .. call_..`, and there is nothing a human can press to get out.
  ;;
  ;; THE ANSWER IS ALREADY ON THE RECORD, so the run must USE it (moved behind the call it
  ;; answers), not invent one and not refuse. The provider pinned below IS the vendor's
  ;; validator (`with-vendor-shaped`), so a history the vendor would refuse cannot reach the
  ;; model at all -- a green here cannot be a fake that answers anything.
  (with-vendor-shaped
   "late-answer"
   (fn []
     (let [tid   "late-answer"
           f     (log-file tid)
           line! (fn [run-id kind payload & [extra]]
                   (spit f (str (row-json (merge {:ts (System/currentTimeMillis)
                                                  :runId run-id :kind kind :payload payload}
                                                 extra))
                                "\n")
                         :append true :encoding "UTF-8"))]
       (io/delete-file f true)
       (.mkdirs (.getParentFile f))
       ;; r1: the person's turn, and a call the run never returned from.
       (line! "r1" "message" {:role "user" :content "看看这个项目"} {:source "client" :id "u1"})
       (doseq [frame (mapcat (ag/outbound tid "r1")
                             [(ev/run-start) (ev/tool-call "c1" "read" "{}")])]
         (line! "r1" "event" frame))
       ;; r2: what the person typed NEXT, recorded while r1 was still open.
       (line! "r2" "message" {:role "user" :content "先别管那个"} {:source "client" :id "u2"})
       (line! "r2" "event" (ev/run-error "the next run was refused"))
       ;; AND NOW the repair, written the way the edge writes it: a rebuild closes the run
       ;; off, and its TOOL_CALL_RESULT is APPENDED -- behind u2.
       (is (= 200 (.statusCode (api-call :post (str "/api/threads/" tid "/rebuild") "{}"))))
       (testing "the record, folded as it lies, still leaves the dead call unanswered"
         (let [folded (replay/lines->messages
                       (str/split-lines (slurp f :encoding "UTF-8")))]
           (is (= ["c1"] (vec (llm/unanswered-tool-calls
                               (ag/inbound (sessions/model-view folded) "S" [])))))
           "the fold is in FILE order, and that order is what the run must not be refused for"))
       (let [resp   (post-run tid {:append [{:id "u3" :role "user" :content "继续"}]})
             frames (wire/frames-from-sse (.body resp))]
         (testing "yet the next run IS answered -- the recorded result is moved behind its call"
           (is (= 200 (.statusCode resp)))
           (is (not-any? #(= "RUN_ERROR" (:type %)) frames)
               (str "saw " (pr-str (mapv :type frames))))
           (is (some #(= "TEXT_MESSAGE_CONTENT" (:type %)) frames)
               "the model got its turn: the vendor accepted the history")))))))

(deftest a-rebuild-during-a-live-run-leaves-the-record-alone
  ;; THE CASE THAT MOTIVATED THE REGISTRY. A client that refreshed into a running
  ;; session has a fresh runtime, so its own `isRunning` is false and it may well aim
  ;; rebuild at the thread -- and rebuild used to close the run off from the file alone:
  ;; a TOOL_CALL_RESULT and a RUN_ERROR appended to a run that was still going, which
  ;; then wrote its own RUN_FINISHED. TWO terminals for one run, in a record whose only
  ;; asset is that every line of it is true.
  (wipe-dir! sofar-dir-2)
  (with-server
   "sofar-b"
   (fn []
     (bind! "sofar-b" sofar-dir-2)
     (let [gate (support/window-gate #'tools/run! 20000)
           sock (fire-run! "sofar-b")]
       (try
         (is (until #(and (pos? (long ((:entered gate))))
                          (some (fn [f] (= "TOOL_CALL_START" (:type f))) (log-frames "sofar-b")))
                    5000)
             "the run is under way: its call is recorded and held")
         (let [resp (api-call :post "/api/threads/sofar-b/rebuild" "{}")
              body (read-json resp)]
           (testing "the conversation is handed over FROM MEMORY, with the run left alone"
             ;; TICKET 05 CHANGED THE ANSWER HERE, NOT THE REASON. It used to be a 400
             ;; -- a rebuild refused a log that ends mid-run, and this log does -- but
             ;; the session is right here, so the conversation can be handed over
             ;; without touching the file at all. What the test is actually about is
             ;; the line below it: the run's record must not be closed off from under
             ;; it, because the run is still writing to it.
             (is (= 200 (.statusCode resp)))
             (is (seq (:messages body)) "the conversation so far")
             (is (= ["user"] (mapv :role (:messages body)))
                 "and it is what has SETTLED: the answer still being written is not a turn"))
           (testing "and NOTHING was appended to it"
             (is (not-any? #(= "session/closed-off" (replay/kind %)) (log-records "sofar-b")))
             (is (empty? (terminals "sofar-b")))))
         (finally (.close sock) ((:release gate)))))
     (testing "the run finishes on its own, with one terminal and no help from anybody"
       (is (until #(false? (row-running? sofar-dir-2 "sofar-b")) 5000))
       (is (= 1 (count (terminals "sofar-b"))))))))

(deftest a-run-names-the-session-once-and-only-once
  ;; The whole path of `sessions.title` over the real edge: the browser's action
  ;; arrives at POST /api/agent, the store gets a name from the SESSION's first user
  ;; turn, and the SIDEBAR's listing -- one GET, every session -- carries it back.
  ;;
  ;; THE NAME COMES FROM THE SESSION, NOT FROM THE REQUEST. ADR 0002 decision 9 retired
  ;; the run body's `messages`: an action carries only what it ADDS (`append`), so the
  ;; fortieth send holds the newest words and nothing earlier. A session that ran before
  ;; this column existed would then be named after its latest message instead of its
  ;; first -- a failure a case written only against the first send cannot see.
  ;;
  ;; The second run is the point of the test rather than a repetition of the first: a
  ;; conversation that has been named must keep that name when it says something else,
  ;; and the rule lives in the UPDATE (`COALESCE(title, ?)`), where a future edit that
  ;; 'refreshes' the title would be caught here.
  ;;
  ;; A PLAIN SCRIPT WITH NO TOOL CALLS, deliberately. This case turns the conversation
  ;; twice, and the default `script` answers its first turn with TWO tool calls in one
  ;; provider message -- which `ag/outbound` emits as two separate assistant messages,
  ;; so the conversation a second run continues from is not the shape a vendor accepts
  ;; (`llm/unanswered-tool-calls` sees `c1` with an assistant message behind it and
  ;; refuses). That is a defect of its own, unrelated to naming, and it would make this
  ;; case fail for a reason it is not about.
  (wipe-dir! task-dir)
  (with-server
   {"named-once" [{:content "这是一个 Clojure 项目。"}
                  {:content "测试用 `clojure -M:test` 跑。"}]
    "no-words"   [{:content "没有说话的一轮。"}]}
   (fn []
     (is (= 200 (.statusCode (register-session! "named-once"))))
     (testing "a session that has not run yet is unnamed"
       (is (nil? (:firstUserText (listed-row "named-once")))))
     (testing "the first send names it after the first thing said"
       (is (= "RUN_FINISHED"
              (:type (last (wire/frames-from-sse (.body (post-run "named-once")))))))
       (is (= "看看这个项目" (:firstUserText (listed-row "named-once")))))
     (testing "and a later send that says something else does not rename it"
       (is (= "RUN_FINISHED"
              (:type (last (wire/frames-from-sse
                            (.body (post-run "named-once"
                                             {:append [{:id "u2" :role "user"
                                                        :content "那它的测试怎么跑？"}]})))))))
       (is (= "看看这个项目" (:firstUserText (listed-row "named-once")))
           "the name is the session's first message, not its latest"))
     (testing "a run with no user turn stamps the time and stores no name"
       ;; The other half of `COALESCE(title, ?)`: a run DID happen, so `last_sent_at`
       ;; moves; there is nothing to name the session with, so the name stays nil. A
       ;; title invented from the system prompt or from the opening context would be a
       ;; name the person never said.
       (is (= "RUN_FINISHED"
              (:type (last (wire/frames-from-sse
                            (.body (post-run "no-words" {:append []})))))))
       (let [row (listed-row "no-words")]
         (is (nil? (:firstUserText row)) "nothing was said, so there is no name")
         (is (some? (:lastSentAt row)) "but a run did happen, so the time moved"))))))


;; ------------------------------------------------- the record that could not be written

(deftest a-record-that-cannot-be-written-is-said-on-the-routes-the-client-reads
  ;; ADR 0002 DECISION 6: writing can be BEHIND, it may not be SILENT. The failure
  ;; is the record writer's (`harness.edge.record/degraded`), and this is the half
  ;; the browser can see: the two doors it reads a conversation through say so, so
  ;; the page has something to put on screen. The other half -- that a DEGRADED
  ;; session keeps running and holds its lines -- is asserted in record-test.
  ;;
  ;; THE FAILURE IS INJECTED at the writer's own write, rather than by chmod-ing a
  ;; directory: a permission bit means nothing to a root test process, means
  ;; something different on Windows, and would leave a mode to restore. What the
  ;; route is being asked is whether it REPORTS a degradation, not how one arises.
  (let [tid (str "degraded-" (java.util.UUID/randomUUID))]
    (with-server
     {tid script}
     (fn []
       ;; A COMPLETE CONVERSATION FIRST. The read below is the ordinary settled one;
       ;; if the terminal frame never landed, `sofar` would answer the cut-off
       ;; refusal instead, and the test would be asking a different question.
       (post-run tid)
       (record/set-sink! (fn [_f _line] (throw (java.io.IOException. "disk is full"))))
       (try
         (#'http/log! tid nil "session/rebuilt" {:messages 0 :via "test"})
         (is (some? (wait-degraded! tid)) "the writer stopped on a line it could not write")
         (testing "GET /sofar -- the read the page polls -- says the record is degraded"
           (let [answer (read-json (sofar tid))]
             (is (= "degraded" (get-in answer [:record :state])))
             (is (str/includes? (str (get-in answer [:record :reason])) "disk is full")
                 "with the writer's own reason, not a paraphrase of it")
             (is (pos? (get-in answer [:record :pending]))
                 "and how many lines are waiting behind the one that failed")))
         (testing "POST /rebuild -- the read a session is opened through -- says it too"
           (let [answer (read-json (api-call :post (str "/api/threads/" tid "/rebuild") "{}"))]
             (is (= "degraded" (get-in answer [:record :state])))))
         (finally (record/reset-sink!)))
       (testing "and a session with nothing wrong carries NO record field at all"
         ;; Absence is the client's 'fine'. A field that said 'ok' on every answer
         ;; would be a field nobody acts on, and the one case worth reading would
         ;; have to be told apart from the noise.
         ;;
         ;; THE HEALTHY WRITE HAS TO BE BACK BEFORE THE RETRY: `retry!` resumes at the
         ;; line that failed, and a retry against the same broken disk fails again --
         ;; which would leave this assertion reading a record that is still degraded
         ;; for a reason this test made up rather than one it is asking about.
         (record/retry! tid)
         (is (= 0 (:pending (record/flush! 10000))))
         (is (nil? (:record (read-json (sofar tid))))))))))

;; -------------------------------------------------------- the window (ticket 05)

(defn- fill-live-window!
  "Put GROUPS arrivals of TEN entries each into THREAD-ID's live session, each landed at
  its own record offset. A window is meant to be a slice of a conversation too long to
  send, so the test has to HAVE one -- and building it through the same two calls the
  edge uses (`append!` then `land!`) is what keeps the numbers the ones the server would
  have minted."
  [tid groups]
  (dotimes [g groups]
    (let [run (str "win-r" g)]
      (sessions/append! tid run (mapv (fn [i] {:id (str run "-" i)
                                               :role "user"
                                               :content (str run "/" i)})
                                      (range 10)))
      (sessions/land! tid run (* 10 g)))))

(defn- feed-open!
  "Open THREAD-ID's feed on a socket of our own and answer [sock next-frame]:
  `next-frame` reads the next `data:` frame, or nil at end of stream.

  A RAW SOCKET RATHER THAN `api-call`, because this route does not end: what matters is
  what arrives AFTER the response, and `ofString` would wait for a body that only closes
  when the window does. The read timeout is the deadline -- a frame that never comes is a
  stack rather than a hung suite."
  ([tid] (feed-open! tid ""))
  ([tid query]
   (let [sock (java.net.Socket. "127.0.0.1" (int *port*))
         _    (.setSoTimeout sock 5000)
         out  (.getOutputStream sock)
         in   (.getInputStream sock)
         text (atom "")
         head (atom true)
         buf  (byte-array 4096)]
     (.write out (.getBytes (str "GET /api/threads/" tid "/feed" query
                                 " HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                                 "Accept: text/event-stream\r\n\r\n")
                            StandardCharsets/UTF_8))
     (.flush out)
     (letfn [(more! []
               (let [n (try (.read in buf)
                            (catch java.net.SocketTimeoutException _ -1))]
                 (when (pos? n)
                   (swap! text str (String. buf 0 n StandardCharsets/UTF_8))
                   true)))
             (next-frame []
               (loop []
                 (let [t @text]
                   (if @head
                     ;; THE RESPONSE HEAD, ONCE: split on the CRLFCRLF that ends it, so
                     ;; no header value can be mistaken for a frame.
                     (let [i (.indexOf t "\r\n\r\n")]
                       (if (neg? i)
                         (when (more!) (recur))
                         (do (reset! head false)
                             (reset! text (subs t (+ i 4)))
                             (recur))))
                     (let [i (.indexOf t "\n\n")]
                       (if (neg? i)
                         (when (more!) (recur))
                         (let [block (subs t 0 i)]
                           (reset! text (subs t (+ i 2)))
                           (when-some [[_ payload] (re-find #"(?m)^data: (.*)$" block)]
                             (json/read-str payload :key-fn keyword)))))))))]
       [sock next-frame]))))

(defn- feed-first-frame
  "Open THREAD-ID's feed, read its OPENING frame, close the socket, answer the frame.

  The `try`/`finally` lives here rather than in a case because a socket closed on the way
  out of a deep `testing` nest is exactly where a stray paren hides; a case that wants the
  FIRST frame (what a page sees the moment it opens the conversation) says so in one line."
  [tid]
  (let [[sock next-frame] (feed-open! tid)]
    (try (next-frame)
         (finally (.close sock)))))

(deftest a-page-is-cut-at-the-arrivals-the-conversation-was-written-in
  (with-server
   "win-page"
   (fn []
     (fill-live-window! "win-page" 30)
     ;; A RUN OF IT IS ALIVE IN THIS PROCESS, which is the one fact about a conversation
     ;; that is in neither the record nor the entries -- a page has to carry it, because
     ;; the turn on screen is drawn as finished or unfinished by it (ticket 06).
     (sessions/run-started! "win-page" "win-r0")
     (testing "no cursor is the tail page, and it is a slice rather than the whole thing"
       (let [resp (api-call :get "/api/threads/win-page/page" nil)
             body (read-json resp)]
         (is (= 200 (.statusCode resp)))
         (is (= 50 (count (:entries body))) "`page-size` entries")
         (is (= 250 (:baseSeq body)) "starting at the oldest arrival's own number")
         (is (true? (:hasMore body)) "and there is more in front of it")
         (is (true? (:live body)) "answered from the session this process is holding")
         (is (some? (:generation body)) "under the generation the window belongs to")
         (is (= 290 (:cursor body)) "and the reader's next cursor is the newest number")
         (is (= "running" (:state body))
             "and how far along it is: a run of this session is alive here")
         (is (= 0 (count (:entries (read-json (api-call :get
                                                        "/api/threads/win-page/page?beforeSeq=0"
                                                        nil)))))
             "and a reader holding from the very first arrival has nothing in front of it")))
     (testing "a cursor is the page IN FRONT of it -- no overlap, nothing skipped"
       (let [body (read-json (api-call :get "/api/threads/win-page/page?beforeSeq=250" nil))]
         (is (= "page" (:type body)))
         (is (= 50 (count (:entries body))))
         (is (= 200 (:baseSeq body)))
         (is (empty? (filter #(<= 250 (long (:seq %))) (:entries body)))
             "nothing the client already holds is handed back")))
     (testing "a cursor that is not a record offset is refused BY NAME"
       (let [resp (api-call :get "/api/threads/win-page/page?beforeSeq=abc" nil)]
         (is (= 400 (.statusCode resp)))
         (is (str/includes? (:error (read-json resp)) "beforeSeq"))))
     (testing "and a conversation nobody here holds is READ, not refused"
       ;; The record is still the way to page through a conversation another process is
       ;; serving -- or one nobody is: a read does not need to hold anything.
       (write-truncated-log! "win-cold" "r1")
       (let [resp (api-call :get "/api/threads/win-cold/page" nil)
             body (read-json resp)]
         (is (= 200 (.statusCode resp)))
         (is (false? (:live body)) "read from the record, and it says so")
         (is (= ["u1"] (mapv (comp :id :message) (:entries body))))
         (is (= [1] (mapv :seq (:entries body)))
             "numbered by its own line in the record -- line 0 is the run's prompt row, and the
              entry is the line that carries it, the same numbers a live session mints")
         (is (= "unfinished" (:state body))
             "and the state is the RECORD's own reading: a log that stops mid-run with
              nothing in this process running it says exactly that, and it is the flag
              that sends a client to the `rebuild` door that closes it off")))
     (testing "a stem with neither a session nor a log is a 404"
       (is (= 404 (.statusCode (api-call :get "/api/threads/win-nothing/page" nil))))))))

(deftest a-feed-whose-window-is-over-is-refused-before-it-streams
  (with-server
   "win-stale"
   (fn []
     (fill-live-window! "win-stale" 30)
     (let [generation (sessions/generation "win-stale")]
       (testing "a cursor behind the tail page would make the delta the whole conversation"
         (let [resp (api-call :get "/api/threads/win-stale/feed?since=3" nil)
               body (read-json resp)]
           (is (= 409 (.statusCode resp)))
           (is (str/includes? (str (:error body)) "window"))
           (is (= 250 (:baseSeq body)) "and it carries where the window starts now")
           (is (= generation (:generation body)))))
       (testing "a generation that is not this window's is the same refusal, from the other side"
         (let [resp (api-call :get "/api/threads/win-stale/feed?since=290&generation=gone" nil)
               body (read-json resp)]
           (is (= 409 (.statusCode resp)))
           (is (= generation (:generation body)))
           (is (= 250 (:baseSeq body)))))
       (testing "and a cursor the window still covers is let through to the stream"
         ;; Proved by the stream itself in the case below; here what matters is that the
         ;; door did NOT answer JSON -- the response is an event stream that stays open.
         (let [[sock next-frame] (feed-open! "win-stale"
                                             (str "?since=290&generation=" generation))]
           (try
             (let [frame (next-frame)]
               (is (= "append" (:type frame))
                   "the answer is a stream frame, not a 409 body")
               (is (empty? (:entries frame)) "with nothing in it: the reader is current")
               (is (= 290 (:cursor frame))))
             (finally (.close sock)))))))))

(deftest the-feed-opens-with-the-window-pushes-what-lands-and-ends-when-it-must
  (with-server
   "win-feed"
   (fn []
     ;; FOUR arrivals -- 40 entries, under `page-size` -- so the tail page is the WHOLE
     ;; conversation and every number in the test is one it can predict. A conversation
     ;; longer than a page is the case the page route above is about.
     (fill-live-window! "win-feed" 4)
     (let [generation (sessions/generation "win-feed")
           [sock next-frame] (feed-open! "win-feed")]
       (try
         (let [window (next-frame)]
           (testing "the first frame is the window"
             (is (= "window" (:type window)))
             (is (= 40 (count (:entries window))))
             (is (= 0 (:baseSeq window)) "the first arrival's number")
             (is (= 30 (:cursor window)) "the newest number the reader now holds")
             (is (false? (:hasMore window)) "and there is nothing in front of it")
             (is (= generation (:generation window))
                 "under the generation the session is being served with")))
         (testing "an entry that lands is pushed -- without anybody asking again"
           (sessions/append! "win-feed" "win-r99" [{:id "win-r99-0" :role "user"
                                                    :content "新的一条"}])
           (sessions/land! "win-feed" "win-r99" 40)
           ;; TWO TELLINGS ARE POSSIBLE AND BOTH ARE RIGHT: the append rings before the
           ;; line lands (the entry has no number yet) and the landing rings again. A
           ;; reader sees the entry once or twice, and the LAST telling carries its
           ;; number -- which is exactly the re-delivery the entries' ids are for.
           (let [told (loop [seen []]
                        (let [frame (next-frame)
                              entries (:entries frame)]
                          (if (and (seq entries) (some? (:seq (first entries))))
                            (conj seen frame)
                            (if (nil? frame)
                              seen
                              (recur (conj seen frame))))))
                 last-telling (last told)]
             (is (seq told) "the landing was pushed")
             (is (= "append" (:type last-telling)))
             (is (= ["win-r99-0"] (mapv (comp :id :message) (:entries last-telling))))
             (is (= [40] (mapv :seq (:entries last-telling))) "with its record number")
             (is (= 40 (:cursor last-telling)) "and the reader's cursor has moved")))
         (testing "putting the session away ends the stream, SAYING WHY"
           (sessions/drop! "win-feed")
           (let [frame (loop [f (next-frame)]
                         (if (or (nil? f) (not= "append" (:type f)))
                           f
                           (recur (next-frame))))]
             (is (= "end" (:type frame)))
             (is (= "put-away" (:reason frame))
                 "a stream that just stopped would leave a reader believing it holds everything")
             (is (= generation (:generation frame))
                 "...and it names the window that just ended, so a reader can tell it
                  apart from whatever holds the conversation next")))
         (testing "and then it is closed"
           (is (nil? (next-frame))))
         (finally (.close sock)))))))

(deftest a-feed-says-when-the-conversation-moves-without-new-entries
  ;; THE STATE IS PART OF THE WINDOW, and this is the case that proves why: a run that
  ;; SETTLES without saying anything still changes what the reader should draw (the turn
  ;; on screen stops being unfinished), and a frame sent only when entries land would
  ;; leave that turn looking live forever. So the feed pushes when EITHER moved.
  (with-server
   "win-state"
   (fn []
     (fill-live-window! "win-state" 2)
     (sessions/run-started! "win-state" "win-r-current")
     (let [[sock next-frame] (feed-open! "win-state")
           opened (next-frame)]
       (try
         (testing "the opening frame says a run of this conversation is alive"
           (is (= "window" (:type opened)))
           (is (= "running" (:state opened))))
         (sessions/run-finished! "win-state" "win-r-current")
         ;; A RUN THAT SAID NOTHING: the frames are the run's own start and nothing else,
         ;; so `apply-frames` folds no messages -- what changed is the state.
         (sessions/settle! "win-state" "win-r-current" [(ev/run-start)])
         (testing "and a frame arrives for the state alone"
           (let [frame (next-frame)]
             (is (= "append" (:type frame)) "a frame arrives for the state alone")
             (is (empty? (:entries frame)) "carrying no entries")
             (is (= "unfinished" (:state frame))
                 "and saying what this process now knows: a run of it ended without a
                  terminal frame, which is a fact the record agrees with")
             (is (= (:cursor opened) (:cursor frame))
                 "the reader's cursor has not moved -- there was nothing to number")))
         (finally (.close sock)))))))


(deftest the-record-holds-exactly-two-kinds-of-row
  ;; 拍定 2026-09-21 (`.scratch/jsonl-two-kinds`): `message` is what a person said or an LLM
  ;; returned; EVERY other fact is an `event` whose payload is a frame -- the wire's frames
  ;; themselves, and the harness's own facts as CUSTOM frames named after them.
  ;;
  ;; This reads the FILE raw, on purpose. Every other case in this file asks
  ;; `replay/kind` / `replay/payload` about a row; a case about the FORMAT has to see the
  ;; bytes the writer actually put down.
  (with-server
   "two-kinds"
   (fn []
     (post-run "two-kinds" {:append [{:id "u1" :role "user" :content "hi"}]})
     (let [rows (until (fn []
                         (let [rs (->> (str/split-lines (slurp (log-file "two-kinds")
                                                                :encoding "UTF-8"))
                                       (keep #(try (json/read-str % :key-fn keyword)
                                                   (catch Throwable _ nil)))
                                       vec)]
                           ;; THE WHOLE RECORD, NOT THE FIRST THING IN IT. The rows below are
                           ;; the FORMAT's own bytes, and a run's terminal frame is written after
                           ;; its body ends -- waiting only for the first `message` row read a
                           ;; record whose ending had not landed yet, and the three assertions
                           ;; below then found no terminal frame at all. The budget is the
                           ;; machine's: a scripted run plus the writer's flush, not 5s.
                           (when (and (some #(= "message" (:type %)) rs)
                                      (some #(= "RUN_FINISHED" (get-in % [:payload :type])) rs))
                             rs)))
                       15000)]
       (testing "every row is one of the two, and carries a payload"
         (is (seq rows))
         (is (every? #(contains? #{"event" "message"} (:type %)) rows)
             (str "rows seen: " (pr-str (mapv :type rows))))
         (is (every? #(contains? % :payload) rows)))
       (testing "and none of them is written in the old contract"
         (is (not-any? #(contains? % :kind) rows)
             "`kind` at the top level is the row this build refuses to read")
         (is (not-any? #(contains? (:payload %) :kind) rows)
             "nor does the envelope's vocabulary leak into a payload"))
       (testing "a message row keeps the provider's own map, verbatim"
         (let [msg (first (filter #(and (= "message" (:type %))
                                        (= "user" (get-in % [:payload :role])))
                                  rows))]
           (is (some? msg) "the person's own message is a `message` row")
           (is (= "hi" (get-in msg [:payload :content])))
           (is (empty? (filter #(contains? (:payload msg) %)
                               [:type :payload :ts :runId :source :hash]))
               "identity and time live on the envelope, never inside the payload")))
       (testing "the system prompt is a message row too -- and the array's FIRST one"
         ;; OWNER, 2026-09-21: a `message` row IS an element of the messages array the model
         ;; was handed, and the prompt is that array's first element, so it is stored the way
         ;; the model got it -- verbatim, `:source` saying the prompt put it there and `:hash`
         ;; naming those bytes. Every run writes its own copy (a stable prefix is what the
         ;; provider's cache reads), which is why `harness.edge.replay/entries` -- the
         ;; conversation, and with it everything a client is ever handed -- is the ONE place
         ;; that has to leave it out.
         (let [prompt (first (filter #(and (= "message" (:type %))
                                           (= "system-prompt" (:source %)))
                                     rows))]
           (is (some? prompt) "the run recorded the prompt it handed the model")
           (is (= "system" (get-in prompt [:payload :role])) "as a system message")
           (is (string? (get-in prompt [:payload :content])))
           (is (= 64 (count (:hash prompt))) "named by its SHA-256, on the envelope")
           (let [first-wire (first (filter #(= "RUN_STARTED" (get-in % [:payload :type])) rows))]
             (is (some? first-wire) "the run's own first frame is on the record")
             (is (< (.indexOf rows prompt) (.indexOf rows first-wire))
                 "the run wrote it before its first wire frame; what sits IN FRONT of the
                  prompt is the action's own rows -- the client's message, which arrived
                  first (票 02) -- and the hook facts that fire while the run is set up"))))
       (testing "a wire frame is an event whose payload IS the frame"
         (let [terminal (first (filter #(= "RUN_FINISHED" (get-in % [:payload :type])) rows))]
           (is (some? terminal) "a run's terminal frame is on the record")
           (is (= "event" (:type terminal)))
           (is (= "two-kinds" (get-in terminal [:payload :threadId]))
               "and it is the frame itself, not a copy of its fields")))
       (testing "a fact the harness knows on its own is an event too -- a CUSTOM frame"
         (let [fact (first (filter #(= "model/start" (get-in % [:payload :name])) rows))]
           (is (some? fact) "the model call's start is a fact with no wire frame of its own")
           (is (= "event" (:type fact)))
           (is (map? (get-in fact [:payload :value])) "carrying what the fact knew")
           (is (nil? (get-in fact [:payload :messageId]))
               "and no message id: nothing on screen draws it"))
         (is (not-any? #(= "input" (get-in % [:payload :name])) rows)
             "and the `input` row 票 02 deletes is GONE -- what an action brought is its own
              message rows, not a fact restating them"))))))

(deftest an-overflow-refusal-compacts-aggressively-and-retries-in-one-turn
  ;; ticket 05: the vendor says the request is too LONG -- a failure of a KIND of its own, not
  ;; just any 400. Before the run ends, the history is compacted with NO capacity consulted,
  ;; and because it really got shorter the call is retried in the SAME run: the user sees one
  ;; turn, not an error followed by a second run.
  (with-server
    "compact-overflow"
    [{:refuse {:status 400
               :body   (str "This model's maximum context length is 128000 tokens."
                            " However, your messages resulted in 200000 tokens.")}}
     {:content "THE SUMMARY"}
     {:content "THE ANSWER"}]
    (fn []
      (let [log (log-file "compact-overflow")]
        (io/delete-file log true)
        ;; a conversation the aggressive plan has a head to take: several turns on the record
        (spit log
              (str (str/join "\n"
                             (map (fn [i]
                                    (json/write-str {:ts i :runId nil :type "message"
                                                     :source "client" :id (str "u" i)
                                                     :payload {:role "user"
                                                               :content (apply str (repeat 200 "a"))}}))
                                  (range 8)))
                   "\n")
              :encoding "UTF-8")
        (let [frames (wire/frames-from-sse
                      (.body (post-run "compact-overflow"
                                       {:append [{:id "u-new" :role "user" :content "keep going"}]})))]
          (testing "the retry answered, in this one run"
            (is (= "RUN_FINISHED" (:type (last frames))) (pr-str (last frames)))
            (is (= "THE ANSWER"
                   (apply str (map :delta (filter #(= "TEXT_MESSAGE_CONTENT" (:type %)) frames))))))
          (testing "and the record holds one compaction, written before the retry"
            (is (until (fn [] (some #{"compaction/end"}
                                    (map replay/kind (replay/read-records log))))
                       5000))
            (let [kinds (mapv replay/kind (replay/read-records log))]
              (is (some #{"context/compacted"} kinds))
              (is (some #{"compaction/start"} kinds)))))))))

(deftest a-giant-tool-result-is-pruned-for-free-and-the-summary-is-skipped
  ;; ticket 06: an oversized TOOL RESULT is elided in the middle -- head and tail kept -- with NO
  ;; model call, and the pressure is MEASURED AGAIN. A view that came back under the threshold
  ;; does not pay for a summary at all, and the record keeps the whole original beside its receipt.
  (with-server
    "prune-e2e"
    [{:content "DONE"}]
    (fn []
      (let [tid   "prune-e2e"
            f     (log-file tid)
            giant (apply str (repeat 60000 "x"))   ;; ~15000 tokens, over a 10000-window's 7000
            line! (fn [run-id kind payload & [extra]]
                    (spit f (str (row-json (merge {:ts (System/currentTimeMillis)
                                                   :runId run-id :kind kind :payload payload}
                                                  extra))
                                 "\n")
                          :append true :encoding "UTF-8"))]
        (io/delete-file f true)
        (.mkdirs (.getParentFile f))
        (line! "r1" "message" {:role "user" :content "look"} {:source "client" :id "u1"})
        (line! "r1" "model/start" {:model "scripted" :context-window 10000 :tools []})
        (doseq [frame (mapcat (ag/outbound tid "r1")
                              [(ev/run-start)
                               (ev/tool-call "c1" "read" "{}")
                               (ev/tool-result "c1" giant nil)
                               (ev/run-end)])]
          (line! "r1" "event" frame))
        (#'http/compact-if-pressured! tid)
        (#'http/compact-if-pressured! tid)   ;; again: pruning must not repeat itself
        (let [pruned? (until (fn [] (some #{"context/pruned"}
                                          (map replay/kind (replay/read-records f))))
                             3000)
              kinds   (mapv replay/kind (replay/read-records f))]
          (testing "a receipt, and NO summary -- pruning alone was enough"
            (is pruned?)
            (is (not-any? #{"context/compacted"} kinds))
            (is (not-any? #{"compaction/start"} kinds))
            (is (= 1 (count (filter #{"context/pruned"} kinds)))
                "pruning is idempotent: the second trigger wrote no second receipt"))
          (testing "the MODEL reads the elided text; the record keeps the whole one"
            (let [view (sessions/messages tid)]
              (is (some #(and (= "tool" (:role %))
                              (str/includes? (str (:content %)) "pruned"))
                        view))
              (is (every? (fn [m]
                            (or (not= "tool" (:role m))
                                (< (count (str (:content m))) 20000)))
                          view)))
            (is (str/includes? (slurp f :encoding "UTF-8") giant)
                "the original is still on the record")))))))

(deftest a-just-produced-giant-tool-result-is-spilled-and-read-back
  ;; ticket 09: a SINGLE huge tool result is moved out of the conversation the moment it is
  ;; produced. The model is handed a pickup slip, the record never holds the giant text (so no
  ;; request ever carried it), and the `read` tool the slip names gets the original back.
  (with-server
    "spill-e2e"
    [{:content ""
      :tool-calls [{:id "c1" :name "spew" :arguments {}}]}
     {:content "got it"}]
    (fn []
      (let [tid  "spill-e2e"
            f    (log-file tid)
            body (apply str (map (fn [i] (str "row " i " " (apply str (repeat 40 "z")) "\n"))
                                (range 2000)))]
        (tools/session-register! tid "spew"
          {:description "Returns a giant string."
           :parameters  {:type "object" :properties {} :required []}
           :required    []
           :run         (fn [_] body)})
        (try
          (io/delete-file f true)
          (let [frames  (wire/frames-from-sse
                         (.body (post-run tid {:append [{:id "u1" :role "user" :content "go"}]})))
                result  (first (filter #(= "TOOL_CALL_RESULT" (:type %)) frames))
                slip    (str (:content result))
                locator (second (re-find #"(?m)^Locator: (.+)$" slip))
                rows    (replay/read-records f)]
            (testing "the model is handed a slip, not the giant result"
              (is (some? result))
              (is (str/includes? slip "spilled"))
              (is (str/includes? slip "use the read tool"))
              (is (not (str/includes? slip body)) "the giant text is not in what the model reads"))
            (testing "and no request ever carried the giant text"
              (is (not-any? #(str/includes? (str (:content (:payload %))) body) rows)
                  "no recorded row's content is the whole result")
              (is (< (count slip) 5000)
                  "the slip the model reads is tiny next to the result"))
            (testing "the slip's retrieval path -- the read tool -- returns the original"
              (let [got (tools/run! {:function {:name "read"
                                                :arguments (json/write-str {:path locator})}}
                                    tid)]
                (is (false? (:error got)))
                (is (str/includes? (apply str (read-lines (:content got))) "row 0 zzzz")
                    "the spilled file comes back through the read tool"))))
          (finally (tools/session-unregister! tid "spew")))))))

(deftest the-language-route-is-this-homes-one-source-for-it
  ;; GET and POST /api/language are the interface's ONLY source for the language -- the
  ;; same value <env> states -- so a page that reads the route and a run that states the
  ;; block cannot disagree. No threadId: a language is a fact about the HOME.
  (let [config (home/config-file)
        saved  (when (.exists config) (slurp config :encoding "UTF-8"))]
    (try
      (spit config "{:default {:provider :alpha :model \"alpha-small\"}}\n" :encoding "UTF-8")
      (with-server "lang-unused"
        (fn []
          (testing "GET always answers one of the two languages"
            (let [body (read-json (api-call :get "/api/language" nil))]
              (is (contains? #{"en" "zh"} (:language body)))))
          (testing "POST writes the choice into config.edn and answers what is resolved"
            (let [resp (api-call :post "/api/language" (json/write-str {:language "zh"}))]
              (is (= 200 (.statusCode resp)))
              (is (= "zh" (:language (read-json resp))))
              (is (str/includes? (slurp config :encoding "UTF-8") ":language :zh"))))
          (testing "a language nobody speaks is a 400 and leaves the file alone"
            (let [before (slurp config :encoding "UTF-8")
                  resp   (api-call :post "/api/language" (json/write-str {:language "fr"}))]
              (is (= 400 (.statusCode resp)))
              (is (str/includes? (:error (read-json resp)) ":fr")
                  "the server's own sentence comes back")
              (is (= before (slurp config :encoding "UTF-8")))))))
      (finally
        (if saved (spit config saved :encoding "UTF-8") (io/delete-file config true))))))
