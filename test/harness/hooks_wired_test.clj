(ns harness.hooks-wired-test
  "The three hook points that are wired, through the real HTTP edge: SessionStart
  on a session's first run, PostToolUse after a successful tool, Stop when a run
  ends normally -- plus the regression that matters most, that a session which
  declares nothing behaves exactly as it did before hooks existed.

  The edge is the layer that has to be exercised here, because it is the edge that
  binds the run's hook sink: everything below it (offline tools, replay) fires
  nothing by design, and a test that only called harness.loop would prove nothing
  about the wiring."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.fake :as fake]
            [harness.home :as home]
            [harness.http :as http]
            [harness.providers :as providers]
            [harness.project :as project]
            [harness.tools :as tools]
            [harness.wire :as wire])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def ^:private script
  [{:content ""
    :tool-calls [{:id "c1" :name "read" :arguments {:path "deps.edn"}}]}
   {:content "done"}])

(defn- with-server [port thread f]
  (providers/use-provider! thread (fake/scripted script))
  (let [stop (http/start! {:port port})]
    (try (f) (finally (stop) (providers/use-provider! thread nil)))))

(defn- post-run [port thread-id]
  (let [body (json/write-str {:threadId thread-id
                              :runId (str (java.util.UUID/randomUUID))
                              :messages [{:id "u1" :role "user" :content "go"}]
                              :tools [] :context []})
        req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/")))
                 (.header "Content-Type" "application/json")
                 (.header "Accept" "text/event-stream")
                 (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                 (.build))]
    (.send (HttpClient/newHttpClient) req
           (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))))

(defn- log-file [thread] (io/file (str (home/logs-dir)) (str thread ".jsonl")))

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

(defn- write-hooks! [decls]
  (.mkdirs (io/file (home/root)))
  (spit (str (home/root) "/hooks.edn") (pr-str decls) :encoding "UTF-8"))

(defn- wipe! []
  (io/delete-file (io/file (home/root) "hooks.edn") true)
  (io/delete-file (io/file (home/root) "hooks-fired.txt") true))

(defn- marker-script [marker label]
  (let [dir (str (home/root) "/hook-scripts")]
    (.mkdirs (io/file dir))
    (let [f (io/file dir (str label ".sh"))]
      (spit f (str "#!/bin/sh\ncat >> " marker "\necho \"" label "\" >> " marker "\n") :encoding "UTF-8")
      (.setExecutable f true)
      (str f))))

;; --------------------------------------------------- nothing declared, nothing

(deftest a-session-with-no-hooks-behaves-exactly-as-before
  (wipe!)
  (with-server
   8121 "hw-none"
   (fn []
     (io/delete-file (log-file "hw-none") true)
     (post-run 8121 "hw-none")
     (let [ls (wait-for (log-file "hw-none")
                        (fn [ls] (some #(= "Stop" (:kind %)) (hook-lines ls)))
                        1500)]
       (testing "no hook/ line is written at all -- an untouched run leaves no trace"
         (is (empty? (hook-lines ls))))
       (testing "and the run itself is complete and well-formed"
         (is (some #(= "RUN_FINISHED" (get-in % [:payload :type]))
                   (filter #(= "event" (:kind %)) ls))))))))

;; ------------------------------------------------------------------ SessionStart

(deftest session-start-fires-once-on-a-sessions-first-run
  (wipe!)
  (write-hooks! {:session-start [{:command (marker-script (str (home/root) "/hooks-fired.txt")
                                                          "session-start")}]})
  (with-server
   8122 "hw-start"
   (fn []
     (io/delete-file (log-file "hw-start") true)
     (post-run 8122 "hw-start")
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
         (post-run 8122 "hw-start")
         (Thread/sleep 600)
         (let [ls2 (log-lines (log-file "hw-start"))]
           (is (= 1 (count (filter #(= "hook/SessionStart" (:kind %)) ls2))))))))))

;; ------------------------------------------------------------------ PostToolUse

(deftest post-tool-use-fires-after-a-tool-ran-and-is-told-which-one
  (wipe!)
  (let [marker (str (home/root) "/hooks-fired.txt")]
    (write-hooks! {:post-tool-use [{:command (marker-script marker "post-tool-use")
                                    :matcher "read"}]})
    (with-server
     8123 "hw-post"
     (fn []
       (io/delete-file (log-file "hw-post") true)
       (post-run 8123 "hw-post")
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
    (write-hooks! {:post-tool-use [{:command (marker-script marker "post-tool-use")
                                    :matcher "bash"}]})
    (with-server
     8124 "hw-nomatch"
     (fn []
       (io/delete-file (log-file "hw-nomatch") true)
       (post-run 8124 "hw-nomatch")
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
    (write-hooks! {:stop [{:command (marker-script marker "stop")}]})
    (with-server
     8125 "hw-stop"
     (fn []
       (io/delete-file (log-file "hw-stop") true)
       (post-run 8125 "hw-stop")
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
  (write-hooks! {:session-start [{:command (marker-script (str (home/root) "/hooks-fired.txt")
                                                          "start")}]
                 :stop          [{:command (marker-script (str (home/root) "/hooks-fired.txt")
                                                          "stop")}]})
  (providers/use-provider! "hw-frames-off" (fake/scripted script))
  (with-server
   8126 "hw-frames-on"
   (fn []
     (let [with-hooks (wire/frames-from-sse
                       (.body (post-run 8126 "hw-frames-on")))]
       (wipe!)
       (let [without (try
                       (wire/frames-from-sse (.body (post-run 8126 "hw-frames-off")))
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
      (str f))))

(deftest a-pretooluse-gate-can-refuse-a-call-and-the-model-is-told-why
  (wipe!)
  (write-hooks! {:pre-tool-use [{:command (gate-script 2 "no reads before breakfast")}]})
  (with-server
   8127 "hw-gate"
   (fn []
     (io/delete-file (log-file "hw-gate") true)
     (let [resp (post-run 8127 "hw-gate")
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
  (write-hooks! {:pre-tool-use [{:command (gate-script 0 "fine")}]})
  (with-server
   8128 "hw-allow"
   (fn []
     (io/delete-file (log-file "hw-allow") true)
     (let [frames (wire/frames-from-sse (.body (post-run 8128 "hw-allow")))
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
  (write-hooks! {:pre-tool-use [{:command (gate-script 2 "denied")}]})
  (with-server
   8129 "hw-audit"
   (fn []
     (io/delete-file (log-file "hw-audit") true)
     (post-run 8129 "hw-audit")
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
    (write-hooks! {:pre-tool-use [{:command (marker-script marker "gate")}]})
    (tools/session-disable! "hw-disabled" "read")
    (try
      (with-server
       8130 "hw-disabled"
       (fn []
         (io/delete-file (log-file "hw-disabled") true)
         (post-run 8130 "hw-disabled")
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
