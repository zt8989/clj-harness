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
                                     (replay/compaction-facts records))
        budget     (long (Math/floor (* (double window) (double retain-ratio))))
        size       (fn [j] (pressure/estimate-message (:message (nth nodes j))))
        ;; THE SESSION'S OPENING IS NOT COMPACTABLE. Its instruction files, skills catalog and
        ;; birth context are what every later request is read against, and a summary is not a
        ;; substitute for them (`.scratch/session-opening`), so the head starts AFTER them.
        protected? (fn [node]
                     (let [m (:message node)]
                       (or (ag/opening-entry? m)
                           (= ag/context-entry-id (:id m)))))
        k          (count (take-while protected? nodes))]
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

;; ------------------------------------------------------------------------ the run

(defn perform!
  "One compaction. RECORDS is the log as it stands; the effects are APPEND (write a row:
  `(append kind payload)`) and SUMMARIZE (the head's messages -> summary text). Returns
  `{:shadowed [ids] :summary text}` when it compacted, and nil when it did not -- because
  the lock was held, or because there was no range.

  THE ROWS GO OUT IN THE ORDER THE LOCK REQUIRES: start, the summary fact, end. Nothing is
  written when there is no range -- a pair of rows recording an empty compaction is noise on
  the record and a lie about work that happened."
  [records {:keys [window retain-ratio append summarize]}]
  (when-not (lock-active? records)
    (when-let [head (plan records window retain-ratio)]
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
