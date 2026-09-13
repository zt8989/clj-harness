(ns harness.memory
  "The introspectable surface AND the provider assembler, in one namespace: eval
  and the agent through it may freely read the frozen system prompt, the tool
  registry (the immutable base plus this session's overlay), the config.edn
  contents, the calls parked for a human's approval -- and ask what provider a
  session is serving from.

  The former harness.opaque (the api-key, provider resolution, session
  overrides) was merged in here: Clojure cannot actually wall a namespace off
  from eval -- var-quote and resolve reach any var -- so the structural split
  guarded nothing. The boundary it used to mark is now a WRITTEN DISCIPLINE in
  prompt.md instead: the api-key is resolved in this namespace and must never be
  read, printed, or returned; introspection answers with the four descriptive
  fields, never the key; and the two override atoms below stay private.

  Where those files ARE is harness.home's business: the config root is
  ~/.clj-harness (relocatable via CLJ_HARNESS_HOME), and prompt.md is the one
  file that stays in the repository.

  What is deliberately NOT here: a copy of a thread's history. The jsonl log
  already holds the conversation, and a second copy in memory can only drift
  from it -- so the log is the record, read it there. The provider question is
  answered by ASKING (effective-provider below), never by keeping a copy: the
  resolution re-derives every call."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
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

;; ------------------------------------------------------- provider outbox
;;
;; A PENDING-OUTBOX, not a copy of state: the tool body records that the session
;; changed and what it changed from and to, and the http edge drains it to the
;; jsonl. It exists for exactly the reason parked-registry does -- to carry a
;; fact across the seam from the code that knows it (the tool) to the code that
;; writes it down (the edge -- the only writer). Once drained it is gone, and
;; nothing reads it back.

(defonce ^:private provider-changes
  (atom []))
;; [{:thread-id .. :before <slice> :after <slice>
;;   :trigger "session-configure" :override <full session override after the change>}]

(defn record-provider-change!
  "Note that THREAD-ID's provider moved from BEFORE to AFTER, by an APPROVED
  change of TRIGGER (a string identifying the path that pressed the change --
  currently always \"session-configure\"). The body only runs on an approval --
  a vetoed call never reaches it -- so landing here means the human said yes;
  a veto leaves no change line at all, and the reader tells the two apart by
  the presence of this line (paired with its approval/decided row).

  OVERRIDE is the session's OWN tier after the change -- the partial the next
  resolve-provider would consult as tier 3. :before / :after are slices (only
  the fields this call moved); :override is the whole tier, so a reader can
  reconstruct the post-change session from this line alone. Drained, not
  read: the writer empties this after every run."
  [thread-id before after trigger override]
  (swap! provider-changes conj {:thread-id thread-id
                                :before before :after after
                                :trigger trigger :override override}))

(defn take-provider-changes!
  "Every pending provider change recorded for THREAD-ID, in order, clearing them.
  Called by the http edge after a run -- the one writer."
  [thread-id]
  (let [[taken _] (swap-vals! provider-changes
                              (fn [vs] (into [] (remove #(= thread-id (:thread-id %))) vs)))]
    (filterv #(= thread-id (:thread-id %)) taken)))

(defonce ^:private init-logged
  (atom #{}))
;; thread-ids whose provider/init line has been written. Writer-side state,
;; deliberately NOT re-derived from the log: the edge must not read its own
;; log, so "have I written the init line yet" has to be remembered here. This
;; is a fact about the FILE, not a copy of the conversation or the provider.

(defn init-logged?
  "Has THREAD-ID's provider/init line already been written?"
  [thread-id]
  (contains? @init-logged thread-id))

(defn mark-init-logged!
  "Remember that THREAD-ID's provider/init line is now on disk, so later runs
  do not write a second one."
  [thread-id]
  (swap! init-logged conj thread-id))

;; ------------------------------------------------------------------- config

(defn config
  "config.edn, re-read every time so it can be edited while the process runs.
  This is the EDN half only (:protocol/:base-url/:model...) -- there is no
  api-key here and never will be: the key is resolved from .env/environment in
  the provider-resolution section below, which is where the effective provider
  is assembled.

  The path comes from harness.home; a missing file is a named failure there."
  []
  (edn/read-string (home/config)))

;; -------------------------------------------------------- provider resolution
;;
;; Merged in from the former harness.opaque, unchanged in behaviour. This is
;; where the effective provider is ASSEMBLED, because that is the one place the
;; api-key is legitimate -- and the one place it must never leak from. The
;; resolution has four levels, each overriding the one before it, field by
;; field (see resolve-provider). The boundary this section marks is prompt.md's
;; written discipline, not the language: eval can reach every var here, and the
;; rule it must follow is spelled out in prompt.md, not in visibility.

;; TWO slots, on purpose -- they answer different questions and must not collide:
;;
;;   scripted-pins     a WHOLE provider, installed by tests (and replay) to take
;;                     the place of config resolution entirely. It is the seam
;;                     that drives the edge offline.
;;   session-overrides a PARTIAL {field value}, the session's own configuration
;;                     change (tier 3 of resolve-provider).
;;
;; Both are keyed by thread-id: one session reconfiguring itself must not
;; silently reconfigure every other session in the process. A nil thread-id
;; addresses the process-wide slot -- replay and offline tools run outside a
;; session and use that one.
(defonce ^:private scripted-pins (atom {}))
(defonce ^:private session-overrides (atom {}))

(defn use-provider!
  "Pin THREAD-ID's session to a whole PROVIDER, bypassing the config files (and
  the session override). Pass nil to drop the pin. This is how the edge is
  exercised offline, against a scripted provider, without an api-key or a
  network."
  ([provider] (use-provider! nil provider))
  ([thread-id provider]
   (if (nil? provider)
     (swap! scripted-pins dissoc thread-id)
     (swap! scripted-pins assoc thread-id provider))))

(defn pinned-provider
  "THREAD-ID's scripted pin, or nil. A pin may carry a key, though a scripted
  one does not -- which is one more reason introspection never returns a whole
  provider map."
  [thread-id]
  (get @scripted-pins thread-id))

(defn override-for
  "THREAD-ID's session configuration override -- a PARTIAL {field value} -- or
  nil. This is tier 3 of resolve-provider: the session's own change, not a test
  seam."
  [thread-id]
  (get @session-overrides thread-id))

(defn set-override!
  "Replace THREAD-ID's session override with OV (a partial {field value} map), or
  drop it entirely when OV is nil. Separate from use-provider! because a session
  override is PARTIAL -- naming only what changes -- while a pin is a whole
  provider."
  [thread-id ov]
  (if (nil? ov)
    (swap! session-overrides dissoc thread-id)
    (swap! session-overrides assoc thread-id ov)))

(defn- parse-dotenv
  "A .env file's contents -> a {name value} map. Handles the shapes the format
  actually uses: `export` prefixes, surrounding single or double quotes,
  `#` comments, blank lines, and values that themselves contain `=` (only the
  first `=` splits).

  We parse it ourselves rather than lean on the dotenv library because that
  library resolves `.env` from the CURRENT DIRECTORY at namespace-load time and
  caches it in a def -- so it cannot be pointed at harness.home, and it would
  miss an edit made while the process runs. Both of those matter here."
  [raw]
  (into {}
        (->> (str/split-lines raw)
             (map str/trim)
             (remove #(or (empty? %) (str/starts-with? % "#")))
             (map #(str/split % #"=" 2))
             (filter #(= 2 (count %)))
             (map (fn [[k v]]
                    [(str/replace (str/trim k) #"^export\s+" "")
                     (let [v (str/trim v)]
                       (if (and (>= (count v) 2)
                                (or (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                                    (and (str/starts-with? v "'") (str/ends-with? v "'"))))
                         (subs v 1 (dec (count v)))
                         v))])))))

(defn- api-key
  "The API key, following the dotenv library's documented precedence: a value in
  .env wins over a real environment variable. So .env is the single place that
  decides, and setting a shell variable will NOT override it.

  The file is harness.home's .env -- and is re-read every time, like config.edn,
  so editing it takes effect without a restart. PRIVATE, and doubly so by
  discipline: the key flows ONLY into resolve-provider's result, and prompt.md
  forbids reaching for it any other way."
  []
  (let [f (home/dotenv-file)
        from-file (when (.exists f) (get (parse-dotenv (slurp f :encoding "UTF-8"))
                                         "HARNESS_API_KEY"))]
    (or from-file (System/getenv "HARNESS_API_KEY"))))

(def ^:private fields
  "The four fields a provider is described by. Nothing else is inherited, so a
  partial provider can never silently pick up a stray key from another level."
  [:protocol :base-url :model :reasoning-effort])

(defn providers
  "providers.edn -> {name provider-map}. Re-read every time, like config.edn.

  A MISSING file is an EMPTY registry, not an error: a config.edn that describes
  its provider inline (the escape hatch) needs no registry at all. The named
  failure belongs where a name is actually used -- see named."
  []
  (let [f (home/providers-file)]
    (if (.exists f)
      (edn/read-string (slurp f :encoding "UTF-8"))
      {})))

(defn- named
  "Look NAME up in the registry. A name that is not there is a hard, NAMED
  failure: falling back to nil would serve the run from nowhere and report the
  problem as a confusing downstream error instead of 'no such provider'."
  [registry name]
  (or (get registry name)
      (throw (ex-info (str "no provider named " (pr-str name) " in providers.edn"
                           "; it defines " (pr-str (vec (sort (keys registry)))))
                      {:name name :known (vec (sort (keys registry)))}))))

(defn- default-base
  "config.edn, the default tier. Three shapes are accepted, in this order:

    {:provider :cheap}       a registry name; looked up in providers.edn
    {:provider {..provider}} an inline map -- the escape hatch (try one endpoint
                             once without registering it)
    {:protocol .. :model ..} a FLAT provider, every field at the top level

  The flat form is last and needs no registry at all: a config that describes
  its provider directly is complete on its own, and is what a lone-provider
  deployment naturally writes. Only when :provider is present and names a
  missing entry does resolution fail -- and it fails NAMING the entry."
  [registry cfg]
  (let [base (cond
               (map? (:provider cfg))  (:provider cfg)
               (some? (:provider cfg)) (named registry (:provider cfg))
               :else                   (select-keys cfg fields))]
    ;; Whatever the shape, the default tier may ALSO override individual fields
    ;; at the top level. That is why config.edn stays a tier instead of
    ;; collapsing into the registry: you can tune one knob (usually
    ;; :reasoning-effort) without minting a new registry entry.
    (merge base (select-keys cfg fields))))

(defn- overlay
  "Apply a partial override: only the fields actually present win. A field left
  out falls back to the tier below, which is what lets the three knobs move
  independently."
  [base over]
  (merge base (select-keys over fields)))

(defn resolve-provider
  "The effective provider for THREAD-ID, plus where it came from.

  Four tiers, each overriding the one before it FIELD BY FIELD (a tier that
  names no value for a field leaves the one below it standing):

    1. the named entry in providers.edn          (or an inline map in config.edn)
    2. config.edn's default-tier field overrides  (:model / :reasoning-effort)
    3. this session's override                    (the session-configure tool)
    4. this run's request                         (REQUEST, from the input map)

  Returns {:provider {.. :api-key ..} :source :default|:request|:inline}, where
  the api-key is attached LAST and only here. The source is :inline when
  config.edn describes its provider directly (an inline map or a flat set of
  fields) rather than naming a registry entry -- the two are worth telling
  apart in the audit trail. A THREAD-ID of nil resolves tiers 1-2 with no
  session in play, which is what an offline tool wants.

  REQUEST must not be routed through the prompt context -- it would become a
  trailing user message and poison the provider's prefix cache."
  ([thread-id] (resolve-provider thread-id nil))
  ([thread-id request]
   (let [registry (providers)
         cfg      (config)
         inline?  (not (keyword? (:provider cfg)))
         base     (default-base registry cfg)
         with-ses (overlay base (or (override-for thread-id) {}))
         with-run (overlay with-ses (or (select-keys request fields) {}))
         source   (cond
                    (seq (select-keys request fields)) :request
                    inline?                            :inline
                    :else                              :default)]
     {:provider (assoc with-run :api-key (api-key))
      :source   source})))

(defn effective-provider
  "The provider for THREAD-ID, without a run request: tiers 1-3 plus the
  ENV-sourced api-key. Offline tools and replay use this -- they run outside a
  run, so there is no request to layer on top."
  ([] (effective-provider nil))
  ([thread-id] (:provider (resolve-provider thread-id))))

(defn current-provider
  "What the http edge serves from for THREAD-ID on this run: an explicit scripted
  pin wins outright -- it is the test seam, and the tests that use it are not
  exercising provider resolution. Otherwise the four-tier resolution runs, with
  REQUEST layered on top."
  ([thread-id] (current-provider thread-id nil))
  ([thread-id request]
   (or (pinned-provider thread-id)
       (:provider (resolve-provider thread-id request)))))

;; -------------------------------------------------------------- introspection
;;
;; Two FACTS ABOUT NOW, computed on demand -- not copies of anything. The
;; question "where is this conversation's log" and "what provider is this session
;; serving from" have answers the jsonl cannot give (the path is a fact about
;; this process; the provider is resolved live), so asking beats copying. Both
;; re-derive every call, matching mem/config.

(defn log-path
  "This thread's JSONL log file, as a string path -- the file harness.http
  appends to, named by the same harness.home/log-file the writer uses. Computed
  fresh every call: the root can change (CLJ_HARNESS_HOME, a test binding)
  between calls, and a cached path would silently point at the wrong file.

  A THREAD-ID of nil answers for the process-wide slot, matching the rest of the
  session-scoped surface."
  [thread-id]
  (str (home/log-file thread-id)))

(defn active-provider
  "What THREAD-ID's session is serving from right now:
  {:protocol .. :base-url .. :model .. :reasoning-effort ..}.

  Resolved live through the four-tier resolution above -- the session
  override included -- but NEVER the api-key: that is copied out, field by
  field, so a future change to the resolver cannot leak one through here.

  A nil THREAD-ID answers for the process-wide slot (no session in play), which
  is what an offline tool wants. The three knobs are independently movable, so
  any of them may be absent if no tier ever named it."
  [thread-id]
  (let [p (effective-provider thread-id)]
    (select-keys p [:protocol :base-url :model :reasoning-effort])))
