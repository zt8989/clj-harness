(ns harness.cap.mcp-wired-test
  "A tool from an MCP server, through the REAL HTTP edge.

  WHY THIS IS NOT IN harness.mcp-test. Everything below can only be observed in a
  run: the frames a client receives, the jsonl lines a run leaves, and the hooks
  that fire. Hooks in particular only fire when the EDGE has bound the run's sink,
  so a test that called harness.loop directly would prove nothing about `PreToolUse`
  guarding a server call -- which is the ticket's central claim, and it is a claim
  about the EDGE being wired, not about the seam being clever.

  The server itself is the same real process the offline tests use
  (test/harness/fake_mcp_server.js); what changes is that a model is now on the
  other side of the call."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.fake :as fake]
            [harness.infra.home :as home]
            [harness.edge.http :as http]
            [harness.edge.replay :as replay]
            [harness.cap.mcp :as mcp]
            [harness.cap.providers :as providers]
            [harness.test-support :as support]
            [harness.kernel.tools :as tools]
            [harness.wire :as wire])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def ^:private fake-server
  (.getAbsolutePath (io/file "test/harness/cap/fake_mcp_server.js")))

(defn- decl [& [extra]]
  (merge {:command (str "node " fake-server)} extra))

(def ^:private script
  "One turn that calls a server-provided tool, then says it is done."
  [{:content ""
    :tool-calls [{:id "c1" :name "mcp__fake__echo" :arguments {:text "from the model"}}]}
   {:content "done"}])

(def ^:dynamic *port* nil)

(defn- mcp-file [] (io/file (home/root) "mcp.edn"))

(defn- write-servers! [servers]
  (spit (mcp-file) (pr-str {:servers servers}) :encoding "UTF-8"))

(defn- wipe! []
  (io/delete-file (mcp-file) true)
  (mcp/shutdown!))

;; mcp.edn is read for EVERY thread and the connection/state/outbox are
;; process-wide, so a test that leaves any of it behind makes the next one see a
;; server it never declared. Same discipline hooks.edn gets in
;; harness.test-support, and the same reason.
(use-fixtures :once support/with-mcp)

(use-fixtures :each (fn [f] (wipe!) (f) (wipe!)))

(defn- with-server
  "A live server on an OS-chosen port, with a scripted provider pinned to THREAD.
  The port is never written down: see AGENTS.md."
  [thread turns f]
  (providers/use-provider! thread (fake/scripted turns))
  (support/start-session! thread)
  (let [stop (http/start! {:port 0})
        port (:local-port (meta stop))]
    (try (binding [*port* port] (f))
         (finally (stop) (providers/use-provider! thread nil)))))

(defn- post-run
  "A real run for THREAD-ID, read from the DOWNLINK (`harness.test-support/mux-run!`): the POST
  answers an ack and the frames arrive on `events.mux`. What it hands back is the SAME SHAPE it
  always was -- an `HttpResponse` whose body is the run's SSE -- so `interrupt-of` and every
  caller read it unchanged."
  ([thread-id] (post-run thread-id {}))
  ([thread-id extra]
    (let [body (json/write-str (merge {:threadId thread-id
                                      ;; THE ACTION'S OWN ENTRIES (ticket 03): the server holds
                                      ;; the conversation, and `with-server` has made sure this
                                      ;; thread is a session of it.
                                      :append [{:id "u1" :role "user" :content "go"}]
                                      :tools []}
                                     extra))
          result (support/mux-run! *port* thread-id body nil)]
      (reify java.net.http.HttpResponse
        (statusCode [_] (:status result))
        (headers [_] (:headers result))
        (body [_] (:body result))))))

(defn- api-get
  "A management-edge GET, as {:status :body}."
  [path]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* "/" path)))
                (.GET)
                (.build))
        resp (.send (HttpClient/newHttpClient) req
                    (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
    {:status (.statusCode resp) :body (.body resp)}))

(defn- log-file
  "A thread's log. Every thread here is UNBOUND -- it exercises servers and hooks,
  not project bindings -- so its log is in the tree's reserved workspace. Composed
  from harness.home and harness.http/unbound-workspace rather than spelled out, so
  it cannot drift from the writer."
  [thread]
  (home/log-file (io/file (home/projects-dir) http/unbound-workspace) thread))

