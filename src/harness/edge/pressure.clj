(ns harness.edge.pressure
  "How full the NEXT request will be -- the meter that decides, BEFORE a request goes
  out, whether the model's window is about to run out. Folded from a session's RECORD.

  THE FIFTH READER OF THE SAME LOG, beside replay (the conversation), stats (the
  numbers), trajectory (what the model saw) and context (how full the last call was) --
  and the question only it answers: not 'how full was the call that already went out'
  (the vendor measured that, and `harness.edge.context` reports it) but 'how full will
  the request we are about to send be'. A trigger that starts a compaction has to decide
  before a request goes out, and the vendor's newest number is one call old.

  THE NUMBER IS THE VENDOR'S PLUS AN ESTIMATED DELTA. Counting a whole prompt in
  characters is wrong: four characters is a word in English and four ideographs in
  Chinese, and the token-meter this strategy comes from admits (verbatim) that it
  'systematically underprices CJK text and JSON schemas'. So the meter does not try. It
  ANCHORS on the vendor's own `prompt_tokens` from the last call that reported one, and
  asks the estimator only for what has been added since -- `:baseline` says which of the
  two the answer rested on. The anchor is adopted only while the ENVELOPE is unchanged
  (the tool table, the route, the system message): a changed envelope is a different
  prompt, and the old total is no longer about it. It is also adopted only while the
  vendor's number is not BELOW the estimator's own reading of that same prompt -- an
  estimator that undercounts CJK is the expected case, and the anchor is adopted exactly
  then.

  IT IS A PURE FUNCTION OVER RECORDS (records->pressure) for the same reason its four
  siblings are: what can be asserted is the interesting part. The two-argument arity is
  the same fold with the request an edge has ALREADY ASSEMBLED handed in -- so a trigger
  in the middle of a run can ask about the array it is holding rather than about the one
  the record last describes -- and `log-pressure` is the file entry point.

  THE TWO PROPORTIONS ARE A PAIR. A compaction starts when the pressure crosses
  `threshold-ratio` of the window and keeps the most recent `retain-ratio` VERBATIM;
  retain must stay strictly below threshold, or a compaction would keep everything it
  was asked to shrink. Neither number is enforced here -- this namespace only reports."
  (:require [clojure.data.json :as json]
            [harness.edge.context :as context]
            [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]
            [harness.edge.stats :as stats]
            [harness.edge.trajectory :as trajectory]))

;; ------------------------------------------------------------------- proportions

(def default-ratios
  "The proportions a compaction uses when harness.edn says nothing: start at seven tenths
  of the window and keep the most recent sixteen hundredths VERBATIM. Retain must stay
  strictly below threshold, or a compaction would keep everything it was asked to shrink --
  `harness.edge.compaction/config` refuses a pair that is not."
  {:threshold-ratio 0.7 :retain-ratio 0.16})

(def threshold-ratio (:threshold-ratio default-ratios))
(def retain-ratio    (:retain-ratio default-ratios))

;; -------------------------------------------------------------------- the estimate

(def chars-per-token
  "Four characters to a token -- the estimator's whole idea, and the source of its known
  bias against CJK and JSON schema. Applied only to the DELTA; see the namespace
  docstring."
  4)

(def block-overhead
  "Tokens of framing per content block (JSON bracketing, type tags)."
  4)

(def role-overhead
  "Tokens of framing per message, for its role field."
  4)

(defn estimate-message
  "One provider message -> an estimated token count. CONTENT is a string or a vector of
  blocks; a Chinese character counts as one character either way -- and is therefore
  underpriced, the estimator's known and admitted bias, not a bug to be fixed here. The
  framing overhead is per CONTENT BLOCK, which is why a message of k blocks costs more
  than the same text in one."
  [message]
  (let [content (:content message)
        blocks  (cond (string? content)     [content]
                      (nil? content)        []
                      (sequential? content) (mapv #(if (string? %)
                                                     %
                                                     (json/write-str % :escape-unicode false))
                                                  content)
                      :else                 [(json/write-str content :escape-unicode false)])]
    (+ (reduce + 0 (map (fn [block]
                          (long (Math/ceil (/ (double (count block))
                                            (double chars-per-token)))))
                        blocks))
       (* block-overhead (max 1 (count blocks)))
       role-overhead)))

