(ns harness.mcp-test
  "MCP servers as a source of tools, against a REAL server process.

  The server is `test/harness/fake_mcp_server.js`: a real program on a real pipe,
  written by us but run by the same code path a third-party server would be. That
  is the only way to test the parts that exist BECAUSE a server is somebody else's
  process -- the spawn, the handshake, the roster, the request timeout, and the
  difference between a server that refuses a call and one that has stopped
  answering.

  TWO TRANSPORTS, TWO KINDS OF FAKE, and the difference is the point of the
  third ticket: a stdio server is a PROCESS we start (the node script), an HTTP
  server is an ENDPOINT we call (an http-kit handler in this JVM, on an OS-chosen
  port). Both are driven through the same client interface, and both end up in
  this file's table assertions -- which is how 'the transport is invisible above
  the client' is checked rather than asserted.

  Everything here is offline: no api-key, no model, and the only network is a pipe
  or a loopback socket. The edge-level half of these tickets (a real AG-UI run, the
  audit lines, a hook blocking a server call) lives in harness.mcp-wired-test,
  because hooks only fire when the edge has bound the run's sink."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.home :as home]
            [harness.log :as log]
            [harness.mcp :as mcp]
            [harness.project :as project]
            [harness.tools :as tools]
            [org.httpkit.server :as hk]))

(def ^:private fake-server
  "The fake MCP server, by ABSOLUTE path: a client starts it with the project
  directory as its cwd, so a relative path would resolve against the wrong
  directory the moment a test binds a session somewhere."
  (.getAbsolutePath (io/file "test/harness/fake_mcp_server.js")))

(defn- mcp-file []
  (io/file (home/root) "mcp.edn"))

(defn- write-servers!
  "Declare SERVERS (a map of name -> declaration) at the user level."
  [servers]
  (spit (mcp-file) (pr-str {:servers servers}) :encoding "UTF-8"))

(defn- wipe! []
  (let [f (mcp-file)]
    (when (.exists f) (io/delete-file f true))))

(defn- fake-decl
  "A declaration that runs the fake server. EXTRA is merged in, so a test can add
  `:env` or a `:timeout` without restating the command."
  [& [extra]]
  (merge {:command (str "node " fake-server)} extra))

(defn- tmp-dir
  "A temp directory, removed when F is done with it."
  [label f]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "mcp-" label "-" (System/currentTimeMillis)))]
    (.mkdirs d)
    (try (f d)
         (finally
           (doseq [x (reverse (file-seq d))]
             (try (io/delete-file x true) (catch Exception _ nil)))))))

(defn- call!
  "Call NAME the way the kernel does -- a provider-shaped tool call -- and answer
  the seam's result. THREAD-ID defaults to a fresh id so no test inherits another
  one's overlays."
  ([name args] (call! (str "mcp-t-" (System/currentTimeMillis) "-" (rand-int 100000)) name args))
  ([thread-id name args]
   (tools/run! {:id (str "c-" name) :type "function"
                :function {:name name :arguments (json/write-str args)}}
               thread-id)))

(use-fixtures :each
  (fn [f]
    ;; The user-level mcp.edn is read for EVERY thread and `states`/`events` are
    ;; process-wide, so a test that leaves either behind makes the next one see a
    ;; server it never declared. Same discipline as hooks.edn.
    (wipe!)
    (mcp/shutdown!)
    (try (f)
         (finally (wipe!) (mcp/shutdown!)))))

;; ------------------------------------------------------------------ the config

(deftest no-declarations-is-the-empty-configuration
  (testing "a fresh install has no mcp.edn, and that is not an error"
    (is (= {} (mcp/config nil)))
    (is (= {} (mcp/tools-for nil)))
    (is (= [] (mcp/status nil)))))

(deftest a-broken-file-fails-by-name
  (testing "each way of being wrong names the file it is in"
    (let [cases {"not EDN at all {{{"                 :invalid-edn
                 "[1 2 3]"                            :not-a-map
                 "{:servers {} :extra 1}"             :unknown-key
                 "{:servers {\"a\" {:command \"x\" :nope 1}}}" :unknown-server-key
                 "{:servers {\"a\" {}}}"              :no-transport
                 "{:servers {\"a\" {:command \"x\" :url \"http://y\"}}}" :two-transports}]
      (doseq [[text _] cases]
        (spit (mcp-file) text :encoding "UTF-8")
        (let [e (try (mcp/config nil) nil (catch Exception e e))]
          (is (some? e) (str "expected a failure for " text))
          (is (str/includes? (ex-message e) (.getAbsolutePath (mcp-file)))
              (str "the failure must name the file: " (ex-message e)))))
      (wipe!))))

