(ns harness.edge.sessions
  "The server's LIVE conversations: thread-id -> the entries the next run continues
  from. This is the table ADR 0002 decides for
  (docs/adr/0002-sessions-live-on-the-server.md): the conversation lives HERE, and the
  browser stops being its author.

  A session is born the first time somebody asks for it -- the page opening it, or an
  action aimed at it -- and it is built ONCE from the record, by `harness.edge.replay`.
  Every later question is answered from memory. After `idle-ttl-ms` of nobody asking,
  it is put away again; the build is cheap enough to repeat because it is a fold over
  a file, and the file is the thing that outlives this process.

  WHAT IT HOLDS, AND WHAT IT DELIBERATELY DOES NOT. It holds the CONVERSATION and
  nothing the run derives for itself. Two things are absent on purpose:

    - THE SYSTEM MESSAGE. It is assembled per run (`harness.cap.system-prompt`: the
      frozen opening plus what the `SystemPrompt` hooks append), and a copy kept here
      would be a sentence that stopped being true the moment a binding moved.
    - THE DERIVED INJECTIONS -- a skill body, a job's ending. They are recomputed from
      the conversation on every run (`harness.cap.project/before-llm`), so a copy kept
      here would be a copy that stopped matching what it was derived from.

  THE SESSION'S OPENING IS NOT ON THAT LIST, and that is the change of 2026-09-21
  (`.scratch/session-opening`). The instruction files and the skills catalog are part of
  what a conversation is BORN with, so they enter ONCE, here, through
  `harness.edge.http/run-agent!` -- and every later run continues from them as history
  instead of being handed them again. They used to be re-read and re-appended behind the
  conversation on every run, which put them after the answer the run itself was about to
  write; that is why they moved, and `harness.edge.ag-ui/opening-entries` is where they
  are shaped. THE CONSEQUENCE TO KNOW ABOUT: an edited AGENTS.md takes effect at the NEXT
  opening -- a new session, or a compaction that rebuilds one -- rather than mid
  conversation. That is deliberate: the opening is an event, not a per-run splice.

  SO THIS TABLE DOES NOT MAKE THE DERIVED INJECTIONS STOP BEING RECOMPUTED. It takes the
  CLIENT out of the loop, which is the half that was never a decision.

  WHAT IT HOLDS IS THE VIEW A CLIENT DRAWS, numbers and all. Each entry is the message
  plus the arrival it came in (`:group`) and the RECORD OFFSET of the line it arrived in
  (`:seq`, nil until that line is on disk) -- ticket 05 of
  `.scratch/sessions-live-on-the-server`, and the unit the feed's window is cut in.

  THE CARDS ARE PART OF IT, AND THE MODEL VIEW IS DERIVED FROM IT. A log carries a
  message per injected-context frame so a rebuilt conversation can draw the cards again
  (`harness.kernel.frames/apply-frames`); those messages are for the screen and the model
  never had them. The table used to drop them at birth, which made memory and the record
  two different conversations -- exactly the drift the feed was built to end -- so the
  cards are KEPT here (they are what the page draws, and what `sofar` answers) and the
  model view is `model-view` of them, computed where a run asks for its history
  (`messages`). One conversation, two readings of it, and the reading that must not see a
  `data` part (`harness.edge.ag-ui/provider-part` refuses one by name) is the one that
  filters.

  THE BUILD IS THE ONLY READ OF DISK. Everything after it arrives through `append!` --
  the entries a client's action carried, with an entry the conversation already holds
  dropped by id -- and through `settle!`, the run's own frames folded at the moment the
  run ends. That pair is what makes this table the AUTHORITY rather than a cache: a run
  continues from here, not from a file, and not from what the browser remembers.

  ONE ATOM, ONE TABLE. The registry is the single home of every fact about a live
  session -- its entries, when it was last asked for, and which runs of it are going.
  That is deliberate: `sweep!` must not put away a session a run is in the middle of,
  and if 'a run is going' lived anywhere else the two would race. It is also why the
  run set is a SET: two runs of one session are refused at the door
  (`harness.edge.http/refuse-second-run!`), and until that refusal existed the table
  still had to be correct about the one fact it owns -- that the session may not be put
  away while ANY of them is going.

  ONE ROW OUTSIDE THIS TABLE, AND IT IS THE CLAIM. A session's lifetime here IS a
  claim on the conversation (`harness.cap.claims`): it is taken as the entry is built
  and handed back as the entry is put away, so no other process serves a conversation
  this one is serving -- and an idle conversation does not hold a claim forever either.
  That is ticket 04 of `.scratch/sessions-live-on-the-server`, and it is the reason
  this namespace requires a capability at all. THE CLAIM'S TOKEN IS ALSO THE FEED'S
  GENERATION (ticket 05): a window is only meaningful while the same process, on the
  same claim, is serving the conversation, so the token that names the claim names the
  window.

  AND IT SAYS WHEN SOMETHING CHANGED. `watch!` is the doorbell a connected replica
  rings on -- it is rung by the writer's landing, not by the entries going in, because
  what a window addresses is the RECORD's numbering. There is no subscription state
  here: a watcher is a fn, it carries no cursor and no identity, and the connection it
  belongs to says 'since N' every time it reads (ADR 0003 decision 7).

  Nothing else here writes: no jsonl, no process log, nothing under the log tree. That
  is asserted, not assumed (see the test)."
  (:require [harness.cap.claims :as claims]
            [harness.edge.replay :as replay]
            [harness.infra.home :as home]
            [harness.kernel.frames :as frames])
  (:import (java.util.concurrent Executors ScheduledExecutorService ThreadFactory
                         TimeUnit)))

