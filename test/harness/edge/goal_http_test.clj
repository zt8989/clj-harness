(ns harness.edge.goal-http-test
  "The goal's EDGE: the read route, the `goal` frames, and the command a person sends to
  move the goal -- driven through the real route functions with a recording channel in
  place of a socket.

  WHY THESE ARE NOT RUN-LEVEL TESTS. What ticket 03 has to get right is that a goal
  written from OUTSIDE any run (a person's `/goal ...`) lands in the record, in the row
  and on the wire -- and none of that needs a model. A real run would make these cases
  slow, non-deterministic and about the provider instead of about the door. The MODEL's
  half (a `create_goal` call inside a run, and the frame `:run/done` pushes for it) rides
  the same two functions and is asserted where it can be: `goal-send!` is called from the
  run loop, and `harness.cap.goal-test` drives the tools themselves.

  THE RECORD IS THE REAL ONE (`harness.edge.http`'s own writer, installed as `start!`
  installs it). A fixture that faked it would be testing the fake, and the whole point of
  the record half is that the ROW and the FOLD agree -- which is only true if the row was
  written from a real row of the conversation's file."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.goal :as goal]
            [harness.edge.commands :as commands]
            [harness.edge.http :as http]
            [harness.edge.mux :as mux]
            [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [org.httpkit.server :as hk]))

(def ^:private tid "goal-http")

(defn- log-file [thread-id]
  (home/log-file (io/file (home/projects-dir) http/unbound-workspace) thread-id))

(defn- answer
  "An `api-response`'s JSON body, keywordized."
  [resp]
  (json/read-str (String. ^bytes (:body resp) "UTF-8") :key-fn keyword))

(defn- fake-channel
  "A channel that remembers the bytes written to it -- the same `reify` of http-kit's own
  protocol the mux suites use, so `hk/send!` is the door under test."
  [sent]
  (reify hk/Channel
    (open? [_] true)
    (websocket? [_] true)
    (close [_] nil)
    (send! [_ data] (swap! sent conj data) true)
    (send! [_ data _close-after] (swap! sent conj data) true)
    (on-receive [_ _] nil)
    (on-close [_ _] nil)
    (on-ping [_ _] nil)))

(defn- frames [sent] (mapv #(json/read-str % :key-fn keyword) @sent))

(defn- clean! []
  (when (.exists (home/db-file))
    (db/with-transaction (fn [c] (db/execute! c "DELETE FROM goals"))))
  (goal/reset-armed!)
  (goal/reset-repaired!)
  (commands/reset-queues!)
  (reset! (var-get #'mux/connections) {})
  (reset! (var-get #'sessions/watchers) {})
  (let [f (io/file (log-file tid))] (when (.exists f) (.delete f))))

(use-fixtures :each
  (fn [f]
    (clean!)
    ;; THE REAL WRITER, as `start!` installs it -- a goal change is a fact row on the
    ;; conversation's own file, written by the edge's own door.
    (#'http/install-goal-writer!)
    (try (f) (finally (clean!)))))

(defn- goal-command
  [action & {:as kvs}]
  (merge {:type "goal" :action action} kvs))

;; ------------------------------------------------------------------- the read route

(deftest the-goal-route-answers-a-snapshot-and-never-404s
  (let [body (answer (#'http/goal-get "a-session-this-home-never-heard-of"))]
    (is (= {:threadId "a-session-this-home-never-heard-of" :goal nil :armed? false} body))))

(deftest the-route-carries-armed-alongside-the-goal-and-not-inside-it
  (#'http/commands-request tid [(goal-command "create" :objective "one objective")])
  (let [body (answer (#'http/goal-get tid))]
    (is (= tid (:threadId body)))
    (is (= "one objective" (get-in body [:goal :objective])))
    (is (true? (:armed? body)) "creating arms this process")
    (is (not (contains? (:goal body) :armed?))
        "the permission is a fact about this PROCESS, so it never rides inside the goal")))

;; ------------------------------------------------------------------- the commands

(deftest a-goal-command-with-no-run-is-executed-and-answered
  (let [resp (#'http/commands-request tid [(goal-command "create" :objective "ship it")])
        body (answer resp)]
    (is (= 200 (:status resp)))
    (is (= [{:type "goal" :ok true}] (:commands body)))
    (is (nil? (:runId body)) "no run was started -- see harness.edge.commands/types")
    (testing "and what it did is in the record, the row and the read route, all three"
      (let [rows (replay/read-records (log-file tid))]
        (is (= ["goal/change"] (mapv replay/kind rows)))
        (is (= "active" (:phase (replay/payload (first rows))))))
      (is (= "active" (:phase (goal/goal-for tid))))
      (is (= "ship it" (get-in (answer (#'http/goal-get tid)) [:goal :objective]))))))

(deftest every-goal-command-pushes-one-frame
  (let [sent (atom []) ch (fake-channel sent)]
    (#'http/mux-attend! "tok-goal" ch [{:threadId tid}])
    (let [before (count @sent)]
      (#'http/commands-request tid [(goal-command "create" :objective "watched")])
      (let [pushed (frames sent)
            new    (subvec pushed before)]
        (is (= 1 (count new)) "one change, one frame")
        (is (= "goal" (:type (first new))))
        (is (= tid (:threadId (first new))))
        (is (= "watched" (get-in (first new) [:goal :objective])))
        (is (true? (:armed? (first new)))))))
  (testing "and a clear pushes too, with a null goal -- a strip that heard nothing would keep drawing it"
    (let [sent (atom []) ch (fake-channel sent)]
      (#'http/mux-attend! "tok-clear" ch [{:threadId tid}])
      (let [g (goal/goal-for tid)
            before (count @sent)]
        (#'http/commands-request tid [(goal-command "clear" :goal_id (:id g) :revision (:revision g))])
        (let [new (subvec (frames sent) before)]
          (is (= 1 (count new)))
          (is (nil? (:goal (first new))))
          (is (false? (:armed? (first new)))))))))

(deftest a-refused-command-carries-the-capabilitys-own-sentence
  (#'http/commands-request tid [(goal-command "create" :objective "the first")])
  (let [body (answer (#'http/commands-request tid [(goal-command "create" :objective "the second")]))
        [outcome] (:commands body)]
    (is (= "goal" (:type outcome)))
    (is (= "goal-exists" (:reason outcome))
        "the wire carries no keywords: the reason arrives as its own name")
    (is (str/includes? (:error outcome) "unfinished goal")
        "the sentence a person reads is harness.cap.goal's, not a second one written here")
    (is (nil? (:ok outcome)))
    (is (= "the first" (:objective (goal/goal-for tid))) "and nothing was written")))

(deftest a-person-pauses-and-resumes-and-a-model-could-not
  (#'http/commands-request tid [(goal-command "create" :objective "paused by a person")])
  (let [g (goal/goal-for tid)]
    (#'http/commands-request tid [(goal-command "pause" :goal_id (:id g) :revision (:revision g))])
    (is (= "paused" (:phase (goal/goal-for tid))))
    (is (false? (goal/armed? tid)))
    (let [g (goal/goal-for tid)]
      (is (= :paused-by-human
             (try
               (goal/resume! tid {:id (:id g) :revision (:revision g)} {:by :model})
               nil
               (catch Exception e (:reason (ex-data e)))))
          "the model's resume is still refused at the capability")
      (testing "and the person's command lifts it"
        (let [body (answer (#'http/commands-request
                            tid [(goal-command "resume" :goal_id (:id g) :revision (:revision g))]))]
          (is (= [{:type "goal" :ok true}] (:commands body)))
          (is (= "active" (:phase (goal/goal-for tid))))
          (is (true? (goal/armed? tid))))))))

(deftest a-command-while-a-run-is-going-is-queued-and-taken-at-the-next-boundary
  (sessions/touch! tid)
  (#'http/register-run! tid "r-goal")
  (try
    (let [resp (#'http/commands-request tid [(goal-command "create" :objective "queued")])
          body (answer resp)]
      (is (= 200 (:status resp)) "NOT a second-run refusal: this is a message to the run that is")
      (is (= "r-goal" (:runId body)))
      (is (= 1 (:queued body)))
      (is (nil? (goal/goal-for tid)) "nothing ran yet -- the run takes it at its boundary")
      (testing "and the run's own pre-LLM step takes it, before the injections are derived"
        (let [history (#'http/before-llm-with-commands [{:role "user" :content "hello"}] tid)]
          (is (= "active" (:phase (goal/goal-for tid))))
          (is (= 2 (count history)) "the question, and the reminder a person's goal just earned")
          (is (str/starts-with? (:content (last history)) "<goal"))))
      (is (empty? (commands/queued tid)) "and the queue is empty afterwards"))
    (finally (#'http/unregister-run! tid "r-goal"))))

(deftest a-refused-command-inside-a-run-is-said-out-loud
  (#'http/commands-request tid [(goal-command "create" :objective "already here")])
  (sessions/touch! tid)
  (#'http/register-run! tid "r-refuse")
  (try
    (#'http/commands-request tid [(goal-command "clear" :goal_id "g-somebody-elses" :revision 99)])
    (let [history (#'http/before-llm-with-commands [{:role "user" :content "hello"}] tid)]
      (is (some #(and (string? (:content %))
                      (str/includes? (:content %) "has moved"))
                history)
          "a command a run executed has no HTTP answer, so the refusal rides the next call"))
    (finally (#'http/unregister-run! tid "r-refuse"))))

(deftest commands-that-need-a-run-are-refused-by-name
  (let [resp (#'http/commands-request tid [{:type "interrupt"}])
        body (answer resp)]
    (is (= 409 (:status resp)))
    (is (str/includes? (:error body) "needs a run"))
    (is (= 1 (count (:commands body)))))
  (testing "and a command nobody implements is refused rather than ignored"
    (let [body (answer (#'http/commands-request tid [{:type "steer" :text "go left"}]))]
      (is (str/includes? (:error body) "needs a run")
          "`steer` is ABOUT a step in progress, so with no run it is the run that is missing"))
    (let [body (answer (#'http/commands-request tid [{:type "compact"}]))]
      (is (str/includes? (:error body) "not implemented")
          "and a command this build only declares says exactly that")))
  (testing "and a type nobody knows names the ones this build reads"
    (let [body (answer (#'http/commands-request tid [{:type "summon"}]))]
      (is (str/includes? (:error body) "does not know a command of type"))
      (is (str/includes? (:error body) "goal")))))

(deftest a-queue-command-stays-queued
  (let [resp (#'http/commands-request tid [{:type "queue" :text "one for later"}])]
    (is (= 200 (:status resp)) "it needs no run: it waits for one")
    (is (= 1 (count (commands/queued tid))))))

;; ------------------------------------------------------- the record and the row agree

(deftest the-row-and-the-fold-say-the-same-thing-after-a-command
  (#'http/commands-request tid [(goal-command "create" :objective "one objective")])
  (let [g (goal/goal-for tid)]
    (#'http/commands-request tid [(goal-command "edit" :goal_id (:id g) :revision (:revision g)
                                                 :objective "one objective, reworded")])
    (let [g (goal/goal-for tid)]
      (#'http/commands-request tid [(goal-command "pause" :goal_id (:id g) :revision (:revision g))])
      (is (= (goal/goal-for tid)
             (goal/goal-of-records (replay/read-records (log-file tid))))
          "three commands, three rows, one answer")
      (is (= 3 (count (replay/read-records (log-file tid))))))))
