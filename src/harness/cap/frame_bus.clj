(ns harness.cap.frame-bus
  "An in-process frame bus: which threads' AG-UI frames have somebody listening,
  keyed by thread id, answered here.

  THE REASON THIS EXISTS INSTEAD OF TAILING THE LOG: the log is a RECORD, never
  a source of truth -- the edge never reads its own file back while serving
  (the contract at the top of harness.edge.http). A server that streamed its
  own log out to a second client would be turning the record into a
  distribution channel, and the forbidden thing is not 'reading a file' but
  that inversion: rebuild's read-once is a reader of finished history; the
  follow route wants frames AS they are written. So the writer's own process
  hands them over, the same way it hands a run's frames to the client whose
  POST opened the run -- except a delegation has no such client, which is why
  this table exists.

  WHO PUBLISHES: only the edge's subagent runner (harness.edge.http/run-
  subagent!), which today writes every frame but the reasoning family into the
  subagent's own jsonl (ADR 0009) and sends none. Publishing beside those writes
  is one line per frame and nothing
  else. The main agent route does not publish -- its frames already have a
  client, the SSE stream the POST opened, and a second channel for them would
  be exactly the record-serving shape this bus exists to avoid.

  WHO SUBSCRIBES: the edge's follow route, and nothing else. One publisher
  side, one subscriber side, one key.

  --------------------------------------------------------------- the traps

  THE PUBLISH SIDE IS A TOOL THREAD. A delegation runs synchronously on the
  tool thread that called `agent`, and this ticket's one real trap lives here:
  a subscriber going away -- or reading slowly -- must never be able to block
  the publisher, because a blocked publish is a blocked delegation. So the
  channel is SLIDING: a follower that falls behind is served the most recent
  frames and silently loses old ones. Losing a frame of a live view is the
  cheap failure; stalling a subagent's whole run is the expensive one, and a
  sliding buffer is what makes the expensive one unreachable -- `put!` onto a
  sliding buffer always succeeds immediately, by evicting the oldest value
  when full. There is no branch in publish! that can wait.

  THE SUBSCRIBER'S LIFECYCLE IS THE SUBSCRIBER'S. subscribe! answers a
  teardown that removes THIS subscription and nothing else -- the same shape
  `harness.cap.subagents/begin!` answers, because a table that can outlive its
  reader needs an ending that is a fact about one row.

  HOW A READER LEARNS THE STREAM IS OVER. A channel close is the mechanism,
  but a close alone is ambiguous to a reader parked in a blocking take (nil
  answers both 'closed' and -- on some code paths -- 'nothing yet'), so stop!
  pushes one in-band marker BEFORE closing: {:bus/closed true}, a key no AG-UI
  frame carries. The reader treats the marker as the end of its stream and
  lets the close be the backstop.

  ------------------------------------------------------------- the shape

  ONE ATOM: thread-id -> {sub-id -> {:ch channel :started-at ms}}. The key is
  the subagent's thread id, read on that side too -- the AGENTS.md rule that a
  process-level container is keyed, and that its reads carry the key. The
  inner map is keyed by a per-subscription id, which is what keeps two
  followers of one thread independent: an ending removes one row, and the
  thread's row goes only when the last one does.

  NIL IS NOT A KEY ANYBODY WRITES, and that is worth saying rather than
  assumed: the tables the concurrency rule names (`turn-plan`, `overlays`)
  give nil the meaning 'process-level tier'. This table has no process-level
  tier -- a frame belongs to exactly one thread -- so a missing key reads as
  'nobody is listening', which is publish!'s zero-cost case."
  (:require [clojure.core.async :as async]))

(defonce ^:private subscribers
  (atom {}))
;; thread-id -> {sub-id -> {:ch (a sliding channel) :started-at ms}}

(def closed-marker
  "The frame a subscriber sees when its subscription ended. A key no AG-UI
  frame ever carries, so a reader cannot mistake it for one; see the docstring
  for why an in-band marker rides beside the channel close."
  {:bus/closed true})

(defn- sub-id
  "A per-subscription id, so two subscriptions on one thread are two rows and
  an ending removes one. A UUID keeps two subscriptions opened in the same
  millisecond distinct; the prefix is for whoever reads a dump, not for
  uniqueness."
  []
  (str "sub-" (java.util.UUID/randomUUID)))

(defn publish!
  "FRAME happened on THREAD-ID. NON-BLOCKING, and that is the load-bearing
  property: the publisher is a subagent's tool thread, and a publish that
  waited would be a delegation that waited. Every delivery below is a `put!`
  onto a sliding buffer, which cannot wait -- see the namespace docstring.

  NO SUBSCRIBERS IS THE ZERO-COST CASE: one map lookup that answers nothing,
  and no delivery value is constructed. (Not 'sent into an empty collection'
  -- the ticket's own sentence -- a lookup, and out.)"
  [thread-id frame]
  (when-some [subs (seq (get @subscribers thread-id))]
    (doseq [[_id {:keys [ch]}] subs]
      (async/put! ch frame))))

(defn subscriber-count
  "How many subscriptions THREAD-ID has -- a fact about this process's table,
  for the route's 'anybody listening' answers and the tests. Reading the key
  on the read side too, per the concurrency rule."
  [thread-id]
  (count (get @subscribers thread-id)))

(defn subscribe!
  "THREAD-ID has a subscriber. Answers {:ch <channel> :stop! <fn>}.

  The channel is SLIDING with a generous buffer: a follower that reads slower
  than a run produces is served the most recent frames and loses old ones
  rather than ever blocking the publisher -- the trade the spec chose when it
  said the bus must never block a delegation.

  `stop!` removes THIS subscription and nothing else, then pushes the closed
  marker and closes the channel: a reader parked on it sees the marker and
  ends. Calling it twice is a no-op the second time -- the row it names is
  gone -- and a second subscription on the same thread is untouched by it.

  THE REMOVAL IS ONE SWAP whose pure function decides, and the marker and the
  close happen AFTER the swap, never inside it: the atom's function has to
  stay pure (the concurrency rule), and whether this call was the one that
  removed the row is read back from the pre-swap map, so a double stop! cannot
  push two markers."
  [thread-id]
  (let [id  (sub-id)
        ch  (async/chan (async/sliding-buffer 256))
        rec {:ch ch :started-at (System/currentTimeMillis)}
        _inserted
        (swap! subscribers (fn [m] (assoc-in m [thread-id id] rec)))
        stop! (fn stop! []
                ;; ONE SWAP whose pure function removes at most this row; the
                ;; pre-swap map decides whether THIS call was the one that
                ;; removed it, so a second stop! pushes no second marker and
                ;; closes nothing twice.
                (let [[before _after]
                      (swap-vals! subscribers
                                  (fn [m]
                                    (let [cur (get m thread-id)]
                                      (if (and (map? cur) (contains? cur id))
                                        (let [rest (dissoc cur id)]
                                          (if (seq rest)
                                            (assoc m thread-id rest)
                                            (dissoc m thread-id)))
                                        m))))]
                  (when (contains? (get before thread-id) id)
                    (async/put! ch closed-marker)
                    (async/close! ch))
                  nil))]
    {:ch ch :stop! stop!}))
