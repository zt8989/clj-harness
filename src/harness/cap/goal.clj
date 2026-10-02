(ns harness.cap.goal
  "A SESSION'S GOAL: one objective a conversation is being pushed towards, across
  many turns and many rounds -- where it stands, how far it has got, and whether
  this process may open the next round by itself.

  WHY IT IS NOT A TASK LIST. `harness.cap.todos` is the model's plan for the step in
  front of it, replaced whole on every call and worth nothing once the step is over.
  A goal is where the SESSION is going: it outlives the turn that set it, a PERSON
  may set or stop it from outside any run, and the model reports on it from inside
  one. The two do not overwrite each other (`.scratch/goal/spec.md`).

  IT IS A RECORD FIRST (spec decision 1). Every change appends a `goal/change` fact
  row -- THE FULL AFTER-STATE SNAPSHOT -- to the conversation's jsonl, and `clear` is
  a TOMBSTONE rather than a deletion, so 'never had one' and 'cleared it' answer the
  same `nil` to every reader. The `goals` row (one per session, one JSON column) is
  the PROJECTION of those rows: the first read -- the strip, the route, the reminder --
  is ONE SELECT instead of a walk of the whole record, and the FOLD
  (`goal-from-records`) is the repair path. That is the shape `sessions.numbers`
  already has (`.scratch/session-numbers-in-the-store`).

  TWO HANDS WRITE IT -- a person from outside any run and the model from inside one --
  so every write carries a `{id, revision}` FENCE: a write built on a snapshot that has
  moved is refused BY NAME (`:goal-moved`) instead of landing on top of somebody else's
  change. `id` is minted once at create (a goal created after a clear is a NEW goal with
  a new id) and `revision` is monotone, so the fold rebuilds both.

  ARMED IS NOT IN THE RECORD. 'May this process open the next round by itself' is a fact
  about THIS PROCESS -- the same family as a pending approval or the job registry -- so it
  lives in an atom here, dies with the process, and is dropped when a session is rebuilt.
  That is what makes 'open the page' different from 'keep burning money' (spec decision 3).

  THE RECORD IS WRITTEN THROUGH A DOOR THE EDGE INSTALLS (`set-record-writer!`), because
  WHERE a conversation's line goes is the edge's business (`harness.edge.http` resolves
  the file, writes the file's header, and stamps the row's envelope). What this namespace
  owns is WHAT the line says."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness.cap.providers :as providers]
            [harness.infra.db :as db]
            [harness.kernel.session :as session]))

;; ------------------------------------------------------------------- the vocabulary

(def phases
  "The four states a goal may be in, and the WHOLE vocabulary -- data rather than a cond,
  for the reason `harness.cap.todos/statuses` is one: every refusal below has to state the
  legal set, and a hand-written sentence per failure is how the two drift apart.

  `clear` is deliberately NOT a fifth phase: it is a tombstone on the record, and a session
  that was cleared answers exactly what one that never had a goal answers (`nil`)."
  ["active" "paused" "blocked" "completed"])

(def ^:private cleared-phase "cleared")

(def snapshot-keys
  "THE NAMES ONE GOAL SNAPSHOT WEARS, in the order a reader should meet them. One list,
  because three consumers have to agree on it: the record's `goal/change` rows, the
  projection's column, and the wire (`GET .../goal`, the `goal` frame, `get_goal`).
  `harness.cap.goal-test` pins the server's half of that agreement and
  `ui/test/suites/goal.ts` the client's, so a field added in one place only fails.

  `:blocked` is present only while the goal IS blocked, and `:pending-block` only while a
  blocker has been reported but has not stood for `block-rounds` rounds yet -- absence is
  the fact, for both (`snapshot` below)."
  [:id :revision :objective :phase :rounds :max-rounds :blocked :pending-block :updated-at])