(defn- log-lines [f]
  ;; A LIVE file: its last line can be half-written, which is a fact about reading
  ;; a log being appended to, not a corrupt log. Skip what does not parse.
  (into []
        (keep (fn [l]
                (when-not (str/blank? l)
                  ;; THROUGH THE RECORD'S OWN READER (`.scratch/jsonl-two-kinds`): a row
                  ;; is `{:type .. :payload ..}`, reading it is what validates it, and
                  ;; `replay/kind` is how an assertion below asks what it is.
                  (try (first (replay/lines->records [l])) (catch Throwable _ nil)))))
        (try (str/split-lines (slurp f :encoding "UTF-8")) (catch Throwable _ []))))

(defn- wait-for
  "The log's lines, once PRED holds over them -- or whatever is there when MS runs
  out.

  WAITING ON THE TERMINAL FRAME IS NOT ENOUGH, and that is the trap this helper
  exists for: RUN_FINISHED is an `event` line, while the tool messages and the
  `mcp/server` lines are written by the consumer AFTER it (they ride :run/done).
  A test that stopped at RUN_FINISHED would read a log that had not been finished
  being written and call the missing lines a bug."
  [f pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [ls (log-lines f)]
        (if (or (pred ls) (> (System/currentTimeMillis) deadline))
          ls
          (do (Thread/sleep 50) (recur)))))))

(defn- wait-marker
  "The MARKER file's text, once PRED holds over it -- or what it says when MS runs
  out.

  `wait-for` IS FOR THE LOG, and this is the same wait on the other file. The marker
  is written by the hook's own CHILD PROCESS, so it is the last thing to arrive:
  there is a window in which the run's log already says everything and this file is
  still a line short, and reading it once inside that window reports a hook that ran
  as a hook that said nothing. The deadline is the ENGINE's own bound for a hook
  (dispatch/default-timeout-ms), because a command is allowed to take that long."
  [f pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [text (try (slurp f :encoding "UTF-8") (catch Throwable _ ""))]
        (if (or (pred text) (> (System/currentTimeMillis) deadline))
          text
          (do (Thread/sleep 50) (recur)))))))

(defn- wait-quiet
  "The log's lines, once the file has stopped growing.

  For an assertion about something being ABSENT, waiting for a line that will
  never come is waiting for the timeout -- so the question has to be 'is the
  writer done', and the honest test of that is a file whose size has stopped
  moving.
  
  A FILE THAT IS NOT THERE HAS NOT STOPPED GROWING -- it has not started. `File.length`
  on a missing file is 0 and STABLE, so the old reading declared the writer done before
  its first line landed and the caller's own `slurp` threw FileNotFoundException. It
  flaked exactly there (measured 2026-09-23: the same suite green on one run and red on
  the next, nothing but timing between them), so absence is now waited out."
  [f ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [last-size -1 stable 0]
      (let [size (if (.exists f) (.length f) -2)]
        (cond
          (= size -2) (if (> (System/currentTimeMillis) deadline)
                        (log-lines f)
                        (do (Thread/sleep 50) (recur size 0)))
          (and (= size last-size) (>= stable 2)) (log-lines f)
          (> (System/currentTimeMillis) deadline) (log-lines f)
          :else (do (Thread/sleep 50)
                    (recur size (if (= size last-size) (inc stable) 0))))))))

