(ns harness.edge.replay
  "Rebuild a conversation from a thread's JSONL log, so it can be continued after the
  process that recorded it is gone.

  This is the read side of the log contract, promoted from dev/ (2026-09-13,
  ticket 05) because rebuilding is now a product capability -- the
  /api/threads/<id>/rebuild endpoint serves a client its own history back. The
  iron law is UNTOUCHED and must stay so: the kernel never reads its log during
  a run. Rebuilding is an explicit management action, outside any run -- which
  is also why every rebuild action leaves its own audit line at the edge.

  The reconstruction rule is one line: WALK THE RECORD IN FILE ORDER, and let each input
  line say what that action brought while the frames that follow it say what came of it
  (see `fold-frames`). Until ticket 03 of `.scratch/sessions-live-on-the-server` that
  line read differently: seed from the FIRST input's messages, then fold every frame onto
  it, ignoring later inputs, because a client's second input restates everything before
  it. The difference is not a detail: a client's restatement was the only
  reason to ignore the later lines, and the same restatement is why a rebuild could not
  show a second run's question at all (no frame ever carries the message the CLIENT
  wrote). An action's own entries are what enter the conversation, and the id is what
  keeps a restated one from entering twice.

  The DIRECTORY is the caller's -- this namespace stays a pure reader and never
  learns where the process keeps its home. That is why every entry point but the
  listing takes one: the routes locate a stem through `locate` (which workspace a
  conversation lives in follows from its project) and hand this namespace the
  directory they found. The FILENAME rule is shared with the writer through
  harness.infra.home/sanitize: that one expression is the part the two sides must agree
  on, and sharing it is what stops them drifting.

  THE LISTING IS A TREE WALK. `threads` used to take one directory, because there
  was one; the logs are now a tree -- one workspace per project plus a reserved
  one -- so a listing has to walk it, and `locate` exists because a stem is no
  longer unique across it. Both are the reading side's answer to the tree. What
  this namespace still refuses to learn is where the tree starts: the caller
  passes the root in, exactly as it passes one directory in for a rebuild."
  (:require [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.edge.ag-ui :as ag]
            [harness.kernel.frames :as frames]
            [harness.infra.home :as home]
            [harness.cap.project :as project]
            [harness.cap.providers :as providers]
            [harness.kernel.loop :as loop]
            [harness.cap.system-prompt :as system-prompt]))

(defn read-lines
  "A log FILE's raw lines (see harness.infra.home/log-file). A missing log is a NAMED
  failure, not an empty conversation: continuing from nothing would silently drop
  a whole history."
  [^java.io.File f]
  (when-not (.exists f)
    (throw (ex-info (str "no log at " (.getAbsolutePath f))
                    {:path (.getAbsolutePath f)})))
  (str/split-lines (slurp f :encoding "UTF-8")))

