(ns harness.edge.compaction
  "Compaction's WRITE half: choose the range, summarize it with ONE model call, record it.

  THE READ HALF LIVES IN `harness.edge.replay` (`compaction-facts` / `model-nodes` /
  `compacted-messages`) and is what makes a recorded compaction change the MODEL VIEW and
  nothing else. This namespace PRODUCES those rows; the two must agree on their shape, and
  the shape is the one `harness.edge.replay/compaction-facts` reads back.

  IT TAKES ITS EFFECTS AS ARGUMENTS -- `append` (write a row) and `summarize` (one model
  call) -- so the whole decision is testable without a provider or a file: the lock, the
  range, the order of the rows and the no-op case are ordinary function calls.

  THE LOCK IS THE ROW ORDER. `compaction/start` goes out BEFORE any work and
  `compaction/end` after all of it, so a crash in between leaves an UNMATCHED START -- a
  lock a later reader can see -- rather than an `end` that falsely claims success. A failed
  attempt still closes its `end`, carrying the error: failure is not left unrecorded, it is
  recorded as failure.

  THE SUMMARY RIDES THE FACT. Where the source strategy writes a separate replacement
  `user/message` carrying a surface `replace`, this harness has no separate surface
  structure -- the model view IS a projection over the record -- so the summary text and
  the range it replaces live on ONE `context/compacted` fact, and the projection
  (`harness.edge.replay/model-nodes`) synthesizes the message at the range's position."
  (:require [harness.edge.pressure :as pressure]
            [harness.cap.project :as project]
            [harness.edge.ag-ui :as ag]
            [harness.edge.replay :as replay]))

