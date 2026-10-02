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

;; THE REAL SESSION SEAMS, so `goal-from-records` is the session's own reader rather than the
;; kernel's default ('no record reader is installed') -- the fold and the row are compared here,
;; and a comparison against a default would prove nothing.
(use-fixtures :once (fn [f] (let [td (sessions/install!)] (f) (td))))
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

(deftest three-places-one-vocabulary
  ;; THE FIELD NAMES ARE A CONTRACT BETWEEN THREE CONSUMERS (`.scratch/goal` decision 3): the
  ;; capability's snapshot, the ROUTE's answer and the pushed FRAME. `harness.cap.goal`'s own
  ;; suite pins the first, this one pins the second and the third against it -- and
  ;; `ui/test/suites/goal.tsx` pins the client's type against the wire. A field added in one
  ;; place only is a client reading `undefined`, so the day somebody adds one, this fails.
  (#'http/commands-request tid [(goal-command "create" :objective "one vocabulary")])
  (let [sent (atom []) ch (fake-channel sent)]
    (#'http/mux-attend! "tok-keys" ch [{:threadId tid}])
    (#'http/commands-request tid [(goal-command "pause" :goal_id (:id (goal/goal-for tid))
                                               :revision (:revision (goal/goal-for tid)))])
    (let [body  (answer (#'http/goal-get tid))
          frame (last (frames sent))
          wired (set (keys (:goal body)))]
      (is (= #{:id :revision :objective :phase :rounds :max-rounds :updated-at} wired)
          (str "a snapshot with nothing pending wears exactly `harness.cap.goal/snapshot-keys`'"
               " required half: " (pr-str wired)))
      (is (= wired (set (keys (:goal frame))))
          "and the frame carries the same fields as the route -- one payload, two doors")
      (is (= #{:threadId :goal :armed?} (set (keys body)))
          "the envelope is three keys, and the permission is one of them")
      (is (not (contains? (:goal body) :armed?)) "never inside the goal")
      (is (not (contains? (:goal frame) :armed?)) "at either door"))))

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

;; ------------------------------------------------------------- the round driver
;;
;; THE DRIVER IS DECIDED, NOT TIMED. Its tests call the decision directly with a recording
;; opener in place of `start-run`, which is why they can pin all four gates and the brake
;; without a provider anywhere in sight.

(defn- note
  "Run some kernel events through `note-tool-event`, as the run loop does."
  [state & events]
  (reduce (fn [s ev] (#'http/note-tool-event s ev)) state events))

(defn- opened-by
  "An `open!` that records what it was asked to start instead of starting it."
  [opened]
  (fn [input run-id] (swap! opened conj {:input input :run-id run-id}) nil))

(deftest the-progress-criterion-is-a-file-changing-tool-that-did-not-throw
  (let [entered (fn [name] {:type :tool/pre-execute :id "c1" :name name :outcome :pass})]
    (is (false? (#'http/changed-a-file? (note {} (entered "read"))))
        "a round that only READ is the loop this brake exists to stop")
    (is (false? (#'http/changed-a-file? (note {} (entered "bash"))))
        "bash is the stated cost: a push made through it does not count")
    (is (true? (#'http/changed-a-file? (note {} (entered "write")))))
    (is (true? (#'http/changed-a-file? (note {} (entered "replace")))))
    (testing "but a call whose execution threw changed nothing"
      (is (false? (#'http/changed-a-file?
                      (note {} (entered "edit")
                            {:type :tool/execute :id "c1" :name "edit" :error "no such anchor"})))))
    (testing "and a call parked for approval never ran at all"
      (is (false? (#'http/changed-a-file?
                      (note {} {:type :tool/pre-execute :id "c2" :name "write"
                                    :outcome :needs-approval})))))))

(deftest only-a-normal-ending-is-a-normal-ending
  (is (true? (#'http/ended-normally? {:terminal "RUN_FINISHED"})))
  (is (false? (#'http/ended-normally? {:terminal "RUN_ERROR"})) "a failure is not a round")
  (is (false? (#'http/ended-normally? {:terminal "RUN_CANCELLED"})))
  (is (false? (#'http/ended-normally? {})) "a run that never reached a terminal is not a round")
  (testing "an interrupt ends on RUN_FINISHED too, and is still not a round"
    (is (false? (#'http/ended-normally?
                 (note {:terminal "RUN_FINISHED"} {:type :run/interrupt}))))))

(deftest an-active-armed-goal-with-progress-opens-the-next-round
  (#'http/commands-request tid [(goal-command "create" :objective "keep going")])
  (let [opened (atom [])]
    (#'http/drive-next-round! tid "r-run-1" true (opened-by opened))
    (is (= 1 (count @opened)) "one round opened")
    (is (= tid (get-in (first @opened) [:input :threadId])))
    (let [turn (get-in (first @opened) [:input :append 0])]
      (is (= "user" (:role turn)) "a round opens with a REAL user message")
      (is (str/includes? (:content turn) "<goal"))
      (is (str/includes? (:content turn) "keep going"))
      (is (str/includes? (:content turn) "round 1/"))
      (is (= "r-run-1-goal-round-1" (:id turn)) "and it is named, so it cannot enter twice"))
    (testing "and the round is COUNTED IN THE RECORD before the run starts"
      (is (= 1 (:rounds (goal/goal-for tid))))
      (is (= 1 (:rounds (goal/goal-from-records tid))) "a restart folds it back")
      (is (= "active" (:phase (goal/goal-for tid)))))))

(deftest nothing-opens-when-the-goal-is-not-being-pushed
  (let [opened (atom [])
        open!  (opened-by opened)]
    (#'http/commands-request tid [(goal-command "create" :objective "gated")])
    (testing "paused -- a person stopped it"
      (let [g (goal/goal-for tid)]
        (#'http/commands-request tid [(goal-command "pause" :goal_id (:id g)
                                                   :revision (:revision g))])
        (#'http/drive-next-round! tid "r1" true open!)
        (is (empty? @opened))
        (is (= 0 (:rounds (goal/goal-for tid))))))
    (testing "active but DISARMED -- a rebuild, a restart, a fork"
      (let [g (goal/goal-for tid)]
        (#'http/commands-request tid [(goal-command "resume" :goal_id (:id g)
                                                   :revision (:revision g))]))
      (is (true? (goal/armed? tid)))
      (goal/disarm! tid)
      (#'http/drive-next-round! tid "r2" true open!)
      (is (empty? @opened) "nobody has said 'carry on' to this process"))
    (testing "completed -- it is over"
      (let [g (goal/goal-for tid)]
        (goal/complete! tid {:id (:id g) :revision (:revision g)}))
      (#'http/drive-next-round! tid "r3" true open!)
      (is (empty? @opened)))))

(deftest the-fuse-is-max-rounds-and-it-leaves-the-phase-alone
  (#'http/commands-request tid [(goal-command "create" :objective "short fuse"
                                             :max_goal_rounds 1)])
  (let [opened (atom [])]
    (#'http/drive-next-round! tid "r1" true (opened-by opened))
    (is (= 1 (count @opened)) "the first round is allowed")
    (#'http/drive-next-round! tid "r2" true (opened-by opened))
    (is (= 1 (count @opened)) "the second is not: rounds is at max-rounds")
    (is (= "active" (:phase (goal/goal-for tid)))
        "and the phase is untouched, so the strip can say it has run as many rounds as it may")
    (is (= 1 (:rounds (goal/goal-for tid))))))

(deftest zero-progress-blocks-the-goal-and-opens-nothing
  (#'http/commands-request tid [(goal-command "create" :objective "spinning")])
  (let [opened (atom [])]
    (#'http/drive-next-round! tid "r1" false (opened-by opened))
    (is (empty? @opened) "the driver does NOT open the next round")
    (let [g (goal/goal-for tid)]
      (is (= "blocked" (:phase g)))
      (is (= "no-progress" (get-in g [:blocked :code])))
      (is (false? (goal/armed? tid)) "and it stops pushing"))
    (testing "a person's resume clears it and the NEXT round may open"
      (let [g (goal/goal-for tid)]
        (#'http/commands-request tid [(goal-command "resume" :goal_id (:id g)
                                                   :revision (:revision g))]))
      (#'http/drive-next-round! tid "r2" true (opened-by opened))
      (is (= 1 (count @opened))))))
