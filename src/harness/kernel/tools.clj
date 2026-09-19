(ns harness.kernel.tools
  "The tool table's SEAM: the per-session overlay, the parked calls a human still
  has to answer, and the three-phase execution. It knows what a tool IS and what
  a call MEANS; it knows no particular tool. `harness.cap.tools` holds the built-in
  this harness ships, and they arrive through `install!`.

  A tool is
     {:description string :parameters JSON-Schema :required [kw..] :run (fn [args] string)}

  RUN gets a keyword-keyed argument map and returns a string.

  run! takes a tool call in the PROVIDER's shape -- {:id .. :type \"function\"
  :function {:name .. :arguments json-string}} -- because the kernel keeps messages
  in the provider shape and never converts. It never throws and never returns nil:
  a tool failure is information for the model, not a failure of the run.

  THE TABLE IS WHAT WAS INSTALLED, not what is in this file. `install!` takes a
  contribution and answers the teardown that withdraws exactly that layer; with
  nothing installed the base table is EMPTY and every call answers 'unknown tool'.
  A session's own additions and switches are a second, per-thread layer on top,
  and neither stands in for the other (see install! below).

  THE SEAM IS ALSO WHERE THE HOOKS SPEAK. Every call ends in one of three
  outcomes -- allow, block, or suspend -- and two hook points decide alongside the
  checks this namespace has always made:

    PreToolUse        may turn an allow into a block. Runs LAST, after the
                      disabled / missing-args / approval checks: a call that
                      cannot run anyway should not be put to a user's rulebook.
    PermissionRequest may answer a SUSPENDED call, so a rule can do what a person
                      would have been interrupted for. Runs only when nothing is
                      decided yet; an answer carries the same weight a human's
                      does, and no answer leaves the call parked.

  The suspend-type rules are also here and are not a separate mechanism from the
  hooks: :requires-approval on a tool, and session-require-approval! for a whole
  session, are how THIS session installs a rule that says 'ask before running
  this'. The hook engine's answer to such a call is what makes them delegable.

  THIS NAMESPACE REQUIRES NO CAPABILITY AT ALL, which is the whole point of the
  layer split and worth spelling out because two of the three edges that used to be
  here were real work:
    - the one-commit batch's arithmetic is a PLANNER, installed through `install!`;
      this file only routes what the plan says.
    - 'which names does this session serve' is a NARROWING POLICY, installed the
      same way; this file only asks it and relays the refusal it answers with.
  Both are contributions, not requirements, so the seam owns the ORDER things happen
  in and the capabilities own what they are."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness.kernel.event :as ev]
            [harness.kernel.hooks.dispatch :as hook]))

