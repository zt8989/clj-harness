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
            [harness.edge.replay :as replay]))

;; --------------------------------------------------------------- reading lines

(defn- last-line-record
  "The last line, parsed -- or nil when it will not parse.

  A LIVE LOG'S LAST LINE MAY BE HALF-WRITTEN, and this is the one reader that
  must care: the writer appends whole lines under a lock, but a reader can still
  catch a line mid-flush, and the composer's strip asks while a run is streaming.
  Dropping it is the honest answer -- the rest of the log is what has happened so
  far -- and it is NOT the same as replay's choice, which is to refuse the whole
  file because a rebuild that silently loses its tail would hand a client a
  shorter conversation than it had."
  [line]
  (try
    (json/read-str line :key-fn keyword)
    (catch Exception _ nil)))

(defn read-records
  "FILE -> its records. Every line but the last is parsed STRICTLY (harness.edge.replay/
  lines->records, which names a line it cannot parse); the last one is dropped if
  it is half-written (see last-line-record)."
  [f]
  (let [lines (replay/read-lines f)]
    (if (empty? lines)
      []
      (let [head (replay/lines->records (butlast lines))
            tail (last-line-record (last lines))]
        (cond-> (vec head) (some? tail) (conj tail))))))

;; ---------------------------------------------------------------------- turns

(defn user-ids
  "The ids of the user messages an INPUT record BRINGS, in order. Ids, not content: two
  identical user messages are two turns, and the system message changes between runs,
  so content comparison would be wrong at both ends.

  WHAT AN INPUT BRINGS IS `:added` -- the entries that entered the conversation -- and
  that is the field the edge has written since ticket 03 of
  `.scratch/sessions-live-on-the-server`. The distinction is not pedantry: a client
  that re-sends a question the conversation already holds brings NO turn, and an input
  line that named the whole conversation would count every earlier turn again on every
  run. `:messages` is read as well because it is what a client sent under the old
  contract, when that WAS what the input brought -- an old log keeps counting the way
  it always did.

  PUBLIC, like `incomplete?`, because BOTH READERS need exactly this answer: this
  namespace counts the turns, harness.edge.trajectory groups the items by them. 'What
  counts as a user message in an input' is one rule, and a second copy of it is a second
  chance to disagree about where one turn ends."
  [record]
  (->> (get-in record [:payload (if (contains? (:payload record) :added)
                                  :added
                                  :messages)])
       (filter #(= "user" (:role %)))
       (keep :id)))

(defn- turns
  "How many turns RECORDS hold.

  A TURN IS ONE USER MESSAGE and all the output it caused (CONTEXT.md), so a turn
  is counted per user message that had NOT been seen before -- and a single input
  that brings two new ones brings two turns. A RESUME sends a second `input` that
  brings the decision and no user message at all (what the parked run left in the
  conversation is already there), so it opens none: that run is the continuation of
  the turn that parked."
  [records]
  (:n (reduce (fn [{:keys [seen n]} record]
                (if (= "input" (:kind record))
                  (let [ids (set (user-ids record))]
                    {:seen (into seen ids)
                     :n    (+ n (count (remove seen ids)))})
                  {:seen seen :n n}))
              {:seen #{} :n 0}
              records)))

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
  (let [usage (:usage (:payload end-record))]
    {:usage (when (seq usage) usage)
     :ms    (when-some [started (:ts pending)] (- (:ts end-record) started))}))

(defn- calls
  "The model calls in RECORDS, in order: [{:usage <vendor map or nil> :ms <int or nil>}].

  A call BEGINS at `model/start` and ends at the next `model/end` -- a pair, by
  order. An end whose payload is empty is a call that reported NOTHING (it died
  mid-stream): it is a call, and it has a duration, and it contributes no tokens.
  A start with no end at all (the log stops here) is still a call: it started, and
  nothing about it has been reported yet."
  [records]
  (loop [[record & more] records
         pending        nil
         acc            []]
    (cond
      (nil? record)
      (if pending (conj acc {:usage nil :ms nil}) acc)

      (= "model/start" (:kind record))
      ;; A previous start with no end is a call that never closed; keep it as one.
      (recur more record (if pending (conj acc {:usage nil :ms nil}) acc))

      (= "model/end" (:kind record))
      (recur more nil (conj acc (close-call pending record)))

      :else
      (recur more pending acc))))

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
  (let [last-frame (last (filter #(= "event" (:kind %)) records))]
    (boolean (and last-frame (not (frames/terminal? (:payload last-frame)))))))

;; ----------------------------------------------------------------- the answer

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
  (let [cs      (calls records)
        usage   (usage-of cs)
        cached  (cache-hit-rate cs)
        per-sec (output-tokens-per-second cs)]
    (cond-> {:turns      (turns records)
             :incomplete (incomplete? records)}
      (seq cs) (assoc :steps (count cs)
                      :stepsWithUsage (count (filter :usage cs)))
      (seq usage) (assoc :usage usage)
      (some? cached) (assoc :cacheHitPercent cached)
      (some? per-sec) (assoc :outputTokensPerSecond per-sec))))

(defn log-stats
  "A log FILE -> records->stats of it. The file entry point, the counterpart of
  harness.edge.replay/rebuild: the caller locates the stem, this namespace never
  learns where the log came from."
  [f]
  (records->stats (read-records f)))
