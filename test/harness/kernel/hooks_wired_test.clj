(ns harness.kernel.hooks-wired-test
  "The hook points that are wired, through the real HTTP edge: SessionStart on a
  session's first run, PostToolUse after a successful tool, Stop when a run ends
  normally, InstructionsLoaded once per instruction file folded, and SystemPrompt
  while the system message is assembled -- plus the regression that matters most,
  that a session which DECLARES nothing fires nothing but the kernel's own rows.

  The edge is the layer that has to be exercised here, because it is the edge that
  binds the run's hook sink: everything below it (offline tools, replay) fires
  nothing by design, and a test that only called harness.kernel.loop would prove nothing
  about the wiring."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.fake :as fake]
            [harness.infra.home :as home]
            [harness.edge.http :as http]
            [harness.cap.providers :as providers]
            [harness.cap.project :as project]
            [harness.test-support :as support]
            [harness.kernel.tools :as tools]
            [harness.wire :as wire])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def ^:private script
  [{:content ""
    :tool-calls [{:id "c1" :name "read" :arguments {:path "deps.edn"}}]}
   {:content "done"}])

(def ^:dynamic *port*
  "The port the server under test is listening on, bound by `with-server`.

  THE OS PICKS IT (`{:port 0}`), so no test names a port and two runs on one
  machine -- a session beside a suite, a leftover e2e server, two worktrees --
  cannot collide; see AGENTS.md."
  nil)

(defn- with-server [thread f]
  (providers/use-provider! thread (fake/scripted script))
  (support/start-session! thread)
  (let [stop (http/start! {:port 0})
        port (:local-port (meta stop))]
    (try (binding [*port* port] (f))
         (finally (stop) (providers/use-provider! thread nil)))))

(defn- post-run [thread-id]
  (let [body (json/write-str {:threadId thread-id
                              ;; THE ACTION'S OWN ENTRIES (ticket 03): the server
                              ;; holds the conversation, and `with-server` has made sure
                              ;; this thread is a session of it.
                              :append [{:id "u1" :role "user" :content "go"}]
                              :tools []})
        req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* "/api/agent")))
                 (.header "Content-Type" "application/json")
                 (.header "Accept" "text/event-stream")
                 (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                 (.build))]
    (.send (HttpClient/newHttpClient) req
           (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))))

(defn- log-file
  "A thread's log, in the tree's reserved workspace.

  Every thread this namespace runs is UNBOUND -- it exercises hooks, not project
  bindings -- so that is where their logs land. The directory is composed from
  harness.infra.home and harness.edge.http/unbound-workspace rather than spelled out, so it
  cannot drift from the writer."
  [thread]
  (home/log-file (io/file (home/projects-dir) http/unbound-workspace) thread))

(defn- log-file-for
  "The same question for a thread that IS bound: its log lives in the workspace
  its project owns, so the reserved workspace is the wrong answer and the
  difference is exactly what the instruction-file tests are checking. Asked the
  way the writer asks it -- the session's project identity, sanitized -- rather
  than composed by hand, so a change to the naming rule fails here instead of
  agreeing with itself."
  [thread]
  (home/log-file (io/file (home/projects-dir)
                          (if-let [identity (project/identity-for thread)]
                            (home/sanitize identity)
                            http/unbound-workspace))
                 thread))

(defn- log-lines [f]
  ;; A LIVE file: its last line can be half-written, and that is a fact about
  ;; reading a log being appended to, not a corrupt log. Skip what does not parse.
  (into []
        (keep (fn [l]
                (when-not (str/blank? l)
                  (try (json/read-str l :key-fn keyword) (catch Throwable _ nil)))))
        (try (str/split-lines (slurp f :encoding "UTF-8")) (catch Throwable _ []))))

(defn- wait-for [f pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [ls (log-lines f)]
        (if (or (pred ls) (> (System/currentTimeMillis) deadline))
          ls
          (do (Thread/sleep 50) (recur)))))))

