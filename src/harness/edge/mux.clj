(ns harness.edge.mux
  "THE DOWNLINK'S CONNECTION REGISTRY: which WebSocket connection is watching which
  conversations, and what to call when one of them changes.

  WHY THIS IS ITS OWN NAMESPACE. Two facts have to be held together and neither belongs
  to the other's owner: the CONNECTION (`harness.edge.http`, which owns the channels and
  the frames) and the DOORBELL (`harness.edge.sessions`, which rings when a conversation
  changes). This namespace is the small bookkeeping between them -- a token -> connection
  map, and a per-conversation watch fn registered with `sessions/watch!` so a ring reaches
  only the connections that asked for that conversation.

  THE SUBSCRIPTION IS PER-CONNECTION, NOT PER-CLIENT, and that is the whole of what makes
  this legal under ADR 0003 decision 7 (\"the server does not remember who is
  subscribed\"): the set lives only while the socket is open, and `detach!` -- http-kit's
  close handler -- is what ends it. There is no durable registry, no identity, no cursor
  kept across connections: a client that reconnects says what it holds in the handshake
  URL, exactly as the SSE feed said `since=N` on every read.

  A CONVERSATION NOBODY SUBSCRIBED TO HAS NO WATCH, and that is the difference that
  matters: this is the filtering the SSE feed never had to do (one connection watched one
  conversation). A ring for an unsubscribed conversation walks nothing and sends nothing.

  AND A WATCHER BELONGS TO THE CONNECTION THAT REGISTERED IT. `attach!` on a token that is
  already in use RELEASES the set the earlier socket left (rather than dropping it),
  `unsubscribe!` and `detach!` release theirs, and the doorbell itself names the connection as
  its OWNER (`sessions/watch!`'s third argument) so that a path which loses the table row
  cannot leave a reader behind. Measured before this: 199 doorbells over 32 conversations with
  2 live connections and 5 subscriptions (`.scratch/memory-hygiene/` 票 02)."
  (:require [harness.edge.sessions :as sessions]))

(defonce ^:private connections
  ;; token -> {:ch <http-kit channel> :subs {thread-id {:watch f :push f}}}
  ;;
  ;; A TOKEN IS THE CLIENT'S OWN NAME FOR THIS SOCKET, minted at connect time and
  ;; thrown away with the connection: it addresses the connection to the HTTP route that
  ;; updates the set (`POST /api/events.mux/subscribe`), because the socket itself is
  ;; downlink-only and carries no client message. It is not an identity and nothing
  ;; outlives the socket.
  (atom {}))

(defn attach!
  "Remember CH as the connection named TOKEN, watching nothing yet. Replaces any set an
  earlier connection under the same name left behind -- A TOKEN IS MEANT TO BE FRESH, and when
  it is not, that earlier set is RELEASED rather than dropped on the floor: a subs map that
  goes away without unwatching leaves its doorbells in `sessions/watchers` with nobody to
  ring them and nothing to end them (`prune-watches!` cannot even see them -- they look like
  live readers). That is `.scratch/memory-hygiene/` 票 02's leak, and this is one of its doors."
  [token ch]
  (locking connections
    (let [token (str token)
          [before] (swap-vals! connections assoc token {:ch ch :subs {}})]
      (doseq [[thread-id sub] (:subs (get before token))]
        (sessions/unwatch! thread-id (:watch sub)))))
  (str token))

(defn channel
  "The channel TOKEN's connection is, or nil when there is no such connection (it closed,
  or never existed -- the two are one answer here, and the caller has nothing to do for
  either)."
  [token]
  (:ch (get @connections (str token))))

(defn token-of
  "The token of the connection that IS CH, or nil. http-kit's close handler is handed the
  channel and nothing else, and a token found here is what lets the set be released."
  [ch]
  (some (fn [[token conn]] (when (identical? ch (:ch conn)) token)) @connections))

(defn subscribed?
  "Whether TOKEN's connection asked for THREAD-ID."
  [token thread-id]
  (contains? (:subs (get @connections (str token))) (str thread-id)))

