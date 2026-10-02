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

  NOTHING HERE IS A SECOND COPY OF A FACT. A turn is one `turn/start` ROW (ADR 0017),
  a call is the `model/start` / `model/end` pair, and every sum comes off the lines --
  none of them is invented here, because a fact kept twice drifts (see
  docs/architecture/edge.md on the jsonl table). What the vendor reported is taken
  VERBATIM -- this is the one place that decides what a vendor key MEANS, and it does
  not rename it."
  (:require [clojure.data.json :as json]
            [harness.kernel.frames :as frames]
            [harness.edge.ag-ui :as ag]
            [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]))

;; --------------------------------------------------------------- reading lines

(defn read-records
  "FILE -> its records, for a file that may be being written RIGHT NOW: every line but the
  last is parsed strictly and the last is dropped if it is half-written.

  THE RULE LIVES IN `harness.edge.replay/read-records` -- this is its second caller (the
  composer's strip asks while a run streams) and it delegates rather than spelling the rule
  again, so the two readers cannot answer differently about the same file."
  [f]
  (replay/read-records f))

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

  PUBLIC, like `incomplete?`, because harness.edge.trajectory shows a
  call's tokens' a second way."
  [usage]
  (or (number-at usage [:total_tokens])
      (let [prompt (number-at usage [:prompt_tokens])
            completion (number-at usage [:completion_tokens])]
        (when (and prompt completion) (+ prompt completion)))))

(defn usage-fields
  "One call's usage map -> the four numbers this repo counts, each NIL when the vendor did not
  report it (the same distinction `number-at` keeps: not reported is not zero).

  PUBLIC, AND IT IS THE ONE PLACE THE VENDOR'S KEY NAMES ARE READ. `harness.edge.projection`
  writes these four into `model_calls` and `usage-of` below sums exactly them, so the global
  leaderboard the store answers and the strip a session draws cannot disagree about what
  `prompt_tokens` means -- the drift a second reading of the keys would be."
  [usage]
  {:promptTokens     (number-at usage [:prompt_tokens])
   :completionTokens (number-at usage [:completion_tokens])
   :totalTokens      (tokens-of usage)
   :cachedTokens     (number-at usage [:prompt_tokens_details :cached_tokens])})

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
  (let [usages (keep :usage calls)
        sum    (fn [k] (sum-over usages #(get (usage-fields %) k)))]
    (cond-> {}
      (sum :totalTokens)      (assoc :totalTokens (sum :totalTokens))
      (sum :promptTokens)     (assoc :promptTokens (sum :promptTokens))
      (sum :completionTokens) (assoc :completionTokens (sum :completionTokens))
      (sum :cachedTokens)     (assoc :cachedTokens (sum :cachedTokens)))))

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

(defn stats-init []
  "The numbers fold's opening state. PUBLIC, like `stats-step` and `stats-answer`, because a
  SESSION registers this fold (`install!`): the same three functions drive the birth walk,
  every later written row, and the cold read."
  {:calls [] :pending nil :turns 0 :last-frame nil})

(defn stats-step
  "One record of the fold: [LINE-INDEX ROW] -> the fold's next state. The line index is
  not needed here (calls pair by ORDER), so it is destructured and ignored.

  TWO ARITIES, ONE RULE. A session's folds are all called as `[value ctx [line-index row]]`
  (`harness.edge.replay/fold-consumers`, `harness.kernel.session/row-written!`), while the
  whole-record driver calls `[value [line-index row]]` (`harness.edge.replay/fold-records`,
  which is what `log-stats` streams with). Rather than a second spelling of this rule for one
  of them, the short arity hands the long one a nil ctx -- this fold reads everything it
  needs out of the row, which is why two drivers can share it at all.

  THREE RULES, ONE PASS:
    - A CALL BEGINS at `model/start` and ends at the next `model/end`, by order. An end
      whose payload is empty is a call that reported NOTHING (it died mid-stream) -- a
      call, with a duration and no tokens -- and a start with no end at all is still a
      call: it started, and nothing has been reported about it yet.
    - A TURN IS ONE `turn/start` ROW (ADR 0017) and everything the record writes between
      it and the next one; a run that brings no new words of a person's opens none.
    - `:last-frame` is the last EVENT row's payload, which is what `:incomplete` is read
      from -- the file's last frame, found in the same walk rather than a second one."
  ;; A NIL ACCUMULATOR STAYS NIL, and that is a statement rather than a guard: it means THIS
  ;; SESSION DOES NOT HOLD THIS FOLD (`register-fold!`: the fold's value is the walk, so a fold
  ;; registered after the session was built has none), and there is nothing to advance. Starting
  ;; fresh instead would answer from the rows written since -- a partial sum wearing the same
  ;; shape as the whole one. Nil keeps `fold-value` answering nil and sends the reader to the
  ;; record, which is the only place the whole thing can be read.
  ([acc pair] (stats-step acc nil pair))
  ([acc _ctx [_ row]]
  (when (some? acc)
  (let [k   (replay/kind row)
        acc (if (= "event" k) (assoc acc :last-frame (replay/payload row)) acc)]
    (case k
      "turn/start"  (update acc :turns inc)
      "model/start" (-> acc
                        (cond-> (:pending acc) (update :calls conj {:usage nil :ms nil}))
                        (assoc :pending row))
      "model/end"   (-> acc
                        (update :calls conj (close-call (:pending acc) row))
                        (assoc :pending nil))
      acc)))))

(defn stats-answer
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

(defn install! []
  "Register the numbers fold on BOTH of a session's seams (the birth walk and the write stream),
  so a live session can answer the composer's five cells without opening the record -- the shape
  `harness.edge.pressure/install!` established. Idempotent; returns the teardown."
  (sessions/register-fold! :stats {:init stats-init :step stats-step})
  (sessions/register-step! :stats stats-step)
  (fn teardown []
    (sessions/unregister-fold! :stats)
    (sessions/unregister-step! :stats)))
