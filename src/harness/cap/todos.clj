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
            [harness.infra.db :as db]))

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
    stored))

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
