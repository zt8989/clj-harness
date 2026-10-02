(ns harness.cap.goal-test
  "A session's goal: the record it is written in, the row that projects it, the fence
  every write carries, and the reminder the model reads.

  IT IS A RECORD FIRST (`.scratch/goal`), so the interesting assertions are the ones that
  compare the TWO readers: what the `goals` row says and what folding the conversation's
  `goal/change` rows says. A goal kept only in the row would pass every test in this file
  except `the-fold-is-the-row` and `a-row-that-went-missing-is-rebuilt-from-the-record` --
  which is exactly why both exist.

  THE RECORD IS WRITTEN THE WAY THE EDGE WRITES IT: the same file (the reserved workspace,
  through `harness.infra.home`'s own filename rule) and the same row envelope (`event` /
  `CUSTOM` / `goal/change`). A fixture that wrote its own shape would be testing a second
  convention rather than this one."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.goal :as goal]
            [harness.edge.http :as http]
            [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.test-support :as support]))

;; ------------------------------------------------------------------- the fixture

(def ^:private tid "goal-test")

(defn- log-file [thread-id]
  (home/log-file (io/file (home/projects-dir) http/unbound-workspace) thread-id))

(defn- install-writer!
  "The record door, as `harness.edge.http/start!` installs it: one `goal/change` fact row
  per change, in the conversation's own file under the reserved workspace."
  []
  (goal/set-record-writer!
   (fn [thread-id kind payload]
     (let [f (log-file thread-id)]
       (when-let [p (.getParentFile f)] (.mkdirs p))
       (spit f (str (json/write-str {:ts (System/currentTimeMillis) :runId nil :producer :record
                                     :type "event"
                                     :payload {:type "CUSTOM" :name kind :value payload}})
                    "\n")
             :append true :encoding "UTF-8")))))

(defn- rows [] (replay/read-records (log-file tid)))
(defn- rows-of [thread-id] (replay/read-records (log-file thread-id)))

(defn- wipe! []
  (when (.exists (home/db-file))
    (db/with-transaction
      (fn [c] (db/execute! c "DELETE FROM goals"))))
  (goal/reset-armed!)
  (goal/reset-repaired!)
  ;; AND THE RECORD GOES WITH THE ROW. A test that left its log behind would not only leak a
  ;; file: `goal-for` REBUILDS a missing row from the record, so the next test would find the
  ;; last one's goal standing (measured -- the first run of this file failed on exactly that).
  (let [f (log-file tid)] (when (.exists f) (.delete f))))

(defn- with-session!
  "Run F with config.edn's :session section replaced by M, and put the file back after --
  the sections are shared by the whole run (`harness.test-support/write-session!`), so a
  test that leaves its own behind changes every later test's home."
  [m f]
  (let [file   (home/config-file)
        before (when (.exists file) (slurp file :encoding "UTF-8"))]
    (support/write-session! m)
    (try (f)
         (finally
           (if before
             (spit file before :encoding "UTF-8")
             (.delete file))))))

(use-fixtures :once (fn [f] (let [td (sessions/install!)] (f) (td))))

