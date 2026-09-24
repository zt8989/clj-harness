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
            [harness.edge.trajectory :as trajectory]
            [harness.kernel.tools :as tools]))

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
  "A call's tool table -> an estimated token count, from THE SIGNATURE a `model/start`
  line keeps rather than from the table (ticket 04). Each tool is one block of
  structured JSON -- short fragments, quotes and field names -- which is the other kind
  of text the estimator underprices, so the framing is charged per tool (`:tools-count`)
  and the table's bytes, when the record has them (`:tools-bytes`), are charged at the
  same four-characters-to-a-token rule as everything else.
 
  AN OLD RECORD STILL CARRIES THE TABLE, and its size is the best it has to offer."
  [start-payload]
  (let [n     (or (:tools-count start-payload) (count (:tools start-payload)))
        bytes (or (:tools-bytes start-payload)
                  (when (seq (:tools start-payload))
                    (context/size-of (:tools start-payload))))]
    (+ (* block-overhead n)
       (if (number? bytes)
         (long (Math/ceil (/ (double bytes) (double chars-per-token))))
         0))))

(defn- tools-names-hash-of
  "PAYLOAD -> the NAME set of the tool table the call went out with, however the record
  spells it: `:tools-names-hash` on a record written since ticket 04, or a hash taken
  here over the `:tools` table an older record kept. nil when the call carried none.
 
  THE COMPARISON IS THE NAME SET, NOT THE BYTES: re-describing a tool does not move it,
  an added or removed one does (owner's rule, 2026-09-24)."
  [payload]
  (or (:tools-names-hash payload)
      (when (seq (:tools payload)) (tools/names-hash (:tools payload)))))

(defn- hooks-signature
  "The identity of the hook set a run's system message was assembled from, however the
  record spells it: `:hooks-names-hash` on the row written since ticket 04, or -- for an
  older record, which kept only the text -- the text itself. nil when there is no system
  row to compare."
  [row]
  (if-some [h (:hooks-names-hash row)]
    h
    (some-> row replay/payload :content)))

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


(defn- own-calls
  "ONE RUN'S OWN model calls, in order -- the `model/start`/`model/end` rows that carry the
  run's OWN id.

  A CALL THE HARNESS WROTE FOR ITSELF ALSO LANDS IN A RUN'S `:calls`. A compaction's
  summarizer call is logged with no run id, and `run-segments` attaches an event to whichever
  run is still open (a fact opens no run): the newest such row used to become `latest-start`,
  and its empty tool table flipped `:baseline` from \"usage\" to \"estimated\", moving the
  anchor off the vendor's own number (2026-09-24, thread `bbcd4ae4-…`). Only the RUN's own
  calls are the calls 'the next one continues from'."
  [run]
  (filterv #(and (some? (:runId %)) (= (:run-id run) (:runId %))) (:calls run)))
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
                    (call-pairs (own-calls run))))
          nil
          runs))

