(ns harness.tools
  "The tool table: the immutable base of five built-ins, the per-session overlay
  over it, the parked calls a human still has to answer, and the execution seam.

  A tool is
     {:description string :parameters JSON-Schema :required [kw..] :run (fn [args] string)}

  RUN gets a keyword-keyed argument map and returns a string.

  run! takes a tool call in the PROVIDER's shape -- {:id .. :type \"function\"
  :function {:name .. :arguments json-string}} -- because the kernel keeps messages
  in the provider shape and never converts. It never throws and never returns nil:
  a tool failure is information for the model, not a failure of the run.

  THE TABLE LIVES HERE, with the seam that reads it, because they are two halves
  of one thing: the seam decides what a call means and the table says what exists
  to be called. It is also where a session's own additions and switches land, all
  per-thread and gone on restart."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.event :as ev]
            [harness.hooks.dispatch :as hook]
            [harness.providers :as providers]
            [harness.project :as project]
            [harness.shell :as shell])
  (:import [java.util.regex Pattern]))

;; A resident namespace, so `def`s in eval persist across calls. This is what lets
;; the agent build itself a toolset -- and hot-swap the kernel with (require .. :reload).
(create-ns 'harness.user)
(binding [*ns* (the-ns 'harness.user)] (clojure.core/refer-clojure))

;; ------------------------------------------------------------------- the base

(defonce registry (atom {}))

(defn- register!
  "Put NAME->TOOL into the process-wide base. PRIVATE, and it should stay that
  way: this is how the six built-ins below are declared, and the base is
  immutable at runtime -- nothing outside this namespace registers anything. A
  session's own definitions go through session-register!, which lands in the
  overlay instead and never touches this atom."
  [name tool]
  (swap! registry assoc name tool))

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

;; ------------------------------------------------------------------- helpers

(defn- tool [description props required run]
  {:description description
   :parameters  {:type "object" :properties props :required (mapv name required)}
   :required    required
   :run         run})

(defn- clip [s]
  (if (> (count s) 8000) (str (subs s 0 8000) "\n...[truncated]") s))

(defn- write-file! [path content]
  (let [f (io/file path)]
    (when-let [p (.getParentFile f)] (.mkdirs p))
    (spit f content :encoding "UTF-8")))

;; --------------------------------------------------------------------- tools

;; The file tools resolve their path through harness.project first: a session
;; bound to a project directory gets its RELATIVE paths re-rooted there, an
;; unbound session passes paths through unchanged (the pre-binding behavior,
;; byte for byte). The tool's answer reports the RESOLVED path -- what actually
;; happened, wherever the model's relative path ended up landing.

(defn- t-read [{:keys [path]}]
  (slurp (project/resolve-path *thread-id* path) :encoding "UTF-8"))

(defn- t-write [{:keys [path content]}]
  (let [p (project/resolve-path *thread-id* path)]
    (write-file! p content)
    (str "wrote " (count content) " chars to " p)))

(defn- t-edit [{:keys [path old_string new_string]}]
  (let [p (project/resolve-path *thread-id* path)
        s (slurp p :encoding "UTF-8")
        n (count (re-seq (re-pattern (Pattern/quote old_string)) s))]
    (when (zero? n) (throw (ex-info (str "old_string not found in " p) {})))
    (when (> n 1)
      (throw (ex-info (str "old_string occurs " n " times in " p ", make it unique") {})))
    (write-file! p (str/replace-first s old_string new_string))
    (str "edited " p)))

(defn- t-bash [{:keys [command]}]
  (let [dir (project/binding-for *thread-id*)
        {:keys [exit out err]} (shell/shell command :dir (when dir dir))
        body (str out err)]
    (str (if (str/blank? body) "(no output)" body)
         (when-not (zero? exit) (str "\n[exit " exit "]")))))

(defn- t-eval [{:keys [code]}]
  (let [sw (java.io.StringWriter.)
        v  (binding [*ns* (the-ns 'harness.user) *out* sw]
             (last (map eval (read-string (str "[" code "]")))))
        printed (.toString sw)]
    (clip (str (when (seq printed) (str printed "\n"))
               (pr-str v)))))

(defn- t-configure
  "Change THIS session's provider / model / reasoning-effort. Each of the three
  is independent: pass only what you mean to change, and the rest keep the value
  the tier below gave them.

  A model id means 'an id this provider serves'. Naming a new :provider with no
  :model moves to that vendor's default model -- the old id belonged to the old
  vendor and is not carried across. A :model the provider does not declare fails
  HERE, by name, and nothing is written: that check is done by resolving the
  proposed tier before committing it, because a change that cannot be served is
  not a change. Writing first and failing later would leave the session's
  override holding a configuration every later run fails on, and the failure
  would surface on the NEXT run, nowhere near the call that caused it.

  Marks :requires-approval, so the call parks and a human decides before any of
  it takes effect -- the body only runs on an approved resume, and a veto means
  it never runs at all. The gate is a WORKFLOW convention, not a security
  boundary: eval can still reach harness.providers/use-provider! directly, and bash
  can still read .env. It is here to stop a slip, and it is labelled as such."
  [args]
  ;; The three knobs are the only thing this tool may move, and the check below
  ;; cannot be left to the catalog's own tier guard: the change map is rebuilt
  ;; from these three names, so a stray :context-window would be dropped by that
  ;; rebuild and the resolution would never see it -- the tool would answer
  ;; 'reconfigured' having changed nothing, which is a lie told to whoever called.
  ;; A call naming a model's counts is refused by name, and told where they live.
  (let [known  #{:provider :model :reasoning-effort}
        extras (sort (map name (remove known (keys args))))]
    (when (seq extras)
      (throw (ex-info (str "session-configure does not understand "
                           (pr-str (vec extras))
                           "; it takes provider, model and reasoning-effort --"
                           " a model's endpoint, modalities and token counts are"
                           " declared in providers.edn, not chosen per session")
                      {:unknown (vec extras)}))))
  (let [{:keys [provider model reasoning-effort]} args
        thread-id *thread-id*
        change    (cond-> {}
                    (some? provider)         (assoc :provider provider)
                    (some? model)            (assoc :model model)
                    (some? reasoning-effort) (assoc :reasoning-effort reasoning-effort))]
    (when (empty? change)
      (throw (ex-info "nothing to change: give at least one of provider, model, reasoning-effort" {})))
    (let [before (providers/override-for thread-id)
          ;; Resolve BEFORE writing: this proves the change can actually be
          ;; served and hands the writer the resolved shape, so the log records
          ;; what the session became rather than what it was asked to become.
          resolved (providers/resolve-override (merge before change))
          ;; set-override! answers with what it stored, and THAT is what the
          ;; change line records -- not `change` merged over `before` a second
          ;; time here. The stored value is the one the next run folds; a
          ;; parallel copy is how a log and a session drift apart.
          after (providers/set-override! thread-id (merge before change))]
      (providers/record-provider-change! thread-id before after "session-configure"
                                   after (:resolved resolved))
      (str "session reconfigured: " (pr-str change)
           " -- effective now for this thread only."
           (when-let [m (:model (:resolved resolved))] (str " Serving " m "."))
           (when (nil? thread-id)
             " (warning: no session in scope; the change landed on the process-wide slot)")))))

;; --------------------------------------------------------------------- specs

(defn specs
  "The tools array as an OpenAI-compatible provider expects it, for THREAD-ID's
  effective toolset (base overlaid with its session additions/removals)."
  ([] (specs nil))
  ([thread-id]
   (mapv (fn [[n t]] {:type "function"
                      :function {:name n
                                 :description (:description t)
                                 :parameters (:parameters t)}})
         (sort-by key (effective-tools thread-id)))))

;; -------------------------------------------------------------- the built-ins

;; read/write/edit carry :fence-paths -- when the session is bound to a
;; project directory, a path resolving outside the project directory AND the
;; configuration home parks for approval before it runs. Unbound sessions are
;; untouched: the fence is a property of the tool MARKER, engaged only by a
;; binding, and the park itself is the ordinary approval flow.

(register! "read"
  (assoc (tool "Read a file. A relative path resolves against this session's project directory when one is bound. When bound, a path resolving outside the project directory and the configuration home parks for human approval first."
               {"path" {:type "string" :description "File path."}}
               [:path] t-read)
         :fence-paths true))

(register! "write"
  (assoc (tool "Write a file, overwriting it. A relative path resolves against this session's project directory when one is bound. When bound, a path resolving outside the project directory and the configuration home parks for human approval first."
               {"path"    {:type "string" :description "File path."}
                "content" {:type "string" :description "Full new contents."}}
               [:path :content] t-write)
         :fence-paths true))

(register! "edit"
  (assoc (tool "Replace an exact string in a file. Fails if old_string is absent or not unique. A relative path resolves against this session's project directory when one is bound. When bound, a path resolving outside the project directory and the configuration home parks for human approval first."
               {"path"       {:type "string" :description "File path."}
                "old_string" {:type "string" :description "Exact text to replace."}
                "new_string" {:type "string" :description "Replacement text."}}
               [:path :old_string :new_string] t-edit)
         :fence-paths true))

(register! "bash"
  (tool "Run a shell command (Git Bash on Windows, the host's shell elsewhere). The working directory is this session's project directory when one is bound, otherwise the process working directory."
        {"command" {:type "string" :description "Command line."}}
        [:command] t-bash))

(register! "eval"
  (tool "Evaluate Clojure in this process. Defs persist across calls."
        {"code" {:type "string" :description "Clojure source."}}
        [:code] t-eval))

;; Configure this session's provider. Marked :requires-approval so a model
;; cannot repoint its own session at another endpoint without a human saying so
;; -- the marked call parks, and only an approved resume runs the body. Every
;; knob is optional and independent; give only what you mean to change.
;;
;; "provider" names a VENDOR and "model" an id THAT VENDOR serves. A name the
;; catalog does not know, or an id the named provider does not declare, is
;; refused inside the body -- before anything is written, so a proposed change
;; that cannot be served never becomes the session's configuration.
(register! "session-configure"
  (assoc (tool (str "Change this session's provider, model, or reasoning effort. "
                    "Parks for human approval; only an approved change takes effect. "
                    "Each argument is independent -- pass only what you mean to change. "
                    "Naming a provider alone switches to that vendor AND its default model.")
               {"provider"         {:type "string"
                                    :description "A provider (vendor) name, e.g. \"openrouter\" or \"ollama\"."}
                "model"            {:type "string"
                                    :description "A model id the current provider serves, e.g. \"anthropic/claude-sonnet-4.5\"."}
                "reasoning-effort" {:type "string" :description "Reasoning effort (e.g. \"low\", \"high\")."}}
               [] t-configure)
         :requires-approval true))

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

(defn- approval-reason
  "WHY this call parks -- nil meaning it does not. The same union
  approval-required? answered, now with the reason attached, in the order the
  or short-circuited: the tool's own declaration first, then the session's
  ask, then the project fence (a fence-marked tool whose path, resolved for
  this session, lands outside the project directory and the configuration
  home). The reason rides the parked record, so the human deciding -- and any
  reader of the audit trail -- can tell a declared-approval call from a fence
  catch without re-deriving either."
  [tool name thread-id parsed]
  (cond
    (:requires-approval tool)                       :tool-declares
    (session-approval-required? thread-id name) :session-asks
    (and (:fence-paths tool)
         (project/out-of-bounds? thread-id (:path parsed)))
    :out-of-bounds
    :else nil))

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

(defn- veto-message
  "What the model is told when a human vetoed the call. It is information, not a
  failure of the run: the loop carries on with this as the tool's answer."
  [{:keys [payload]}]
  (str "vetoed by human: the call was not executed."
       (when (some? payload) (str " reason: " (json/write-str payload)))))

(defn- disabled-message
  "What the model is told when it calls a tool this session switched off. Like a
  veto, this is information rather than a run failure -- and it says DISABLED,
  never unknown: the tool exists and is on offer, so calling its absence a lie
  would only send the model hunting for a workaround."
  [name]
  (str "disabled in this session: " name
       " is switched off. Re-enable it with (harness.tools/session-enable!"
       " harness.tools/*thread-id* \"" name "\")."))

(defn run!
  "The ONE tool execution seam. The call's lifecycle is reported to ON-PHASE
  (a fn of kernel events, may be nil) as it passes through:
    :tool/pre-execute   -- entered the seam; outcome :pass, :unknown-tool,
                           :disabled, :missing-args (with the missing names),
                           :needs-approval, :approved, or :vetoed
    :tool/execute       -- left execution; the error message, or nil
    :tool/post-execute  -- closes the lifecycle, whatever the phases decided
  A call that never passes pre-execute (unknown tool, disabled tool, missing
  arguments) skips the :tool/execute phase, but its :tool/post-execute still
  arrives -- the lifecycle is always closed. :disabled is checked before
  approval: a tool this session switched off is refused outright, never parked.

  A call marked for approval takes one of three transits:
    - no decision yet      -> :needs-approval: parked, the tool body does NOT
                              run, no :tool/result, and this transit closes at
                              once (the seam looked; the call is the human's).
                              The returned map carries :parked for the loop.
    - approved             -> :approved, then it executes exactly like :pass.
    - vetoed               -> :vetoed: no execution; the answer is the veto,
                              fed back to the model like any tool failure.

  The verdict is taken from the parked record by interrupt id and consumed on the
  way through, so a replayed interrupt can never run its call twice.

  WHY a call parks is computed once per transit (approval-reason) and rides
  the parked record: :tool-declares (the tool's own :requires-approval),
  :session-asks (this session required it), or :out-of-bounds (a fence-marked
  file tool whose path resolves outside the session's project directory and
  the configuration home -- only when a project is bound). The verdict of a
  human override stands: an approved out-of-bounds call executes like :pass."
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
                         (let [[result err]
                               (try [(binding [*thread-id* thread-id] ((:run tool) parsed)) nil]
                                    (catch Throwable t [nil t]))
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
                           (if err
                             {:content (ex-message err) :error true}
                             {:content (str result) :error false})))
               park (fn [reason]
                      (let [existing (parked-for-call thread-id id)
                            interrupt-id (or (:interrupt-id existing)
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
                                   reason (assoc :reason reason))}))]
           (cond
             ;; Disabled is checked FIRST: it is a hard refusal, and there is no
             ;; point parking a call that is never going to execute.
             (session-disabled? thread-id name)
             (do (report (ev/tool-pre-execute id name :disabled []))
                 (report (ev/tool-post-execute id name))
                 {:content (disabled-message name) :error true})

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
                 ;; nothing decided yet (or the verdict was already spent):
                 ;; the call is the human's until they answer.
                 (park reason)))

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
