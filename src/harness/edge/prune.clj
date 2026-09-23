(ns harness.edge.prune
  "Tool-result PRUNING: the FREE step that runs BEFORE a compaction.

  A giant TOOL RESULT -- a fetched page, a command that matched fifty thousand lines -- is the
  commonest way a request gets too big, and it is the one thing that can be made smaller
  WITHOUT ASKING ANYBODY: cut the middle out, keep the head and the tail, and the model still
  sees how the output began and how it ended. NO MODEL CALL IS MADE AND NOTHING IS SPENT --
  that is the whole difference from a compaction, which pays for a summary.

  THE MESSAGE IS STILL THE MESSAGE. Only the text body of a role \"tool\" message changes; its
  role, its `tool_call_id` and therefore the call-it-answers pairing are untouched, so a vendor
  cannot be shown a tool result whose call is missing. The ORIGINAL stays in the record -- the
  receipt (`receipt`) names the event it shadowed and the new event carries the elided text --
  and `harness.edge.replay/prune-messages` is the fold that makes the MODEL read the elided
  version while the person still reads the whole thing.

  IT IS DELIBERATELY NOT COMPLETE, and saying so is part of what it is: it only moves the text
  inside a tool result. It cannot shrink a long paragraph the PERSON pasted, and it cannot
  shrink the ENVELOPE (the system prompt and the tool table), which no compaction and no spill
  can shrink either. When it removes nothing, the caller simply carries on to the summary.

  THE COUNTS ARE CODE POINTS, not UTF-16 code units (ticket 06). `(count \"😀\")` is 2 in Java
  and 1 to everyone else, and a cut made at a code-unit boundary can split a surrogate pair
  into two lone halves -- mojibake that is worse than the bytes it saved. `harness.infra.text`
  is the one place that knows this, and `elide` goes through it."
  (:require [harness.infra.text :as text]))

(def keep-chars
  "How many code points of a pruned result are kept at EACH end. The head is what the command
  printed first and the tail is what it ended with; the middle is where the bulk usually is."
  2000)

(def threshold-chars
  "A tool result whose body is at most this many code points is left alone -- there is nothing
  worth spending a marker on, and pruning a result the model may need in full is a cost with no
  benefit. Only a body LONGER than this is elided."
  6000)


(defn elide
  "STRING -> `{:content :before :after :removed}`, or nil when there is nothing worth cutting.

  The middle is replaced by a MARKER that says how many characters went, followed by the same
  number of kept characters at each end. `:before` and `:after` are CODE-POINT counts of the
  whole body, so the receipt's arithmetic is the caller's and this is the only thing that
  knows what a code point is here."
  [s]
  (let [n (text/code-point-count s)]
    (when (> n threshold-chars)
      (let [removed (- n (* 2 keep-chars))
            middle  (str "\n…[pruned " removed " characters]…\n")
            content (str (text/slice s 0 keep-chars)
                         middle
                         (text/slice s (- n keep-chars) n))]
        {:content content
         :before  n
         :after   (text/code-point-count content)
         :removed removed}))))

(defn prune-plan
  "ENTRIES (`harness.edge.replay/entries`, each `{:seq :message}`) + DONE-IDS -> the tool results
  worth eliding, in order, each as

    {:toolCallId id :shadowed [seq] :content text :before n :after n :removed n}

  PURE AND DETERMINISTIC: the same entries and the same DONE-IDS always answer the same plan (no
  model call, no clock, no state), which is what makes it safe to run at every entry point. A
  tool result already smaller than `threshold-chars` is not in the plan; one whose body is not a
  string is left alone.

  A RESULT WHOSE CALL IS IN DONE-IDS IS SKIPPED, and this is what keeps pruning IDEMPOTENT. The
  record keeps the ORIGINAL tool message and a separate receipt, so `entries` still holds the
  giant text on every later read: a caller that did not exclude what is already done would
  elide the same result -- and append the same receipt -- once per run, for ever.
  `harness.edge.replay/prune-facts` is exactly those call ids."
  ([entries] (prune-plan entries #{}))
  ([entries done-ids]
   (into []
         (keep (fn [{:keys [seq message]}]
                 (when (= "tool" (:role message))
                   (let [id (or (:tool_call_id message) (:toolCallId message))
                         c  (:content message)]
                     (when (and id (string? c) (not (contains? done-ids id)))
                       (when-some [{:keys [content before after removed]} (elide c)]
                         {:toolCallId id
                          :shadowed   [seq]
                          :content    content
                          :before     before
                          :after      after
                          :removed    removed}))))))
         entries)))

(defn reduction
  "A plan (or a list of applied facts) -> `{:pruned n :removed n}` -- how many results were
  elided and how many code points that took out, in total. The one number a caller needs to
  say what pruning bought."
  [plan]
  {:pruned  (count plan)
   :removed (reduce + 0 (map :removed plan))})

(defn receipt
  "A plan entry -> the `context/pruned` fact's payload: what the fold reads back. `:shadowed`
  names the event the elided text replaced, `:toolCallId` is the call the two share, and
  `:before`/`:after` are the CODE-POINT counts either side of the cut."
  [{:keys [toolCallId shadowed content before after removed]}]
  {:toolCallId toolCallId
   :shadowed   (vec shadowed)
   :content    content
   :before     before
   :after      after
   :removed    removed})
