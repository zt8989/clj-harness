(ns harness.parked
  "The calls waiting for a human: the registry, the verdicts, and the signal a
  tool raises when it cannot finish without an answer.

  WHY THIS IS NOT IN harness.tools ANY MORE. It was, and it was the right place
  while the seam was the only thing that parked a call. Two kinds of caller exist
  now -- the seam (an approval rule suspends a call before it runs) and a tool's
  own body (an MCP server asks a question mid-call) -- and the second one lives in
  a namespace the tool table already requires, so keeping the registry next to the
  seam would have made the two require each other. THE REGISTRY IS SHARED STATE,
  so it gets its own home and both sides require it; nothing here knows about
  tools, hooks or MCP.

  PROCESS-LOCAL AND SHORT-LIVED, deliberately. A restart loses every pending
  decision, and a resume naming an interrupt this process never parked is refused
  by name rather than guessed at. Nothing here is persisted: the record exists
  between the park and the verdict being consumed, and a conversation's actual
  state is its log.

  THE ONE THING THAT IS NOT A RECORD: `suspend!`, which parks a call AND throws.
  That is how a tool body stops mid-flight -- there is no returning from the
  middle of somebody else's function -- and the seam is what catches it."
  (:require [clojure.string :as str]))

(def ^:dynamic *tool-call-id*
  "The CALL being executed, bound by the execution seam (harness.tools/run!)
  around a tool body.

  IT LIVES HERE because a parked record is keyed by exactly (thread-id,
  tool-call-id), so this is the namespace that already speaks that pair -- and
  because the suspension path needs it from both sides (the seam parks, a tool's
  body signals) while the two namespaces that own those sides must not require
  each other. Unbound outside a run."
  nil)

(defonce ^:private registry
  ;; interrupt-id -> {:thread-id .. :tool-call-id .. :name .. :args ..}
  (atom {}))

(defn park-approval!
  "Record a parked call under INTERRUPT-ID -- the correlation key a client hands
  back on resume. Deliberately process-local: a restart loses the parking, and a
  resume naming an interrupt this process never parked is answered as unknown
  rather than guessed at. Never persisted, never read back from disk.

  Re-parking the same id reopens it: any earlier decision is cleared, so a call
  that had to be parked twice cannot inherit the first verdict."
  [interrupt-id rec]
  (swap! registry update interrupt-id
         (fn [old] (merge (dissoc old :verdict :payload :consumed)
                          (assoc rec :interrupt-id interrupt-id)))))

(defn parked
  "The parked record for INTERRUPT-ID, or nil -- what the approval endpoint and
  the resume path look up, and what a test asserts on. Process-local and
  short-lived: it exists between the park and the verdict being consumed."
  [interrupt-id]
  (get @registry interrupt-id))

(defn parked-for-call
  "The parked record for TOOL-CALL-ID in THREAD-ID, or nil. Call ids are unique
  per assistant message, so at most one record matches a given call."
  [thread-id tool-call-id]
  (->> @registry
       vals
       (filter #(and (= thread-id (:thread-id %))
                     (= tool-call-id (:tool-call-id %))))
       first))

(defn parked-calls
  "interrupt-id -> parked record, for THREAD-ID (every thread when nil)."
  ([] @registry)
  ([thread-id]
   (into {} (filter #(= thread-id (:thread-id (val %))) @registry))))

(defn decide-approval!
  "Record the human's decision for INTERRUPT-ID: :approved or :vetoed, plus any
  payload the client attached (a reason, typically). Recording decides nothing by
  itself -- the seam consumes the verdict on the call's next transit through it."
  [interrupt-id verdict payload]
  (swap! registry update interrupt-id
         (fn [rec] (assoc (or rec {}) :interrupt-id interrupt-id
                          :verdict verdict :payload payload))))

(defn take-decision!
  "Atomically take -- and mark consumed -- the decision for INTERRUPT-ID. Returns
  {:verdict .. :payload ..} the first time and nil ever after, so replaying an
  interrupt cannot execute its call twice."
  [interrupt-id]
  (let [[before _]
        (swap-vals! registry
                    (fn [reg]
                      (cond-> reg
                        (and (get-in reg [interrupt-id :verdict])
                             (not (get-in reg [interrupt-id :consumed])))
                        (assoc-in [interrupt-id :consumed] true))))]
    (let [rec (get before interrupt-id)]
      (when (and (:verdict rec) (not (:consumed rec)))
        (select-keys rec [:verdict :payload])))))

;; ------------------------------------------------------------------ dispatch

;; ------------------------------------------------------------------ the signal

(defn suspend!
  "Park the call THREAD-ID/TOOL-CALL-ID is running, and STOP IT: this throws out
  of the tool body, out of whatever the body was waiting on, and into the seam.

  WHY IT THROWS. A tool body cannot return halfway -- the value it returns IS the
  call's result -- so the only way to say 'stop here, I need an answer' is to
  unwind. The seam catches this, reports the call as suspended, and lets the run
  end on an interrupt; the answer arrives on a later run and the call is issued
  again (see harness.mcp's elicitation handler, which is what re-issues it).

  QUESTION describes what is being asked and rides the parked record, so a client
  can render a form without inventing anything. It is NOT protocol: the interrupt
  carries an id, a reason and a line for a human, and the schema is fetched from
  the harness's own edge.

  A caller that catches this exception is breaking the contract."
  [thread-id tool-call-id question]
  (let [interrupt-id (or (:interrupt-id (parked-for-call thread-id tool-call-id))
                         (str (java.util.UUID/randomUUID)))]
    (park-approval! interrupt-id (assoc question
                                        :thread-id thread-id
                                        :tool-call-id tool-call-id
                                        :reason :elicitation))
    (throw (ex-info (str "the call suspended on a question: " (:prompt question))
                    {::suspended true
                     ::interrupt-id interrupt-id
                     ::question question}))))

(defn suspended?
  "Did T come out of `suspend!`? The seam's question, asked without the seam
  having to know who asked or why."
  [t]
  (boolean (::suspended (ex-data t))))

(defn suspension
  "T's suspension, as a map -- {:interrupt-id .. :question ..} -- or nil."
  [t]
  (when (suspended? t)
    (select-keys (ex-data t) [::interrupt-id ::question])))
