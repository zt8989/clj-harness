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
  read, printed, or returned; introspection answers with the selection and the
  resolved endpoint, never the key; and the two override atoms below stay private.

  WHAT ANNOUNCES A MODEL now lives in harness.models: the catalog's shape, its
  validation, and the assembly of a selection into a provider. This namespace
  keeps the one thing that is genuinely a precedence question -- which TIER wins
  -- and the one thing that must never leave it -- the key. Splitting them is
  what let a provider stop being a model: the catalog says which vendor serves
  which model, the tiers say which of each this run uses.

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
            [harness.home :as home]
            [harness.models :as models]
            [harness.project :as project]))

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
;; [{:thread-id .. :before <knob slice> :after <knob slice>
;;   :trigger "session-configure" :override <session tier afterwards>
;;   :resolved <what the catalog assembled from that tier>}]

(defn record-provider-change!
  "Note that THREAD-ID's provider moved from BEFORE to AFTER, by an APPROVED
  change of TRIGGER (a string identifying the path that pressed the change --
  currently always \"session-configure\"). The body only runs on an approval --
  a vetoed call never reaches it -- so landing here means the human said yes;
  a veto leaves no change line at all, and the reader tells the two apart by
  the presence of this line (paired with its approval/decided row).

  OVERRIDE is the session's OWN tier after the change -- the partial the next
  resolve-provider would consult. :before / :after are slices (only the knobs
  this call moved); :override is the whole tier, so a reader can reconstruct the
  post-change session from this line alone.

  RESOLVED is that tier again, assembled -- the endpoint and modalities the
  session is now served by. Recorded here rather than left to a reader to
  re-derive, because the catalog moves underneath the log: a built-in table
  gains a model, a base-url changes, and re-resolving an old line would answer
  with today's catalog instead of that day's. Drained, not read: the writer
  empties this after every run."
  [thread-id before after trigger override resolved]
  (swap! provider-changes conj {:thread-id thread-id
                                :before before :after after
                                :trigger trigger :override override
                                :resolved resolved}))

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
  "THREAD-ID's session configuration override -- a partial SELECTION ({:provider
  :model :reasoning-effort}) -- or nil. This is the session's own tier of
  resolve-provider: the session's own change, not a test seam."
  [thread-id]
  (get @session-overrides thread-id))

(defn set-override!
  "Replace THREAD-ID's session override with OV, or drop it entirely when OV is
  nil. Separate from use-provider! because a session override is PARTIAL --
  naming only what changes -- while a pin is a whole provider.

  OV is canonicalized into a selection on the way in (see models/selection), so a
  provider named by a JSON string and one named by an EDN keyword are the SAME
  override. Storing the raw spelling would let one provider be selected twice and
  show up in a timeline as a change that changed nothing.

  Only the three knobs are kept, and that is the shape, not a lossy filter: a
  session chooses WHICH provider and model, never an endpoint -- the endpoint is
  what the catalog answers for the provider it chose. An override naming one
  therefore has no effect, which is the correct outcome rather than a silent
  failure: there is nothing for it to mean.

  RETURNS what was stored, canonicalized (nil when cleared). A caller recording
  the change it just made should record THAT rather than its own copy of the
  arguments: the stored value is the one the next run will fold, and a caller
  keeping a parallel version of it is how the log and the session drift apart."
  [thread-id ov]
  (if (nil? ov)
    (do (swap! session-overrides dissoc thread-id) nil)
    (let [sel (models/selection ov)]
      (swap! session-overrides assoc thread-id sel)
      sel)))

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

;; ---------------------------------------------- the selection and its tiers
;;
;; A provider is no longer described field by field across the tiers. It is
;; SELECTED: the three knobs in harness.models/knobs (:provider / :model /
;; :reasoning-effort) are folded down the tiers, and the selected provider's
;; entry in the catalog ASSEMBLES the endpoint and the model's modalities.
;;
;; That is the whole reason the old four-field merge went away. Under a
;; field-by-field merge, "which vendor" was expressed by moving :protocol and
;; :base-url, so a tier could name :provider and have it silently dropped -- the
;; name was not a field, so nothing carried it. A knob that is folded and then
;; resolved cannot be dropped without a loud failure: an unknown provider name
;; and an undeclared model id both stop the run by name.

(defn providers
  "The catalog this process resolves against, validated. Re-read every time, like
  config.edn -- and validated in full on every read, including entries this run
  will not use, so a malformed catalog fails on the run that reads it rather than
  intermittently on the run that names it."
  []
  (models/catalog))

