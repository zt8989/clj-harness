(ns harness.cap.todos
  "A session's task list: what `todo_write` writes, and what anything else reads
  back.

  WHY IT IS IN THE STORE. The tool REPLACES the whole list on every call, and
  'can it be rewritten' is exactly the criterion harness.infra.db uses to tell state from
  record -- so a task list is state, and it lives in a row rather than in the
  conversation. It also means the list outlives the run that wrote it: a restart,
  another process, and a panel that shows somebody what the agent is working on all
  read the same row.

  THE LIST IS ONE VALUE, stored as JSON text in one column. It is written whole by
  the only writer and read whole by the only reader, so nothing here ever queries
  by element -- see harness.infra.db/todos-table for why a row per item would buy
  nothing.

  VALIDATION IS HERE, NOT IN THE TOOL. Every refusal a model can act on is raised
  by `write!`, so a second caller (an eval, a test, a future endpoint) gets the
  same rules instead of a second implementation of them. The tool body is one
  line because of it."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness.cap.reminder :as reminder]
            ;; The config.edn discipline, for the auto reminder's fuse (`:session :todo`): read
            ;; fresh on every call, and nothing here reads back through `providers`, so the two
            ;; namespaces do not form a ring.
            [harness.cap.providers :as providers]
            [harness.infra.db :as db]))

;; THE REMINDER SECTION AT THE FOOT OF THIS FILE defines these; `write!` and `forget!` reach
;; for them from above, because a list with nothing outstanding turns the auto reminder off and
;; a session taken back takes its reminder memory with it.
(declare outstanding? disarm! forget-reminder!)

(def statuses
  "The three states an item may be in, and the WHOLE vocabulary. Data rather than
  a cond, because every refusal below has to state the legal set, and a
  hand-written sentence per failure is how the two drift apart."
  ["pending" "in_progress" "completed"])

(def max-items
  "How many items one list may hold. A list longer than this is not a plan, it is a
  transcript -- and the answer would spend the context window the plan exists to
  save."
  50)

;; ------------------------------------------------------------------ validation