(def idle-ttl-ms
  "How long a session may go untouched before it is put away.

  THIRTY SECONDS BECAUSE THAT IS A PERSON'S PAUSE, not a measurement of anything: long
  enough that scrolling away and back, or thinking between two messages, does not cost
  a rebuild, and short enough that a session nobody is looking at is not held. The
  number is a judgement and is written down as one -- what matters is that there IS a
  number, because 'a while' would make the bound unsayable."
  30000)

(def max-running
  "How many sessions this process will have RUNNING at once, across all of them.

  THIS IS THE ONE THAT BOUNDS MEMORY, and the idle rule is not a substitute for it: a
  session with a run going cannot be put away, so the only thing standing between this
  process and every session in the tree is this number. It is a guess -- the work here
  is I/O-bound and a run is mostly waiting on a vendor -- and it is named so that it can
  be argued with instead of discovered. Over the line, `run-started!` refuses BY NAME
  rather than queueing: a queued run would be a client waiting on a spinner with nothing
  to read."
  8)

(def sweep-interval-ms
  "How often the sweeper looks. Cheap (it walks a map) and deliberately much shorter
  than the TTL, so a session goes away within a few seconds of becoming eligible rather
  than up to a TTL late."
  5000)

(def page-size
  "How many entries a feed page holds.

  FIFTY, BECAUSE THE REFERENCE IMPLEMENTATION SAYS FIFTY (`events.open({ maxMessages:
  50 })` in the client this repo mirrors), and because a screenful is a few dozen turns
  -- so it is a JUDGEMENT, not a measurement: nobody here has timed a page against a
  conversation. What matters is that there IS a number and that it is written down
  (`.scratch/sessions-live-on-the-server` ticket 05, judgement 2): 'a page' would make
  the cost of opening a conversation unsayable, and the whole point of the window is
  that opening one costs a bound instead of its length."
  50)

;; ------------------------------------------------------------------- the table

(defonce ^:private registry
  ;; thread-id -> {:entries [{:group <run-id> :seq <line-offset|nil> :message <ag-ui>} ..]
  ;;               :context [..] :state :unfinished|:parked|:settled|nil
  ;;               :touched-at <ms> :runs #{run-id ..} :claim <token>}
  ;;
  ;; `:entries` IS IN CONVERSATION ORDER and it is the one structure: `messages` is a
  ;; reading of it, the window is a slice of it, and `settle!`/`append!` are the only
  ;; things that add to it. `:seq` is nil while the line the entry arrived in is still
  ;; in the writer's queue (`land!` fills it in from the writer's answer).
  (atom {}))

(defonce ^:private unflushed?
  ;; A session whose bytes are not all on disk may not be put away, because putting it
  ;; away would mean rebuilding from a record that is behind it -- and the difference
  ;; would be silent. TICKET 02 OWNS THE ANSWER (`harness.edge.record/pending?`), and
  ;; this is the seam it reaches the table through.
  (atom (constantly false)))

(defn watch-unflushed!
  "Say which sessions have bytes that have not reached the record. F is handed a
  thread-id; `sweep!` will not put away a session it answers true for.

  A seam rather than a field because the answer belongs to the writer
  (`harness.edge.record`), and this namespace must not grow a second opinion about
  what has been written."
  [f]
  (reset! unflushed? f))

;; ------------------------------------------------------------------ the doorbell

(defonce ^:private watchers
  ;; thread-id -> #{fn}. SEE `watch!`: a notification, not a subscription.
  (atom {}))