(deftest a-server-name-must-be-invertible
  (testing "a name that could collide with another server's tools is refused"
    (doseq [bad ["a__b" "has space" "has.dot" "" "工具"]]
      (spit (mcp-file) (pr-str {:servers {bad {:command "x"}}}) :encoding "UTF-8")
      (let [e (try (mcp/config nil) nil (catch Exception e e))]
        (is (some? e) (str "expected " (pr-str bad) " to be refused"))
        (is (str/includes? (ex-message e) (pr-str bad))))
      (wipe!))
    (testing "and a good one is accepted"
      (write-servers! {"github-1" (fake-decl)})
      (is (= ["github-1"] (keys (mcp/config nil)))))))

(deftest the-project-level-set-replaces-the-users
  (tmp-dir "replace"
           (fn [d]
             (write-servers! {"user-level" (fake-decl)})
             (let [proj (io/file d "proj")
                   har  (io/file proj ".harness")]
               (.mkdirs har)
               (spit (io/file har "mcp.edn") (pr-str {:servers {"project-level" (fake-decl)}})
                     :encoding "UTF-8")
               (let [thread (str "mcp-replace-" (System/currentTimeMillis))]
                 (project/bind! thread (.getAbsolutePath proj))
                 (testing "a bound session sees only the project's servers"
                   (is (= ["project-level"] (keys (mcp/config thread)))))
                 (testing "an unbound session sees the user's"
                   (is (= ["user-level"] (keys (mcp/config nil))))))))))

;; ------------------------------------------------------------------- the tools

