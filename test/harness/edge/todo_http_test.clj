(ns harness.edge.todo-http-test
  "The task list's reminder at the EDGE: the read route (`auto?` beside the list), the two
  `todo` commands, and the round driver -- driven through the real route functions with a
  recording channel in place of a socket.

  WHY THESE ARE NOT RUN-LEVEL TESTS, the same reason `goal-http-test` gives: what has to be
  right here is that a person's press lands somewhere a run can see, and that a finished run
  opens AT MOST ONE next run -- none of which needs a model. `drive-next-round!` and
  `drive-next-reminder-round!` take the door that starts a run as an argument (`open!`), so the
  decisions are pinned without a provider anywhere in sight.

  THE GOAL MACHINERY IS REAL, not stubbed, in the cases that ask 'who gets the round': the
  rule being tested is precisely that the goal's driver answers truthy when it opens one, and a
  belief about a stub would prove nothing."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.goal :as goal]
            [harness.cap.todos :as todos]
            [harness.edge.commands :as commands]
            [harness.edge.http :as http]
            [harness.edge.mux :as mux]
            [harness.edge.sessions :as sessions]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [org.httpkit.server :as hk]))

(def ^:private tid "todo-http")

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
    (db/with-transaction (fn [c] (db/execute! c "DELETE FROM todos")
                              (db/execute! c "DELETE FROM goals"))))
  (todos/reset-auto!)
  (goal/reset-armed!)
  (goal/reset-repaired!)
  (commands/reset-queues!)
  (reset! (var-get #'mux/connections) {})
  (reset! (var-get #'sessions/watchers) {})
  (let [f (io/file (log-file tid))] (when (.exists f) (.delete f))))

;; THE REAL SESSION SEAMS, so the goal half of `drive-after-run!` is the real thing rather than
;; the kernel's default ('no record reader is installed').
(use-fixtures :once (fn [f] (let [td (sessions/install!)] (f) (td))))
(use-fixtures :each
  (fn [f]
    (clean!)
    ;; THE REAL RECORD WRITER, as `start!` installs it: a goal command writes a fact row on the
    ;; conversation's own file, and a driver test needs the goal to really exist.
    (#'http/install-goal-writer!)
    (try (f) (finally (clean!)))))

(defn- opened-by
  "An `open!` that records what it was asked to start instead of starting it."
  [opened]
  (fn [input run-id] (swap! opened conj {:input input :run-id run-id}) {:status 200}))

;; ------------------------------------------------------------------- the read route

(deftest the-todos-route-answers-the-list-and-the-switch-beside-it
  (let [body (answer (#'http/todos-get "a-session-this-home-never-heard-of"))]
    (is (= {:threadId "a-session-this-home-never-heard-of" :todos [] :auto? false} body)
        "a stem nobody knows answers an empty list and an off switch, with no 404"))
  (todos/write! tid [{:content "read the code" :status "pending"}])
  (let [body (answer (#'http/todos-get tid))]
    (is (= [{:content "read the code" :status "pending"}] (:todos body)))
    (is (false? (:auto? body))))
  (todos/arm! tid)
  (let [body (answer (#'http/todos-get tid))]
    (is (true? (:auto? body)) "and the switch is the PROCESS's to report")
    (is (not (contains? (first (:todos body)) :auto?))
        "never inside an item: the list is the store's")))

;; ------------------------------------------------------------------- the commands
(deftest a-reminder-with-nothing-running-opens-the-round-it-needs
  (todos/write! tid [{:content "half done" :status "in_progress"}])
  (let [opened (atom [])
        resp   (with-redefs-fn {#'http/start-run (fn [input run-id]
                                               (swap! opened conj {:input input :run-id run-id})
                                               {:status 200})}
                             (fn [] (#'http/commands-request tid [{:type "todo" :action "remind"}])))
        body   (answer resp)]
    (is (= 200 (:status resp)))
    (is (= [{:type "todo" :ok true}] (:commands body)))
    (is (= 1 (count @opened)) "one round, started by the press")
    (let [turn (get-in (first @opened) [:input :append 0])]
      (is (= "user" (:role turn)) "the reminder is a REAL user message")
      (is (str/includes? (:content turn) "<system-reminder>"))
      (is (str/includes? (:content turn) "Task list reminder"))
      (is (str/includes? (:content turn) "half done")))
    (is (false? (todos/auto? tid)) "a manual press does not flip the automatic switch")))

(deftest a-reminder-while-a-run-is-going-is-injected-into-that-run
  (todos/write! tid [{:content "half done" :status "in_progress"}])
  (sessions/touch! tid)
  (#'http/register-run! tid "r-todo")
  (try
    (let [opened (atom [])
          resp   (with-redefs-fn {#'http/start-run (fn [input run-id]
                                                  (swap! opened conj {:input input :run-id run-id})
                                                  {:status 200})}
                                (fn [] (#'http/commands-request tid [{:type "todo" :action "remind"}])))
          body   (answer resp)]
      (is (= 200 (:status resp)) "NOT a second-run refusal: this is a message to the run that is")
      (is (= "r-todo" (:runId body)))
      (is (= 1 (:queued body)))
      (is (empty? @opened) "no second run was opened -- two runs of one conversation is the danger")
      (testing "and the run's own pre-LLM step is where the press lands"
        (let [history (#'http/before-llm-with-commands [{:role "user" :content "hello"}] tid)]
          (is (= 2 (count history)) "the question, and the reminder a person just asked for")
          (is (str/includes? (:content (last history)) "Task list reminder")))
        (testing "TAKEN ONCE: the next call is not told again"
          (is (= 1 (count (#'http/before-llm-with-commands [{:role "user" :content "hello"}] tid)))))
        (is (empty? (commands/queued tid)) "and the queue is empty afterwards")))
    (finally (#'http/unregister-run! tid "r-todo"))))

(deftest a-reminder-with-nothing-outstanding-is-refused-by-name
  (testing "a list every item of which is done"
    (todos/write! tid [{:content "done" :status "completed"}])
    (let [[outcome] (:commands (answer (#'http/commands-request tid [{:type "todo" :action "remind"}])))]
      (is (= "todo" (:type outcome)))
      (is (= "nothing-to-remind" (:reason outcome)))
      (is (str/includes? (:error outcome) "nothing left")
          "the sentence a person reads is the capability's, not a second one written here")))
  (testing "and a session with no list at all says the same thing"
    (let [[outcome] (:commands (answer (#'http/commands-request "never-wrote-one"
                                                               [{:type "todo" :action "remind"}])))]
      (is (= "nothing-to-remind" (:reason outcome))))))

(deftest the-auto-switch-is-a-command-and-the-route-reports-it
  (let [body (answer (#'http/commands-request tid [{:type "todo" :action "auto" :on true}]))]
    (is (= [{:type "todo" :ok true}] (:commands body)))
    (is (true? (todos/auto? tid)) "the switch went on in THIS process")
    (is (true? (:auto? (answer (#'http/todos-get tid))))))
  (testing "and off is the same door"
    (#'http/commands-request tid [{:type "todo" :action "auto" :on false}])
    (is (false? (todos/auto? tid))))
  (testing "a command with no `on` says what it is missing"
    (let [[outcome] (:commands (answer (#'http/commands-request tid [{:type "todo" :action "auto"}])))]
      (is (= "missing-on" (:reason outcome)))))
  (testing "and an action nobody implements names the two that exist"
    (let [[outcome] (:commands (answer (#'http/commands-request tid [{:type "todo" :action "sing"}])))]
      (is (= "unknown-todo-action" (:reason outcome)))
      (is (str/includes? (:error outcome) "remind")))))

;; ------------------------------------------------------------------- the round driver

(deftest the-driver-opens-the-next-round-when-the-switch-is-on-and-work-is-left
  (todos/write! tid [{:content "half done" :status "in_progress"}])
  (todos/arm! tid)
  (let [opened (atom [])]
    (#'http/drive-next-reminder-round! tid "r-run-1" (opened-by opened))
    (is (= 1 (count @opened)) "one round opened")
    (let [turn (get-in (first @opened) [:input :append 0])]
      (is (= "user" (:role turn)) "a round opens with a REAL user message")
      (is (str/includes? (:content turn) "<system-reminder>"))
      (is (str/includes? (:content turn) "half done"))
      (is (= "r-run-1-todo-reminder-1" (:id turn)) "and it is named, so it cannot enter twice"))
    (is (= 1 (:rounds (todos/fuse-for tid))))
    (testing "and the SAME list does not earn a second reminder -- the brake"
      (#'http/drive-next-reminder-round! tid "r-run-1" (opened-by opened))
      (is (= 1 (count @opened)) "the round that changed nothing is where it stops"))
    (testing "while a list that MOVED does"
      (todos/write! tid [{:content "half done" :status "completed"}
                         {:content "the next thing" :status "pending"}])
      (#'http/drive-next-reminder-round! tid "r-run-2" (opened-by opened))
      (is (= 2 (count @opened))))))

(deftest nothing-opens-when-the-switch-is-off-or-the-work-is-done
  (let [opened (atom [])]
    (todos/write! tid [{:content "x" :status "pending"}])
    (#'http/drive-next-reminder-round! tid "r1" (opened-by opened))
    (is (empty? @opened) "the switch is off -- nobody asked this process to push")
    (todos/arm! tid)
    (todos/write! tid [{:content "x" :status "completed"}])
    (is (false? (todos/auto? tid)) "and writing a finished list turned the switch off")
    (#'http/drive-next-reminder-round! tid "r2" (opened-by opened))
    (is (empty? @opened))))

(deftest the-fuse-stops-the-rounds-at-max-rounds
  (todos/write! tid [{:content "x" :status "pending"}])
  (todos/arm! tid)
  (let [opened (atom [])
        open!  (opened-by opened)]
    ;; EVERY round must move the list to be allowed past the brake, so the list is rewritten
    ;; with new words each time -- what stops the loop here is the FUSE, and that is the point.
    (dotimes [i 30]
      (todos/write! tid [{:content (str "x " i) :status "pending"}])
      (#'http/drive-next-reminder-round! tid (str "r" i) open!))
    (is (= todos/default-max-rounds (count @opened)) "the cap is a fuse: no more than it says")
    (is (= todos/default-max-rounds (:rounds (todos/fuse-for tid))))))

(deftest one-finished-run-opens-at-most-one-next-run
  (todos/write! tid [{:content "half done" :status "in_progress"}])
  (todos/arm! tid)
  (let [opened (atom [])
        open!  (opened-by opened)]
    (testing "a goal that CAN advance takes the round"
      (#'http/commands-request tid [{:type "goal" :action "create" :objective "ship it"}])
      (#'http/drive-after-run! tid "r1" true open!)
      (is (= 1 (count @opened)) "one run, not two")
      (is (str/includes? (get-in (first @opened) [:input :append 0 :content]) "<goal"))
      (is (= 0 (:rounds (todos/fuse-for tid))) "and the task list's driver opened nothing"))
    (testing "a goal nobody is pushing (paused) leaves the round to the task list"
      (let [g (goal/goal-for tid)]
        (#'http/commands-request tid [{:type "goal" :action "pause"
                                       :goal_id (:id g) :revision (:revision g)}]))
      (todos/write! tid [{:content "half done, reworded" :status "in_progress"}])
      (#'http/drive-after-run! tid "r2" true open!)
      (is (= 2 (count @opened)))
      (is (str/includes? (get-in (second @opened) [:input :append 0 :content]) "Task list reminder")
          "the reminder is the message the round opens with"))))

;; ------------------------------------------------------------------- the frame

(deftest a-switch-change-pushes-one-frame
  ;; THE SWITCH HAS A WRITE POINT, so by `docs/rules/panel-data.md` it has a frame: a strip in
  ;; ANOTHER page that only re-read on the two facts would go on drawing the state it read last.
  (let [sent (atom []) ch (fake-channel sent)]
    (#'http/mux-attend! "tok-todo" ch [{:threadId tid}])
    ;; THE CONNECTION ITSELF DRAWS A WINDOW FIRST -- that frame is not this test's subject.
    (let [before (count @sent)]
      (#'http/commands-request tid [{:type "todo" :action "auto" :on true}])
      (let [new (subvec (vec (frames sent)) before)]
        (is (= 1 (count new)) "one change, one frame")
        (is (= "todos" (:type (first new))))
        (is (= tid (:threadId (first new))))
        (is (true? (:auto? (first new))) "the whole answer, switch included")
        (is (= [] (:todos (first new))) "and the list rides along")))))
