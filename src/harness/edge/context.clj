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

  IT IS A PURE FUNCTION OVER RECORDS (records->context) for the same reason the two
  sibling folds are: what can be asserted is the interesting part. Walking a log is
  a second, thinner function (log-context), and THE DIRECTORY IS THE CALLER'S.

  NOTHING HERE IS RE-DERIVED FROM TODAY'S CATALOG. A session can be switched to
  another model between calls and a built-in table can be edited, so the window in
  force for the call is read off the call's own line -- with the record's provider
  timeline as the fallback for logs written before that key existed. Answering from
  the live resolution would divide one call's prompt by another model's window."
  (:require [clojure.data.json :as json]
            [harness.edge.stats :as stats]
            [harness.edge.trajectory :as trajectory]
            [harness.edge.replay :as replay]
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
  {:run <index into RUNS> :start <its start record or nil> :end <its end record>}.

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
                    (call-records (:calls run))))
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

(defn timeline-window
  "The window the provider timeline said was in force at or before RECORD: the last
  `provider/init` / `provider/changed` before it, as the edge wrote it down at the time.

  THE FALLBACK FOR LOGS WRITTEN BEFORE `model/start` CARRIED THE WINDOW. It is
  deliberately not a fallback to the catalog: that would answer with today's number
  for a call made under another one. A session served by a scripted pin writes no
  timeline line at all -- there is no resolution to record -- and takes its window
  from the call's own line, which is why that is the primary source."
  [records end-record]
  (->> records
       (filter #(contains? #{"provider/init" "provider/changed"} (replay/kind %)))
       ;; By the clock, not by position: the lines a session's provider timeline is
       ;; made of land outside runs too, and a log's :ts only ever goes forwards.
       (filter #(<= (:ts %) (:ts end-record)))
       last
       window-of))

;; -------------------------------------------------------------------- the split

(defn- shares
  "What filled this call, in characters, in the order the panel draws it: the system
  message, then the tool table, then the conversation. The answer is a vector of
  [key size] pairs, and the keys are the wire's spelling of each bucket -- system,
  tools, conversation.

  THE THREE PARTITIONS ARE THE RECORD'S OWN. The system message is the `message`
  line whose role says so; the tool table is the very value that went into the
  request body (recorded on the start line, so it is the table that WENT OUT); and
  the conversation is every other message line of the run -- both the submitted side
  (the client's messages, the opening blocks, the skill bodies that rode along) and
  the returned side (what the kernel appended). The injected context is not a fourth
  bucket: it IS a message line, and it is in the conversation.
 
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
  [run start-payload]
  (let [messages (concat (:submitted run) (:returned run))
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

(defn records->context
  "RECORDS -> the context section of the session's payload. See the namespace
  docstring for the rules; the shape is the one documented on the route
  (GET /api/threads/<stem>/stats):

    {:usedTokens 76300 :windowTokens 262144 :percent 29
     :parts [{:key system :tokens 1800} {:key tools :tokens 12900}
             {:key conversation :tokens 50700}]}

  (The keys are strings on the wire; they are written bare here to keep this
  docstring readable.)

  EVERY KEY IS ABSENT WHEN IT WOULD BE A GUESS. No call reported a prompt: no
  `:usedTokens`. The call's line and the timeline both say nothing about a window:
  no `:windowTokens` and no `:percent` (a percentage needs both halves, and the
  client is not asked to divide). The three parts need the run's message side to be
  COMPLETE -- the kernel writes it one beat after the terminal frame -- so a run that
  is still going, or whose tail has not landed, reports the vendor's numbers and no
  split at all. Half a message set would make the conversation look like a small
  share of a large prompt."
  [records]
  (let [runs   (trajectory/run-segments records)
        chosen (last-reporting-call runs)]
    (if (nil? chosen)
      {}
      (let [run       (nth runs (:run chosen))
            start     (:start chosen)
            payload   (replay/payload start)
            usage     (replay/payload (:end chosen))
            used      (get-in usage [:usage :prompt_tokens])
            window    (or (:context-window payload) (timeline-window records (:end chosen)))
            ;; THE SPLIT NEEDS THE RUN'S MESSAGE SIDE TO BE ON DISK. Two ways it is
            ;; not: the chosen call is in the log's LAST run and that run has no
            ;; terminal frame (it is still going), or its returned tail has not landed
            ;; yet -- the kernel writes it one beat after the frame that ends the run.
            ;; Either way the vendor's numbers are already true and the split is not, so
            ;; the numbers are reported and the split is left out.
            unfinished? (or (and (= (:run chosen) (dec (count runs)))
                                 (stats/incomplete? records))
                            (empty? (:returned run)))
            parts     (when-not unfinished?
                        (apportion used (shares run payload)))]
        (cond-> {:usedTokens used}
          (and (number? window) (pos? window))
          (assoc :windowTokens window
                 :percent (long (Math/round (* 100.0 (/ (double used) (double window))))))

          (seq parts) (assoc :parts parts))))))

(defn log-context
  "A log FILE -> records->context of it. The file entry point, the counterpart of
  harness.edge.stats/log-stats: the caller locates the stem and this namespace never
  learns where the log came from."
  [f]
  (records->context (stats/read-records f)))