;; A resident namespace, so `def`s in eval persist across calls. This is what lets
;; the agent build itself a toolset -- and hot-swap the kernel with (require .. :reload).
(create-ns 'harness.user)
(binding [*ns* (the-ns 'harness.user)] (clojure.core/refer-clojure))

;; ------------------------------------------------------------------- the base

(defonce registry
  (atom {}))
;; The MATERIALIZED base table: every installed layer folded in install order.
;; Rebuilt from the layer stack on every install and teardown, and read by
;; everything that used to read a table registered at load time -- so layering is
;; a fact about the door rather than a second code path to keep in step.

(defonce ^:private layers
  (atom []))
;; The installed layers, in arrival order:
;;   [{:id .. :name .. :tools {name tool} :disable [name ..]} ..]
;; Later wins, which is what lets a later layer REPLACE an earlier one's
;; definition without that earlier layer being unloaded first.

(defonce ^:private installed-planner
  (atom nil))
;; The planner some layer contributed, or nil. A planner answers "which of this
;; turn's calls are one commit, and what is each call's part in it" -- the seam
;; routes what it says and knows nothing about how it decided. Last contributor
;; wins, like a definition does.

(defonce ^:private installed-narrowings
  (atom []))
;; The narrowing policies installed, in ARRIVAL ORDER. Each answers two things and
;; the seam relays both without understanding either: WHETHER this session serves a
;; name, and WHAT to tell the model when it does not. With none installed every name
;; is served -- see served? for why that default is a promise rather than a guess.
;;
;; MORE THAN ONE, BECAUSE A TABLE CAN BE NARROWED BY MORE THAN ONE BOUNDARY. The
;; editing mode is one ("this session edits by anchor, so `edit` is not in its
;; table"); a subagent's declared capability is another. They are separate
;; statements about the same session and neither subsumes the other, so the seam
;; keeps them apart instead of demanding that one layer know about both. With ONE
;; installed -- which is every process until something installs a second -- the
;; behaviour is byte-for-byte what it was when the slot held a single policy.
;;
;; THEY ARE ASKED IN ARRIVAL ORDER and the FIRST one that says "not served" owns
;; the refusal: it is the one with the more specific thing to say, because the
;; layer that narrowed this name is the layer that knows what replaced it.

(defonce ^:private base-disabled
  (atom {}))
;; name -> the layer that switched it off. A switch-off is NOT a removal: the
;; definition stays in the table, so the model still sees the tool and learns it
;; exists, and the CALL is refused by name, saying who switched it off. See
;; disabled-message; session-disable! is the per-thread half of the same rule.

(defn- fold-layers
  [layers]
  (reduce (fn [acc layer]
            (-> acc
                (update :tools merge (:tools layer))
                (update :disabled into (map (fn [n] [n (:name layer)])) (:disable layer))
                (update :planner #(or (:planner layer) %))
                (update :narrows conj (:narrow layer))
                (update :unattended #(or (:unattended layer) %))
                ;; ...AND THE TWO CONTRIBUTIONS THAT ANSWER FOR ONE SESSION AT A
                ;; TIME. A layer whose tools depend on the SESSION (an external
                ;; server's roster) or whose switch-off is per-session cannot say
                ;; so in a static map, so it says it with a function. Folded
                ;; after the static ones: a later layer's dynamic answer wins over
                ;; an earlier layer's static one, which is the same "later wins"
                ;; the rest of this door follows.
                (update :tools-for conj (:tools-for layer))
                (update :disabled-for conj (:disabled-for layer))))
          {:tools {} :disabled {} :planner nil :narrows [] :unattended nil
           :tools-for [] :disabled-for []}
          layers))

(defonce ^:private installed-unattended
  (atom nil))
;; The unattended policy some layer contributed, or nil. It answers ONE question
;; about a thread: can this thread STOP AND WAIT FOR A HUMAN? Nil -- the default,
;; and every process until a layer says otherwise -- means every thread can, which
;; is what a run whose client holds an approval card does. A layer answers a
;; sentence when it cannot, and the seam answers the call with that sentence
;; instead of parking it. Last contributor wins, like a definition does.
;;
;; IT IS THE SEAM'S BUSINESS BECAUSE PARKING IS. "This call stops here and a person
;; decides" is an outcome this namespace owns; whether the thread has a person to
;; stop for is a fact about the thread, which the seam cannot know and a capability
;; can. Keeping the two apart is what lets a subagent be told, in its own tool
;; result, that the call needed an approval it cannot wait for -- instead of its run
;; ending on an interrupt nobody is watching.

(defonce ^:private session-sources
  ;; The installed :tools-for contributions, in install order. Called on the way
  ;; to every LLM request (specs) and by every call (run!), so a contribution that
  ;; is expensive is expensive everywhere -- the capability's problem, not this
  ;; door's.
  (atom []))

(defonce ^:private session-switches
  ;; The installed :disabled-for contributions. Each answers nil, or a sentence
  ;; about WHY this name is off for this thread.
  (atom []))

(defn- recompute!
  []
  (let [{:keys [tools disabled planner narrows unattended tools-for disabled-for]} (fold-layers @layers)]
    (reset! registry tools)
    (reset! base-disabled disabled)
    (reset! installed-planner planner)
    (reset! installed-narrowings (vec (remove nil? narrows)))
    (reset! installed-unattended unattended)
    (reset! session-sources (vec (remove nil? tools-for)))
    (reset! session-switches (vec (remove nil? disabled-for)))))

(defn base-disabled-by
  "The layer that switched NAME off process-wide, or nil. The refusal names it,
  which is the whole reason a layer carries a :name."
  [name]
  (get @base-disabled name))

(defn install!
  "Install CONTRIBUTION and answer the TEARDOWN that withdraws it.

  CONTRIBUTION is a map:
    {:name    \"built-ins\"       ; what a refusal calls this layer; optional
     :tools   {\"read\" <tool>}    ; definitions to add. A name already in the base
                                 ;   is REPLACED -- later wins.
     :disable [\"bash\"]          ; names to switch off, which KEEPS them visible
     :planner (fn [thread-id calls])  ; how a turn's calls become one commit, or a
                                 ;   map of each call's part. Optional: with none
                                 ;   installed a turn is just calls, and every one
                                 ;   of them runs on its own.
     :narrow  {:served? (fn [thread-id name])   ; which names this session is served
               :refuse  (fn [thread-id name])}   ; ...and what to say when it is not
                                                 ;   ONE policy per layer, and a layer
                                                 ;   may install a second one beside
                                                 ;   another's: they are asked in
                                                 ;   arrival order and the first that
                                                 ;   says it is not served is the one
                                                 ;   whose refusal the model reads.
     :unattended  (fn [thread-id name reason])   ; nil, or what to tell a call that
                                                 ;   needs a human this thread cannot
                                                 ;   wait for. Nil -- the default --
                                                 ;   means every thread can wait.
     :tools-for    (fn [thread-id] {name tool})  ; tools whose EXISTENCE is a
                                                 ;   question about the session
     :disabled-for (fn [thread-id name])}        ; nil, or {:by .. :message ..}
                                                 ;   when something switched it off

  THE TEARDOWN WITHDRAWS THIS LAYER BY IDENTITY and folds the rest again. That is
  the point of keeping the stack rather than deleting names: 'A installed x, B
  replaced x, A tears down' must leave B's x, and an uninstall that deleted the
  name would take B's definition with it -- the ORDER of teardowns would then be
  load-bearing, which is the kind of coupling a setup/teardown shape exists to
  remove. Calling a teardown twice is a no-op.

  NOT THE SAME AS SWITCHING A NAME OFF. A teardown withdraws a contribution; a
  :disable leaves the definition in place and refuses its calls. Disabling on the
  way in and deleting on the way out are different statements, and a caller that
  wants the second has to mean it."
  [{:keys [name tools disable planner narrow unattended tools-for disabled-for]
    :as _contribution}]
  (let [id    (str (java.util.UUID/randomUUID))
        layer {:id id :name (or name id) :tools (or tools {})
               :disable (vec disable) :planner planner :narrow narrow
               :unattended unattended
               :tools-for tools-for :disabled-for disabled-for}]
    (swap! layers conj layer)
    (recompute!)
    (fn teardown []
      (swap! layers (fn [ls] (filterv #(not= (:id %) id) ls)))
      (recompute!)
      nil)))

;; ------------------------------------------------------------ session tools

(def ^:dynamic *thread-id*
  "Bound by the execution seam (harness.kernel.tools/run!) to the thread whose run the
  current tool call serves, so code inside a tool -- eval above all -- can
  address its own session. Unbound outside a run.")

(defonce ^:private overlays
  (atom {}))
;; thread-id -> {:added {name tool} :disabled #{name}}
;;
;; Two orthogonal axes over one immutable base:
;;   :added     the presence half -- definitions this session contributed.
;;   :disabled  the availability half -- names this session switched off. The
;;              definition is untouched and the tool STAYS in the toolset; the
;;              execution seam (harness.kernel.tools/run!) is what refuses the call.
;; There is deliberately no :removed: nothing may vanish from a toolset, because
;; a model that cannot see a tool reads its absence as "this capability does not
;; exist" and goes looking for a way around it. Disabled is honest; hidden is not.

(declare effective-tools session-disabled-elsewhere? session-tools parked-for-call park-approval!)
;; The narrowing policy's two relays are used by , which is above them; the
;; policy itself arrives through install! and the definitions sit below with the
;; rest of the refusal vocabulary.
(declare served? unserved-message)

(defn session-register!
  "Add NAME->TOOL for THREAD-ID's session only. Registering over a base tool's
  name SHADOWS it for this session -- the base definition is untouched -- and
  the change is visible to the next run of this thread, never to another.

  Re-adding a name that was retracted starts it ENABLED: retraction clears the
  disabled mark, so a fresh definition never inherits a stale one."
  [thread-id name tool]
  (swap! overlays assoc-in [thread-id :added name] tool))

(defn session-unregister!
  "Retract NAME from THREAD-ID's session -- the presence half only. This undoes
  a session-register! and nothing else: a base tool's name is a no-op, because
  base tools cannot be removed (as of tool-toggles, nothing leaves a toolset;
  use session-disable! to take one's availability away). A name that was never
  added is also a no-op. The base registry is never mutated.

  Retracting also drops NAME's disabled mark, so re-adding it later is enabled."
  [thread-id name]
  (swap! overlays
         (fn [ov]
           (if (contains? (get-in ov [thread-id :added]) name)
             (-> ov
                 (update-in [thread-id :added] dissoc name)
                 (update-in [thread-id :disabled] (fnil disj #{}) name))
             ov))))

(defn session-disable!
  "Switch NAME off for THREAD-ID's session only -- the availability half. The
  tool remains in the session's toolset and its definition is untouched; the
  execution seam refuses calls of it with a :disabled outcome, and
  session-enable! brings it straight back. Reversible, idempotent, and a no-op
  for a name the session cannot see -- disabling never invents a tool.

  This is a policy switch, NOT a security boundary: hiding a capability is not
  the same as forbidding the behaviour (disabling `write` does not stop `bash`
  from writing a file). The enforced bounds are the approval park and the
  sandbox, not the toolset."
  [thread-id name]
  ;; THE GUARD READS THE BASE, AND THE BASE IS NOW INSTALLABLE -- so a switch made
  ;; before the capability is installed is switching nothing, silently. That is the
  ;; honest consequence of "disabling never invents a tool": the mark is only made
  ;; for a name the table actually offers, and the table can change under it. A
  ;; caller that installs on the way in (the composition root, a test fixture) has
  ;; the name present by the time it asks; a caller that does not has switched
  ;; nothing, which is visible in `session-disabled?` rather than assumed.
  (when (contains? (effective-tools thread-id) name)
    (swap! overlays update-in [thread-id :disabled] (fnil conj #{}) name)))

(defn session-enable!
  "Undo session-disable! for NAME. A name that was never disabled is a no-op."
  [thread-id name]
  (swap! overlays update-in [thread-id :disabled] (fnil disj #{}) name))

(defn session-added?
  "Did THIS SESSION put NAME in its own table -- was it session-register!'d rather
  than inherited from the base?

  ASKED BY WHOEVER HAS TO JUDGE A DEFINITION'S PROVENANCE, and it has to be asked
  separately from what the definition SAYS: `session-register!` keeps the map it was
  handed, tags and all, so a name that arrived this way can carry `:source :builtin`
  and `:read-only true` and be neither. Reading the map is not reading its papers.

  The base registry is never consulted here -- a name that is BOTH a base tool and
  a session's own reads as the session's, because that is the copy the session's
  calls will actually run."
  [thread-id name]
  (contains? (get-in @overlays [thread-id :added] {}) name))

(defn session-disabled?
  "Is NAME switched off in THREAD-ID's session? The seam's lookup, per call.

  TWO WAYS TO BE OFF, and the seam does not care which: this session switched this
  TOOL off (the overlay), or something a LAYER knows about switched it off --
  an external server that was turned off takes its tools with it. The second is
  answered by whatever the layer installed, so the seam still knows nothing about
  what a server is."
  [thread-id name]
  (boolean (or (contains? (get-in @overlays [thread-id :disabled] #{}) name)
               (session-disabled-elsewhere? thread-id name))))

(defn- session-disabled-elsewhere?
  "The layer-supplied answer for NAME, or nil. First answer wins, in install
  order, so a switch installed later can still be overruled by nothing at all --
  the FIRST switch that claims a name is the one whose sentence is used."
  [thread-id name]
  (some (fn [f] (try (f thread-id name) (catch Throwable _ nil)))
        @session-switches))

(def ^:dynamic *tool-call-id*
  "The CALL being executed, bound by the seam beside *thread-id* -- the other half
  of the identity a suspension needs. *thread-id* says whose conversation this is;
  this says which of that conversation's calls is asking, which is what lets a tool
  that has to stop mid-flight park ITSELF and be answered by the same id later.

  Unbound outside a run, like its sibling."
  nil)

(defn suspend!
  "Stop the call that is executing, from INSIDE its own body, and ask a human.

  THE THIRD WAY A CALL CAN END, and the only one a tool causes itself. The other
  two are decided for it at the seam: allow, or be refused. This one is a tool
  saying 'I cannot finish without an answer' -- which is what an MCP server asking
  for input means, and why it must NOT be modelled as a tool that returned early
  with a message appended afterwards.

  QUESTION describes what is being asked ({:server .. :prompt .. :schema ..}). It
  rides the parked record, so a client can render a form without inventing
  anything, and NONE of it goes on the wire as protocol: the interrupt carries an
  id, a reason and a line for a human, and the schema comes from this harness's own
  edge.

  THROWS, always: its job is to unwind out of the tool body and out of whatever
  machinery the tool was waiting on, so the seam can turn it into a park. A caller
  that catches it is breaking the contract."
  [thread-id tool-call-id question]
  (let [interrupt-id (or (:interrupt-id (parked-for-call thread-id tool-call-id))
                         (str (java.util.UUID/randomUUID)))]
    (park-approval! interrupt-id (assoc question
                                        :thread-id thread-id
                                        :tool-call-id tool-call-id
                                        :reason :elicitation))
    (throw (ex-info (str "the call suspended on a question: " (:prompt question))
                    {:suspended true
                     :suspended/interrupt-id interrupt-id
                     :suspended/question question}))))

(defn suspended?
  "Did T come out of `suspend!`? The seam's question, asked without the seam
  having to know who asked or why."
  [t]
  (boolean (:suspended (ex-data t))))

(defn effective-tools
  "NAME->TOOL for THREAD-ID: the immutable base overlaid with the session's
  additions. A thread with no overlay sees the pure base; nil THREAD-ID (no
  session context) also means the base.

  DELIBERATELY NOT the set of tools that will run: a disabled tool is still in
  here. Availability is a separate question, answered per call by
  session-disabled? at the execution seam."
  [thread-id]
  (let [base (into @registry (session-tools thread-id))]
    (if (nil? thread-id)
      base
      (into base (get-in @overlays [thread-id :added] {})))))

(defn- session-tools
  "What the installed layers offer THIS SESSION, folded in arrival order.

  A layer's static :tools are in `registry` already; this is the other half --
  the tools whose EXISTENCE is a question about the session. An external server's
  roster is exactly that shape: which tools it has depends on which project's
  declaration is in force and on whether the server is up, so it cannot be a map
  written down at setup.

  A contribution that throws contributes nothing: a source that cannot answer for
  a session must not take the whole toolset down with it. That is the same
  judgement the capability itself makes about one broken server."
  [thread-id]
  (reduce (fn [acc f]
            (try (merge acc (f thread-id)) (catch Throwable _ acc)))
          {}
          @session-sources))


;; --------------------------------------------------------------------- specs

(defn- tool-face
  "The two fields a model actually reads -- :description and :parameters -- for
  NAME's tool in THREAD-ID's session.

  A tool's face is STATIC unless it declares a `:describe` fn, and that escape
  hatch exists for one situation: a tool whose NAME stays the same across the two
  editing modes while what it DOES does not. Two of them do -- `read` (plain text
  in str-replace mode, `anchor│content` rows in hashline mode) and `write` (which
  releases a file's anchors in the mode that has them). The alternative was
  differently-named tools per mode, and the user asked for these to be the same
  tool either way.

  The face varies because the BEHAVIOUR does, and only where it does: a
  description is the model's only view of what a call will do, so a session with
  no anchors is not read a paragraph about releasing them.

  Everything else about a tool -- :required, :run, the markers the seam reads --
  is untouched by this: only what the MODEL sees varies, which keeps the call's
  behaviour a function of the session rather than of the description."
  [thread-id [n t]]
  (if-let [describe (:describe t)]
    (describe thread-id)
    {:description (:description t) :parameters (:parameters t)}))

(defn specs
  "The tools array as an OpenAI-compatible provider expects it, for THREAD-ID's
  effective toolset (base overlaid with its session additions/removals).

  A SESSION IS SERVED A SUBSET, not the whole table: every installed narrowing
  policy gets its say and a name any of them withholds is left out. The editing
  mode's subtraction came first (a session is served ONE editing toolset), and a
  subagent's declared range is the same kind of statement, asked through the same
  door -- so 'which names does this session serve' has one answer here rather than
  one per mechanism. Everything else stays in, including tools this session has
  switched OFF: availability is enforced per call at the execution
  seam, not by omission, and a model that cannot see a switched-off tool would
  read its absence as 'this does not exist'.

  That distinction is worth keeping straight, because the mode's subtraction
  looks like the same trick. It is not, and the difference is what the model
  learns: a disabled tool is VISIBLE and its calls are refused, while an unserved
  tool is absent from the list and its calls are refused by name with the
  substitute and the config key to switch (harness.cap.editing/unserved-message).
  Either way nobody is left guessing -- which is the property both mechanisms are
  actually for."
  ([] (specs nil))
  ([thread-id]
   (mapv (fn [[n t]] {:type "function"
                      :function (assoc (tool-face thread-id [n t])
                                       :name n)})
         (sort-by key (into {}
                            (filter (fn [[n _]] (served? thread-id n))
                                    (effective-tools thread-id)))))))


;; --------------------------------------------------------------- approvals
;;
;; Two things a call can be made to wait on, and the record of who is waiting:
;; a session's own list of tools that need a human, and the parked calls
;; themselves. Both are PROCESS-LOCAL and per-thread -- a restart loses every
;; pending decision, and a resume naming an interrupt this process never parked
;; is refused by name rather than guessed at. Nothing here is persisted.

(defonce ^:private session-approvals
  (atom {}))
;; thread-id -> #{name}

(defn session-require-approval!
  "Make every call of NAME suspend for a decision in THREAD-ID's session only --
  the session's way of installing a rule that says 'ask before running this'.
  Session-scoped exactly like the tool overlay: another thread is unaffected, and
  the process-wide base registry is never touched. The union of this set and the
  tool's own :requires-approval flag is what suspends a call.

  'Ask' means the call parks; WHO answers is not this function's business. A
  human does, unless a PermissionRequest hook answers first -- a rule that
  delegates the decision is the point of that hook, and a session that declares
  one gets its calls decided without interrupting anybody."
  [thread-id name]
  (swap! session-approvals update thread-id (fnil conj #{}) name))

(defn session-approval-required?
  "Does THREAD-ID's session suspend calls of NAME pending a decision?"
  [thread-id name]
  (contains? (get @session-approvals thread-id #{}) name))

(defonce ^:private parked-registry
  (atom {}))
;; interrupt-id -> {:thread-id .. :tool-call-id .. :name .. :args ..}

(defn park-approval!
  "Record a parked call under INTERRUPT-ID -- the correlation key a client hands
  back on resume. Deliberately process-local: a restart loses the parking, and a
  resume naming an interrupt this process never parked is answered as unknown
  rather than guessed at. Never persisted, never read back from disk.

  Re-parking the same id reopens it: any earlier decision is cleared, so a call
  that had to be parked twice cannot inherit the first verdict."
  [interrupt-id rec]
  (swap! parked-registry update interrupt-id
         (fn [old] (merge (dissoc old :verdict :payload :consumed)
                          (assoc rec :interrupt-id interrupt-id)))))

(defn parked
  "The parked record for INTERRUPT-ID, or nil -- what the approval endpoint and
  the resume path look up, and what a test asserts on. Process-local and
  short-lived: it exists between the park and the verdict being consumed."
  [interrupt-id]
  (get @parked-registry interrupt-id))

(defn parked-for-call
  "The parked record for TOOL-CALL-ID in THREAD-ID, or nil. Call ids are unique
  per assistant message, so at most one record matches a given call."
  [thread-id tool-call-id]
  (->> @parked-registry
       vals
       (filter #(and (= thread-id (:thread-id %))
                     (= tool-call-id (:tool-call-id %))))
       first))

(defn parked-interrupts
  "The parked calls for TOOL-CALL-IDS in THREAD-ID, as the interrupt maps a run ends on:
  {:interrupt-id .. :id <the tool call> .. :name .. :args ..}, plus :reason and
  :question when the park carried them. An id no record answers is simply ABSENT,
  which is the fact the asker needs: harness.kernel.loop reads this to tell a call a
  human is still deciding from one nobody can answer at all.
  
  THE SHAPE IS THE ONE :run/interrupt ALREADY EMITS for a call parked THIS turn (see
  the :parked maps in `run!`), because a client must not have to tell 'parked just now'
  from 'parked last turn, and the run is asking again' -- they are the same question and
  the same answer."
  [thread-id ids]
  (vec (keep (fn [id]
               (when-let [rec (parked-for-call thread-id id)]
                 (cond-> {:interrupt-id (:interrupt-id rec)
                          :id            (:tool-call-id rec)
                          :name          (:name rec)
                          :args          (:args rec)}
                   (:reason rec)   (assoc :reason (:reason rec))
                   (:question rec) (assoc :question (:question rec)))))
             ids)))

(defn parked-calls
  "interrupt-id -> parked record, for THREAD-ID (every thread when nil)."
  ([] @parked-registry)
  ([thread-id]
   (into {} (filter #(= thread-id (:thread-id (val %))) @parked-registry))))

(defn decide-approval!
  "Record the human's decision for INTERRUPT-ID: :approved or :vetoed, plus any
  payload the client attached (a reason, typically). Recording decides nothing by
  itself -- the seam consumes the verdict on the call's next transit through it."
  [interrupt-id verdict payload]
  (swap! parked-registry update interrupt-id
         (fn [rec] (assoc (or rec {}) :interrupt-id interrupt-id
                          :verdict verdict :payload payload))))

(defn take-decision!
  "Atomically take -- and mark consumed -- the decision for INTERRUPT-ID. Returns
  {:verdict .. :payload ..} the first time and nil ever after, so replaying an
  interrupt cannot execute its call twice."
  [interrupt-id]
  (let [[before _]
        (swap-vals! parked-registry
                    (fn [reg]
                      (cond-> reg
                        (and (get-in reg [interrupt-id :verdict])
                             (not (get-in reg [interrupt-id :consumed])))
                        (assoc-in [interrupt-id :consumed] true))))]
    (let [rec (get before interrupt-id)]
      (when (and (:verdict rec) (not (:consumed rec)))
        (select-keys rec [:verdict :payload])))))

;; ------------------------------------------------------------------ dispatch

(defn- missing-args [{:keys [required]} args]
  (vec (remove #(contains? args %) required)))

(defn- narrowing-serves?
  "Does ONE installed policy serve NAME for THREAD-ID? A policy that THROWS serves:
  the same promise served? makes below, asked one layer down -- a broken policy must
  not be able to take a working tool away."
  [thread-id name {:keys [served?]}]
  (if served?
    (try (boolean (served? thread-id name)) (catch Throwable _ true))
    true))

(defn served?
  "Does this session serve NAME? NOTHING INSTALLED MEANS EVERYTHING IS SERVED, and
  that is a promise rather than a guess: a policy that fails to load, or an offline
  caller that never installed one, must not turn a working tool into an
  unservable one. The failure would be silent and total, so the default fails open
  and the policy's own answers are the only thing that narrows a table.

  EVERY INSTALLED POLICY MUST SERVE IT, so two boundaries compose as an
  intersection -- the editing mode's subtraction and a subagent's declared range are
  each a statement about the same session, and a name either of them withholds is
  not served. With one policy installed (every process until something installs a
  second) this is exactly the single question it always was.

  PUBLIC BECAUSE A TABLE'S DERIVATION HAS TO ASK IT. 'Which names does this session
  serve' is the question a capability answering for a DIFFERENT session -- a
  subagent's declared range, derived from its parent's -- has to put, and asking it
  per name here is what keeps that derivation from being a second implementation of
  the narrowing rules."
  [thread-id name]
  (every? (fn [policy] (narrowing-serves? thread-id name policy))
          @installed-narrowings))

(defn- unserved-message
  "What the model is told when the policies do not serve NAME. The sentence is the
  FIRST policy's that says so -- it is the one that knows what takes the name's
  place and which configuration key switches back -- and this namespace only relays
  it. With more than one policy installed, the first to refuse owns the wording:
  the layer that narrowed this name is the layer with the specific thing to say,
  and a boundary that has nothing to add about a name it also does not serve should
  not be the one talking."
  [thread-id name]
  (if-let [policy (first (filter :refuse
                                 (remove #(narrowing-serves? thread-id name %)
                                         @installed-narrowings)))]
    ((:refuse policy) thread-id name)
    (str name " is not served in this session.")))

(defn- unattended-reason
  "Whether THREAD-ID can stop and wait for a human: nil it can, or the sentence to
  answer the call with when it cannot. See the installed-unattended atom for why
  this belongs to the seam."
  [thread-id name reason]
  (when-let [policy @installed-unattended]
    (try (policy thread-id name reason) (catch Throwable _ nil))))

(defn- approval-reason
  "WHY this call parks -- nil meaning it does not. Three sources, in the order they
  are or-short-circuited, and only the first two are the seam's own business:

    1. the tool's own declaration -- :requires-approval          -> :tool-declares
    2. this session's ask -- session-require-approval!            -> :session-asks
    3. whatever the tool DECLARES as its park rule                -> that rule's answer

  THE THIRD IS A DECLARATION, NOT A CASE. A tool spec may carry `:park-reason`, a
  function of (thread-id, parsed-args) answering a reason keyword or nil. This
  namespace does not know what any such reason MEANS: it collects one and puts it on
  the parked record, where the human deciding -- and any reader of the audit trail --
  can tell a declared-approval call from a fence catch without re-deriving either.
  That is what keeps the fence (a fact about projects, configuration homes and paths)
  out of the seam while the seam keeps the ORDER, which is the kernel's contract.

  A DECLARATION THAT THROWS IS NOT A PARK. It runs before the argument checks, so a
  rule handed a malformed payload must answer nil -- nothing to judge -- rather than
  signal through an exception; the missing-arguments check reports that payload one
  line later, with the useful message. `:out-of-bounds` is the only reason any tool
  declares today, and 'no path at all' is deliberately not a fence case: read as
  one, it would answer with an exception from inside java.io instead of the honest
  fact that the argument is absent."
  [tool name thread-id parsed]
  (cond
    (:requires-approval tool)                   :tool-declares
    (session-approval-required? thread-id name) :session-asks
    :else
    (when-let [declare-reason (:park-reason tool)]
      ;; Best effort, exactly as the derivation the capability now owns: see the
      ;; fence in harness.cap.tools for the rule itself.
      (try (declare-reason thread-id parsed) (catch Throwable _ nil)))))

(defn- hook-block-message
  "What the model is told when a PreToolUse hook refused the call. Exactly the
  shape of a veto message, because it IS one: the call was stopped before it ran
  and the reason is information, not a run failure -- the loop carries on and the
  model gets to try something else. The only difference is who said no, and it
  says who: a model that reads \"a human vetoed this\" will stop asking for
  things, while one that reads \"a hook blocked this\" knows to look at the rule."
  [reason]
  (str "blocked by a PreToolUse hook: the call was not executed."
       (when (seq (str reason)) (str " reason: " reason))))

(defn- delegated-veto-message
  "What the model is told when a PermissionRequest hook answered \"deny\". Same
  shape as a human veto -- the call did not run, and the reason is information --
  but it says the answer came from a rule, not from the person whose name a veto
  would otherwise put on it."
  [{:keys [answer reason]}]
  (let [why (or (get answer "reason") reason)]
    (str "denied by a PermissionRequest hook: the call was not executed."
         (when (seq (str why)) (str " reason: " why)))))

(defn- veto-message
  "What the model is told when a human vetoed the call. It is information, not a
  failure of the run: the loop carries on with this as the tool's answer."
  [{:keys [payload]}]
  (str "vetoed by human: the call was not executed."
       (when (some? payload) (str " reason: " (json/write-str payload)))))

(defn- disabled-message
  "What the model is told when it calls a tool that is switched off. Like a veto,
  this is information rather than a run failure -- and it says DISABLED, never
  unknown: the tool exists and is on offer, so calling its absence a lie would
  only send the model hunting for a workaround.

  TWO PLACES CAN SWITCH A NAME OFF, and the sentence says which one did, because
  they have different ways back. The SESSION's own switch is one the session can
  undo, so the call to undo it is offered. A process-wide switch declared by an
  installed layer is not the session's to undo -- offering session-enable! there
  would be a false promise that costs a round trip to disprove -- so it names the
  layer instead.

  When the session's editing mode does not serve NAME either, that is stated too:
  re-enabling a tool the mode subtracts changes nothing about the next call, and a
  reader who acts on only half of a doubly-refused call will try it again and be
  told the same thing."
  [thread-id name]
  ;; A LAYER THAT KNOWS WHY SPEAKS FOR ITSELF: an external server that was switched
  ;; off can say which server it was and how to bring it back, and only that layer
  ;; knows. Its sentence is used verbatim -- the seam has nothing to add to an
  ;; answer about something it has never heard of.
  (if-let [elsewhere (session-disabled-elsewhere? thread-id name)]
    (:message elsewhere)
    (if-let [layer (base-disabled-by name)]
    (str "disabled in this harness: " name " is switched off by the " layer
         " layer, which no session can re-enable."
         (when-not (served? thread-id name)
           (str " " (unserved-message thread-id name))))
    (str "disabled in this session: " name
         " is switched off."
         (if (served? thread-id name)
           (str " Re-enable it with (harness.kernel.tools/session-enable!"
                " harness.kernel.tools/*thread-id* \"" name "\").")
           (str " Re-enabling it will not make it run, either: "
                (unserved-message thread-id name)))))))

;; ------------------------------------------------------------------- the batch

;; A TURN'S CALLS CAN BE ONE COMMIT, AND THE SEAM ONLY ROUTES.
;;
;; Several anchor edits to one file in one message have to land as one write -- the
;; alternative LOSES DATA, and the reasoning for that lives with the arithmetic, in
;; the batch section of harness.cap.hashline.replace. What stays here is the part
;; that is about TURNS rather than about editing:
;;
;;   - a turn is handed over once (`register-turn!`), because the run loop is the
;;     only place that sees a whole turn at once;
;;   - the PLAN is whatever the installed planner answers; with none installed
;;     there is no plan, and every call is an ordinary call;
;;   - each call can ask its part in its message (`batch-role`), and the seam acts
;;     on what the entry carries: an appointed call RUNS the group, a member answers
;;     with its note and touches nothing.
;;
;; NOTHING BELOW KNOWS WHAT A `replace` IS. The grouping key, the name test and the
;; wording of a member's note are all the planner's, which is what lets this
;; namespace stop requiring harness.cap.hashline at all.

(defonce ^:private turn-plan (atom {}))
;; thread-id -> {:token <this registration> :plan {call-id -> the part that call
;; plays} :counts {tool-name -> how many calls}}
;;
;; TWO FACTS ABOUT ONE TURN, in one atom, because both answer 'what else is in this
;; message' and a second atom would be a second thing to reset. The plan is the
;; installed planner's (the anchor-edit batching above); the counts are the seam's
;; own, and `sole-call-of-its-name?` is what reads them.
;;
;; KEYED BY THREAD-ID because two sessions run at once -- `harness.edge.http` gives
;; every run its own go block -- and a single slot would let one session's turn
;; overwrite another's, silently degrading an anchor batch into concurrent edits
;; that lose updates. The key is the one a tool BODY can read: a body gets parsed
;; arguments and no call id, but the seam binds `*thread-id*` around it.
;;
;; TWO TURNS ON ONE THREAD-ID AT ONCE is not prevented -- a client may fire two runs
;; at the same id -- and is a KNOWN, ACCEPTED boundary rather than something this
;; registry queues for: the later registration wins that id's slot, and the
;; earlier turn's answers then read the later counts.

(defn- plan-counts
  "How many times each tool NAME is called in this turn.

  A COUNT OF NAMES RATHER THAN OF CALL IDS, so a tool BODY can ask the question:
  bodies receive parsed arguments and nothing else -- no call id -- so a rule that
  a body enforces has to be answerable from the turn alone. Names are what a body
  knows about itself.

  A name that does not exist is counted too, and it cannot matter: the counts are
  keyed by name, so a call of some unknown name never changes the answer for a
  known one."
  [calls]
  (frequencies (keep (fn [call] (get-in call [:function :name])) calls)))

(defn register-turn!
  "Hand the seam the calls of the turn about to run, so each can be told who its
  siblings are. Called by the run loop -- the only place that sees a whole turn at
  once -- and best effort throughout: a planner that throws, or none at all, leaves
  a turn that behaves exactly as it did before batching existed, which is one call,
  one edit. The counts do not depend on any planner, so they are taken either way.

  ANSWERS A TOKEN naming THIS registration and writes it into the entry -- see
  forget-turn! for what it is for. It is handed back rather than derived because
  only the caller knows which turn it is finishing, and two turns on one thread-id
  are otherwise indistinguishable. Other thread-ids' entries are left alone.
"
  [thread-id calls]
  (let [token (str (java.util.UUID/randomUUID))]
    (swap! turn-plan assoc thread-id
           {:token  token
            :plan   (if-let [plan @installed-planner]
                      (try (plan thread-id calls) (catch Throwable _ {}))
                      {})
            :counts (plan-counts calls)})
    token))

(defn forget-turn!
  "Drop a turn's plan once its calls have all answered. Without this the map grows
  with the process, one entry per anchor edit ever made.

  ONLY THE ENTRY THE TOKEN STILL OWNS. A later registration on the same thread-id
  replaces the entry and records a new token; this turn's teardown must then leave
  that one alone, because the later turn's plan is the live one.

  The NO-ARGUMENT arity drops every thread-id's entry. It is what a test fixture
  wants when it resets the seam between cases; the run loop always names its own
  turn."
  ([]
   (reset! turn-plan {}))
  ([thread-id token]
   (swap! turn-plan
          (fn [plans]
            (if (= token (get-in plans [thread-id :token]))
              (dissoc plans thread-id)
              plans)))))

(defn sole-call-of-its-name?
  "Is the call being run the ONLY call of NAME in its turn?

  TWO CALLS OF THE SAME NAME IN ONE MESSAGE are usually fine -- two edits to two
  files, two reads -- but not for a tool that REPLACES a whole value: such a tool
  sends its complete value every time, so a second call in the same message has no
  meaning to merge. A turn's calls run concurrently, so the later write would win
  while BOTH reported success -- silent data loss, arrived at from the other
  direction than the batch above.

  A TOOL THAT CARES ASKS THIS FROM ITS OWN BODY, which is why the answer is a fact
  about the turn rather than a routing decision here: the seam has no idea which
  tools those are, and it should not.

  TRUE WHEN THE TURN WAS NEVER REGISTERED (a direct `run!`, a replayed approval):
  THE ANSWER IS FOR THE SESSION ASKING IT -- the counts of the turn registered under
  the `*thread-id*` the seam has bound around this body, not one shared slot. Two
  sessions running at once each ask about their own message.

  those callers run one call at a time, which is the case the rule is about."
  [name]
  (<= (long (get (:counts (get @turn-plan *thread-id*)) name 0)) 1))

(defn- batch-role
  "What this call's part in its message is, or nil when it is an ordinary call."
  [id]
  (get-in @turn-plan [*thread-id* :plan id]))

(defn run!
  "The ONE tool execution seam. The call's lifecycle is reported to ON-PHASE
  (a fn of kernel events, may be nil) as it passes through:
    :tool/pre-execute   -- entered the seam; outcome :pass, :unknown-tool,
                           :unserved, :disabled, :missing-args (with the missing
                           names), :hook-blocked, :needs-approval, :unattended,
                           :approved, or :vetoed
    :tool/execute       -- left execution; the error message, or nil
    :tool/post-execute  -- closes the lifecycle, whatever the phases decided
  A call that never passes pre-execute (unknown tool, unserved tool, disabled
  tool, missing arguments) skips the :tool/execute phase, but its
  :tool/post-execute still arrives -- the lifecycle is always closed. :disabled
  is checked before approval: a tool this session switched off is refused
  outright, never parked.

  EVERY CALL ENDS IN ONE OF THREE OUTCOMES, and the order they are decided in is
  the point of the whole pre phase:

    ALLOW     it runs. Nothing refused it: no switch, no mode subtraction, no
              missing argument, no gate, no rule that says a human has to look.
    BLOCK     it does not run, and the reason goes back to the model as the
              call's result -- a veto, a hook's exit 2, a disabled switch, a mode
              that does not serve this tool. The run carries on; the model gets
              to try something else.
    SUSPEND   it does not run YET: the run ends on an interrupt and the call is
              the human's until they answer. Nothing executes, no :tool/result
              is emitted, and the tool message lands on the resume run.
              ...UNLESS NOBODY IS WATCHING: a thread an installed unattended
              policy answers for cannot stop for a person, so the call comes back
              as a BLOCK whose reason is that sentence -- parked is not the same
              as forgotten, and a call that silently waited forever would be.

  The decisions are taken in ONE order, first match wins (the cond below):
    disabled -> mode -> missing args -> approval rule -> PreToolUse gate
  The gate is LAST because everything before it is this harness deciding, and a
  gate should not be asked about a call that cannot run anyway.

  `disabled` BEFORE `mode` is the order tool-toggles asked for and it says
  something real: a switch the session threw itself outranks a policy it
  inherited, so the answer a doubly-refused call gets names the thing the caller
  can undo. It does not hide the second refusal -- disabled-message states both
  when both are true. `mode` is second and still ahead of the argument and
  approval checks, because a call this session does not serve is not going to
  run whatever its arguments look like, and there is no reason to park it for a
  human or to read the file it names.

  A call that must be suspended asks TWO things before it bothers a person. It
  first takes any decision already on the parked record -- the resume path --
  and only if there is none does it fire PermissionRequest, the point that lets a
  RULE answer what would otherwise interrupt somebody. A hook's answer has the
  same standing as a human's: \"approve\" runs the call, \"deny\" answers it with
  the hook's reason. A point that says nothing -- no declaration, or a
  declaration that puts no decision on stdout -- leaves the call exactly where it
  was: parked, waiting for a person. That is why a session with no hooks behaves
  byte for byte as it did before hooks existed.

  WHY a call is suspended is computed once per transit (approval-reason) and
  rides the parked record: :tool-declares (the tool's own :requires-approval),
  :session-asks (this session required it), or :out-of-bounds (a fence-marked
  file tool whose path resolves outside the session's project directory and
  the configuration home -- only when a project is bound). Those two switches are
  NOT a separate mechanism from the hook engine -- they are this session's way of
  installing a suspend-type rule of its own, alongside the ones a hooks.edn gate
  installs. The verdict of a human override stands: an approved out-of-bounds call
  executes like :pass."
  ([call] (run! call nil nil))
  ([call thread-id] (run! call thread-id nil))
  ([{:keys [id function] :as _call} thread-id on-phase]
   (let [report (fn [e] (when on-phase (on-phase e)))
         {:keys [name arguments]} function]
     (if-let [tool (get (effective-tools thread-id) name)]
       (try
         (let [parsed  (json/read-str (if (str/blank? arguments) "{}" arguments)
                                      :key-fn keyword)
               missing (missing-args tool parsed)
               reason  (approval-reason tool name thread-id parsed)
               execute (fn []
                         ;; *thread-id* is bound around the tool body so code
                         ;; running inside a tool -- eval above all -- can address
                         ;; its own session (this namespace).
                         (let [body (fn []
                                      (if-let [role (batch-role id)]
                                        ;; A call in a batch: either it runs the
                                        ;; whole group (the appointed one) or it
                                        ;; answers with its note and touches
                                        ;; nothing. BOTH COME FROM THE PLAN -- this
                                        ;; namespace neither knows nor asks what
                                        ;; kind of work that is. See the batch
                                        ;; section above.
                                        (if (= :applier (:role role))
                                          ((:run role))
                                          (:note role))
                                        ((:run tool) parsed)))
                               [result err]
                               (try [(binding [*thread-id* thread-id
                                               *tool-call-id* id]
                                       (body))
                                     nil]
                                    (catch Throwable t [nil t]))
                               ;; A SUSPENSION IS NOT A FAILURE, and it is picked
                               ;; out before the generic handler can read it as
                               ;; one: the tool did not go wrong, it stopped to ask
                               ;; (see parked/suspend!).
                               suspended (when (suspended? err) (ex-data err))
                               _ (report (ev/tool-executed id name (some-> err ex-message)))
                               ;; PostToolUse is an OBSERVER: its verdict is
                               ;; discarded here on purpose. It already ran inside
                               ;; the seam (hook/emit), it cannot un-run the tool,
                               ;; and its failure semantics are the point's own
                               ;; (:on-error :proceed) -- so nothing about this
                               ;; call's outcome depends on it.
                               _ (when-not err
                                   (hook/emit :post-tool-use {:tool_name name
                                                              :tool_input parsed}))
                               _ (report (ev/tool-post-execute id name))]
                           (cond
                             ;; THE CALL COMPLETED ITS OWN STORY: the record it
                             ;; parked is finished off here with the two things
                             ;; only the seam knows -- the name and arguments it
                             ;; was called with -- because the resume has to be
                             ;; able to issue this call again.
                             suspended
                             (do (park-approval! (:suspended/interrupt-id suspended)
                                                        {:thread-id    thread-id
                                                         :tool-call-id id
                                                         :name         name
                                                         :args         arguments
                                                         :question     (:suspended/question suspended)})
                                 {:content "" :error false
                                  :parked (cond-> {:interrupt-id (:suspended/interrupt-id suspended)
                                                   :id id :name name :args arguments
                                                   :question (:suspended/question suspended)}
                                            true (assoc :reason :elicitation))})

                             err
                             {:content (ex-message err) :error true}

                             :else
                             {:content (str result) :error false})))
               park (fn [reason interrupt-id]
                      ;; A THREAD THAT CANNOT WAIT FOR A HUMAN IS TOLD SO INSTEAD OF
                      ;; BEING PARKED. Everything above decided that this call needs a
                      ;; person; whether there is a person to stop for is a fact about
                      ;; the THREAD, and the installed policy is what knows it. When
                      ;; there is not, the call does not run and does not hang: it
                      ;; comes back as information -- the same shape every other
                      ;; refusal has -- so the model can find another way. Nothing is
                      ;; recorded as parked, because nothing is waiting.
                      (if-let [why (unattended-reason thread-id name reason)]
                        (do (report (ev/tool-pre-execute id name :unattended []))
                            (report (ev/tool-post-execute id name))
                            {:content why :error true})
                        (let [interrupt-id (or interrupt-id
                                               (:interrupt-id (parked-for-call thread-id id))
                                               (str (java.util.UUID/randomUUID)))]
                          (park-approval! interrupt-id (cond-> {:thread-id thread-id
                                                                :tool-call-id id
                                                                :name name :args arguments}
                                                         reason (assoc :reason reason)))
                          (report (ev/tool-pre-execute id name :needs-approval []))
                          (report (ev/tool-post-execute id name))
                          {:content "" :error false
                           :parked (cond-> {:interrupt-id interrupt-id :id id
                                            :name name :args arguments}
                                     reason (assoc :reason reason))})))]
           (cond
             ;; Disabled is checked FIRST: it is a hard refusal, and there is no
             ;; point parking a call that is never going to execute.
             (or (session-disabled? thread-id name) (base-disabled-by name))
             (do (report (ev/tool-pre-execute id name :disabled []))
                 (report (ev/tool-post-execute id name))
                 {:content (disabled-message thread-id name) :error true})

             ;; ...then the editing mode's subtraction, which is the only other
             ;; thing that can take a registered tool out of a session's set. It
             ;; is still AHEAD of the argument and approval checks: a call this
             ;; session does not serve will not run whatever its arguments are,
             ;; and parking it would ask a person about a call that could not
             ;; have executed anyway.
             (not (served? thread-id name))
             (do (report (ev/tool-pre-execute id name :unserved []))
                 (report (ev/tool-post-execute id name))
                 {:content (unserved-message thread-id name) :error true})

             (seq missing)
             (do (report (ev/tool-pre-execute id name :missing-args missing))
                 (report (ev/tool-post-execute id name))
                 {:content (str "missing required argument(s): "
                                (str/join ", " (map (fn [k] (clojure.core/name k)) missing)))
                  :error true})

             reason
             (let [existing (parked-for-call thread-id id)
                   decision (when existing (take-decision! (:interrupt-id existing)))]
               (case (:verdict decision)
                 :approved (do (report (ev/tool-pre-execute id name :approved []))
                               (execute))
                 :vetoed   (do (report (ev/tool-pre-execute id name :vetoed []))
                               (report (ev/tool-post-execute id name))
                               {:content (veto-message decision) :error true})
                 ;; Nothing decided yet (or the verdict was already spent, which
                 ;; a replay of the same interrupt would be). ASK THE HOOKS
                 ;; before asking a person: this is PermissionRequest, the point
                 ;; that exists so a rule can answer what would otherwise
                 ;; interrupt somebody. Its answer has the same standing as a
                 ;; human's -- approve runs the call, deny answers it with the
                 ;; hook's reason -- and a point that says nothing (no
                 ;; declaration, no answer on stdout) leaves the call exactly
                 ;; where it was: parked, waiting for a person.
                 (let [interrupt-id (or (:interrupt-id existing)
                                        (str (java.util.UUID/randomUUID)))
                       answer (hook/emit :permission-request
                                         {:tool_name name
                                          :tool_input parsed
                                          :interrupt_id interrupt-id})
                       decision (get (:answer answer) "decision")]
                   (case decision
                     "approve" (do (report (ev/tool-pre-execute id name :approved []))
                                   (execute))
                     "deny"    (do (report (ev/tool-pre-execute id name :vetoed []))
                                   (report (ev/tool-post-execute id name))
                                   {:content (delegated-veto-message answer) :error true})
                     (park reason interrupt-id)))))

             ;; THE GATE, last of the refusals. Everything above is this harness
             ;; deciding; this is the user's own rulebooks deciding, and it comes
             ;; after them on purpose:
             ;;   - a DISABLED tool is refused without spawning anything, which is
             ;;     what "switched off" has to mean to be worth the word;
             ;;   - a call missing an argument would be refused by the command
             ;;     too, and asking a hook to judge a call that cannot run wastes
             ;;     the hook and muddies the reason the model reads;
             ;;   - a call already PARKED is the human's, and a hook cannot
             ;;     overrule them -- PermissionRequest is the point that speaks to
             ;;     a parked call, and it is the next ticket's.
             ;; The verdict here is :allow or :block. The third outcome a hook
             ;; could express -- "stop and ask a human" -- is not a hook's to
             ;; give yet; today a gate either lets a call through or refuses it.
             :else
             (let [gate (hook/emit :pre-tool-use {:tool_name name :tool_input parsed})]
               (if (= :block (:verdict gate))
                 (do (report (ev/tool-pre-execute id name :hook-blocked []))
                     (report (ev/tool-post-execute id name))
                     {:content (hook-block-message (:reason gate)) :error true})
                 (do (report (ev/tool-pre-execute id name :pass []))
                     (execute))))))
         (catch Throwable t
           ;; A malformed argument payload dies before the pass branch even
           ;; starts; the lifecycle still closes on the seam's own terms.
           (report (ev/tool-executed id name (ex-message t)))
           (report (ev/tool-post-execute id name))
           {:content (ex-message t) :error true}))
       (do (report (ev/tool-pre-execute id name :unknown-tool []))
           (report (ev/tool-post-execute id name))
           {:content (str "unknown tool: " name) :error true})))))
