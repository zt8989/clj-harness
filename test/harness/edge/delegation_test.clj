(ns harness.edge.delegation-test
  "A subagent, through the REAL HTTP edge: the delegating tool, the subagent's own
  conversation, its own record, and the range it is held to.

  WHY THIS IS NOT IN harness.cap.subagents-test. Everything the ticket asks for is a
  claim about a RUN: what the client receives, what the two jsonl files hold, and
  which hook points fired. None of that exists below the edge -- the runner lives
  there, the hook sink is bound there, and the record is written there -- so a test
  that called the pieces directly would be proving the pieces work, not that a
  delegation works.

  THE SCRIPT IS ONE SEQUENCE, AND THAT IS THE POINT. The provider pin belongs to the
  DELEGATING thread and the subagent inherits it (see
  harness.edge.http/run-subagent!): so the turns below are consumed in exactly the
  order the conversation reaches them -- the parent's opening round, the subagent's
  whole conversation, then the parent's closing round. A delegation that ran at the
  wrong moment, or twice, would consume them out of order and land its assertions on
  the wrong text."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.fake :as fake]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.kernel.hooks :as hooks]
            [harness.edge.http :as http]
            [harness.edge.replay :as replay]
            [harness.cap.project :as project]
            [harness.cap.providers :as providers]
            [harness.test-support :as support])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def ^:dynamic *port* nil)

;; -------------------------------------------------------------------- fixtures
;;
;; A hooks.edn left behind by another namespace would add declarations this one
;; never wrote -- and the counts below are exact. Wiped around every test, the same
;; discipline project_test applies to harness.edn.

(defn- wipe! [] (support/wipe-hooks!))

(use-fixtures :each (fn [f] (wipe!) (f) (wipe!)))

;; ----------------------------------------------------------------- the harness

(defn- with-server
  "A live server on an OS-chosen port, with a scripted provider pinned to THREAD.
  The port is never written down: see AGENTS.md.

  THE SESSION IS BORN FIRST. The run door refuses an action for a conversation the
  store has never heard of (`harness.edge.http/refuse-unknown-session!`: turning up is
  not how a session is created), so this stands in for the page's POST /api/sessions --
  which is also what binds THREAD's record to a project's workspace when the test binds
  one."
  [thread turns f]
  (providers/use-provider! thread (fake/scripted turns))
  (support/start-session! thread)
  (let [stop (http/start! {:port 0})
        port (:local-port (meta stop))]
    (try (binding [*port* port] (f))
         (finally (stop) (providers/use-provider! thread nil)))))

(defn- post-run
  "One action on the run edge, as a page sends it: the ENTRIES it adds (`:append`) and
  nothing that is per-run. There is no body `:runId` any more -- the server mints it,
  because it names a run in THIS process -- and no `:messages`, which the door refuses
  by name (`harness.edge.http/refuse-retired-messages!`)."
  ([thread-id] (post-run thread-id {}))
  ([thread-id extra]
   (let [body (json/write-str (merge {:threadId thread-id
                                      :append   [{:id "u1" :role "user" :content "go"}]
                                      :tools [] :context []}
                                     extra))
         ;; THE RUN IS READ FROM THE DOWNLINK NOW (`support/mux-run-response`): the POST answers
         ;; an ack and the frames arrive on `events.mux`, so the helper subscribes first and
         ;; hands back the same `HttpResponse` (status, headers, SSE body) it always did.
         result (support/mux-run-response *port* thread-id body nil)]
     result)))

(defn- api-call
  "A plain JSON call to the management edge -- the /api/* endpoints, not the AG-UI
  run endpoint. Returns the raw HttpResponse."
  [method path body]
  (let [b (.header (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* path)))
                   "Content-Type" "application/json")
        b (if (= :post method)
            (.POST b (HttpRequest$BodyPublishers/ofString (str body) StandardCharsets/UTF_8))
            (.GET b))]
    (.send (HttpClient/newHttpClient) (.build b)
           (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))))