(defn watching?
  "Whether ANY LIVE CONNECTION is subscribed to THREAD-ID right now -- 'is anybody reading
  this conversation'.

  THIS IS THE OWNER A ROUTE NAMES WHEN ITS OWN READER CANNOT BE OBSERVED. The trajectory stream
  is a plain streaming response: http-kit reports NO close for one and its `open?` stays true
  after the client is gone (measured: `.scratch/memory-hygiene/` 票 02), so a doorbell
  registered on that socket's life is a doorbell nothing can ever end. The subscription that
  CAN be observed is this one -- the page's own downlink, which says when it goes -- so the
  stream's watcher names it: while somebody is subscribed the stream may push, and when nobody
  is, it is not a reader and goes at the next prune (`sessions/prune-watches!`, which
  `detach!` and `unsubscribe!` call for the conversation they just released)."
  [thread-id]
  (let [tid (str thread-id)]
    (boolean (some (fn [[_ conn]] (contains? (:subs conn) tid)) @connections))))

(defn subscribe!
  "Register THREAD-ID on TOKEN's connection: every ring for that conversation calls PUSH.
  Idempotent -- a second call replaces the push and its doorbell rather than stacking a
  second one, which is what a client re-declaring its set (a `since` that moved, a
  reconnect) means.

  Answers true when the conversation was NOT already subscribed (http reads it to decide
  whether a whole opening frame is owed), false when it was replaced.

  AND FALSE WHEN THERE IS NO SUCH CONNECTION AT ALL: a token whose socket has gone cannot be
  subscribed -- there is nothing to open, and a doorbell registered for it would have nobody
  to ring it and nothing to end it."
  [token thread-id push]
  (let [token (str token)
        thread-id (str thread-id)]
    ;; ONE CRITICAL SECTION WITH `detach!`, and that is not tidiness: the two have to agree
    ;; on whether this doorbell exists. An interleaving where a socket's close lands between
    ;; the table write and the `sessions/watch!` below leaves a watcher nobody owns -- and a
    ;; `sessions/watch!` on a token that is already gone RESURRECTS the row as a connection
    ;; with no channel, which nothing can ever detach.
    (locking connections
      (let [existing (get-in @connections [token :subs thread-id])]
        (if-not (contains? @connections token)
          ;; A CONNECTION THAT IS GONE CANNOT BE SUBSCRIBED. Answers false -- 'nothing new
          ;; here' -- because there is nothing to open.
          false
          (let [watch (fn [_ _] (push))]
            (when-some [old existing] (sessions/unwatch! thread-id (:watch old)))
            ;; THE OWNER IS THE CONNECTION, so even a doorbell that slipped past this section
            ;; (a path that drops a subs map without unwatching) is not a reader for long:
            ;; `watched?` asks and the sweeper's `prune-watches!` takes it out.
            (sessions/watch! thread-id watch (fn [] (contains? @connections token)))
            (swap! connections assoc-in [token :subs thread-id] {:watch watch :push push})
            (nil? existing)))))))

(defn unsubscribe!
  "Stop watching THREAD-ID on TOKEN's connection (the client closed it, or moved off it).
  Answers the thread id when something was released, nil when there was nothing."
  [token thread-id]
  (let [token (str token)
        thread-id (str thread-id)]
    (locking connections
      (let [sub (get-in @connections [token :subs thread-id])]
        (when (contains? @connections token)
          (swap! connections update-in [token :subs] dissoc thread-id))
        (when-some [sub sub]
          (sessions/unwatch! thread-id (:watch sub))
          ;; AND THE WATCHERS THAT NAMED THIS CONVERSATION'S SUBSCRIPTION AS THEIR OWNER (the
          ;; trajectory stream -- its own reader cannot be observed at all): the set that owned
          ;; them has just gone, so they go now rather than at the next sweep.
          (sessions/prune-watches! thread-id)
          thread-id)))))

