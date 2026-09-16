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
  (when (nil? thread-id)
    (throw (ex-info (str "there is no session in scope, and a task list belongs to one"
                         " -- its row is keyed by the session's id. Call todo_write"
                         " from a run, or name a thread.")
                    {:reason :no-session})))
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

(defn render
  "ITEMS as the lines a model reads back -- the answer to a `todo_write`, and the
  same shape anything else showing a list should use, so there is one rendering of
  a task list rather than one per caller.

  The marker per line is the STATUS in the 3 characters it takes: `[x]` done,
  `[~]` being done now, `[ ]` not started. The numbering is the list's own order,
  which is why the order is worth sending."
  [items]
  (if (empty? items)
    "the task list is empty now -- nothing is planned."
    (let [n        (fn [status] (count (filter #(= status (:status %)) items)))
          counts   (->> [["in progress" (n "in_progress")]
                         ["completed" (n "completed")]]
                        (remove (comp zero? second))
                        (map (fn [[label k]] (str k " " label)))
                        (str/join ", "))]
      (str (count items) " item" (when (not= 1 (count items)) "s")
           " stored for this session"
           (when (seq counts) (str " (" counts ")"))
           ":\n"
           (str/join "\n"
                     (map-indexed (fn [i {:keys [content status]}]
                                    (str "  " (case status
                                                "completed"   "[x]"
                                                "in_progress" "[~]"
                                                "[ ]")
                                         " " (inc i) ". " content))
                                  items))))))
