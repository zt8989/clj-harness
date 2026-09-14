(ns harness.memory
  "What is left of the introspectable surface: the calls parked for a human's
  approval, and two facts about now (this thread's log path, the project
  directory it is bound to).

  This namespace is on its way out. Everything that was here when the surface was
  argued into being has a better owner:

    the frozen prompt          -> harness.llm, with the prefix-cache reason it exists
    the tool table + overlay   -> harness.tools, beside the seam that reads it
    config.edn, the api-key,   -> harness.providers
    the tier fold, the outbox

  and the two remaining pieces follow in the next ticket: the log path to
  harness.home, whose path derivation it already is, and the project binding to
  harness.project, which is where the binding lives and where it is re-read from.

  WHY THE SURFACE IS GOING, in one line: it existed so that eval could READ the
  harness from inside a run. That reading is gone -- it answered questions the
  files on disk answer better -- and what replaced it, a session growing itself
  hooks at runtime, has no use for a namespace whose point was introspection.

  What is deliberately still NOT here: a copy of a thread's history. The jsonl log
  already holds the conversation, and a second copy in memory can only drift
  from it -- so the log is the record, read it there."
  (:require [harness.home :as home]
            [harness.project :as project]))

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