(def ^:private row-types
  "THE RECORD HAS EXACTLY TWO KINDS OF ROW (`.scratch/jsonl-two-kinds`, 拍定 2026-09-21):
  `message` -- what a person said or an LLM returned -- and `event`, every other fact."
  #{"event" "message"})

(def ^:private wire-custom-names
  "The CUSTOM frame names the WIRE uses, which is how a row that is a frame is told from a
  row that is a fact: an `event` carrying one of these is a frame the conversation is made
  of, and an `event` carrying any OTHER CUSTOM is the harness speaking about itself (see
  `harness.edge.http/row-of`). A second wire name must be added here in the same commit that
  starts using it -- otherwise a fact and a frame would be read the same way."
  #{ag/injected-part-name})

(defn- fact-frame?
  "Is FRAME the harness speaking about itself rather than a frame the conversation is made
  of? A CUSTOM frame whose name is NOT one the wire uses (see `wire-custom-names`)."
  [frame]
  (and (map? frame)
       (= "CUSTOM" (:type frame))
       (not (contains? wire-custom-names (:name frame)))))

(defn- read-row
  "ONE LINE OF THE RECORD -> the row itself, validated: `{:type :payload :ts :runId ..}`.

  NOTHING IS REWRITTEN ON THE WAY IN (2026-09-21, `.scratch/jsonl-two-kinds` 票 02). Until
  this ticket the reader turned each line into `{:kind .. :payload ..}` on the way -- a
  second shape every reader then spoke, which is exactly what ticket 01's comment called
  the seam. What a reader holds now is what the file says, so an `:id` the writer put on a
  row is visible to the reader that needs it (票 02's messages carry their own identity),
  and there is one shape to reason about instead of two. The vocabulary for asking what a
  row IS -- `kind`, `payload`, `message?`, `frame?` -- is derived in ONE place, below.

  THE READER IS STRICT, BY 拍定. A line that is not one of the two rows is a HARD failure that
  names the line and the reason -- including a line from the OLD contract (`:kind` at the top
  level), which is refused by name rather than read leniently: an old record is a record this
  build cannot honestly fold (`.scratch/jsonl-two-kinds` 决定 3: 报错并提示开新会话)."
  [idx line]
  (let [n (inc idx)
        fail (fn [reason sentence]
               (throw (ex-info (str "line " n " of the log " sentence) {:line n :reason reason})))]
    (let [row (try (json/read-str line :key-fn keyword)
                   (catch Exception e
                     (fail :not-json (str "is not valid JSON (" (ex-message e)
                                          ") -- the log is truncated or corrupt"))))]
      (cond
        (not (map? row))
        (fail :not-an-object "is not a JSON object")

        (contains? row :kind)
        (fail :old-contract (str "is written in the old contract (`kind` at the top level);"
                                 " this build reads only `event` and `message` rows --"
                                 " start a new conversation, or read it with the build that"
                                 " wrote it"))

        (not (contains? row :payload))
        (fail :missing-payload "has no `payload`")

        (not (contains? row-types (:type row)))
        (fail :unknown-type (str "has type " (pr-str (:type row)) ", and a record row is"
                                 " either \"event\" or \"message\""))

        :else row))))

(defn kind
  "WHAT A ROW IS, in the one word or name every reader of this file asks for:

    \"message\"  what a person said or an LLM returned;
    \"event\"    a frame the wire carried (RUN_STARTED, TEXT_MESSAGE_CONTENT, an injected
                context card, ...) -- the conversation is made of these;
    <a name>    the harness speaking about ITSELF: the CUSTOM frame's own name
                (`model/start`, `project/bound`, `hook/SystemPrompt`, `system-prompt`, ...).

  DERIVED, NOT STORED, AND DERIVED HERE. The row is the file's own; this is the vocabulary
  for the one question everybody asks about it, and it has exactly one answer because it is
  written once: the two row types are the file's (`row-types`) and which CUSTOM names are
  the wire's is `wire-custom-names`."
  [row]
  (if (= "message" (:type row))
    "message"
    (let [frame (:payload row)]
      (if (fact-frame? frame) (:name frame) "event"))))

(defn payload
  "THE THING A ROW CARRIES, whatever it is: a `message` row's provider message, a frame
  row's frame, and a FACT row's OWN VALUE (the `:value` under the CUSTOM envelope that names
  it). `kind` says which of the three a row is; this says what was in it.

  ONE DOOR, because the three cases are the same question -- 'what did this row bring' --
  and every reader that spells the unwrapping itself is a reader that can disagree with
  `kind` about what a fact is."
  [row]
  (let [frame (:payload row)]
    (if (fact-frame? frame) (:value frame) frame)))

(defn message?
  "A row that is what a person said or an LLM returned."
  [row]
  (= "message" (kind row)))

(defn frame?
  "A row that is a frame the wire carried -- what the conversation is made of."
  [row]
  (= "event" (kind row)))

(defn fact?
  "A row that is the harness speaking about itself: neither a message nor a frame, but a fact
  with a NAME (`kind` is that name)."
  [row]
  (let [k (kind row)]
    (and (not= "message" k) (not= "event" k))))

(defn system-prompt?
  "A row that carries THE SYSTEM MESSAGE -- a `message` row whose `:source` says the model's
  own prompt put it in the array (`harness.edge.http` writes one per run, `:source
  \"system-prompt\"` and the bytes' `:hash`).

  IT IS A MESSAGE ROW AND NOT A SPEAKING PART, which is why this predicate exists: a `message`
  row IS an element of the array the model read, so the prompt belongs there; the CONVERSATION
  the session holds -- and with it everything a client is ever handed -- does not contain it,
  because the client never has the prompt (see `entries`). One rule, in the place that folds
  rows into that conversation, is what keeps the two apart."
  [row]
  (and (= "message" (kind row)) (= "system-prompt" (:source row))))

(defn lines->records
  "Parse a log's lines into ROWS -- the file's own shape, validated. A line that will not
  parse -- or is not one of the record's two rows -- is a hard failure that names the line:
  a log killed mid-write must not be mistaken for a shorter conversation, and an
  old-contract record must not be mistaken for an unreadable one (see `read-row`)."
  [lines]
  (mapv read-row (range) lines))

(defn read-records
  "A log FILE's records, DROPPING a half-written LAST line -- the one shape a file being
  appended to legitimately has.

  The writer hands whole lines to one consumer (`harness.edge.record/append!`), but a reader
  can still catch the newest line mid-flush: that is a fact about reading a live log, not a
  corrupt one, and dropping it is the honest answer -- the rest is what has happened so far.
  EVERY OTHER LINE IS READ STRICTLY: a torn line in the middle is corruption, and a reader
  that swallowed it would hand back a shorter conversation as if it were the whole one.

  TWO READERS, ONE RULE, spelled here so they cannot drift: `harness.edge.stats/read-records`
  (the numbers strip, which asks while a run streams) delegates to this, and the window asks
  through it while a run it holds is in flight (`harness.edge.http/record-entries`).
  `lines->records` remains the reader for a file that is supposed to be finished -- refusing
  a torn line there is what sends a client to the `rebuild` door."
  [^java.io.File f]
  (let [lines (read-lines f)]
    (if (empty? lines)
      []
      (let [head (lines->records (butlast lines))
            tail (try (first (lines->records [(last lines)]))
                      (catch Throwable _ nil))]
        (cond-> (vec head) (some? tail) (conj tail))))))

(defn- runs
  "Every run a log holds, in the order its first MESSAGE row opened it: {:run-id .. :frames
  [payload ..] :terminal <frame type or nil>}.

  A RUN BEGINS WHERE AN ARRAY WAS HANDED OVER, and the rows that say so are `message` rows:
  `.scratch/jsonl-two-kinds` 票 02 deleted the `input` row that used to open a run and made
  every element of the model's array a row of its own, so the run's first such row is where
  its record begins -- the client's own message on a run that brought one, the system prompt
  on a run that did not (`harness.edge.http` writes the prompt once per run, with the bytes'
  `hash`). A HARNESS FACT DOES NOT OPEN A RUN: the audit lines written outside one
  (`project/bound`, `provider/changed`) are `event` rows, and that is exactly the distinction
  `ensure-complete!` rests on.

  A RUN IS PAIRED BY IDENTITY, NEVER BY POSITION. Every frame line carries the id of the run
  that emitted it, and so does every message row -- the pairing the writer at the edge keeps,
  which is why the frames appended to close a run land under THAT run's id. So 'which run
  never ended' is a question asked of ids, and the positional answers -- 'the last input',
  'the last terminal frame' -- stop being answers the moment one thread has two runs in
  flight: their lines interleave in the one file, and the run that finishes LAST
  can sit after a run that never finished at all.

  A frame that arrives after its run's terminal does not replace it: the first
  terminal ends the run (`frames/terminal?`'s rule, 'nothing may follow it'), and
  `:frames` keeps everything recorded for the run, so a reader can still name what
  it left unsaid.

  THE TERMINAL IS KEPT TWICE, as its `:type` and as the FRAME ITSELF
  (`:terminal-frame`), because which frame ended a run matters as much as that one
  did: a RUN_FINISHED carrying `outcome.interrupts` says the conversation is parked
  on a human, and a reader that only had the type could not tell that from an
  ordinary finish. First terminal wins for both, together."
  [records]
  (reduce
   (fn [found row]
     (let [runId (:runId row)
           frame (payload row)]
       (if (message? row)
         (if (some #(= runId (:run-id %)) found)
           found
           (conj found {:run-id runId :frames [] :terminal nil :terminal-frame nil}))
         (case (kind row)
           "event" (mapv (fn [run]
                           (if (= runId (:run-id run))
                             (if (frames/terminal? frame)
                            ;; THE FIRST TERMINAL ENDS THE RUN, and the FRAME goes with
                            ;; the type: `:terminal` says a run ended, `:terminal-frame`
                            ;; says what it ended SAYING -- which is where a parked run's
                            ;; interrupts live (RUN_FINISHED carrying
                            ;; outcome.interrupts), and what a reader needs to tell
                            ;; 'waiting on a human' from 'finished'. A later frame --
                            ;; another terminal included -- changes nothing (see this
                            ;; function's docstring).
                               (if (nil? (:terminal run))
                                 (assoc run :terminal (:type frame)
                                        :terminal-frame frame)
                                 run)
                               (update run :frames conj frame))
                             run))
                         found)
           found))))
   [] records))

(defn- open-runs
  "The runs a log opened and never closed, OLDEST FIRST, as {:run-id .. :last-frame
  .. :unanswered [toolCallId ..]} -- one entry per run, because a log is only
  complete when every run it opened has ended, and a process killed with two runs in
  flight leaves two open ones.

  ONLY AN OPEN RUN'S CALLS ARE REPORTED. A call with no result INSIDE a run that did
  close is the ordinary approval park -- its :run/interrupt ends the run and the
  answer arrives on the resume run -- so 'every call needs a result' would report
  the normal flow as damage and append a result to a call a human is still
  deciding.

  AND AN OPEN RUN IS NOT NECESSARILY AN ENDED ONE: this reads a FILE, so a run that
  is still going and a run whose process was killed look the same to it -- both are
  'open'. Nothing here can tell them apart, and the caller that is about to act on
  the answer has to ask the process for the half the file cannot give it
  (harness.edge.http/running? -- the live-runs registry, which is exactly this
  distinction kept where it can be known)."
  [records]
  (->> (runs records)
       (remove :terminal)
       (mapv (fn [{:keys [run-id frames]}]
               (let [answered (into #{} (keep #(when (= "TOOL_CALL_RESULT" (:type %))
                                                  (:toolCallId %)))
                                    frames)
                     calls    (distinct (keep #(when (= "TOOL_CALL_START" (:type %))
                                                 (:toolCallId %))
                                              frames))]
                 {:run-id     run-id
                  :last-frame (:type (peek frames))
                  :unanswered (vec (remove answered calls))})))))

(defn open-run
  "The oldest run a log opened and never closed, or nil -- {:run-id .. :last-frame
  .. :unanswered [toolCallId ..]}. The walk itself, and which calls count as
  unanswered, are `open-runs`' -- this is its head, for a caller that wants the one
  run a continuation is about to close.
  WHETHER SUCH A RUN IS DEAD IS NOT THIS FUNCTION'S QUESTION -- a log that ends
  without a terminal is equally what a run somebody is still answering looks like,
  and the caller that would REPAIR it asks the process registry first
  (`harness.edge.http/close-off-open-run!`; `ensure-complete!` below is only reached
  after that has been asked)."
  [records]
  (first (open-runs records)))

