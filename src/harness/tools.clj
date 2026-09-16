(ns harness.tools
  "The tool table: the immutable base of built-ins, the per-session overlay
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
  per-thread and gone on restart.

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
  this'. The hook engine's answer to such a call is what makes them delegable."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.editing :as editing]
            [harness.event :as ev]
            [harness.glob :as glob]
            [harness.hashline.edit :as edit]
            [harness.hashline.grep :as grep]
            [harness.hashline.replace :as replace]
            [harness.hashline.serve :as serve]
            [harness.hashline.store :as store]
            [harness.hashline.undo :as undo]
            [harness.hashline.write :as hashline-write]
            [harness.hooks.dispatch :as hook]
            [harness.providers :as providers]
            [harness.project :as project]
            [harness.skills :as skills]
            [harness.shell :as shell]
            [harness.todos :as todos]
            [harness.web :as web]
            [harness.web.search :as search])
  (:import [java.util.regex Pattern]))

;; A resident namespace, so `def`s in eval persist across calls. This is what lets
;; the agent build itself a toolset -- and hot-swap the kernel with (require .. :reload).
(create-ns 'harness.user)
(binding [*ns* (the-ns 'harness.user)] (clojure.core/refer-clojure))

;; ------------------------------------------------------------------- the base

(defonce registry (atom {}))

(defn- register!
  "Put NAME->TOOL into the process-wide base. PRIVATE, and it should stay that
  way: this is how the built-ins below are declared, and the base is
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

;; ------------------------------------------------------------------- read
;;
;; ONE TOOL, TWO FACES. The same tool name serves both editing modes because that
;; is what was asked for, and because a model that reads a file has not thereby
;; chosen how to edit it. What differs is what a row IS:
;;
;;   :str-replace  the file's text, unchanged since before any of this existed.
;;   :hashline     `anchor│content` rows, where the anchor is the ONLY safe way to
;;                 address a line -- by content cannot tell two identical lines
;;                 apart, and by line number is a number the model has to count.
;;
;; The parameters differ with it, so neither mode's description mentions a field
;; the other does not have. What does NOT differ: the path fence (both faces are
;; fence-marked), the project re-rooting, and the fact that this is where a
;; session first learns a file's anchors.

(def ^:private read-anchor-description
  (str "Read a file and return one row per line, each line prefixed by its 4-character anchor, "
       "like `Hasu│(defn f [])`. Edit by ANCHOR, never by content and never by line number: "
       "anchors are unique even for identical lines, and an anchor from a stale read is refused "
       "rather than applied to the wrong line. "
       "Paging: use `offset` (1-based, default 1) and `limit` to read part of a large file; when "
       "the output says it stopped early it names the `offset` that continues. "
       "A line too long to show still gets its anchor, so it can be replaced whole. "
       "Binary files, images, directories and UTF-16/32 text are refused by name. "
       "A relative path resolves against this session's project directory when one is bound. "
       "When bound, a path resolving outside the project directory and the configuration home "
       "parks for human approval first. "
       "After an edit the tool that made it hands back the anchors for the changed lines, so a "
       "follow-up edit needs no new read."))

(def ^:private read-anchor-params
  {:type "object"
   :properties
   {"path"   {:type "string" :description "File path."}
    "offset" {:type "integer" :minimum 1
              :description "Line number to start from (1-based). Default 1."}
    "limit"  {:type "integer" :minimum 1
              :description "How many lines to return at most."}}})

(def ^:private read-plain-description
  "Read a file. A relative path resolves against this session's project directory when one is bound. When bound, a path resolving outside the project directory and the configuration home parks for human approval first.")

(def ^:private read-plain-params
  {:type "object" :properties {"path" {:type "string" :description "File path."}}})

(defn- positive-int
  "V as a positive integer, or a NAMED failure. `offset`/`limit` arrive from JSON,
  so 0, -3 and 1.5 are all things a model can actually send -- and each of them
  means something specific enough to say back."
  [k v]
  (when (some? v)
    (when-not (and (integer? v) (pos? v))
      (throw (ex-info (str "`" (name k) "` must be a positive integer (1 or more); got "
                           (pr-str v) ".")
                      {:argument k :value v :reason :not-a-positive-integer})))
    v))

(defn- anchored-read
  "The hashline face of `read`: resolve, serve, and answer with the rows."
  [{:keys [path offset limit]}]
  (let [p (project/resolve-path *thread-id* path)
        {:keys [text]} (serve/read! *thread-id* p
                                    {:offset (positive-int :offset offset)
                                     :limit  (positive-int :limit limit)})]
    text))

(defn- t-read
  "`read`'s body, dispatched on this session's editing mode."
  [{:keys [path] :as args}]
  (let [p (project/resolve-path *thread-id* path)]
    (if (= :hashline (:mode (editing/editing-mode *thread-id*)))
      (anchored-read (assoc args :path p))
      (slurp p :encoding "UTF-8"))))

(defn- t-write
  "`write`'s body, dispatched on this session's editing mode.

  In anchor mode the file's anchors are not merely stale -- they address lines
  that are gone -- so the write RELEASES them, clears the file's undo record, and
  hands back anchored rows for what it wrote (harness.hashline.write). In
  str-replace mode none of that exists and this is the write it always was."
  [{:keys [path content] :as args}]
  (if (= :hashline (:mode (editing/editing-mode *thread-id*)))
    (hashline-write/perform! *thread-id* #(project/resolve-path *thread-id* %) args
                             (editing/editing-mode *thread-id*))
    (let [p (project/resolve-path *thread-id* path)]
      (write-file! p content)
      (str "wrote " (count content) " chars to " p))))

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

(defn- t-skill
  "Load a skill into the conversation. Answers with the confirmation that
  harness.skills/derived-injections then looks for -- that string is the ONLY
  record that a load happened, which is why it is shared rather than written
  twice (see harness.skills/loaded-prefix).

  The name never becomes a path: harness.skills/skill-for looks it up among the
  names an actual directory listing produced, so '../../etc/passwd' is refused
  as an unknown skill rather than resolved into anything.

  THE MODEL'S HALF OF A TWO-WAY LOAD. A person loads a skill by typing `/name` in
  the composer (harness.skills/slash-request); this tool is the model's way, and
  the two differ in exactly one place: `disable-model-invocation` is refused here
  and allowed there.

  NOT marked :requires-approval. Reading instructions is not a side effect, and
  everything the body goes on to ask for is gated by its own seam: the fence
  still parks a file path, a declared approval still parks its tool. Gating the
  read would make the instructions harder to obtain than the actions they
  describe, which is backwards."
  [{:keys [name]}]
  (let [roots (project/skill-roots *thread-id*)
        entry (skills/skill-for roots name)]
    (cond
      (nil? entry)
      (throw (ex-info (skills/absent-notice roots name)
                      {:name name :known (skills/known-names roots)}))

      (not (:available? entry))
      ;; The sentence comes from harness.skills/broken-notice, which spells it once
      ;; for both load paths. That move also retired a latent bug: this branch used
      ;; to inline `(name (:reason entry))` with `name` bound to the SKILL'S NAME,
      ;; so the shadowing made it call a String as a function -- a broken skill got
      ;; a ClassCastException instead of the refusal. Nothing exercised the branch,
      ;; which is the only reason it survived; skills_test reaches it now.
      (throw (ex-info (skills/broken-notice name entry)
                      {:name name :reason (:reason entry) :path (:path entry)}))

      ;; THE FLAG, FINALLY ENFORCED. `disable-model-invocation: true` is the file
      ;; saying this one is not the model's to reach for -- and leaving the name
      ;; out of the catalog was concealment, not a refusal: a guessed name loaded
      ;; it. Now the guess is refused by name. A PERSON still can (that is what
      ;; the flag reserves), which is the one thing the two load paths do
      ;; differently, and the refusal says so rather than leaving the model to
      ;; read a missing catalog entry as an oversight.
      (:disable-model-invocation? entry)
      (throw (ex-info (str "skill " (pr-str name) " is not for the model to load: its"
                           " SKILL.md sets disable-model-invocation, so it is kept out of"
                           " the catalog on purpose. A person can still load it by typing"
                           " /" name " in the composer.")
                      {:name name :reason :model-invocation-disabled}))

      :else
      (let [{:keys [body missing]} (skills/body entry)]
        (if missing
          (throw (ex-info missing {:name name :path (:path entry)}))
          (str (skills/loaded-summary name (count body))
               "\n" (:dir entry) " is the skill's directory; read files under it by absolute path."))))))

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

  THE EDITING MODE SUBTRACTS FROM THIS LIST, and it is the only thing that does.
  A session is served ONE editing toolset -- the mode's -- so the other mode's
  tools never reach the model. Everything else stays in, including tools this
  session has switched OFF: availability is enforced per call at the execution
  seam, not by omission, and a model that cannot see a switched-off tool would
  read its absence as 'this does not exist'.

  That distinction is worth keeping straight, because the mode's subtraction
  looks like the same trick. It is not, and the difference is what the model
  learns: a disabled tool is VISIBLE and its calls are refused, while an unserved
  tool is absent from the list and its calls are refused by name with the
  substitute and the config key to switch (harness.editing/unserved-message).
  Either way nobody is left guessing -- which is the property both mechanisms are
  actually for."
  ([] (specs nil))
  ([thread-id]
   (mapv (fn [[n t]] {:type "function"
                      :function (assoc (tool-face thread-id [n t])
                                       :name n)})
         (sort-by key (into {}
                            (filter (fn [[n _]] (editing/served? thread-id n))
                                    (effective-tools thread-id)))))))

;; -------------------------------------------------------------- the built-ins

;; The file tools carry :fence-paths -- when the session is bound to a
;; project directory, a path resolving outside the project directory AND the
;; configuration home parks for approval before it runs. Unbound sessions are
;; untouched: the fence is a property of the tool MARKER, engaged only by a
;; binding, and the park itself is the ordinary approval flow.

(register! "read"
  (assoc (tool read-plain-description
               {"path" {:type "string" :description "File path."}}
               [:path] t-read)
         :fence-paths true
         ;; `read` and `write` are the two tools whose face follows the editing
         ;; mode: both keep their NAME (the user asked for one `read`, one `write`)
         ;; while what they do differs, so the description has to say which of the
         ;; two behaviours THIS session gets. Both read faces keep :required
         ;; [:path] -- offset and limit are optional in the anchored one, and
         ;; nothing else about the call moves (see tool-face).
         :describe (fn [thread-id]
                     (if (= :hashline (:mode (editing/editing-mode thread-id)))
                       {:description read-anchor-description :parameters read-anchor-params}
                       {:description read-plain-description :parameters read-plain-params}))))

(def ^:private write-anchor-description
  (str "Write a file, overwriting it. "
       "This session edits by anchor, so a successful write RELEASES the file's"
       " anchors: the content is no longer what they were minted against. The"
       " answer shows the top of the file it just wrote with the anchors that name"
       " those lines now, so you can edit what you wrote without reading it back. "
       "Content that begins a line with an anchor of this file followed by `│` is"
       " refused -- that is read's markup copied back in, not text. "
       "A relative path resolves against this session's project directory when one"
       " is bound. When bound, a path resolving outside the project directory and"
       " the configuration home parks for human approval first."))

(def ^:private write-plain-description
  "Write a file, overwriting it. A relative path resolves against this session's project directory when one is bound. When bound, a path resolving outside the project directory and the configuration home parks for human approval first.")

(defn- write-face
  "`write`'s description in THREAD-ID's session. The parameters do not vary -- the
  arguments are the same two either way -- so only the text does; see tool-face for
  why the two modes share one tool name at all."
  [thread-id]
  {:description (if (= :hashline (:mode (editing/editing-mode thread-id)))
                  write-anchor-description
                  write-plain-description)
   :parameters  {:type "object"
                 :properties {"path"    {:type "string" :description "File path."}
                              "content" {:type "string" :description "Full new contents."}}}})

(register! "write"
  (assoc (tool write-plain-description
               {"path"    {:type "string" :description "File path."}
                "content" {:type "string" :description "Full new contents."}}
               [:path :content] t-write)
         :fence-paths true
         :describe write-face))

(register! "edit"
  (assoc (tool "Replace an exact string in a file. Fails if old_string is absent or not unique. A relative path resolves against this session's project directory when one is bound. When bound, a path resolving outside the project directory and the configuration home parks for human approval first."
               {"path"       {:type "string" :description "File path."}
                "old_string" {:type "string" :description "Exact text to replace."}
                "new_string" {:type "string" :description "Replacement text."}}
               [:path :old_string :new_string] t-edit)
         :fence-paths true))

;; ------------------------------------------------------------------ replace
;;
;; The anchor-addressed edit. Prompt text lives here rather than in replace.clj
;; because it is what the MODEL reads, and this is the file where a tool's face is
;; declared -- the two must not be able to drift apart.
;;
;; It describes what the tool DOES TODAY, and nothing more. It carried two
;; sentences that outran their tickets for a while -- the healing answer and the
;; one-commit batch -- and both are now true, so both are back below. The habit is
;; worth keeping: a model told to expect what does not happen learns to distrust the
;; whole description.

(def ^:private replace-description
  (str "Replace a range of lines in a file, addressed by 4-character ANCHORS from "
       "read output: `remove_from` and `remove_to` are the first and last line of the "
       "range (the same anchor twice for one line), and `replacement_lines` is an "
       "array with one string per new line -- bare lines, no `│`, no embedded "
       "newlines. An empty array deletes the range; an array holding one empty string "
       "inserts one blank line. "
       "Every line of the range must still be what read showed. A refusal is not a "
       "dead end: a file that changed under the session, or a range that runs into a "
       "line you were never shown, comes back with those lines and their CURRENT "
       "anchors, so retry with those instead of re-reading. "
       "The answer shows the changed region as `+`, `-` and context rows, each with "
       "the CURRENT anchor for its line -- `+` and context rows are immediately "
       "editable, so a follow-up edit needs no read. A `-` row's anchor column is "
       "blank because that line is gone. "
       "A replacement that repeats the line just outside the range has that line "
       "deduplicated (reported as a `dedup│` row), so re-including a boundary line is "
       "safe but unnecessary. "
       "SEVERAL replace calls on ONE file in ONE message are applied as ONE commit: "
       "they are checked against the same state, written together or not at all, and "
       "ONE undo takes the whole message back. Their line ranges must not overlap -- "
       "an overlap is refused (nothing is written) and you get the current anchors to "
       "retry with; send the second edit in a later message if you meant both. "
       "A relative path resolves against this session's project directory when one is "
       "bound. When bound, a path resolving outside the project directory and the "
       "configuration home parks for human approval first."))

(def ^:private replace-params
  {:type "object"
   :properties
   {"remove_from"       {:type "string"
                         :description (str "Anchor of the FIRST line to remove: the four "
                                           "characters before the `│` of a read row. "
                                           "Never the row's content, never a line number.")}
    "remove_to"         {:type "string"
                         :description (str "Anchor of the LAST line to remove, inclusive. "
                                           "Omit it to change a single line.")}
    "replacement_lines" {:type "array"
                         :items {:type "string"}
                         :description (str "One string per replacement line. No `│`, no "
                                           "embedded newlines. [] deletes the range; "
                                           "[""] inserts one blank line.")}}})

(defn- anchor-replace
  "The body `replace` and `insert` share: resolve the path for the session and hand
  the rest to the engine, which tells the two apart by their payload. The fence
  looked at the same path before this ran (see `:path-for`)."
  [args]
  (replace/perform! *thread-id* #(project/resolve-path *thread-id* %) args
                    (editing/editing-mode *thread-id*)))

(register! "replace"
  (assoc (tool replace-description
               (get replace-params :properties)
               [:remove_from :replacement_lines] anchor-replace)
         :fence-paths true
         ;; The fence has no `path` argument to look at when the model omits it, so
         ;; the target is derived here -- the same derivation the body uses, so the
         ;; call that parks and the call that runs are about the same file.
         :path-for (fn [thread-id parsed]
                     (replace/target-path thread-id parsed (editing/editing-mode thread-id)))))

(def ^:private insert-description
  (str "Insert new lines next to a line, addressed by the 4-character ANCHOR from"
       " read output. `direction` is \"after\" to add them below that line or"
       " \"before\" to add them above it; `lines` is an array with one string per"
       " new line -- bare lines, no `│`, no embedded newlines. An array holding one"
       " empty string inserts one blank line. "
       "THE ANCHORED LINE IS LEFT EXACTLY AS IT IS -- you never retype it, so you"
       " cannot change it by accident -- and IT KEEPS ITS ANCHOR, so the anchors you"
       " already hold stay valid after an insert and no re-read is needed. An empty"
       " `lines` array changes nothing. "
       "Inserting a line that reads like its neighbour is fine here: unlike replace,"
       " insert never deduplicates what you asked for. "
       "The answer shows the changed region as `+` and context rows with their"
       " current anchors, so a follow-up edit needs no read. "
       "A relative path resolves against this session's project directory when one is"
       " bound. When bound, a path resolving outside the project directory and the"
       " configuration home parks for human approval first."))

(def ^:private insert-params
  {:type "object"
   :properties
   {"anchor"    {:type "string"
                 :description (str "Anchor of the line to insert next to: the four"
                                   " characters before the `│` of a read row.")}
    "direction" {:type "string"
                 :enum ["before" "after"]
                 :description (str "\"after\" adds the lines below the anchored line,"
                                   " \"before\" above it.")}
    "lines"     {:type "array"
                 :items {:type "string"}
                 :description (str "One string per new line. No `│`, no embedded"
                                   " newlines. [\"\"] inserts one blank line; []"
                                   " changes nothing.")}}})

(register! "insert"
  (assoc (tool insert-description
               (get insert-params :properties)
               [:anchor :direction :lines] anchor-replace)
         :fence-paths true
         ;; Same derivation as `replace`: the fence has no `path` to look at when
         ;; the model omits it, so the target comes from the anchor.
         :path-for (fn [thread-id parsed]
                     (replace/target-path thread-id parsed (editing/editing-mode thread-id)))))

(def ^:private undo-description
  (str "Undo the LAST edit made to a file by replace or insert, restoring the file's"
       " text, its line endings, its encoding and the anchors that named those lines"
       " -- so you can go straight on editing, no read needed. "
       "ONE edit, not a stack: a second call says there is nothing left to undo, and"
       " a write clears the history (what it wrote is what is there). "
       "It REFUSES rather than overwrites when the file has changed since that edit"
       " (an editor, another tool, a bash command): nothing is written, the history"
       " is kept, and the answer says to read the file instead. A file that was"
       " deleted is restored from the history. "
       "A relative path resolves against this session's project directory when one is"
       " bound. When bound, a path resolving outside the project directory and the"
       " configuration home parks for human approval first."))

(defn- t-undo
  "`undo_last_replace`'s body. It takes the path through the same resolution the
  other file tools use, so the fence and the re-root are the same ones."
  [args]
  (undo/perform! *thread-id* #(project/resolve-path *thread-id* %) args
                 (editing/editing-mode *thread-id*)))

(register! "undo_last_replace"
  (assoc (tool undo-description
               {"path" {:type "string"
                        :description (str "The file whose last edit should be taken"
                                          " back.")}}
               [:path] t-undo)
         :fence-paths true))

(def ^:private grep-description
  (str "Search files for a pattern (ripgrep), and get every match back as an ANCHORED"
       " row: a line number for reading, and a 4-character anchor that can be edited"
       " directly. No read afterwards is needed -- the anchors in the answer are"
       " current and already usable, by replace or insert. "
       "The line number is for you to talk about a hit, never to edit by: `replace`"
       " takes anchors. "
       "Results respect .gitignore and skip binary files. `literal: true` searches for"
       " plain text, which is both faster and the way out when a pattern is refused"
       " for being able to hang a regex engine. "
       "A relative `path` resolves against this session's project directory when one"
       " is bound. When bound, a path resolving outside the project directory and the"
       " configuration home parks for human approval first."))

(defn- t-grep
  "`anchor_grep`'s body. The search root is resolved for the session exactly as the
  file tools' paths are, so a relative root means what it means everywhere else."
  [args]
  (grep/perform! *thread-id* #(project/resolve-path *thread-id* %) args
                 (editing/editing-mode *thread-id*)))

(register! "anchor_grep"
  (assoc (tool grep-description
               {"pattern"     {:type "string"
                               :description (str "Regular expression to search for"
                                                 " (or literal text, with"
                                                 " literal: true).")}
                "path"        {:type "string"
                               :description (str "File or directory to search;"
                                                 " defaults to this session's project"
                                                 " directory (or the process working"
                                                 " directory when none is bound).")}
                "glob"        {:type "string"
                               :description (str "Only search files matching this glob,"
                                                 " e.g. \"*.clj\".")}
                "ignore-case" {:type "boolean" :description "Case-insensitive search."}
                "literal"     {:type "boolean"
                               :description (str "Treat `pattern` as plain text (no"
                                                 " regex). The way out when a pattern"
                                                 " is refused as too complex.")}
                "context"     {:type "integer" :minimum 0
                               :description (str "Lines of context to show around each"
                                                 " match (0 by default). Context rows"
                                                 " carry anchors too.")}
                "limit"       {:type "integer" :minimum 1
                               :description (str "Max matches per file (default "
                                                 grep/default-limit ").")}}
               [:pattern] t-grep)
         ;; A search reads files, so the fence applies: when a project is bound, a
         ;; root outside it parks for a human exactly as a read does.
         :fence-paths true))

;; ---------------------------------------------------------------------- glob
;;
;; THE OTHER SEARCH QUESTION. `anchor_grep` answers "which lines say this"; this
;; answers "which files are named this", and its answer is a list of PATHS rather
;; than anchored rows -- there is no line here to name. So it carries no anchors,
;; belongs to neither editing family, and is served in BOTH modes (see
;; harness.editing/families: a tool named in neither family is served always).

(def ^:private glob-description
  (str "Find files by name, with a glob pattern, and get their paths back -- ready to"
       " hand to `read`. Use this for \"which files are there\" questions; use"
       " `anchor_grep` when you are looking for content. "
       "Results respect .gitignore and never include `.git`; hidden files (dotfiles)"
       " ARE listed. One absolute path per line, sorted by path. "
       "`path` narrows the search to a file or directory, and defaults to this"
       " session's project directory (or the process working directory when none is"
       " bound). "
       "A relative `path` resolves against this session's project directory when one"
       " is bound. When bound, a path resolving outside the project directory and the"
       " configuration home parks for human approval first."))

(defn- t-glob
  "`glob`'s body. The search root is resolved for the session exactly as the file
  tools' paths are, so a relative root means what it means everywhere else."
  [args]
  (glob/perform! *thread-id* #(project/resolve-path *thread-id* %) args))

(register! "glob"
  (assoc (tool glob-description
               {"pattern" {:type "string"
                           :description (str "Glob to match file names against, e.g."
                                             " \"**/*.clj\" or \"src/**/*.ts\".")}
                "path"    {:type "string"
                           :description (str "File or directory to search; defaults to"
                                             " this session's project directory (or"
                                             " the process working directory when none"
                                             " is bound).")}}
               [:pattern] t-glob)
         ;; A listing reads the tree, so the fence applies exactly as it does to a
         ;; search: a root outside the project parks for a human.
         :fence-paths true))

;; ------------------------------------------------------------------ web_fetch
;;
;; The only tool here whose subject is neither the tree nor the run: it leaves the
;; machine. NOT MARKED :requires-approval, and that is a decision rather than an
;; oversight -- `bash` reaches the network today with no gate at all, so a park on
;; this one would be a speed bump that reads as a wall. A session that wants the
;; gate installs one (see harness.web's docstring).

(def ^:private web-fetch-description
  (str "Fetch an http(s) URL and return the page's TEXT -- markup removed, so it is"
       " something to read. `<script>` and `<style>` contents are dropped and"
       " block-level tags become line breaks: this is a text extractor, NOT a"
       " renderer, so a page built by JavaScript comes back empty. "
       "The answer says where the request ended up (redirects are followed and"
       " reported), the HTTP status, the content type and the page title."
       " `text/plain` and `application/json` come back unchanged. "
       "At most " web/max-bytes " bytes of text are returned, and a truncated answer"
       " says so. "
       "A URL that is not http(s) is refused, as are a status of 400 or worse and a"
       " content type that is not text."))

(defn- t-web-fetch
  "`web_fetch`'s body. The fetching, the redirect rules and the lossy extraction are
  harness.web's -- this is the tool's face, not a second implementation of them."
  [{:keys [url]}]
  (web/fetch-text url))

(register! "web_fetch"
  (tool web-fetch-description
        {"url" {:type "string"
                :description (str "The full address to fetch, including the https://"
                                  " prefix.")}}
        [:url] t-web-fetch))

;; ----------------------------------------------------------------- web_search
;;
;; The other half of reading the web: `web_fetch` reads a URL you already have, this
;; finds one. Its destination is fixed by harness.web.search, not chosen per call by
;; the model -- see that namespace for why neither of them is marked for approval.

(def ^:private web-search-description
  (str "Search the web and get a few results back -- a title, a URL and a snippet"
       " for each. Read one of them with `web_fetch`. "
       "Use it when you do not already know the address; when you do, `web_fetch` is"
       " one call instead of two. "
       "Needs " search/api-key-env " set in the configuration home's .env (or the"
       " environment). Without it the call is refused by name and nothing else breaks. "
       "`count` defaults to " search/default-count " and may be at most "
       search/max-count "."))

(defn- t-web-search
  "`web_search`'s body. The vendor's wire -- request, key, response -- is
  harness.web.search's; this is the tool's face."
  [args]
  (search/perform args))

(register! "web_search"
  (tool web-search-description
        {"query" {:type "string" :description "What to search for."}
         "count" {:type "integer" :minimum 1
                  :description (str "How many results to ask for (default "
                                    search/default-count ", at most "
                                    search/max-count ").")}}
        [:query] t-web-search))

(register! "bash"
  (tool "Run a shell command (Git Bash on Windows, the host's shell elsewhere). The working directory is this session's project directory when one is bound, otherwise the process working directory."
        {"command" {:type "string" :description "Command line."}}
        [:command] t-bash))

(register! "eval"
  (tool "Evaluate Clojure in this process. Defs persist across calls."
        {"code" {:type "string" :description "Clojure source."}}
        [:code] t-eval))

;; The skills this session can load, by name, are announced in the run's opening
;; messages (harness.preamble) -- one line each, with the description their own
;; SKILL.md declares. The catalog is NOT repeated here: the tool's schema is part
;; of the request on every call, and a per-session catalog would make it differ
;; between sessions for a reason that has nothing to do with the tool's shape.
(register! "skill"
  (tool (str "Load a skill -- a set of instructions for a kind of task -- into this conversation. "
             "The skills available to this session are listed in the message tagged <skills> at the "
             "start of the conversation; call this with one of those names when its description "
             "matches what you are about to do. The full text is added to the conversation and "
             "stays available for the rest of the session.")
        {"name" {:type "string" :description "The skill's name, as listed in <skills>."}}
        [:name] t-skill))

;; ------------------------------------------------------------------- todo_write
;;
;; The session's task list, and the only tool here whose subject is the run rather
;; than the tree. It belongs to NEITHER editing family (it touches no file), so it
;; is served in both modes.
;;
;; The list REPLACES rather than appends, which is what makes it state -- and what
;; makes two calls in one message meaningless. `sole-call-of-its-name?` is the
;; check, and it is the seam's own turn plan rather than anything tool-specific:
;; the run loop is the only place that sees a whole message (see `plan-turn`).

(def ^:private todo-write-description
  (str "Record this session's task list: the items you are working through, in order,"
       " with the state of each one. Use it when the work has several steps, so the"
       " plan is visible and you can see what is left. "
       "`todos` is the COMPLETE list, each item {\"content\": \"..\", \"status\": \"..\"}"
       " where status is one of \"pending\", \"in_progress\", \"completed\"; an empty"
       " array clears the list. There is no append and no partial update -- send the"
       " whole list every time. At most one item may be \"in_progress\": the list has"
       " to say what you are doing NOW. "
       "The list belongs to this session and is stored with it, so it outlives this"
       " run. Call this at most ONCE per message: a list is replaced whole, so two"
       " calls in one message have nothing to merge and NEITHER is applied."))

(declare sole-call-of-its-name?)

(defn- t-todo-write
  "`todo_write`'s body. Its rules live in harness.todos (one place, shared with any
  other caller); the one thing here is the per-MESSAGE rule, because this is the
  layer that can see a whole message.

  The argument is read out of ARGS rather than destructured: the natural binding
  name for it is `todos`, which is what this namespace calls harness.todos."
  [args]
  (when-not (sole-call-of-its-name? "todo_write")
    (throw (ex-info (str "this message holds more than one todo_write. A task list is"
                         " replaced WHOLE, so two calls in one message have nothing to"
                         " merge -- neither was applied. Send the list once, and the"
                         " next update in a later message.")
                    {:reason :second-todo-write-in-turn})))
  (todos/render (todos/write! *thread-id* (:todos args))))

(register! "todo_write"
  (tool todo-write-description
        {"todos" {:type "array"
                  :items {:type "object"
                          :properties {"content" {:type "string"
                                                  :description (str "What the item is,"
                                                                    " in one line.")}
                                       "status"  {:type "string"
                                                  :enum ["pending" "in_progress" "completed"]
                                                  :description (str "Where it stands."
                                                                    " At most one item in"
                                                                    " the list may be"
                                                                    " \"in_progress\".")}}
                          :required ["content" "status"]}
                  :description (str "The COMPLETE list, in order. [] clears it.")}}
        [:todos] t-todo-write))

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

(defn- fenced-path
  "The path the fence should judge for this call, or nil when there is nothing to
  judge.

  Usually that is the `path` argument. A tool may declare `:path-for` instead, for
  the calls whose target is DERIVED rather than given -- `replace` addresses a line
  by anchor, and the anchor is what names the file (see
  harness.hashline.replace/target-path). Without this, an anchor-addressed edit to a
  file outside the project would run with no fence at all, because there is no path
  argument to look at.

  Either way this is best-effort: it runs BEFORE the argument checks, so a malformed
  payload must come back as nil (no path to judge) rather than as an exception from
  a derivation that was handed nonsense. The argument check is what reports the
  malformed payload, one line later, with the useful message."
  [tool name thread-id parsed]
  (or (:path parsed)
      (when-let [derive (:path-for tool)]
        (try (derive thread-id parsed) (catch Throwable _ nil)))))

(defn- approval-reason
  "WHY this call parks -- nil meaning it does not. The same union
  approval-required? answered, now with the reason attached, in the order the
  or short-circuited: the tool's own declaration first, then the session's
  ask, then the project fence (a fence-marked tool whose path, resolved for
  this session and possibly derived from its arguments, lands outside the project
  directory and the configuration home). The reason rides the parked record, so the
  human deciding -- and any reader of the audit trail -- can tell a declared-approval
  call from a fence catch without re-deriving either.

  A call with NO PATH AT ALL is not a fence case, whatever else it is. The fence is
  a question about a path, and asking it about a missing one would answer with an
  exception from deep inside java.io rather than with the useful fact -- that the
  argument is absent, which the missing-arguments check says a line later. Reading
  'no path' as 'not out of bounds' keeps that check reachable, which is what a call
  with no arguments should be told."
  [tool name thread-id parsed]
  (cond
    (:requires-approval tool)                       :tool-declares
    (session-approval-required? thread-id name)     :session-asks
    (and (:fence-paths tool)
         (some? (fenced-path tool name thread-id parsed))
         (project/out-of-bounds? thread-id (fenced-path tool name thread-id parsed)))
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
  "What the model is told when it calls a tool this session switched off. Like a
  veto, this is information rather than a run failure -- and it says DISABLED,
  never unknown: the tool exists and is on offer, so calling its absence a lie
  would only send the model hunting for a workaround.

  When the session's editing mode does not serve NAME either, the last sentence
  is not offered, because it would be a false promise: re-enabling a tool the
  mode subtracts changes nothing about the next call. Both facts are true at
  once, so both are stated -- the refusal is the session's own switch AND the
  mode's subtraction, and a reader who acts on only half of that will try the
  same call again and be told the same thing."
  [thread-id name]
  (str "disabled in this session: " name
       " is switched off."
       (if (editing/served? thread-id name)
         (str " Re-enable it with (harness.tools/session-enable!"
              " harness.tools/*thread-id* \"" name "\").")
         (str " Re-enabling it will not make it run, either: "
              (editing/unserved-message thread-id name)))))

;; ------------------------------------------------------------------- the batch

;; SEVERAL ANCHOR EDITS TO ONE FILE IN ONE MESSAGE ARE ONE COMMIT.
;;
;; The reason is not tidiness, it is that the alternative LOSES DATA. A turn's tool
;; calls run concurrently (`harness.loop/drive!`), and two edits to one file each
;; validate against the state the session was shown and each compute their own new
;; content. Run independently, the later write wins and the earlier one is gone --
;; with BOTH reporting success. No lock around a single edit fixes that, because
;; the two edits are individually correct; what is wrong is applying them apart.
;;
;; So the edits of one message are applied as one patch: validated against the
;; state BEFORE the message, required to have disjoint line ranges (an overlap is a
;; REFUSAL, not a question about which ran first), and landed as one write, one
;; anchor advance and one undo record. The model gets one diff, one set of current
;; anchors and one undo for the thought it had.
;;
;; WHICH CALL APPLIES IT is decided here, because this is the only place that sees
;; a whole turn at once. The last of a group applies it; the others answer
;; immediately, having touched nothing, and say where the outcome appears. No call
;; waits on another, so a refusal or a park anywhere in the message cannot strand
;; the rest.

(defn- batchable?
  "May this call be merged with its siblings? Only `replace` -- see the batch section
  above for why splicing two of them into one patch is possible at all, and
  `insert`'s own registration for the same treatment when ticket 10 lands."
  [name]
  (contains? #{"replace" "insert"} name))

(defn- group-key
  "The file a call addresses, as a canonical path -- or nil when it cannot be worked
  out. Best effort on purpose: a call whose target cannot be derived does not join a
  batch, it runs on its own and reports its own error. Refusing a whole message
  because one of its calls is malformed would refuse edits that are perfectly fine.

  DELIBERATELY NOT the full payload parse. This asks one question -- which file? --
  and a call with a broken `replacement_lines` still answers it; making the answer
  depend on the rest of the payload would silently exclude exactly the calls whose
  refusals a batch most needs to report."
  [thread-id parsed]
  (try
    (let [anchor (edit/bare-anchor (or (:remove_from parsed) (:replace_from parsed)
                                       (:anchor parsed)))
          owner  (when anchor (store/owner-of thread-id anchor))
          p      (or owner (:path parsed))]
      (when (and (string? p) (not (str/blank? p)))
        (store/canonical (project/resolve-path thread-id p))))
    (catch Throwable _ nil)))

(defn- call-args
  "A call's arguments as a keyword-keyed map, or nil when the JSON is malformed.
  Nil rather than an exception because the only caller is deciding whether this call
  JOINS A BATCH -- and a call whose arguments cannot be read is one that should run
  on its own and produce the bad-argument message, not one that should take the
  message down."
  [{:keys [function]}]
  (try (json/read-str (if (str/blank? (:arguments function)) "{}"
                          (:arguments function))
                      :key-fn keyword)
       (catch Throwable _ nil)))

(defn- plan-turn
  "The turn's calls as a plan: TOOL-CALL-ID -> {:role :applier|:member, :group n,
  :index i, :path p, :members [args ..]}, for every call that shares its target
  file with another.

  Only groups of MORE THAN ONE appear: a lone edit is not a batch, and routing it
  through the batch path would change what its answer looks like for no reason."
  [thread-id calls]
  (let [parsed (keep (fn [{:keys [id function] :as call}]
                       (let [{:keys [name]} function]
                         (when (and (batchable? name)
                                    (editing/served? thread-id name)
                                    ;; ...and a name that exists, or the plan would
                                    ;; be promising work to a tool the seam is about
                                    ;; to answer with "unknown tool"
                                    (contains? (effective-tools thread-id) name))
                           (when-let [args (call-args call)]
                             (when-let [k (group-key thread-id args)]
                               {:id id :name name :k k :args args})))))
                     calls)
        groups (into {} (filter (fn [[_ g]] (> (count g) 1))
                                (group-by :k parsed)))]
    (into {}
          (for [[path g] groups
                :let [members (vec g)
                      n (count members)]]
            (into {} (map-indexed
                      (fn [i m]
                        [(:id m) {:role    (if (= i (dec n)) :applier :member)
                                  :group   n
                                  :index   i
                                  :path    path
                                  :name    (:name m)
                                  :members (mapv :args members)}])
                      members))))))

(defonce ^:private turn-plan (atom {}))
;; {:plan {call-id -> role} :counts {tool-name -> how many times it is called}}
;;
;; TWO FACTS ABOUT ONE TURN, in one atom, because both are answers to 'what else is
;; in this message' and a second atom would be a second thing to reset. The plan is
;; the anchor-edit batching above; the counts are what `sole-call-of-its-name?`
;; reads.

(defn- plan-counts
  "How many times each tool NAME is called in this turn. Only names the session can
  actually call are counted: a call of a name that does not exist is answered
  'unknown tool' by the seam and cannot be a sibling of anything."
  [thread-id calls]
  (->> calls
       (keep (fn [{:keys [function]}]
               (let [n (:name function)]
                 (when (contains? (effective-tools thread-id) n) n))))
       frequencies))

(defn register-turn!
  "Hand the seam the calls of the turn about to run, so each can be told who its
  siblings are. Called by the run loop -- the only place that sees a whole turn at
  once -- and best effort throughout: a turn whose calls were never registered (a
  direct `run!`, a replayed approval) behaves exactly as it did before batching
  existed, which is one call, one edit."
  [thread-id calls]
  (reset! turn-plan (try {:plan   (plan-turn thread-id calls)
                          :counts (plan-counts thread-id calls)}
                         (catch Throwable _ {}))))

(defn forget-turn!
  "Drop the plan once the turn's calls have all answered. Without this the map grows
  with the process, one entry per anchor edit ever made."
  []
  (reset! turn-plan {}))

(defn sole-call-of-its-name?
  "Is the call being run the ONLY call of NAME in its turn?

  TWO CALLS OF THE SAME NAME IN ONE MESSAGE are usually fine -- two edits to two
  files, two reads -- but they are not for a tool that REPLACES a whole value:
  `todo_write` sends the complete list every time, so a second call in the same
  message has no meaning to merge. A turn's calls run concurrently, so the later
  write would win while BOTH reported success -- the silent data loss the batch
  section above exists to prevent, arrived at from the other direction.

  TRUE WHEN THE TURN WAS NEVER REGISTERED (a direct `run!`, a replayed approval):
  those callers run one call at a time, which is the case the rule is about."
  [name]
  (<= (long (get (:counts @turn-plan) name 0)) 1))

(defn- batch-role
  "What this call's part in its message is, or nil when it is an ordinary edit."
  [id]
  (get-in @turn-plan [:plan id]))

(defn- run-batch!
  "The APPOINTED call of a group runs the whole group. The members are the same
  calls' arguments, in call order, and every one of them addresses this file -- see
  the batch section of harness.hashline.replace for the arithmetic, and `plan-turn`
  for who decided that these calls belong together."
  [{:keys [members]} thread-id]
  (binding [*thread-id* thread-id]
    (replace/perform-edits! thread-id #(project/resolve-path thread-id %) members
                            (editing/editing-mode thread-id))))

(defn run!
  "The ONE tool execution seam. The call's lifecycle is reported to ON-PHASE
  (a fn of kernel events, may be nil) as it passes through:
    :tool/pre-execute   -- entered the seam; outcome :pass, :unknown-tool,
                           :unserved, :disabled, :missing-args (with the missing
                           names), :hook-blocked, :needs-approval, :approved, or
                           :vetoed
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
                                        ;; whole group (the last of them) or it
                                        ;; answers with a note and touches nothing.
                                        ;; See the batch section above.
                                        (if (= :applier (:role role))
                                          (run-batch! role thread-id)
                                          (replace/merged-note
                                           {:index (:index role)
                                            :size  (:group role)
                                            :path  (:path role)}))
                                        ((:run tool) parsed)))
                               [result err]
                               (try [(binding [*thread-id* thread-id] (body)) nil]
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
               park (fn [reason interrupt-id]
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
                                   reason (assoc :reason reason))}))]
           (cond
             ;; Disabled is checked FIRST: it is a hard refusal, and there is no
             ;; point parking a call that is never going to execute.
             (session-disabled? thread-id name)
             (do (report (ev/tool-pre-execute id name :disabled []))
                 (report (ev/tool-post-execute id name))
                 {:content (disabled-message thread-id name) :error true})

             ;; ...then the editing mode's subtraction, which is the only other
             ;; thing that can take a registered tool out of a session's set. It
             ;; is still AHEAD of the argument and approval checks: a call this
             ;; session does not serve will not run whatever its arguments are,
             ;; and parking it would ask a person about a call that could not
             ;; have executed anyway.
             (not (editing/served? thread-id name))
             (do (report (ev/tool-pre-execute id name :unserved []))
                 (report (ev/tool-post-execute id name))
                 {:content (editing/unserved-message thread-id name) :error true})

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