(defn- finished? [ls]
  (some #(= "RUN_FINISHED" (get-in (replay/payload %) [:type])) ls))

(defn- of-kind [ls kind] (filterv #(= kind (replay/kind %)) ls))

(defn- tool-results [ls]
  (->> (of-kind ls "message")
       (map replay/payload)
       (filterv #(= "tool" (:role %)))
       (mapv :content)))

(defn- ran-server-tool? [ls]
  (some #(str/includes? % "echo: from the model") (tool-results ls)))

;; ------------------------------------------------------ a server's tool, a run

(deftest a-run-sees-a-servers-tools-and-calls-one
  (let [thread "wired-call"]
    (write-servers! {"fake" (decl)})
    (with-server thread script
                 (fn []
                   (io/delete-file (log-file thread) true)
                   (post-run thread)
                   (let [ls (wait-for (log-file thread) ran-server-tool? 5000)]
                     (testing "the call ran and the server's text is the tool result"
                       (is (ran-server-tool? ls)))
                     (testing "and it went through the ONE execution seam, so all
                               three lifecycle lines are there like any built-in's"
                       (doseq [kind ["tools/pre-execute" "tools/execute" "tools/post-execute"]]
                         (is (= ["c1"] (mapv #(get-in (replay/payload %) [:toolCallId])
                                             (of-kind ls kind)))
                             kind)))
                     (testing "the connection is on the record too"
                       (let [lines (of-kind ls "mcp/server")]
                         (is (= 1 (count lines)))
                         (is (= "fake" (:server (replay/payload (first lines)))))
                         (is (= "connected" (:status (replay/payload (first lines))))))))))))

(deftest a-server-that-will-not-start-does-not-break-the-run
  (let [thread "wired-broken"]
    (write-servers! {"broken" {:command "definitely-not-a-program-xyz"}
                     "fake" (decl)})
    (with-server thread script
                 (fn []
                   (io/delete-file (log-file thread) true)
                   (post-run thread)
                   (let [dead? (fn [ls]
                                 (some #(= "broken" (get-in (replay/payload %) [:server]))
                                       (of-kind ls "mcp/server")))
                         ;; BOTH, because they are written at different moments:
                         ;; the mcp line rides :run/done and the tool MESSAGE is
                         ;; written after it. Waiting on the first alone reads a
                         ;; log whose last line has not been written yet.
                         ls    (wait-for (log-file thread)
                                         #(and (dead? %) (ran-server-tool? %))
                                         5000)]
                     (testing "the run finished normally"
                       (is (finished? ls)))
                     (testing "the working server still answered"
                       (is (ran-server-tool? ls)))
                     (testing "and the dead one is NAMED, so its missing tools have an answer"
                       (let [failed (->> (of-kind ls "mcp/server")
                                         (filter #(= "broken" (get-in (replay/payload %) [:server])))
                                         first)]
                         (is (some? failed))
                         (is (= "failed" (get-in (replay/payload failed) [:status])))
                         (is (seq (get-in (replay/payload failed) [:error]))))))))))

;; ------------------------------------------- the same guards, one table over

(deftest a-pretooluse-gate-can-refuse-a-server-call
  ;; The ticket's central claim, tested at the only level where it can be: the
  ;; hook engine only fires when the EDGE has bound the run's sink.
  (let [thread "wired-gate"]
    (write-servers! {"fake" (decl)})
    (let [dir (str (home/root) "/hook-scripts")]
      (.mkdirs (io/file dir))
      (let [gate (io/file dir "gate-mcp.sh")]
        (spit gate (str "#!/bin/sh\necho \"not that server, not today\" 1>&2\nexit 2\n")
              :encoding "UTF-8")
        (.setExecutable gate true)
        ;; A hook's :command is shell text, so its path is spelled for the shell --
        ;; see harness.test-support/shell-path.
        (support/write-hooks! {:pre-tool-use [{:command (support/shell-path gate)
                                               :matcher "mcp__fake__"}]})
        (with-server thread script
                     (fn []
                       (io/delete-file (log-file thread) true)
                       (post-run thread)
                       (let [blocked? (fn [ls]
                                        (some #(str/includes? % "blocked by a PreToolUse hook")
                                              (tool-results ls)))
                             ls       (wait-for (log-file thread) blocked? 5000)]
                         (testing "the call did not run"
                           (is (not (ran-server-tool? ls))))
                         (testing "and the model is told a rule refused it, in the
                                   hook's own words"
                           (is (some #(and (str/includes? % "blocked by a PreToolUse hook")
                                           (str/includes? % "not that server, not today"))
                                     (tool-results ls))))
                         (testing "the seam recorded it as a hook block"
                           (is (= ["hook-blocked"]
                                  (mapv #(get-in (replay/payload %) [:outcome])
                                        (of-kind ls "tools/pre-execute")))))
                         (support/wipe-hooks!))))))))

(deftest a-permission-request-hook-can-answer-for-a-server-call
  (let [thread "wired-delegate"]
    (write-servers! {"fake" (decl)})
    (let [dir (str (home/root) "/hook-scripts")]
      (.mkdirs (io/file dir))
      (let [rule (io/file dir "approve-mcp.sh")]
        (spit rule (str "#!/bin/sh\necho '{\"decision\":\"approve\"}'\n") :encoding "UTF-8")
        (.setExecutable rule true)
        ;; The session asks for approval on the tool, and a RULE answers instead
        ;; of a person -- which is the point of the delegation.
        (tools/session-require-approval! thread "mcp__fake__echo")
        ;; A hook's :command is shell text, so its path is spelled for the shell --
        ;; see harness.test-support/shell-path.
        (support/write-hooks! {:permission-request [{:command (support/shell-path rule)}]})
        (with-server thread script
                     (fn []
                       (io/delete-file (log-file thread) true)
                       (post-run thread)
                       (let [ls (wait-for (log-file thread) ran-server-tool? 5000)]
                         (testing "the call ran on the rule's say-so, without a human"
                           (is (ran-server-tool? ls)))
                         (support/wipe-hooks!))))))))

;; ------------------------------------------------------------------- secrecy

(deftest a-servers-environment-never-reaches-the-log
  (let [thread "wired-secret"
        sentinel "SENTINEL-4c1f-do-not-log-me"]
    (write-servers! {"fake" (decl {:env {"FAKE_TOKEN" sentinel}})})
    (with-server thread script
                 (fn []
                   (let [f (log-file thread)]
                     (io/delete-file f true)
                     (post-run thread)
                     ;; Wait for the WRITER, not for a line: the assertion is
                     ;; about something being absent, so the only honest moment
                     ;; to read is when the file has stopped growing.
                     (wait-quiet f 5000)
                     ;; The whole FILE, not one field: a value that may not be
                     ;; logged must be nowhere in it, however it got there.
                     (is (not (str/includes? (slurp f :encoding "UTF-8") sentinel))))))))

;; ------------------------------------------------------------- the regression

(deftest a-session-that-declares-no-server-leaves-no-mcp-line
  (let [thread "wired-none"]
    (with-server thread
                 [{:content "no tools needed"}]
                 (fn []
                   (io/delete-file (log-file thread) true)
                   (post-run thread)
                   (let [ls (wait-quiet (log-file thread) 5000)]
                     (testing "the run is a normal run"
                       (is (finished? ls)))
                     (testing "and no server was ever mentioned -- no declarations
                               means this capability leaves no trace at all"
                       (is (= [] (of-kind ls "mcp/server")))))))))

;; ------------------------------------- 04: a server asks, through the real edge

(defn- record-script
  "A hook command that appends its stdin payload to MARKER. Used for the two
  elicitation points, whose payload is the thing worth asserting on.

  BOTH PATHS ARE SPELLED FOR THE SHELL -- the command and the marker the script
  redirects to -- because both are read by the shell the hook engine spawns. See
  harness.test-support/shell-path."
  [marker label]
  (let [dir (str (home/root) "/hook-scripts")]
    (.mkdirs (io/file dir))
    (let [f (io/file dir (str label ".sh"))]
      (spit f (str "#!/bin/sh\n{ echo \"--- " label "\"; cat; } >> "
                   (support/shell-path marker) "\n")
            :encoding "UTF-8")
      (.setExecutable f true)
      (support/shell-path f))))

(defn- interrupt-of
  "The interrupts a run ended on, read off the real wire -- not out of the
  server's own bookkeeping, which is the difference between checking the protocol
  and checking our opinion of it."
  [response]
  (->> (wire/frames-from-sse (.body response))
       (filter #(= "RUN_FINISHED" (:type %)))
       (keep #(get-in % [:outcome :interrupts]))
       (first)))

(defn- ask-script [message]
  [{:content ""
    :tool-calls [{:id "q1" :name "mcp__fake__ask"
                  :arguments {:message message
                              :schema {:type "object"
                                       :properties {"name" {:type "string"}}}}}]}
   {:content "thanks"}])

(deftest a-servers-question-parks-the-run-and-the-answer-finishes-it
  (let [thread "wired-ask"
        marker (str (home/root) "/elicitation-hooks.txt")]
    (write-servers! {"fake" (decl)})
    (io/delete-file marker true)
    ;; THE ENGINE'S DEFAULT BUDGET FOR A HOOK IS 10s (`dispatch/default-timeout-ms`), AND THAT
    ;; IS TOO TIGHT HERE. A hook's :command is spawned through the login shell like every
    ;; other command, which costs 2.2s before it even starts on the machine this was measured
    ;; on (2026-09-23; the same measurement `harness.infra.shell-test` carries), and under the
    ;; load of a full run this case was seen timing out -- `:timeout true` and no exit code --
    ;; while it passes on its own. The generous budget is THIS CASE's, not the engine's: what a
    ;; default lets a hung hook hold up is a product decision, and this is only a test that
    ;; wants its own record before it reads it.
    (support/write-hooks! {:elicitation        [{:command (record-script marker "elicitation")
                                                 :timeout 30000}]
                           :elicitation-result [{:command (record-script marker "elicitation-result")
                                                 :timeout 30000}]})
    (with-server thread (ask-script "What is your name?")
                 (fn []
                   (io/delete-file (log-file thread) true)
                   (let [ints (interrupt-of (post-run thread))]
                     (testing "the run ended on a QUESTION, and the frame says which kind"
                       (is (some? ints))
                       (is (= 1 (count ints)))
                       (is (= "elicitation" (:reason (first ints)))))
                     (testing "the human-facing line is the question itself"
                       (is (= "What is your name?" (:message (first ints)))))
                     (testing "and the SCHEMA is not on the wire -- it is fetched from
                               the harness's own edge, because the interrupt's shape
                               belongs to AG-UI and is strictly validated"
                       (is (not (contains? (first ints) :schema)))
                       (let [id (:id (first ints))]
                         (is (some? id))
                         (let [answer (api-get (str "api/elicitation?interruptId=" id))
                               body   (json/read-str (:body answer) :key-fn keyword)]
                           (is (= 200 (:status answer)))
                           (is (= "fake" (:server body)))
                           (is (= "What is your name?" (:prompt body)))
                           (is (contains? (:schema body) :properties)))))
                     (testing "an id nobody parked is a named 404, not an empty form"
                       (is (= 404 (:status (api-get "api/elicitation?interruptId=nope")))))
                     (testing "the answer goes back and the call finishes"
                       (let [id (:id (first ints))
                             resumed (post-run thread
                                               {:resume [{:interruptId id :status "resolved"
                                                          :payload {"name" "Ada"}}]})]
                         (is (= "RUN_FINISHED"
                                (:type (last (wire/frames-from-sse (.body resumed))))))
                         (let [ls (wait-for (log-file thread)
                                            #(some (fn [c] (str/includes? c "Ada"))
                                                   (tool-results %))
                                            5000)]
                           (is (some #(str/includes? % "Ada") (tool-results ls))
                               "the server was told the values, and said so back"))))
                     (testing "and both elicitation points FIRED, with the server
                               named in the payload the command was handed"
                       (let [ls (wait-quiet (log-file thread) 3000)]
                         ;; TWICE, and that is the price this design knowingly
                         ;; pays: the answer arrives on a LATER run, the call is
                         ;; issued again, and the server asks again -- the second
                         ;; time being the one the recorded answer satisfies. A
                         ;; count of one here would mean the tool was never
                         ;; re-issued, i.e. that the resume did something else.
                         (is (= 2 (count (of-kind ls "hook/Elicitation"))))
                         (testing "and the ANSWER was reported once, before it went back"
                           (let [fired (of-kind ls "hook/ElicitationResult")]
                             (is (= 1 (count fired)))
                             ;; A COUNT IS NOT THE WHOLE CLAIM: the audit line lands
                             ;; even for a command the engine could not run to
                             ;; completion -- it carries `:exit nil` and the reason --
                             ;; so a hook that never appended would still be counted,
                             ;; and the missing line below would read as a lost file
                             ;; rather than as a hook that timed out. `exit 0` is the
                             ;; engine's own record that the command ran and allowed.
                             (is (= [0] (mapv :exit (mapcat (comp :results replay/payload) fired)))
                                 (str "the result hook's own outcome: " (pr-str fired)))))
                         ;; WAITED FOR, NOT READ ONCE -- see `wait-marker`: the block
                         ;; above reads the LOG, which the run's own thread finishes
                         ;; writing first, and this file is written by the hook's shell.
                         (let [seen (wait-marker marker #(str/includes? % "Ada") 10000)]
                           (is (str/includes? seen "--- elicitation"))
                           (is (str/includes? seen "--- elicitation-result"))
                           (is (str/includes? seen "\"server\":\"fake\""))
                           (is (str/includes? seen "What is your name?"))
                           (is (str/includes? seen "Ada")))))
                     (support/wipe-hooks!))))))

(deftest a-run-with-no-question-is-untouched-by-any-of-this
  (let [thread "wired-no-ask"]
    (write-servers! {"fake" (decl)})
    (with-server thread script
                 (fn []
                   (io/delete-file (log-file thread) true)
                   (let [ints (interrupt-of (post-run thread))
                         ls   (wait-for (log-file thread) ran-server-tool? 5000)]
                     (testing "an ordinary server call ends the run normally"
                       (is (nil? ints))
                       (is (finished? ls)))
                     (testing "and no elicitation point fired"
                       (is (= [] (of-kind ls "hook/Elicitation")))))))))

;; ------------------------------------------- 05: the ledger and the switch, over HTTP

(defn- api-post [path body]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* "/" path)))
                (.header "Content-Type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString
                        (if (string? body) body (json/write-str body))
                        StandardCharsets/UTF_8))
                (.build))
        resp (.send (HttpClient/newHttpClient) req
                    (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
    {:status (.statusCode resp) :body (.body resp)}))

(deftest the-mcp-ledger-answers-over-http-and-leaves-no-trace
  (let [thread "wired-ledger"]
    (write-servers! {"fake" (decl)})
    (with-server thread (ask-script "anything")
                 (fn []
                   (let [f (log-file thread)]
                     (io/delete-file f true)
                     (post-run thread)
                     (wait-quiet f 4000)
                     (let [before (count (log-lines f))
                           answer (api-get (str "api/mcp?threadId=" thread))
                           body   (json/read-str (:body answer) :key-fn keyword)]
                       (testing "an unbound session's declaration is answered, not refused"
                         (is (= 200 (:status answer)))
                         (is (= thread (:threadId body)))
                         (is (= 1 (count (:servers body)))))
                       (let [server (first (:servers body))]
                         (testing "what a person diagnosing this needs to know"
                           (is (= "fake" (:server server)))
                           (is (= "stdio" (:transport server)))
                           (is (= "connected" (name (:status server))))
                           (is (some #(= "mcp__fake__echo" (:name %)) (:tools server))))
                         (testing "and nothing about a connection, an env, or a command"
                           (is (nil? (:command server)))
                           (is (nil? (:env server)))))
                       (testing "READ-ONLY: asking left no audit line"
                         (is (= before (count (log-lines f)))))))))))

(deftest the-switch-over-http-changes-the-session-and-leaves-a-line
  (let [thread "wired-switch"]
    (write-servers! {"fake" (decl)})
    (with-server thread (ask-script "anything")
                 (fn []
                   (let [f (log-file thread)]
                     (io/delete-file f true)
                     (post-run thread)
                     (wait-quiet f 4000)
                     (testing "OFF: the tool is still in the model's toolset and a call is refused"
                       (let [off (api-post "api/mcp" {:threadId thread :server "fake" :enabled false})]
                         (is (= 200 (:status off)))
                         (let [answer (api-get (str "api/mcp?threadId=" thread))
                               server (first (:servers (json/read-str (:body answer)
                                                                    :key-fn keyword)))]
                           (is (= "disabled" (name (:status server))))
                           (is (some #(= "mcp__fake__echo" (:name %)) (:tools server))
                               "still listed: off is not hidden")))
                       (testing "the switch landed an mcp/server line, runId null"
                         (let [lines (of-kind (wait-quiet f 3000) "mcp/server")
                               last-line (last (filter #(= "fake" (get-in (replay/payload %) [:server]))
                                                       lines))]
                           (is (some? last-line))
                           (is (true? (get-in (replay/payload last-line) [:disabled])))
                           (is (nil? (:runId last-line))))))
                     (testing "ON: it connects again and its tools run"
                       (is (= 200 (:status (api-post "api/mcp" {:threadId thread
                                                                :server "fake" :enabled true}))))
                       (let [answer (api-get (str "api/mcp?threadId=" thread))
                             server (first (:servers (json/read-str (:body answer)
                                                                   :key-fn keyword)))]
                         (is (= "connected" (name (:status server))))))
                     (testing "a server this session does not declare is a named 404"
                       (let [nope (api-post "api/mcp" {:threadId thread :server "ghost"
                                                       :enabled false})]
                         (is (= 404 (:status nope)))
                         (is (str/includes? (:body nope) "ghost"))))
                     (testing "and a body that makes no sense is a named 400, not a guess"
                       (is (= 400 (:status (api-post "api/mcp" {:threadId thread :server "fake"}))))
                       (is (= 400 (:status (api-post "api/mcp" {:server "fake" :enabled true}))))
                       (is (= 400 (:status (api-post "api/mcp" "not json"))))))))))
