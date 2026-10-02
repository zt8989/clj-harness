(ns harness.edge.turns
  "THE TURNS A RECORD HOLDS, folded from the rows that write them down (ADR 0017):
  `[{:from N :to M :steps n :messages n} …]`, oldest first.

  IT READS ROWS, AND ONLY ROWS. Where a turn begins is not decided here -- `harness.edge.http`
  writes a `turn/start` row when a person says something and a `turn/end` row when the run that
  carried it reached its terminal event, and this fold is nothing but those two rows gathered.
  A record with no such rows has NO TURNS -- that is ADR 0017's decision 8, and it is why a
  record written before 2026-10-02 reads as a conversation without them.

  NOT `harness.edge.turn` (singular): that namespace is the WRITER's own count of the turn it is
  in the middle of (`harness.edge.http` asks it for the numbers `turn/end` carries), and it is
  not a reader. This one is the reader, and it is the only one.

  A TURN THAT HAS NOT CLOSED has `:from` and no `:to` / `:steps` / `:messages` -- absent, not
  zero: the record has not said them yet."
  (:require [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]))

(defn turn-id
  "THE TURN'S NAME IS DERIVED, NOT MINTED (ADR 0006 decision 3, kept by 0017): the record line
  it opened on. ONE SPELLING -- the fact that goes down the wire and the window that is handed
  the record's own turns have to name the same turn."
  [thread-id from]
  (str thread-id "-t" from))

(defn state-init
  "No turn yet."
  []
  [])

(defn state-step
  "ONE ROW of the fold -> the next state, with CTX ignored -- the shape every consumer fold has
  (`harness.edge.stats/stats-step`), so one function drives the birth walk, the write stream,
  and a cold read.

  A TURN OPENS on a `turn/start` row and carries only the line it opened on: the counts are not
  known when the boundary is written. IT CLOSES on the `turn/end` row that follows it, which
  carries them (`:steps` / `:messages`) -- the same two numbers the fact pushes."
  [st _ctx [i row]]
  (when (some? st)
    (case (replay/kind row)
      "turn/start" (conj st {:from i})
      "turn/end"   (if (seq st)
                     (conj (pop st)
                           (assoc (peek st)
                                  :to       i
                                  :steps    (:steps (replay/payload row))
                                  :messages (:messages (replay/payload row))))
                     st)
      st)))

(defn records->turns
  "A whole record's turns, for a conversation this process does NOT hold -- the fold is not there
  to ask, and the window still needs to hand the reader the turns the record has.

  A SECOND ENTRY POINT, NOT A SECOND RULE: it is `state-step` over the rows, in order."
  [records]
  (reduce (fn [st pair] (state-step st nil pair)) (state-init) (map-indexed vector records)))

(defn install!
  "Register this fold on BOTH of a session's seams (the birth walk and the write stream), the
  shape `harness.edge.turn/install!` established. Idempotent; returns the teardown."
  []
  (sessions/register-fold! :turns {:init state-init :step state-step})
  (sessions/register-step! :turns state-step)
  (fn teardown []
    (sessions/unregister-fold! :turns)
    (sessions/unregister-step! :turns)))