(defn estimate-messages [messages]
  (reduce + 0 (map estimate-message messages)))

(defn estimate-tools
  "The request's tool table -> an estimated token count. Each tool is one block of
  structured JSON -- short fragments, quotes and field names -- which is the other kind
  of text the estimator underprices."
  [tools]
  (reduce + 0
          (map (fn [tool]
                 (+ (long (Math/ceil (/ (double (context/size-of tool))
                                        (double chars-per-token))))
                    block-overhead))
               tools)))

;; ------------------------------------------------------------- reading the record

(defn- call-pairs
  "The model calls of ONE run, as {:start row :end row} in order. The pairing rule is the
  record's own (and `harness.edge.context`'s): the nth model/start of a run is that run's
  nth call."
  [call-rows]
  (loop [[row & more] call-rows
         pending      nil
         acc          []]
    (cond
      (nil? row)
      (cond-> acc pending (conj {:start pending :end nil}))

      (= "model/start" (replay/kind row))
      (recur more row (cond-> acc pending (conj {:start pending :end nil})))

      (= "model/end" (replay/kind row))
      (recur more nil (conj acc {:start pending :end row}))

      :else
      (recur more pending acc))))

(defn- last-reporting-call
  "The most recent call whose vendor reported a `prompt_tokens`, as
  {:run <the run map> :start <its start row> :end <its end row>} -- nil when nobody
  reported one. A call that reported nothing does not erase an earlier one: the meter
  keeps the newest MEASUREMENT."
  [runs]
  (reduce (fn [acc run]
            (reduce (fn [acc {:keys [start end] :as pair}]
                      (if (number? (get-in (replay/payload end) [:usage :prompt_tokens]))
                        {:run run :start start :end end}
                        acc))
                    acc
                    (call-pairs (:calls run))))
          nil
          runs))

