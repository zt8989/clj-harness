(ns harness.edge.sessions
  "The server's LIVE conversations: thread-id -> the messages the next run continues
  from. This is the table ADR 0002 decides for
  (docs/adr/0002-sessions-live-on-the-server.md): the conversation lives HERE, and the
  browser stops being its author.

  A session is born the first time somebody asks for it -- the page opening it, or an
  action aimed at it -- and it is built ONCE from the record, by `harness.edge.replay`.
  Every later question is answered from memory. After `idle-ttl-ms` of nobody asking,
  it is put away again; the build is cheap enough to repeat because it is a fold over
  a file, and the file is the thing that outlives this process.

  WHAT IT HOLDS, AND WHAT IT DELIBERATELY DOES NOT. It holds the CONVERSATION -- the
  messages a client used to send back every turn -- and nothing the run derives for
  itself. Two things are absent on purpose:

    - THE SYSTEM MESSAGE. It is assembled per run (`harness.cap.system-prompt`: the
      frozen opening plus what the `SystemPrompt` hooks append), and a copy kept here
      would be a sentence that stopped being true the moment a binding moved.
    - THE INJECTIONS -- instruction files, the skills catalog, a skill body, a job's
      ending. Sharper still: the instruction files are re-read on EVERY run so that an
      edited AGENTS.md takes effect without a restart (`harness.edge.http/opening-blocks!`
      says why). Freezing them here is exactly the '改了没生效' failure the design refuses.

  SO THIS TABLE DOES NOT MAKE INJECTIONS STOP BEING RECOMPUTED. It takes the CLIENT out
  of the loop, which is the half that was never a decision.

  AND THE CARDS ARE NOT PART OF IT. A log carries a message per injected-context frame
  so a rebuilt conversation can draw the cards again
  (`harness.kernel.frames/apply-frames`); those messages are for the screen and the
  model never had them, so `build` leaves them out -- they would otherwise be handed to
  a provider that has no idea what a `data` part is
  (`harness.edge.ag-ui/provider-part` refuses one by name, which is the failure this
  keeps from happening).

  THE BUILD IS THE ONLY READ OF DISK. Everything after it arrives through `append!` --
  the entries a client's action carried, with an entry the conversation already holds
  dropped by id -- and through `settle!`, the run's own frames folded at the moment the
  run ends. That pair is what makes this table the AUTHORITY rather than a cache: a run
  continues from here, not from a file, and not from what the browser remembers.

  ONE ATOM, ONE TABLE. The registry is the single home of every fact about a live
  session -- its messages, when it was last asked for, and which runs of it are going.
  That is deliberate: `sweep!` must not put away a session a run is in the middle of,
  and if 'a run is going' lived anywhere else the two would race. It is also why the
  run set is a SET: two runs of one session is `.scratch/session-after-refresh` ticket
  05's business to refuse, and until it does, the table must still be correct about the
  one fact it owns -- that the session may not be put away while ANY of them is going.

  Nothing here writes: no sqlite, no jsonl, no process log. That is asserted, not
  assumed (see the test)."
  (:require [harness.edge.replay :as replay]
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

;; ------------------------------------------------------------------- the table

(defonce ^:private registry
  ;; thread-id -> {:messages [..] :touched-at <ms> :runs #{run-id ..}}
  (atom {}))

(defonce ^:private unflushed?
  ;; TICKET 02 FILLS THIS IN. A session whose bytes are not all on disk may not be put
  ;; away, because putting it away would mean rebuilding from a record that is behind it
  ;; -- and the difference would be silent. Until the async writer exists there is
  ;; nothing behind anything, so the honest default is 'nothing is pending'.
  (atom (constantly false)))

(defn watch-unflushed!
  "Say which sessions have bytes that have not reached the record. F is handed a
  thread-id; `sweep!` will not put away a session it answers true for.

  A seam rather than a field because the answer belongs to the writer
  (`harness.edge.http/log!` and ticket 02's queue), and this namespace must not grow a
  second opinion about what has been written."
  [f]
  (reset! unflushed? f))

(defn- without-cards
  "MESSAGES with the injected-context cards taken out.

  A card is a message the log carries so the screen can draw it again; it is not
  something the model ever read. Dropping the whole message is right because a card IS
  its own message (`apply-frames` makes one per frame), and a message left with no parts
  at all is dropped with it rather than sent as an empty turn."
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
  "THREAD-ID's conversation, read out of its record. Empty when the thread has never
  run.

  `find-log` answers nil rather than refusing, and that nil is the right answer HERE:
  a session that has never run has nothing recorded, and a page opening it must get an
  empty conversation rather than an error. A session that HAS run and whose log has gone
  missing is the different, uglier case, and telling the two apart needs the session row
  -- which is ticket 03's 'new session' action, not this fold's business.

  `sofar` RATHER THAN `rebuild`, and the difference is the whole reason both exist: a
  rebuild refuses a log whose run has not ended, and a session is born exactly when
  somebody looks at it -- which after a process restart is a log that ends mid-run. The
  door that answers that is the one that says so, and it still uses the strict reader
  for a log that IS finished."
  [thread-id]
  (if-some [f (replay/find-log (home/projects-dir) thread-id)]
    (without-cards (:messages (replay/sofar f)))
    []))

(defn- count-running [m]
  (count (filter #(seq (:runs %)) (vals m))))

(defn- ensure-session
  "THREAD-ID's entry, building it if this is the first ask.

  THE BUILD HAPPENS OUTSIDE THE SWAP, and that is a rule rather than a preference:
  `swap!` may call its function more than once under contention, and a second read of
  the log would be a second answer to a question that has one. So a racing pair both
  build and one is discarded -- the bytes are equal, so which one won cannot be told,
  which is the property that makes discarding one safe."
  [thread-id]
  (if-some [e (get @registry thread-id)]
    e
    (let [built {:messages (build thread-id) :touched-at (System/currentTimeMillis) :runs #{}}]
      (get (swap! registry (fn [m] (if (contains? m thread-id) m (assoc m thread-id built))))
           thread-id))))

(defn touch!
  "Say that somebody is dealing with THREAD-ID now -- which is both 'it exists' and
  'the idle clock starts over'."
  [thread-id]
  (let [id (str thread-id)]
    (ensure-session id)
    (swap! registry update id assoc :touched-at (System/currentTimeMillis))
    nil))

(defn messages
  "THREAD-ID's conversation: the messages the next run continues from.

  GET-OR-CREATE, and it counts as somebody dealing with the session -- the page drawing
  it is what keeps it alive."
  [thread-id]
  (let [id (str thread-id)]
    (touch! id)
    (:messages (get @registry id))))

(defn append!
  "Put ENTRIES at the end of THREAD-ID's conversation and answer the ones that ENTERED.

  AN ENTRY THE CONVERSATION ALREADY HOLDS IS DROPPED, and that is the property that
  makes an action repeatable: a page whose run died on the way -- a refresh, a retry, a
  socket that went away -- sends the same bytes again, and the conversation must not end
  up with the question twice. The identity is the message's own `:id`, which is also
  what the record folds by (`harness.edge.replay/fold-frames`), so a conversation that
  was rebuilt from disk dedupes against the same names a live one does. An entry with
  NO id cannot be recognised and is therefore kept -- guessing that two unnamed messages
  are the same one would be inventing an identity.

  IT ANSWERS THE ENTRIES THAT ENTERED, which is not always the ones it was handed: a
  repeat drops out, and the record wants to say what the action MEANT rather than what it
  typed (the edge logs both -- see `harness.edge.http/run-agent!`)."
  [thread-id entries]
  (let [id  (str thread-id)
        _   (touch! id)
        add (fn [msgs e]
              (if (and (:id e) (some #(= (:id %) (:id e)) msgs))
                msgs
                (conj msgs e)))
        [before after] (swap-vals! registry
                                   (fn [m]
                                     (update-in m [id :messages]
                                                (fn [msgs] (reduce add (vec msgs) entries)))))
        n (- (count (get-in after [id :messages]))
             (count (get-in before [id :messages])))]
    (vec (take-last n (get-in after [id :messages])))))

(defn settle!
  "Fold the frames a run emitted into THREAD-ID's conversation, so the next run of it
  continues from what just happened.

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

  Called with the frames a run emitted, in order. Answers the messages that entered,
  same as `append!` -- a run that produced nothing (a refusal) answers []."
  [thread-id frames]
  (if (seq frames)
    (append! thread-id (without-cards (frames/apply-frames frames)))
    []))

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
  is the one that decides."
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
    nil))

(defn drop!
  "Put THREAD-ID away now, whoever is running it. The explicit door: `sweep!` is the
  one that respects the pins, and callers that want to forget a session outright
  (a test between cases, an archive action) say so here."
  [thread-id]
  (swap! registry dissoc (str thread-id))
  nil)

(defn- evictable?
  "May TID's ENTRY be put away as of NOW? Three things, and every one of them is a
  reason not to: somebody is running it, its bytes are not all on disk, it was touched
  recently."
  [tid entry now]
  (and (empty? (:runs entry))
       (not (@unflushed? tid))
       (<= idle-ttl-ms (- now (:touched-at entry)))))

(defn sweep!
  "Put away every session that has been idle past `idle-ttl-ms`, that has no run going,
  and whose bytes have all reached the record. NOW is milliseconds. Answers the ids it
  put away, in no particular order -- an answer rather than a count, because 'which one'
  is the only thing a reader can act on."
  [now]
  (let [[before after] (swap-vals! registry
                                   (fn [m]
                                     (into {} (remove (fn [[tid e]] (evictable? tid e now))) m)))]
    (vec (remove #(contains? after %) (keys before)))))

(defn live
  "What is in the table right now: thread-id -> {:messages <count> :runs <set>
  :touched-at <ms>}. FOR A READER -- a test, a report, the sidebar later. It answers
  with a snapshot, and a snapshot is not a fact about a later moment (see
  docs/rules/concurrency.md)."
  []
  (into {}
        (map (fn [[tid e]] [tid {:messages   (count (:messages e))
                                 :runs       (:runs e)
                                 :touched-at (:touched-at e)}]))
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