(defn- latest-start
  "The newest RUN call's start row: what the NEXT call continues from. A call the harness
  wrote for itself (a compaction's summarizer) is not one -- see `own-calls`."
  [runs]
  (last (filter #(= "model/start" (replay/kind %)) (mapcat own-calls runs))))

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

(defn- messages-of
  "ENTRY-MESSAGES (the conversation's own, RAW -- cards and all) + a SYSTEM payload +
  INJECTIONS -> the array the model was handed, in the array's own order: the system message
  first, then the conversation (with its cards taken off by `sessions/model-view`), then the
  run's own injections.
 
  IT IS THE ONE PLACE THAT SPELLS THAT ORDER, so the record's fold and the cached METER
  (ticket 03) cannot disagree about what a call was handed."
  [entry-messages system injections]
  (into [] (concat (when system [system])
                   (sessions/model-view (vec entry-messages))
                   injections)))

(defn- messages-in
  "RECORDS -> the messages the model was handed, AS FAR AS THE RECORD DESCRIBES THEM: the
  conversation's own entries (deduped and card-stripped the way a run hands them over),
  the newest system message in front, and the last run's own injections (which carry no id
  and so are not entries).
 
  THE SYSTEM MESSAGE IS ADDED BY HAND because it is not a conversation entry -- the
  client never holds the prompt (`harness.edge.replay/entries`) -- and it is the single
  largest fixed cost in every request."
  [records]
  (messages-of (mapv :message (replay/entries records))
               (some-> (system-row records) replay/payload)
               (injected-rows (last (trajectory/run-segments records)))))

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

(defn meter-of-records
  "RECORDS -> the METER BAND `state->pressure` eats, and nothing else (ticket 03): the newest
  TRUE run call's `model/start` payload, the newest system row's signature, and the ANCHOR --
  the last call whose vendor reported a `prompt_tokens` -- together with the conversation AS IT
  STOOD at that call (its `:messages`, the system message in force, and that run's own
  injections).
 
  IT IS THE SAME FOLD `records->pressure` HAS ALWAYS RUN, stopping at the facts a later
  reading needs instead of finishing the arithmetic: `records->pressure` is `meter-of-records`
  + `state->pressure`, so the offline reading and the cached one CANNOT disagree. The live
  side keeps an equivalent band as rows are written (`meter-step`), which is what lets a run
  start answer without reading the record at all.
 
  THE EXTRA KEYS (`:system` / `:run` / `:injections`) are what a LIVE band needs to carry on
  from here; `state->pressure` ignores them."
  [records]
  (let [records  (vec records)
        runs     (trajectory/run-segments records)
        latest   (latest-start runs)
        last-run (last runs)
        anchor   (last-reporting-call runs)
        start-p  (some-> anchor :start replay/payload)
        prefix   (when anchor (upto records (:start anchor)))
        system   (system-row records)
        a-system (when anchor (system-row prefix))
        a-run    (:run anchor)]
    {:latest-start    (some-> latest replay/payload)
     :latest-sig      (hooks-signature system)
     :system          (some-> system replay/payload)
     :run             (:run-id last-run)
     :injections      (vec (injected-rows last-run))
     :anchor          (when (and anchor start-p)
                        {:start      start-p
                         :prompt     (get-in (some-> anchor :end replay/payload) [:usage :prompt_tokens])
                         :messages   (mapv :message (replay/entries prefix))
                         :system     (some-> a-system replay/payload)
                         :sig        (hooks-signature a-system)
                         :injections (vec (when a-run (injected-rows a-run)))})
     :timeline-window (when (seq records)
                        (context/timeline-window records (last records)))}))

(defn state->pressure
  "METER + the request an edge has assembled + RATIOS -> how full the next request is: the
  same answer, FIELD FOR FIELD, that `records->pressure` gives for the record the meter was
  taken from. See `records->pressure` for what the fields mean and when they are absent.
 
  THE METER IS THE WHOLE INPUT, on purpose: an anchor is an assertion about the prefix a
  call rested on, so it carries the conversation it was priced against (`:messages`), the
  system message in force, and that run's own injections. NOTHING HERE REACHES FOR A RECORD
  OR A FILE -- a run start hands in MESSAGES and this answers (ticket 03)."
  [meter messages ratios]
  (let [{:keys [latest-start latest-sig anchor timeline-window]} meter
        start-p    (:start anchor)
        prompt     (:prompt anchor)
        anchor-est (when anchor
                     (+ (estimate-messages
                         (messages-of (:messages anchor) (:system anchor) (:injections anchor)))
                        (estimate-tools start-p)))
        window     (or (:context-window latest-start) timeline-window)
        current    (+ (estimate-messages messages) (estimate-tools latest-start))
        anchored?  (and anchor
                        prompt
                        (= (tools-names-hash-of start-p) (tools-names-hash-of latest-start))
                        (= (route-of start-p) (route-of (or latest-start start-p)))
                        (= (:sig anchor) latest-sig)
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
             :retainTokens    (long (Math/floor (* (double window) (:retain-ratio ratios))))))))

(defn records->pressure
  "RECORDS (and, in the two-argument arity, the request an edge has assembled) -> how
  full the next request is:
 
    {:pressureTokens 812000 :windowTokens 1000000 :percent 81
     :thresholdTokens 700000 :retainTokens 160000 :baseline \"usage\"}
 
  `:baseline` is `\"usage\"` when the vendor's own number anchored the answer and
  `\"estimated\"` when nothing reusable was available and the whole surface was counted
  in characters. `:windowTokens`, `:percent`, `:thresholdTokens` and `:retainTokens` are
  ABSENT when the record says nothing about a window -- a percentage needs both halves,
  and nobody is asked to divide by a number they were not given.
 
  IT IS `meter-of-records` + `state->pressure` (ticket 03): the offline reading and the
  live one run the SAME arithmetic over the SAME facts, so they cannot drift apart. Use this
  when the records are in hand (a test, an offline tool); a run start uses the live band
  (`log-pressure` / `band-pressure`)."
  ([records]
   (records->pressure records (messages-in records) default-ratios))
  ([records messages]
   (records->pressure records messages default-ratios))
  ([records messages ratios]
   (state->pressure (meter-of-records records) messages ratios)))

;; ----------------------------------------------------------- the live meter band (ticket 03)
;;
;; `records->pressure` folds a WHOLE RECORD, and a run start used to pay that fold two or
;; three times over (the auto-compaction check, the `context/pressure` row, the window). The
;; band below is the SAME FACTS KEPT INCREMENTALLY: `harness.edge.http/log!` -- the one writer
;; path -- hands every row here as it is written, and the band is updated IN PLACE. A run start
;; then answers from the band (O(1)) instead of reading the file.
;;
;; A THREAD'S BAND IS SEEDED ONCE PER PROCESS (`seed-band!`), by folding the record exactly
;; the way `meter-of-records` does -- and `meter-row!` leaves a thread it has never seen
;; alone, so rows written before that seed are not lost: the seed's fold has them.

(defonce ^:private bands
  ;; thread-id -> the band `state->pressure` eats, kept current by `meter-row!`.
  (atom {}))

(defn- empty-band []
  {:latest-start nil :latest-sig nil :system nil :run nil :injections []
   :anchor nil :timeline-window nil})

(defn meter-row!
  "ONE jsonl ROW, AS IT IS WRITTEN -> THREAD-ID's band, updated in place (ticket 03). Called
  from `harness.edge.http/log!`, the one writer path, so the band never re-reads the record.
 
  KINDS IT CARES ABOUT, and nothing else:
    `model/start` with a run id      -> `:latest-start` (the newest TRUE run call). A
                                        compaction's own start carries NO run id and is left
                                        alone -- that is the bug ticket 02 fixed, and it is
                                        the same rule `own-calls` applies to a record.
    `model/end` with a run id and a `prompt_tokens` -> the ANCHOR, with the conversation, the
                                        system message and this run's injections snapshotted
                                        as they stood at that call.
    `message` whose source is `system-prompt` -> `:system` and `:latest-sig`.
    `message` with no id, not a system message, in a run -> this run's injections.
 
  A THREAD WITH NO BAND IS LEFT ALONE: the fold that installs one (`seed-band!`) reads the
  whole record, so it has every row written so far."
  [thread-id run-id kind payload extra]
  (let [id (str thread-id)]
    (when (contains? @bands id)
      (swap! bands update id
             (fn [b]
               (let [;; a new run clears the injection list; a run's injections are written at
                     ;; its birth, before its first call.
                     b   (if (and (some? run-id) (not= (str run-id) (:run b)))
                           (assoc b :run (str run-id) :injections [])
                           b)
                     own? (some? run-id)]
                 (case kind
                   "model/start" (if own? (assoc b :latest-start payload) b)
                   "model/end"   (if (and own? (number? (get-in payload [:usage :prompt_tokens])))
                                   (assoc b :anchor {:start      (:latest-start b)
                                                     :prompt     (get-in payload [:usage :prompt_tokens])
                                                     :messages   (sessions/raw-messages thread-id)
                                                     :system     (:system b)
                                                     :sig        (:latest-sig b)
                                                     :injections (vec (:injections b))})
                                   b)
                   "message"     (if (= "system-prompt" (:source extra))
                                   (assoc b :system payload
                                            :latest-sig (or (:hooks-names-hash extra)
                                                            (:content payload)))
                                   ;; A RUN'S OWN INJECTION, and only that: `returned-source`
                                   ;; names what the pre-LLM step derived `skill` / `job` /
                                   ;; `injection`, while what the model RETURNED is `model` /
                                   ;; `tool` -- so this is `injected-rows`' rule (no id, not the
                                   ;; system message) spelled by source, and a returned
                                   ;; assistant message can never be mistaken for one.
                                   (if (and own?
                                            (nil? (:id extra))
                                            (contains? #{"skill" "job" "injection"}
                                                        (:source extra)))
                                     (update b :injections conj payload)
                                     b))
                   b)))))))

(defn seed-band!
  "FOLD RECORDS INTO THREAD-ID's band and install it: the ONE read a process pays per thread,
  after which `meter-row!` keeps it current."
  [thread-id records]
  (let [b (meter-of-records records)]
    (swap! bands assoc (str thread-id) b)
    b))

(defn- band-for
  "THREAD-ID's band: the live one, or one seeded from FILE (the first time this process is
  asked about the thread)."
  [thread-id ^java.io.File f]
  (or (get @bands (str thread-id))
      (seed-band! thread-id (if (and f (.exists f)) (stats/read-records f) []))))

(defn band-pressure
  "THREAD-ID's band + MESSAGES + RATIOS -> the same answer `records->pressure` gives, WITHOUT
  reading the record (beyond the one seed a process pays per thread). The cheap check a
  compaction trigger starts from."
  [thread-id f messages ratios]
  (state->pressure (band-for thread-id f) messages ratios))

(defn log-pressure
  "THREAD-ID's log FILE plus the request an edge has ASSEMBLED BUT NOT YET WRITTEN -> the same
  answer. MESSAGES supplies the surface, because the lines for the run in flight are still
  with the writer. WINDOW, when given, is the window THIS run will go out under -- the call
  that declares it has not happened yet, so the record cannot supply it and the edge hands it
  in.
 
  IT NO LONGER READS THE RECORD (ticket 03): the band is kept as rows are written, and only a
  thread this process has never seen is seeded with one read. WINDOW is the only thing the
  cached answer cannot know.
 
  A file that does not exist yet (and a window nobody declared) are both normal: the answer
  is then the estimate over MESSAGES, with no window-derived numbers."
  ([thread-id f messages] (log-pressure thread-id f messages nil))
  ([thread-id f messages window]
   ;; A REPORT-ONLY METER MUST NEVER KILL A RUN. The band can be wrong or stale -- the file
   ;; was moved out from under us by a rebind, the writer is behind, a band was never seeded
   ;; -- and this call sits on the run's own path, inside the try that turns any escape into
   ;; RUN_ERROR. So anything at all degrades to the estimate over MESSAGES.
   (let [answer (try
                  (band-pressure thread-id f messages default-ratios)
                  (catch Throwable _ (state->pressure (empty-band) messages default-ratios)))]
     (if (and (number? window) (pos? window))
       (assoc answer
              :windowTokens    window
              :percent         (long (Math/round (* 100.0 (/ (double (:pressureTokens answer))
                                                             (double window)))))
              :thresholdTokens (long (Math/floor (* (double window) threshold-ratio)))
              :retainTokens    (long (Math/floor (* (double window) retain-ratio))))
       answer))))
