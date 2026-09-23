(ns harness.cap.claims-test
  "harness.cap.claims' external behavior: a claim is a row in the store that says
  which process is serving a conversation, and the three rules that go with it --
  take one, hand one back, take one over from a process that is gone.

  TWO TESTS ARE ABOUT A REAL SECOND PROCESS, and they are the reason this file
  forks a JVM at all: `alive?` is a question only the OPERATING SYSTEM can answer
  about a pid, a shutdown hook is code only an exiting JVM runs, and neither is
  visible to a `with-redefs`. Everything else here is in-process, including the
  live-stranger cases -- a row whose pid is this process's own and whose INSTANCE is
  somebody else's is exactly what a live other process leaves behind, and it can be
  written without booting one (the same trick harness.edge.sessions-test uses).

  THE CLAIMS TABLE IS EMPTIED AROUND EVERY TEST: the store is the run-wide one the
  test runner isolated (harness.test-runner/isolate!), which every namespace in the
  run shares, and a claim this file forgot would be a refusal in somebody else's
  case. Wiping it is what makes these cases independent."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.claims :as claims]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.infra.shell :as shell]
            [harness.test-support :as support])
  (:import (java.io File)
           (java.util.concurrent TimeUnit)))

;; ----------------------------------------------------------------- the fixtures

(defn- wipe-claims! []
  (db/with-transaction (fn [c] (db/execute! c "DELETE FROM session_claims"))))

(defn- empty-claims [f]
  (wipe-claims!)
  (f)
  (wipe-claims!))

(use-fixtures :each empty-claims)

;; ------------------------------------------------------------- asking the OS

(defn- dead-pid
  "A pid the operating system has just finished with: a short-lived process that has
  been WAITED FOR, so it is gone by the time this answers.

  NODE, because this repo already requires it (the ui suite, `child-command`) and
  because `ProcessHandle/of` takes the number node reports for itself. A pid is a
  number the OS reuses, so this is a claim about a moment rather than a guarantee:
  what the tests do with it is ask `holder` straight away."
  []
  (let [p (-> (ProcessBuilder. ^java.util.List (vec ["node" "-e" ""]))
              (.redirectErrorStream true)
              (.start))]
    (.waitFor p 30 TimeUnit/SECONDS)
    (.pid p)))

(defn- row-for [thread-id]
  (first (db/select "SELECT * FROM session_claims WHERE thread_id = ?" thread-id)))

(defn- foreign!
  "Leave behind the row a LIVE OTHER PROCESS would leave: this process's own pid and
  start instant (so the OS says a process is there), under an instance that is not
  ours (so 'is it mine?' is answered no).

  THIS IS THE POINT OF THE INSTANCE FIELD, stated as something a test can make: the
  liveness question is about the pid, and ownership is a comparison of ids that needs
  no OS call at all."
  ([thread-id] (foreign! thread-id "somebody-else"))
  ([thread-id instance]
   (let [us (claims/this-process)]
     (db/with-transaction
       (fn [c]
         (db/execute! c "INSERT INTO session_claims
                           (thread_id, instance, token, pid, started_at, since)
                         VALUES (?, ?, ?, ?, ?, ?)"
                      thread-id instance (str (java.util.UUID/randomUUID))
                      (:pid us) (:started-at us) (System/currentTimeMillis))))
     (row-for thread-id))))

;; -------------------------------------------------------------------- the cases

(deftest a-claim-is-a-row-that-says-whose-it-is
  (let [token (:token (claims/take! "cl-own"))
        row   (row-for "cl-own")
        us    (claims/this-process)]
    (testing "the row names this process, in the two ways that answer two questions"
      (is (= (:instance us) (:instance row)) "the instance is what 'is this mine?' compares")
      (is (= (:pid us) (:pid row)) "the pid is what a later process asks the OS about")
      (is (= (:started-at us) (:started-at row))
          "and the start instant is what keeps a reused pid from pinning it forever"))
    (testing "and it carries the claim's own token, not the process's identity"
      (is (string? token))
      (is (= token (:token row)))
      (is (pos? (:since row)) "a claim says when it was taken, for the sentence a client reads"))
    (testing "the table answers the two questions separately"
      (is (some? (claims/holder "cl-own")) "somebody alive holds it")
      (is (claims/mine? (claims/holder "cl-own")) "and that somebody is us"))
    (testing "a conversation nobody has claimed answers nil, not an error"
      (is (nil? (claims/holder "cl-never"))))))

