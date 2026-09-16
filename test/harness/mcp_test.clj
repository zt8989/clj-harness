(ns harness.mcp-test
  "MCP servers as a source of tools, against a REAL server process.

  The server is `test/harness/fake_mcp_server.js`: a real program on a real pipe,
  written by us but run by the same code path a third-party server would be. That
  is the only way to test the parts that exist BECAUSE a server is somebody else's
  process -- the spawn, the handshake, the roster, the request timeout, and the
  difference between a server that refuses a call and one that has stopped
  answering.

  Everything here is offline: no api-key, no model, and the only network is a pipe.
  The http-level half of the ticket (a real AG-UI run, the audit lines, a hook
  blocking a server call) lives in harness.http-test, because hooks only fire when
  the edge has bound the run's sink."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.home :as home]
            [harness.log :as log]
            [harness.mcp :as mcp]
            [harness.project :as project]
            [harness.tools :as tools]))

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
      (is (= #{"mcp__fake__echo" "mcp__fake__where" "mcp__fake__fail" "mcp__fake__hang"}
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

(deftest an-http-declaration-is-refused-by-name-for-now
  (write-servers! {"remote" {:url "https://example.invalid/mcp"}})
  (let [thread (str "mcp-http-" (System/currentTimeMillis))]
    (is (= {} (mcp/tools-for thread)))
    (let [failed (first (mcp/status thread))]
      (is (= :failed (:status failed)))
      (is (str/includes? (:error failed) "HTTP transport is not implemented")))))

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
