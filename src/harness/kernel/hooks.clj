(ns harness.kernel.hooks
  "The hook engine's data half: WHICH hook points exist, and what a user's
  hooks.edn is allowed to declare at each of them.

  A HOOK POINT is data, not code:

    {:name     \"PreToolUse\"   ; the name the payload carries (CodeBuddy's spelling)
     :when     \"...\"          ; one line: when this point fires
     :payload  #{..}           ; the payload keys this point adds beyond the
                               ;   common four (hook / thread_id / project_dir)
     :matches  :tool_name      ; the PAYLOAD KEY a declaration's :matcher is
                               ;   compared against, or nil when this point has
                               ;   nothing to match -- naming a payload key
                               ;   rather than a concept means the matcher is
                               ;   always compared against the same string the
                               ;   command just read on stdin
     :gate?    true            ; does this point's verdict redirect the run?
     :on-error :block          ; what a TIMEOUT or a failed spawn means here:
                               ;   :block for a gate, :proceed for an observer
     :stdout   :content}       ; what a declaration's STDOUT means here, when it
                               ;   means anything. The default (the key absent)
                               ;   is the base protocol: exit codes decide, and
                               ;   stdout is read only for a JSON answer. :content
                               ;   makes stdout the THING ITSELF -- the text the
                               ;   point collects -- which is the one cell that
                               ;   makes a point's results more than a verdict

  ADDING A POINT IS ADDING A ROW. The engine's dispatch reads this table; it has
  no per-point code, which is the property the whole hook design rests on: a new
  hook point must not be a new special case in the execution path.

  WHAT THIS NAMESPACE OWNS: the point table, the RULES for the three sources
  declarations come from (the kernel's own, the files, and the session's overlay),
  validation of a declaration, and the one read entry -- `effective-hooks` -- that
  answers which declarations are in force for a thread. It does NOT run anything: spawning, exit codes, timeouts and the
  audit line are `harness.kernel.hooks.dispatch`'s, and the session-level overlay that
  lets a running session add or switch off its own declarations is the session
  overlay below.

  THREE SOURCES, ONE READ, and ONE ORDER. WHO READS THE FILES IS NOT THIS NAMESPACE'S
  BUSINESS: which two files, in what order and how they merge is a capability, and it
  arrives through `install!` as a DECLARATIONS function -- exactly as the tool table
  arrives through the same-shaped door in harness.kernel.tools. What is here is the
  ORDER (the source tiers), the fold, and the session's own layer, which is what a
  running session may add to, remove from and switch off: per-thread, in-process,
  gone on restart. Dispatch reads the folded one and nothing else.

  THE KERNEL'S OWN ROWS ARE ROWS LIKE ANY OTHER, which is the property worth
  stating plainly rather than the three-source table being a special case: they
  sit in the same table, run through the same seam, answer with the same exit
  codes and leave the same audit line. Only two things differ, and both are on the
  row: :source is :built-in (which puts it FIRST, ahead of the files), and what it
  runs is a function (:run) rather than a command. A session can switch one off
  and back on exactly like a declared hook -- so 'the kernel's own' is a fact
  about where a row came FROM, not a run of exemptions from the engine.

  THE ORDER IS THE SOURCE, THEN THE WRITING. Built-in rows first, then the file's
  in the file's order, then what the session added in the order it added them.
  That is the whole precedence rule, and it is the same rule for every point: a
  row added later cannot silently outrank one that was already in force.

  WHAT A ROW RUNS IS ONE THING, AND IT SAYS WHICH. A declaration carries exactly
  one of :command (a non-empty string, spawned by the shell) or :run (a callable,
  invoked in this process with the payload as a map). Neither, or both, fails by
  name. `hooks.edn` may not name :run at all -- a file cannot hold a function --
  and that refusal is the same per-field validation :timeout and :matcher get,
  not an exception carved out for one key."
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------- the point table
;;
;; Spelled the way the payload spells them (CodeBuddy's CamelCase), because that
;; string is what a hook command receives on stdin and what lands in the
;; hook/<point> audit line. The EDN key a user writes in hooks.edn is the
;; kebab-case keyword beside it -- :pre-tool-use -- and `point-for` is the one
;; place the two spellings are tied together.

(def points
  "Every hook point this harness knows, as data. 27 of them.

  The P2 points and `SystemPrompt` -- FIFTEEN rows, since it sits among the P2 rows
  rather than after them -- have a trigger source in this codebase today; the P3
  points are declared and never fire until the subsystem they belong to is built --
  file watching, MCP elicitation, context compaction, subagents, tasks, git
  worktrees. A point with no trigger source simply never dispatches: that is the
  design, not an omission, and it is why a P3 point costs one row rather than an
  interface.

  THE TWO ASSEMBLY POINTS SIT ASTRIDE EACH OTHER and each owns one half of what a
  run opens with: `SystemPrompt` owns the system message (its opening is frozen,
  the rows at this point append their text behind it) and `InstructionsLoaded`
  owns the user-side opening blocks. They cannot interleave -- different message
  roles -- so 'the order has exactly one decider' holds inside each half."
  [{:name "SessionStart" :when "a session's first run starts, or a log is rebuilt and resumed"
    :payload #{:source} :matches nil :gate? false :on-error :proceed}
   {:name "UserPromptSubmit" :when "a user turn arrives, before the model sees it"
    :payload #{:prompt} :matches nil :gate? true :on-error :block}
   {:name "PreToolUse" :when "a tool call is about to run, after the disabled and approval checks"
    :payload #{:tool_name :tool_input} :matches :tool_name :gate? true :on-error :block}
   {:name "PermissionRequest" :when "a call has parked and a human (or a hook) must answer"
    :payload #{:tool_name :tool_input :interrupt_id} :matches :tool_name :gate? true :on-error :block}
   {:name "PermissionDenied" :when "a call was refused -- vetoed, disabled, or blocked"
    :payload #{:tool_name :reason} :matches :tool_name :gate? false :on-error :proceed}
   {:name "PostToolUse" :when "a tool ran and returned"
    :payload #{:tool_name :tool_input :result} :matches :tool_name :gate? false :on-error :proceed}
   {:name "PostToolUseFailure" :when "a tool ran and threw"
    :payload #{:tool_name :tool_input :error} :matches :tool_name :gate? false :on-error :proceed}
   {:name "Stop" :when "a run finished normally"
    :payload #{} :matches nil :gate? false :on-error :proceed}
   {:name "StopFailure" :when "a run ended in an error"
    :payload #{:error} :matches nil :gate? false :on-error :proceed}
   {:name "Notification" :when "the harness has something to tell the user"
    :payload #{:message} :matches nil :gate? false :on-error :proceed}
   ;; THE ONE POINT WHOSE STDOUT IS THE THING ITSELF. Everywhere else a
   ;; declaration answers with an exit code (and, at PermissionRequest, an answer
   ;; on stdout); here the matched declarations all run and every non-empty stdout
   ;; is text appended to the system message. That single difference is the
   ;; `:stdout :content` cell above and nothing else -- the seam is the same one.
   {:name "SystemPrompt" :when "a run's system message is being assembled, before the model sees it"
    :payload #{} :matches nil :gate? true :on-error :block :stdout :content}
   {:name "InstructionsLoaded" :when "an instruction file is folded into the run's context"
    :payload #{:path} :matches nil :gate? false :on-error :proceed}
   {:name "ConfigChange" :when "a session's configuration moved (provider, model, project)"
    :payload #{:what} :matches nil :gate? false :on-error :proceed}
   {:name "CwdChanged" :when "a session's project directory moved"
    :payload #{:project_dir :before} :matches nil :gate? false :on-error :proceed}
   {:name "SessionEnd" :when "a session is closed"
    :payload #{} :matches nil :gate? false :on-error :proceed}

   ;; ---- declared, waiting for their subsystem (see the docstring above)
   {:name "FileChanged" :when "a watched file changed"
    :payload #{:file} :matches :file :gate? false :on-error :proceed}
   {:name "Elicitation" :when "an MCP server asks the user for input"
    :payload #{:server :request} :matches nil :gate? false :on-error :proceed}
   {:name "ElicitationResult" :when "the user answered an elicitation, before it goes back"
    :payload #{:server :response} :matches nil :gate? false :on-error :proceed}
   {:name "PreCompact" :when "context compaction is about to run"
    :payload #{} :matches nil :gate? false :on-error :proceed}
   {:name "PostCompact" :when "context compaction finished"
    :payload #{} :matches nil :gate? false :on-error :proceed}
   {:name "SubagentStart" :when "a subagent starts"
    :payload #{:subagent} :matches nil :gate? false :on-error :proceed}
   {:name "SubagentStop" :when "a subagent finishes"
    :payload #{:subagent} :matches nil :gate? false :on-error :proceed}
   {:name "TeammateIdle" :when "a team member is about to go idle"
    :payload #{:teammate} :matches nil :gate? false :on-error :proceed}
   {:name "TaskCreated" :when "a task is created"
    :payload #{:task} :matches nil :gate? false :on-error :proceed}
   {:name "TaskCompleted" :when "a task completes"
    :payload #{:task} :matches nil :gate? false :on-error :proceed}
   {:name "WorktreeCreate" :when "a git worktree is created"
    :payload #{:path} :matches nil :gate? false :on-error :proceed}
   {:name "WorktreeRemove" :when "a git worktree is removed"
    :payload #{:path} :matches nil :gate? false :on-error :proceed}])

(def ^:private by-key
  "The EDN key a user writes -> its point. :pre-tool-use -> the PreToolUse row.
  Derived, not written twice: the table above is the only place a point is
  declared."
  (into {}
        (map (fn [p]
               [(keyword (str/lower-case
                          (str/replace (:name p) #"([a-z0-9])([A-Z])" "$1-$2")))
                p]))
        points))

(def point-keys
  "The keys hooks.edn may name, sorted -- what a validation failure lists."
  (vec (sort (keys by-key))))

(defn point-for
  "The point a hooks.edn KEY names, or nil. nil is the honest answer for a key
  that is not a point, and the caller turns it into a named failure listing
  `point-keys`."
  [k]
  (get by-key k))

;; ------------------------------------------------------------- declarations
;;
;; One row under a point: {:matcher .. :command .. :timeout ..} -- or :run
;; instead of :command, in the two sources that can hold a function.
;;
;; A DECLARATION SAYS WHAT IT RUNS, and it says exactly one thing: a :command
;; string, or a :run function. Which of the two is allowed is the SOURCE's
;; business, and the source is the one argument of `check-declaration` that is
;; not the declaration itself -- because 'a file cannot hold a function' is a fact
;; about files, not about the row.

(def ^:private declaration-keys
  "Everything a declaration may carry. A key outside this set fails by name: a
  stray :commnd would otherwise be dropped and the row would look like it
  declared nothing to run, which is worse than an error."
  #{:matcher :command :run :timeout})

(defn fail
  "Throw a named failure. Public because the contract is the kernel's and the
  FILE is a capability's: harness.cap.hooks validates what it reads against it."
  [msg data] (throw (ex-info msg data)))

(defn check-declaration
  "One declaration at POINT, from ORIGIN (:config for a hooks.edn row, :session
  for one a running session added), validated. Returns it unchanged. Everything
  wrong with it fails HERE, by name, with the point and the offending value in the
  message: a hook that silently does not run is indistinguishable from a hook
  that ran and decided to allow.

  ORIGIN decides one of the checks and no more than one -- a hooks.edn row may not
  carry :run, because a file cannot hold a function. That refusal is named rather
  than left to the unknown-key check: the author did not misspell a field, they
  reached for the wrong one, and 'a file cannot hold a function' is the sentence
  that tells them where to put it instead."
  [point-kw point idx decl origin]
  (let [where (str (case origin
                     :config "hooks.edn"
                     :session "a session declaration")
                   " " point-kw "[" idx "]")]
    (when-not (map? decl)
      (fail (str where " must be a map, not " (pr-str (type decl)))
            {:point point-kw :index idx :value decl}))
    (let [unknown (sort (remove declaration-keys (keys decl)))]
      (when (seq unknown)
        (fail (str where " has unknown key(s) " (pr-str (vec unknown))
                   "; a declaration takes " (pr-str (vec (sort declaration-keys)))
                   " -- one of `command` or `run` is the one that is required")
              {:point point-kw :index idx :unknown (vec unknown)})))
    ;; WHAT IT RUNS: exactly one of :command / :run. Checked as a pair rather
    ;; than field by field, because the failure is about the pair -- a row with
    ;; both says two contradictory things, and a row with neither says nothing.
    ;;
    ;; THE SOURCE'S OWN RULE COMES FIRST, because it is the more fundamental fact
    ;; about the row: a file that names :run at all has reached for the wrong
    ;; field, whatever it put in it, and 'a file cannot hold a function' is the
    ;; sentence that sends the author to the right place.
    (let [has-command? (contains? decl :command)
          has-run?     (contains? decl :run)]
      (cond
        (and (= :config origin) has-run?)
        (fail (str where " sets :run, but a hooks.edn declaration runs a command:"
                   " a file cannot hold a function. Declare it from the session"
                   " (harness.kernel.hooks/session-add!), or write it as a :command"
                   " -- and if what you want is a hook the kernel itself runs,"
                   " that is a :built-in row, not a file.")
              {:point point-kw :index idx :run (:run decl) :reason :run-in-a-file})

        ;; BOTH KEYS AT ALL, whatever they hold: the row says two contradictory
        ;; things, and picking one of them here would be the engine choosing
        ;; which of the author's two sentences to believe.
        (and has-command? has-run?)
        (fail (str where " gives both :command and :run; a declaration runs exactly"
                   " one thing -- pick the one this hook is")
              {:point point-kw :index idx :command (:command decl) :run (:run decl)})

        (and has-run? (not (ifn? (:run decl))))
        (fail (str where " :run must be callable (a fn of the payload map), got "
                   (pr-str (:run decl)))
              {:point point-kw :index idx :run (:run decl)})

        (and (not has-run?) (not (and (string? (:command decl))
                                     (not (str/blank? (:command decl))))))
        (fail (str where " needs a non-empty string :command, or a callable :run"
                   " -- a declaration says what it runs. Got " (pr-str (:command decl)))
              {:point point-kw :index idx :command (:command decl)})

        :else nil))
    (let [t (:timeout decl)]
      (when (some? t)
        (when-not (and (integer? t) (pos? t))
          (fail (str where " :timeout must be a positive whole number of milliseconds, got "
                     (pr-str t))
                {:point point-kw :index idx :timeout t}))
        ;; A :timeout bounds the WAIT for a spawned command. A :run hook has no
        ;; spawn -- it is a call in this process that returns when it returns --
        ;; so the bound would do nothing at all. Quietly accepting a field that
        ;; cannot take effect is the same failure the unknown-key check exists to
        ;; stop, so it is refused for the same reason and in the same voice.
        (when (contains? decl :run)
          (fail (str where " sets :timeout with :run, but there is no spawn to"
                     " bound: :timeout is the wait for a :command, and a :run hook"
                     " is a call in this process that returns when it returns")
                {:point point-kw :index idx :timeout t :reason :timeout-on-a-run}))))
    (when (contains? decl :matcher)
      (let [m (:matcher decl)]
        (when-not (and (string? m) (not (str/blank? m)))
          (fail (str where " :matcher must be a non-empty string, got " (pr-str m))
                {:point point-kw :index idx :matcher m}))
        ;; A matcher means "compare this against something". A point with no
        ;; match target has nothing to compare it to, so rather than inventing a
        ;; third meaning for the field -- or quietly matching everything -- the
        ;; declaration is refused and told which points do take one.
        (when-not (:matches point)
          (fail (str where " sets :matcher, but " (:name point)
                     " has no match target; only these points match something: "
                     (pr-str (vec (sort (for [[k p] by-key :when (:matches p)] k)))))
                {:point point-kw :index idx :matcher m})))
      ;; The string is a REGEX, and a broken one must fail here rather than at
      ;; trigger time: a hook that never fires because its pattern does not
      ;; compile is exactly the silent failure this validation exists to stop.
      (try (re-pattern (:matcher decl))
           (catch Exception e
             (fail (str where " :matcher is not a valid regex: " (ex-message e))
                   {:point point-kw :index idx :matcher (:matcher decl)}))))
    decl))

;; -------------------------------------------------- built-in declarations

(defonce ^:private builtins
  (atom {}))
;; point-kw -> [row ..], folded from the installed layers. Each carries :id, :name
;; and :run. Materialized, like the tool table: rebuilt from the layer stack on
;; every install and teardown.

(def ^:private builtin-keys
  "Everything a built-in row may carry. Same discipline as a declaration: a key
  the engine does not read fails by name rather than sitting there looking
  honoured."
  #{:name :run})

(defn- check-builtin!
  "One layer's built-in row, validated against the contract. Fails by name, like
  every other refusal in this namespace: a row that could not run should not be
  quietly skipped where nobody would notice."
  [point-kw name decl]
  (when-not (point-for point-kw)
    (fail (str (pr-str point-kw) " is not a hook point; the points are "
               (pr-str point-keys))
          {:point point-kw :reason :unknown-point}))
  (when-not (and (string? name) (not (str/blank? name)))
    (fail (str "a built-in hook needs a non-empty string name, got " (pr-str name))
          {:point point-kw :name name}))
  (when-not (map? decl)
    (fail (str "builtin:" name " must be a map, not " (pr-str (type decl)))
          {:point point-kw :name name}))
  (let [unknown (sort (remove builtin-keys (keys decl)))]
    (when (seq unknown)
      (fail (str "builtin:" name " has unknown key(s) " (pr-str (vec unknown))
                 "; a built-in row takes " (pr-str (vec (sort builtin-keys))))
            {:point point-kw :name name :unknown (vec unknown)})))
  (ifn? (:run decl)))

(defn- role-row
  "NAME's row at POINT as the table holds it, :id and :name filled in. The id is
  `builtin:<name>`: a built-in has a name because the kernel wrote it and can say
  what it is, so it reads as itself in a table, in a disable call and in an audit
  line."
  [point-kw name decl]
  (when-not (check-builtin! point-kw name decl)
    (fail (str "builtin:" name " needs a callable :run -- a built-in hook is a"
               " function in this process, and that is the only thing it is"
               " allowed to be. Got " (pr-str (:run decl)))
          {:point point-kw :name name :run (:run decl)}))
  (assoc decl :id (str "builtin:" name) :name name))

(defn- merge-rows
  "ROWS folded with NEW-ROWS: a later row with the same :id REPLACES the earlier one
  IN PLACE.

  Replacing in place is what makes re-installing a no-op rather than a second copy --
  an id is a name and a name means one row -- and keeping the earlier position is
  what holds the rows in the order they first appeared, which is the order they
  append their text in."
  [rows new-rows]
  (reduce (fn [acc row]
            (let [at (first (keep-indexed (fn [i r] (when (= (:id r) (:id row)) i)) acc))]
              (if (some? at) (assoc acc at row) (conj acc row))))
          (vec rows) new-rows))

(defn- fold-layers
  [layers]
  (reduce (fn [acc layer]
            (let [acc (reduce (fn [a [point-kw decls]]
                                (update a point-kw (fnil merge-rows [])
                                        (mapv (fn [[name decl]] (role-row point-kw name decl))
                                              decls)))
                              acc
                              (into {} (:builtins layer)))]
              (update acc ::declarations #(or (:declarations layer) %))))
          {::declarations nil}
          layers))

(defonce ^:private layers
  (atom []))

(defonce ^:private installed-declarations
  (atom nil))
;; The DECLARATIONS function some layer contributed, or nil: (fn [thread-id] ->
;; {point-kw [declaration ..]}) answering what the FILES say. Nil means no files,
;; which is the honest state of a process that never installed one.

(defn- recompute!
  []
  (let [folded (fold-layers @layers)]
    (reset! installed-declarations (::declarations folded))
    (reset! builtins (dissoc folded ::declarations))))

(defn install!
  "Install CONTRIBUTION and answer the TEARDOWN that withdraws it. The same door,
  and the same four rules, as harness.kernel.tools/install!:

    {:name         \"system-prompt rows\"   ; what a message about this layer calls it
     :builtins     {:system-prompt [[\"tools\" {:run f}] ..]}  ; rows the kernel owns
     :declarations (fn [thread-id] {point-kw [declaration ..]})} ; what the FILES say

  LAST WINS, a teardown withdraws ITS OWN layer and folds the rest again, and a row
  whose id is already present is replaced IN PLACE. With nothing installed there are
  no built-in rows and no files, which is a process whose hook table holds only what
  a session added itself -- the state every test that asks about declared hooks
  starts from."
  [{:keys [name builtins declarations] :as _contribution}]
  (let [id    (str (java.util.UUID/randomUUID))
        layer {:id id :name (or name id)
               :builtins (or builtins {}) :declarations declarations}]
    (swap! layers conj layer)
    (recompute!)
    (fn teardown []
      (swap! layers (fn [ls] (filterv #(not= (:id %) id) ls)))
      (recompute!)
      nil)))

;; ------------------------------------------------- session hooks (the eval face)
;;
;; A running session may grow hooks of its own, and switch any declaration off --
;; the ones it added, the ones declared on disk, AND the kernel's own. That is
;; what `eval` is FOR now: not a way to read the harness, but a way to give this
;; session behaviour it did not start with.
;;
;; TWO ORTHOGONAL AXES, the same pair the tool table uses (harness.kernel.tools):
;;
;;   presence      session-add! / session-remove! -- definitions this session
;;                 contributed. Removal undoes an add and NOTHING else: a
;;                 disk-declared hook cannot be removed, only switched off.
;;   availability  session-disable! / session-enable! -- switched off in this
;;                 session. The declaration STAYS in the table (so the session can
;;                 read it, and switch it back on) and simply never fires.
;;
;; WHY DISABLE RATHER THAN REMOVE for disk declarations: hiding a hook would make
;; "there is no such hook" and "this hook is off" the same observation, and the
;; first is a lie -- the declaration is right there in a file. Off is honest.
;;
;; EVERYTHING HERE IS PER-THREAD AND PROCESS-LOCAL. Another session is untouched;
;; a restart forgets all of it, and the files on disk are what come back. Nothing
;; is written: a session's hooks are not a truth source, they are this run's
;; behaviour.

(defonce ^:private overlays
  (atom {}))
;; thread-id -> {:added {id declaration} :disabled #{id}}

(defonce ^:private counters
  (atom {}))
;; thread-id -> how many hooks this session has added, so an id is short and
;; legible in a table (pre-tool-use@2) rather than a UUID the reader has to match
;; by eye. Per-thread for the same reason everything else is.

(declare effective-hooks)

(defn- disabled? [thread-id id]
  (contains? (get-in @overlays [thread-id :disabled] #{}) id))

(defn- declaration-id
  "The id an on-disk declaration answers to: its point and its position, e.g.
  `pre-tool-use#0`. Positional because that is what a file HAS -- there is no
  name in a declaration -- and it is stable as long as the file is, which is the
  same guarantee the file gives."
  [point-kw idx]
  (str (name point-kw) "#" idx))

(defn session-add!
  "Add DECL to POINT in THREAD-ID's session only, and return the id it answers
  to. DECL is validated exactly as a hooks.edn declaration is -- a session hook
  that cannot run is refused where it was written, not skipped at trigger time --
  and a point that does not exist is refused by name. A session is the one source
  that may name :run as well as :command.

  Session declarations are APPENDED to whatever the kernel registered and the
  files declared: a hook a session grows cannot silently replace either, and the
  id prefix (@ versus # versus builtin:) makes the three readable apart in one
  table.

  An id is per session and stays put, so a declaration added at the start of a
  session can be switched off later by the same id."
  [thread-id point-kw decl]
  (let [point (point-for point-kw)]
    (when-not point
      (fail (str (pr-str point-kw) " is not a hook point; the points are "
                 (pr-str point-keys))
            {:point point-kw :reason :unknown-point}))
    (check-declaration point-kw point 0 decl :session)
    (let [n  (get (swap! counters update thread-id (fnil inc 0)) thread-id)
          id (str (name point-kw) "@" n)]
      (swap! overlays assoc-in [thread-id :added id] (assoc decl :point point-kw))
      id)))

(defn session-remove!
  "Retract the session hook ID from THREAD-ID -- the presence half only, and only
  for a hook THIS session added. An id naming an on-disk declaration is a no-op:
  a disk hook cannot be removed, only switched off (session-disable!).

  Removing also drops the id's disabled mark, so a re-added hook does not inherit
  a stale off state."
  [thread-id id]
  (swap! overlays
         (fn [ov]
           (if (contains? (get-in ov [thread-id :added]) id)
             (-> ov
                 (update-in [thread-id :added] dissoc id)
                 (update-in [thread-id :disabled] (fnil disj #{}) id))
             ov))))

(defn session-disable!
  "Switch ID off for THREAD-ID's session only. The declaration stays in the
  table and simply never fires -- no spawn, no audit line, no hold on the run.

  The id may name a session hook, an on-disk one, or one of the kernel's own; a
  hook this session cannot see is a no-op, because switching something off must
  never invent it. A built-in is switchable like any other row: what the kernel
  registers is a HOOK, and switching a hook off is this session's business.

  This is a POLICY switch, like the tool table's: it stops a hook from running,
  which is not the same as forbidding the behaviour a hook was there to allow."
  [thread-id id]
  (when (some? (get (effective-hooks thread-id) id))
    (swap! overlays update-in [thread-id :disabled] (fnil conj #{}) id)))

(defn session-enable!
  "Undo session-disable! for ID in THREAD-ID. An id that was never disabled is a
  no-op."
  [thread-id id]
  (swap! overlays update-in [thread-id :disabled] (fnil disj #{}) id))

(defn session-hook-disabled?
  "Is ID switched off in THREAD-ID's session?"
  [thread-id id]
  (disabled? thread-id id))

;; ------------------------------------------------------------------ the read

(defn config
  "The hook declarations the FILES say for THREAD-ID, or {} when no layer installed
  a reader.

  IT IS A RELAY NOW. Which files, in what order and how they merge is a capability
  (harness.cap.hooks reads the configuration home's hooks.edn under the bound
  project's .harness/hooks.edn), and it arrives through `install!` -- so this
  namespace no longer knows that projects or a configuration home exist, which is
  what let it stop requiring harness.cap.project.

  The reader is asked EVERY CALL and reads both files every time (the config.edn
  discipline), so an edit takes effect on the next trigger rather than the next
  restart. Nothing installed means {} -- no files -- which is not an error."
  ([] (config nil))
  ([thread-id]
   (if-let [read-declarations @installed-declarations]
     (read-declarations thread-id)
     {})))

(defn effective-hooks
  "ID -> the declaration in force for THREAD-ID, over the WHOLE table: every
  point, with the kernel's own rows, the on-disk declarations and the session's
  own folded together.

    {\"builtin:env\"     {:point :system-prompt :run <fn> :source :built-in}
     \"stop#0\"         {:point :stop :command \"notify.sh\" :source :config}
     \"pre-tool-use@1\"  {:point :pre-tool-use :command \"gate.sh\" :source :session}
     ...}

  Every declaration carries :id (what a disable names), :point (which trigger
  runs it), :source (:built-in, :config or :session) and -- for the kernel's own
  rows -- :name, which is the half of a built-in id worth reading. That is enough
  for a session to read its own table and act on it, which is the whole point of
  exposing it.

  A DISABLED declaration is still in here, with :disabled? true. Availability is
  a separate question from presence, exactly as in the tool table: the caller
  that decides whether to run something asks `runnable`, not this.

  THIS IS THE SEAM the engine reads -- dispatch asks here, not of the files -- so
  a session's edit reaches its next trigger without anything being persisted."
  [thread-id]
  (into {}
        (concat
         (for [[point-kw decls] @builtins
               decl decls]
           [(:id decl) (assoc decl :point point-kw :source :built-in
                              :disabled? (disabled? thread-id (:id decl)))])
         (for [[point-kw decls] (config thread-id)
               [idx decl] (map-indexed vector decls)
               :let [id (declaration-id point-kw idx)]]
           [id (assoc decl :id id :point point-kw :source :config
                      :disabled? (disabled? thread-id id))])
         (for [[id decl] (get-in @overlays [thread-id :added])]
           [id (assoc decl :id id :source :session
                      :disabled? (disabled? thread-id id))]))))

(def ^:private source-tier
  "Where a SOURCE sits in the order a point's declarations run: the kernel's own
  first, then the files, then what this session added.

  Keyed on :source rather than on the id's punctuation, because the tier is a fact
  about where a row came from and the id's spelling is a separate decision -- and
  because a new source should be a new line here, not a new branch in a regex."
  {:built-in 0 :config 1 :session 2})

(defn- builtin-position
  "Which built-in row ID is, counting across the whole registry. Derived from the
  registry rather than carried on the row, so a built-in's public shape stays the
  shape every other declaration has. Sorted by point so the answer does not
  depend on hash-map iteration order."
  [id]
  (long (or (first (keep-indexed (fn [i d] (when (= id (:id d)) i))
                                 (mapcat val (sort-by (comp name key) @builtins))))
            0)))

(defn- declaration-order
  "The order two declarations of one point were written in: the kernel's own (in
  registration order), then the file's (in the file's own order), then what the
  session added (in the order it added them). Derived from the row, because a map
  has no order worth relying on -- and the order is load-bearing: the first block
  wins, so the reader of a model's refusal can trace it to a specific line."
  [d]
  (if (= :built-in (:source d))
    [0 (builtin-position (:id d))]
    [(source-tier (:source d) 3)
     (Long/parseLong (second (re-find #"(\d+)$" (:id d))))]))

(defn declarations-at
  "The declarations of POINT in force for THREAD-ID that this trigger should
  consider: the kernel's own rows, the session's, and the on-disk ones together,
  in the order they were written (built-in, then the file's, then the session's).

  Disabled ones are LEFT OUT here rather than filtered by the caller: a switched
  off hook is one that does not fire, and that is a fact about the table, not a
  decision dispatch should have to remember to make. Read `effective-hooks` to
  see them."
  [thread-id point-kw]
  (->> (vals (effective-hooks thread-id))
       (filter #(and (= point-kw (:point %)) (not (:disabled? %))))
       (sort-by declaration-order)
       vec))