(defn- check!
  "Refuse a payload the list cannot be built from, naming the problem AND the fix.
  Each of these is something a model actually sends: a bare string instead of an
  array, an item that is just its text, a status it invented."
  [items]
  (when-not (vector? items)
    (throw (ex-info (str "`todos` must be an array of {\"content\", \"status\"} objects,"
                         " but got " (pr-str items) ". Send the COMPLETE list, or [] to"
                         " clear it.")
                    {:argument :todos :value items :reason :not-an-array})))
  (when (> (count items) max-items)
    (throw (ex-info (str "`todos` holds " (count items) " items; at most " max-items
                         " fit in one list. Split the work, or drop what is already"
                         " finished.")
                    {:argument :todos :count (count items) :max max-items
                     :reason :too-many-items})))
  (doseq [[i item] (map-indexed vector items)]
    (when-not (map? item)
      (throw (ex-info (str "item " (inc i) " of `todos` must be an object with"
                           " \"content\" and \"status\", but it is " (pr-str item) ".")
                      {:argument :todos :index i :value item :reason :item-not-an-object})))
    (let [content (:content item)]
      (when-not (and (string? content) (not (str/blank? content)))
        (throw (ex-info (str "item " (inc i) " of `todos` needs a non-empty"
                             " \"content\"; got " (pr-str content) ".")
                        {:argument :todos :index i :value content
                         :reason :item-without-content}))))
    (let [status (:status item)]
      (when-not (contains? (set statuses) status)
        (throw (ex-info (str "item " (inc i) " of `todos` has status " (pr-str status)
                             "; it must be one of " (str/join ", " statuses) ".")
                        {:argument :todos :index i :value status
                         :reason :unknown-status})))))
  ;; AT MOST ONE ITEM IN PROGRESS, which is the whole point of the state existing:
  ;; 'what am I doing' has one answer, and a list where three things are in
  ;; progress does not say what to do next. A workflow rule, not a safety boundary
  ;; -- refusable, and one line to relax.
  (let [running (filter #(= "in_progress" (:status %)) items)]
    (when (> (count running) 1)
      (throw (ex-info (str (count running) " items are \"in_progress\"; at most one may"
                           " be. Mark the others \"pending\" or \"completed\" so the"
                           " list still says what you are doing now.")
                      {:argument :todos :count (count running)
                       :reason :more-than-one-in-progress})))))

;; ----------------------------------------------------------------- reading/writing

(defn items-for
  "THREAD-ID's task list, in the order it was written, or [] when this session has
  never written one -- 'no list' and 'an empty list' are different facts in the
  store but the same answer to a reader, and this is the answer a reader wants.

  A thread with no id has no list and no row to read: see `write!`."
  [thread-id]
  (if (nil? thread-id)
    []
    (if-let [row (first (db/select "SELECT items FROM todos WHERE thread_id = ?" thread-id))]
      (vec (json/read-str (:items row) :key-fn keyword))
      [])))

;; ------------------------------------------------------------- the session's key
;;
;; ONE SENTENCE, TWO CALLERS. A task list's row is keyed by the session's id, so a
;; call with no session in scope has nothing to write under a placeholder and
;; nothing to read back: both refuse by NAME (`:no-session`), in the same sentence,
;; differing only in the tool they tell the model to call.

(defn- no-session!
  "The refusal both doors answer a call with no session with. TOOL is the name the
  model is told to call (`todo_write` or `todo_read`), so the sentence it reads
  points at the way in rather than at the wall."
  [tool]
  (throw (ex-info (str "there is no session in scope, and a task list belongs to one"
                       " -- its row is keyed by the session's id. Call " tool
                       " from a run, or name a thread.")
                  {:reason :no-session})))

(defn write!
  "Replace THREAD-ID's task list with ITEMS and answer the list AS STORED.

  ITEMS is the COMPLETE list -- there is no append and no partial update, and []
  clears the list. What comes back is the normalized list (the two fields this
  namespace understands, in order), so the model is told what was recorded rather
  than what it sent.

  A THREAD WITH NO ID IS REFUSED, not stored under a placeholder. The row's key IS
  the session, so a list written without one would be readable by nobody --
  including the caller, on its next call.

  WRITING A LIST WITH NOTHING OUTSTANDING TURNS THE AUTO REMINDER OFF (`disarm!` below):
  the switch exists so unfinished work keeps being pushed at the model, and 'every item is
  completed' is that work being over. The person turns it back on for whatever comes next.

  Throws a named failure for every payload `check!` refuses."
  [thread-id items]
  (when (nil? thread-id) (no-session! "todo_write"))
  (check! items)
  (let [stored (mapv (fn [item] {:content (:content item) :status (:status item)}) items)]
    (db/with-transaction
      (fn [c]
        (db/execute! c "INSERT INTO todos (thread_id, items, updated_at)
                        VALUES (?, ?, ?)
                        ON CONFLICT(thread_id) DO UPDATE SET
                          items = excluded.items,
                          updated_at = excluded.updated_at"
                    thread-id (json/write-str stored) (System/currentTimeMillis))))
    ;; SEE THE DOCSTRING: a list with nothing left to do turns the auto reminder off.
    (when-not (outstanding? stored) (disarm! thread-id))
    stored))

(defn forget!
  "Drop THREAD-ID's task list -- the ROW, not an empty list.

  THE DIFFERENCE IS VISIBLE TO THE NEXT READER: an empty list says 'this conversation has no
  tasks', and a missing row says 'this conversation has no list yet'. For a conversation being taken
  back (`.scratch/session-lifecycle/`) the second is the honest one -- there is nothing left for a
  reader to be told about."
  [thread-id]
  (db/with-transaction
    (fn [c] (db/execute! c "DELETE FROM todos WHERE thread_id = ?" (str thread-id))))
  ;; AND WHAT THIS PROCESS REMEMBERED ABOUT ITS REMINDERS GOES WITH IT -- see the reminder
  ;; section below: a forgotten session has no next round to open and no list to remind about.
  (forget-reminder! thread-id))

;; ----------------------------------------------------------------- one vocabulary
;;
;; BOTH ANSWERS DESCRIBE THE SAME LIST TO THE SAME MODEL, so the words for a count,
;; for a status and for an empty list are written ONCE and spelled out by neither:
;; the receipt answers a call that just SENT the list, the read answers one that
;; does not have it. What separates them is the CLAIM rather than the vocabulary --
;; 'stored for this session' is something only the writer may say.

(def ^:private empty-answer
  "'Never wrote one' and 'wrote an empty list' are different facts in the store and
  ONE answer to a reader: `items-for` already collapses them, so this sentence is
  the whole of both, and both answers below say it."
  "the task list is empty now -- nothing is planned.")

(def ^:private status-markers
  "A status in the three characters it takes, in `statuses`' own vocabulary: `[x]`
  done, `[~]` being done now, `[ ]` not started. DATA rather than a cond, for the
  reason `statuses` is, and drawn by the READ only -- the receipt does not repeat
  the list, so it carries no marker at all."
  {"completed" "[x]" "in_progress" "[~]" "pending" "[ ]"})

(defn- item-count
  "N item / N items. The pluralization lives here so that no answer says '1 items'."
  [n]
  (str n " item" (when (not= 1 n) "s")))

(defn- distribution
  "How ITEMS stand, as `1 in progress, 1 completed` -- nil when a list of only
  pending items has nothing else to say. The two labels live here and nowhere else,
  which is what keeps a receipt and a read from naming one status two ways."
  [items]
  (->> [["in progress" (count (filter #(= "in_progress" (:status %)) items))]
        ["completed"   (count (filter #(= "completed" (:status %)) items))]]
       (remove (comp zero? second))
       (map (fn [[label k]] (str k " " label)))
       (str/join ", ")))

(defn render
  "ITEMS as the RECEIPT a `todo_write` answers with.

  A RECEIPT, NOT AN ECHO. The list was in the call that stored it, so it is already
  in front of the model: repeating it here would pay for the same tokens twice and
  tell the model nothing it did not just send. What the answer owes is the fact --
  how many items there are, and how they stand -- and that is all this returns.

  The marker-per-line rendering went with it -- and came back for the READ
  (`read-back` below), which is the one answer that has to hand the items over. A
  screen that wants to draw the list reads the call's own arguments
  (`ui/src/message-parts.tsx`), and the stored row is read with `items-for`."
  [items]
  (if (empty? items)
    empty-answer
    (let [counts (distribution items)]
      (str (item-count (count items))
           " stored for this session"
           (when (seq counts) (str " (" counts ")"))
           "."))))

(defn read-back
  "THREAD-ID's task list, as the ANSWER to a read: one row per item with its status
  marker, then one sentence with the total and how the items stand.

  NOT A RECEIPT. `render` above answers the call that just SENT the list and
  withholds it on purpose; this answers a call that does NOT have it, so the items
  themselves are the point rather than a cost paid twice. A compressed context, a
  later process and an eval all read the same row -- the list outlives the run that
  wrote it, which is exactly why a way back to it is needed.

  A LIST NEVER WRITTEN AND AN EMPTY LIST ANSWER THE SAME SENTENCE, because
  `items-for` already turned them into the same value.

  NO SESSION IN SCOPE IS REFUSED BY NAME (`:no-session`), in `write!`'s own
  sentence: a list belongs to a session, so there is nothing to read back without
  one. `items-for` answers [] for a nil thread-id -- a caller that only wants the
  value has nobody to tell -- while a TOOL has a caller to tell."
  [thread-id]
  (when (nil? thread-id) (no-session! "todo_read"))
  (let [items (items-for thread-id)]
    (if (empty? items)
      empty-answer
      (let [counts (distribution items)]
        (str (str/join "\n"
                       (map (fn [item]
                              ;; The default is the OLD renderer's fallback, and
                              ;; `check!` is what makes it unreachable: every stored
                              ;; item's status is one of `statuses`.
                              (str "- " (get status-markers (:status item) "[ ]")
                                   " " (:content item)))
                            items))
             "\n"
             (item-count (count items))
             (when (seq counts) (str " (" counts ")"))
             ".")))))

;; ----------------------------------------------------------------- the reminder
;;
;; THE SECOND ROAD INTO THE CONVERSATION. Everything above is the LIST -- what `todo_write`
;; stores and what a reader hands back. This section is what happens when the list is NOT
;; finished: a `<system-reminder>` saying so, carried into the conversation as a message of the
;; harness's own.
;;
;; TWO HANDS, ONE SHAPE. A person presses (`remind`), or a finished round finds work left while
;; the person's switch is on (`auto`) -- and both become the SAME message, built by
;; `reminder-turn` below. That is deliberate: a model reading the reminder must not be able to
;; tell (or need to tell) which hand pressed it, and one writer means the two can never word it
;; differently.
;;
;; WHY IT IS NOT A DERIVED INJECTION like the goal's. `harness.cap.goal/before-llm` re-derives a
;; block before EVERY call and is idempotent by content, because a goal is a STANCE that holds
;; for as long as it is active. A reminder is an EVENT -- 'this round is over and the work is
;; not' -- which is worth saying once. So it is a real user message, and the two moments one is
;; made are a person's press and a finished round.
;;
;; AND WHY THE SWITCH IS PROCESS MEMORY, like the goal's `armed` and the command queue: 'keep
;; pushing THIS conversation's list' is a fact about the process serving it right now. A
;; restart, an eviction and a fork all drop it -- and UNLIKE the goal's `armed`, nothing puts
;; it back but the person pressing the switch again (`arm!`'s one door,
;; `harness.edge.http/run-todo-command!`). A goal's arm answers 'I spoke, carry on'; this is a
;; SWITCH, and a switch a stray message turns on is a switch that starts spending money on its
;; own.

(def default-max-rounds
  "How many auto-reminder rounds one session may open before the fuse blows, when config.edn
  says nothing. The same small number, and the same reasoning, as
  `harness.cap.goal/default-max-rounds`: a round is a whole run and the money is per token, so
  the cap is a FUSE rather than a target."
  25)

(def ^:private known-config-keys #{:max-rounds})

(defn- todo-block
  "config.edn's `:session :todo` block for THREAD-ID, as written -- nothing merged with the
  defaults, and every key checked by name, exactly as the goal's own block is: a typo fails
  here rather than leaving the reminder bounded by whatever the default happened to be."
  [thread-id]
  (let [b (:todo (providers/session-config thread-id))]
    (when-not (or (nil? b) (map? b))
      (throw (ex-info (str "config.edn's :session :todo must be a map of knobs (:max-rounds),"
                           " but it is " (pr-str b))
                      {:reason :bad-todo-config :value b})))
    (let [unknown (remove known-config-keys (keys b))]
      (when (seq unknown)
        (throw (ex-info (str "config.edn's :session :todo carries " (count unknown)
                             " key(s) nothing reads: " (str/join ", " (sort (map name unknown)))
                             " -- known: " (str/join ", " (sort (map name known-config-keys))))
                        {:reason :unknown-todo-key :keys (vec unknown)}))))
    (or b {})))

(defn- knob
  "KEY's value in BLOCK, or FALLBACK -- refusing anything that is not a positive whole number,
  by name. `harness.cap.goal/knob`'s rule over this block, spelled again here rather than
  reached for: the two caps are two different promises, and neither namespace owns the
  other's."
  [block key fallback]
  (let [n (get block key fallback)]
    (when-not (and (integer? n) (pos? n))
      (throw (ex-info (str "config.edn's :session :todo " (name key) " must be a positive whole"
                           " number, but it is " (pr-str n))
                      {:reason :bad-todo-knob :key key :value n})))
    (long n)))

(defn config
  "The reminder knobs THIS SESSION runs under: config.edn's `:session :todo` over the default
  written here. Read fresh on every call (the config.edn discipline), so editing the file
  moves the answer with no restart."
  [thread-id]
  {:max-rounds (knob (todo-block thread-id) :max-rounds default-max-rounds)})

(defn outstanding
  "ITEMS that are not done yet -- everything whose status is not \"completed\", in the list's
  own order. THE QUESTION THIS WHOLE SECTION IS ABOUT: a reminder is owed exactly when this is
  not empty."
  [items]
  (vec (remove #(= "completed" (:status %)) items)))

(defn outstanding?
  "Is there anything left in ITEMS for a reminder to be about?"
  [items]
  (boolean (seq (outstanding items))))

(defn fingerprint
  "ITEMS -> a string that changes whenever the list's CONTENT or one of its STATUSES does, and
  not otherwise. THE BRAKE'S WHOLE EVIDENCE (the driver in `harness.edge.http`): two auto
  reminders about the same fingerprint would be the same sentence twice, so the second is the
  one not sent."
  [items]
  (let [md (java.security.MessageDigest/getInstance "SHA-1")
        bs (.digest md (.getBytes (str/join "\u0000"
                                            (map (fn [item]
                                                   (str (:status item) "\u0001" (:content item)))
                                                 items))
                                    "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff)) bs))))

(def reminder-sentence
  "What every reminder block asks the model to do -- ONE sentence, and the only place this
  request is spelled."
  (str "接着把任务清单做完：做完一项就把它的状态改成 \"completed\"；"
       "清单有变化时用 `todo_write` 重新提交整份清单。"))

(defn reminder-text
  "ITEMS -> the `<system-reminder>` block one reminder carries, or nil when there is nothing
  to remind about: no list, an empty one, or one whose every item is \"completed\".

  THE FIRST LINE IS THE LABEL (`Task list reminder:` -- `harness.cap.reminder/labels` names it),
  which is what files the block as an INJECTION in the record and titles its card on screen.
  The whole list is in the block rather than only the unfinished items: where an item stands
  is half of what the model needs to pick up the work."
  [items]
  (when (outstanding? items)
    (let [left (outstanding items)]
      (reminder/wrap
       (concat [(str "Task list reminder: " (item-count (count left)) " not done yet"
                     (when-some [d (distribution items)] (str " (" d ")"))
                     " -- " (item-count (count items)) " in all.")]
               (map (fn [item] (str "- " (get status-markers (:status item) "[ ]") " " (:content item)))
                    items)
               [reminder-sentence])))))

(defn reminder-turn
  "ITEMS -> the message a reminder puts into the conversation: role user, the block above as
  its content. ONE function, so a person's press, the driver's round and any test cannot word
  the same reminder three ways."
  [items]
  {:role "user" :content (reminder-text items)})

;; -------------------------------------------------------- what this process remembers

(defonce ^:private auto
  ;; thread-id -> true, for the sessions THIS PROCESS is auto-reminding. Process memory on
  ;; purpose -- see the section header; `arm!` is the only thing that puts a session in here.
  (atom #{}))

(defonce ^:private due
  ;; thread-id -> true, for a PERSON'S reminder owed at the session's next model call. Set
  ;; where `remind` is executed with a run in flight (`note-manual!`), taken by `take-manual!`
  ;; in the pre-LLM step. Process memory for the same reason `auto` is.
  (atom #{}))

(defonce ^:private fuse
  ;; thread-id -> {:rounds n :prints <fingerprint>}: how many auto rounds this process opened
  ;; for the session, and the list the last of them was about. The second half is the brake
  ;; (`fingerprint` above), the first is the fuse (`config`'s :max-rounds).
  (atom {}))

(defn auto?
  "Is THIS PROCESS auto-reminding THREAD-ID? False for a session nobody turned it on for, and
  for every id this process has never heard of."
  [thread-id]
  (contains? @auto (str thread-id)))

(defn arm!
  "Turn THREAD-ID's auto reminder ON in this process -- the switch's ONLY door. Answers true.

  ARMING ALSO CLEARS THE FUSE: a person pressing the switch is a fresh grant, and a count left
  over from an earlier stretch would blow a fuse they never spent."
  [thread-id]
  (let [tid (str thread-id)]
    (swap! auto conj tid)
    (swap! fuse dissoc tid)
    true))

(defn disarm!
  "Turn THREAD-ID's auto reminder OFF in this process. Called by the switch, by a list every
  item of which is done (`write!` above), and when the session is REBUILT -- a conversation
  this process had to fold back into memory is one nobody has pressed the switch for yet."
  [thread-id]
  (let [tid (str thread-id)]
    (swap! auto disj tid)
    (swap! due disj tid)
    (swap! fuse dissoc tid))
  nil)

(defn forget-reminder!
  "Everything this process remembered about THREAD-ID's reminders, dropped -- what a session
  taken back leaves behind (`forget!` above is its row)."
  [thread-id]
  (disarm! thread-id))

(defn reset-auto!
  "The process's whole reminder memory, emptied -- a test fixture's door, like the goal's
  `reset-armed!`."
  []
  (reset! auto #{})
  (reset! due #{})
  (reset! fuse {}))

(defn note-manual!
  "A PERSON'S reminder is owed at THREAD-ID's next model call. Called where the `remind`
  command is executed with a run in flight, and taken by `take-manual!` at that run's next
  pre-LLM step."
  [thread-id]
  (swap! due conj (str thread-id))
  true)

(defn take-manual!
  "Is a person's reminder owed here? TAKE IT -- the answer is true only for the call that
  consumed the request, because a reminder is an event and not a standing injection."
  [thread-id]
  (let [tid (str thread-id)]
    (contains? (first (swap-vals! due disj tid)) tid)))

(defn fuse-for
  "THREAD-ID's fuse as `{:rounds n :prints <fingerprint-or-nil>}` -- the zero value for a
  session this process has not opened an auto round for."
  [thread-id]
  (get @fuse (str thread-id) {:rounds 0 :prints nil}))

(defn note-a-round!
  "One more AUTO round has been opened for THREAD-ID, about the list PRINTS fingerprints.
  Answers the new count."
  [thread-id prints]
  (let [tid (str thread-id)
        n   (inc (long (:rounds (fuse-for tid))))]
    (swap! fuse assoc tid {:rounds n :prints prints})
    n))

(defn note-reminded!
  "Record that a reminder about the list PRINTS has just been DELIVERED, without counting one
  of the fuse's rounds. Called by the MANUAL press as well as by `note-a-round!` above.

  THIS IS WHAT KEEPS THE TWO HANDS FROM SAYING THE SAME SENTENCE TWICE (owner, 2026-10-03): the
  driver's brake is 'is this the list the LAST reminder was about', and a manual press that
  recorded nothing left it looking like no reminder had ever happened -- so with the switch on,
  the round the press opened was followed at once by an auto round repeating it. A press is a
  reminder; the brake should count it as one."
  [thread-id prints]
  (let [tid (str thread-id)]
    ;; `merge` over the zero value, so a row is never left without `:rounds` (`fuse-for`'s shape,
    ;; and the driver reads that key with `long`).
    (swap! fuse update tid (fn [row] (merge {:rounds 0 :prints nil} row {:prints prints})))))

(defn before-llm
  "HISTORY with a person's reminder for THREAD-ID appended, when one is owed -- the todo half
  of the pre-LLM step, composed by `harness.cap.project/before-llm` beside the goal's.

  ONLY WHAT A PERSON ASKED FOR AND HAS NOT BEEN GIVEN comes through this door: the auto
  reminder does not pass here at all (it is a ROUND, opened by the edge's driver), and a list
  that finished between the press and this call has nothing to say -- the request is spent
  and the history unchanged."
  [history thread-id]
  (if (take-manual! thread-id)
    (if-some [text (reminder-text (items-for thread-id))]
      (conj (vec history) {:role "user" :content text})
      history)
    history))