(defn- default-selection
  "config.edn, the default tier. Two shapes:

    {:provider :openrouter :model \"…\" :reasoning-effort \"high\"}
        name a provider; the other two knobs are optional
    {:protocol … :base-url … :model … :input #{…} :output #{…}}
        DESCRIBE a provider instead of naming one -- the escape hatch, for trying
        one endpoint without registering it. Modalities may be omitted here,
        which is a statement that this entry declares nothing (nothing then
        guards its input), not an error.

  A config that does neither fails by name in models/assemble, saying which of
  the two shapes to write."
  [cfg]
  (if (contains? cfg :provider)
    (models/selection cfg)
    (let [desc (select-keys cfg models/inline-fields)]
      (when (seq desc)
        (assoc (models/selection cfg) :provider desc)))))

(defn- fold-and-assemble
  "A folded SELECTION -> the provider it names, assembled from the catalog. The
  one place a selection becomes a provider, so every path that resolves -- a run,
  an offline tool, a session's own pending change -- agrees on what a selection
  means and on which failures it can raise.

  Deliberately WITHOUT the api-key: resolution and key-attachment are two steps,
  and only resolve-provider performs the second. Validation has no business
  reading a secret to answer a question about a model id."
  [folded]
  (models/assemble (providers) folded))

(defn resolve-provider
  "The effective provider for THREAD-ID, plus where it came from.

  Three tiers, each overriding the one before it KNOB BY KNOB:

    1. config.edn's default tier       (or a provider described inline)
    2. this session's override         (the session-configure tool)
    3. this run's request              (REQUEST, from the input map)

  The fold produces a SELECTION, and the catalog assembles it: the selected
  provider supplies :protocol / :base-url and the model's :input / :output,
  and a model id no tier named resolves to the provider's default. Because the
  endpoint follows the provider, a tier that names only :provider really does
  switch vendors -- under the old field-by-field merge it silently did not.

  Returns {:provider {.. :api-key ..} :selection {..} :source :default|:inline|:request},
  where the api-key is attached LAST and only here. :source names where the
  resolution STARTED -- config's default tier, a provider config described
  inline, or this run's request -- not which tier last moved a knob; a session's
  own changes are the provider/changed timeline's business, not this field's. A
  THREAD-ID of nil resolves the default tier with no session in play, which is
  what an offline tool wants.

  REQUEST must not be routed through the prompt context -- it would become a
  trailing user message and poison the provider's prefix cache."
  ([thread-id] (resolve-provider thread-id nil))
  ([thread-id request]
   (let [base   (default-selection (config))
         ses    (or (override-for thread-id) {})
         run    (or request {})
         folded (models/fold-selection base ses run)
         source (cond
                  (seq (models/selection run)) :request
                  (map? (:provider base))      :inline
                  :else                        :default)]
     {:provider  (assoc (fold-and-assemble folded) :api-key (api-key))
      :selection folded
      :source    source})))

(defn resolve-override
  "What THREAD-ID's session would be served by if its own tier WERE OV -- the
  question session-configure asks before it writes anything.

  A change that cannot be served is not a change: a provider name that is not in
  the catalog, or a model id the selected provider does not declare, has to fail
  at the moment it is proposed. Writing it first and failing later would leave
  the override holding a configuration every subsequent run fails on -- and the
  failure would surface on the NEXT run, nowhere near the call that caused it.

  OV is the session's whole tier afterwards (not just the knobs this call moved),
  because that is what the next run will fold. Returns
  {:selection <the tier as folded> :resolved <what the catalog assembled>}, with
  no api-key: this answers a question about a configuration, and a secret is not
  part of that answer."
  [ov]
  (let [folded (models/fold-selection (default-selection (config)) ov)]
    {:selection folded
     :resolved  (fold-and-assemble folded)}))

(defn effective-provider
  "The provider for THREAD-ID, without a run request: the default and session
  tiers plus the ENV-sourced api-key. Offline tools and replay use this -- they
  run outside a run, so there is no request to layer on top."
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
  "What THREAD-ID's session is serving from right now -- the SELECTION and what
  it resolved to:

    {:provider :openrouter :model \"anthropic/claude-sonnet-4.5\"
     :reasoning-effort \"high\" :protocol :openai-completions
     :base-url \"https://openrouter.ai/api/v1\" :input #{:text :image} :output #{:text}
     :context-window 1000000 :max-output-tokens 64000}

  The three knobs are what was CHOSEN; the rest is what the catalog answered.
  Both are worth reporting: :model is the id, and a reader asking 'what is this
  session on' wants the name, not just the endpoint it happens to reach. The two
  counts are the model's, and are absent when the catalog says nothing about
  them -- which is a fact about the entry, not a zero.

  Resolved live through the tiers above -- the session override included -- but
  NEVER the api-key: the fields are named one by one rather than the map being
  passed through, so a future change to the resolver cannot leak a key through
  here.

  A nil THREAD-ID answers for the process-wide slot (no session in play), which
  is what an offline tool wants. Sets, not wire strings: this is the in-process
  answer, and harness.models/wire is what renders it for a log, an HTTP body or
  a tool result."
  [thread-id]
  (let [p (effective-provider thread-id)]
    (select-keys p [:provider :model :reasoning-effort
                    :protocol :base-url :input :output
                    :context-window :max-output-tokens])))

(defn active-project
  "The project directory THREAD-ID's session is bound to, as an absolute path
  string -- or nil, the explicit answer for NO binding (never an error):
  an unbound session is the normal case, and its file tools and shell run
  exactly as they did before bindings existed.

  Asked, not copied: the binding lives in harness.project and is re-read every
  call, matching active-provider and log-path."
  [thread-id]
  (project/binding-for thread-id))