(use-fixtures :each
  (fn [f]
    (wipe!)
    (install-writer!)
    (let [f' (io/file (log-file tid))]
      (when (.exists f') (.delete f')))
    (f)
    (wipe!)))

(defn- ref-of [g] (select-keys g [:id :revision]))

(defn- refusal
  "F's named refusal -- `{:reason .. :message ..}`, or nil when it did not refuse."
  [f]
  (try (f) nil (catch Exception e {:reason (:reason (ex-data e)) :message (ex-message e)})))

(defn- goal-now [] (goal/goal-for tid))

;; ---------------------------------------------------------- it is a record and a row

(deftest a-created-goal-is-one-fact-on-the-record-and-one-row
  (let [g (goal/create! tid "ship the goal feature")]
    (testing "the record carries ONE `goal/change` row, whose value is the whole snapshot"
      (let [rows (rows)]
        (is (= 1 (count rows)))
        (is (= "goal/change" (replay/kind (first rows))))
        (is (= (dissoc g :updated-at)
               (dissoc (replay/payload (first rows)) :updated-at)))))
    (testing "and the projection row says the same thing"
      (let [row (first (db/select "SELECT thread_id, goal FROM goals"))]
        (is (= tid (:thread-id row)))
        (is (= g (json/read-str (:goal row) :key-fn keyword)))))
    (testing "the shape of a fresh goal"
      (is (= "active" (:phase g)))
      (is (= 1 (:revision g)))
      (is (= 0 (:rounds g)))
      (is (= goal/default-max-rounds (:max-rounds g)))
      (is (re-matches #"g-.*" (:id g)))
      (is (nil? (:blocked g)))
      (is (nil? (:pending-block g)))
      (is (= [:id :revision :objective :phase :rounds :max-rounds :updated-at]
             (vec (keys g)))
          "every key it wears is one of `snapshot-keys`, and none of the optional two"))))

(deftest a-session-that-never-ran-can-still-have-a-goal
  (testing "no session row, no log -- the goal is written anyway"
    (is (false? (.exists (log-file tid))))
    (goal/create! tid "write the thing")
    (is (.exists (log-file tid)) "the record writer made the file it needed")
    (is (some? (goal-now)))))

(deftest the-fold-is-the-row
  (let [tid2 "goal-fold"]
    (goal/create! tid2 "one objective")
    (let [g1 (goal/goal-for tid2)]
      (goal/edit! tid2 (ref-of g1) "one objective, reworded")
      (let [g2 (goal/goal-for tid2)]
        (goal/note-round! tid2 (ref-of g2))
        (let [g3 (goal/goal-for tid2)]
          (is (= g3 (goal/goal-from-records tid2))
              "what the record folds to and what the row says are one value")
          (is (= g3 (goal/goal-of-records (rows-of tid2)))
              "and the in-memory fold of those rows agrees with the session's own reader")
          (is (= 3 (count (rows-of tid2))) "three changes, three rows")
          (is (= 3 (:revision g3)) "and one revision per change"))))))

(deftest a-row-that-went-missing-is-rebuilt-from-the-record
  (goal/create! tid "repaired from the record")
  (let [g (goal-now)]
    (db/with-transaction (fn [c] (db/execute! c "DELETE FROM goals")))
    (is (nil? (first (db/select "SELECT goal FROM goals"))) "the row really is gone")
    (is (= g (goal/goal-for tid)) "the fold answers, not nil")
    (is (= g (goal-now)) "and the row is back, so the next read is a row again")))

(deftest cleared-and-never-had-one-answer-the-same
  (is (nil? (goal/goal-for "goal-never")))
  (is (nil? (goal/goal-from-records "goal-never")))
  (goal/create! tid "cleared soon")
  (goal/clear! tid (ref-of (goal-now)))
  (testing "a tombstone is a row, not a deletion"
    (let [rows (rows)]
      (is (= 2 (count rows)))
      (is (= "cleared" (:phase (replay/payload (last rows)))))))
  (testing "and every reader answers nil"
    (is (nil? (goal-now)))
    (is (nil? (goal/goal-from-records tid)))
    (is (nil? (:goal (first (db/select "SELECT goal FROM goals")))))))

(deftest clearing-what-is-not-there-is-not-an-error
  (is (nil? (goal/clear! tid nil)))
  (is (nil? (goal/clear! tid {:id "g-nope" :revision 9})))
  (is (nil? (first (db/select "SELECT goal FROM goals")))
      "nothing is written for a goal that was never there"))

;; ------------------------------------------------------------------------- the fence

(deftest a-stale-revision-is-refused-by-name-and-writes-nothing
  (goal/create! tid "fenced")
  (let [g1 (goal-now)]
    (goal/edit! tid (ref-of g1) "second wording")
    (let [before (rows)
          g2     (goal-now)
          moved  (refusal #(goal/edit! tid (ref-of g1) "from an old snapshot"))]
      (is (= :goal-moved (:reason moved)))
      (is (str/includes? (:message moved) "has moved"))
      (is (= before (rows)) "no row was appended")
      (is (= g2 (goal-now)) "and the goal did not move")
      (testing "a write naming somebody else's goal id is the same refusal"
        (is (= :goal-moved (:reason (refusal #(goal/pause! tid {:id "g-other" :revision (:revision g2)}))))))
      (testing "and the write with the CURRENT ref goes through"
        (is (= "paused" (:phase (goal/pause! tid (ref-of g2)))))))))

(deftest every-write-verb-needs-the-fence
  (goal/create! tid "all five")
  (let [g     (goal-now)
        stale {:id (:id g) :revision (dec (:revision g))}]
    (doseq [[name f] {"edit"     #(goal/edit! tid stale "x")
                      "pause"    #(goal/pause! tid stale)
                      "resume"   #(goal/resume! tid stale {:by :human})
                      "complete" #(goal/complete! tid stale)
                      "block"    #(goal/block! tid stale {:code "x" :reason "y"})}]
      (is (= :goal-moved (:reason (refusal f))) (str name " was fenced")))
    (is (= g (goal-now)) "not one of them wrote")))

;; ---------------------------------------------------------------- creating a goal

(deftest a-second-unfinished-goal-is-refused
  (goal/create! tid "the first one")
  (let [g1     (goal-now)
        exists (refusal #(goal/create! tid "the second one"))]
    (is (= :goal-exists (:reason exists)))
    (is (str/includes? (:message exists) "unfinished goal"))
    (testing "a paused goal is unfinished too"
      (goal/pause! tid (ref-of g1))
      (is (= :goal-exists (:reason (refusal #(goal/create! tid "still refused"))))))
    (testing "and a finished one is not: the next goal is a NEW goal"
      (goal/complete! tid (ref-of (goal-now)))
      (let [g2 (goal/create! tid "the second one, after all")]
        (is (not= (:id g1) (:id g2)) "a new goal is a NEW id")
        (is (= 1 (:revision g2)) "and its own revision, from one")))))

(deftest an-empty-objective-is-refused
  (is (= :no-objective (:reason (refusal #(goal/create! tid "   ")))))
  (is (= :no-objective (:reason (refusal #(goal/create! tid nil)))))
  (is (nil? (goal-now))))

;; ------------------------------------------------------------------ the phases

(deftest pause-takes-the-permission-away-and-only-a-person-gives-it-back
  (goal/create! tid "a goal that pauses")
  (is (true? (goal/armed? tid)))
  (goal/pause! tid (ref-of (goal-now)))
  (is (= "paused" (:phase (goal-now))))
  (is (false? (goal/armed? tid)) "pausing stops the round driver")
  (testing "the model may not lift a person's pause"
    (is (= :paused-by-human (:reason (refusal #(goal/resume! tid (ref-of (goal-now)) {:by :model}))))))
  (testing "a person may"
    (is (= "active" (:phase (goal/resume! tid (ref-of (goal-now)) {:by :human}))))
    (is (true? (goal/armed? tid)))))

(deftest an-active-but-disarmed-goal-is-the-one-thing-the-model-may-rearm
  (goal/create! tid "rearmed by the model")
  (let [g      (goal-now)
        before (rows)]
    (goal/disarm! tid)
    (is (false? (goal/armed? tid)))
    (is (= g (goal/resume! tid (ref-of g) {:by :model})) "the snapshot did not move")
    (is (true? (goal/armed? tid)) "but the permission is back")
    (is (= before (rows)) "and nothing was written: the record says the same thing"))
  (testing "a completed goal is not resumed by anybody"
    (goal/complete! tid (ref-of (goal-now)))
    (is (= :goal-completed (:reason (refusal #(goal/resume! tid (ref-of (goal-now)) {:by :human})))))))

(deftest edit-changes-the-words-and-nothing-else
  (goal/create! tid "before")
  (goal/pause! tid (ref-of (goal-now)))
  (let [g (goal/edit! tid (ref-of (goal-now)) "after")]
    (is (= "after" (:objective g)))
    (is (= "paused" (:phase g)) "editing does not lift a pause")
    (is (false? (goal/armed? tid)) "and does not arm it either")
    (is (= 3 (:revision g)))))

(deftest complete-is-an-ending
  (goal/create! tid "finish me")
  (let [g (goal/complete! tid (ref-of (goal-now)))]
    (is (= "completed" (:phase g)))
    (is (false? (goal/armed? tid)))
    (is (= :goal-completed (:reason (refusal #(goal/complete! tid (ref-of g))))))))

(deftest a-blocker-has-to-be-reported-three-rounds-in-a-row
  (goal/create! tid "blocked eventually")
  (testing "the first report does not move the phase"
    (let [g (goal/block! tid (ref-of (goal-now)) {:code "no-credentials" :reason "the key is gone"})]
      (is (= "active" (:phase g)))
      (is (= {:code "no-credentials" :reason "the key is gone" :reports 1} (:pending-block g)))
      (is (nil? (:blocked g)))))
  (testing "a DIFFERENT code starts the count over"
    (let [g (goal/block! tid (ref-of (goal-now)) {:code "flaky-tests" :reason "a test times out"})]
      (is (= 1 (get-in g [:pending-block :reports])))))
  (testing "and the third consecutive report of one code takes"
    (goal/block! tid (ref-of (goal-now)) {:code "flaky-tests" :reason "a test times out"})
    (is (= 2 (get-in (goal-now) [:pending-block :reports])))
    (let [g (goal/block! tid (ref-of (goal-now)) {:code "flaky-tests" :reason "a test times out"})]
      (is (= "blocked" (:phase g)))
      (is (= {:code "flaky-tests" :reason "a test times out"} (:blocked g)))
      (is (nil? (:pending-block g)) "the pending report became the blocked one")
      (is (false? (goal/armed? tid)) "a blocked goal stops the round driver"))))

(deftest the-drivers-zero-progress-brake-blocks-at-once
  (goal/create! tid "stuck")
  (let [g (goal/block! tid (ref-of (goal-now))
                       {:code "no-progress" :reason "the last round changed no file"
                        :immediate? true})]
    (is (= "blocked" (:phase g)))
    (is (= "no-progress" (get-in g [:blocked :code])))))

(deftest a-blocked-goal-is-a-persons-to-lift
  (goal/create! tid "blocked and resumed")
  (goal/block! tid (ref-of (goal-now)) {:code "no-progress" :reason "nothing moved" :immediate? true})
  (is (= :blocked-needs-a-person
         (:reason (refusal #(goal/resume! tid (ref-of (goal-now)) {:by :model})))))
  (let [g (goal/resume! tid (ref-of (goal-now)) {:by :human})]
    (is (= "active" (:phase g)))
    (is (nil? (:blocked g)) "the person's resume clears the blocker")
    (is (true? (goal/armed? tid)))))

(deftest noting-a-round-counts-in-the-record
  (goal/create! tid "rounds")
  (let [g (goal/note-round! tid (ref-of (goal-now)))]
    (is (= 1 (:rounds g)))
    (is (= 1 (:rounds (goal/goal-from-records tid))) "a restart folds the count back"))
  (testing "no round opens on a goal nobody is pushing"
    (goal/pause! tid (ref-of (goal-now)))
    (is (= :not-active (:reason (refusal #(goal/note-round! tid (ref-of (goal-now)))))))))

(deftest the-permission-dies-with-the-session-and-comes-back-when-a-person-speaks
  (goal/create! tid "armed")
  (is (true? (goal/armed? tid)))
  (testing "a rebuild has nobody's permission to carry on -- both session seams disarm"
    (goal/disarm! tid)
    (is (false? (goal/armed? tid)))
    (is (= "active" (:phase (goal-now))) "and the goal is untouched: it lives in the record"))
  (testing "a person speaking to the session gives the permission back"
    (is (true? (goal/arm-if-active! tid)))
    (is (true? (goal/armed? tid))))
  (testing "and a message does not re-arm a paused or finished goal"
    (goal/pause! tid (ref-of (goal-now)))
    (is (false? (goal/arm-if-active! tid)))
    (is (false? (goal/armed? tid)))))

(deftest a-call-with-no-session-is-refused-by-name
  (doseq [[name f] {"create"   #(goal/create! nil "x")
                    "edit"     #(goal/edit! nil {:id "g" :revision 1} "x")
                    "pause"    #(goal/pause! nil {:id "g" :revision 1})
                    "resume"   #(goal/resume! nil {:id "g" :revision 1} {:by :human})
                    "complete" #(goal/complete! nil {:id "g" :revision 1})
                    "block"    #(goal/block! nil {:id "g" :revision 1} {:code "x" :reason "y"})
                    "clear"    #(goal/clear! nil nil)}]
    (is (= :no-session (:reason (refusal f))) (str name " refused without a session")))
  (is (nil? (goal/goal-for nil))))

;; ------------------------------------------------------------------- the reminder

(deftest the-reminder-is-one-block-appended-and-never-repeated
  (goal/create! tid "把登录模块重构完，补齐测试和迁移说明")
  (let [g       (goal-now)
        history [{:role "user" :content "hello"}]
        once    (goal/before-llm history tid)]
    (is (= 2 (count once)) "one message, appended at the END")
    (is (= (goal/reminder-text g) (:content (last once))))
    (is (str/starts-with? (:content (last once)) (str "<goal revision=\"" (:revision g) "\">")))
    (is (str/includes? (:content (last once)) (:objective g)))
    (is (str/includes? (:content (last once)) (str "round 0/" (:max-rounds g))))
    (testing "idempotent by content"
      (is (= once (goal/before-llm once tid)))
      (is (= once (goal/before-llm (goal/before-llm once tid) tid))))
    (testing "a change puts a NEW block at the end and leaves the old one"
      (goal/note-round! tid (ref-of (goal-now)))
      (let [twice (goal/before-llm once tid)]
        (is (= 3 (count twice)))
        (is (str/includes? (:content (last twice)) "round 1/"))
        (is (= (:content (second once)) (:content (second twice)))
            "the block it already read is still there")))))

(deftest only-an-active-goal-is-injected
  (let [history [{:role "user" :content "hello"}]]
    (is (= history (goal/before-llm history tid)) "no goal at all")
    (goal/create! tid "pausable")
    (is (= 2 (count (goal/before-llm history tid))))
    (goal/pause! tid (ref-of (goal-now)))
    (is (= history (goal/before-llm history tid)) "a paused goal stops pushing")
    (goal/resume! tid (ref-of (goal-now)) {:by :human})
    (goal/complete! tid (ref-of (goal-now)))
    (is (= history (goal/before-llm history tid)) "a completed goal is over")
    (goal/clear! tid (ref-of (goal-now)))
    (is (= history (goal/before-llm history tid)) "and a cleared goal is no goal")))

(deftest the-round-opening-and-the-reminder-say-the-same-thing
  (goal/create! tid "one objective")
  (let [g    (goal-now)
        turn (goal/round-turn g)]
    (is (= "user" (:role turn)))
    (is (str/starts-with? (:content turn) (goal/reminder-text g))
        "both come from the same function rather than two hand-written texts")
    (is (str/includes? (:content turn) (:objective g)))
    (is (str/includes? (:content turn) (str "round " (:rounds g) "/" (:max-rounds g))))
    (testing "and a round opening counts as having said it"
      (is (= [{:role "user" :content "hello"} turn]
             (goal/before-llm [{:role "user" :content "hello"} turn] tid))
          "the reminder block is already in the history, so nothing is repeated"))))

;; ------------------------------------------------------------------- the knobs

(deftest config-edn-bounds-what-this-namespace-defaults
  (with-session! {:goal {:max-rounds 5 :block-rounds 1}}
    (fn [] (is (= 5 (:max-rounds (goal/create! tid "bounded by config"))))))
  (goal/clear! tid nil)   ;; one goal at a time: the case above left one behind
  (testing "a create! argument wins over the file"
    (with-session! {:goal {:max-rounds 5}}
      (fn [] (is (= 9 (:max-rounds (goal/create! tid "asking for more" {:max-rounds 9})))))))
  (testing "a knob nothing reads is refused by name"
    (with-session! {:goal {:bogus 1}}
      (fn [] (is (= :unknown-goal-key (:reason (refusal #(goal/config tid))))))))
  (testing "and a knob that is not a whole positive number is refused by name"
    (with-session! {:goal {:max-rounds 0}}
      (fn [] (is (= :bad-goal-knob (:reason (refusal #(goal/config tid)))))))))

(deftest block-rounds-is-a-knob-and-one-report-can-be-enough
  (with-session! {:goal {:block-rounds 1}}
    (fn []
      (goal/create! tid "blocked at once")
      (let [g (goal/block! tid (ref-of (goal-now)) {:code "no-progress" :reason "nothing moved"})]
        (is (= "blocked" (:phase g)))))))

(deftest the-goal-knob-is-a-session-key-config-edn-may-carry
  (testing "a home that writes :session :goal is not refused as an unknown key"
    (with-session! {:goal {:max-rounds 7}}
      (fn [] (is (= 7 (:max-rounds (goal/config tid))))))))