(defn- latest-start
  "The newest model call's start row: what the NEXT call continues from."
  [runs]
  (last (filter #(= "model/start" (replay/kind %)) (mapcat :calls runs))))

(defn- system-row [records] (last (filter replay/system-prompt? records)))

(defn- route-of
  "The half of the provider identity that decides WHICH prompt this is. A change here is
  an envelope change; the window is deliberately not part of it (it does not change what
  was sent)."
  [start-payload]
  (select-keys start-payload [:model :base-url :reasoning-effort]))

(defn- injected-rows
  "The rows a run put in front of the model that are NOT conversation entries: the skill
  bodies, the job endings and the run's own context, which the pre-LLM step derives
  afresh each run and none of which carries an id (`harness.edge.replay/entries` drops
  them for exactly that reason). They are still in the array a call was handed -- so the
  record's own fold has to add the LAST run's back, and the same rows are in the anchor's
  price, or the injection surface would sit in the delta on every run and never in the
  baseline."
  [run]
  (vec (remove #(or (= "system" (:role %)) (some? (:id %))) (:submitted run))))

(defn- messages-in
  "RECORDS -> the messages the model was handed, AS FAR AS THE RECORD DESCRIBES THEM: the
  conversation's own entries (deduped and card-stripped the way a run hands them over),
  the newest system message in front, and the last run's own injections (which carry no id
  and so are not entries).

  THE SYSTEM MESSAGE IS ADDED BY HAND because it is not a conversation entry -- the
  client never holds the prompt (`harness.edge.replay/entries`) -- and it is the single
  largest fixed cost in every request."
  [records]
  (let [conversation (sessions/model-view (mapv :message (replay/entries records)))
        system       (some-> (system-row records) replay/payload)
        injections   (injected-rows (last (trajectory/run-segments records)))]
    (into [] (concat (when system [system]) conversation injections))))

(defn- identity-index
  "Where X sits in V, by IDENTITY. The rows `harness.edge.trajectory` hands back ARE the
  rows of the vector they came from, so this finds the call's own line without comparing
  two maps that may merely be equal."
  [v x]
  (first (keep-indexed (fn [i row] (when (identical? row x) i)) v)))

(defn- upto
  "The prefix of RECORDS that ends at X's own line -- the record as it stood at X."
  [records x]
  (if-some [i (identity-index records x)]
    (subvec records 0 (inc i))
    records))


;; -------------------------------------------------------------------- the answer

(defn records->pressure
  "RECORDS (and, in the two-argument arity, the request an edge has assembled) -> how
  full the next request is:

    {:pressureTokens 812000 :windowTokens 1000000 :percent 81
     :thresholdTokens 700000 :retainTokens 160000 :baseline \"usage\"}

  `:baseline` is `\"usage\"` when the vendor's own number anchored the answer and
  `\"estimated\"` when nothing reusable was available and the whole surface was counted
  in characters. `:windowTokens`, `:percent`, `:thresholdTokens` and `:retainTokens` are
  ABSENT when the record says nothing about a window -- a percentage needs both halves,
  and nobody is asked to divide by a number they were not given."
  ([records]
   (records->pressure records (messages-in records) default-ratios))
  ([records messages]
   (records->pressure records messages default-ratios))
  ([records messages ratios]
   (let [records    (vec records)
         runs       (trajectory/run-segments records)
         latest     (latest-start runs)
         latest-p   (some-> latest replay/payload)
         tools      (:tools latest-p)
         anchor     (last-reporting-call runs)
         start-p    (some-> anchor :start replay/payload)
         prompt     (get-in (some-> anchor :end replay/payload) [:usage :prompt_tokens])
         prefix     (when anchor (upto records (:start anchor)))
         anchor-est (when anchor
                      (+ (estimate-messages (messages-in prefix))
                         (estimate-tools (:tools start-p))))
         window     (or (:context-window latest-p)
                        (when (seq records)
                          (context/timeline-window records (last records))))
         current    (+ (estimate-messages messages) (estimate-tools tools))
         anchored?  (and anchor
                         prompt
                         (= (:tools start-p) tools)
                         (= (route-of start-p) (route-of (or latest-p start-p)))
                         (= (some-> (system-row prefix) replay/payload :content)
                            (some-> (system-row records) replay/payload :content))
                         (>= prompt anchor-est))
         total      (if anchored?
                      (max 0 (- (+ prompt current) anchor-est))
                      current)]
     (cond-> {:pressureTokens total
              :baseline       (if anchored? "usage" "estimated")}
       (and (number? window) (pos? window))
       (assoc :windowTokens    window
              :percent         (long (Math/round (* 100.0 (/ (double total) (double window)))))
              :thresholdTokens (long (Math/floor (* (double window) (:threshold-ratio ratios))))
              :retainTokens    (long (Math/floor (* (double window) (:retain-ratio ratios)))))))))

(defn log-pressure
  "A log FILE plus the request an edge has ASSEMBLED BUT NOT YET WRITTEN -> the same
  answer. The file supplies the anchor (the previous call is long on disk); MESSAGES
  supplies the surface, because the lines for the run in flight are still with the writer.
  WINDOW, when given, is the window THIS run will go out under -- the call that declares
  it has not happened yet, so the record cannot supply it and the edge hands it in.

  A file that does not exist yet (and a window nobody declared) are both normal: the answer
  is then the estimate over MESSAGES, with no window-derived numbers."
  ([f messages] (log-pressure f messages nil))
  ([f messages window]
   ;; A REPORT-ONLY METER MUST NEVER KILL A RUN. Reading the record can throw -- the file
   ;; was moved out from under us by a rebind, an old-contract line the strict reader
   ;; refuses, a permissions change -- and this call sits on the run's own path, inside the
   ;; try that turns any escape into RUN_ERROR. So a failed read degrades to the estimate.
   ;;
   ;; AND THE SNAPSHOT RACES THE WRITER: the previous call's model/end may still be queued
   ;; (the record writer appends off-thread), in which case there is simply no anchor yet
   ;; and `:baseline` says "estimated". That is the honest answer for a reading taken while
   ;; the record is still catching up.
   (let [answer (try
                 (records->pressure (if (.exists f) (stats/read-records f) []) messages)
                 (catch Throwable _ (records->pressure [] messages)))]
     (if (and (number? window) (pos? window))
       (assoc answer
              :windowTokens    window
              :percent         (long (Math/round (* 100.0 (/ (double (:pressureTokens answer))
                                                       (double window)))))
              :thresholdTokens (long (Math/floor (* (double window) threshold-ratio)))
              :retainTokens    (long (Math/floor (* (double window) retain-ratio))))
       answer))))