(deftest taking-a-claim-twice-keeps-one-row-and-moves-the-token
  ;; THE TOKEN MOVES ON EVERY TAKE, which is not tidiness: it is what makes a
  ;; RELEASE safe when a session is born again between a put-away deciding to let go
  ;; and letting go (see `release!`). One row, always -- a second take must not open
  ;; a second claim on one conversation.
  (let [first-token  (:token (claims/take! "cl-again"))
        first-since  (:since (row-for "cl-again"))
        second-token (:token (claims/take! "cl-again"))]
    (is (= 1 (count (db/select "SELECT * FROM session_claims WHERE thread_id = ?" "cl-again"))))
    (is (not= first-token second-token))
    (is (<= first-since (:since (row-for "cl-again"))))
    (is (claims/mine? (claims/holder "cl-again")))))

(deftest a-live-other-process-is-refused-by-name
  (let [row   (foreign! "cl-busy")
        held  (claims/holder "cl-busy")]
    (testing "the holder is answered, and it is not ours"
      (is (= "somebody-else" (:instance held)))
      (is (not (claims/mine? held))))
    (testing "taking it throws a NAMED refusal carrying the process that is in the way"
      (let [e (try (claims/take! "cl-busy") nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? e) "a live holder is not something to shrug at")
        (is (= :session-claimed (:reason (ex-data e))))
        (is (= "cl-busy" (:thread-id (ex-data e))))
        (is (= (:pid row) (:pid (:holder (ex-data e)))))
        (testing "and the sentence says which process, so a person can act on it"
          (is (str/includes? (ex-message e) (str (:pid row)))))))
    (testing "and the refused take left the row exactly as it was"
      (is (= row (row-for "cl-busy"))))))

(deftest a-process-that-is-gone-is-nobody
  (let [pid (dead-pid)]
    (db/with-transaction
      (fn [c]
        (db/execute! c "INSERT INTO session_claims
                          (thread_id, instance, token, pid, started_at, since)
                        VALUES (?, ?, ?, ?, ?, ?)"
                     "cl-dead" "long-gone" (str (java.util.UUID/randomUUID))
                     pid 1 (System/currentTimeMillis))))
    (testing "a pid the OS has finished with is not a holder"
      (is (false? (support/alive? pid)) "the premise: this pid really is gone")
      (is (nil? (claims/holder "cl-dead"))))
    (testing "and the next process along takes it over"
      (let [token (:token (claims/take! "cl-dead"))]
        (is (string? token))
        (is (claims/mine? (claims/holder "cl-dead")))))))

(deftest a-pid-that-was-reused-is-not-the-owner-either
  ;; THE CASE A PID ALONE CANNOT ANSWER. A number the OS has handed to somebody else
  ;; says 'a process is there' about a claim whose process is gone, and the claim
  ;; would pin the conversation to a stranger for as long as that stranger lives. The
  ;; start instant is what tells them apart -- here it is a live pid (our own) with a
  ;; start time that does not match, which is exactly what a reused number looks like.
  (let [us (claims/this-process)]
    (db/with-transaction
      (fn [c]
        (db/execute! c "INSERT INTO session_claims
                          (thread_id, instance, token, pid, started_at, since)
                        VALUES (?, ?, ?, ?, ?, ?)"
                     "cl-reused" "the-previous-owner" (str (java.util.UUID/randomUUID))
                     (:pid us) 1 (System/currentTimeMillis))))
    (is (true? (support/alive? (:pid us))) "the premise: the pid itself is very much alive")
    (testing "so the row is stale, and the conversation is free"
      (is (nil? (claims/holder "cl-reused")))
      (is (string? (:token (claims/take! "cl-reused")))))))