(defn- hook-lines [ls]
  (filter #(str/starts-with? (str (:kind %)) "hook/") ls))

(defn- wipe! []
  (support/wipe-hooks!)
  (io/delete-file (io/file (home/root) "hooks-fired.txt") true))

;; A hooks.edn this namespace wrote is a hook EVERY LATER TEST IN THE PROCESS
;; would fire -- including harness.edge.http-test's parked-call test, which would find
;; its call answered by a rule it never declared. So both levels are wiped around
;; every test, the same discipline project_test applies to harness.edn.
(use-fixtures :each (fn [f] (wipe!) (f) (wipe!)))

(defn- marker-script [marker label]
  (let [dir (str (home/root) "/hook-scripts")]
    (.mkdirs (io/file dir))
    (let [f (io/file dir (str label ".sh"))
          m (support/shell-path marker)]
      ;; BOTH PATHS ARE SPELLED FOR THE SHELL: the command the hook engine hands
      ;; to it, and the marker the script itself writes to. See
      ;; harness.test-support/shell-path for why a Windows path cannot be used raw.
      (spit f (str "#!/bin/sh\ncat >> " m "\necho \"" label "\" >> " m "\n") :encoding "UTF-8")
      (.setExecutable f true)
      (support/shell-path f))))

;; --------------------------------------------------- nothing declared, nothing

(deftest a-session-that-declares-nothing-fires-only-the-kernels-own-rows
  ;; The regression that matters most, restated for a table that now holds the
  ;; kernel's own rows: a session which DECLARES nothing leaves the declaration
  ;; points silent, and the one hook line a run leaves is the SystemPrompt row the
  ;; kernel registered for itself -- because that row appends text to the system
  ;; message on every run, and a trigger that did work leaves a line.
  (wipe!)
  (with-server
   "hw-none"
   (fn []
     (io/delete-file (log-file "hw-none") true)
     (post-run "hw-none")
     (let [_  (wait-for (log-file "hw-none")
                        (fn [ls] (some #(= "RUN_FINISHED" (get-in % [:payload :type])) ls))
                        1500)
           ;; Stop fires as the run ends and the returned message tail lands one
           ;; beat after the terminal frame, so the run is given a moment to finish
           ;; writing before the claim "and nothing else fired" is made. Same shape
           ;; as the second-run check in session-start-fires-once below.
           _  (Thread/sleep 300)
           ls (log-lines (log-file "hw-none"))]
       (testing "the only hook line is the kernel's own SystemPrompt trigger"
         (is (= ["hook/SystemPrompt"] (mapv :kind (hook-lines ls)))))
       (testing "and every point a session would have to declare at is silent"
         (is (empty? (filter #(contains? #{"hook/SessionStart" "hook/PostToolUse"
                                           "hook/Stop" "hook/InstructionsLoaded"}
                                         (:kind %))
                             ls))))
       (testing "the run itself is complete and well-formed"
         (is (some #(= "RUN_FINISHED" (get-in % [:payload :type])) ls)))))))

;; ------------------------------------------------------------------ SessionStart

(deftest session-start-fires-once-on-a-sessions-first-run
  (wipe!)
  (support/write-hooks!
   {:session-start [{:command (marker-script (str (home/root) "/hooks-fired.txt")
                                             "session-start")}]})
  (with-server
   "hw-start"
   (fn []
     (io/delete-file (log-file "hw-start") true)
     (post-run "hw-start")
     (let [ls (wait-for (log-file "hw-start")
                        (fn [ls] (some #(= "hook/SessionStart" (:kind %)) ls))
                        1500)
           starts (filter #(= "hook/SessionStart" (:kind %)) ls)]
       (testing "the audit line names the point the way the payload does"
         (is (= 1 (count starts))))
       (testing "one declaration matched, and it was allowed"
         (is (= 1 (get-in (first starts) [:payload :matched])))
         (is (= "allow" (get-in (first starts) [:payload :verdict]))))
       (testing "the command really ran"
         (is (str/includes? (slurp (str (home/root) "/hooks-fired.txt")) "session-start")))
       (testing "a SECOND run of the same thread does not repeat it"
         (post-run "hw-start")
         (Thread/sleep 600)
         (let [ls2 (log-lines (log-file "hw-start"))]
           (is (= 1 (count (filter #(= "hook/SessionStart" (:kind %)) ls2))))))))))

;; ------------------------------------------------------------------ PostToolUse

(deftest post-tool-use-fires-after-a-tool-ran-and-is-told-which-one
  (wipe!)
  (let [marker (str (home/root) "/hooks-fired.txt")]
    (support/write-hooks! {:post-tool-use [{:command (marker-script marker "post-tool-use")
                                            :matcher "read"}]})
    (with-server
     "hw-post"
     (fn []
       (io/delete-file (log-file "hw-post") true)
       (post-run "hw-post")
       (let [ls (wait-for (log-file "hw-post")
                          (fn [ls] (some #(= "hook/PostToolUse" (:kind %)) ls))
                          1500)
             line (first (filter #(= "hook/PostToolUse" (:kind %)) ls))]
         (testing "the point fired once, for the one matching call"
           (is (some? line))
           (is (= 1 (get-in line [:payload :matched]))))
         (testing "and the hook received the tool name and its arguments on stdin"
           (let [fired (slurp marker)]
             (is (str/includes? fired "\"tool_name\":\"read\""))
             (is (str/includes? fired "\"hook\":\"PostToolUse\""))
             (is (str/includes? fired "deps.edn") "the arguments came through too"))))))))

(deftest a-matcher-that-does-not-fit-means-the-hook-never-sees-that-call
  (wipe!)
  (let [marker (str (home/root) "/hooks-fired.txt")]
    (support/write-hooks! {:post-tool-use [{:command (marker-script marker "post-tool-use")
                                            :matcher "bash"}]})
    (with-server
     "hw-nomatch"
     (fn []
       (io/delete-file (log-file "hw-nomatch") true)
       (post-run "hw-nomatch")
       (let [ls (wait-for (log-file "hw-nomatch")
                          (fn [ls] (some #(= "hook/Stop" (:kind %)) ls))
                          1500)]
         (testing "no PostToolUse line: the call was not selected"
           (is (empty? (filter #(= "hook/PostToolUse" (:kind %)) ls))))
         (testing "and the command never ran"
           (is (not (.exists (io/file marker))))))))))

;; ------------------------------------------------------------------------- Stop

(deftest stop-fires-when-a-run-ends-normally
  (wipe!)
  (let [marker (str (home/root) "/hooks-fired.txt")]
    (support/write-hooks! {:stop [{:command (marker-script marker "stop")}]})
    (with-server
     "hw-stop"
     (fn []
       (io/delete-file (log-file "hw-stop") true)
       (post-run "hw-stop")
       (let [ls (wait-for (log-file "hw-stop")
                          (fn [ls] (some #(= "hook/Stop" (:kind %)) ls))
                          1500)
             line (first (filter #(= "hook/Stop" (:kind %)) ls))]
         (testing "the line is there and the command ran"
           (is (some? line))
           (is (str/includes? (slurp marker) "stop")))
         (testing "Stop carries no tool facts -- it is about the run, not a call"
           (is (not (str/includes? (slurp marker) "tool_name")))))))))

;; --------------------------------------------------------- observers change nothing

(deftest none-of-the-three-points-changes-the-runs-frames
  ;; Two threads, one run each, so both cases get a fresh script and neither is
  ;; reading a log the other wrote. The claim is about OBSERVERS: their verdict is
  ;; discarded, so the run they watch is unchanged.
  (wipe!)
  (support/write-hooks!
   {:session-start [{:command (marker-script (str (home/root) "/hooks-fired.txt")
                                             "start")}]
    :stop          [{:command (marker-script (str (home/root) "/hooks-fired.txt")
                                             "stop")}]})
  (providers/use-provider! "hw-frames-off" (fake/scripted script))
  ;; THE SECOND THREAD IS A SESSION TOO: this case compares two runs, and the one that is
  ;; not the fixture's own thread has to exist before it can run (ticket 03 refuses a run
  ;; of an id the store has never heard of).
  (support/start-session! "hw-frames-off")
  (with-server
   "hw-frames-on"
   (fn []
     (let [with-hooks (wire/frames-from-sse
                       (.body (post-run "hw-frames-on")))]
       (wipe!)
       (let [without (try
                       (wire/frames-from-sse (.body (post-run "hw-frames-off")))
                       (finally (providers/use-provider! "hw-frames-off" nil)))]
         (testing "an observer's verdict is not the run's: same frame TYPES either way"
           (is (= (map :type with-hooks) (map :type without))))
         (testing "and both are structurally valid runs"
           (is (empty? (wire/violations with-hooks)))
           (is (empty? (wire/violations without)))))))))

;; -------------------------------------------------------- PreToolUse, the gate

(defn- gate-script [exit reason]
  (let [dir (str (home/root) "/hook-scripts")]
    (.mkdirs (io/file dir))
    (let [f (io/file dir (str "gate-" exit ".sh"))]
      (spit f (str "#!/bin/sh\ncat > /dev/null\necho \"" reason "\" >&2\nexit " exit "\n")
            :encoding "UTF-8")
      (.setExecutable f true)
      ;; The command is shell text, so its path is spelled for the shell.
      (support/shell-path f))))

(deftest a-pretooluse-gate-can-refuse-a-call-and-the-model-is-told-why
  (wipe!)
  (support/write-hooks! {:pre-tool-use [{:command (gate-script 2 "no reads before breakfast")}]})
  (with-server
   "hw-gate"
   (fn []
     (io/delete-file (log-file "hw-gate") true)
     (let [resp (post-run "hw-gate")
           frames (wire/frames-from-sse (.body resp))
           results (filter #(= "TOOL_CALL_RESULT" (:type %)) frames)]
       (testing "the call is refused, and the REFUSAL is what the model reads"
         (is (= 1 (count results)))
         (is (str/includes? (:content (first results)) "blocked by a PreToolUse hook"))
         (is (str/includes? (:content (first results)) "no reads before breakfast")))
       (testing "the run is NOT a failure -- it carries on and ends normally"
         (is (= "RUN_FINISHED" (:type (last frames))))
         (is (empty? (wire/violations frames))))
       (testing "the tool never ran: no :tool/execute for that call"
         (let [ls (wait-for (log-file "hw-gate")
                            (fn [ls] (some #(= "hook/PreToolUse" (:kind %)) ls))
                            1500)]
           (is (some? (first (filter #(= "hook/PreToolUse" (:kind %)) ls))))
           (is (empty? (filter #(and (= "tools/execute" (:kind %))
                                     (= "c1" (get-in % [:payload :toolCallId])))
                               ls))
               "a blocked call is never executed, so it leaves no execute line")))))))

(deftest a-gate-that-allows-changes-nothing-about-the-run
  (wipe!)
  (support/write-hooks! {:pre-tool-use [{:command (gate-script 0 "fine")}]})
  (with-server
   "hw-allow"
   (fn []
     (io/delete-file (log-file "hw-allow") true)
     (let [frames (wire/frames-from-sse (.body (post-run "hw-allow")))
           results (filter #(= "TOOL_CALL_RESULT" (:type %)) frames)]
       (testing "the tool really ran, with its real output"
         (is (= 1 (count results)))
         (is (str/includes? (:content (first results)) ":paths")))
       (testing "and the run is well-formed end to end"
         (is (= "RUN_STARTED" (:type (first frames))))
         (is (= "RUN_FINISHED" (:type (last frames))))
         (is (empty? (wire/violations frames))))))))

(deftest the-verdict-is-audited-with-the-point-and-the-outcome
  (wipe!)
  (support/write-hooks! {:pre-tool-use [{:command (gate-script 2 "denied")}]})
  (with-server
   "hw-audit"
   (fn []
     (io/delete-file (log-file "hw-audit") true)
     (post-run "hw-audit")
     (let [ls (wait-for (log-file "hw-audit")
                        (fn [ls] (some #(= "hook/PreToolUse" (:kind %)) ls))
                        1500)
           hook-line (first (filter #(= "hook/PreToolUse" (:kind %)) ls))
           pre (first (filter #(and (= "tools/pre-execute" (:kind %))
                                    (= "c1" (get-in % [:payload :toolCallId])))
                              ls))]
       (testing "the hook line carries the point, the count and the folded verdict"
         (is (= 1 (get-in hook-line [:payload :matched])))
         (is (= "block" (get-in hook-line [:payload :verdict])))
         (is (= "denied" (get-in hook-line [:payload :reason]))))
       (testing "and the seam says hook-blocked -- a NEW outcome, documented, not an unknown"
         (is (= "hook-blocked" (get-in pre [:payload :outcome]))))))))

(deftest a-disabled-tool-is-refused-without-asking-the-gate
  ;; The ordering claim: "switched off" has to mean no work happens, so the gate
  ;; must not be spawned for a call that can never run.
  (wipe!)
  (let [marker (str (home/root) "/hooks-fired.txt")]
    (support/write-hooks! {:pre-tool-use [{:command (marker-script marker "gate")}]})
    (try
      (with-server
       "hw-disabled"
       (fn []
         ;; SWITCHED OFF AFTER THE CAPABILITY IS INSTALLED, and that order is now
         ;; load-bearing: `session-disable!` only marks a name the table actually
         ;; offers, and the table arrives with the server (see
         ;; harness.kernel.tools/session-disable!). Switching it off out here --
         ;; before any install -- would switch nothing at all.
         (tools/session-disable! "hw-disabled" "read")
         (io/delete-file (log-file "hw-disabled") true)
         (post-run "hw-disabled")
         (let [ls (wait-for (log-file "hw-disabled")
                            (fn [ls] (some #(= "hook/Stop" (:kind %)) ls))
                            1500)]
           (testing "the call is refused as disabled"
             (let [pre (first (filter #(= "tools/pre-execute" (:kind %)) ls))]
               (is (= "disabled" (get-in pre [:payload :outcome])))))
           (testing "and the gate never ran -- not even its audit line"
             (is (empty? (filter #(= "hook/PreToolUse" (:kind %)) ls)))
             (is (not (.exists (io/file marker))))))))
      (finally (tools/session-enable! "hw-disabled" "read")))))

;; ------------------------------- PermissionRequest: a rule answering the park

(defn- answer-script [decision reason]
  (let [dir (str (home/root) "/hook-scripts")]
    (.mkdirs (io/file dir))
    (let [f (io/file dir (str "answer-" decision ".sh"))]
      (spit f (str "#!/bin/sh\ncat > /dev/null\n"
                   "echo '{\"decision\":\"" decision "\",\"reason\":\"" reason "\"}'\nexit 0\n")
            :encoding "UTF-8")
      (.setExecutable f true)
      ;; The command is shell text, so its path is spelled for the shell.
      (support/shell-path f))))

(deftest a-permission-request-hook-can-approve-a-parked-call
  (wipe!)
  (support/write-hooks!
   {:permission-request [{:command (answer-script "approve" "the rule says yes")}]})
  (tools/session-require-approval! "hw-delegate-ok" "read")
  (try
    (with-server
     "hw-delegate-ok"
     (fn []
       (io/delete-file (log-file "hw-delegate-ok") true)
       (let [frames (wire/frames-from-sse (.body (post-run "hw-delegate-ok")))
             results (filter #(= "TOOL_CALL_RESULT" (:type %)) frames)]
         (testing "the call RAN -- the hook took the human's place, no interrupt was needed"
           (is (= 1 (count results)))
           (is (str/includes? (:content (first results)) ":paths")))
         (testing "and the run finished normally rather than waiting for anyone"
           (is (= "RUN_FINISHED" (:type (last frames))))
           (is (nil? (:outcome (first (filter #(= "RUN_FINISHED" (:type %)) frames))))))
         (testing "the seam records it as an approval, like a human's"
           (let [ls (wait-for (log-file "hw-delegate-ok")
                              (fn [ls] (some #(= "hook/PermissionRequest" (:kind %)) ls))
                              1500)
                 pre (first (filter #(= "tools/pre-execute" (:kind %)) ls))
                 ;; the log reader keywordizes keys, so the hook's own JSON comes
                 ;; back as :decision rather than "decision"
                 hook-line (first (filter #(= "hook/PermissionRequest" (:kind %)) ls))]
             (is (= "approved" (get-in pre [:payload :outcome])))
             (is (= "approve" (get-in hook-line [:payload :answer :decision])))
             (is (= 1 (get-in hook-line [:payload :matched]))))))))
    (finally (tools/session-require-approval! "hw-delegate-ok" "no-such-tool"))))

(deftest a-permission-request-hook-can-deny-a-parked-call
  (wipe!)
  (support/write-hooks!
   {:permission-request [{:command (answer-script "deny" "not on a tuesday")}]})
  (tools/session-require-approval! "hw-delegate-no" "read")
  (try
    (with-server
     "hw-delegate-no"
     (fn []
       (io/delete-file (log-file "hw-delegate-no") true)
       (let [frames (wire/frames-from-sse (.body (post-run "hw-delegate-no")))
             results (filter #(= "TOOL_CALL_RESULT" (:type %)) frames)]
         (testing "the call did NOT run, and the hook's reason is what the model reads"
           (is (= 1 (count results)))
           (is (str/includes? (:content (first results)) "denied by a PermissionRequest hook"))
           (is (str/includes? (:content (first results)) "not on a tuesday")))
         (testing "the run carries on -- a denial is information, not a failure"
           (is (= "RUN_FINISHED" (:type (last frames))))
           (is (empty? (wire/violations frames)))))))
    (finally (tools/session-require-approval! "hw-delegate-no" "no-such-tool"))))

(deftest a-hook-that-does-not-answer-leaves-the-call-to-a-person
  ;; The regression that matters most: a declaration exists at the point but says
  ;; nothing on stdout, so the call parks exactly as it did before hooks existed.
  (wipe!)
  (support/write-hooks! {:permission-request [{:command (gate-script 0 "just watching")}]})
  (tools/session-require-approval! "hw-delegate-quiet" "read")
  (try
    (with-server
     "hw-delegate-quiet"
     (fn []
       (io/delete-file (log-file "hw-delegate-quiet") true)
       (let [frames (wire/frames-from-sse (.body (post-run "hw-delegate-quiet")))
             term (last (filter #(= "RUN_FINISHED" (:type %)) frames))
             interrupts (get-in term [:outcome :interrupts])]
         (testing "no tool result: the call was never answered"
           (is (empty? (filter #(= "TOOL_CALL_RESULT" (:type %)) frames))))
         (testing "the run ended on an interrupt, with the call parked for a human"
           (is (= "interrupt" (get-in term [:outcome :type])))
           (is (= "tool-approval" (:reason (first interrupts))))
           (is (= "c1" (:toolCallId (first interrupts))))))))
    (finally (tools/session-require-approval! "hw-delegate-quiet" "no-such-tool"))))

(deftest an-approval-with-no-hooks-at-all-still-parks
  ;; Byte-for-byte the pre-hook behaviour, asserted here because this is the ticket
  ;; that put a hook call in the middle of the park path.
  (wipe!)
  (tools/session-require-approval! "hw-plain" "read")
  (try
    (with-server
     "hw-plain"
     (fn []
       (io/delete-file (log-file "hw-plain") true)
       (let [frames (wire/frames-from-sse (.body (post-run "hw-plain")))
             term (last (filter #(= "RUN_FINISHED" (:type %)) frames))
             interrupt (first (get-in term [:outcome :interrupts]))]
         (is (= "interrupt" (get-in term [:outcome :type])))
         (is (some? interrupt))
         (testing "and nothing about a hook appears anywhere on the wire"
           (is (empty? (filter #(str/includes? (str (:type %)) "CUSTOM") frames)))))))
    (finally (tools/session-require-approval! "hw-plain" "no-such-tool"))))

;; ------------------------------------------------------------ InstructionsLoaded

(defn- wipe-instructions! []
  (io/delete-file (io/file (home/user-home) "AGENTS.md") true))

(use-fixtures :each (fn [f] (wipe!) (wipe-instructions!) (f) (wipe!) (wipe-instructions!)))

(deftest instructions-loaded-fires-once-per-file-folded
  ;; The point had been declared since the hook engine landed and had no trigger
  ;; source until this feature: nothing in the codebase folded an instruction
  ;; file. Now something does, and it has to fire THERE -- which is why the edge's
  ;; hook-sink binding had to start wrapping the set-up, not just the run.
  (wipe!)
  (let [marker (str (home/root) "/hooks-fired.txt")
        proj   (str (home/root) "/hw-instructions-project")]
    (.mkdirs (io/file (home/user-home)))
    (.mkdirs (io/file proj))
    (spit (str (home/user-home) "/AGENTS.md") "user rules\n" :encoding "UTF-8")
    (spit (str proj "/AGENTS.md") "project rules\n" :encoding "UTF-8")
    (project/bind! "hw-instructions" proj)
    (support/write-hooks!
     {:instructions-loaded [{:command (marker-script marker "instructions-loaded")}]})
    (with-server
     "hw-instructions"
     (fn []
       (io/delete-file (log-file-for "hw-instructions") true)
       (post-run "hw-instructions")
       (let [ls (wait-for (log-file-for "hw-instructions")
                          (fn [ls] (>= (count (filter #(= "hook/InstructionsLoaded" (:kind %)) ls)) 2))
                          1500)
             lines (filter #(= "hook/InstructionsLoaded" (:kind %)) ls)]
         (testing "one line per folded file -- and NOT for the one that is missing"
           (is (= 2 (count lines))))
         (testing "each was an observer, allowed, and named the point the way the payload does"
           (is (every? #(= "allow" (get-in % [:payload :verdict])) lines))
           (is (every? #(= 1 (get-in % [:payload :matched])) lines)))

         (testing "the hook command really received each path on stdin"
           ;; The marker script cats its stdin, so the payload lines are in the
           ;; file -- parsed rather than substring-matched, because JSON escapes
           ;; the path separators.
           (let [fired (slurp marker)
                 payloads (into []
                                (keep (fn [l]
                                        (when (str/starts-with? l "{")
                                          (json/read-str l :key-fn keyword))))
                                (str/split-lines fired))]
             (is (str/includes? fired "instructions-loaded"))
             (is (= #{(str (io/file (home/user-home) "AGENTS.md"))
                      (str (io/file proj "AGENTS.md"))}
                    (set (map :path payloads))))
             (is (every? #(= "InstructionsLoaded" (:hook %)) payloads))))

         (testing "and the folded text really reached the model, as user messages"
           (let [texts (map #(str (get-in % [:payload :content]))
                            (filter #(= "message" (:kind %)) ls))]
             (is (some #(str/includes? % "user rules") texts))
             (is (some #(str/includes? % "project rules") texts))
             (testing "tagged with an absolute path, not a bare filename"
               (is (some #(str/includes? % (str "<instructions path=\"" (io/file proj "AGENTS.md") "\">"))
                         texts))))))))))

(deftest a-session-with-no-instruction-files-fires-nothing
  (wipe!)
  (wipe-instructions!)
  (support/write-hooks!
   {:instructions-loaded [{:command (marker-script (str (home/root) "/hooks-fired.txt")
                                                   "should-not-run")}]})
  (with-server
   "hw-noinstructions"
   (fn []
     (io/delete-file (log-file "hw-noinstructions") true)
     (post-run "hw-noinstructions")
     (let [ls (wait-for (log-file "hw-noinstructions")
                        (fn [ls] (some #(= "RUN_FINISHED" (get-in % [:payload :type])) ls))
                        1500)]
       (testing "nothing was folded, so the point does not fire"
         (is (empty? (filter #(= "hook/InstructionsLoaded" (:kind %)) ls))))
       (testing "and no hook line of any kind is written for it"
         (is (empty? (filter #(str/starts-with? (str (:kind %)) "hook/InstructionsLoaded") ls))))
       (testing "the run itself is complete -- a missing file is not a failure"
         (is (some #(= "RUN_FINISHED" (get-in % [:payload :type])) ls)))))))

;; ------------------------------------------------------- two runs, one thread
;;
;; TWO RUNS OF ONE THREAD IS AN ORDINARY CASE -- two tabs, or any client that is not
;; this UI -- and the facts a session establishes on its first run must be established
;; once. The edge promises "every thread exactly one line"; the check and the mark used
;; to be two atom operations with a side effect between them, which is wide enough for
;; both runs to take the first one.

(deftest two-runs-of-one-thread-start-it-once
  (wipe!)
  (support/write-hooks!
   {:session-start [{:command (marker-script (str (home/root) "/hooks-fired.txt")
                                             "session-start")}]})
  (with-server
   "hw-both"
   (fn []
     (io/delete-file (log-file "hw-both") true)
     (let [a (future (post-run "hw-both"))
           b (future (post-run "hw-both"))]
       (is (not= ::timeout (deref a 30000 ::timeout)) "the first run finished")
       (is (not= ::timeout (deref b 30000 ::timeout)) "and so did the second")
       (let [ls (wait-for (log-file "hw-both")
                          (fn [ls] (some #(= "provider/init" (:kind %)) ls))
                          1500)]
         (is (= 1 (count (filter #(= "hook/SessionStart" (:kind %)) ls)))
             (str "SessionStart fired once, not once per run: "
                  (pr-str (mapv :kind (hook-lines ls)))))
         ;; The provider init LINE is not written at all under a scripted pin -- there is
         ;; no resolution to record -- so its once-only half is what
         ;; harness.edge.http-test's timeline case covers. What this case adds is the half
         ;; that needs two runs at once.
         (is (empty? (filter #(= "provider/init" (:kind %)) ls))
             "no init line: this session is served by a scripted pin")
         (is (str/includes? (slurp (str (home/root) "/hooks-fired.txt")) "session-start")
             "the hook command really ran"))))))

(deftest a-claim-is-taken-by-exactly-one-caller
  ;; The primitive the two facts above are built on, on its own: sixteen threads released
  ;; together, one winner. This one cannot be red against the old code -- the entry did
  ;; not exist -- so it is the proof that the replacement is right rather than the proof
  ;; that the old shape was wrong.
  (let [gate    (support/start-gate 16)
        a       (atom #{})
        claims  (atom [])
        workers (mapv (fn [i]
                        (future
                          ((:arrive gate))
                          (swap! claims conj [(#'http/claim-once! a (str "t" i))
                                              (#'http/claim-once! a (str "t" i))])))
                      (range 16))]
    (doseq [w workers] (is (not= ::timeout (deref w 20000 ::timeout)) "every claim finished"))
    (is (= 16 (count (filter first @claims)))
        (str "exactly one claim per thread-id was the first: " (pr-str @claims)))
    (is (every? false? (map second @claims))
        "and a second claim on a thread-id already claimed is always the loser")
    (is (= 16 (count @a)) "the set holds one entry per thread-id")))
