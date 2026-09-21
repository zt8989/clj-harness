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

(defn lines->records
  "Parse a log's lines. A line that will not parse is a hard failure that names the
  line: a log killed mid-write must not be mistaken for a shorter conversation."
  [lines]
  (mapv (fn [idx line]
          (try
            (json/read-str line :key-fn keyword)
            (catch Exception e
              (throw (ex-info (str "line " (inc idx) " of the log is not valid JSON ("
                                   (ex-message e) ") -- the log is truncated or corrupt")
                              {:line (inc idx)})))))
        (range)
        lines))

(defn- runs
  "Every run a log holds, in the order its `input` opened it: {:run-id .. :frames
  [payload ..] :terminal <frame type or nil>}.

  A RUN IS PAIRED BY IDENTITY, NEVER BY POSITION. An input line carries the id of
  the run it opened, and every frame line carries the id of the run that emitted
  it -- the pairing the writer at the edge already keeps, which is why the frames
  appended to close a run land under THAT run's id. So 'which run never ended' is a
  question asked of ids, and the positional answers -- 'the last input', 'the last
  terminal frame' -- stop being answers the moment one thread has two runs in
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
   (fn [found {:keys [kind runId payload]}]
     (case kind
      "input" (if (some #(= runId (:run-id %)) found)
                 found
                 (conj found {:run-id runId :frames [] :terminal nil :terminal-frame nil}))
      "event" (mapv (fn [run]
                       (if (= runId (:run-id run))
                         (if (frames/terminal? payload)
                           ;; THE FIRST TERMINAL ENDS THE RUN, and the FRAME goes with
                           ;; the type: `:terminal` says a run ended, `:terminal-frame`
                           ;; says what it ended SAYING -- which is where a parked run's
                           ;; interrupts live (RUN_FINISHED carrying
                           ;; outcome.interrupts), and what a reader needs to tell
                           ;; 'waiting on a human' from 'finished'. A later frame --
                           ;; another terminal included -- changes nothing (see this
                           ;; function's docstring).
                           (if (nil? (:terminal run))
                             (assoc run :terminal (:type payload)
                                    :terminal-frame payload)
                             run)
                           (update run :frames conj payload))
                         run))
                     found)
       found))
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
  run a continuation is about to close."
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
                              :content   (str "the run was cut off before this call"
                                              " returned; no result was recorded")})
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

    - an entry an `input` line ADDED is numbered by THAT line -- the action's own
      line, the first line of its run;
    - an entry a run's FRAMES produced is numbered by the run's TERMINAL line, which
      is the line the edge attaches its landing callback to;
    - a run that never reached a terminal (a log that stops mid-run, read leniently)
      numbers its entries by the LAST line of the run, which is where the record
      stops -- a partial answer is allowed to move once the run ends.

  ENTRIES NUMBERED ALIKE ARRIVED TOGETHER: one action's entries, or one run's. That is
  what makes a page cut at a group boundary unambiguously right, and it is why the
  window carries numbers rather than a slice of the message list (ticket 05's
  `tail` / `since` / `before`, and ticket 06's replica).

  THE FOLD ITSELF IS `fold-frames`' AND UNCHANGED -- the messages this returns are
  exactly the ones the readers below have always answered; numbering them is the only
  addition. A message still enters once (`append-new` drops a repeat by id, which is
  how an old input line that carried the whole conversation reads the same as a new
  one that carried only what it added)."
  [records]
  (let [add   (fn [acc seq-n msgs]
                (update acc :entries into (map (fn [m] {:seq seq-n :message m}) msgs)))
        folded (fn [acc] (mapv :message (:entries acc)))
        flush (fn [acc fallback]
                (let [new (frames/apply-frames (:pending acc))
                      at  (or (:after acc) fallback)]
                  (-> (if (seq new) (add acc at new) acc)
                      (assoc :pending [] :after nil))))]
    (:entries
     (flush
      (reduce (fn [acc [i {:keys [kind payload]}]]
                (case kind
                  "input" ;; `append-new` answers BASE AND THE NEW ONES TOGETHER (the
                          ;; fold replaces its message list with it); what ENTERED is the
                          ;; tail of that, and a fold that appended the whole answer
                          ;; would put every entry in twice.
                          (let [acc  (flush acc (max 0 (dec i)))
                                base (folded acc)
                                all  (vec (append-new base (or (:added payload)
                                                               (:messages payload))))]
                            (add acc i (subvec all (count base))))
                  "event" (let [acc (update acc :pending conj payload)]
                            ;; THE LAST TERMINAL OF THE GROUP WINS, not the first: the
                            ;; frames after a terminal belong to a line this reader
                            ;; would otherwise number short. (A run has one terminal;
                            ;; `frames/terminal?` is the same rule `runs` pairs by.)
                            (if (frames/terminal? payload)
                              (assoc acc :after i)
                              acc))
                  acc))
              {:entries [] :pending [] :after nil}
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

(defn- first-input
  "The first input line's payload -- where a log keeps the run CONTEXT from before
  ticket 03 (see `rebuild`). The seed messages used to come from here too, when the first
  line was the whole conversation and the frames were everything after it; the fold reads
  every line now, so nothing needs this but the context."
  [records]
  (some->> records
           (filter #(= "input" (:kind %)))
           first
           :payload))

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
  (let [records (lines->records (read-lines f))
        input   (first-input records)]
    ;; THE CONTEXT IS A MESSAGE NOW, NOT A FIELD (ticket 03): the conversation is born
    ;; with the session's context as its own entry (`ag/context-entry`, id
    ;; "session-context"), so `:messages` above already carries it and there is nothing
    ;; separate to hand back. What is still read here is the OLD shape: a log whose
    ;; first input line names a context -- every log written before this ticket, and the
    ;; rebuild tool's own fixtures -- keeps answering with it, because a reader that
    ;; dropped it would silently change what those conversations continue from. An
    ;; answer of [] is the honest answer for a log that says nothing about context.
    {:messages (records->messages records)
     :entries  (entries records)
     :context  (vec (:context input))}))

(defn- record-state
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
  is waiting on now."
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
        input   (first-input records)
        state   (record-state records)
        open?   (= :unfinished (:state state))]
    {:messages   (if open?
                   (messages-so-far records)
                   (records->messages records))
     :entries    (entries records)
     :context    (:context input)
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
  included, and it comes back in exactly the shape the model expects -- the reasoning
  folded onto its assistant message, calls in the provider's casing.

  The system message is ASSEMBLED, not read out of the log: prompt.md's frozen
  opening plus whatever the SystemPrompt hooks append for the thread the file
  names. Nothing is appended here, because replay has no hook sink (see
  harness.cap.system-prompt/assemble) -- so what comes back is the frozen opening, byte
  for byte, which is exactly what the tests below pin."
  [^java.io.File f]
  (let [records (lines->records (read-lines f))]
    (ag/inbound (records->messages records)
                (system-prompt/assemble (thread-id-of f))
                (:context (first-input records)))))

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