(defn- ensure-complete!
  "Refuse a log whose RUNS did not all finish.

  AN INPUT IS WHAT OPENS A RUN, AND ONLY AN INPUT. A log can hold lines that are
  not a run at all -- a `project/bound` audit line is written the moment a
  session is bound, and `provider/changed` can land outside any run -- and such a
  log is COMPLETE as it stands: it records no conversation because none has
  happened yet. Reading it must yield an empty message list, which is the honest
  answer for a session that exists but has never run. Judging by 'the log has
  records' would refuse exactly the state a fresh session is in, and the refusal
  would name a truncated run that never existed.

  So the walk is: an `input` line opens a run, a terminal frame CARRYING THAT RUN'S
  ID closes it, and every run must be closed by the end. That is `open-runs` seen
  the other way -- ONE walk, so the refusal and the repair cannot drift.

  Reading it as one question about the LAST input and the LAST terminal (which is
  what this asked before 2026-09-18) covers the case a 'peek at the last frame'
  rule misses -- the first run finished, a second input was recorded, and the
  process died before that run's first frame -- and MISSES its converse, which is
  the one a thread with several runs in flight produces: an earlier run that never
  ended, followed by a later one that did. That log's last line IS a RUN_FINISHED,
  its last frame belongs to the run nobody closed, and the repair used to close
  nothing at all: the refusal counted six inputs against five terminals while the
  repair asked about the sixth and found it terminated.

  Trailing `message` lines do NOT reopen a run: the returned tail is written after
  the terminal frame, so closing on the frame and letting the tail follow is what
  the writer actually does.

  Frames are the source of truth within a run: a log killed mid-run must not be
  mistaken for a shorter conversation."
  [records]
  (let [open (open-runs records)]
    (when-let [{:keys [run-id last-frame]} (first open)]
      (throw (ex-info (str "the log ends mid-run: run " run-id
                           " recorded no terminal frame"
                           (when (< 1 (count open))
                             (str ", and " (count open) " runs are open"))
                           "; its last recorded frame is "
                           (if last-frame (str last-frame) "absent")
                           ", not RUN_FINISHED or RUN_ERROR. Replaying a truncated log "
                           "would produce half a conversation, so it is refused.")
                      {:run-id run-id :last-frame last-frame})))))

