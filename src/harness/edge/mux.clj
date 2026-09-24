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
  conversation). A ring for an unsubscribed conversation walks nothing and sends nothing."
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
  earlier connection under the same name left behind (a token is meant to be fresh)."
  [token ch]
  (swap! connections assoc (str token) {:ch ch :subs {}})
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

(defn subscribe!
  "Register THREAD-ID on TOKEN's connection: every ring for that conversation calls PUSH.
  Idempotent -- a second call replaces the push and its doorbell rather than stacking a
  second one, which is what a client re-declaring its set (a `since` that moved, a
  reconnect) means.

  Answers true when the conversation was NOT already subscribed (http reads it to decide
  whether a whole opening frame is owed), false when it was replaced."
  [token thread-id push]
  (let [token (str token)
        thread-id (str thread-id)
        existing (get-in @connections [token :subs thread-id])]
    (when-some [old existing] (sessions/unwatch! thread-id (:watch old)))
    (let [watch (fn [_ _] (push))]
      (swap! connections assoc-in [token :subs thread-id] {:watch watch :push push})
      (sessions/watch! thread-id watch))
    (nil? existing)))

(defn unsubscribe!
  "Stop watching THREAD-ID on TOKEN's connection (the client closed it, or moved off it).
  Answers the thread id when something was released, nil when there was nothing."
  [token thread-id]
  (let [token (str token)
        thread-id (str thread-id)
        [before] (swap-vals! connections update-in [token :subs] dissoc thread-id)]
    (when-some [sub (get-in before [token :subs thread-id])]
      (sessions/unwatch! thread-id (:watch sub))
      thread-id)))

(defn detach!
  "Release every subscription TOKEN's connection holds and forget it. This is the close
  handler, so a tab that goes away takes its whole set with it -- there is nothing left
  for the server to remember."
  [token]
  (when-some [token (some-> token str not-empty)]
    (let [[before] (swap-vals! connections dissoc token)]
      (doseq [[thread-id sub] (:subs before)]
        (sessions/unwatch! thread-id (:watch sub)))
      nil)))

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