(defn- read-json [resp] (json/read-str (.body resp) :key-fn keyword))

(defn- frames
  "The SSE body as maps. The wire shape is `data: {json}` lines, which is what the
  runner writes. `.body`, not `(:body ..)`: a java.net.http response is not a map,
  and keyword lookup on it answers nil in silence."
  [^HttpResponse response]
  (->> (str/split-lines (.body response))
       (keep #(when (str/starts-with? % "data: ") (subs % 6)))
       (mapv #(json/read-str % :key-fn keyword))))

(defn- frame-of [fs type] (filterv #(= type (:type %)) fs))

(defn- called-names
  "The tool names a REBUILT message list mentions. A tool call is nested on the
  assistant message it belongs to (see harness.kernel.frames/apply-frames), so the
  question is asked of the whole list rather than of any one message."
  [messages]
  (->> messages (mapcat :toolCalls) (map #(get-in % [:function :name])) (remove nil?) set))

(defn- workspace-of
  "The workspace THREAD-ID's records are in, asked the way the SERVER asks it: the
  sanitized project identity, or the reserved workspace when it is bound to nothing.
  Derived rather than written down, so a change to the naming rule fails here
  instead of making a test agree with a stale path."
  [thread-id]
  (io/file (home/projects-dir)
           (if-let [identity (project/identity-for thread-id)]
             (home/sanitize identity)
             http/unbound-workspace)))

(defn- read-lines [^java.io.File f]
  (->> (str/split-lines (slurp f :encoding "UTF-8"))
       (remove str/blank?)
       (keep #(try (json/read-str % :key-fn keyword) (catch Throwable _ nil)))
       (vec)))

(defn- log-file [thread-id]
  (home/log-file (workspace-of thread-id) thread-id))

(defn- log-lines
  "THREAD-ID's own record, read AFTER the run -- a run's lines land as it goes."
  [thread-id]
  (let [f (log-file thread-id)]
    (when (.exists f) (read-lines f))))

(defn- wait-for
  "The log, once PRED holds of it or MS has passed. A run's tail lands a beat after
  its terminal frame, and the hook audit lines for a subagent are written on the
  tool thread -- so a claim made the instant the HTTP response arrives is a race."
  [thread-id pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [ls (log-lines thread-id)]
        (if (or (pred ls) (> (System/currentTimeMillis) deadline))
          ls
          (do (Thread/sleep 50) (recur)))))))

(defn- kinds
  "Each row's OWN name, asked the way every reader of a record asks it
  (`harness.edge.replay/kind`): `message`, a wire frame's `type`, or the name of the
  CUSTOM frame the harness writes about itself (`hook/SubagentStart`,
  `tools/pre-execute`, `model/start`, ...).

  NOT A ROW'S `:type`, and that distinction is the whole of this helper. The record has
  exactly two row types -- `message` and `event` -- so `:type` answers `event` for every
  fact below, including the ones these tests are about. The name is derived, in one
  place, by the reader that folds records; spelling it again here is how a test comes to
  disagree with the record it is reading."
  [lines]
  (mapv replay/kind lines))

(defn- messages-of [lines role]
  (->> lines
       (filter replay/message?)
       (map replay/payload)
       (filter #(= role (:role %)))
       (map :content)))

(defn- subagent-log
  "The record of the delegation THIS thread made: the jsonl of the child session the
  STORE says THREAD-ID's delegation created.

  ASKED OF THE STORE, NOT OF THE FILE. The record no longer opens with an `input` line
  naming the delegator -- `.scratch/jsonl-two-kinds` 票 02 deleted that row, and a
  subagent's file holds its own CONVERSATION while the link back to the delegator lives
  where every other session fact does. The workspace is shared with every other
  delegation this JVM ran and the file's name is a fresh UUID nobody can predict, so
  the row is what identifies the file rather than the file being guessed at.

  IT WAITS FOR THE RUN TO BE ON DISK. The record is written by a consumer off the run's
  own thread, so 'the HTTP response arrived' and 'the last line landed' are two
  moments; the run's terminal frame is the row that says the first is over."
  [thread-id]
  (let [deadline (+ (System/currentTimeMillis) 4000)
        child    #(some-> (db/select (str "SELECT id FROM sessions WHERE parent_id = ?"
                                          " ORDER BY created_at DESC")
                                     thread-id)
                          first :id)
        done?    #(boolean (some (fn [l] (and (= "event" (replay/kind l))
                                              (contains? #{"RUN_FINISHED" "RUN_ERROR"}
                                                         (:type (replay/payload l)))))
                                 %))]
    (loop []
      (let [id (child)
            f  (when id (log-file id))
            ls (if (and f (.exists f)) (read-lines f) [])]
        (cond
          (and id (done? ls))                     {:thread-id id :lines ls}
          (> (System/currentTimeMillis) deadline) (when id {:thread-id id :lines ls})
          :else                                   (do (Thread/sleep 50) (recur)))))))

(def ^:private explore-task "where is the tool execution seam")

;; ------------------------------------------------------------------ the bullet

(deftest a-delegation-runs-and-only-its-answer-enters-the-conversation
  (let [thread "dg-happy"
        script [{:content ""
                 :tool-calls [{:id "d1" :name "agent"
                               :arguments {:name "explore" :prompt explore-task}}]}
                ;; ^ the delegating round
                {:content "" :tool-calls [{:id "c1" :name "read"
                                           :arguments {:path "deps.edn"}}]}
                {:content "the seam is harness.kernel.tools/run!"}
                ;; ^ the SUBAGENT's whole conversation: a tool call, then its answer
                {:content "the subagent found it"}]]
    ;; ^ the delegating round's second call, which happens only once the tool result
    ;;   exists -- so a subagent that never ran leaves this turn unconsumed
    (with-server thread script
      (fn []
        (let [fs      (frames (post-run thread))
              calls   (frame-of fs "TOOL_CALL_START")
              results (frame-of fs "TOOL_CALL_RESULT")
              answer  (->> results (filter #(= "d1" (:toolCallId %))) first :content)]
          (testing "the client sees the delegation as one tool call"
            (is (= ["agent"] (mapv :toolCallName calls))))
          (testing "whose result IS the subagent's answer"
            (is (= 1 (count (filter #(= "d1" (:toolCallId %)) results))))
            (is (str/includes? (str answer) "the seam is harness.kernel.tools/run!")))
          (testing "and nothing else about the exchange"
            ;; The subagent's own tool call is not this stream's business: a client
            ;; that could see it would be watching two conversations at once.
            (is (not-any? #(= "c1" (:toolCallId %)) (concat calls results))))
          (testing "the run finished rather than interrupting"
            (is (= 1 (count (frame-of fs "RUN_FINISHED"))))
            (is (empty? (frame-of fs "RUN_ERROR"))))
          (testing "and the delegating conversation's record holds the answer as a tool
                    result, not as something the model said"
            ;; WAITED FOR, not read once: the returned side of the message record
            ;; lands a beat after the terminal frame (a delegation makes that beat
            ;; long), and a snapshot taken when the HTTP response arrives is a
            ;; snapshot of a record still being written.
            (let [lines (wait-for thread
                                  (fn [ls] (seq (messages-of ls "tool")))
                                  4000)
                  tool  (messages-of lines "tool")
                  said  (messages-of lines "assistant")]
              (is (some #(str/includes? (str %) "the seam is") tool))
              (is (not-any? #(str/includes? (str %) "the seam is") said)
                  "the answer arrived as a tool result, not as the parent's own words"))))))))

(deftest the-subagent-runs-as-its-own-conversation-with-its-own-record
  (let [thread "dg-record"
        script [{:content "" :tool-calls [{:id "d1" :name "agent"
                                           :arguments {:name "explore" :prompt explore-task}}]}
                {:content "" :tool-calls [{:id "c1" :name "read"
                                           :arguments {:path "deps.edn"}}]}
                {:content "found it"}
                {:content "ok"}]]
    (with-server thread script
      (fn []
        (post-run thread)
        (let [{:keys [thread-id lines]} (subagent-log thread)]
          (is (some? thread-id) "a delegation leaves a record of its own")
          (is (not= thread (str thread-id)) "under the subagent's own id, not the parent's")
          (testing "opened by the task, as this conversation's own first message"
            ;; WHAT USED TO BE AN `input` LINE. The row is gone
            ;; (`.scratch/jsonl-two-kinds` 票 02): a run logs the messages IT put into
            ;; the array the model read, and the task IS one of them -- this session's
            ;; first, and its only client message.
            (let [first-msg (first (filter replay/message? lines))]
              (is (= "user" (:role (replay/payload first-msg))))
              (is (= explore-task (:content (replay/payload first-msg)))
                  "and the task as the tool was handed it")
              (is (= "client" (:source first-msg))
                  "whose element it was: the delegating model is this session's client,
                   which is the source the fold reads as a speaking part")
              (is (string? (:id first-msg)) "named, so a repeat or a rebuild folds it once")))
          (testing "with the subagent's own system message -- who it is"
            (let [system (first (messages-of lines "system"))]
              (is (str/includes? (str system) "<subagent>"))
              (is (str/includes? (str system) "You are the explore subagent"))
              (is (str/includes? (str system) "cannot delegate"))))
          (testing "and its conversation, not the parent's"
            (let [roles (->> (filter replay/message? lines) (map replay/payload) (map :role) set)]
              (is (contains? roles "user"))
              (is (contains? roles "assistant"))
              (is (contains? roles "tool") "it really read a file"))))))))

(deftest the-two-subagent-hook-points-fire-on-the-delegating-record
  ;; The points were DECLARED and had never fired, which is the one thing their
  ;; declaration could not tell anybody. Observed two ways, deliberately:
  ;;
  ;;   * through an in-process row at each point, so the fact itself is on hand --
  ;;     which conversation it fired for, and the subagent's name;
  ;;   * and through the audit line a run leaves, which is what a reader of the
  ;;     record sees afterwards.
  ;;
  ;; AN IN-PROCESS ROW RATHER THAN A hooks.edn COMMAND, because a :command takes this
  ;; machine's shell to run and this machine's shell is the known-crooked piece
  ;; (harness.infra.shell resolves a Windows launcher that is not a shell -- see the
  ;; baseline failures in harness.kernel.tools-test). What is under test here is that
  ;; the POINT fires, not that a shell works.
  (let [thread    "dg-hooks"
        seen      (atom [])
        probe     (fn [point]
                    (fn [payload]
                      (swap! seen conj [point payload])
                      {:exit 0 :out "" :err ""}))
        install   (hooks/install! {:name "delegation probe"
                                   :builtins
                                   {:subagent-start [["probe-start" {:run (probe :subagent-start)}]]
                                    :subagent-stop  [["probe-stop" {:run (probe :subagent-stop)}]]}})
        script    [{:content "" :tool-calls [{:id "d1" :name "agent"
                                              :arguments {:name "general" :prompt "just answer"}}]}
                   {:content "answered"}
                   {:content "ok"}]]
    (try
      (with-server thread script
        (fn []
          (post-run thread)
          (let [ls (wait-for thread (fn [ls] (some #(= "hook/SubagentStop" (replay/kind %)) ls)) 4000)
                ks (kinds ls)]
            (testing "each point fired exactly once, in order"
              (is (= [:subagent-start :subagent-stop] (mapv first @seen)))
              (is (= 1 (count (filter #{"hook/SubagentStart"} ks))))
              (is (= 1 (count (filter #{"hook/SubagentStop"} ks))))
              (is (< (.indexOf ^java.util.List ks "hook/SubagentStart")
                     (.indexOf ^java.util.List ks "hook/SubagentStop"))))
            (testing "both name the conversation that delegated and the subagent"
              ;; THE DELEGATING THREAD, not the subagent's: the sink is the parent's
              ;; when these fire, because they are statements about the parent's run.
              ;; STRING KEYS, because the payload convention is the one a :command
              ;; reads on stdin -- see harness.kernel.hooks.dispatch/payload-of.
              (doseq [[_ payload] @seen]
                (is (= thread (get payload "thread_id")))
                (is (= "general" (get payload "subagent")))))
            (testing "and the start lands inside the call it is about"
              ;; After the call's pre-execute -- the seam reports that before the body
              ;; runs, and the delegation IS the body -- and before its post-execute.
              (is (< (.indexOf ^java.util.List ks "hook/SubagentStart")
                     (.indexOf ^java.util.List ks "tools/post-execute")))))))
      (finally (install)))))

;; ----------------------------------------------------------------- the failures

(deftest an-unknown-subagent-is-refused-by-name-listing-what-exists
  (let [thread "dg-unknown"
        script [{:content "" :tool-calls [{:id "d1" :name "agent"
                                           :arguments {:name "nope" :prompt "hi"}}]}
                {:content "ok"}]]
    (with-server thread script
      (fn []
        (let [results (frame-of (frames (post-run thread)) "TOOL_CALL_RESULT")
              content (->> results (filter #(= "d1" (:toolCallId %))) first :content)]
          (is (str/includes? (str content) "no subagent called"))
          (is (str/includes? (str content) "general") "and it lists the ones that do exist")
          (is (str/includes? (str content) "explore")))))))

(deftest a-subagent-cannot-reach-outside-its-range-in-a-real-run
  (let [thread "dg-range"
        script [{:content "" :tool-calls [{:id "d1" :name "agent"
                                           :arguments {:name "explore" :prompt "write it"}}]}
                ;; ^ the delegating round
                {:content "" :tool-calls [{:id "c1" :name "write"
                                           :arguments {:path "notes.md" :content "hi"}}]}
                {:content "I could not write it"}
                ;; ^ the SUBAGENT: refused, and says so -- which is the half that
                ;;   makes the range usable rather than merely closed
                {:content "understood"}]]
    (with-server thread script
      (fn []
        (post-run thread)
        (let [{:keys [lines]} (subagent-log thread)
              refusal (->> (messages-of lines "tool")
                           (map str)
                           (filter #(str/includes? % "not part of this subagent's range"))
                           first)
              pre     (->> lines (filter #(= "tools/pre-execute" (replay/kind %)))
                           (map replay/payload))]
          (is (some? refusal) "the call was refused and the reason went back to the model")
          (is (str/includes? (str refusal) "explore") "naming the range it is in")
          (is (some #(= "unserved" (:outcome %)) pre) "and the record says why it did not run")
          (is (empty? (filter #(= "tools/execute" (replay/kind %)) lines))
              "it never executed: the range is enforced at the seam, not read and ignored"))))))

(deftest a-subagent-is-told-when-a-call-would-need-a-human-it-cannot-ask
  ;; The other half of "a subagent has nobody watching it": a call the fence would
  ;; park for a person does not park here. It comes back as information, so the
  ;; subagent carries on with what it has instead of leaving a card nobody is looking
  ;; at and a run stopped forever. A park in a subagent would be indistinguishable
  ;; from a hang, which is the failure this exists to prevent.
  (let [thread      "dg-parked"
        project-dir (support/temp-dir "delegation-project")
        script      [{:content "" :tool-calls [{:id "d1" :name "agent"
                                                :arguments {:name "general" :prompt "read it"}}]}
                     ;; ^ the delegating round
                     {:content "" :tool-calls [{:id "c1" :name "read"
                                                :arguments {:path "../outside.txt"}}]}
                     {:content "I could not read it"}
                     ;; ^ the SUBAGENT: told it needs a human it cannot ask
                     {:content "understood"}]]
    (try
      (project/bind! thread project-dir)
      (with-server thread script
        (fn []
          (let [fs (frames (post-run thread))]
            (testing "nothing parked, so the delegating run finished"
              (is (= 1 (count (frame-of fs "RUN_FINISHED"))))
              (is (not-any? #(= "interrupt" (get-in % [:outcome :type])) fs)))
            (testing "and the subagent was told why, in the words of the fact"
              (let [tool (messages-of (:lines (subagent-log thread)) "tool")]
                (is (some #(str/includes? (str %) "needs a human's approval") tool))
                (is (some #(str/includes? (str %) "nobody to ask") tool)))))))
      (finally
        (project/bind! thread nil)
        (support/wipe-tree! project-dir)))))

;; --------------------------------------------------------- the store, and read-back
;;
;; 02 gave a delegation a jsonl of its own; these are the other half -- it is a
;; SESSION of this home, bound where its parent is bound, readable by its own id,
;; and NOT one of the conversations the sidebar lists.

(def ^:private project-script
  "A delegation from a session that IS bound: one directory for the parent, and its
  subagent resolves against the same one."
  [{:content "" :tool-calls [{:id "d1" :name "agent"
                              :arguments {:name "explore" :prompt explore-task}}]}
   {:content "it is harness.kernel.tools"}
   {:content "ok"}])

(def ^:private readback-script
  "The same delegation where the subagent WORKS -- so its record has a tool call of
  its own to read back, which is the half that tells the two conversations apart."
  [{:content "" :tool-calls [{:id "d1" :name "agent"
                              :arguments {:name "explore" :prompt explore-task}}]}
   {:content "" :tool-calls [{:id "c1" :name "read" :arguments {:path "deps.edn"}}]}
   {:content "it is harness.kernel.tools"}
   {:content "ok"}])

(defn- with-project
  "Run F with THREAD bound to a fresh directory, released afterwards."
  [thread f]
  (let [dir (support/temp-dir "delegation-store")]
    (try
      (project/bind! thread dir)
      (f dir)
      (finally
        (project/bind! thread nil)
        (support/wipe-tree! dir)))))

(deftest a-delegation-leaves-a-row-that-says-who-it-belonged-to
  (let [thread "dg-store"]
    (with-project
     thread
     (fn [_]
       (with-server thread project-script
         (fn []
           (post-run thread)
           (let [{:keys [thread-id]} (subagent-log thread)
                 row    (first (db/select "SELECT s.parent_id, s.subagent, s.project_id, s.path
                                             FROM sessions s WHERE s.id = ?" thread-id))
                 parent (first (db/select "SELECT project_id, path FROM sessions WHERE id = ?"
                                          thread))]
             (is (some? row) "the subagent is a session of this home, not just a file")
             (is (= thread (:parent-id row)) "whose parent is the conversation that delegated")
             (is (= "explore" (:subagent row)) "and which subagent it ran as")
             (testing "bound to its parent's project, spelled the way the parent spells it"
               (is (some? (:project-id row)))
               (is (= (:project-id parent) (:project-id row)))
               (is (= (:path parent) (:path row))))
             (testing "and its record is in that project's workspace"
               (is (str/starts-with? (.getAbsolutePath (log-file thread-id))
                                     (.getAbsolutePath (workspace-of thread))))))))))))

(deftest a-subagent-is-not-a-conversation-in-the-sidebars-listing
  (let [thread "dg-listing"]
    (with-project
     thread
     (fn [dir]
       (with-server thread project-script
         (fn []
           (post-run thread)
           ;; THE ANSWER IS A MAP OF TWO LISTS (`harness.edge.http/projects-get`):
           ;; `:projects`, each with its sessions, and `:tasks` for conversations that
           ;; never had a home. Reading it as a bare list of projects is what this
           ;; assertion spent one rebase doing -- and an empty listing passes every
           ;; 'is not in it' check below, which is why the first of the three says the
           ;; row is really there.
           (let [child  (:thread-id (subagent-log thread))
                 listed (->> (:projects (read-json (api-call :get "/api/projects" nil)))
                             (filter #(= (.getCanonicalPath (io/file dir)) (:path %)))
                             first
                             :sessions
                             (mapv :threadId))]
             (testing "the project lists the conversation that ran"
               (is (contains? (set listed) thread)))
             (testing "and not the subagent it delegated to"
               ;; A row here is an offer to open, continue and type into a
               ;; conversation. A subagent's is none of those.
               (is (not (contains? (set listed) child))))
             (testing "while the row is really there"
               (is (some? (db/select "SELECT id FROM sessions WHERE id = ?" child)))))))))))

(deftest a-subagent-can-be-read-back-by-its-own-id
  (let [thread "dg-readback"]
    (with-project
     thread
     (fn [dir]
       (with-server thread readback-script
         (fn []
           (post-run thread)
           (let [child (:thread-id (subagent-log thread))
                 _     (wait-for thread (fn [ls] (seq (messages-of ls "tool"))) 4000)
                 mine  (read-json (api-call :get (str "/api/threads/" child "/trajectory") nil))
                 its   (read-json (api-call :post (str "/api/threads/" child "/rebuild") "{}"))
                 stem  (read-json (api-call :post (str "/api/threads/" thread "/rebuild") "{}"))]
             (testing "the trajectory is its own"
               (is (= child (:threadId mine)))
               (is (seq (:turns mine))))
             (testing "and the rebuild hands back ITS conversation, not its parent's"
               ;; BOTH READERS UNCHANGED: rebuild folds the seed and the frames, and a
               ;; subagent's run records both -- see harness.edge.http/run-subagent!,
               ;; which writes the frames it never sends.
               (is (= child (:threadId its)))
               (is (not= (:messages its) (:messages stem)))
               (is (= "where is the tool execution seam" (:content (first (:messages its))))
                   "the task it was delegated, not the parent's own user message")
               (is (= "go" (:content (first (:messages stem)))))
               (is (contains? (called-names (:messages its)) "read")
                   "its own tool call is in it")
               (is (not (contains? (called-names (:messages stem)) "read"))
                   "and the parent's conversation does not hold it")
               (is (contains? (called-names (:messages stem)) "agent")
                   "while the parent's has the delegation and nothing behind it")))))))))

(deftest a-project-removal-releases-a-subagent-with-its-parent
  ;; The ticket leaves the CHOICE open and requires the choice to be pinned. This is
  ;; it: a removal is a statement about a DIRECTORY, and the schema's own trigger
  ;; unbinds every session that belonged to it in one statement -- the two rows go
  ;; together, and neither is left half-bound. Both conversations still EXIST, which
  ;; is the same promise removal makes to any session, and re-adding the directory
  ;; brings both back (they both remember it).
  (let [thread "dg-removal"]
    (with-project
     thread
     (fn [dir]
       (with-server thread project-script
         (fn []
           (post-run thread)
           (let [child (:thread-id (subagent-log thread))]
             (project/remove-project! (.getCanonicalPath (io/file dir)))
             (let [rows (db/select "SELECT id, project_id, path, parent_id, archived
                                      FROM sessions WHERE id IN (?, ?)" thread child)]
               (is (= 2 (count rows)) "both rows survive the removal")
               (is (every? nil? (map :project-id rows)))
               (is (every? nil? (map :path rows))))
             (testing "archiving the parent leaves the subagent's flag alone"
               ;; Archiving is a statement about ONE conversation, and a subagent's
               ;; record is not the parent's -- there is nothing to archive.
               (project/archive! thread true)
               (is (true? (project/archive! thread true)))
               (is (= 0 (:archived (first (db/select "SELECT archived FROM sessions WHERE id = ?"
                                                     child)))))))))))))