(defn detach!
  "Release every subscription TOKEN's connection holds and forget it. This is the close
  handler, so a tab that goes away takes its whole set with it -- there is nothing left
  for the server to remember.

  AND NOTHING LEFT FOR ANYBODY ELSE EITHER: a watcher that named this conversation's live
  subscription as its OWNER (`sessions/watch!`'s third argument -- the trajectory stream) is
  pruned here, for the conversations this connection was the last reader of."
  [token]
  (when-some [token (some-> token str not-empty)]
    (locking connections
      (let [[before] (swap-vals! connections dissoc token)
            ;; THE ROW, NOT THE TABLE. This line read `(:subs before)` -- `before` is the
            ;; WHOLE registry -- so it was always nil and this close handler never released a
            ;; single doorbell: the connection row went and its watchers stayed in
            ;; `sessions/watchers` forever, holding their closures and pinning their
            ;; conversations. 199 of them over 32 conversations when it was measured
            ;; (`.scratch/memory-hygiene/` ticket 02, 2026-09-29).
            subs (:subs (get before token))]
        (doseq [[thread-id sub] subs]
          (sessions/unwatch! thread-id (:watch sub)))
        (doseq [thread-id (keys subs)]
          (sessions/prune-watches! thread-id))))
    nil))

(def ^:private run-buffer-size
  "How many of a conversation's most recent RUN frames are kept for a reader that
  reconnects. A GAP IS SHORT BY NATURE -- the client re-declares a cursor it held a moment
  ago -- so this is a bound on memory, not a promise to a reader that was away for a whole
  turn. `harness.edge.http/mux-broadcast!` is the one writer."
  4096)

(defonce ^:private runs
  ;; thread-id -> {:next n :frames [[n frame] ..]} -- the CURRENT run's frames, numbered.
  ;;
  ;; WHY THIS EXISTS AT ALL: a run's frames are pushed, and a push that nobody hears is
  ;; gone (the record has the words, but the AG-UI frame stream a runtime reads does not). A
  ;; socket that drops mid-run therefore loses the gap unless the sender remembers it. This
  ;; is that memory, and a client re-declares how far it got (`runSince`) to get the rest.
  (atom {}))

(defn record-run!
  "Number FRAME for THREAD-ID's live run, remember it, and answer it WITH its `:seq`.
 
  A FRAME THAT ALREADY CARRIES A NUMBER KEEPS IT: a subagent's frames are numbered by
  `run-subagent!`'s own counter (the same one the record carries), and renumbering them
  here would break the boundary the replay and the live tail are joined by. A frame with
  no number -- a run a page is DRIVING -- gets one from this thread's counter.
  
  A RUN_STARTED STARTS A FRESH BUFFER: a new run is not a continuation of the old one's
  numbering, and a reader that reconnects mid-run must not be handed the previous run's
  tail."
  [thread-id frame]
  (let [tid (str thread-id)
        reset? (= "RUN_STARTED" (:type frame))
        [_ after] (swap-vals!
                   runs
                   (fn [m]
                     (let [{:keys [next frames]} (get m tid)
                           base (if reset? 0 (long (or next 0)))
                           n    (long (or (:seq frame) (inc base)))
                           kept (if reset? [] (or frames []))]
                       (assoc m tid {:next   (max n base)
                                     :frames (vec (take-last run-buffer-size
                                                          (conj kept [n (assoc frame :seq n)])))}))))
        n (first (peek (get-in after [tid :frames])))]
    (assoc frame :seq n)))

(defn run-frames-after
  "THREAD-ID's remembered run frames whose number is greater than SINCE, oldest first.
  SINCE nil means 'I hold nothing', which is what a reader that never saw a frame
  declares."
  [thread-id since]
  (let [{:keys [frames]} (get @runs (str thread-id))
        since (long (or since 0))]
    (->> (or frames [])
         (filter (fn [[n _]] (> (long n) since)))
         (mapv second))))

(def fact-types
  "THE FRAME NAMES THE FACT FAMILY SPEAKS, IN ONE PLACE. A fact is about a conversation
  rather than a part of it -- a turn's two ends and a model call's two ends (ADR 0006) --
  and it rides the same socket as a run's frames, carrying the RECORD's `seq` (a line
  number), which is what aligns the pushed half with the window half.

  WHY THE LIST LIVES HERE AND NOT AT THE WRITER: `harness.edge.http/family-send!` writes
  them, and this namespace already owns the other half of 'what a fact is' -- the bounded
  per-conversation ring a reconnecting reader is handed (`record-fact!` / `facts-after` /
  `fact-buffer-size`, just below). A name the writer knows and the ring does not is a
  fact nobody can ask for again.

  AND THE CLIENT HAS ITS OWN COPY (`ui/src/lib/mux.ts`'s `FACT_TYPES`, which `familyOf`
  answers with). A name that gets into one and not the other is not a display bug: the
  frame falls through the client's routing into the RUN family, `@ag-ui/client`'s schema
  refuses it, and the whole run goes down. Two processes, two languages -- so the
  agreement is pinned by a case that reads the WIRE, not by the compiler: see
  `ui/test/suites/frames.ts`'s `the-wire-says-which-names-are-facts`.
  `test/harness/test_support.clj` reads this Var rather than spelling the set again."
  #{"turn/start" "turn/end" "model/start" "model/end" "step/start" "step/end"})

(def ^:private fact-buffer-size
  "How many of a conversation's most recent FACTS (`turn/*`, `model/*`, ADR 0006) are kept for
  a reader that reconnects. A GAP IS SHORT BY NATURE -- the client re-declares a cursor it held
  a moment ago -- so this is a bound on memory, not a promise to a reader that was away for a
  whole turn. `harness.edge.http/family-send!` is the one writer."
  1024)

(defonce ^:private facts
  ;; thread-id -> [fact ..], oldest first, each carrying the RECORD's `seq` (a line number).
  ;;
  ;; WHY THIS EXISTS: a fact is a PUSH like a run frame, and a push nobody heard is gone. The
  ;; difference from `runs` above is the numbering -- a run's frames are numbered by the sender
  ;; (`record-run!`), while a fact already HAS its number: the record line it was written for.
  ;; So there is no counter here, only a bound.
  (atom {}))

(defn record-fact!
  "Remember FACT for THREAD-ID, so a reader that reconnects can be handed the ones it missed.
  A fact with no `:seq` is still sent -- it is simply not something a cursor can ask after --
  and it is remembered all the same (a reader that declares 'I hold nothing' gets it)."
  [thread-id fact]
  (let [tid (str thread-id)]
    (swap! facts update tid
           (fn [kept] (vec (take-last fact-buffer-size (conj (or kept []) fact)))))
    fact))

(defn facts-after
  "THREAD-ID's remembered facts whose record `seq` is greater than SINCE, oldest first. SINCE
  nil means 'I hold nothing', which is what a reader that never saw a fact declares. A fact
  that carries no `:seq` is only ever handed to that reader: a cursor cannot place it."
  [thread-id since]
  (let [kept (or (get @facts (str thread-id)) [])]
    (if (nil? since)
      (vec kept)
      (let [since (long since)]
        (->> kept
             (filter (fn [f] (let [n (:seq f)] (or (nil? n) (> (long n) since)))))
             vec)))))

(defn channels-for
  "Every connection's channel watching THREAD-ID, for a broadcaster that has a frame of
  its own to send (`harness.edge.http`'s run emitter). `mux-send!` on a closed channel is
  caught there, so a channel that goes away between this and the send costs nothing."
  [thread-id]
  (let [tid (str thread-id)]
    (into []
          (keep (fn [[_ conn]] (when (contains? (:subs conn) tid) (:ch conn))))
                @connections)))

(defn subscriptions
  "The conversations TOKEN's connection is watching, in no particular order."
  [token]
  (keys (:subs (get @connections (str token)))))

(defn connections*
  "The whole registry, for tests and diagnostics. Never a value a route answers with."
  []
  @connections)
