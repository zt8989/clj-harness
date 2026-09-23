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
