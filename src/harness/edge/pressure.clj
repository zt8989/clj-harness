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
            ;; NOTE: this namespace no longer reads a record at all -- tickets 03 / 04 moved
            ;; the band onto the session, so `harness.edge.stats` (the file reader) is not
            ;; required here any more.
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

(defn- text-blocks
  "TEXT -> the blocks this estimator charges for: a string is one block, a vector is its parts
  (each serialized when it is not already text), nil is none. ONE READER, so a message's content
  and its reasoning cannot be spelled two different ways."
  [text]
  (cond (string? text)     [text]
        (nil? text)        []
        (sequential? text) (mapv #(if (string? %)
                                   %
                                   (json/write-str % :escape-unicode false))
                                text)
        :else              [(json/write-str text :escape-unicode false)]))

(defn estimate-message
  "One provider message -> an estimated token count. CONTENT is a string or a vector of
  blocks; a Chinese character counts as one character either way -- and is therefore
  underpriced, the estimator's known and admitted bias, not a bug to be fixed here. The
  framing overhead is per CONTENT BLOCK, which is why a message of k blocks costs more
  than the same text in one.

  REASONING IS PRICED, WHEREVER IT IS SPELLED. A thinking-mode vendor bills the thought on
  the assistant message it arrives on, and the SAME text reaches this function two ways: as
  `reasoning_content` on that assistant (the PROVIDER shape `harness.edge.ag-ui/absorbed`
  folds it into), or as a `role \"reasoning\"` message of its own (the AG-UI shape the
  RECORD folds back out of the run's own row). Reading only `:content` charged one and not
  the other -- the same conversation came out 330,648 tokens in one shape and 689,229 in the
  other (measured on a real 1.4M-character log) -- and the meter SUBTRACTS one of those from
  the other (`state->pressure`), so the shape it happened to be handed decided the answer.
  Which shape it is handed must not matter."
  [message]
  (let [blocks (into (text-blocks (:content message))
                     (text-blocks (:reasoning_content message)))]
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

(defn- delivery-of
  "The delivery mode a run's system row was written under, off the ENVELOPE
  (`:instruction-updates`, since `.scratch/instruction-updates` ticket 02), or nil
  for a row that predates it. NIL IS NOT A MODE: `anchored?` reads it as :replace,
  the conservative default this feature chose for an endpoint whose capability is
  unknown (a wrong `developer` message would keep a run from starting at all)."
  [row]
  (:instruction-updates row))

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

(defn- empty-band []
  {:latest-start nil :latest-sig nil :latest-mode nil :system nil :run nil :injections []
   :anchor nil :timeline-window nil})

(defn- band-step
  "ONE ROW of a session's walk -> the meter's band, advanced. CTX is `{:messages (fn [] ..)}`:
  the conversation AS THE WALK HAS IT SO FAR, as entry messages -- which is what an anchor
  snapshots, because an anchor is a claim about the PREFIX a call rested on.

  THE ONE RULE, run on both of a session's seams (ticket 03's read stream and ticket 04's
  write stream) and by `meter-of-records` offline, so the live band and the record cannot
  disagree. Kinds it cares about, and nothing else:

    `model/start` with a run id -> `:latest-start` (the newest TRUE run call). A compaction's
        own start carries NO run id and is left alone.
    `model/end` with a run id and a `prompt_tokens` -> the ANCHOR, with the conversation, the
        system message and this run's injections snapshotted as they stood at that call.
    `message` whose source is `system-prompt` -> `:system`, `:latest-sig` and
        `:latest-mode` (the delivery mode that run was served with).
    a run's own injection (`skill` / `job` / `injection` / `instruction-update`,
    a run's own injection (`skill` / `job` / `injection`, no id) -> `:injections`.
    `provider/init` / `provider/changed` -> the window in force."
  [band ctx [i row]]
  (let [run-id  (:runId row)
        k       (replay/kind row)
        payload (replay/payload row)
        extra   (dissoc row :type :payload :ts)
        own?    (some? run-id)
        band    (if (and own? (not= (str run-id) (:run band)))
                  (assoc band :run (str run-id) :injections [])
                  band)]
    (case k
      "model/start" (if own? (assoc band :latest-start payload) band)
      "model/end"   (if (and own? (number? (get-in payload [:usage :prompt_tokens])))
                      (assoc band :anchor
                             {:start      (:latest-start band)
                              :prompt     (get-in payload [:usage :prompt_tokens])
                              :messages   ((:messages ctx))
                              :system     (:system band)
                              :sig        (:latest-sig band)
                              :mode       (:latest-mode band)
                              :injections (vec (:injections band))})
                      band)
      "message"     (if (= "system-prompt" (:source extra))
                      (assoc band :system payload
                             :latest-sig (hooks-signature row)
                             :latest-mode (delivery-of row))
                      (if (and own? (nil? (:id extra))
                               (contains? #{"skill" "job" "injection"
                                             ;; a developer message an instruction update
                                             ;; rode the tail in -- it carries no id and is in the
                                             ;; array the call was handed, like the rest of these
                                             ;; (`.scratch/instruction-updates`).
                                             "instruction-update"} (:source extra)))
                        (update band :injections conj payload)
                        band))
      ("provider/init" "provider/changed")
      (assoc band :timeline-window (context/timeline-window [row] row))
      band)))

(defn meter-of-records
  "RECORDS -> the METER BAND `state->pressure` eats, folded with `band-step` -- THE SAME STEP
  the live band is kept with (`harness.edge.sessions`' two seams), so the offline reading and
  the live one cannot drift: `records->pressure` is `meter-of-records` + `state->pressure`."
  [records]
  (:pressure
   (replay/fold-consumers (vec records)
                           {:pressure {:init empty-band :step band-step}})))

(defn state->pressure
  "METER + the request an edge has assembled + RATIOS -> how full the next request is: the
  same answer, FIELD FOR FIELD, that `records->pressure` gives for the record the meter was
  taken from. See `records->pressure` for what the fields mean and when they are absent.
 
  THE METER IS THE WHOLE INPUT, on purpose: an anchor is an assertion about the prefix a
  call rested on, so it carries the conversation it was priced against (`:messages`), the
  system message in force, and that run's own injections. NOTHING HERE REACHES FOR A RECORD
  OR A FILE -- a run start hands in MESSAGES and this answers (ticket 03)."
  [meter messages ratios]
  (let [{:keys [latest-start latest-sig latest-mode anchor timeline-window]} meter
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
                        ;; THE PREFIX SURVIVES A HOOK CHANGE ONLY UNDER :in-place, and
                        ;; `:sig` alone cannot tell the two apart -- under :in-place the
                        ;; system row is FROZEN, so its hash is the one the anchor rests on
                        ;; and a moved hook set does not move it. What `:sig` does not catch
                        ;; is a run that MOVED between the two delivery modes: message[0]
                        ;; is a different message then, so the anchor is dropped. A row
                        ;; written before this feature (no mode) reads as :replace, the
                        ;; conservative default.
                        (= (or (:mode anchor) :replace)
                           (or latest-mode :replace))
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

;; ------------------------------------------ the band on the session (tickets 03 & 04)
;;
;; THE BAND IS NOT KEPT IN THIS NAMESPACE. `harness.edge.sessions` owns the ONE walk over a
;; record, and this namespace registers `band-step` on BOTH of that session's seams:
;;
;;   - `register-fold!` (ticket 03, the READ stream): a session is built by folding the
;;     record once, and the band is folded on that same walk -- so a run start answers from
;;     the band WITHOUT reading the file, including the first time this process sees the
;;     thread (there is no seed read left to pay).
;;   - `register-step!` (ticket 04, the WRITE stream): the one writer path --
;;     `harness.edge.http/log!` -- hands every row it writes to the session, and the band is
;;     advanced IN PLACE, so a later run start still reads nothing.
;;
;; ONE STEP, TWO SEAMS, ONE OFFLINE CALLER (`meter-of-records`): three callers of one rule.

(defn band-pressure
  "THREAD-ID's band + MESSAGES + RATIOS -> the same answer `records->pressure` gives, WITHOUT
  reading the record: the band was folded when the session was built and is advanced as rows
  are written. A session this process does not hold has no band, and the answer is then the
  estimate over MESSAGES -- the honest reading of 'nothing is held here'."
  [thread-id messages ratios]
  (state->pressure (or (sessions/fold-value thread-id :pressure) (empty-band)) messages ratios))

(defn log-pressure
  "THREAD-ID + the request an edge has ASSEMBLED BUT NOT YET WRITTEN + WINDOW -> the same
  answer. MESSAGES supplies the surface, because the lines for the run in flight are still
  with the writer. WINDOW, when given, is the window THIS run will go out under -- the call
  that declares it has not happened yet, so the band cannot supply it and the edge hands it in.

  IT READS NO RECORD (tickets 03 / 04): the band lives on the session and is kept current by
  the writer's own row notifications. WINDOW is the only thing the band cannot know."
  ([thread-id messages] (log-pressure thread-id messages nil))
  ([thread-id messages window]
   ;; A REPORT-ONLY METER MUST NEVER KILL A RUN. The band can be missing or stale -- the
   ;; writer is behind, a session is not held, a registration never happened -- and this call
   ;; sits on the run's own path, inside the try that turns any escape into RUN_ERROR. So
   ;; anything at all degrades to the estimate over MESSAGES.
   (let [answer (try
                  (band-pressure thread-id messages default-ratios)
                  (catch Throwable _ (state->pressure (empty-band) messages default-ratios)))]
     (if (and (number? window) (pos? window))
       (assoc answer
              :windowTokens    window
              :percent         (long (Math/round (* 100.0 (/ (double (:pressureTokens answer))
                                                             (double window)))))
              :thresholdTokens (long (Math/floor (* (double window) threshold-ratio)))
              :retainTokens    (long (Math/floor (* (double window) retain-ratio))))
       answer))))

;; --------------------------------------------------- registering the consumer (tickets 03 / 04)
;;
;; AT NAMESPACE LOAD: a session's walk must already have this fold when it is built, and
;; `harness.edge.http` requires this namespace on the run path, so the registration is in
;; place before the first session is born.
(defn install!
  "Register the meter on the session's two seams (tickets 03 / 04): the band's fold on the
  READ stream and the same step on the WRITE stream. THE COMPOSITION ROOT CALLS THIS
  (`harness.edge.http/start!`), so a process that never starts a server never registers a
  consumer -- and a test that wants a band says so in one line.

  Idempotent; returns the teardown."
  [ ] ; no arguments
  (sessions/register-fold! :pressure {:init empty-band :step band-step})
  (sessions/register-step! :pressure band-step)
  (fn teardown []
    (sessions/unregister-fold! :pressure)
    (sessions/unregister-step! :pressure)))