(defn closing-frames
  "What a log that ends mid-run is MISSING, as a vector of {:run-id .. :last-frame
  .. :frames [frame ..]} -- one entry per run the log never closed, oldest first --
  or nil when the log ends where a log should.

  PURE: it says what is missing, and the writer is the edge, which owns the file.

  ONE ENTRY PER OPEN RUN, because the log is only readable once EVERY run it opened
  has ended: closing the first and leaving the rest would swap one refusal for the
  next. Each entry's frames belong to that entry's run, and the edge appends them
  under that run's id -- which is what lets the log tell the runs apart at all.

  A TOOL CALL THAT NEVER ANSWERED GETS A RESULT FIRST, and that is not tidiness:
  the rebuilt conversation is handed to the client as the NEXT run's history, and an
  assistant message whose tool_calls has no answering tool message is a shape the
  vendors refuse -- so closing the run without answering it would trade a refusal on
  the read side for a 400 on the next call. The sentence is the true one (the call
  was cut off and nothing was recorded) rather than an invented result.

  AND THE TERMINAL IS RUN_ERROR, NEVER RUN_FINISHED. A run that never reached a
  terminal frame did not finish; this log is an append-only record whose whole value
  is that its lines are true, and RUN_FINISHED would be the one line in it that lies.
  The message says what happened and who wrote the line, because the reader of a
  record is entitled to know a frame appeared without a run having emitted it."
  [records]
  (seq
   (mapv (fn [{:keys [run-id last-frame unanswered]}]
           (let [results (vec (map-indexed
                           (fn [i id]
                             {:type      "TOOL_CALL_RESULT"
                              :messageId (str run-id "-cut-" (inc i))
                              :toolCallId id
                              ;; THE SENTENCE IS THE KERNEL'S (`harness.kernel.frames`),
                              ;; because the run loop writes it too for the calls it
                              ;; abandons when somebody presses stop.
                              :content   (frames/cut-off-result)})
                           unanswered))]
             {:run-id     run-id
              :last-frame last-frame
              :frames     (conj results
                                {:type    "RUN_ERROR"
                                 :message (str "the run was cut off: this record ends "
                                               (if last-frame
                                                 (str "at " last-frame)
                                                 "before the run's first frame")
                                               " with no terminal frame, and it was closed"
                                               " when the session was continued")})}))
         (open-runs records))))