(def default-max-rounds
  "How many goal rounds one goal may open before it stops, when config.edn says nothing.

  NOT the reference's 256. A round is a whole run and the money is per token: the cap is a
  FUSE rather than a target, and 256 rounds behind one bad compaction is a session's budget
  gone (`dsh` issue #7894). A goal that needs more says so by being created again."
  25)

(def default-block-rounds
  "How many CONSECUTIVE reports of the SAME blocker it takes before a goal counts as blocked.

  ONE REPORT IS NOT ENOUGH, deliberately: a model that hits one error and declares the
  objective impossible has turned 'this round went badly' into 'this cannot be done'."
  3)

(def ^:private known-config-keys #{:max-rounds :block-rounds})

;; ------------------------------------------------------------------- the knobs

(defn- goal-block
  "config.edn's `:session :goal` block for THREAD-ID, as written: nothing merged with the
  defaults and every key checked by name, so a typo fails here rather than leaving the goal
  bounded by whatever the default happened to be."
  [thread-id]
  (let [b (:goal (providers/session-config thread-id))]
    (when-not (or (nil? b) (map? b))
      (throw (ex-info (str "config.edn's :session :goal must be a map of knobs"
                           " (:max-rounds, :block-rounds), but it is " (pr-str b))
                      {:reason :bad-goal-config :value b})))
    (let [unknown (remove known-config-keys (keys b))]
      (when (seq unknown)
        (throw (ex-info (str "config.edn's :session :goal carries " (count unknown)
                             " key(s) nothing reads: " (str/join ", " (sort (map name unknown)))
                             " -- known: " (str/join ", " (sort (map name known-config-keys))))
                        {:reason :unknown-goal-key :keys (vec unknown)}))))
    (or b {})))

(defn- knob
  "KEY's value in BLOCK, or FALLBACK -- refusing anything that is not a positive whole number,
  by name. A fraction or a zero is not a bound, and rounding one would hide a typo in the
  number that decides how much this session may spend."
  [block key fallback]
  (let [n (get block key fallback)]
    (when-not (and (integer? n) (pos? n))
      (throw (ex-info (str "config.edn's :session :goal " (name key) " must be a positive whole"
                           " number, but it is " (pr-str n))
                      {:reason :bad-goal-knob :key key :value n})))
    (long n)))

(defn config
  "The goal knobs THIS SESSION runs under: config.edn's `:session :goal`, over the defaults
  written here. Read fresh on every call (the config.edn discipline), so editing the file
  moves the answer with no restart."
  [thread-id]
  (let [b (goal-block thread-id)]
    {:max-rounds   (knob b :max-rounds default-max-rounds)
     :block-rounds (knob b :block-rounds default-block-rounds)}))

;; ------------------------------------------------------------------- the snapshot

(defn- snapshot
  "The value that goes ON THE RECORD and IN THE ROW for one state of a goal: the whole
  after-state, never a delta (spec decision 1) -- a reader that meets one `goal/change` row
  and nothing else still knows where the goal stands."
  [{:keys [id revision objective phase rounds max-rounds blocked pending-block updated-at]}]
  (cond-> (array-map :id id :revision revision :objective objective :phase phase
                     :rounds rounds :max-rounds max-rounds :updated-at updated-at)
    (some? blocked)       (assoc :blocked blocked)
    (some? pending-block) (assoc :pending-block pending-block)))

(defn- as-snapshot
  "A stored value -> the snapshot a reader gets, or nil. A tombstone is nil: THERE IS NO GOAL.
  Anything this version does not know is dropped by `snapshot`, so a row written by a later
  version reads as the fields this one understands rather than as a map with surprises in it."
  [v]
  (when (and (map? v) (not= cleared-phase (:phase v)))
    (snapshot v)))

;; ------------------------------------------------------------------- the record's writer

(defonce ^:private record-writer
  ;; (fn [thread-id kind value] -> nil), installed by the composition root. NIL IS A
  ;; MIS-WIRED PROCESS, not 'no goal today': the record is where a goal lives, so a change
  ;; that could not be written down must fail loudly rather than leave the projection row
  ;; as the only copy of it.
  (atom nil))

(defn set-record-writer!
  "Install the door a goal change is written down through. The composition root passes
  `harness.edge.http`'s own record writer here -- resolving WHERE a conversation's line goes
  is the edge's business (its project binding, its file header, its carry-back), and this
  capability is the one that knows what the line says."
  [f]
  (reset! record-writer f))

(defn- write-record! [thread-id change]
  (if-some [w @record-writer]
    (w (str thread-id) "goal/change" change)
    (throw (ex-info (str "a goal change was ready to be written for " (pr-str thread-id)
                         " and this process has no record writer installed -- the goal would"
                         " live in the projection row alone, which is not where a goal lives")
                    {:reason :no-goal-record-writer :thread-id thread-id}))))

;; ------------------------------------------------------------------- the projection

(defonce ^:private repaired
  ;; thread-id -> the sessions THIS PROCESS has already folded and found no goal in. It
  ;; exists so the repair path below is not a walk of the whole record on EVERY read of a
  ;; session that has never had a goal -- the reminder asks once per model call. Any write
  ;; clears the entry (`write-row!`), and a goal that appears in the record without a row
  ;; still repairs, because that is exactly the case this cache is not consulted for.
  (atom #{}))

(defn- write-row!
  "THREAD-ID's projection row -> SNAPSHOT (nil writes a NULL goal: there is none).
  Written WHOLE and IN PLACE, which is what makes this materialized state rather than a
  second record (`harness.infra.db/goals-table` has the argument)."
  [thread-id snapshot]
  (let [tid (str thread-id)]
    (db/with-transaction
      (fn [c]
        (db/execute! c "INSERT INTO goals (thread_id, goal, updated_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT(thread_id) DO UPDATE SET
                          goal = excluded.goal,
                          updated_at = excluded.updated_at"
                    tid (when snapshot (json/write-str snapshot)) (System/currentTimeMillis))))
    ;; THE ROW IS THERE AGAIN, so the 'nothing to repair here' mark this process may be
    ;; holding is no longer about anything: see `goal-for`.
    (swap! repaired disj tid)
    snapshot))

(defn reset-repaired! []
  (reset! repaired #{}))

(defn- change-value
  "ROW as the record spells a `goal/change` fact -> the value it carries, or nil for a row
  about anything else. The envelope is the record's own (`harness.edge.http/row-of`): an
  `event` row whose payload is a CUSTOM frame named `goal/change`."
  [row]
  (let [p (:payload row)]
    (when (and (= "event" (:type row))
               (map? p)
               (= "CUSTOM" (:type p))
               (= "goal/change" (:name p)))
      (:value p))))

(defn- goal-step
  "One row -> the goal the rows so far fold to. A tombstone folds to nil, which IS the
  answer rather than a missing step: a cleared goal is a session with no goal."
  [acc [_index row]]
  (if-some [v (change-value row)]
    (as-snapshot v)
    acc))

(defn goal-of-records
  "RECORDS -> the goal they fold to, or nil. THE FOLD ITSELF, in one place, so the session's
  own reader (`goal-from-records`) and a caller holding rows already (a test, a tool) cannot
  disagree about what a record says."
  [records]
  (reduce goal-step nil (map-indexed vector (or records []))))

(defn goal-from-records
  "THREAD-ID's goal folded from its RECORD -- the repair path, and the answer a session with
  no projection row is rebuilt from. Nil for a session with no goal, for one whose log is not
  under this home, and for a record that cannot be read: a reader that must not throw is
  asked by the strip, and `nil` is what 'no goal' already means."
  [thread-id]
  (when thread-id
    (let [{:keys [ok]} (session/fold-record thread-id nil goal-step)]
      (as-snapshot ok))))

(defn goal-for
  "THREAD-ID's goal as it stands, or nil. The projection row is the first read; a session
  with NO ROW is folded from its record once per process (`repaired`), and a fold that finds
  one writes the row back -- the repair path, exactly as `sessions.numbers` has it.

  A ROW THAT SAYS NULL IS AN ANSWER, not a missing row: it is what a clear writes, and
  folding the record to 'prove' it would make every cleared session pay for a walk."
  [thread-id]
  (when thread-id
    (let [tid  (str thread-id)
          rows (db/select "SELECT goal FROM goals WHERE thread_id = ?" tid)]
      (if (seq rows)
        (when-some [stored (:goal (first rows))]
          (as-snapshot (json/read-str stored :key-fn keyword)))
        (when-not (contains? @repaired tid)
          (let [folded (goal-from-records tid)]
            (if folded
              (write-row! tid folded)
              (do (swap! repaired conj tid) nil))))))))

;; ------------------------------------------------------------------- armed

(defonce ^:private armed
  ;; thread-id -> true, for the sessions THIS PROCESS may open the next round of. Process
  ;; memory on purpose: a restart, a rebuild, and a fork all empty it, and a person speaking
  ;; to the session fills it back in. See the namespace docstring.
  (atom #{}))

(defn armed?
  "'May this process open the next goal round of THREAD-ID by itself?' False for a session
  nobody armed, and for every id this process has never heard of."
  [thread-id]
  (contains? @armed (str thread-id)))

(defn- arm! [thread-id] (swap! armed conj (str thread-id)) true)

(defn disarm!
  "Stop THREAD-ID's goal from opening rounds by itself. Called when the goal pauses, blocks,
  completes or is cleared, and when the session is REBUILT -- a conversation this process had
  to fold back into memory is one nobody has said 'carry on' to yet."
  [thread-id]
  (swap! armed disj (str thread-id))
  nil)

(defn reset-armed! [] (reset! armed #{}))

(defn arm-if-active!
  "A PERSON SPOKE TO THREAD-ID: if it has an active goal, this process may carry on again.

  This is what makes a message the other door back from `disarmed` (the first is a person's
  `resume`): coming back to a conversation and saying something is asking for the work to
  continue, which is not the same as opening the page. Answers whether the goal is active."
  [thread-id]
  (let [g (goal-for thread-id)
        active? (and (some? g) (= "active" (:phase g)))]
    (when active? (arm! thread-id))
    (boolean active?)))

;; ------------------------------------------------------------------- refusals

(defn- refuse! [reason sentence data]
  (throw (ex-info sentence (assoc data :reason reason))))

(defn- no-session! []
  (refuse! :no-session
           (str "there is no session in scope, and a goal belongs to one -- its rows are keyed"
                " by the session's id. Call this from a run, or name a thread.")
           {}))

(defn- objective!
  "OBJECTIVE as stored, or a refusal naming what is wrong. Trimmed rather than rejected for
  its whitespace: a goal is one sentence a person or a model wrote, and the edges of it are
  not the point."
  [objective]
  (when-not (and (string? objective) (not (str/blank? objective)))
    (refuse! :no-objective
             (str "a goal needs a non-empty objective, and this one is " (pr-str objective)
                  " -- one sentence saying where this session is going.")
             {:objective objective}))
  (str/trim objective))

(defn- moved!
  "The one refusal the fence owes: the write named a snapshot that has moved, so it is
  answered with WHERE the goal is now and told to go and read it."
  [current ref]
  (refuse! :goal-moved
           (str "this goal has moved since the snapshot you are holding: it is now "
                (pr-str (:id current)) " at revision " (:revision current)
                ", and the write named " (pr-str (:id ref)) " at revision " (pr-str (:revision ref))
                ". Read it again (`get_goal`, or GET .../goal) and copy the id and revision"
                " from that answer.")
           {:current {:id (:id current) :revision (:revision current)} :ref ref}))

(defn- fenced
  "REF checked against the goal THREAD-ID has right now -> that goal. Every write verb comes
  through here, so the fence is one rule rather than a check per verb."
  [thread-id ref]
  (let [c (goal-for thread-id)]
    (when-not c
      (refuse! :no-goal
               (str "there is no goal on this session to change -- none was created, or it was"
                    " cleared. Create one first.")
               {}))
    (when-not (and (map? ref)
                   (= (str (:id ref)) (str (:id c)))
                   (= (str (:revision ref)) (str (:revision c))))
      (moved! c ref))
    c))

(defn- new-id [] (str "g-" (subs (str (java.util.UUID/randomUUID)) 0 8)))

(defn- next-revision [c] (inc (long (:revision c))))

;; ------------------------------------------------------------------- the verbs

(defn append-change!
  "Write CHANGE -- a full snapshot, or a tombstone -- onto THREAD-ID's RECORD and its
  PROJECTION row, and answer CHANGE. The record first: it is the truth, and the row is the
  read of it.

  IT DOES NOT REQUIRE THAT THE SESSION EVER RAN (ticket 01): the row is keyed by the thread
  id and the record writer makes the file it needs, which is what lets `/goal ...` on a
  conversation nobody has spoken to yet create a goal."
  [thread-id change]
  (write-record! thread-id change)
  (write-row! thread-id (as-snapshot change))
  change)

(defn create!
  "Create THREAD-ID's goal: OBJECTIVE, active, armed, no rounds run yet.

  ANSWERS THE NEW SNAPSHOT. Refuses when the session already has an UNFINISHED goal
  (`:goal-exists`) -- 'unfinished' being anything but completed, so a paused or blocked goal
  is not quietly replaced either; a completed goal or a cleared one may be replaced, and the
  new goal gets a NEW id. OPTS may carry `:max-rounds`."
  ([thread-id objective] (create! thread-id objective {}))
  ([thread-id objective {:keys [max-rounds]}]
   (when (nil? thread-id) (no-session!))
   (let [objective (objective! objective)
         current   (goal-for thread-id)
         knobs     (config thread-id)]
     (when (and current (not= "completed" (:phase current)))
       (refuse! :goal-exists
                (str "this session already has an unfinished goal (" (pr-str (:id current)) ", "
                     (:phase current) ", revision " (:revision current) "): " (pr-str (:objective current))
                     ". There is one goal at a time -- finish it (`update_goal` with action"
                     " \"complete\"), or ask the person to clear it first.")
                {:current (select-keys current [:id :phase :revision :objective])}))
     (let [snap (snapshot {:id         (new-id)
                           :revision   1
                           :objective  objective
                           :phase      "active"
                           :rounds     0
                           :max-rounds (if (nil? max-rounds)
                                         (:max-rounds knobs)
                                         (knob {:max-rounds max-rounds} :max-rounds (:max-rounds knobs)))
                           :updated-at (System/currentTimeMillis)})]
       (arm! thread-id)
       (append-change! thread-id snap)))))

(defn edit!
  "Change a goal's WORDS and nothing else: not its phase, and not whether it is armed (the
  reference's rule, and the reason `/goal edit ...` cannot accidentally restart a paused
  goal). OPTS may carry `:max-rounds` -- which is still neither the phase nor the permission,
  and is the one other knob a goal carries. Answers the new snapshot."
  ([thread-id ref objective] (edit! thread-id ref objective {}))
  ([thread-id ref objective {:keys [max-rounds]}]
   (when (nil? thread-id) (no-session!))
   (let [objective (objective! objective)
         c         (fenced thread-id ref)]
     (append-change! thread-id (snapshot (cond-> (assoc c
                                                        :objective  objective
                                                        :revision   (next-revision c)
                                                        :updated-at (System/currentTimeMillis))
                                                (some? max-rounds)
                                                (assoc :max-rounds (knob {:max-rounds max-rounds}
                                                                          :max-rounds (:max-rounds c)))))))))

(defn pause!
  "Active -> paused, and the process stops being allowed to open rounds. Answers the new
  snapshot; refuses anything that is not active by name."
  [thread-id ref]
  (when (nil? thread-id) (no-session!))
  (let [c (fenced thread-id ref)]
    (when-not (= "active" (:phase c))
      (refuse! :not-active
               (str "this goal is " (:phase c) ", so there is nothing to pause -- only an active"
                    " goal can be paused.")
               {:phase (:phase c)}))
    (disarm! thread-id)
    (append-change! thread-id (snapshot (assoc c
                                               :phase      "paused"
                                               :revision   (next-revision c)
                                               :updated-at (System/currentTimeMillis))))))

(defn resume!
  "Bring a goal back to active, as BY (`:human` or `:model`) -- the one verb whose answer
  depends on WHO asked:

    paused    only a PERSON may resume it (`:paused-by-human`), because a person's pause is a
              person's to lift and the model was never asked to stop;
    blocked   only a PERSON may resume it (`:blocked-needs-a-person`), and their resume drops
              the blocker and clears it from the record;
    active    the model MAY resume it, and that is the only place it may -- 'active but
              disarmed' is this process having forgotten its permission (a rebuild, a fork),
              not a decision anybody took, so re-arming it changes no one's mind;
    completed refused: a finished goal is started again, not resumed."
  [thread-id ref {:keys [by]}]
  (when (nil? thread-id) (no-session!))
  (let [c (fenced thread-id ref)]
    (case (:phase c)
      "completed"
      (refuse! :goal-completed
               (str "this goal is completed, and a completed goal is not resumed -- create a new"
                    " one if the session is going somewhere else.")
               {})

      "blocked"
      (if (= :human by)
        (do (arm! thread-id)
            (append-change! thread-id (snapshot (-> c
                                                    (assoc :phase "active")
                                                    (dissoc :blocked)
                                                    (assoc :revision   (next-revision c)
                                                           :updated-at (System/currentTimeMillis))))))
        (refuse! :blocked-needs-a-person
                 (str "this goal is blocked (" (pr-str (get-in c [:blocked :code]))
                      "), and only a person resumes it -- the judgment that this cannot go on is"
                      " theirs to overrule. Say what is in the way and stop.")
                 {:blocked (:blocked c)}))

      "paused"
      (if (= :human by)
        (do (arm! thread-id)
            (append-change! thread-id (snapshot (assoc c
                                                       :phase      "active"
                                                       :revision   (next-revision c)
                                                       :updated-at (System/currentTimeMillis)))))
        (refuse! :paused-by-human
                 (str "this goal is paused, and a person's pause is a person's to lift -- wait for"
                      " them (the goal strip has the button, and /goal resume is the command).")
                 {}))

      "active"
      (if (armed? thread-id)
        c
        (do (arm! thread-id) c)))))

(defn complete!
  "Active, paused or blocked -> completed, and the process stops opening rounds. Refuses a
  goal that is already completed."
  [thread-id ref]
  (when (nil? thread-id) (no-session!))
  (let [c (fenced thread-id ref)]
    (when (= "completed" (:phase c))
      (refuse! :goal-completed "this goal is already completed." {}))
    (disarm! thread-id)
    (append-change! thread-id (snapshot (assoc c
                                               :phase      "completed"
                                               :revision   (next-revision c)
                                               :updated-at (System/currentTimeMillis))))))

(defn block!
  "Report that the goal cannot go on, as `{:code .. :reason ..}`.

  IT DOES NOT TAKE EFFECT ON THE FIRST REPORT. The same `code` has to be reported
  `block-rounds` times IN A ROW; until then the reason is kept as `:pending-block` and the
  phase does not move. A different code starts the count over -- a model that keeps finding
  NEW obstacles is not repeating one judgment. `:immediate?` skips the count, and exactly one
  caller uses it: the round driver's zero-progress brake, which is a fact the harness
  measured rather than a report the model made (`.scratch/goal` decision 9)."
  [thread-id ref {:keys [code reason immediate?]}]
  (when (nil? thread-id) (no-session!))
  (let [c (fenced thread-id ref)]
    (when-not (= "active" (:phase c))
      (refuse! :not-active
               (str "this goal is " (:phase c) ", so a blocker cannot be reported on it -- only an"
                    " active goal is being pushed at.")
               {:phase (:phase c)}))
    (let [{:keys [block-rounds]} (config thread-id)
          code    (str code)
          prior   (:pending-block c)
          reports (if (= code (str (:code prior))) (inc (long (or (:reports prior) 1))) 1)]
      (if (or immediate? (>= reports (long block-rounds)))
        (do (disarm! thread-id)
            (append-change! thread-id (snapshot (-> c
                                                    (assoc :phase "blocked"
                                                           :blocked {:code code :reason reason})
                                                    (dissoc :pending-block)
                                                    (assoc :revision   (next-revision c)
                                                           :updated-at (System/currentTimeMillis))))))
        (append-change! thread-id (snapshot (assoc c
                                                   :pending-block {:code code :reason reason
                                                                   :reports reports}
                                                   :revision   (next-revision c)
                                                   :updated-at (System/currentTimeMillis))))))))

(defn note-round!
  "One more goal round has been opened: `rounds + 1`, written down so a restart folds it back.
  Refuses a goal that is not active -- a round cannot open on a goal nobody is pushing."
  [thread-id ref]
  (when (nil? thread-id) (no-session!))
  (let [c (fenced thread-id ref)]
    (when-not (= "active" (:phase c))
      (refuse! :not-active
               (str "this goal is " (:phase c) ", so no round can open on it.")
               {:phase (:phase c)}))
    (append-change! thread-id (snapshot (assoc c
                                               :rounds     (inc (long (:rounds c)))
                                               :revision   (next-revision c)
                                               :updated-at (System/currentTimeMillis))))))

(defn clear!
  "Clear THREAD-ID's goal: a TOMBSTONE row, a NULL projection row, and no permission to
  continue. Answers nil whether or not there was one -- clearing what is not there is the
  same nothing.

  REF IS OPTIONAL HERE, unlike every other verb, and the reason is what a clear says: the
  tombstone is the same tombstone whatever the revision was, so a person's clear that races a
  model's edit still leaves exactly what they asked for. A caller that HAS a ref (the
  command, carrying its snapshot) still gets the fence: a clear built on a snapshot that has
  moved is refused, so a person cannot clear a goal they are not looking at."
  [thread-id ref]
  (when (nil? thread-id) (no-session!))
  (when-some [c (goal-for thread-id)]
    (when (and (map? ref)
               (not (and (= (str (:id ref)) (str (:id c)))
                         (= (str (:revision ref)) (str (:revision c))))))
      (moved! c ref))
    (disarm! thread-id)
    (append-change! thread-id {:id       (:id c)
                               :revision (next-revision c)
                               :phase    cleared-phase}))
  nil)

;; ------------------------------------------------------------------- the reminder

(def reminder-sentence
  "What every `<goal>` block tells the model to do about it -- ONE sentence, shared by the
  reminder and the round opening, so the two cannot say different things about the same goal."
  "朝着这个目标推进。用 `get_goal` 看清现状、`update_goal` 报进展或标完成；做不下去就说明卡在哪。")

(def round-instruction
  "The one line a ROUND OPENING adds to the reminder: the round is the message, and what the
  model owes at the end of it."
  "这一轮就做这件事：做到就用 `update_goal` 报 complete，做不下去就用 block 说明卡在哪。")

(defn reminder-text
  "SNAPSHOT -> the `<goal>` block the model reads. ONE function, because the reminder and the
  driver's round opening must say the same thing about the objective, the round and the cap."
  [g]
  (str "<goal revision=\"" (:revision g) "\">\n"
       (:objective g) "\n"
       "round " (:rounds g) "/" (:max-rounds g) "\n"
       reminder-sentence "\n"
       "</goal>"))

(defn round-turn
  "SNAPSHOT -> the USER message the round driver appends to open the next round: the reminder
  block (so the round says the same thing the reminder does) plus the one line that makes it
  a round. A real message, so it is in the conversation the client draws and in the record."
  [g]
  {:role    "user"
   :content (str (reminder-text g) "\n" round-instruction)})

(defn before-llm
  "HISTORY with the goal reminder for THREAD-ID APPENDED -- a session's goal half of the
  pre-LLM step, composed by `harness.cap.project/before-llm` beside the skill bodies and the
  job endings.

  ONLY AN ACTIVE GOAL IS INJECTED. Paused, blocked, completed and 'none at all' all answer the
  history unchanged: a paused goal must not keep pushing, a blocked one has already said where
  it is stuck, and a completed one is over.

  IT IS IDEMPOTENT BY CONTENT: if the history already carries this exact block (a round
  opening carries it, and so does the injection this function made a moment ago) nothing is
  appended. The block moves with the revision and the round number, so a message that changes
  the goal -- or a round opening -- puts a NEW block at the end and leaves the old ones where
  they are: the model really did read those."
  [history thread-id]
  (let [g (goal-for thread-id)]
    (if-not (and g (= "active" (:phase g)))
      history
      (let [history (vec history)
            text    (reminder-text g)]
        (if (some (fn [m] (and (string? (:content m)) (str/includes? (:content m) text))) history)
          history
          (conj history {:role "user" :content text}))))))
