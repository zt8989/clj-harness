(ns harness.edge.turn
  "THE CURRENT TURN'S COUNTS, folded from a session's record: how many model calls it has made and
  how many assistant messages it has written -- the two numbers the client's fold line already
  draws (`ui/src/lib/turns.ts`'s `turnCounts`, whose keys these are).

  WHY A FOLD OF ITS OWN RATHER THAN A NUMBER `stats` COULD ALSO ANSWER. `harness.edge.stats`
  counts a whole SESSION and its `:steps` is the session's model calls. What `turn/end` carries is
  a different unit: two counts of ONE turn. Same arithmetic, different unit, so a fold per unit.

  IT IS READ AT THE END OF A TURN AND RESET AT THE START OF THE NEXT ONE (`set-fold-value!`, the
  door an on-demand consumer comes through). A turn can span runs -- park, then resume -- so the
  reset is what makes these counts 'this turn' rather than 'since this session began'.

  A NIL STATE STAYS NIL: a session that does not hold this fold (it was built before the fold was
  registered) has nothing to advance. See `harness.edge.stats/stats-step`."
  (:require [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]))

(defn state-init
  "A turn that has done nothing yet."
  []
  {:calls 0 :messages 0})

(defn state-step
  "ONE ROW of the fold -> the next state, with CTX ignored -- the shape every consumer fold has
  (`harness.edge.stats/stats-step`), so one function drives the birth walk, the write stream, and
  a cold read.

  ONE TOOL CALL IS ONE `TOOL_CALL_START` FRAME (every call hangs off an assistant message,
  `harness.edge.ag-ui`), and ONE ASSISTANT MESSAGE IS ONE `message` ROW whose envelope says the
  model returned it (`:source` = `model`). Those are the client's own readings of the same words."
  [st _ctx [_ row]]
  (when (some? st)
    (let [k (replay/kind row)]
      (cond
        (and (= "message" k) (= "model" (:source row))) (update st :messages inc)
        (and (= "event" k) (= "TOOL_CALL_START" (:type (replay/payload row)))) (update st :calls inc)
        :else st))))

(defn answer
  "STATE -> the two counts `turn/end` carries, or nil for a session that does not hold the fold
  (the caller then sends the turn's end without them, rather than inventing zeroes)."
  [st]
  (when (some? st) {:calls (:calls st) :messages (:messages st)}))

(defn records->turn
  "A whole record -> the counts of the LAST turn in it -- the offline twin, for tests."
  [records]
  (answer (reduce (fn [st pair] (state-step st nil pair))
                  (state-init)
                  (map-indexed vector records))))

(defn install!
  "Register this fold on BOTH of a session's seams (the birth walk and the write stream), the shape
  `harness.edge.pressure/install!` established. Idempotent; returns the teardown."
  []
  (sessions/register-fold! :turn {:init state-init :step state-step})
  (sessions/register-step! :turn state-step)
  (fn teardown []
    (sessions/unregister-fold! :turn)
    (sessions/unregister-step! :turn)))
