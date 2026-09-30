(ns harness.edge.context
  "How full the model's window is, folded from a session's RECORD: the prompt the
  last reporting model call was sent, the window that call ran under, and the three
  things that filled it.

  THE FOURTH READER OF THE SAME LOG, beside harness.edge.replay (the conversation),
  harness.edge.stats (the numbers) and harness.edge.trajectory (what the model saw).
  What it reads is the `message` / `model/*` lines and the provider
  timeline, and the question it answers is none of the other three's: 'this call was
  sent N tokens -- out of how much room, and what was in them'.

  THE NUMBERS ARE THE VENDOR'S, THE SPLIT IS OURS, AND BOTH SAY WHICH THEY ARE.
  `:usedTokens` is the vendor's own `prompt_tokens` on the paired `model/end`, and
  `:windowTokens` is the `:context-window` the catalog declared for THAT CALL
  (recorded on its `model/start`; see harness.kernel.event). Nobody reports a split,
  so the three parts are an ESTIMATE -- the vendor's total apportioned by the
  recorded size of each part -- and they therefore ADD UP TO `:usedTokens` exactly.
  A stacked bar that does not reach its own total is a drawing that lies, and a
  fourth 'other' bucket would be a bucket nobody measured.

  IT IS A PURE FUNCTION OF ITS TWO ARGUMENTS -- the records, and the ARRAY the chosen
  call was handed (records->context, and see below for why the array is an argument) --
  for the same reason the two sibling folds are: what can be asserted is the interesting
  part. THE ARRAY IS NOT FOLDED HERE. Which messages a call carried is one rule, and it
  lives with the fold that keeps one array per call (`harness.edge.pressure`'s anchor --
  see `shares`); a second spelling of it here is a second chance to disagree, which is
  exactly what the conversation bucket used to do. Walking a log is a second, thinner
  function (log-context), and THE DIRECTORY IS THE CALLER'S.

  NOTHING HERE IS RE-DERIVED FROM TODAY'S CATALOG. A session can be switched to
  another model between calls and a built-in table can be edited, so the window in
  force for the call is read off the call's own line -- with the record's provider
  timeline as the fallback for logs written before that key existed. Answering from
  the live resolution would divide one call's prompt by another model's window."
  (:require [clojure.data.json :as json]
            [harness.edge.stats :as stats]
            [harness.edge.trajectory :as trajectory]
            [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]
            [harness.kernel.tools :as tools]))

;; ----------------------------------------------------------------- measured size

(defn size-of
  "The size of VALUE as this record spells it: characters of its JSON, unicode NOT
  escaped.

  THE SAME RULE FOR ALL THREE PARTS is what makes the split mean anything -- a
  policy per part would decide the answer instead of measuring it. `:escape-unicode
  false` is not cosmetic: with the default a Chinese character counts as the six
  characters of `\\u4e2d`, which would inflate a conversation written in Chinese
  against a tool table written in ASCII."
  [value]
  (count (json/write-str value :escape-unicode false)))

(defn tool-signature
  "SPECS -> the small statement of a request's tool table that a `model/start` line
  keeps (harness.kernel.event): the NAME set as a hash, the count, and the size in
  characters as this record spells it (`size-of`). nil for an empty table, which
  writes no `:tools-*` key at all.

  THE EDGE IS WHERE THIS LIVES because `size-of` is the wire's character rule and the
  kernel does not own it. The kernel owns the MECHANISM -- `harness.kernel.loop`
  hands the signature through, `harness.kernel.tools` owns the name hash -- and this
  is the capability half, the one number that has to be measured rather than hashed.",
  [specs]
  (when (seq specs)
    (assoc (tools/default-signature specs)
           :tools-bytes (size-of specs))))

;; --------------------------------------------------------------------- the call

(defn- call-records
  "PAIRING is the record's own rule, used in all three folds that need it: the nth
  `model/start` of a run is that run's nth call, and the record carries no counter
  because a counter would be the same fact written twice.

  Both lines of a call are kept here rather than only the usage: the window and the
  tool table live on the start's payload, and they have to be THIS call's."
  [records]
  (loop [[record & more] records
         pending         nil
         acc             []]
    (cond
      (nil? record)
      (cond-> acc pending (conj {:start pending :end nil}))

      (= "model/start" (replay/kind record))
      (recur more record (cond-> acc pending (conj {:start pending :end nil})))

      (= "model/end" (replay/kind record))
      (recur more nil (conj acc {:start pending :end record}))

      :else
      (recur more pending acc))))

