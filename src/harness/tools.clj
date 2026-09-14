(ns harness.tools
  "The five tools. A tool is
     {:description string :parameters JSON-Schema :required [kw..] :run (fn [args] string)}

  RUN gets a keyword-keyed argument map and returns a string.

  run! takes a tool call in the PROVIDER's shape -- {:id .. :type \"function\"
  :function {:name .. :arguments json-string}} -- because the kernel keeps messages
  in the provider shape and never converts. It never throws and never returns nil:
  a tool failure is information for the model, not a failure of the run."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [harness.event :as ev]
            [harness.memory :as mem]
            [harness.project :as project])
  (:import [java.util.regex Pattern]))

;; The tool registry itself lives in harness.memory (the introspectable
;; surface): the agent reads and extends its own toolset through eval. This
;; namespace owns only the tool SHAPE, the five built-ins, and dispatch.

;; A resident namespace, so `def`s in eval persist across calls. This is what lets
;; the agent build itself a toolset -- and hot-swap the kernel with (require .. :reload).
(create-ns 'harness.user)
(binding [*ns* (the-ns 'harness.user)] (clojure.core/refer-clojure))

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

;; `bash` on PATH is C:\WINDOWS\System32\bash.exe -- the WSL launcher, a different
;; filesystem entirely, which fails silently from a JVM. Pin Git Bash by path.
(defonce git-bash
  (or (first (filter #(.exists (io/file %))
                     ["C:\\Program Files\\Git\\bin\\bash.exe"
                      "C:\\Program Files\\Git\\usr\\bin\\bash.exe"]))
      "bash"))

;; --------------------------------------------------------------------- tools

;; The file tools resolve their path through harness.project first: a session
;; bound to a project directory gets its RELATIVE paths re-rooted there, an
;; unbound session passes paths through unchanged (the pre-binding behavior,
;; byte for byte). The tool's answer reports the RESOLVED path -- what actually
;; happened, wherever the model's relative path ended up landing.

(defn- t-read [{:keys [path]}]
  (slurp (project/resolve-path mem/*thread-id* path) :encoding "UTF-8"))

(defn- t-write [{:keys [path content]}]
  (let [p (project/resolve-path mem/*thread-id* path)]
    (write-file! p content)
    (str "wrote " (count content) " chars to " p)))

(defn- t-edit [{:keys [path old_string new_string]}]
  (let [p (project/resolve-path mem/*thread-id* path)
        s (slurp p :encoding "UTF-8")
        n (count (re-seq (re-pattern (Pattern/quote old_string)) s))]
    (when (zero? n) (throw (ex-info (str "old_string not found in " p) {})))
    (when (> n 1)
      (throw (ex-info (str "old_string occurs " n " times in " p ", make it unique") {})))
    (write-file! p (str/replace-first s old_string new_string))
    (str "edited " p)))

(defn- t-bash [{:keys [command]}]
  (let [dir (project/binding-for mem/*thread-id*)
        {:keys [exit out err]} (apply shell/sh git-bash "-lc" command :out-enc "UTF-8"
                                      (when dir [:dir dir]))
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
  boundary: eval can still reach harness.memory/use-provider! directly, and bash
  can still read .env. It is here to stop a slip, and it is labelled as such."
  [{:keys [provider model reasoning-effort]}]
  (let [thread-id mem/*thread-id*
        change    (cond-> {}
                    (some? provider)         (assoc :provider provider)
                    (some? model)            (assoc :model model)
                    (some? reasoning-effort) (assoc :reasoning-effort reasoning-effort))]
    (when (empty? change)
      (throw (ex-info "nothing to change: give at least one of provider, model, reasoning-effort" {})))
    (let [before (mem/override-for thread-id)
          ;; Resolve BEFORE writing: this proves the change can actually be
          ;; served and hands the writer the resolved shape, so the log records
          ;; what the session became rather than what it was asked to become.
          resolved (mem/resolve-override (merge before change))
          ;; set-override! answers with what it stored, and THAT is what the
          ;; change line records -- not `change` merged over `before` a second
          ;; time here. The stored value is the one the next run folds; a
          ;; parallel copy is how a log and a session drift apart.
          after (mem/set-override! thread-id (merge before change))]
      (mem/record-provider-change! thread-id before after "session-configure"
                                   after (:resolved resolved))
      (str "session reconfigured: " (pr-str change)
           " -- effective now for this thread only."
           (when-let [m (:model (:resolved resolved))] (str " Serving " m "."))
           (when (nil? thread-id)
             " (warning: no session in scope; the change landed on the process-wide slot)")))))

;; ------------------------------------------------------------------ registry

(defn specs
  "The tools array as an OpenAI-compatible provider expects it, for THREAD-ID's
  effective toolset (base overlaid with its session additions/removals)."
  ([] (specs nil))
  ([thread-id]
   (mapv (fn [[n t]] {:type "function"
                      :function {:name n
                                 :description (:description t)
                                 :parameters (:parameters t)}})
         (sort-by key (mem/effective-tools thread-id)))))

;; ------------------------------------------------------------------ registry

;; read/write/edit carry :fence-paths -- when the session is bound to a
;; project directory, a path resolving outside the project directory AND the
;; configuration home parks for approval before it runs. Unbound sessions are
;; untouched: the fence is a property of the tool MARKER, engaged only by a
;; binding, and the park itself is the ordinary approval flow.

(mem/register! "read"
  (assoc (tool "Read a file. A relative path resolves against this session's project directory when one is bound. When bound, a path resolving outside the project directory and the configuration home parks for human approval first."
               {"path" {:type "string" :description "File path."}}
               [:path] t-read)
         :fence-paths true))

(mem/register! "write"
  (assoc (tool "Write a file, overwriting it. A relative path resolves against this session's project directory when one is bound. When bound, a path resolving outside the project directory and the configuration home parks for human approval first."
               {"path"    {:type "string" :description "File path."}
                "content" {:type "string" :description "Full new contents."}}
               [:path :content] t-write)
         :fence-paths true))

(mem/register! "edit"
  (assoc (tool "Replace an exact string in a file. Fails if old_string is absent or not unique. A relative path resolves against this session's project directory when one is bound. When bound, a path resolving outside the project directory and the configuration home parks for human approval first."
               {"path"       {:type "string" :description "File path."}
                "old_string" {:type "string" :description "Exact text to replace."}
                "new_string" {:type "string" :description "Replacement text."}}
               [:path :old_string :new_string] t-edit)
         :fence-paths true))

(mem/register! "bash"
  (tool "Run a shell command in Git Bash. The working directory is this session's project directory when one is bound, otherwise the process working directory."
        {"command" {:type "string" :description "Command line."}}
        [:command] t-bash))

(mem/register! "eval"
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
(mem/register! "session-configure"
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
    (mem/session-approval-required? thread-id name) :session-asks
    (and (:fence-paths tool)
         (project/out-of-bounds? thread-id (:path parsed)))
    :out-of-bounds
    :else nil))

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
       " is switched off. Re-enable it with harness.memory/session-enable!."))

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

  The verdict is taken from harness.memory by interrupt id and consumed on the
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
     (if-let [tool (get (mem/effective-tools thread-id) name)]
       (try
         (let [parsed  (json/read-str (if (str/blank? arguments) "{}" arguments)
                                      :key-fn keyword)
               missing (missing-args tool parsed)
               reason  (approval-reason tool name thread-id parsed)
               execute (fn []
                         ;; *thread-id* is bound around the tool body so code
                         ;; running inside a tool -- eval above all -- can address
                         ;; its own session (harness.memory).
                         (let [[result err]
                               (try [(binding [mem/*thread-id* thread-id] ((:run tool) parsed)) nil]
                                    (catch Throwable t [nil t]))
                               _ (report (ev/tool-executed id name (some-> err ex-message)))
                               _ (report (ev/tool-post-execute id name))]
                           (if err
                             {:content (ex-message err) :error true}
                             {:content (str result) :error false})))
               park (fn [reason]
                      (let [existing (mem/parked-for-call thread-id id)
                            interrupt-id (or (:interrupt-id existing)
                                             (str (java.util.UUID/randomUUID)))]
                        (mem/park-approval! interrupt-id (cond-> {:thread-id thread-id
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
             (mem/session-disabled? thread-id name)
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
             (let [existing (mem/parked-for-call thread-id id)
                   decision (when existing (mem/take-decision! (:interrupt-id existing)))]
               (case (:verdict decision)
                 :approved (do (report (ev/tool-pre-execute id name :approved []))
                               (execute))
                 :vetoed   (do (report (ev/tool-pre-execute id name :vetoed []))
                               (report (ev/tool-post-execute id name))
                               {:content (veto-message decision) :error true})
                 ;; nothing decided yet (or the verdict was already spent):
                 ;; the call is the human's until they answer.
                 (park reason)))

             :else
             (do (report (ev/tool-pre-execute id name :pass []))
                 (execute))))
         (catch Throwable t
           ;; A malformed argument payload dies before the pass branch even
           ;; starts; the lifecycle still closes on the seam's own terms.
           (report (ev/tool-executed id name (ex-message t)))
           (report (ev/tool-post-execute id name))
           {:content (ex-message t) :error true}))
       (do (report (ev/tool-pre-execute id name :unknown-tool []))
           (report (ev/tool-post-execute id name))
           {:content (str "unknown tool: " name) :error true})))))
