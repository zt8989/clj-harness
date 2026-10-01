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
            [harness.cap.project :as project]
            [harness.edge.ag-ui :as ag]
            [harness.edge.replay :as replay]
            [clojure.string :as str]
            [harness.kernel.llm :as llm]))

(def summary-instruction
  "What the summarizer is told.

  THE SECTION LIST, THE RULES AND THEIR WORDING ARE COPIED FROM THE REFERENCE
  (`@deepseek-ai/dsh@0.1.5-rc.2`, `packages/compaction/compaction-basic/src/summarizer.ts`,
  `COMPACTION_INSTRUCTION`). Two things in it are the whole reason for copying rather than
  paraphrasing, and both are about the SEAM the summary has to be read against:

    - the last two sections are `## Current Work` and `## Next Step`: a summary written under
      this skeleton cannot be a 'state of the world' that stops at an old commit -- it has to
      say where the work stood AT THE CHECKPOINT and what the single next action is;
    - `Do NOT mention this summarization request or that the context was compacted` -- the
      model is told not to editorialise about provenance. The summary of the owner's incident
      (thread `a0621fce-...`, 2026-10-01) said 'not created by me -- reported by git now',
      which is exactly the kind of sentence that made it read its own work as a stranger's.

  AND THIS MESSAGE CARRIES NOTHING ELSE (owner, 2026-10-01: follow DSH's strategy exactly).
  No git facts, no list of what the range already produced, no hook output -- those three were
  ours, and each went for a reason the incident teaches: the git block (`Where this work is
  happening`, the owner's own addition of 2026-09-27) answered with the BOUND checkout while the
  work was in a worktree, i.e. it named somebody else's uncommitted files as the session's; the
  `Already produced` list covered only the range being folded, while the work the model then
  mis-attributed was in the RETAINED tail, so it never once stood in the way; and the hook's
  words are not part of the reference's request at all. What tells a continuing model who it is
  and where the work stands is now exactly what the reference uses: the retained tail verbatim,
  the checkpoint's preamble, and `## Current Work` / `## Next Step` at the foot of the summary."
  (str/join
   "\n"
   ["You are now acting as a compaction engine for this AI coding assistant. Condense the"
    "conversation ABOVE into a structured checkpoint that lets another model resume the work"
    "with no loss of essential context."
    ""
    "Output EXACTLY the Markdown structure below: keep every section, in order. Use terse"
    "bullets, not prose paragraphs. Write \"(none)\" for an empty section -- never drop a"
    "section."
    ""
    "## Primary Request and Intent"
    "- [the user's original and evolving goals; quote verbatim where the exact wording matters]"
    ""
    "## Key Technical Concepts"
    "- [technologies, frameworks, patterns, and conventions in play]"
    ""
    "## Files and Code"
    "- [exact path: why it matters, key changes or snippets]"
    ""
    "## Errors and Fixes"
    "- [error: how it was resolved, plus any related user feedback]"
    ""
    "## Pending Jobs"
    "- [explicitly requested work not yet completed]"
    ""
    "## Current Work"
    "- [precisely what was in progress at this checkpoint]"
    ""
    "## Next Step"
    "- [the single next action, directly in line with the most recent request, or \"(none)\"]"
    ""
    "## Critical Context"
    "- [decisions and their rationale, constraints, user preferences, open questions, data"
    "needed to continue]"
    ""
    "Rules:"
    "- Write concise English engineering prose. Preserve exact file paths, commands, error"
    "strings, identifiers, numeric values, function signatures, and syntax fragments."
    "- Capture user feedback and explicit instructions faithfully, especially corrections."
    "- Do NOT mention this summarization request or that the context was compacted."
    "- Output only the checkpoint text: do not call any tool or take any other action."
    "- If the conversation already contains a <compacted-summary> block, it is a PRIOR"
    "checkpoint. Do not copy it forward verbatim: preserve still-true facts, drop stale ones,"
    "and merge newer information into a single consolidated summary under the same structure."]))


(defn summary-messages
  "MESSAGES (the plan's model view) + INSTRUCTION -> the array the summarizer is handed: the
  provider's own shape -- the SAME fold the run path uses (`ag/provider-messages`) -- with every
  RECORDED tool answer moved directly behind the call that named it, and the instruction as the
  last user message.

  `llm/adjacent-answers` IS THE HALF THAT WAS MISSING (owner, 2026-09-28), and it is why
  compaction kept failing on a real session. A run cut off mid-call is repaired by
  `harness.edge.replay/closing-frames`, whose TOOL_CALL_RESULT is APPENDED to the log -- so in
  the record the answer sits behind LATER messages, where it answers nothing. The vendor
  refuses that outright (measured: 23 compactions of one session, 23 refusals, every one
  `tool calls and tool results do not match`), while the run path had been moving them back all
  along (`harness.kernel.loop`).

  IT LIVES HERE so that 'what the summarizer is handed' is ONE expression, beside the
  instruction that rides on it."
  [messages instruction]
  (conj (vec (llm/adjacent-answers (ag/provider-messages messages)))
        {:role "user" :content instruction}))

(defn check-ratios!
  "Validate a merged compaction pair, or throw naming what is wrong. Split out so the
  refusal can be asserted without a file."
  [merged]
  (doseq [k [:threshold-ratio :retain-ratio] :let [v (get merged k)]]
    (when-not (and (number? v) (pos? v) (<= v 1))
      (throw (ex-info (str "config.edn's :session :compaction " (name k) " must be a fraction in (0, 1], but it is "
                           (pr-str v))
                      {:key k :value v :reason :bad-compaction-value}))))
  (when (>= (:retain-ratio merged) (:threshold-ratio merged))
    (throw (ex-info (str "config.edn's :session :compaction retain-ratio " (:retain-ratio merged)
                         " must be strictly below threshold-ratio " (:threshold-ratio merged))
                    {:reason :retain-not-below-threshold})))
  merged)

(defn- block
  "config.edn's :session :compaction block for THREAD-ID, as it was written -- nothing merged
  with the defaults and nothing validated. The one reader, so `config` and
  `overflow-retries` cannot fold the same file two different ways. ONE LEVEL: the project
  level this key used to compose against is gone (.scratch/config-merge/spec.md decision 2)."
  [thread-id]
  (let [b (:compaction (project/harness-config thread-id))]
    (if (map? b) b {})))

(defn config
  "The compaction proportions THIS SESSION is configured with, from config.edn's
  `:compaction` (`{:threshold-ratio 0.7 :retain-ratio 0.16}`), the project level over the
  user level, and `harness.edge.pressure`'s defaults when neither says anything.

  REFUSES A PAIR THAT CANNOT WORK, naming what is wrong: a retain that is not STRICTLY
  below the threshold would keep everything a compaction was asked to shrink, and a value
  that is not a fraction is not a proportion at all. Reading is on demand (config.edn is
  re-read, not cached), so a bad value is refused the moment a compaction is asked for."
  [thread-id]
  (check-ratios! (merge pressure/default-ratios (block thread-id))))

(def default-overflow-retries
  "How many times ONE model call the vendor refused for LENGTH may be retried after an
  aggressive compaction, when `config.edn` says nothing: once. A retry happens only when the
  aggressive pass actually shortened the model view, so this bounds a path that must make
  progress rather than a loop that might not. `0` disables the recovery."
  1)

(defn overflow-retries
  "How many times a length-refused model call may be retried for this session: `config.edn`'s
  `:compaction :overflow-retries`, the project level over the user level, defaulting to
  `default-overflow-retries`. `0` disables the recovery -- the vendor's own refusal is then
  handed out untouched.

  REFUSES A VALUE THAT IS NOT A WHOLE NUMBER OF RETRIES, naming it. A fraction or a negative is
  not a count, and rounding one would hide a typo in the single number that bounds a retry
  path."
  [thread-id]
  (let [n (get (block thread-id) :overflow-retries default-overflow-retries)]
    (when-not (and (integer? n) (not (neg? n)))
      (throw (ex-info (str "config.edn's :session :compaction overflow-retries must be a whole number of"
                           " retries (0 disables the recovery), but it is " (pr-str n))
                      {:key :overflow-retries :value n :reason :bad-overflow-retries})))
    n))

(def default-max-tokens
  "How many OUTPUT tokens one summary call may spend when `config.edn` says nothing: 8192 --
  the reference's own default (`@deepseek-ai/dsh@0.1.5-rc.2`, compaction-basic's `maxTokens`).
  A checkpoint that runs into this cap is TRUNCATED, and half a state block is worse than none:
  the caller refuses it rather than landing it (`harness.edge.http`'s summary call checks the
  endpoint's own finish reason)."
  8192)

(defn check-max-tokens!
  "Validate one configured output cap, or throw naming what is wrong. Split out for the same reason `check-ratios!` is: the refusal can be asserted without a file."
  [n]
  (when-not (and (integer? n) (pos? n))
    (throw (ex-info (str "config.edn's :session :compaction max-tokens must be a positive"
                         " whole number of output tokens, but it is " (pr-str n))
                    {:key :max-tokens :value n :reason :bad-compaction-value})))
  n)

(defn max-tokens
  "The output cap one summary call carries for this session: `config.edn`'s \":compaction :max-tokens\", defaulting to `default-max-tokens`."
  [thread-id]
  (check-max-tokens! (get (block thread-id) :max-tokens default-max-tokens)))

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

(defn- protected-node?
  "Is NODE part of the session's OPENING -- the part no compaction may take? Its instruction
  files, skills catalog and birth context are what every later request is read against, and a
  summary is not a substitute for them (`.scratch/session-opening`), so BOTH plans start the
  head after them."
  [node]
  (let [m (:message node)]
    (or (ag/opening-entry? m)
        (= ag/context-entry-id (:id m)))))

(defn- protected-boundary
  "The lowest node index a compaction's head may start at: ONE PAST the opening.

  IT IS NOT `take-while`'s ANSWER ANY MORE, and that is the whole of the 2026-09-27 fix:
  the opening blocks do not sit at the FRONT of the conversation -- the order the edge
  writes is system → the person's question → the opening blocks -- so a prefix scan stopped
  at the first node (the question) and every compaction folded the opening into its summary.
  A protected node is protected WHEREVER it sits, so the boundary is past the LAST one."
  [nodes]
  (if-some [last-i (last (keep-indexed (fn [i n] (when (protected-node? n) i)) nodes))]
    (inc last-i)
    0))

(defn- step-start-indexes
  "NODES + RECORDS -> the index of each step's FIRST node (the first node whose record seq is
  at or after that step's `step/start` line), ascending and distinct. Empty for a record
  written before steps existed -- the fallback the plans then use is a user message."
  [nodes records]
  (let [nodes  (vec nodes)
        n      (count nodes)
        starts (vec (sort (keep-indexed (fn [i r] (when (= "step/start" (replay/kind r)) i)) records)))]
    (if (empty? starts)
      []  ;; no `step/*` rows: a record written before steps existed
      (loop [i 0 ss starts acc []]
        (cond
          (or (>= i n) (empty? ss)) (vec (distinct acc))
          (>= (:id (nth nodes i)) (first ss)) (recur (inc i) (rest ss) (conj acc i))
          :else (recur (inc i) ss acc))))))

(defn- tail-anchor
  "NODES + RECORDS + FROM -> the index the tail may start at, at or BEFORE FROM: the newest
  node at or before FROM that begins a step, or -- on a record with no `step/*` rows -- a
  user message. nil when neither exists there.

  THIS IS WHAT KEEPS A CUT OFF A TOOL PAIR. A step is one model call plus the tools it asked
  for, so a tail that starts on a step's first node leaves every earlier step whole -- no
  `assistant(tool_calls)` is separated from the tool messages answering it, which is the
  shape a vendor refuses (the 400 this ticket exists for)."
  [nodes records from]
  (let [users (keep-indexed (fn [i node] (when (= "user" (get-in node [:message :role])) i)) nodes)]
    (or (last (filter #(<= % from) (step-start-indexes nodes records)))
        (last (filter #(<= % from) users)))))

(defn plan
  "RECORDS + WINDOW + RETAIN-RATIO (+ the SURFACE to plan over) -> the HEAD to compact, or nil
  when there is none.

    {:shadowed [ids] :messages [msgs] :head-tokens n}

  THE TAIL IS THE MOST RECENT NODES whose estimated size reaches
  `floor(window * retain-ratio)` -- the part kept VERBATIM -- and the head is everything
  before it. nil when the whole surface fits in the tail: there is nothing to do, and
  nothing is written.

  THE NODES ARE THE MODEL-FACING SURFACE (`harness.edge.replay/model-nodes`): earlier
  compactions' summaries included, and carrying the ids `:shadowed` is made of.

  SURFACE IS THAT SURFACE WHEN THE CALLER HAS IT, and it is a parameter for the reason the
  whole ticket (`05` of `.scratch/compaction-shape`) exists: THE TRIGGER MEASURES THE ARRAY THE
  MODEL IS ACTUALLY HANDED (`sessions/messages` + the system message + this run's injections),
  and the record's own fold is NOT that array -- it drops every run's injections except the
  last one (`harness.edge.pressure/injected-rows`). A plan over the record fold while the
  trigger measured the live array can answer a head that is only the previous SUMMARY: the fold
  replaces it with something the same size, the surface does not move, and the trigger -- still
  over its threshold -- fires again before the next model call. Measured on thread `62f30024-…`
  (2026-09-30): four compactions in 2 min 22 s, every one of them folding one summary node
  (`:shadowed [12587]`, `head-tokens 4594`) and re-writing a 19 880-character summary.
  NIL SURFACE KEEPS THE OLD READING -- the record's fold -- which is what a process that does
  not hold the session, a fork and a test have, and is the honest answer then.

  MIN-HEAD-TOKENS IS THE RELIEF THE CALLER NEEDS (`{:min-head-tokens n}`), and a head under it
  is NO head: folding less than the trigger says must come off cannot bring anything back under
  a threshold, so the honest answer is nil -- no rows, no summary call, no card. It is the
  caller's number because only the caller has the trigger's reading (the pressure and its
  threshold); a person's manual compaction and the recovery after a vendor's refusal pass none.
  With the surface handed in, this guard is arithmetic that cannot bite (the head of a surface
  the trigger measured is `surface - retain-budget`, and the retain budget is well under the
  threshold) -- it bites exactly when the two arrays disagree again."
  ([records window retain-ratio] (plan records window retain-ratio nil nil))
  ([records window retain-ratio surface] (plan records window retain-ratio surface nil))
  ([records window retain-ratio surface {:keys [min-head-tokens]}]
  (let [records    (vec records)
        facts      (vec (replay/compaction-facts records))
        ;; AN EMPTY SURFACE IS NO SURFACE (`(vec nil)` is `[]`, which is truthy): a caller that has
        ;; nothing live to plan over gets the record's fold, which is the reading it can have.
        nodes      (if (seq surface)
                     (vec surface)
                     (replay/model-nodes (replay/entries records)
                                         facts
                                         (replay/prune-facts records)))
        budget     (long (Math/floor (* (double window) (double retain-ratio))))
        size       (fn [j] (pressure/estimate-message (:message (nth nodes j))))
        k          (protected-boundary nodes)]
    (loop [j (dec (count nodes)) acc 0]
      (cond
        (and (pos? budget) (>= acc budget))
        (when (>= j k)
          (let [t (tail-anchor nodes records (inc j))
                j (if (some? t) (dec t) j)]
            (when (>= j k)
              (let [head (subvec nodes k (inc j))
                    tokens (reduce + 0 (map size (range k (inc j))))]
                ;; TWO THINGS MAKE A HEAD NOT WORTH WRITING, and either answer is nil -- no
                ;; `compaction/start`, no summary call, no card:
                ;;   * it is under the relief the caller asked for (see the docstring);
                ;;   * a node in it has NO ID. `:shadowed` addresses entries by the record line
                ;;     they arrived in, so a head that cannot be named cannot be folded. It is the
                ;;     newest, unlanded part of a LIVE surface that has this shape, and the head is
                ;;     the OLDEST part -- so this refuses a surface that is entirely unlanded rather
                ;;     than a normal one.
                (when (and (or (nil? min-head-tokens) (>= tokens (long min-head-tokens)))
                           (every? :id head))
                  {:shadowed    (mapv :id head)
                   :messages    (mapv :message head)
                   :head-tokens tokens})))))

        (neg? j) nil
        :else    (recur (dec j) (+ acc (size j))))))))

(defn- unit-start
  "NODES -> the index of the first node of the NEWEST INDIVISIBLE UNIT.

  A unit is what the projection may not split: the newest `user` message and everything after
  it -- the request the model still owes an answer to, plus the in-flight work on it. When the
  surface holds no user message at all (a bare assistant/tool tail), the unit is the smallest
  LEGAL suffix, found by walking back over `tool` messages: a suffix may not BEGIN with a tool
  result, because an OpenAI-shaped vendor refuses a `tool` message whose `tool_calls` are not
  in the request."
  [nodes]
  (or (last (keep-indexed (fn [i node] (when (= "user" (:role (:message node))) i)) nodes))
      (loop [i (dec (count nodes))]
        (if (and (pos? i) (= "tool" (:role (:message (nth nodes i)))))
          (recur (dec i))
          i))))

(defn overflow-plan
  "RECORDS + WINDOW + RETAIN-RATIO -> the head to compact when the vendor has ALREADY refused
  the request for its LENGTH, or nil when there is nothing left to remove.

  IT BYPASSES THE BUDGET ON PURPOSE. `plan` keeps `window * retain-ratio` worth of tail
  because the pressure meter is PREDICTING that the window will run out; here the vendor has
  already answered no, so there is nothing to predict and no capacity to consult -- the
  estimate is simply wrong, which is the case `harness.edge.pressure` admits. The tail is the
  smallest thing worth keeping (`unit-start`), and everything between the opening and it is
  summarized.

  WINDOW, RETAIN-RATIO AND SURFACE are accepted and IGNORED so this has the same arity as
  `plan` and the writer can be handed either: the recovery exists precisely because no capacity was
  known, so it must not require one. THE SURFACE IS IGNORED ON PURPOSE TOO -- this plan folds
  everything before the newest indivisible unit whatever array it reads, and the RECORD's fold is
  the one whose `step/*` rows the cut is anchored to; the live array can hold an injected `user`
  card after the question, which `unit-start` would take for the newest request."
  ([records] (overflow-plan records nil nil nil nil))
  ([records _window _retain-ratio] (overflow-plan records _window _retain-ratio nil nil))
  ([records _window _retain-ratio _surface] (overflow-plan records _window _retain-ratio _surface nil))
  ([records _window _retain-ratio _surface _opts]
   (let [records (vec records)
         nodes   (replay/model-nodes (replay/entries records)
                                     (replay/compaction-facts records)
                                     (replay/prune-facts records))
         k       (protected-boundary nodes)
         start   (unit-start nodes)]
     (when (> start k)
       (let [head (subvec nodes k start)]
         {:shadowed    (mapv :id head)
          :messages    (mapv :message head)
          :head-tokens (reduce + 0 (map #(pressure/estimate-message (:message %)) head))})))))

;; ------------------------------------------------------------------------ the run

(defn perform!
  "One compaction. RECORDS is the log as it stands; the effects are APPEND (write a row:
  `(append kind payload)`) and SUMMARIZE (the head's messages -> summary text). Returns
  `{:compactionId id :shadowed [ids] :summary text :tokens n}` when it compacted, and nil
  when it did not -- because the lock was held, or because there was no range.

  IT ANSWERS WHAT IT DID, AND NOTHING ELSE IS ASKED OF IT: `:compactionId` names the pair of
  rows it just wrote and `:tokens` is what the folded range was estimated at, and both are on the
  `context/compacted` fact already. A caller that has somewhere to SAY this (a run's frame
  sink -- `harness.edge.http`) reads them off here rather than picking the fact back out of the
  record: one writer, one answer.

  THE ROWS GO OUT IN THE ORDER THE LOCK REQUIRES: start, the summary fact, end. Nothing is
  written when there is no range -- a pair of rows recording an empty compaction is noise on
  the record and a lie about work that happened.

  A PLAN CAN BE HANDED IN (`:plan-fn`), and the aggressive one is: when the vendor has
  already refused the request for its length, `overflow-plan` bypasses the budget and keeps
  only the newest indivisible unit. Everything else -- the lock, the row order, the no-op
  WHAT IT WRITES, ONE FACT ONE PLACE (owner, 2026-09-28): `compaction/start` carries the PROMPT
  -- the one user message the summarizer is handed -- `context/compacted` carries what was
  folded (`:shadowed` / `:range` / `:tokens`) and what came back (`:summary`), and
  `compaction/end` closes. Nothing is written twice, so a reader reconstructs the request
  from the prompt on the first row plus the records the shadowed seqs name.

  rule -- is the same, so the two paths cannot drift.

  SURFACE AND MIN-HEAD-TOKENS RIDE STRAIGHT TO THE PLAN (`plan`'s docstring has both): the array
  the model is actually handed, and the relief the caller's trigger asked for. A plan that answers
  nil for either reason writes NOTHING -- no `compaction/start` row, no summary call, no card."
  [records {:keys [window retain-ratio append summarize plan-fn surface min-head-tokens]}]
  (when-not (lock-active? records)
    (when-let [head ((or plan-fn plan) records window retain-ratio surface
                     {:min-head-tokens min-head-tokens})]
      (let [id          (str (java.util.UUID/randomUUID))
            ;; THE PROMPT IS BUILT HERE, AND IT IS THE ONE THE SUMMARIZER GETS: the string
            ;; on `compaction/start` and the string in the request are the same value, so a
            ;; reader can reconstruct what was asked without guessing at the pieces.
            ;;
            ;; AND THIS ROW IS THE ONLY PLACE IT IS WRITTEN (owner, 2026-09-28: nothing is
            ;; recorded twice). The folded seqs, the range and the token count are NOT repeated
            ;; here -- they are what `context/compacted` says about the work; this row says what
            ;; the work was TOLD. It goes here rather than on the fact because it is written
            ;; BEFORE any work: a compaction that fails still says what it tried.
            instruction summary-instruction]
        (append "compaction/start" {:compactionId id :instruction instruction})
        (try
          (let [summary (summarize (:messages head) instruction)
                ;; THE SUMMARY HAS TO COME OUT SMALLER THAN WHAT IT REPLACES -- the check the
                ;; reference makes (`dsh-compaction-basic`'s `summarizeCompaction`: a framed
                ;; checkpoint that does not price below the shadowed range is refused). We had
                ;; only the ENTRY guard (`min-head-tokens`: the head must be worth folding); this
                ;; is the EXIT one. A summary that is not smaller would leave the pressure exactly
                ;; where it was and burn a model call doing it, and `.scratch/compaction-shape`
                ;; has the incident that class of failure caused (four folds in two and a half
                ;; minutes, every one folding a summary into another). The MESSAGE is priced, not
                ;; the text: the preamble and the tags are part of what the next request carries.
                framed  (pressure/estimate-message (replay/compaction-summary summary))]
            (when (>= framed (:head-tokens head))
              (throw (ex-info (str "the summary is not smaller than the range it replaces ("
                                   framed " estimated tokens framed >= " (:head-tokens head)
                                   " shadowed)")
                              {:reason :summary-not-smaller
                               :framed framed
                               :shadowed (:head-tokens head)})))
            (append "context/compacted"
                    {:compactionId id
                     :summary      summary
                     :shadowed     (:shadowed head)
                     :range        {:start (first (:shadowed head))
                                    :end   (last (:shadowed head))}
                     :tokens       (:head-tokens head)})
            (append "compaction/end" {:compactionId id})
            {:compactionId id
             :shadowed     (:shadowed head)
             :summary      summary
             :tokens       (:head-tokens head)})
          (catch Throwable t
            (append "compaction/end" {:compactionId id :error (ex-message t)})
            (throw t)))))))