(deftest a-declared-server-s-tools-arrive
  (write-servers! {"fake" (fake-decl)})
  (let [thread (str "mcp-arrive-" (System/currentTimeMillis))
        table  (mcp/tools-for thread)]
    (testing "the roster became rows of the table"
      (is (= #{"mcp__fake__echo" "mcp__fake__where" "mcp__fake__fail" "mcp__fake__hang"
                      "mcp__fake__exit"}
             (set (keys table)))))
    (testing "and they say where they came from"
      (is (every? #(= :mcp (:source %)) (vals table))))
    (testing ":required is derived from the schema's own required list, as the
              seam wants it -- a mismatch would tell a model it forgot an
              argument it did give"
      (is (= [:text] (:required (get table "mcp__fake__echo"))))
      (is (= [] (:required (get table "mcp__fake__where")))))
    (testing "the schema is passed through verbatim"
      (is (= {:type "object" :properties {:text {:type "string" :description "Anything."}}
              :required ["text"]}
             (:parameters (get table "mcp__fake__echo")))))
    (testing "and a built-in still says what it is"
      (is (= :builtin (:source (get (tools/effective-tools nil) "read")))))))

(deftest a-bridged-name-a-provider-would-refuse-is-dropped-not-truncated
  (write-servers! {"fake" (fake-decl)})
  (let [thread (str "mcp-long-" (System/currentTimeMillis))
        table  (mcp/tools-for thread)
        status (first (mcp/status thread))]
    (testing "the too-long tool is not in the table"
      (is (not-any? #(> (count %) 64) (keys table))))
    (testing "and the reason names the server and the tool"
      (let [skipped (first (:skipped status))]
        (is (some? skipped))
        (is (str/starts-with? (:skipped skipped) "mcp__fake__"))
        (is (str/includes? (:why skipped) "64"))))))

(deftest a-call-gets-the-servers-text
  (write-servers! {"fake" (fake-decl)})
  (let [{:keys [content error]} (call! "mcp__fake__echo" {:text "hello"})]
    (is (false? error))
    (is (= "echo: hello" content))))

(deftest a-call-the-server-refuses-is-a-tool-error-and-not-a-run-failure
  (write-servers! {"fake" (fake-decl)})
  (let [{:keys [content error]} (call! "mcp__fake__fail" {})]
    (testing "the seam answers with an error result; it never throws"
      (is (true? error))
      (is (= "the fake server refused" content)))))

(deftest the-server-runs-in-the-sessions-project-directory
  (tmp-dir "cwd"
           (fn [d]
             (let [proj (io/file d "proj")
                   har  (io/file proj ".harness")]
               (.mkdirs har)
               (spit (io/file har "mcp.edn") (pr-str {:servers {"fake" (fake-decl)}})
                     :encoding "UTF-8")
               (let [thread (str "mcp-cwd-" (System/currentTimeMillis))]
                 (project/bind! thread (.getAbsolutePath proj))
                 (let [{:keys [content]} (call! thread "mcp__fake__where" {})]
                   ;; The server answers with ITS cwd. It is the project's
                   ;; canonical path: connections are keyed by the project's
                   ;; identity, and the process that serves one is that project's.
                   (is (= (project/identity-for thread) content))))))))

;; ------------------------------------------- the same seam, the same four rules
;;
;; These four are the ticket's central claim: a tool from a server is an ordinary
;; row, so everything that already guards a built-in guards it too -- without one
;; line of MCP-specific code in the seam.

(deftest a-server-call-is-refused-when-this-session-switched-the-tool-off
  (write-servers! {"fake" (fake-decl)})
  (let [thread (str "mcp-disabled-" (System/currentTimeMillis))]
    (tools/session-disable! thread "mcp__fake__echo")
    (let [{:keys [content error]} (call! thread "mcp__fake__echo" {:text "x"})]
      (is (true? error))
      (is (str/includes? content "disabled in this session")))
    (testing "and switching it back on runs it"
      (tools/session-enable! thread "mcp__fake__echo")
      (is (= "echo: x" (:content (call! thread "mcp__fake__echo" {:text "x"})))))))

(deftest a-server-call-parked-by-an-approval-rule-resumes-like-any-other
  (write-servers! {"fake" (fake-decl)})
  (let [thread (str "mcp-approval-" (System/currentTimeMillis))]
    (tools/session-require-approval! thread "mcp__fake__echo")
    (let [{:keys [parked]} (call! thread "mcp__fake__echo" {:text "x"})]
      (testing "the call suspends instead of running"
        (is (some? parked))
        (is (= "mcp__fake__echo" (:name parked))))
      (testing "and the reason says it was this session's rule"
        (is (= :session-asks (:reason parked))))
      (testing "an approved resume runs it"
        (tools/decide-approval! (:interrupt-id parked) :approved nil)
        (is (= "echo: x" (:content (call! thread "mcp__fake__echo" {:text "x"})))))
      (testing "and a vetoed one never reaches the server"
        (let [{:keys [parked]} (call! thread "mcp__fake__echo" {:text "y"})]
          (tools/decide-approval! (:interrupt-id parked) :vetoed nil)
          (let [{:keys [content error]} (call! thread "mcp__fake__echo" {:text "y"})]
            (is (true? error))
            (is (str/includes? content "vetoed by human"))))))))

;; ------------------------------------------------------------------ failures

(deftest a-server-that-will-not-start-costs-only-its-own-tools
  (write-servers! {"broken" (fake-decl) "gone" {:command "definitely-not-a-program-xyz"}})
  (let [thread (str "mcp-broken-" (System/currentTimeMillis))
        table  (mcp/tools-for thread)
        by-name (into {} (map (juxt :server identity) (mcp/status thread)))]
    (testing "the working server's tools are all there"
      (is (contains? table "mcp__broken__echo")))
    (testing "the other one contributes nothing"
      (is (not-any? #(str/starts-with? % "mcp__gone__") (keys table))))
    (testing "and it is NAMED as failed, so 'the tools are missing' has an answer"
      (is (= :failed (:status (by-name "gone"))))
      (is (seq (:error (by-name "gone")))))
    (testing "the working one is reported connected"
      (is (= :connected (:status (by-name "broken")))))))

(deftest a-line-that-is-not-json-is-a-named-protocol-error
  (write-servers! {"noisy" (fake-decl {:env {"MCP_FAKE_BANNER" "1"}})})
  (let [thread (str "mcp-noisy-" (System/currentTimeMillis))
        table  (mcp/tools-for thread)
        failed (first (mcp/status thread))]
    (testing "a server polluting the protocol stream provides no tools"
      (is (= {} table)))
    (testing "and the reason quotes what it actually printed"
      (is (= :failed (:status failed)))
      (is (str/includes? (:error failed) "not JSON"))
      (is (str/includes? (:error failed) "fake mcp server starting")))))

;; ------------------------------------------------------------------- the edges

(deftest the-env-values-never-reach-the-state-surface
  (let [sentinel "SENTINEL-9f3a2b-do-not-log-me"]
    (write-servers! {"fake" (fake-decl {:env {"FAKE_TOKEN" sentinel}})})
    (let [thread (str "mcp-env-" (System/currentTimeMillis))]
      (mcp/tools-for thread)
      (testing "the status and the outbox describe the server without its secrets"
        (let [seen (json/write-str [(mcp/status thread) (mcp/take-events!)])]
          (is (not (str/includes? seen sentinel))))))))

(deftest the-outbox-reports-a-change-and-then-shuts-up
  (write-servers! {"fake" (fake-decl)})
  (let [thread (str "mcp-events-" (System/currentTimeMillis))]
    (mcp/take-events!)                       ; anything a previous test left
    (mcp/tools-for thread)
    (testing "connecting is news"
      (let [events (mcp/take-events!)]
        (is (= 1 (count events)))
        (is (= "fake" (:server (first events))))
        (is (= :connected (:status (first events))))))
    (testing "and asking again is not -- tools-for runs on the way to every LLM
              request, so queueing eagerly would write a line per request"
      (mcp/tools-for thread)
      (is (= [] (mcp/take-events!))))))

(deftest editing-mcp-edn-takes-effect-without-a-restart
  (write-servers! {"first" (fake-decl)})
  (let [thread (str "mcp-edit-" (System/currentTimeMillis))]
    (is (contains? (mcp/tools-for thread) "mcp__first__echo"))
    (write-servers! {"second" (fake-decl)})
    (let [table (mcp/tools-for thread)]
      (testing "a renamed server is a different server"
        (is (contains? table "mcp__second__echo"))
        (is (not (contains? table "mcp__first__echo")))))
    (wipe!)
    (testing "and no declarations is no tools"
      (is (= {} (mcp/tools-for thread))))))

;; ------------------------------- 02: a server that breaks gets back up

(defn- lifecycle-file
  "A path the fake server appends start/term/exit lines to, so a test can watch a
  process be born and be collected rather than infer it from behaviour."
  [label]
  (let [d (io/file (home/root) "mcp-lifecycle")]
    (.mkdirs d)
    (str (io/file d (str label ".txt")))))

(defn- lifecycle [f]
  (when (.exists (io/file f))
    (->> (str/split-lines (slurp f :encoding "UTF-8"))
         (keep #(first (str/split % #" ")))
         vec)))

(defn- last-pid
  "The pid the fake server last reported -- the process a test has to be able to
  prove is gone."
  [f]
  (when (.exists (io/file f))
    (->> (str/split-lines (slurp f :encoding "UTF-8"))
         (keep #(second (str/split % #" ")))
         (map #(Long/parseLong %))
         last)))

(defn- running?
  "Is PID still a live process? Asked of the OS rather than of our own records:
  'the server is gone' is a claim about the machine, and a bookkeeping entry we
  deleted is not evidence about the machine."
  [pid]
  (when pid
    (let [h (java.lang.ProcessHandle/of (long pid))]
      (and (.isPresent h) (.isAlive (.get h))))))

(defn- wait-gone
  "True once the OS no longer has PID -- or false if it is still there at the
  deadline.

  THE CONTRACT IS 'IT IS GONE', NOT 'WE SIGTERMed IT'. A server whose stdin closes
  may exit by itself before the signal lands, and both are the same outcome; a
  test that demanded one mechanism would fail on the other while the leak it is
  guarding against stayed hidden."
  [pid ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [alive? (running? pid)]
        (if (or (not alive?) (> (System/currentTimeMillis) deadline))
          (not alive?)
          (do (Thread/sleep 50) (recur)))))))

(deftest a-call-that-hangs-times-out-and-the-connection-is-dropped
  ;; `hang` never answers, and the declaration's :timeout is what bounds it -- so
  ;; this is also the test that `:timeout` is read at all.
  (let [live (lifecycle-file "timeout")]
    (write-servers! {"slow" (fake-decl {:env {"MCP_FAKE_LIFECYCLE" live} :timeout 1500})})
    (let [thread (str "mcp-timeout-" (System/currentTimeMillis))]
      (is (contains? (mcp/tools-for thread) "mcp__slow__hang"))
      (let [{:keys [content error]} (call! thread "mcp__slow__hang" {})]
        (testing "the model gets a NAMED failure, not an empty answer and not a
                  thrown run"
          (is (true? error))
          (is (str/includes? content "MCP server \"slow\""))
          (is (str/includes? content "hang"))
          (is (str/includes? content "1500ms")))
        (testing "and the connection went with it -- a request still in flight
                  would put every later message one off"
          (is (wait-gone (last-pid live) 3000) "and the process is gone"))))))

(deftest a-server-that-dies-reconnects-and-the-roster-is-taken-again
  (let [live   (lifecycle-file "reconnect")
        roster (str (io/file (home/root) "mcp-roster.txt"))]
    (spit roster "" :encoding "UTF-8")
    (write-servers! {"flaky" (fake-decl {:env {"MCP_FAKE_LIFECYCLE" live
                                               "MCP_FAKE_ROSTER_FILE" roster}})})
    (let [thread (str "mcp-reconnect-" (System/currentTimeMillis))]
      (is (contains? (mcp/tools-for thread) "mcp__flaky__echo"))
      (testing "the server goes away without answering"
        (let [{:keys [error]} (call! thread "mcp__flaky__exit" {})]
          (is (true? error) "a dead server is a tool ERROR, not a dead run")))
      (testing "THE NEXT CALL IS SERVED BY A NEW PROCESS, which is what 'it got
                back up' means -- not a revived conversation"
        (is (= "echo: still here" (:content (call! thread "mcp__flaky__echo" {:text "still here"}))))
        (is (<= 2 (count (filter #{"start"} (lifecycle live))))))
      (testing "and the roster is taken again, so a tool that arrived while it was
                down is in the table"
        ;; A LIVE connection is not re-listed -- that is the cache doing its job,
        ;; and asking a server its roster on every LLM request would be paying for
        ;; an answer that has not changed. The roster moves when the CONNECTION
        ;; does, so this is the same move as above: take the server away first.
        (spit roster "brand_new\n" :encoding "UTF-8")
        (call! thread "mcp__flaky__exit" {})
        (is (contains? (mcp/tools-for thread) "mcp__flaky__brand_new")))
      (testing "and one that left is not"
        (spit roster "" :encoding "UTF-8")
        (call! thread "mcp__flaky__exit" {})
        (let [again (mcp/tools-for thread)]
          (is (contains? again "mcp__flaky__echo"))
          (is (not (contains? again "mcp__flaky__brand_new"))))))))

(deftest a-changed-declaration-is-a-different-server
  (let [a (lifecycle-file "decl-a")
        b (lifecycle-file "decl-b")]
    (write-servers! {"svc" (fake-decl {:env {"MCP_FAKE_LIFECYCLE" a}})})
    (let [thread (str "mcp-decl-" (System/currentTimeMillis))]
      (is (= "echo: one" (:content (call! thread "mcp__svc__echo" {:text "one"}))))
      (testing "the command changed, so the next use is a new process"
        (write-servers! {"svc" (fake-decl {:env {"MCP_FAKE_LIFECYCLE" b}})})
        (is (= "echo: two" (:content (call! thread "mcp__svc__echo" {:text "two"}))))
        (is (= ["start"] (lifecycle b)) "the new declaration was actually used"))
      (testing "and the old process is gone"
        (is (wait-gone (last-pid a) 3000)))
      (testing "changing ONLY the timeout counts as a change too"
        (let [c (lifecycle-file "decl-c")]
          (write-servers! {"svc" (fake-decl {:env {"MCP_FAKE_LIFECYCLE" c} :timeout 5000})})
          (is (contains? (mcp/tools-for thread) "mcp__svc__echo"))
          (is (= ["start"] (lifecycle c))))))))

(deftest a-declaration-that-goes-away-takes-its-process-with-it
  (let [live (lifecycle-file "removed")]
    (write-servers! {"temp" (fake-decl {:env {"MCP_FAKE_LIFECYCLE" live}})})
    (let [thread (str "mcp-removed-" (System/currentTimeMillis))]
      (is (contains? (mcp/tools-for thread) "mcp__temp__echo"))
      (is (= ["start"] (lifecycle live)))
      (wipe!)
      (testing "its tools leave the table"
        (is (= {} (mcp/tools-for thread))))
      (testing "and the process is collected -- the walk only visits what the file
                still says, so nothing else was ever going to look at this one"
        (is (wait-gone (last-pid live) 3000)))
      (testing "it ended of its own accord or on our signal, and either way it is
                not on this machine any more"
        (is (some #{"term" "exit"} (lifecycle live)))
        (is (not (running? (last-pid live))))))))

(deftest a-server-that-speaks-rubbish-is-named-and-then-replaced
  ;; The protocol half of "a bad server costs only itself": stdout is the
  ;; protocol's, so a line that is not protocol is the SERVER's error and is
  ;; quoted back -- never skipped, because the next message would inherit the
  ;; doubt.
  (let [live (lifecycle-file "rubbish")]
    (write-servers! {"noisy" (fake-decl {:env {"MCP_FAKE_BANNER" "1"
                                               "MCP_FAKE_LIFECYCLE" live}})})
    (let [thread (str "mcp-rubbish-" (System/currentTimeMillis))
          table  (mcp/tools-for thread)
          failed (first (mcp/status thread))]
      (is (= {} table))
      (is (= :failed (:status failed)))
      (is (str/includes? (:error failed) "not JSON"))
      (is (str/includes? (:error failed) "fake mcp server starting"))
      (testing "and the process behind the broken conversation is not left running"
        (is (wait-gone (last-pid live) 3000))))))

(deftest a-server-that-writes-to-stderr-is-not-a-server-that-failed
  ;; stderr is DIAGNOSTICS. A server that logs a line there must be usable, and
  ;; its log must not be mistaken for protocol -- the same rule the hook engine
  ;; holds, for the same reason.
  (write-servers! {"chatty" {:command (str "node -e 'console.error(\"a log line\"); require(\""
                                           (.getAbsolutePath (io/file "test/harness/fake_mcp_server.js"))
                                           "\")'")}})
  (let [thread (str "mcp-stderr-" (System/currentTimeMillis))]
    (is (contains? (mcp/tools-for thread) "mcp__chatty__echo"))
    (is (= "echo: fine" (:content (call! thread "mcp__chatty__echo" {:text "fine"}))))))

(deftest one-broken-server-does-not-cost-another-its-tools
  (let [live (lifecycle-file "isolation")]
    (write-servers! {"good" (fake-decl {:env {"MCP_FAKE_LIFECYCLE" live}})
                     "bad"  {:command "definitely-not-a-program-xyz"}})
    (let [thread (str "mcp-iso-" (System/currentTimeMillis))
          table  (mcp/tools-for thread)]
      (is (contains? table "mcp__good__echo"))
      (is (not-any? #(str/starts-with? % "mcp__bad__") (keys table)))
      (testing "the good one still answers, in the same turn the bad one failed"
        (is (= "echo: fine" (:content (call! thread "mcp__good__echo" {:text "fine"}))))))))

;; ------------------------------- 03: the same thing, over HTTP

(def ^:private http-tools
  "The roster the fake HTTP server offers: the same names the stdio fake has, so
  the two sides of this file are comparable line for line."
  [{:name "echo" :description "Answer with the text it was given."
    :inputSchema {:type "object"
                  :properties {:text {:type "string"}}
                  :required ["text"]}}
   {:name "where" :description "Answer with this server's working directory."
    :inputSchema {:type "object" :properties {}}}
   {:name "fail" :description "Answer with a failure."
    :inputSchema {:type "object" :properties {}}}])

(defn- fake-http-response
  "The JSON-RPC answer to MSG, or nil for a notification."
  [msg]
  (let [{:keys [id method params]} msg]
    (when (some? id)
      (case method
        "initialize" {:jsonrpc "2.0" :id id
                      :result {:protocolVersion "2025-06-18"
                               :capabilities {:tools {}}
                               :serverInfo {:name "fake-http" :version "1"}}}
        "tools/list" {:jsonrpc "2.0" :id id :result {:tools http-tools}}
        "tools/call" (case (:name params)
                       "echo" {:jsonrpc "2.0" :id id
                               :result {:content [{:type "text"
                                                   :text (str "echo: " (:text (:arguments params)))}]}}
                       "fail" {:jsonrpc "2.0" :id id
                               :result {:content [{:type "text" :text "the fake http server refused"}]
                                        :isError true}}
                       {:jsonrpc "2.0" :id id
                        :result {:content [{:type "text" :text "no such tool"}]
                                 :isError true}})
        {:jsonrpc "2.0" :id id
         :error {:code -32601 :message (str "no such method: " method)}}))))

(defn- with-fake-http
  "A fake MCP server speaking HTTP, on a port the OS picks. MODE is :json or :sse
  -- the two shapes a server may answer in. OPTS may carry :status to answer
  everything with that HTTP status instead, and :hold to never answer.

  Every request is recorded ({:session :body}), because two of this ticket's
  claims are about what the CLIENT SENT: that the session id it was given comes
  back on later requests, and that it is absent when the server gave none."
  [mode opts f]
  (let [seen (atom [])
        stop (hk/run-server
              (fn [req]
                (let [body (json/read-str (slurp (:body req) :encoding "UTF-8")
                                          :key-fn keyword)
                      sid  (get-in req [:headers "mcp-session-id"])]
                  (swap! seen conj {:session sid :body body :path (:uri req)})
                  (cond
                    (:hold opts)
                    ;; Hanging: the handler answers nothing, ever, which is what a
                    ;; server that has stopped responding looks like.
                    (hk/as-channel req {:on-open (fn [_] nil)})

                    (:status opts)
                    {:status (:status opts)
                     :headers {"Content-Type" "application/json"}
                     :body "{\"error\":\"nope\"}"}

                    :else
                    (let [answer (fake-http-response body)]
                      (if (nil? answer)
                        {:status 202 :headers {} :body ""}
                        (case mode
                          :json {:status 200
                                 :headers (cond-> {"Content-Type" "application/json"}
                                            (:session opts) (assoc "Mcp-Session-Id" (:session opts)))
                                 :body (json/write-str answer)}
                          :sse  {:status 200
                                 :headers {"Content-Type" "text/event-stream"}
                                 :body (str "event: message\ndata: "
                                            (json/write-str answer) "\n\n")}))))))
              {:port 0})]
    (try (f (:local-port (meta stop)) seen)
         (finally (stop)))))

(defn- http-decl [port & [extra]]
  (merge {:url (str "http://127.0.0.1:" port "/mcp")} extra))

(deftest an-http-server-s-tools-arrive-and-are-called-the-same-way
  (with-fake-http :json {}
    (fn [port _]
      (write-servers! {"remote" (http-decl port)})
      (let [thread (str "mcp-http-" (System/currentTimeMillis))
            table  (mcp/tools-for thread)]
        (testing "the roster arrives as ordinary rows, exactly as a stdio server's did"
          (is (= #{"mcp__remote__echo" "mcp__remote__where" "mcp__remote__fail"}
                 (set (keys table))))
          (is (every? #(= :mcp (:source %)) (vals table)))
          (is (= [:text] (:required (get table "mcp__remote__echo")))))
        (testing "a call is served by the remote tool"
          (is (= "echo: hi there" (:content (call! thread "mcp__remote__echo" {:text "hi there"})))))
        (testing "and a refusal is a tool ERROR, not a failed run"
          (let [{:keys [content error]} (call! thread "mcp__remote__fail" {})]
            (is (true? error))
            (is (= "the fake http server refused" content))))))))

(deftest an-sse-answer-is-read-too
  ;; A server may answer either way, and which one it picks is its business --
  ;; so both shapes have to end up as the same tool.
  (with-fake-http :sse {}
    (fn [port _]
      (write-servers! {"sse" (http-decl port)})
      (let [thread (str "mcp-sse-" (System/currentTimeMillis))]
        (is (contains? (mcp/tools-for thread) "mcp__sse__echo"))
        (is (= "echo: streamed" (:content (call! thread "mcp__sse__echo" {:text "streamed"}))))))))

(deftest a-session-id-is-carried-back-and-absent-when-none-was-given
  (testing "the server mints one, so every later request carries it"
    (with-fake-http :json {:session "sess-abc123"}
      (fn [port seen]
        (write-servers! {"sess" (http-decl port)})
        (let [thread (str "mcp-sess-" (System/currentTimeMillis))]
          (mcp/tools-for thread)
          (let [reqs @seen
                initialize (first (filter #(= "initialize" (get-in % [:body :method])) reqs))
                later      (remove #(= "initialize" (get-in % [:body :method])) reqs)]
            (testing "the handshake itself does not carry one -- there is nothing to carry yet"
              (is (nil? (:session initialize))))
            (testing "and everything after it does"
              (is (seq later))
              (is (every? #(= "sess-abc123" (:session %)) later))))))))
  (testing "a server that mints none is not a problem"
    (with-fake-http :json {}
      (fn [port seen]
        (write-servers! {"stateless" (http-decl port)})
        (let [thread (str "mcp-stateless-" (System/currentTimeMillis))]
          (is (contains? (mcp/tools-for thread) "mcp__stateless__echo"))
          (is (every? nil? (map :session @seen))))))))

(deftest an-http-failure-is-named-and-costs-only-its-own-tools
  (testing "an HTTP status that is not 2xx"
    (with-fake-http :json {:status 500}
      (fn [port _]
        (write-servers! {"broken" (http-decl port)})
        (let [thread (str "mcp-http500-" (System/currentTimeMillis))
              failed (do (mcp/tools-for thread) (first (mcp/status thread)))]
          (is (= {} (mcp/tools-for thread)))
          (is (= :failed (:status failed)))
          (is (str/includes? (:error failed) "500"))
          (is (str/includes? (:error failed) (str port)))))))
  (testing "an endpoint nobody is listening on"
    (write-servers! {"gone" {:url "http://127.0.0.1:1/mcp" :timeout 900}})
    (let [thread (str "mcp-nobody-" (System/currentTimeMillis))
          failed (do (mcp/tools-for thread) (first (mcp/status thread)))]
      (is (= {} (mcp/tools-for thread)))
      (is (= :failed (:status failed)))
      (is (str/includes? (:error failed) "http://127.0.0.1:1/mcp"))))
  (testing "and a good server beside a bad one still answers"
    (with-fake-http :json {}
      (fn [port _]
        (write-servers! {"good" (http-decl port)
                         "gone" {:url "http://127.0.0.1:1/mcp" :timeout 900}})
        (let [thread (str "mcp-http-iso-" (System/currentTimeMillis))]
          (is (= "echo: alive" (:content (call! thread "mcp__good__echo" {:text "alive"})))))))))

(deftest an-http-server-that-never-answers-times-out-by-the-declaration
  (with-fake-http :json {:hold true}
    (fn [port _]
      (write-servers! {"slow" (http-decl port {:timeout 1500})})
      (let [thread (str "mcp-http-slow-" (System/currentTimeMillis))
            table  (mcp/tools-for thread)
            failed (first (mcp/status thread))]
        (testing "a handshake that hangs is a named failure, like a process that hangs"
          (is (= {} table))
          (is (= :failed (:status failed)))
          (is (str/includes? (:error failed) "1500ms")))))))

(deftest an-http-declaration-never-reaches-the-spawn-path
  ;; The ticket's structural claim, and the one worth an assertion of its own: a
  ;; URL is not a command. If it ever were, this declaration would be run through
  ;; the shell -- and the failure would name bash rather than the URL.
  (write-servers! {"mistaken" {:url "http://127.0.0.1:1/mcp" :timeout 900}})
  (let [thread (str "mcp-nospawn-" (System/currentTimeMillis))
        failed (do (mcp/tools-for thread) (first (mcp/status thread)))]
    (is (= :failed (:status failed)))
    (is (str/includes? (:error failed) "http://127.0.0.1:1/mcp"))
    (is (not (str/includes? (:error failed) "bash")) "it was fetched, not spawned")
    (testing "and the declaration's shape is recorded as a url, not a command"
      (is (nil? (:command failed)))
      (is (nil? (get failed :url)) "the url is not echoed into the status either"))))

(deftest an-http-server-is-guarded-by-the-same-seam
  ;; 01's four assertions, re-run on the other transport: the seam reads the
  ;; TABLE, so a remote tool must be indistinguishable from a local one there.
  (with-fake-http :json {}
    (fn [port _]
      (write-servers! {"remote" (http-decl port)})
      (let [thread (str "mcp-http-seam-" (System/currentTimeMillis))]
        (testing "disabled"
          (tools/session-disable! thread "mcp__remote__echo")
          (let [{:keys [content error]} (call! thread "mcp__remote__echo" {:text "x"})]
            (is (true? error))
            (is (str/includes? content "disabled in this session")))
          (tools/session-enable! thread "mcp__remote__echo"))
        (testing "an approval rule parks it, and the reason says whose rule"
          (tools/session-require-approval! thread "mcp__remote__echo")
          (let [{:keys [parked]} (call! thread "mcp__remote__echo" {:text "x"})]
            (is (some? parked))
            (is (= :session-asks (:reason parked)))
            (testing "and an approved resume runs it"
              (tools/decide-approval! (:interrupt-id parked) :approved nil)
              (is (= "echo: x" (:content (call! thread "mcp__remote__echo" {:text "x"})))))))))))