(deftest a-release-lets-go-of-the-claim-it-was-given-and-no-other
  (let [old (:token (claims/take! "cl-release"))
        new (:token (claims/take! "cl-release"))]
    (testing "a token from an earlier take does not delete the claim that replaced it"
      (is (false? (claims/release! "cl-release" old)))
      (is (some? (row-for "cl-release")) "the row is still there, and still ours"))
    (testing "the token the claim is actually held under does"
      (is (true? (claims/release! "cl-release" new)))
      (is (nil? (row-for "cl-release"))))
    (testing "releasing what is not there is not a failure"
      (is (false? (claims/release! "cl-release" new))))))

(deftest hand-over-moves-a-row-this-process-holds-and-nothing-else
  ;; THE RACE `hand-over!` EXISTS FOR, at the size a test can run: two takes in one
  ;; process, the second one's token is the row's, and the entry that won the session
  ;; table's swap holds the FIRST one. The loser straightens the row out so the
  ;; winner's put-away can hand it back.
  (let [winner (:token (claims/take! "cl-hand"))
        loser  (:token (claims/take! "cl-hand"))]
    (is (= loser (:token (row-for "cl-hand"))) "the second take is what the row carries")
    (is (true? (claims/hand-over! "cl-hand" loser winner)))
    (is (= winner (:token (row-for "cl-hand"))))
    (testing "and the winner's release now works"
      (is (true? (claims/release! "cl-hand" winner))))
    (testing "a hand-over of a token this process is not holding does nothing"
      (is (false? (claims/hand-over! "cl-hand" "not-a-token" "another"))))))

(deftest release-all-lets-go-of-this-processs-claims-and-leaves-the-others
  (claims/take! "cl-all-1")
  (claims/take! "cl-all-2")
  (foreign! "cl-all-other")
  (is (= 2 (claims/release-all!)))
  (testing "ours are gone"
    (is (nil? (row-for "cl-all-1")))
    (is (nil? (row-for "cl-all-2"))))
  (testing "and a row that is not ours is not ours to delete"
    (is (some? (row-for "cl-all-other")))))

(deftest the-exit-hook-is-installed-once
  (claims/reset-exit-hook!)
  (try
    (let [installs (atom 0)]
      (with-redefs [claims/install-hook! (fn [_] (swap! installs inc))]
        (dotimes [_ 5] (claims/ensure-exit-hook!)))
      (is (= 1 @installs) "five births, one hook: 'did I install it' has one answer"))
    (finally (claims/reset-exit-hook!)
             (claims/ensure-exit-hook!) "leave this process with the hook it should have")))

;; ---------------------------------------------------- a real second process
;;
;; THE PART NO IN-PROCESS TEST CAN REACH. `alive?` asks the OPERATING SYSTEM about a
;; pid, and the release on the way out is a SHUTDOWN HOOK: a JVM that never exits
;; never runs it, and a `with-redefs` proves nothing about either. So this case forks
;; one, and it is the ticket's own acceptance case -- a second process serving a
;; conversation, normal exit, and a process that was killed.

(def ^:private child-form
  "The program the forked JVM runs: take THREAD's claim the way a run does -- through
  the session table, so the real birth path is what claims it, not this namespace's
  own verb -- then say its pid, then stay alive until the parent says go.

  IT WRITES ITS PID RATHER THAN LETTING THE PARENT GUESS IT: the parent's `Process`
  object knows the pid of the `clojure` launcher, and the JVM underneath is where the
  claim's pid comes from -- the same distinction `harness.test-support/child-command`
  documents for a shell and its child. Asking the process itself is the one spelling
  that is right on both platforms."
  (str "(require '[harness.edge.sessions :as sessions])"
       " (let [thread (System/getenv \"CLJ_HARNESS_TEST_THREAD\")"
       "       answer (System/getenv \"CLJ_HARNESS_TEST_OUT\")"
       "       go     (System/getenv \"CLJ_HARNESS_TEST_GO\")]"
       "   (sessions/touch! thread)"
       "   (spit answer (str (.pid (java.lang.ProcessHandle/current))) :encoding \"UTF-8\")"
       "   (loop []"
       "     (when-not (.exists (java.io.File. go))"
       "       (Thread/sleep 50)"
       "       (recur)))"
       "   (System/exit 0))"))

