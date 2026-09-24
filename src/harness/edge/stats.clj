(ns harness.edge.stats
  "A session's numbers, folded from its RECORD: how many turns, how many model
  calls, how fast it answered, how many tokens it burned, how much of that was
  the vendor's prefix cache.

  IT IS A READER OF THE LOG, sitting beside harness.edge.replay (which folds the
  same records back into a conversation) and harness.kernel.frames (which folds
  frames into messages). What it reads is the AUDIT half of the log -- `input`
  lines and the `model/start` / `model/end` pair -- not the conversation; the
  conversation's reader is `replay`, and neither reads the other's lines.

  THE FOLD IS A PURE FUNCTION OVER RECORDS (records->stats), because that is what
  makes it assertable: a test hands it a handful of hand-written records and pins
  the arithmetic, the denominators, and the absences. Walking a real log is a
  second, thinner function (log-stats) that does nothing but the file I/O and hand
  the records over.

  THE DIRECTORY IS THE CALLER'S, exactly as in harness.edge.replay: this namespace
  stays a pure reader and never learns where the process keeps its home. The route
  locates the stem and hands it a file.

  NOTHING HERE IS A SECOND COPY OF A FACT. Turn boundaries, call ordering, the
  durations and every sum are DERIVED from the lines; none of them is written into
  the record, because a fact kept twice drifts (see docs/architecture/edge.md on
  the jsonl table). What the vendor reported is taken VERBATIM -- this is the one
  place that decides what a vendor key MEANS, and it does not rename it."
  (:require [clojure.data.json :as json]
            [harness.kernel.frames :as frames]
            [harness.edge.ag-ui :as ag]
            [harness.edge.replay :as replay]))

;; --------------------------------------------------------------- reading lines

(defn read-records
  "FILE -> its records, for a file that may be being written RIGHT NOW: every line but the
  last is parsed strictly and the last is dropped if it is half-written.

  THE RULE LIVES IN `harness.edge.replay/read-records` -- this is its second caller (the
  composer's strip asks while a run streams) and it delegates rather than spelling the rule
  again, so the two readers cannot answer differently about the same file."
  [f]
  (replay/read-records f))

;; ---------------------------------------------------------------------- turns

(defn user-ids
  "The ids of the user message ONE RECORD brings, in order -- for a `message` row, the one
  message it carries. Ids, not content: two identical user messages are two turns, and the
  system message changes between runs, so content comparison would be wrong at both ends.

  WHAT A ROW BRINGS IS ITSELF, and the row's `source` says whether it was the person's
  (`.scratch/jsonl-two-kinds` 票 02: every message the model was handed is its own row, and
  the envelope says who put it in the array). ONLY `client` COUNTS HERE, because a turn is
  something a PERSON said: the conversation's birth entries (the session's context and its
  opening blocks) ride as ordinary user messages too -- `source` = `injection` / `opening`,
  which `ag/injected?` names -- and counting them would make a session's first run one turn
  plus one per instruction file. The `input` row this used to read is gone; so is the
  question 'was this the whole conversation or just what it added', which is what made an
  old log count differently from a new one.

  PUBLIC, like `incomplete?`, because BOTH READERS need exactly this answer: this
  namespace counts the turns, harness.edge.trajectory groups the items by them. 'What
  counts as a user message a run brought' is one rule, and a second copy of it is a second
  chance to disagree about where one turn ends."
  [record]
  (let [message (replay/payload record)]
    (when (and (= "client" (:source record))
               (= "user" (:role message)))
      ;; THE ID IS THE ENVELOPE'S when the record has one -- the payload is the verbatim
      ;; provider message, and a provider message has no such field (`ag/inbound` strips it
      ;; on the way in, `.scratch/jsonl-two-kinds` 票 02 puts it back on the way out).
      ;; `keep :id` WOULD ANSWER NIL HERE: the envelope's id IS the id, so there is no
      ;; second lookup to make -- the shape `keep` is for.
      (let [id (or (:id record) (:id message))]
        (when (some? id) [id])))))


;; --------------------------------------------------------------- model calls

