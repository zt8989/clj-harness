(ns harness.memory
  "The introspectable surface: the in-memory state and accessors eval (and the
  agent through it) may freely read -- the frozen system prompt, the tool
  registry (the immutable base plus this session's overlay), the config.edn
  contents, and the calls parked for a human's approval. Nothing here ever
  carries a secret: the ENV-sourced api-key and any raw provider that could
  hold one live in harness.opaque, the non-introspectable counterpart of this
  namespace.

  Where those files ARE is harness.home's business: the config root is
  ~/.clj-harness (relocatable via CLJ_HARNESS_HOME), and prompt.md is the one
  file that stays in the repository."
  (:require [clojure.edn :as edn]
            [harness.home :as home]))

;; ------------------------------------------------------------------- prompt

(defonce frozen-prompt (atom nil))

(defn prompt
  "The system prompt, FROZEN: prompt.md is read once -- on the first call -- and
  every run after that reuses the same text. The provider's prefill (prompt
  cache) keys on a stable prefix; a system prompt that changes per run would
  miss it on every call. Editing prompt.md takes effect only after
  (reset-prompt!) or a process restart."
  []
  (or @frozen-prompt
      (reset! frozen-prompt (slurp "prompt.md" :encoding "UTF-8"))))

(defn reset-prompt!
  "Re-read prompt.md into the frozen slot. The deliberate counterpart of
  freezing: the agent -- or you, in the REPL -- opts into a new prefix, trading
  one cold prefill for the change."
  []
  (reset! frozen-prompt nil))

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
;; thread-id -> {:added {name tool} :removed #{name}}

(defn session-register!
  "Add NAME->TOOL for THREAD-ID's session only. Registering over a base tool's
  name SHADOWS it for this session -- the base definition is untouched -- and
  the change is visible to the next run of this thread, never to another."
  [thread-id name tool]
  (swap! overlays assoc-in [thread-id :added name] tool))

(defn session-unregister!
  "Remove NAME from THREAD-ID's session view: an added tool is retracted, a
  base tool is hidden for this session only. A name that is neither is a
  no-op. The base registry is never mutated."
  [thread-id name]
  (swap! overlays
         (fn [ov]
           (let [added (get-in ov [thread-id :added])]
             (cond
               (contains? added name)   (update-in ov [thread-id :added] dissoc name)
               (contains? @registry name) (update-in ov [thread-id :removed]
                                                     (fnil conj #{}) name)
               :else ov)))))

(defn effective-tools
  "NAME->TOOL for THREAD-ID: the immutable base overlaid with the session's
  additions and removals. A thread with no overlay sees the pure base; nil
  THREAD-ID (no session context) also means the base."
  [thread-id]
  (if (nil? thread-id)
    @registry
    (let [{:keys [added removed]} (get @overlays thread-id {:added {} :removed #{}})]
      (into (apply dissoc @registry removed) added))))

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

;; ----------------------------------------------------------------- sessions

(defonce ^:private sessions
  (atom {}))
;; thread-id -> {:provider {...} :history [...]}

(defn record-provider!
  "Snapshot, for THREAD-ID, the provider its run actually serves with. The
  api-key is stripped HERE and never enters the memory surface; the config
  fields (:protocol/:base-url/:model ...) stay."
  [thread-id provider]
  (swap! sessions assoc-in [thread-id :provider] (dissoc provider :api-key)))

(defn record-history!
  "Record THREAD-ID's final history at :run/done -- the same landing point and
  the same known timing window as the jsonl message tail (harness.http)."
  [thread-id history]
  (swap! sessions assoc-in [thread-id :history] (vec history)))

(defn session
  "The recorded state of THREAD-ID's last run: {:provider .. :history ..}, or
  nil for a thread this process has never served. This is the introspection
  read side -- what eval hands the agent when it asks about its own session."
  [thread-id]
  (get @sessions thread-id))

;; ------------------------------------------------------------------- config

(defn config
  "config.edn, re-read every time so it can be edited while the process runs.
  This is the EDN half only (:protocol/:base-url/:model...) -- there is no
  api-key here and never will be: the key is resolved from .env/environment in
  harness.opaque, which is where the effective provider is assembled.

  The path comes from harness.home; a missing file is a named failure there."
  []
  (edn/read-string (home/config)))