(defn- start-child!
  "A second JVM, given this run's home and an OS home of its own, holding THREAD's
  claim until told to go (or killed). The three files it needs travel by name.

  BOTH HOMES TRAVEL: a child JVM inherits nothing of this process's root override, and
  a child left to the JVM's own `user.home` would read the DEVELOPER'S ~/AGENTS.md --
  the rule for any test that forks a JVM (AGENTS.md). The home it is given is the
  runner's own per-run temp root, which is the point here rather than a shortcut: two
  processes and one store is the situation this capability exists for.

  THE FORM TRAVELS AS A FILE, not as `-e <form>`: an embedded double quote does not
  survive the JVM's argv on Windows (harness.cap.project-test records that discovery),
  and every string in this form is quoted."
  [^File script ^File answer ^File go ^String thread]
  (let [pb (doto (ProcessBuilder. ^java.util.List
                                  (vec ["clojure"
                                        (str "-J-Duser.home=" (support/temp-dir "claims-child-home"))
                                        "-M" (.getAbsolutePath script)]))
             (.directory (io/file (System/getProperty "user.dir")))
             (.redirectErrorStream true)
             ;; ITS OUTPUT GOES TO A FILE, not to a pipe this test never drains: a
             ;; child that fills a pipe nobody reads blocks forever, and the interesting
             ;; part of a failed boot is worth being able to read afterwards.
             (.redirectOutput (io/file (.getParentFile script) "child.out")))]
    (.put (.environment pb) "CLJ_HARNESS_HOME" (home/root))
    (.put (.environment pb) "CLJ_HARNESS_TEST_THREAD" thread)
    (.put (.environment pb) "CLJ_HARNESS_TEST_OUT" (.getAbsolutePath answer))
    (.put (.environment pb) "CLJ_HARNESS_TEST_GO" (.getAbsolutePath go))
    (.start pb)))

(defn- until
  "Poll F until it answers truthy, or MS runs out -- answering F's last value either
  way. A child JVM boots in its own time, so every wait here is bounded and none of
  them is a fixed sleep."
  [f ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (let [v (f)]
        (if (or v (< deadline (System/currentTimeMillis)))
          v
          (do (Thread/sleep 50) (recur)))))))

(defn- child-answer
  "What the child wrote into its answer file, or nil while it has not written yet.
  A FILE RATHER THAN STDOUT: since JDK 24 the first native call prints four lines
  about native access to stdout, and a stream read line by line would trip over the
  warnings before it reached the answer."
  [^File answer]
  (when (and (.exists answer) (pos? (.length answer)))
    (str/trim (slurp answer :encoding "UTF-8"))))

(defn- go!
  "Tell the child it may exit, and wait for it to do so. Answers its exit code, or
  nil if it did not leave in time."
  [^Process p ^File go]
  (spit go "go" :encoding "UTF-8")
  (when (.waitFor p 120 TimeUnit/SECONDS)
    (.exitValue p)))

(defn- process-log
  "The process log under the run's own root -- where `harness.infra.log` writes
  whatever the process chose to say out loud."
  []
  (let [f (io/file (home/root) "logs" "harness.infra.log")]
    (if (.exists f) (slurp f :encoding "UTF-8") "")))