(defn- append-new
  "BASE with ENTRIES the conversation does not already hold, in order.

  THE ID IS THE IDENTITY, and that is what lets one fold read records written under
  both contracts. Until ticket 03 of `.scratch/sessions-live-on-the-server` a client
  sent the WHOLE conversation every run, so each input line repeats everything before
  it; from that ticket on, an input line carries only what its action added. Deduping by
  `:id` -- the same rule `harness.edge.sessions/append!` applies live, and the ids are
  the frames' own, so the assistant messages match too -- reads both the same way: what
  the line adds, it adds once. An entry with no id cannot be recognised and is kept."
  [base entries]
  (let [[out _] (reduce (fn [[out seen] e]
                          (if (and (:id e) (contains? seen (:id e)))
                            [out seen]
                            [(conj out e) (cond-> seen (:id e) (conj (:id e)))]))
                        [(vec base) (into #{} (keep :id) base)]
                        entries)]
    out))

(def ^:private conversation-sources
  "The `source`s that make a `message` row a SPEAKING PART of the conversation -- the
  entries a client is handed, in the record's order.

    \"client\"      the person sent it with an action (`harness.edge.http/entry-source`);
    \"injection\"   the session's own context entry, written into the array at the
                    conversation's birth;
    \"opening\"     one of the conversation's opening blocks (its instruction files and
                    its skills catalog), written the same way and the same day.

  THE THREE ARE THE CONVERSATION'S BIRTH AND ITS PEOPLE; everything else a `message` row
  can say is already somewhere else: `system-prompt` is the prompt the client never has,
  `model` and `tool` are what the run returned (the frames carry them), and `skill` and
  `job` are what the pre-LLM step derived for one run (the frames carry those as cards
  too, and the next run re-derives them rather than reading them back)."
  #{"client" "injection" "opening"})

(defn entries
  "Parsed log records -> the conversation's entries IN ORDER, each numbered:
  [{:seq N :message M} ..].

  THE NUMBER IS THE RECORD OFFSET OF THE LINE THE ENTRY ARRIVED IN -- 0 for a
  thread's first line, one more for each line after it. ADR 0003 decision 1 asks for
  a monotonic number per entry and decision 9 asks that it be REPLAYABLE; the line
  ordinal is both. It is not a field of the record (the format is frozen) and not an
  in-memory counter (a counter is only replayable if it is derived from the record --
  which the ordinal already is).

  WHICH LINE AN ENTRY ARRIVED IN IS THIS FOLD'S READING, and it is the same reading
  the edge mints live (`harness.edge.sessions/land!` reads the writer's answer), so
  the numbers a live session hands out and the numbers a replay reproduces agree:

    - an entry a MESSAGE ROW carried is numbered by ITS OWN line -- the row the writer
      wrote for it, one line per message since `.scratch/jsonl-two-kinds` 票 02. A message
      row also CLOSES the frame group before it, the way an `input` row used to: the run
      those frames belong to is over by the time the next run's rows begin, so their
      number is that run's terminal line and not this line;
    - an entry a run's FRAMES produced is numbered by the run's TERMINAL line, which
      is the line the edge attaches its landing callback to;
    - a run that never reached a terminal (a log that stops mid-run, read leniently)
      numbers its entries by the LAST line of the run, which is where the record
      stops -- a partial answer is allowed to move once the run ends.

  ENTRIES NUMBERED ALIKE ARRIVED TOGETHER: one run's frames. That is what makes a page cut
  at a group boundary unambiguously right, and it is why the window carries numbers rather
  than a slice of the message list (ticket 05's `tail` / `since` / `before`, and ticket 06's
  replica).

  WHAT THE CONVERSATION IS, now that both kinds of row are folded (票 02): the messages the
  CLIENT sent and the ones the conversation's BIRTH wrote for it -- `conversation-sources`
  below is exactly that list, and it is the only place the distinction lives -- plus every
  frame, which is how a run's own output is drawn. A `message` row that is NOT one of those
  is in the record and not in the conversation: the system prompt (`source` =
  `system-prompt`) because the client never has it, the model's return and a tool's answer
  (`model` / `tool`) because the frames carry them, and what the pre-LLM step derived
  (`skill` / `job`) because the frames carry those as cards too. One rule, in one place, is
  what keeps every surface a client is handed -- the window, the snapshot, the rebuild --
  saying the same thing.

  THE FOLD ITSELF IS `fold-frames`' AND UNCHANGED -- the messages this returns are
  exactly the ones the readers below have always answered; numbering them is the only
  addition. A message still enters once (`append-new` drops a repeat by id, which is
  how an old input line that carried the whole conversation reads the same as a new
  one that carried only what it added)."
  [records]
  (let [add   (fn [acc seq-n msgs]
                (update acc :entries into (map (fn [m] {:seq seq-n :message m}) msgs)))
        folded (fn [acc] (mapv :message (:entries acc)))
        ;; THE MESSAGE ROWS THAT ARE THE CONVERSATION'S OWN (see the docstring): what the
        ;; client sent, and what the birth put in on the session's behalf. Everything else
        ;; a `message` row can be is either not the client's to see (the prompt) or is
        ;; already drawn from the frames.
        ;; AN INJECTED ROW IS AN ENTRY ONLY WHEN IT HAS AN ID. The client's messages always
        ;; carry one (`harness.edge.http/entry-source`), and the conversation's birth wrote
        ;; its context and opening blocks with theirs -- while a block a later run
        ;; RE-DERIVED for itself (the same instruction files, the same context, read again)
        ;; is logged with no id, because nothing appended it to the conversation. That is
        ;; the whole difference between the two, and it is why `append!` and this fold can
        ;; dedupe by id.
        ours?  (fn [row]
                 (and (contains? conversation-sources (:source row))
                      (or (= "client" (:source row)) (some? (:id row)))))
        seen?  (fn [acc row] (and (some? (:id row)) (contains? (:seen acc) (:id row))))
        flush (fn [acc fallback]
                (let [new (frames/apply-frames (:pending acc))
                      at  (or (:after acc) fallback)]
                  (-> (if (seq new) (add acc at new) acc)
                      (assoc :pending [] :after nil))))]
    (:entries
     (flush
      (reduce (fn [acc [i row]]
                (let [value (payload row)]
                 (case (kind row)
                  ;; A MESSAGE ROW IS AN ENTRY when it is the conversation's own, and it is
                  ;; numbered by its OWN line (`land-at!` gives the live session the same
                  ;; number). A repeat is dropped by `:id` -- the client only ever appends,
                  ;; so a row whose name the conversation already holds is one it sent
                  ;; before (a retry, or a page re-sending what it holds); an entry with NO
                  ;; id cannot be recognised and is therefore kept, exactly as `append!`
                  ;; decides it.
                  "message" (let [acc (flush acc (max 0 (dec i)))]
                              (if (and (ours? row) (not (seen? acc row)))
                                (-> acc
                                    (add i [(cond-> value (:id row) (assoc :id (:id row)))])
                                    (update :seen conj (:id row)))
                                acc))
                  "event" (let [acc (update acc :pending conj value)]
                            ;; THE LAST TERMINAL OF THE GROUP WINS, not the first: the
                            ;; frames after a terminal belong to a line this reader
                            ;; would otherwise number short. (A run has one terminal;
                            ;; `frames/terminal?` is the same rule `runs` pairs by.)
                            (if (frames/terminal? value)
                              (assoc acc :after i)
                              acc))
                  acc)))
              {:entries [] :pending [] :after nil :seen #{}}
              (map-indexed vector records))
      (max 0 (dec (count records)))))))

(defn- fold-frames
  "Parsed log records -> the AG-UI message list they describe, WITHOUT judging
  whether the log is finished. THE MESSAGES OF `entries`, which is where the fold
  and its numbering live; this is the reader every message-only caller wants.

  THE RECORD IS READ IN FILE ORDER, and the order is the conversation's: an input line
  says what that action ADDED, and the frames that follow are what came of it, folded in
  one group per run. A run's frames are folded as a group rather than one by one because
  they are not independent: the text of a message lives in a START and its CONTENT
  frames, and a fold that saw only the content would have nothing to patch.

  WHICH FIELD OF THE INPUT LINE SAYS WHAT WAS ADDED DEPENDS ON WHO WROTE IT, and both
  are read: `:added` is what the edge writes since ticket 03 (the entries that actually
  entered the conversation -- a retried message enters nothing, and the birth context
  enters without the client ever having sent it), while `:messages` is what a client sent
  under the old contract, when that WAS the whole conversation. `append-new` dedupes by
  id, which is what makes the old shape read correctly: an old input line repeats
  everything before it, and the repetition is dropped rather than doubled.

  THE FOLD AND THE JUDGEMENT ARE TWO STEPS, which is why this is one function and the
  two public readers below are the other two. What a log CONTAINS and whether it is
  COMPLETE are different questions -- the first is answerable of a log that is still
  being written, the second is not -- and a reader that had to fold in order to
  refuse would answer the wrong one first."
  [records]
  ;; THE LAST RUN IS FLUSHED TOO, and that is not a formality: a log ENDS with the
  ;; frames of its last run, so the pending group is non-empty at the end of every
  ;; complete record. A fold that only flushed on the next input line would answer
  ;; every conversation with everything except the answer that was just given --
  ;; `entries` flushes it (see there).
  (mapv :message (entries records)))

(defn records->messages
  "Parsed log records -> the AG-UI message list they describe, REFUSING a log whose
  runs did not all finish (`ensure-complete!`).

  THE READER FOR A LOG THAT IS SUPPOSED TO BE DONE -- continuing a conversation,
  handing one back to a client, rebuilding for the eval reader. A truncated log here
  is a fact the caller must act on (close it off, or refuse the request) rather than
  fold quietly: that is the contract this whole namespace exists to keep, and the
  refusal names the run and its last frame so the caller can do something about it.

  A LOG THAT MAY STILL BE GROWING IS THE OTHER READER'S: `messages-so-far`. The
  difference between them is exactly this line, which is why the fold they share is
  `fold-frames` and not a copy each."
  [records]
  (ensure-complete! records)
  (fold-frames records))

(defn messages-so-far
  "Parsed log records -> what has been RECORDED of them so far, with no judgement
  about whether the conversation is finished.

  FOR A LOG SOMEBODY IS STILL WRITING. `ensure-complete!` refuses a run with no
  terminal frame -- rightly, for every reader that means to CONTINUE the
  conversation, because folding half a run and calling it the conversation would be
  a quiet lie. But a client that wants to show what is on the screen NOW has the
  opposite need: the frames are in the file the moment they are written (`log!` is a
  per-frame append), so 'what has arrived' is a question worth answering and the
  answer is allowed to be half a turn -- a half-written assistant message, a call
  with no result yet.

  NOT A LENIENT `records->messages`: it answers a different question. Whoever asks it
  is responsible for saying in the answer that it is partial (harness.edge.http's
  `sofar` verb does, with the registry's half of the fact -- see
  docs/architecture/home-and-storage.md). Nothing here writes: reading a log that is
  being appended to is a read."
  [records]
  (fold-frames records))

(defn lines->messages
  "A thread's raw log lines -> the AG-UI message list they describe."
  [lines]
  (records->messages (lines->records lines)))

(defn rebuild
  "What a client needs to RE-OWN its conversation: the AG-UI message list (every
  action's own message, folded in file order with every recorded frame of the run it
  started, reasoning and tool calls included) plus whatever context the log carries at
  its start, which since ticket 03 is also an ordinary message in that list. The client
  takes both into its next ordinary RunAgentInput -- the server holds no rebuilt state,
  exactly as it holds no conversation state ever.

  IT ALSO ANSWERS THE ENTRIES, NUMBERED (`entries` below): the same conversation with
  the record offset each entry arrived at. A caller that wants the window -- a client
  that refreshed, a page being cut -- needs those numbers, and they are the same numbers
  the live edge mints (`harness.edge.sessions/land!`), so the two readings of one record
  cannot disagree."
  [^java.io.File f]
  (let [records (lines->records (read-lines f))]
    ;; THE CONTEXT IS A MESSAGE AND NOT A FIELD: the conversation is born with the
    ;; session's context as its own entry (`ag/context-entry`, id "session-context"), so
    ;; `:messages` above already carries it and there is nothing separate to hand back.
    ;; The old place it lived -- a `:context` field on the first `input` row -- is gone with
    ;; that row (`.scratch/jsonl-two-kinds` 票 02), and a log written when it existed is
    ;; refused BY NAME by the reader rather than answered with a field this build no longer
    ;; writes (see `read-row`). [] is therefore the honest answer, and it is what a client
    ;; takes into its next RunAgentInput without changing anything.
    {:messages (records->messages records)
     :entries  (entries records)
     :context  []}))

(defn record-state
  "What a log's RECORD says about the conversation in it -- one of three, and
  deliberately nothing about whether anyone is still writing it:

    {:state :unfinished :open-runs [run-id ..]}   a run with no terminal frame
    {:state :parked     :interrupts [frame ..]}   the newest run ended on an interrupt
    {:state :settled}                             the newest run ended, and not on one

  THE FILE'S ANSWER, NOT THE PROCESS'S. `:unfinished` is the honest name for what a
  file can say: an input line with no terminal after it is a run still going OR a
  process that was killed, and nothing in the file distinguishes them. The caller
  that needs the difference asks the process (harness.edge.http/running?), which is
  why the state is named for what IS known rather than for the answer wanted --
  'running' here would be a guess dressed as a fact.

  THE NEWEST RUN DECIDES :parked, because a park is the state of the CONVERSATION
  and not of one run in it: the parked run ended on its interrupt, and if a resume
  followed, that resume is the newest run and its terminal is what the conversation
  is waiting on now.

  PUBLIC BECAUSE A WINDOW NEEDS IT TOO (ticket 06 of
  `.scratch/sessions-live-on-the-server`): a page route asked for a conversation this
  process does not hold answers its entries AND this state, and it has the records in
  hand already -- asking `sofar` for the same answer would fold the log a second time."
  [records]
  (let [open   (open-runs records)
        newest (last (runs records))
        tf     (:terminal-frame newest)]
    (cond
      (seq open) {:state :unfinished :open-runs (mapv :run-id open)}
      (= "interrupt" (get-in tf [:outcome :type]))
      {:state      :parked
       :interrupts (vec (get-in tf [:outcome :interrupts]))}
      :else {:state :settled})))

;; -------------------------------------------------------- compaction (model view)

(defn compaction-facts
  "RECORDS -> the compactions the record declares, in the order they happened, each as
  {:seq <the fact's own record offset> :shadowed [<record seqs>] :range {..} :tokens n
   :summary \"...\"}.

  A COMPACTION IS A FACT THE HARNESS WROTE ABOUT ITSELF (`context/compacted`, a CUSTOM
  frame), not a message: it changes what the MODEL is handed and nothing a person reads.
  `:shadowed` is the AUTHORITATIVE list of the surface nodes it replaces, IN SURFACE ORDER
  -- not a numeric interval, because the summary it stands for has a LARGER record seq than
  the range it replaces and yet sits where that range was."
  [records]
  (keep-indexed (fn [i row]
                  (when (= "context/compacted" (kind row))
                    (assoc (payload row) :seq i)))
                (vec records)))

(defn- compaction-summary
  "The message the model reads in a compacted range: one ordinary user message wrapping the
  summary text, so no consumer has to learn a new message shape."
  [text]
  {:role "user" :content (str "<compacted-summary>" text "</compacted-summary>")})

(defn- apply-compaction
  "Replace one range of SURFACE (a vector of {:id :message}) with its summary. The range is
  found BY MEMBERSHIP and walked in SURFACE ORDER -- the first node whose id is shadowed
  marks where the summary goes -- never by comparing ids as numbers."
  [surface {:keys [seq shadowed summary]}]
  (let [shadowed (set shadowed)
        start    (first (keep-indexed (fn [i node] (when (shadowed (:id node)) i)) surface))]
    (if (nil? start)
      surface
      (into (subvec surface 0 start)
            (cons {:id seq :message (compaction-summary summary)}
                  (remove #(shadowed (:id %)) (subvec surface start)))))))

(defn model-nodes
  "ENTRIES (replay/entries) + FACTS (compaction-facts) -> the MODEL-FACING SURFACE as
  NODES `{:id <record seq> :message M}`, in order, every compaction's summary standing where
  its range stood.

  THE IDS ARE THE POINT of this arity: `compacted-messages` is this minus the ids, and a
  compaction WRITER needs them -- `:shadowed` is a list of node ids, so a caller that only
  had the messages could not name a range.

  THE ORIGINAL ENTRIES ARE NOT TOUCHED -- `replay/entries` is unchanged, so the client keeps
  reading the originals (the model reads the summary, a person reads the source), and the
  record keeps every row. This is the one fold that shows summaries.

  A NODE IS AN ENTRY OR AN EARLIER SUMMARY: a summary is named by its FACT's own record seq,
  so a later compaction can shadow it -- and then `start` can be GREATER than `end`, which is
  exactly why the walk is by position and not by comparison."
  [entries facts]
  (let [base (mapv (fn [{:keys [seq message]}] {:id seq :message message}) entries)]
    (vec (reduce apply-compaction base (sort-by :seq facts)))))

(defn compacted-messages
  "NODES -> just the messages (`model-nodes` without the ids)."
  [entries facts]
  (mapv :message (model-nodes entries facts)))

(defn sofar
  "What has been recorded of a conversation SO FAR: the message list, the context, and
  the state the record is in (`record-state`, plus what the fold could see).

  FOR THE CLIENT THAT IS LOOKING AT A SESSION RIGHT NOW -- a page that just landed on
  a conversation its own runtime knows nothing about (a refresh: the run belongs to
  the process, not to the tab). It is the READ half of `rebuild`, and the difference
  between them is one line: rebuild REFUSES a log whose run has not ended, because
  handing half a conversation back as 'yours now' would be a lie; this one returns
  what is there and says it is `:unfinished`.

  THE STRICT READER IS STILL USED FOR A LOG THAT IS DONE -- `records->messages`, the
  same call rebuild makes -- so a settled conversation cannot read differently through
  the two doors; the lenient fold is only for the case that has no strict answer.

  NOTHING HERE WRITES, and that is a requirement rather than a happy accident: this is
  what a client POLLS while a run is being written, and a read path that repaired the
  file it was reading would make every poll a write (see the flag: a run this process
  is answering is not a truncated log).

  `:entries` IS THE SAME CONVERSATION WITH ITS NUMBERS, and it is what lets a session be
  born already able to answer a window: the session keeps the entries, and the numbers
  are the record's own line offsets rather than anything this process remembers. The
  fold is the same one `:messages` comes from (`entries`), so the two cannot drift."
  [^java.io.File f]
  (let [records (lines->records (read-lines f))
        es      (entries records)
        state   (record-state records)
        open?   (= :unfinished (:state state))]
    {:messages   (if open?
                   (messages-so-far records)
                   (records->messages records))
     :entries    es
     :compactions (compaction-facts records)
     :context    []
     :state      (:state state)
     :open-runs  (:open-runs state)
     :interrupts (:interrupts state)}))

(defn- thread-id-of
  "The session a log FILE belongs to: its stem. The writer names the file through
  harness.infra.home/sanitize, so reading the name back is the same 'what is on disk is
  what there is' stance `threads` takes -- asking the store instead would answer a
  different question, and would fail for a log that was moved by hand.

  This exists for the system message's sake. Replay has no hook sink, so assembly
  appends nothing here -- but the DERIVATION is what matters: whoever assembles a
  system message recomputes it from live facts for THIS thread rather than reading
  a copy out of the log, and that argument has to name the thread even when the
  answer is currently 'nothing to append'."
  [^java.io.File f]
  (let [n (.getName f)]
    (subs n 0 (- (count n) (count ".jsonl")))))

(defn history
  "A log FILE -> the provider-shaped messages you can hand straight to
  loop/run-chan.

  This is the whole point of the namespace: after the process that wrote the log is
  gone, this rebuilds the conversation that was in flight, reasoning and tool results
  included, and it comes back in exactly the shape the model expects.

  TWO DIALECTS MEET HERE AND ONLY ONE COMES OUT: an ENTRY's row holds the message the
  provider was handed (`.scratch/jsonl-two-kinds` 票 02), while a message folded out of a
  run's FRAMES is AG-UI's spelling -- and every frame-derived message is translated, entry
  rows passing through untouched (`ag/provider-messages` tells them apart). The system
  message is then assembled on top, which is the half a record cannot answer.

  THE FOLD'S INPUT IS A CONVERSATION, whichever way each message got here: `entries`
  stamps every message with the id a client draws it by, and a provider array has no such
  field -- which `provider-messages` itself takes care of, so nothing has to be handed to
  it pre-cleaned (it used to be, here and only here; the LIVE path read the same folded
  entries and did not know to -- see `ag-ui/absorbed`).

  The system message is ASSEMBLED, not read out of the log: prompt.md's frozen
  opening plus whatever the SystemPrompt hooks append for the thread the file
  names. Nothing is appended here, because replay has no hook sink (see
  harness.cap.system-prompt/assemble) -- so what comes back is the frozen opening, byte
  for byte, which is exactly what the tests below pin."
  [^java.io.File f]
  (let [records (lines->records (read-lines f))]
    (ag/provider-array (ag/provider-messages (records->messages records))
                       (system-prompt/assemble (thread-id-of f)))))

(defn- logs-under
  "Every *.jsonl file at any depth under DIR, in no particular order. The tree is
  what 'the logs' means now: one workspace per project plus a reserved one, so a
  listing is a walk rather than a single directory scan -- and it stays a
  FILESYSTEM fact rather than a database query, because a log that was moved by
  hand is still a log."
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".jsonl")))))

(defn threads
  "The conversations a log TREE holds: one entry per *.jsonl file --
  {:thread-id .. :last-activity <epoch millis> :bytes <file size>} -- newest
  first. DIR is the tree's root (harness.infra.home/projects-dir); the walk is
  recursive, so its workspaces are covered without this namespace knowing how
  they are named. The thread-id is the FILE's stem, and this listing speaks
  filenames.

  An empty or MISSING tree is an empty list, not an error -- no logs yet is the
  normal state of a fresh install. The listing says nothing about whether a log
  is complete; rebuilding a truncated one is refused, and the refusal names why.
  Nor does it say whether a stem is UNIQUE: one conversation whose binding moved
  between runs has a log in each workspace, and that is what `locate` refuses."
  [dir]
  (->> (logs-under dir)
       (mapv (fn [^java.io.File f]
               (let [name (.getName f)]
                 {:thread-id     (subs name 0 (- (count name) (count ".jsonl")))
                  :last-activity (.lastModified f)
                  :bytes         (.length f)})))
       (sort-by :last-activity >)
       vec))

(defn logs-for
  "EVERY log file under DIR whose name is the one a STEM gets: none, one, or
  several. A stem comes from `threads`, so it is already a file stem; a full path
  works too, since sanitizing it leaves only the final segment changed -- and
  sanitize is the one rule for turning an id into a filename, so it is used rather
  than restated.

  The whole vector is the answer, rather than one file, because the callers
  disagree about what to do with more than one and neither may guess: rebuilding
  refuses (no half is the conversation), and a log move refuses too (whichever
  file it carried, the other would be left behind as a second half). Returning the
  list is what lets each say so in its own words instead of one caller
  re-interpreting the other's refusal.

  None is an ordinary answer: a session that has never run has no log."
  [dir stem]
  (let [want (str (home/sanitize stem) ".jsonl")]
    (->> (logs-under dir)
         (filter #(= want (.getName ^java.io.File %)))
         (sort-by #(str %))
         vec)))

(defn find-log
  "THE log file a STEM names anywhere under DIR -- nil when there is none.
  Several is a NAMED failure: a conversation whose log landed in two workspaces is
  not one this function can hand back (see logs-for)."
  [dir stem]
  (let [found (logs-for dir stem)]
    (case (count found)
      0 nil
      1 (first found)
      (throw (ex-info (str "thread " (pr-str stem) " has " (count found)
                           " logs, in different workspaces: "
                           (str/join ", " (map #(.getAbsolutePath ^java.io.File %) found))
                           ". Neither one is the whole of it")
                      {:thread-id stem
                       :paths (mapv #(.getAbsolutePath ^java.io.File %) found)})))))

(defn locate
  "The log file a STEM names, found anywhere under DIR -- the same answer as
  find-log, with 'nothing found' made a failure too, for the callers that must be
  handed a file:

    one match   that file
    none        a NAMED failure saying so, with where it looked
    several     a NAMED failure naming every file, and what to do about it

  A rebuild must not guess and must not invent an empty conversation: rebuilding
  from nothing would silently drop a whole history, which is why 'nothing found'
  is an exception here and an answer in find-log."
  [dir stem]
  (if-some [found (find-log dir stem)]
    found
    (throw (ex-info (str "no log for thread " (pr-str stem) " under "
                         (.getAbsolutePath (io/file dir))
                         " -- nothing there is named "
                         (str (home/sanitize stem) ".jsonl"))
                    {:thread-id stem
                     :path (str (io/file dir (str (home/sanitize stem) ".jsonl")))}))))

(defn resume!
  "Rebuild a thread from its log, append TEXT as a new user turn, and run the agent on.
  Returns the AG-UI frames the continuation produced.

  The rebuilt history is exactly what the server would have recomputed on the next run,
  so this continues the conversation rather than starting a fresh one -- which is the
  whole reason the log is worth keeping.

  It does NOT append to the log. The writer lives at the http edge, and this namespace
  is deliberately the read side only; a resumed conversation therefore leaves no new
  trace on disk. An author-side action, not a run path."
  ([f thread-id text] (resume! f thread-id text (providers/effective-provider thread-id)))
  ([f thread-id text provider]
     (let [run-id (str (java.util.UUID/randomUUID))
         emit   (ag/outbound thread-id run-id)
         frames (atom [])
         events (loop/run-chan provider
                               (conj (history f) {:role "user" :content text})
                               {:thread-id thread-id
                                ;; the same pre-LLM step the edge passes, so a
                                ;; rebuilt conversation carries its skill bodies
                                :before-llm project/before-llm})]
     (loop []
       (when-let [event (async/<!! events)]
         (when-not (= :run/done (:type event))
           (doseq [frame (emit event)] (swap! frames conj frame))
           (recur))))
     @frames)))