(defn- last-reporting-call
  "The most recent call whose vendor reported a `prompt_tokens`, as
  {:run <index into RUNS> :start <its start record or nil> :end <its end record>.

  ONLY THE RUN'S OWN CALLS COUNT (`harness.edge.trajectory/own-calls`). A run's rows also carry
  the calls the HARNESS wrote for itself, and right after a compaction the newest of those is the
  summarizer's own request -- which was sent A RANGE OF THE CONVERSATION plus an instruction, not
  the conversation. Counting it made the ring report the size of what had just been folded AWAY:
  measured on a real session (2026-09-28, `86c1c343-…`), a compaction that folded 308,071 tokens
  put the ring at 27% (the summarizer's own 281,889-token prompt) while the session itself stood
  at 66%, and it snapped back to 66% on the very next call -- the number a person read as 'the
  compaction did not work'.

  ABSENT WHEN NOBODY REPORTED ONE, and a call that reported nothing does not erase
  an earlier call's number: the ring shows the last MEASUREMENT, not a blank. What
  it shows is therefore that call's prompt -- the record cannot say what a later call
  that reported nothing was sent."
  [runs]
  (reduce (fn [acc [i run]]
            (reduce (fn [acc call]
                      (let [prompt (get-in (replay/payload (:end call)) [:usage :prompt_tokens])]
                        (if (number? prompt)
                          {:run i :start (:start call) :end (:end call)}
                          acc)))
                    acc
                    (call-records (trajectory/own-calls run))))
          nil
          (map-indexed vector runs)))

(defn- window-of
  "The window a PROVIDER LINE declared, whichever of the two shapes it is written in.

  THE TWO SHAPES ARE NOT AN ACCIDENT AND THE READER MUST NOT HAVE TO CARE WHICH IT MET.
  A `provider/changed` line nests the resolution -- `:before` / `:after` / `:override` are
  the change, and `:resolved` is what the catalog made of it afterwards. A `provider/init`
  line IS the resolution: harness.edge.http/provider-line splices the wire map at the top
  level beside `:source`. Reading only the nested one silently answers nil on every log
  whose session never changed provider -- which is most of them, and the failure looks
  like 'this model declares no window' rather than like a bug."
  [record]
  (let [payload (replay/payload record)]
    (or (get-in payload [:resolved :context-window])
        (:context-window payload))))

(defn- timeline-row?
  "Whether ROW is one of the lines a provider's window is read from."
  [row]
  (contains? #{"provider/init" "provider/changed"} (replay/kind row)))

(defn- timeline-pair
  "ROW -> [ts window]: the pair the timeline is kept as, so the session's fold can hold the
  same value a whole-record reader builds, instead of folding the records again at answer time."
  [row]
  [(:ts row) (window-of row)])

(defn- window-at
  "The window in force at TS, read off TIMELINE's [ts window] pairs.

  BY THE CLOCK, NOT BY POSITION: the lines a session's provider timeline is made of land
  outside runs too, and a log's `:ts` only ever goes forwards."
  [timeline ts]
  (->> timeline (filter (fn [[t _]] (<= t ts))) last second))

(defn timeline-window
  "The window the provider timeline said was in force at or before RECORD: the last
  `provider/init` / `provider/changed` before it, as the edge wrote it down at the time.

  THE FALLBACK FOR LOGS WRITTEN BEFORE `model/start` CARRIED THE WINDOW. It is
  deliberately not a fallback to the catalog: that would answer with today's number
  for a call made under another one. A session served by a scripted pin writes no
  timeline line at all -- there is no resolution to record -- and takes its window
  from the call's own line, which is why that is the primary source."
  [records end-record]
  (window-at (into [] (comp (filter timeline-row?) (map timeline-pair)) records)
             (:ts end-record)))

;; -------------------------------------------------------------------- the split

(defn- shares
  "What filled this call, in characters, in the order the panel draws it: the system
  message, then the tool table, then the conversation. The answer is a vector of
  [key size] pairs, and the keys are the wire's spelling of each bucket -- system,
  tools, conversation.

  FACE IS THE ARRAY THE CALL WAS HANDED, and it arrives as an argument rather than being
  folded here. THE SIZES ARE THIS NAMESPACE'S, THE ARRAY IS NOT: which messages a call
  carried is one rule, kept by the fold that snapshots an array per call (see
  `harness.edge.pressure/anchor-face`). This split used to spell the rule itself -- the
  conversation was \"this run's own rows\" -- and ADR 0002 means the history is not among
  them: on a real session (2026-09-30, `.scratch/context-ring`) the tool table came out at
  68.7% of a prompt it was under 3% of, and the conversation at a quarter of the window it
  had almost all of.

  THE THREE PARTITIONS ARE THE ARRAY'S OWN. The system message is the message whose role
  says so; the tool table is the very value that went into the request body (recorded on
  the start line, so it is the table that WENT OUT); and the conversation is every other
  message of the array -- the whole history as the session holds it, the client's new
  messages, the opening blocks, the skill bodies that rode along, and whatever the kernel
  had appended by the time the call went out. The injected context is not a fourth bucket:
  it IS a message, and it is in the conversation.
 
  AN INSTRUCTION UPDATE (role \"developer\", source \"instruction-update\") IS IN THE
  CONVERSATION TOO, and that is a decision, not a default: the `system` bucket is
  literally the record's system row -- message[0] as it went out -- while an update is
  an ordinary tail message the model was handed, and turning `system` into 'anything
  instructional' would answer a different question than the record's own split does.
  Its price is visible and honest: under :in-place the chain grows in the conversation
  bucket because it is resent every run (`.scratch/instruction-updates` decision 3).

  THE KEYS ARE STRINGS, like the trajectory's item kinds: this is an enum-shaped
  value that goes out on the wire and comes back to a client that matches on it, and
  a keyword here would be a value whose spelling changed in transit."
  [face start-payload]
  (let [messages (vec face)
        system   (filter #(= "system" (:role %)) messages)
        rest     (remove #(= "system" (:role %)) messages)]
    [["system"       (reduce + 0 (map size-of system))]
     ["tools"        (if-some [b (:tools-bytes start-payload)]
                       b
                       ;; AN OLD RECORD KEEPS THE TABLE, and its size is still the answer.
                       (size-of (or (:tools start-payload) [])))]
     ["conversation" (reduce + 0 (map size-of rest))]]))

(defn- apportion
  "TOKENS split over SHARES by size, so that the parts ADD UP TO TOKENS exactly.

  Each part is rounded and the rounding drift lands on the LARGEST part -- the one
  where a token or two is least visible, and the one that would otherwise be the
  visibly short segment of a bar that has to reach the end. nil when there is nothing
  to divide by, which is the honest answer for a prompt whose parts the record does
  not describe."
  [tokens shares]
  (let [total (reduce + 0 (map second shares))]
    (when (pos? total)
      (let [rounded (mapv (fn [[k c]] [k (long (Math/round (* (double tokens) (/ (double c) (double total)))))])
                          shares)
            drift   (- tokens (reduce + 0 (map second rounded)))
            biggest (first (apply max-key second shares))]
        (mapv (fn [[k n]] {:key k :tokens (if (= k biggest) (+ n drift) n)}) rounded)))))

;; ------------------------------------------------------------------- the answer

(defn state-init []
  "The state the context section is read from: THE SAME RUN-SEGMENT MACHINE the trajectory
  view folds (`harness.edge.trajectory/segments-init`), plus the one small thing only this
  reader needs -- the provider timeline as [ts window] pairs.

  REUSING THE SEGMENT MACHINE IS THE POINT. `segments-step` is already a fold, and
  `harness.edge.trajectory/run-segments` says in its own docstring that this namespace is the
  second reader of it ('a second implementation of it would be a second chance to disagree
  about where a run starts'). Registering THAT fold on the session therefore adds no second
  reading of the record -- which is what `.scratch/turn-and-model-events` ticket 01 asks for.

  IT ONCE ALSO KEPT `:last-event`, and that went with `incomplete-here?` (2026-09-30): the
  split no longer waits on the run's terminal frame, so nothing here reads the log's last row.
  (`harness.edge.trajectory` keeps its own -- for `:incomplete`, a fact about a READ.)"
  {:segments   (trajectory/segments-init)
   :timeline   []})          ;; [[ts window] ...], provider lines only, in file order

(defn state-step
  "ONE ROW of the fold -> the next state: [LINE-INDEX ROW] -> state, with CTX ignored.

  A STEP RATHER THAN A WHOLE-RECORD FUNCTION, because the session advances it as rows are
  written (`sessions/register-step!`): the same function drives the birth walk and every
  later line, so the live answer and a cold read cannot disagree."
  [st _ctx [i row]]
  ;; A NIL STATE STAYS NIL -- this session does not hold this fold (it was built before the
  ;; registration), and there is nothing to advance. See `harness.edge.stats/stats-step` for the
  ;; longer note; the reason is the same one.
  (when (some? st)
    (cond-> (update st :segments trajectory/segments-step [i row])
      (timeline-row? row) (update :timeline conj (timeline-pair row)))))

(defn state->context
  "STATE + the ARRAY the chosen call was handed -> the context section of the session's
  payload. See the namespace docstring for the rules; the shape is the one documented on
  the route (GET /api/threads/<stem>/stats):

    {:usedTokens 76300 :windowTokens 262144 :percent 29
     :parts [{:key system :tokens 1800} {:key tools :tokens 12900}
             {:key conversation :tokens 50700}]}

  (The keys are strings on the wire; they are written bare here to keep this
  docstring readable.)

  EVERY KEY IS ABSENT WHEN IT WOULD BE A GUESS. No call reported a prompt: no
  `:usedTokens`. The call's line and the timeline both say nothing about a window:
  no `:windowTokens` and no `:percent` (a percentage needs both halves, and the
  client is not asked to divide). Nobody kept the array that call was handed: no
  `:parts` -- the vendor's numbers without a split is half an answer, and the
  honest half.

  THE SPLIT IS DRAWN WHILE THE RUN IS STILL GOING, and it took a browser (2026-09-30)
  to show what the old rule cost. This used to withhold `:parts` whenever the chosen
  call sat in a run with no terminal frame yet, on the reasoning that the run's message
  side lands one beat after that frame -- but the frame being waited for was the RUN's,
  not the call's. The rows a call was handed are written before it goes out (on a real
  log a mid-run call's assistant row lands immediately before its own `model/end`), so
  the split was withheld for the whole length of every long run, and a ring with no split
  to draw is one arc in the fallback colour -- near black in the light theme -- for
  minutes at a time while agents worked.

  THE PERCENTAGE AND THE SPLIT ARE THE SAME CALL'S, which is why FACE is taken rather
  than the conversation as it stands now: dividing a measured prompt by messages that
  call was never sent would describe a call nobody made.

  JUST AFTER A COMPACTION the ring still shows the call that went out BEFORE it, and
  that is decided rather than overlooked (`context-ring` ticket 01 asked for the reason
  in writing). The other answer on the table was 'show the conversation's own size once
  `compaction/end` is the newest row', which would put an ESTIMATE under a number that is
  the vendor's -- and telling those two apart is this namespace's oldest rule (`:estimated`
  exists for exactly that). Nor does a compaction make the last call's measurement wrong:
  that call WAS sent that much. What a reader wants at that moment is 'did it work',
  which is the meter's answer; the ring answers the question it has always answered --
  how full the last real call was."
  [st face]
  (let [runs   (trajectory/segments-answer (:segments st))
        chosen (last-reporting-call runs)]
    (if (nil? chosen)
      {}
      (let [start   (:start chosen)
            payload (replay/payload start)
            usage   (replay/payload (:end chosen))
            used    (get-in usage [:usage :prompt_tokens])
            window  (or (:context-window payload)
                        (window-at (:timeline st) (:ts (:end chosen))))
            parts   (when (seq face) (apportion used (shares face payload)))]
        (cond-> {:usedTokens used}
          (and (number? window) (pos? window))
          (assoc :windowTokens window
                 :percent (long (Math/round (* 100.0 (/ (double used) (double window))))))

          (seq parts) (assoc :parts parts))))))

(defn records->context
  "A whole record + the ARRAY its last reporting call was handed -> its context section,
  folded from a stream: the same answer `state->context` gives, driven one row at a time
  so a session can advance it between reads.

  FOLDED FROM A STREAM, and that is not only about memory: the SAME `state-step` is what the
  session registers (`install!`), so a cold read and a live answer are one implementation."
  [records face]
  ;; THE CTX IS PASSED AS NIL HERE AND IGNORED: the read fold's driver hands a step
  ;; `[value ctx [line-index row]]` (see `harness.edge.pressure/band-step`, which is the
  ;; same shape), and this reader has no use for it -- what it folds is in the rows, and the
  ;; one array it needs is the FACE argument.
  (state->context (reduce (fn [st pair] (state-step st nil pair))
                          (state-init)
                          (map-indexed vector records))
                  face))

(defn install! []
  "Register this reader's fold on BOTH of a session's seams (the birth walk and the write
  stream), so a live session can answer without opening the record at all -- the shape
  `harness.edge.pressure/install!` established. Idempotent; returns the teardown."
  (sessions/register-fold! :context {:init state-init :step state-step})
  (sessions/register-step! :context state-step)
  (fn teardown []
    (sessions/unregister-fold! :context)
    (sessions/unregister-step! :context)))

(defn log-context
  "A log FILE + the array its last reporting call was handed -> records->context of it. The
  file entry point, the counterpart of harness.edge.stats/log-stats: the caller locates the
  stem and this namespace never learns where the log came from."
  [f face]
  (records->context (stats/read-records f) face))