(defn watch!
  "Call F -- (fn [thread-id event]) -- when THREAD-ID changes. Answers F, so a caller
  can hand the same value to `unwatch!`.

  IT IS A DOORBELL, NOT A SUBSCRIPTION, and the difference is ADR 0003 decision 7:
  what a watcher may not be is a place where the server remembers WHAT A CLIENT HAS.
  It carries no cursor, no identity, and no per-client state -- the connected replica
  that rings it says 'since N' on every read, so the truth about what it holds lives
  on its side of the wire and a missed ring costs one delayed read, never a wrong one.

  EVENTS: {:kind :entries} -- come and read, something about the session changed (an
  entry arrived, a line landed, a run of it started or finished); {:kind :gone :reason
  ..} -- the session was put away or its claim changed hands, and the window built on it
  is over. `:reason` is a value the reader can be told (`harness.edge.http`'s feed sends
  it as the last frame), because a stream that just stops leaves a replica believing
  it has everything."
  [thread-id f]
  (swap! watchers update (str thread-id) (fnil conj #{}) f)
  f)

(defn unwatch!
  "Stop calling F for THREAD-ID."
  [thread-id f]
  (swap! watchers update (str thread-id) disj f)
  nil)

(defn- ring!
  "Tell THREAD-ID's watchers that EVENT happened. A watcher that throws is not allowed
  to stop the others, or to reach the caller: this runs on the writer's consumer thread
  (the landing) as well as on request threads, and a doorbell that can break the thing
  ringing it is worse than a missed one -- the next read catches up anyway."
  [thread-id event]
  (doseq [f (get @watchers (str thread-id))]
    (try (f (str thread-id) event) (catch Throwable _ nil)))
  nil)

;; ------------------------------------------------------------------- the views

(defn model-view
  "MESSAGES -> the conversation AS A PROVIDER MAY BE HANDED IT: the cards taken out.

  A card is a message (or part) the record carries so the screen can draw it again; it
  is not something the model ever read. `harness.edge.ag-ui/provider-part` refuses a
  `data` part BY NAME, and it should keep refusing: this is the function that makes sure
  one is never offered.

  DROPPING A WHOLE MESSAGE IS RIGHT because a card is usually its own message
  (`harness.kernel.frames/apply-frames` makes one per frame), and a message left with no
  parts at all is dropped with it rather than sent as an empty turn.

  AN OPENING ENTRY IS THE ONE MESSAGE THAT CARRIES BOTH (`harness.edge.ag-ui/opening-entries`):
  the `data` half is the card the page draws, the `text` half is what the model reads. So
  removing the `data` part leaves exactly what the model is owed -- which is why the
  session's opening needs no case of its own and no second reader anywhere else.

  PUBLIC BECAUSE A RUN NEEDS IT FOR WHAT IT IS ABOUT TO ADD, not only for what the
  session already holds: `append!` answers with the entries as the RECORD keeps them --
  cards and all, they are what the log needs -- so the edge asks for this view of the
  list it is about to hand over (`harness.edge.http/run-agent!`). One decision, asked
  twice."
  [messages]
  (into []
        (keep (fn [m]
                (let [c (:content m)]
                  (if (and (sequential? c) (some #(= "data" (:type %)) c))
                    (let [kept (into [] (remove #(= "data" (:type %))) c)]
                      (when (seq kept) (assoc m :content kept)))
                    m))))
        messages))

(defn- build
  "THREAD-ID's conversation as the record has it: {:entries .. :context .. :state ..}.
  Empty when the thread has never run.

  `find-log` answers nil rather than refusing, and that nil is the right answer HERE:
  a session that has never run has nothing recorded, and a page opening it must get an
  empty conversation rather than an error. A session that HAS run and whose log has gone
  missing is the different, uglier case, and telling the two apart needs the session row
  -- which is ticket 03's 'new session' action, not this fold's business.

  `sofar` RATHER THAN `rebuild`, and the difference is the whole reason both exist: a
  rebuild refuses a log whose run has not ended, and a session is born exactly when
  somebody looks at it -- which after a process restart is a log that ends mid-run. The
  door that answers that is the one that says so, and it still uses the strict reader
  for a log that IS finished.

  THE ENTRIES COME WITH THEIR NUMBERS (`harness.edge.replay/entries`), which is the
  whole reason the session can answer a feed page the moment it is born: the numbers are
  the record's own line offsets, so a client that was talking to a previous process can
  keep them."
  [thread-id]
  (if-some [f (replay/find-log (home/projects-dir) thread-id)]
    (let [{:keys [entries context state]} (replay/sofar f)]
      {:entries (vec entries) :context (vec context) :state state})
    {:entries [] :context [] :state nil}))

(defn- count-running [m]
  (count (filter #(seq (:runs %)) (vals m))))

(defn- ensure-session
  "THREAD-ID's entry, building it if this is the first ask.

  THE BUILD HAPPENS OUTSIDE THE SWAP, and that is a rule rather than a preference:
  `swap!` may call its function more than once under contention, and a second read of
  the log would be a second answer to a question that has one. So a racing pair both
  build and one is discarded -- the bytes are equal, so which one won cannot be told,
  which is the property that makes discarding one safe.

  THE CLAIM IS NOT DISCARDABLE, though, which is why it is taken BEFORE the swap: a
  build that loses the race must not leave a claim behind it either. Each take mints a
  token of its own (see `harness.cap.claims/release!` on why), so the row can end up
  carrying the LOSER's token while the entry that won holds a different one; the
  losing branch below is where that is straightened out (`hand-over!`), because the
  loser is the one party that can see both tokens.

  A CLAIM ANOTHER LIVE PROCESS HOLDS THROWS, by name, out of here. That is not the
  path a client normally sees -- `harness.edge.http/handle-run` asks the same question
  at the door, so the answer is a status rather than a half-written stream -- but it is
  the invariant this table exists for, and it has to hold wherever a session is asked
  for, not only at that door."
  [thread-id]
  (if-some [e (get @registry thread-id)]
    e
    (let [claim  (claims/take! thread-id)
          built  (merge {:touched-at (System/currentTimeMillis)
                         :runs       #{}
                         :claim      (:token claim)}
                        (build thread-id))
          [before after] (swap-vals! registry
                                     (fn [m] (if (contains? m thread-id) m (assoc m thread-id built))))]
      (if-some [won (get before thread-id)]
        (do (claims/hand-over! thread-id (:token claim) (:claim won))
            (get after thread-id))
        (get after thread-id)))))

(defn touch!
  "Say that somebody is dealing with THREAD-ID now -- which is both 'it exists' and
  'the idle clock starts over'."
  [thread-id]
  (let [id (str thread-id)]
    (ensure-session id)
    (swap! registry update id assoc :touched-at (System/currentTimeMillis))
    nil))

(defn live-entry
  "THREAD-ID's entry, or nil when this process is not holding the conversation --
  A LOOKUP, NOT A BIRTH. The read routes ask this one: a GET that created a session
  would take a claim and rebuild a conversation on behalf of somebody who only wanted
  to look, and `harness.edge.http/sofar-get` in particular must be able to answer
  'nobody here is holding this' without making it true."
  [thread-id]
  (get @registry (str thread-id)))

(defn generation
  "The name of the window a reader of THREAD-ID is looking at: the claim token of the
  session holding it, or nil when nobody here holds it.

  IT IS THE CLAIM'S TOKEN BECAUSE THAT IS THE FACT (ticket 04, and ADR 0003 decision
  6): a window is only meaningful while the SAME claim serves the conversation -- put
  away, taken over, rebuilt in another process, and every number in the reader's hands
  is about a session that no longer exists. The claim already moves its token on every
  take and loses its row on every release, so the generation inherits both properties
  rather than inventing a second thing to keep in step."
  [thread-id]
  (:claim (live-entry thread-id)))

(defn running?
  "Is a run of THREAD-ID alive in THIS PROCESS right now?

  THE FACT THAT IS NOT IN THE RECORD, and the reason it is asked here: an input line
  whose run never terminated is either a run still going or a process that died
  mid-flight. Every reader that has to choose (the sidebar row, the composer's gate,
  the read side's 'may I close this off') asks this instead of inferring from the
  file.

  IT IS THE SESSION TABLE'S OWN FACT NOW (ticket 05, discharging ticket 01's correction
  3 in `.scratch/sessions-live-on-the-server`): the edge used to keep a second registry
  of live runs, which meant 'this process is answering it' had two homes and two ways to
  be wrong. The run ids here are the same ones `run-started!` pinned, so the answer has
  one owner."

  [thread-id]
  (boolean (seq (:runs (live-entry thread-id)))))

(defn running-run-id
  "The run id THREAD-ID's live run was registered under, or nil. FOR A REFUSAL that has
  to say what is in the way: the client that got a 409 did not choose its run id (the
  server mints it), so the only useful name here is the one already going."
  [thread-id]
  (first (:runs (live-entry thread-id))))

;; -------------------------------------------------------------- the conversation

(defn messages
  "THREAD-ID's conversation AS A RUN CONTINUES FROM IT: the entries a provider may be
  handed, which is the entries minus the cards (`model-view`).

  GET-OR-CREATE, and it counts as somebody dealing with the session -- the page drawing
  it is what keeps it alive."
  [thread-id]
  (let [id (str thread-id)]
    (touch! id)
    (model-view (mapv :message (:entries (get @registry id))))))

(defn- as-sent
  "ENTRIES as a reader sees them: the record offset and the message, without the run
  that carried them (`:group` is how this table fills a number in, not something a
  client can act on). ONE SHAPE ON THE WIRE -- `display`, a page and a feed delta all
  answer this, so a reader that handles entries from one handles them from all."
  [entries]
  (mapv (fn [e] {:seq (:seq e) :message (:message e)}) entries))

(defn display
  "THREAD-ID's conversation AS A CLIENT DRAWS IT: every entry in order, cards included,
  each with the seq of the record line it arrived in (`nil` while that line is still in
  the writer's queue). GET-OR-CREATE, like `messages`.

  TWO READINGS, ONE CONVERSATION: this is the one the window, `sofar` and the feed
  answer with, and `messages` is the model's. They differ by the cards and nothing
  else, which is why they are derived from one vector rather than kept as two."
  [thread-id]
  (let [id (str thread-id)]
    (touch! id)
    (as-sent (:entries (get @registry id)))))

(defn drawn
  "THREAD-ID's conversation as MESSAGES a client draws -- `display` without the numbers.
  THE OTHER HALF OF `messages`: where that one is what a provider may be handed, this is
  what the page puts on the screen, cards and all. Both are readings of the one entry
  vector, and neither is stored twice."
  [thread-id]
  (mapv :message (display thread-id)))

(defn context
  "THREAD-ID's opening context, as the record carried it, or []."
  [thread-id]
  (:context (live-entry thread-id)))

(defn state
  "What the conversation's own record last said about it -- :unfinished, :parked or
  :settled -- or nil for a session that has never run. `running?` is the other half of
  the question and is deliberately not folded in here (see `harness.edge.http/sofar-get`)."
  [thread-id]
  (:state (live-entry thread-id)))

(defn- wrap
  "An entry of the conversation as this table holds it: the message, the ARRIVAL it came
  in (`:group`, the run whose line will carry it), and the record offset of that line
  once the writer says where it landed."
  [group message]
  {:group (str group) :seq nil :message message})

(defn append!
  "Put ENTRIES at the end of THREAD-ID's conversation as part of run GROUP, and answer
  the ones that ENTERED.

  AN ENTRY THE CONVERSATION ALREADY HOLDS IS DROPPED, and that is the property that
  makes an action repeatable: a page whose run died on the way -- a refresh, a retry, a
  socket that went away -- sends the same bytes again, and the conversation must not end
  up with the question twice. The identity is the message's own `:id`, which is also
  what the record folds by (`harness.edge.replay/entries`), so a conversation that was
  rebuilt from disk dedupes against the same names a live one does. An entry with NO id
  cannot be recognised and is therefore kept -- guessing that two unnamed messages are
  the same one would be inventing an identity.

  GROUP IS THE RUN WHOSE LINE WILL CARRY THEM, and it is an argument rather than
  something this table invents because the writer is the one that knows: the edge hands
  the same group to `land!` when the line it wrote comes back with an offset. An action
  that continues a session puts its entries in the run that action starts; a run's own
  messages go in through `settle!` under the same name (`harness.edge.http/run-agent!`).

  IT ANSWERS THE ENTRIES THAT ENTERED, which is not always the ones it was handed: a
  repeat drops out, and the record wants to say what the action MEANT rather than what it
  typed (the edge logs both -- see `harness.edge.http/run-agent!`)."
  [thread-id group entries]
  (let [id  (str thread-id)
        g   (str group)
        _   (touch! id)
        add (fn [es m]
              (if (and (:id m) (some #(= (:id %) (:id m)) (map :message es)))
                es
                (conj es (wrap g m))))
        [before after] (swap-vals! registry
                                   (fn [m]
                                     (update-in m [id :entries]
                                                (fn [es] (reduce add (vec es) entries)))))
        n (- (count (get-in after [id :entries]))
             (count (get-in before [id :entries])))]
    (when (pos? n)
      (ring! id {:kind :entries}))
    (mapv :message (take-last n (get-in after [id :entries])))))

(defn settle!
  "Fold the frames a run emitted into THREAD-ID's conversation, so the next run of it
  continues from what just happened. GROUP is the run id those frames belong to.

  THE RUN'S OWN FRAMES ARE THE CONVERSATION'S OTHER HALF, and they are folded from
  FRAMES for the same reason the record is: the kernel's events are the model's shape
  and the log's frames are the conversation's, and a run that kept its answer in any
  other form would be a second vocabulary for one fact. Folding a run's frames on their
  own is exact because every id the converter mints is namespaced by the run (`runId-mN`,
  `runId-openN`) -- no frame of this run can patch a message of an earlier one.

  AT THE END OF THE RUN, not per frame: the frames of a run only become 'the
  conversation' together (a half-written assistant message is not a turn), and the
  per-frame cost of folding is O(all frames of the run) each time. The one thing this
  owes the next run is that it happens BEFORE the run is unregistered -- see the emitter
  in `harness.edge.http`, where the terminal frame is the moment both are done.

  THE STATE COMES WITH IT, because the frame that ends a run is the only place the
  conversation's own state is said: a terminal carrying an interrupt leaves it
  `:parked`, any other terminal leaves it `:settled`, and a run that died without one
  leaves it `:unfinished` -- which is exactly what the record would say, read back
  (`harness.edge.replay/record-state`), and the two must agree because one of them is
  what a client is shown.

  Called with the frames a run emitted, in order. Answers the messages that entered,
  same as `append!` -- a run that produced nothing (a refusal) answers []."
  [thread-id group frames]
  (let [entered (if (seq frames)
                  (append! thread-id group (frames/apply-frames frames))
                  [])
        tf      (last (filter frames/terminal? frames))
        st      (if (seq frames)
                  (if-some [t tf]
                    (if (= "interrupt" (get-in t [:outcome :type])) :parked :settled)
                    :unfinished)
                  (:state (live-entry thread-id)))]
    (when (seq frames)
      ;; THE INTERRUPTS COME WITH THE STATE, because the client's approval card is
      ;; drawn from them and a live session is what `sofar` answers from now: a parked
      ;; conversation whose interrupts lived only in the record would come back from
      ;; memory with nothing to approve.
      (swap! registry update (str thread-id)
             (fn [e] (-> e
                         (assoc :state st)
                         (assoc :interrupts (vec (get-in tf [:outcome :interrupts]))))))
      (ring! (str thread-id) {:kind :entries}))
    entered))

(defn land!
  "The line that carried GROUP's entries is on disk, at OFFSET -- the record offset the
  writer answered with, counted from the start of the thread's file.

  THIS IS WHERE AN ENTRY GETS ITS NUMBER (ticket 05's judgement 1). The number cannot be
  predicted when the entry goes in: the offset depends on the file's length, which the
  writer counts -- and re-counts, if the carry-back moved the file -- on its own thread.
  So the number comes back from the writer rather than being guessed here, and an entry
  whose line has not landed yet carries nil rather than a number that might be wrong.

  BY GROUP, NOT BY POSITION: the entries of one action and the entries of one run arrive
  under the same group name (`append!`), and a group that has already landed (or never
  will, because the run died before its terminal) is left exactly as it is. Idempotent."
  [thread-id group offset]
  (let [id (str thread-id)
        g  (str group)
        n  (long offset)]
    (swap! registry update-in [id :entries]
           (fn [es]
             (mapv (fn [e]
                     (if (and (= g (:group e)) (nil? (:seq e)))
                       (assoc e :seq n)
                       e))
                   (or es []))))
    (ring! id {:kind :entries})
    nil))

;; -------------------------------------------------------------- what runs here

(defn- refuse!
  "The one sentence this table says when it is full. A FUNCTION because it is thrown from
  two places -- the look before anything is created, and the atomic step -- and two copies
  of a refusal would drift into two answers to the same question."
  [thread-id run-id]
  (throw (ex-info (str "refusing to start run " (pr-str (str run-id)) " for "
                       (pr-str thread-id) ": this process already has " max-running
                       " sessions running (harness.edge.sessions/max-running), and a"
                       " session with a run going is held until it ends")
                  {:reason    :too-many-running-sessions
                   :thread-id thread-id
                   :limit     max-running})))

(defn run-started!
  "Pin THREAD-ID while RUN-ID is going, and REFUSE when this process already has
  `max-running` sessions running.

  The refusal is a NAMED failure rather than a queue: whoever asks gets a sentence
  naming the limit, and the run does not start. Throwing is the shape
  `harness.edge.http`'s set-up already turns into a RUN_ERROR a client can read.

  THE CEILING IS ASKED BEFORE ANYTHING IS CREATED, so a refused run leaves no session
  behind: it never ran, and an entry for it would be this process holding a conversation
  on behalf of something that did not happen -- paid for with a rebuild as well. And it
  is asked AGAIN inside the swap, because the first one is only a look; the atomic step
  is the one that decides.

  THIS IS THE WHOLE REGISTRY OF 'A RUN IS GOING IN THIS PROCESS' (ticket 05 merged the
  edge's second one into it -- `.scratch/sessions-live-on-the-server` ticket 01's
  correction 3): `running?` reads it, the sidebar's row reads it, and the run edge's
  door refuses a second run off it."
  [thread-id run-id]
  (let [id (str thread-id)]
    (when (and (<= max-running (count-running @registry))
               (not (contains? (get-in @registry [id :runs] #{}) (str run-id))))
      (refuse! id run-id))
    (touch! id)
    (let [[before _] (swap-vals! registry
                                 (fn [m]
                                   (if (<= max-running (count-running m))
                                     m
                                     (update-in m [id :runs] (fnil conj #{}) (str run-id)))))
          held?     (contains? (get-in before [id :runs] #{}) (str run-id))]
      (when (and (<= max-running (count-running before)) (not held?))
        (refuse! id run-id))
      (ring! id {:kind :entries})
      nil)))

(defn run-finished!
  "Unpin RUN-ID's hold on THREAD-ID -- if that is a hold this entry actually has.

  BY ID, not blind: `unregister-run!` in the edge compares ids for the same reason, and
  a run that ends must not unpin a session while a different run of it is still going.
  The touch is what starts the idle clock at the END of the run -- without it a long run
  would be eligible to be put away the instant it finished."
  [thread-id run-id]
  (let [id (str thread-id)]
    (swap! registry
           (fn [m]
             (if (contains? m id)
               (-> m
                   (update-in [id :runs] disj (str run-id))
                   (assoc-in [id :touched-at] (System/currentTimeMillis)))
               m)))
    (ring! id {:kind :entries})
    nil))

;; ------------------------------------------------------------------ the window

(defn- arrivals-of
  "ENTRIES grouped by the arrival they share -- one action's entries, or one run's -- in
  order: [{:seq <n|nil> :entries [message ..]} ..].

  THE GROUP IS THE LINE, AND THE LINE IS THE GROUP: entries that arrived together share
  the record offset they were numbered with, and entries still in flight share the run
  they came from. Which is the same partition a replay produces
  (`harness.edge.replay/entries` numbers by line), so a page cut here and a page cut
  after a refresh fall in the same places.

  IT TAKES ENTRIES RATHER THAN A THREAD, because the same cut has to be made of a
  RECORD: `page` answers a conversation this process is not holding by folding it
  (`harness.edge.http/page-get`), and a second copy of this arithmetic for that path is
  exactly how two windows start disagreeing about where a page begins."
  [entries]
  (->> entries
       (partition-by (fn [e] (if (some? (:seq e))
                               [:line (:seq e)]
                               [:pending (:group e)])))
       (mapv (fn [g] {:seq (:seq (first g))
                      :entries (vec g)}))))

(defn- page-from
  "ARRIVALS from index I up to (not including) J, as a page:
  {:entries .. :baseSeq .. :hasMore ..}.

  `:entries` ARE THE NUMBERED ENTRIES THEMSELVES (`{:seq N :message M}`), not the bare
  messages: a page is what a client ADDRESSES, and an entry without its record offset
  cannot be told apart from one the client already holds. `:hasMore` is about I rather
  than J -- what is in front of the page -- so a prepend page answers it the same way
  the tail does."
  [arrivals i j]
  (let [taken   (subvec (vec arrivals) i j)
        entries (as-sent (mapcat :entries taken))]
    {:entries entries
     ;; THE BASE IS A NUMBER THE READER CAN HOLD, and a group still in flight has none:
     ;; the first LANDED offset in the page is the honest answer, and nil says the whole
     ;; page is still in the writer's queue (a conversation younger than one page, where
     ;; the reader's next move is to open the tail again anyway).
     :baseSeq (or (:seq (first taken)) (some :seq taken))
     :hasMore (pos? i)}))

(defn tail-of
  "The tail page of ENTRIES: the last `page-size` of them, cut back to the start of the
  arrival they came in (so a page never begins mid-turn).

  THE PAGE THE CLIENT OPENS WITH (ADR 0003 decision 2, ticket 05's `tail`). The cut is
  what makes `before` safe: a page that started in the middle of a group would leave the
  rest of that group unreachable -- `before` pages strictly in front of a group, so the
  entries a page did not take must be a whole number of groups.

  A GROUP LARGER THAN THE PAGE IS TAKEN WHOLE, which is why this can answer with more
  than `page-size` entries: a turn is not divisible, and half a turn is not something a
  reader can use.

  THE ENTRIES IT ANSWERS WITH ARE NUMBERED (`{:seq N :message M}`) -- see `display` for
  the same shape read whole."
  [entries]
  (let [as (arrivals-of entries)]
    (loop [i (dec (count as)) n 0]
      (if (neg? i)
        (page-from as 0 (count as))
        (let [n' (+ n (count (:entries (nth as i))))]
          (if (<= page-size n')
            (page-from as i (count as))
            (recur (dec i) n')))))))

(defn tail
  "The tail page of THREAD-ID's LIVE conversation (`tail-of` of its own entries)."
  [thread-id]
  (tail-of (:entries (live-entry thread-id))))

(defn since-of
  "Every entry of ENTRIES that arrived AFTER record offset SEQ -- what a connected
  replica is missing (the `append` direction of ADR 0003 decision 3).

  UNLANDED ENTRIES COUNT AS AFTER ANYTHING: a group still in the writer's queue is the
  newest thing there is, and a reader that had 'everything through N' has not been told
  about it. A NIL CURSOR IS 'I HAVE NOTHING', so it answers the whole conversation --
  the feed route never asks that way (no cursor means the tail page), but the reading
  should be the obvious one for anyone holding this function. It may be handed the same entry twice across a reconnect (its line had
  not landed when the reader asked, and its number came back afterwards) -- which is why
  entries carry their ids and every reader of this dedupes by them."
  [entries seq-n]
  (let [as (arrivals-of entries)]
    (as-sent (mapcat :entries
                     (filter (fn [{:keys [seq]}]
                               (or (nil? seq-n)
                                   (nil? seq)
                                   (< (long seq-n) (long seq))))
                             as)))))

(defn since
  "THREAD-ID's LIVE entries after SEQ (`since-of` of its own entries)."
  [thread-id seq-n]
  (since-of (:entries (live-entry thread-id)) seq-n))

(defn before-of
  "The page of ENTRIES immediately before record offset SEQ: the `page-size` entries in
  front of it, cut back to the start of their arrival (the `prepend` direction, ADR 0003
  decision 4).

  STRICTLY IN FRONT, AND THAT IS THE WHOLE CORRECTNESS ARGUMENT: a reader holds a window
  whose oldest entry sits at the start of an arrival (every page this table hands out
  does), so the entries it does not have are all in front of that arrival and none of
  them is inside it. Paging 'up to and including' SEQ would hand back entries the reader
  already holds; paging by count would lose the ones between.

  SEQ THAT MATCHES NOTHING lands after the nearest arrival ahead of it -- offsets come
  from the record, and a reader whose number is stale (a page it never received) still
  gets a page that ends somewhere sensible instead of an error it cannot act on."
  [entries seq-n]
  (let [as  (arrivals-of entries)
        end (or (first (keep-indexed (fn [i {:keys [seq]}]
                                       (when (and (some? seq) (<= (long seq-n) (long seq)))
                                         i))
                                     as))
                (count as))]
    (loop [i (dec end) n 0]
      (if (neg? i)
        (page-from as 0 end)
        (let [n' (+ n (count (:entries (nth as i))))]
          (if (<= page-size n')
            (page-from as i end)
            (recur (dec i) n')))))))

(defn before
  "THREAD-ID's LIVE page in front of SEQ (`before-of` of its own entries)."
  [thread-id seq-n]
  (before-of (:entries (live-entry thread-id)) seq-n))

;; ------------------------------------------------------------------- put away

(defn drop!
  "Put THREAD-ID away now, whoever is running it, and hand its claim back. The explicit
  door: `sweep!` is the one that respects the pins, and callers that want to forget a
  session outright (a test between cases, an archive action) say so here.

  THE CLAIM GOES BACK WITH IT, in both directions of this file: a conversation this
  process has stopped serving is one another process may serve, whether it went away
  because nobody asked for it for a while (`sweep!`) or because somebody said so. And
  every connected window is told, because a reader that is not told keeps a window open
  on a conversation this process no longer holds (ADR 0003 decision 6)."
  [thread-id]
  (let [id (str thread-id)
        [before _] (swap-vals! registry dissoc id)]
    (when-some [e (get before id)]
      (claims/release! id (:claim e)))
    (ring! id {:kind :gone :reason :put-away})
    nil))

(defn- watched?
  "Is anything connected to TID's window right now? (`watch!` / `unwatch!`;
  `harness.edge.http/stream-feed!` is the one caller that holds a connection open.)"
  [tid]
  (boolean (seq (get @watchers (str tid)))))

(defn- evictable?
  "May TID's ENTRY be put away as of NOW? FOUR things, and every one of them is a reason
  not to: somebody is running it, its bytes are not all on disk, somebody is WATCHING it,
  and it was touched recently.

  A CONNECTED WINDOW IS A PIN (ticket 06), and it took a browser to show why: a reader
  who leaves a conversation open is looking at it, so sweeping the session out from under
  them ends the feed every thirty seconds -- the window says it was reopened, the reader
  never asked for anything, and the server does the same thing again half a minute later.
  A poll used to keep such a session alive by touching it on every read; a feed's
  connection is the same fact without the traffic, and `unwatch!` is what ends it
  (http-kit's close handler, so a tab that goes away releases it)."
  [tid entry now]
  (and (empty? (:runs entry))
       (not (@unflushed? tid))
       (not (watched? tid))
       (<= idle-ttl-ms (- now (:touched-at entry)))))

(defn sweep!
  "Put away every session that has been idle past `idle-ttl-ms`, that has no run going,
  that nothing is watching, and whose bytes have all reached the record. NOW is
  milliseconds. Answers the ids it put away, in no particular order -- an answer rather
  than a count, because 'which one' is the only thing a reader can act on."
  [now]
  (let [[before after] (swap-vals! registry
                                   (fn [m]
                                     (into {} (remove (fn [[tid e]] (evictable? tid e now))) m)))
        gone            (vec (remove #(contains? after %) (keys before)))]
    ;; THE CLAIM FOLLOWS THE ENTRY OUT, and it is handed back with the token the
    ;; ENTRY carried rather than by thread id: a request that arrives between the swap
    ;; above and these releases births the session again, and that new birth's claim
    ;; must not be the one this deletes (`harness.cap.claims/release!`).
    (doseq [tid gone]
      (claims/release! tid (:claim (get before tid)))
      (ring! tid {:kind :gone :reason :idle}))
    gone))

(defn live
  "What is in the table right now: thread-id -> {:entries <count> :messages <count>
  :runs <set> :touched-at <ms> :state <state> :claim <token>}. FOR A READER -- a test, a
  report, the sidebar later. It answers with a snapshot, and a snapshot is not a fact
  about a later moment (see docs/rules/concurrency.md).

  `:entries` COUNTS WHAT THE CLIENT SEES (cards included) and `:messages` what a run
  would be handed (`model-view`), so the two differ exactly where the conversation
  carries a card."
  []
  (into {}
        (map (fn [[tid e]]
               [tid {:entries    (count (:entries e))
                     :messages   (count (model-view (mapv :message (:entries e))))
                     :runs       (:runs e)
                     :state      (:state e)
                     :touched-at (:touched-at e)
                     :claim      (:claim e)}]))
        @registry))

(defn running-count
  "How many sessions have a run going -- the number `max-running` is about."
  []
  (count-running @registry))

;; ------------------------------------------------------------------ the sweeper

(defonce ^:private sweeper (atom nil))

(defn start!
  "Start the sweeper that enforces `idle-ttl-ms`, and answer the fn that stops it.

  A BACKGROUND THREAD RATHER THAN A CHECK ON ACCESS, because a bound that only bites
  when somebody asks is not a bound: the sessions nobody asks about are exactly the ones
  that would sit there. A DAEMON thread, so a process that never stops the server still
  exits; and idempotent, so a second `start!` answers the first one's stop fn rather
  than starting a second clock (the composition root may be called more than once by a
  test suite, which is the shape `harness.kernel.hooks/install!` has to live with too)."
  []
  (if-some [s @sweeper]
    (fn [] (.shutdown ^ScheduledExecutorService s))
    (let [s (Executors/newSingleThreadScheduledExecutor
             (reify ThreadFactory
               (newThread [_ r]
                 (doto (Thread. ^Runnable r "harness-session-sweeper")
                   (.setDaemon true)))))]
      (.scheduleAtFixedRate ^ScheduledExecutorService s
                            ^Runnable (fn [] (sweep! (System/currentTimeMillis)))
                            0 sweep-interval-ms TimeUnit/MILLISECONDS)
      (when (compare-and-set! sweeper nil s)
        (fn [] (.shutdown ^ScheduledExecutorService s))))))