(defn- close-call
  "The pending call, closed by an END record: the VENDOR'S USAGE out of the payload
  (the payload is the whole telemetry -- usage, finish reason, echoed model -- and
  the fold's questions are about the tokens, so that is what a call carries), and
  how long it took when its start is known. ORDER is the only thing that pairs them
  (the record carries no call id on purpose) -- so this is called for the FIRST end
  after a start, and a second end without a start of its own is its own call with no
  duration: it happened, its start line is what is missing.

  NO USAGE BECOMES NIL, not an empty map: the call reported NOTHING (it died
  mid-stream), and every question below is asked with `(seq usage)` -- an empty map
  would answer 'yes, this call reported usage'."
  [pending end-record]
  (let [usage (:usage (replay/payload end-record))]
    {:usage (when (seq usage) usage)
     :ms    (when-some [started (:ts pending)] (- (:ts end-record) started))}))

(defn- number-at
  "A number out of a vendor's usage map, or nil -- nil for a key that is absent, for
  a value that is not a number, and for a null. The distinction this protects is the
  whole point of the fold: NOT REPORTED is not zero."
  [usage ks]
  (let [v (get-in usage ks)]
    (when (number? v) v)))

(defn tokens-of
  "One call's total: the vendor's own `total_tokens`, else prompt + completion --
  and only for a call that reported BOTH halves. Half a sum is not a total, and
  presenting one as a total is the same mistake as calling an absent count zero.

  PUBLIC, like `incomplete?` and `user-ids`, because harness.edge.trajectory shows a
  per-call total on the trajectory's rows and must not spell 'what counts as this
  call's tokens' a second way."
  [usage]
  (or (number-at usage [:total_tokens])
      (let [prompt (number-at usage [:prompt_tokens])
            completion (number-at usage [:completion_tokens])]
        (when (and prompt completion) (+ prompt completion)))))

(defn- sum-over
  "The sum of KEY-OF over CALLS, or nil when no call reported it. `nil`, not 0: a
  key nobody reported must be ABSENT from the answer."
  [calls key-of]
  (let [vs (keep key-of calls)]
    (when (seq vs) (reduce + vs))))

(defn- usage-of
  "The vendor's report, summed over the calls that made one.

  EACH KEY STANDS ON ITS OWN -- a sum covers the calls that reported THAT key, and a
  key nobody reported is missing from the answer. `totalTokens` is the vendor's own
  total when it gave one, and prompt + completion only for a call that gave both.
  A call that reported nothing (a call that died) contributes to none of them.

  The cached/prompt pair is summed here for display, and the RATE is taken separately
  (cache-hit-rate) from the calls that reported BOTH halves, so a ratio can never
  put a numerator from one call over a denominator from another."
  [calls]
  (let [usages (keep :usage calls)]
    (cond-> {}
      (sum-over usages tokens-of)                                   (assoc :totalTokens (sum-over usages tokens-of))
      (sum-over usages #(number-at % [:prompt_tokens]))            (assoc :promptTokens (sum-over usages #(number-at % [:prompt_tokens])))
      (sum-over usages #(number-at % [:completion_tokens]))        (assoc :completionTokens (sum-over usages #(number-at % [:completion_tokens])))
      (sum-over usages #(number-at % [:prompt_tokens_details :cached_tokens]))
      (assoc :cachedTokens (sum-over usages #(number-at % [:prompt_tokens_details :cached_tokens]))))))

(defn- cache-hit-rate
  "The share of the prompt the vendor served from its own prefix cache, over the
  calls that reported BOTH halves of the ratio -- a call that reported one and not
  the other is left out rather than paired with a number from another call. The
  answer is a percentage, or nil when no call reported the pair."
  [calls]
  (let [pairs (keep (fn [{:keys [usage]}]
                      (when-some [cached (number-at usage [:prompt_tokens_details :cached_tokens])]
                        (when-some [prompt (number-at usage [:prompt_tokens])]
                          [cached prompt])))
                    calls)
        total (reduce (fn [[c p] [c' p']] [(+ c c') (+ p p')]) [0 0] pairs)]
    (when (and (seq pairs) (pos? (second total)))
      (Math/round (* 100.0 (/ (double (first total)) (double (second total))))))))

(defn- output-tokens-per-second
  "How fast this session's answers came out: completion tokens over the seconds the
  calls that produced them took.

  ONLY THE CALLS THAT REPORTED BOTH are in it -- an output count without a duration
  (or the other way round) would put a number in the numerator and nothing in the
  denominator, and the seconds are the `:ts` difference between the pair, never a
  guess from the gap between two other lines. Time to first token is INSIDE this
  number: it is what a caller observes, not a pure generation rate."
  [calls]
  (let [usable (keep (fn [{:keys [usage ms]}]
                       (when-some [completion (number-at usage [:completion_tokens])]
                         (when (and (some? ms) (pos? ms))
                           [completion ms])))
                     calls)
        [tokens ms] (reduce (fn [[t m] [t' m']] [(+ t t') (+ m m')]) [0 0] usable)]
    (when (and (seq usable) (pos? ms))
      (Math/round (* 1000.0 (/ (double tokens) (double ms)))))))

(defn incomplete?
  "TRUE when the log's last frame is not a terminal one: that run is still going,
  or died without closing. Reading it anyway is the point -- a session is most
  likely to be asked about while it is running -- and this is the flag that says
  the last turn is not over yet.

  PUBLIC BECAUSE BOTH READERS ASK IT: this namespace reports it as a number's
  caveat, harness.edge.trajectory as a turn's. One rule, one spelling -- the
  alternative is two readers that can disagree about whether a log is finished."
  [records]
  (let [last-frame (last (filter #(= "event" (replay/kind %)) records))]
    (boolean (and last-frame (not (frames/terminal? (replay/payload last-frame)))))))

;; ----------------------------------------------------------------- the answer

;; ------------------------------------------------------ the stats fold, one pass
;;
;; TICKET 06: `records->stats` used to walk its records THREE times -- once for the calls,
;; once for the turns, once for the last frame -- and a lazy seq walked three times pins
;; every row it realized, which is the whole file. All three are accumulated in ONE pass,
;; so `harness.edge.replay/fold-records` can drive this and hold only the answer.

(defn- stats-init [] {:calls [] :pending nil :seen #{} :turns 0 :last-frame nil})

(defn- stats-step
  "One record of the fold: [LINE-INDEX ROW] -> the fold's next state. The line index is
  not needed here (calls pair by ORDER), so it is destructured and ignored.
 
  THREE RULES, ONE PASS:
    - A CALL BEGINS at `model/start` and ends at the next `model/end`, by order. An end
      whose payload is empty is a call that reported NOTHING (it died mid-stream) -- a
      call, with a duration and no tokens -- and a start with no end at all is still a
      call: it started, and nothing has been reported about it yet.
    - A TURN IS ONE USER MESSAGE and the output it caused (CONTEXT.md), counted per user
      message NOT seen before; a resume brings none and opens none.
    - `:last-frame` is the last EVENT row's payload, which is what `:incomplete` is read
      from -- the file's last frame, found in the same walk rather than a second one."
  [acc [_ row]]
  (let [k   (replay/kind row)
        ids (set (user-ids row))
        acc (-> acc
                (update :seen into ids)
                (update :turns + (count (remove (:seen acc) ids))))
        acc (if (= "event" k) (assoc acc :last-frame (replay/payload row)) acc)]
    (case k
      "model/start" (-> acc
                        (cond-> (:pending acc) (update :calls conj {:usage nil :ms nil}))
                        (assoc :pending row))
      "model/end"   (-> acc
                        (update :calls conj (close-call (:pending acc) row))
                        (assoc :pending nil))
      acc)))

(defn- stats-answer
  "The fold's answer from its accumulated state (see `stats-step` for what each part is)."
  [{:keys [calls pending turns last-frame]}]
  (let [cs      (cond-> calls pending (conj {:usage nil :ms nil}))
        usage   (usage-of cs)
        cached  (cache-hit-rate cs)
        per-sec (output-tokens-per-second cs)]
    (cond-> {:turns      turns
             :incomplete (boolean (and last-frame (not (frames/terminal? last-frame))))}
      (seq cs) (assoc :steps (count cs)
                      :stepsWithUsage (count (filter :usage cs)))
      (seq usage) (assoc :usage usage)
      (some? cached) (assoc :cacheHitPercent cached)
      (some? per-sec) (assoc :outputTokensPerSecond per-sec))))
(defn records->stats
  "RECORDS -> the session's numbers. See the namespace docstring for what this is
  and read-records for what a record is; the payload this builds is the one
  documented on the route (GET /api/threads/<stem>/stats).

  ABSENCES ARE THE ANSWER WHERE A NUMBER WOULD BE A LIE. `steps` is missing when
  the log holds no model call at all -- an old log predates the lines, and that is
  'this record cannot tell', not 'no call ever happened'. `usage` is missing when
  no call reported any, and a key inside it is missing when no call reported THAT
  key. `stepsWithUsage` is carried so a reader can see how much of the session the
  sums actually cover."
  [records]
  (stats-answer (reduce stats-step (stats-init) (map-indexed vector records))))

(defn log-stats
  "A log FILE -> records->stats of it. The file entry point, the counterpart of
  harness.edge.replay/rebuild: the caller locates the stem, this namespace never
  learns where the log came from."
  [f]
  ;; FOLDED FROM A STREAM (ticket 06): the same answer `records->stats` gives, and the
  ;; file's rows are never all held at once.
  (stats-answer (replay/fold-records f (stats-init) stats-step)))