(deftest a-second-jvm-owns-a-conversation-until-it-goes-away
  ;; THE TICKET'S OWN ACCEPTANCE CASE, and the one thing this file cannot fake: a
  ;; REAL second process. It claims a conversation the way a run does, and while it
  ;; lives this process may not have it. How it ends is the second half -- a normal
  ;; exit hands the claim back through the shutdown hook, and a `kill -9` cannot, so
  ;; the row is left for the next process to take over out loud.
  (let [dir     (support/temp-dir "claims-two-jvm")
        script  (io/file dir "child.form.clj")
        answer  (io/file dir "child.answer")
        go      (io/file dir "child.go")
        thread  "cl-two-jvm"
        _       (spit script child-form :encoding "UTF-8")
        child   (start-child! script answer go thread)]
    (try
      (let [pid (until #(some-> (child-answer answer) parse-long) 120000)]
        (testing "the second process booted and claimed the conversation"
          (is (some? pid) "the child never said its pid -- see its output above")
          (is (claims/holder thread) "and the store says somebody is serving it")
          (is (= pid (:pid (claims/holder thread))) "that somebody is the child"))
        (testing "so this process is refused, by name"
          (let [e (try (claims/take! thread) nil (catch clojure.lang.ExceptionInfo e e))]
            (is (= :session-claimed (:reason (ex-data e))))
            (is (= pid (:pid (:holder (ex-data e)))))
            (is (str/includes? (ex-message e) (str pid))
                "the sentence names the process that is in the way")))
        (testing "and reads are not touched by any of this -- only actions are"
          (is (some? (db/select "SELECT * FROM session_claims WHERE thread_id = ?" thread))))
        (testing "a normal exit hands the claim back, with no cleanup by anybody"
          (is (zero? (go! child go)))
          (is (nil? (claims/holder thread))
              "the child's shutdown hook let go of it on the way out")
          (is (string? (:token (claims/take! thread))) "so this process may have it"))
        (claims/release-all!)
        (testing "a process that is KILLED cannot, and the row it left says so"
          ;; The same child again, told to stay: this one is not given a `go` file, so
          ;; nothing but a kill takes it -- and a kill cannot run a shutdown hook.
          (let [answer2 (io/file dir "child2.answer")
                go2     (io/file dir "child2.go")
                script2 (io/file dir "child2.form.clj")
                _       (spit script2 child-form :encoding "UTF-8")
                killed  (start-child! script2 answer2 go2 thread)
                pid2    (until #(some-> (child-answer answer2) parse-long) 120000)]
            (try
              (is (= pid2 (:pid (claims/holder thread))) "the second child holds it too")
              ;; A KILL THAT TAKES THE CHILD WITH IT. `.destroyForcibly` on the launcher is the
              ;; whole act on POSIX, where `clojure` execs the JVM and the process IS the claim's
              ;; holder; on Windows the launcher stays and the JVM it started lives on -- so the
              ;; holder was still alive, the row was not stale and the takeover never happened.
              ;; `stop-tree!` is what the harness itself does at a time limit (`harness.infra.shell`
              ;; `kill-tree!`: descendants first, then the parent), which is the same act on both
              ;; platforms -- and a hard kill cannot run a shutdown hook either way.
              (shell/stop-tree! killed)
              (.waitFor killed 60 TimeUnit/SECONDS)
              (is (false? (support/alive? pid2)) "it is gone, with the hook never run")
              (testing "the row is stale rather than authoritative"
                (is (nil? (claims/holder thread))
                    "a pid the OS has finished with is not a holder"))
              (testing "and the next process to want it takes it over, saying so"
                ;; THE LINE IS MATCHED BY THE PROCESS IT NAMES, not by the word: the
                ;; process log belongs to the whole run, and the other cases in this file
                ;; take claims over too -- a `taken-over` line was already there.
                (let [said (re-pattern (str "taken-over .*from-pid=" pid2
                                            ".*thread-id="
                                            (java.util.regex.Pattern/quote thread)))]
                  (is (string? (:token (claims/take! thread))))
                  (is (until #(re-find said (process-log)) 5000)
                      (str "the takeover is written down, naming the process that did"
                           " not hand it back: " (process-log)))))
              (finally
                (when (.isAlive killed) (.destroyForcibly killed)))))))
      (finally
        (when (.isAlive ^Process child) (.destroyForcibly ^Process child))))))


