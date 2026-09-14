(ns harness.memory
  "The introspectable surface: the tool registry (the immutable base plus this
  session's overlay), the calls parked for a human's approval, and two facts
  about now (this thread's log path, the project directory it is bound to).

  This namespace is on its way out. The provider half -- config.edn, the api-key,
  the tier fold, the session overrides, the provider/changed outbox -- moved to
  harness.providers, and the frozen prompt to harness.llm. What remains above is
  the part that is genuinely about a SESSION's own machinery rather than about
  providers; it will follow them out.

  The provider half's docstring used to argue a STRUCTURAL boundary -- a
  non-introspectable namespace that eval was not invited to -- and that argument
  was retired when the two were merged: Clojure cannot actually wall a namespace
  off from eval, since var-quote and resolve reach any var, so the split guarded
  nothing. The boundary is a WRITTEN DISCIPLINE in prompt.md instead: the api-key
  is resolved in harness.providers and must never be read, printed, or returned,
  and self-inspection answers with the selection and the resolved endpoint, never
  the key.

  Where files ARE is harness.home's business: the config root is ~/.clj-harness
  (relocatable via CLJ_HARNESS_HOME), and prompt.md is the one file that stays in
  the repository.

  What is deliberately NOT here: a copy of a thread's history. The jsonl log
  already holds the conversation, and a second copy in memory can only drift
  from it -- so the log is the record, read it there."
  (:require [harness.home :as home]
            [harness.project :as project]))

;; -------------------------------------------------------------------- tools

(defonce registry (atom {}))

(defn register! [name tool] (swap! registry assoc name tool))

;; ------------------------------------------------------------ session tools

(def ^:dynamic *thread-id*
  "Bound by the execution seam (harness.tools/run!) to the thread whose run the
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
;;              execution seam (harness.tools/run!) is what refuses the call.
;; There is deliberately no :removed: nothing may vanish from a toolset, because
;; a model that cannot see a tool reads its absence as "this capability does not
;; exist" and goes looking for a way around it. Disabled is honest; hidden is not.

(declare effective-tools)

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
  (when (contains? (effective-tools thread-id) name)
    (swap! overlays update-in [thread-id :disabled] (fnil conj #{}) name)))

(defn session-enable!
  "Undo session-disable! for NAME. A name that was never disabled is a no-op."
  [thread-id name]
  (swap! overlays update-in [thread-id :disabled] (fnil disj #{}) name))

(defn session-disabled?
  "Is NAME switched off in THREAD-ID's session? The seam's lookup, per call."
  [thread-id name]
  (contains? (get-in @overlays [thread-id :disabled] #{}) name))

(defn effective-tools
  "NAME->TOOL for THREAD-ID: the immutable base overlaid with the session's
  additions. A thread with no overlay sees the pure base; nil THREAD-ID (no
  session context) also means the base.

  DELIBERATELY NOT the set of tools that will run: a disabled tool is still in
  here. Availability is a separate question, answered per call by
  session-disabled? at the execution seam."
  [thread-id]
  (if (nil? thread-id)
    @registry
    (into @registry (get-in @overlays [thread-id :added] {}))))

;; --------------------------------------------------------------- approvals

(defonce ^:private session-approvals
  (atom {}))
;; thread-id -> #{name}

(defn session-require-approval!
  "Make every call of NAME park for a human decision in THREAD-ID's session only.
  Session-scoped exactly like the tool overlay: another thread is unaffected, and
  the process-wide base registry is never touched. The union of this set and the
  tool's own :requires-approval flag is what parks a call."
  [thread-id name]
  (swap! session-approvals update thread-id (fnil conj #{}) name))

(defn session-approval-required?
  "Does THREAD-ID's session require a human decision for NAME?"
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
  "The parked record for INTERRUPT-ID, or nil. Part of the introspectable
  surface: an agent may look up a call of its own that is waiting on a human."
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

;; -------------------------------------------------------------- introspection
;;
;; FACTS ABOUT NOW, computed on demand -- not copies of anything. Where this
;; thread's log is, and which project directory it is bound to: neither is in the
;; jsonl (the path is a fact about this process; the binding moves), so asking
;; beats copying. Both re-derive every call, matching config.

(defn log-path
  "This thread's JSONL log file, as a string path -- the file harness.http
  appends to, named by the same harness.home/log-file the writer uses. Computed
  fresh every call: the root can change (CLJ_HARNESS_HOME, a test binding)
  between calls, and a cached path would silently point at the wrong file.

  A THREAD-ID of nil answers for the process-wide slot, matching the rest of the
  session-scoped surface."
  [thread-id]
  (str (home/log-file thread-id)))

(defn active-project
  "The project directory THREAD-ID's session is bound to, as an absolute path
  string -- or nil, the explicit answer for NO binding (never an error):
  an unbound session is the normal case, and its file tools and shell run
  exactly as they did before bindings existed.

  Asked, not copied: the binding lives in harness.project and is re-read every
  call, matching active-provider and log-path."
  [thread-id]
  (project/binding-for thread-id))
