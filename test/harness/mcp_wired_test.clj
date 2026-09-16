(ns harness.mcp-wired-test
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
            [harness.home :as home]
            [harness.http :as http]
            [harness.mcp :as mcp]
            [harness.providers :as providers]
            [harness.test-support :as support]
            [harness.tools :as tools])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def ^:private fake-server
  (.getAbsolutePath (io/file "test/harness/fake_mcp_server.js")))

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
(use-fixtures :each (fn [f] (wipe!) (f) (wipe!)))

(defn- with-server
  "A live server on an OS-chosen port, with a scripted provider pinned to THREAD.
  The port is never written down: see AGENTS.md."
  [thread turns f]
  (providers/use-provider! thread (fake/scripted turns))
  (let [stop (http/start! {:port 0})
        port (:local-port (meta stop))]
    (try (binding [*port* port] (f))
         (finally (stop) (providers/use-provider! thread nil)))))

(defn- post-run [thread-id]
  (let [body (json/write-str {:threadId thread-id
                              :runId (str (java.util.UUID/randomUUID))
                              :messages [{:id "u1" :role "user" :content "go"}]
                              :tools [] :context []})
        req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* "/")))
                 (.header "Content-Type" "application/json")
                 (.header "Accept" "text/event-stream")
                 (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                 (.build))]
    (.send (HttpClient/newHttpClient) req
           (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))))

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
                  (try (json/read-str l :key-fn keyword) (catch Throwable _ nil)))))
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

(defn- wait-quiet
  "The log's lines, once the file has stopped growing.

  For an assertion about something being ABSENT, waiting for a line that will
  never come is waiting for the timeout -- so the question has to be 'is the
  writer done', and the honest test of that is a file whose size has stopped
  moving."
  [f ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [last-size -1 stable 0]
      (let [size (.length f)]
        (if (and (= size last-size) (>= stable 2))
          (log-lines f)
          (if (> (System/currentTimeMillis) deadline)
            (log-lines f)
            (do (Thread/sleep 50)
                (recur size (if (= size last-size) (inc stable) 0)))))))))

(defn- finished? [ls]
  (some #(= "RUN_FINISHED" (get-in % [:payload :type])) ls))

(defn- of-kind [ls kind] (filterv #(= kind (:kind %)) ls))

(defn- tool-results [ls]
  (->> (of-kind ls "message")
       (map :payload)
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
                         (is (= ["c1"] (mapv #(get-in % [:payload :toolCallId])
                                             (of-kind ls kind)))
                             kind)))
                     (testing "the connection is on the record too"
                       (let [lines (of-kind ls "mcp/server")]
                         (is (= 1 (count lines)))
                         (is (= "fake" (get-in lines [0 :payload :server])))
                         (is (= "connected" (get-in lines [0 :payload :status]))))))))))

(deftest a-server-that-will-not-start-does-not-break-the-run
  (let [thread "wired-broken"]
    (write-servers! {"broken" {:command "definitely-not-a-program-xyz"}
                     "fake" (decl)})
    (with-server thread script
                 (fn []
                   (io/delete-file (log-file thread) true)
                   (post-run thread)
                   (let [dead? (fn [ls]
                                 (some #(= "broken" (get-in % [:payload :server]))
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
                                         (filter #(= "broken" (get-in % [:payload :server])))
                                         first)]
                         (is (some? failed))
                         (is (= "failed" (get-in failed [:payload :status])))
                         (is (seq (get-in failed [:payload :error]))))))))))

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
        (support/write-hooks! {:pre-tool-use [{:command (str gate) :matcher "mcp__fake__"}]})
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
                                  (mapv #(get-in % [:payload :outcome])
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
        (support/write-hooks! {:permission-request [{:command (str rule)}]})
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