(def summary-instruction
  "What the summarizer is told. It asks for the facts a continuing model needs and for
  nothing invented -- exact paths, commands, error strings, identifiers, numbers, and what
  was already decided (including what was tried and failed)."
  "Summarize the conversation above so another model can continue the work from it. Keep
exact file paths, commands, error strings, identifiers, numbers, function signatures and
decisions already made, including anything that was tried and failed. Do not invent
anything. Be concise.")

(defn check-ratios!
  "Validate a merged compaction pair, or throw naming what is wrong. Split out so the
  refusal can be asserted without a file."
  [merged]
  (doseq [k [:threshold-ratio :retain-ratio] :let [v (get merged k)]]
    (when-not (and (number? v) (pos? v) (<= v 1))
      (throw (ex-info (str "harness.edn :compaction " (name k) " must be a fraction in (0, 1], but it is "
                           (pr-str v))
                      {:key k :value v :reason :bad-compaction-value}))))
  (when (>= (:retain-ratio merged) (:threshold-ratio merged))
    (throw (ex-info (str "harness.edn :compaction retain-ratio " (:retain-ratio merged)
                         " must be strictly below threshold-ratio " (:threshold-ratio merged))
                    {:reason :retain-not-below-threshold})))
  merged)

(defn- block
  "harness.edn's `:compaction` block for THREAD-ID, the PROJECT level over the USER level,
  as it was written -- nothing merged with the defaults and nothing validated. The one reader,
  so `config` and `overflow-retries` cannot fold the same file two different ways."
  [thread-id]
  (let [{:keys [user project]} (project/harness-edn-levels thread-id)]
    (reduce (fn [m level]
              (let [b (:compaction (get {:user user :project project} level))]
                (if (map? b) (merge m b) m)))
            {}
            [:user :project])))

(defn config
  "The compaction proportions THIS SESSION is configured with, from harness.edn's
  `:compaction` (`{:threshold-ratio 0.7 :retain-ratio 0.16}`), the project level over the
  user level, and `harness.edge.pressure`'s defaults when neither says anything.

  REFUSES A PAIR THAT CANNOT WORK, naming what is wrong: a retain that is not STRICTLY
  below the threshold would keep everything a compaction was asked to shrink, and a value
  that is not a fraction is not a proportion at all. Reading is on demand (harness.edn is
  re-read, not cached), so a bad value is refused the moment a compaction is asked for."
  [thread-id]
  (check-ratios! (merge pressure/default-ratios (block thread-id))))

(def default-overflow-retries
  "How many times ONE model call the vendor refused for LENGTH may be retried after an
  aggressive compaction, when `harness.edn` says nothing: once. A retry happens only when the
  aggressive pass actually shortened the model view, so this bounds a path that must make
  progress rather than a loop that might not. `0` disables the recovery."
  1)

(defn overflow-retries
  "How many times a length-refused model call may be retried for this session: `harness.edn`'s
  `:compaction :overflow-retries`, the project level over the user level, defaulting to
  `default-overflow-retries`. `0` disables the recovery -- the vendor's own refusal is then
  handed out untouched.

  REFUSES A VALUE THAT IS NOT A WHOLE NUMBER OF RETRIES, naming it. A fraction or a negative is
  not a count, and rounding one would hide a typo in the single number that bounds a retry
  path."
  [thread-id]
  (let [n (get (block thread-id) :overflow-retries default-overflow-retries)]
    (when-not (and (integer? n) (not (neg? n)))
      (throw (ex-info (str "harness.edn :compaction overflow-retries must be a whole number of"
                           " retries (0 disables the recovery), but it is " (pr-str n))
                      {:key :overflow-retries :value n :reason :bad-overflow-retries})))
    n))

;; ------------------------------------------------------------------------ the lock

(defn- lifecycle-rows
  "The compaction lifecycle rows of RECORDS, in order, as {:kind :payload}."
  [records]
  (keep (fn [row]
          (let [kind (replay/kind row)]
            (when (#{"compaction/start" "compaction/end"} kind)
              {:kind kind :payload (replay/payload row)})))
        records))

(defn lock-active?
  "RECORDS -> the compaction id of an UNMATCHED `compaction/start`, or nil when the lock is
  free. A started-but-not-ended compaction blocks every entry point (a second one would
  pick a range the first is already replacing); a matched pair is over."
  [records]
  (first
   (reduce (fn [open {:keys [kind payload]}]
             (let [id (:compactionId payload)]
               (if (= "compaction/start" kind)
                 (conj open id)
                 (vec (remove #(= id %) open)))))
           []
           (lifecycle-rows records))))

;; ----------------------------------------------------------------------- the range

(defn- protected-node?
  "Is NODE part of the session's OPENING -- the part no compaction may take? Its instruction
  files, skills catalog and birth context are what every later request is read against, and a
  summary is not a substitute for them (`.scratch/session-opening`), so BOTH plans start the
  head after them."
  [node]
  (let [m (:message node)]
    (or (ag/opening-entry? m)
        (= ag/context-entry-id (:id m)))))

(defn plan
  "RECORDS + WINDOW + RETAIN-RATIO -> the HEAD to compact, or nil when there is none.

    {:shadowed [ids] :messages [msgs] :head-tokens n}

  THE TAIL IS THE MOST RECENT NODES whose estimated size reaches
  `floor(window * retain-ratio)` -- the part kept VERBATIM -- and the head is everything
  before it. nil when the whole surface fits in the tail: there is nothing to do, and
  nothing is written.

  THE NODES ARE THE MODEL-FACING SURFACE (`harness.edge.replay/model-nodes`): earlier
  compactions' summaries included, and carrying the ids `:shadowed` is made of."
  [records window retain-ratio]
  (let [records    (vec records)
        nodes      (replay/model-nodes (replay/entries records)
                                     (replay/compaction-facts records)
                                     (replay/prune-facts records))
        budget     (long (Math/floor (* (double window) (double retain-ratio))))
        size       (fn [j] (pressure/estimate-message (:message (nth nodes j))))
        k          (count (take-while protected-node? nodes))]
    (loop [j (dec (count nodes)) acc 0]
      (cond
        (and (pos? budget) (>= acc budget))
        (when (>= j k)
          (let [head (subvec nodes k (inc j))]
            {:shadowed    (mapv :id head)
             :messages    (mapv :message head)
             :head-tokens (reduce + 0 (map size (range k (inc j))))}))

        (neg? j) nil
        :else    (recur (dec j) (+ acc (size j)))))))

(defn- unit-start
  "NODES -> the index of the first node of the NEWEST INDIVISIBLE UNIT.

  A unit is what the projection may not split: the newest `user` message and everything after
  it -- the request the model still owes an answer to, plus the in-flight work on it. When the
  surface holds no user message at all (a bare assistant/tool tail), the unit is the smallest
  LEGAL suffix, found by walking back over `tool` messages: a suffix may not BEGIN with a tool
  result, because an OpenAI-shaped vendor refuses a `tool` message whose `tool_calls` are not
  in the request."
  [nodes]
  (or (last (keep-indexed (fn [i node] (when (= "user" (:role (:message node))) i)) nodes))
      (loop [i (dec (count nodes))]
        (if (and (pos? i) (= "tool" (:role (:message (nth nodes i)))))
          (recur (dec i))
          i))))

(defn overflow-plan
  "RECORDS + WINDOW + RETAIN-RATIO -> the head to compact when the vendor has ALREADY refused
  the request for its LENGTH, or nil when there is nothing left to remove.

  IT BYPASSES THE BUDGET ON PURPOSE. `plan` keeps `window * retain-ratio` worth of tail
  because the pressure meter is PREDICTING that the window will run out; here the vendor has
  already answered no, so there is nothing to predict and no capacity to consult -- the
  estimate is simply wrong, which is the case `harness.edge.pressure` admits. The tail is the
  smallest thing worth keeping (`unit-start`), and everything between the opening and it is
  summarized.

  WINDOW and RETAIN-RATIO are accepted and IGNORED so this has the same arity as `plan` and
  the writer can be handed either: the recovery exists precisely because no capacity was
  known, so it must not require one."
  ([records] (overflow-plan records nil nil))
  ([records _window _retain-ratio]
   (let [records (vec records)
         nodes   (replay/model-nodes (replay/entries records)
                                     (replay/compaction-facts records)
                                     (replay/prune-facts records))
         k       (count (take-while protected-node? nodes))
         start   (unit-start nodes)]
     (when (> start k)
       (let [head (subvec nodes k start)]
         {:shadowed    (mapv :id head)
          :messages    (mapv :message head)
          :head-tokens (reduce + 0 (map #(pressure/estimate-message (:message %)) head))})))))

;; ------------------------------------------------------------------------ the run

(defn perform!
  "One compaction. RECORDS is the log as it stands; the effects are APPEND (write a row:
  `(append kind payload)`) and SUMMARIZE (the head's messages -> summary text). Returns
  `{:shadowed [ids] :summary text}` when it compacted, and nil when it did not -- because
  the lock was held, or because there was no range.

  THE ROWS GO OUT IN THE ORDER THE LOCK REQUIRES: start, the summary fact, end. Nothing is
  written when there is no range -- a pair of rows recording an empty compaction is noise on
  the record and a lie about work that happened.

  A PLAN CAN BE HANDED IN (`:plan-fn`), and the aggressive one is: when the vendor has
  already refused the request for its length, `overflow-plan` bypasses the budget and keeps
  only the newest indivisible unit. Everything else -- the lock, the row order, the no-op
  rule -- is the same, so the two paths cannot drift."
  [records {:keys [window retain-ratio append summarize plan-fn]}]
  (when-not (lock-active? records)
    (when-let [head ((or plan-fn plan) records window retain-ratio)]
      (let [id (str (java.util.UUID/randomUUID))]
        (append "compaction/start" {:compactionId id})
        (try
          (let [summary (summarize (:messages head))]
            (append "context/compacted"
                    {:compactionId id
                     :summary      summary
                     :shadowed     (:shadowed head)
                     :range        {:start (first (:shadowed head))
                                    :end   (last (:shadowed head))}
                     :tokens       (:head-tokens head)})
            (append "compaction/end" {:compactionId id})
            {:shadowed (:shadowed head) :summary summary})
          (catch Throwable t
            (append "compaction/end" {:compactionId id :error (ex-message t)})
            (throw t)))))))
