(ns harness.hooks
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
     :on-error :block}         ; what a TIMEOUT or a failed spawn means here:
                               ;   :block for a gate, :proceed for an observer

  ADDING A POINT IS ADDING A ROW. The engine's dispatch reads this table; it has
  no per-point code, which is the property the whole hook design rests on: a new
  hook point must not be a new special case in the execution path.

  WHAT THIS NAMESPACE OWNS: the point table, the two-level assembly (the
  configuration home's hooks.edn under the bound project's), validation of a
  declaration, and the one read entry -- `effective-hooks` -- that answers which
  declarations are in force for a thread. It does NOT run anything: spawning,
  exit codes, timeouts and the audit line are `harness.hooks.dispatch`'s, and the
  session-level overlay that lets a running session add or switch off its own
  declarations is the session overlay below.

  TWO LAYERS, ONE READ. `config` answers what the FILES say and re-reads them
  every call (the config.edn discipline), so editing hooks.edn takes effect on the
  next trigger, not the next restart. `effective-hooks` folds the session's own
  layer over that, which is what a running session may add to, remove from, and
  switch off -- per-thread, in-process, gone on restart. Dispatch reads the
  folded one and nothing else."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.home :as home]
            [harness.project :as project]))

;; ------------------------------------------------------------- the point table
;;
;; Spelled the way the payload spells them (CodeBuddy's CamelCase), because that
;; string is what a hook command receives on stdin and what lands in the
;; hook/<point> audit line. The EDN key a user writes in hooks.edn is the
;; kebab-case keyword beside it -- :pre-tool-use -- and `point-for` is the one
;; place the two spellings are tied together.

(def points
  "Every hook point this harness knows, as data. 26 of them.

  The P2 points (the first fourteen) have a trigger source in this codebase
  today; the P3 points (the rest) are declared and never fire until the
  subsystem they belong to is built -- file watching, MCP elicitation, context
  compaction, subagents, tasks, git worktrees. A point with no trigger source
  simply never dispatches: that is the design, not an omission, and it is why
  a P3 point costs one row rather than an interface."
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
;; One row under a point: {:matcher .. :command .. :timeout ..}. :command is the
;; only required field -- a declaration with nothing to run is not a declaration.

(def ^:private declaration-keys
  "Everything a declaration may carry. A key outside this set fails by name: a
  stray :commnd would otherwise be dropped and the row would look like it
  declared nothing to run, which is worse than an error."
  #{:matcher :command :timeout})

(defn- fail [msg data] (throw (ex-info msg data)))

(defn- check-declaration
  "One declaration at POINT, validated. Returns it unchanged. Everything wrong
  with it fails HERE, by name, with the point and the offending value in the
  message: a hook that silently does not run is indistinguishable from a hook
  that ran and decided to allow."
  [point-kw point idx decl]
  (let [where (str "hooks.edn " point-kw "[" idx "]")]
    (when-not (map? decl)
      (fail (str where " must be a map, not " (pr-str (type decl)))
            {:point point-kw :index idx :value decl}))
    (let [unknown (sort (remove declaration-keys (keys decl)))]
      (when (seq unknown)
        (fail (str where " has unknown key(s) " (pr-str (vec unknown))
                   "; a declaration takes " (pr-str (vec (sort declaration-keys)))
                   " -- `command` is the one that is required")
              {:point point-kw :index idx :unknown (vec unknown)})))
    (let [cmd (:command decl)]
      (when-not (and (string? cmd) (not (str/blank? cmd)))
        (fail (str where " needs a non-empty string :command, got " (pr-str cmd))
              {:point point-kw :index idx :command cmd})))
    (let [t (:timeout decl)]
      (when (some? t)
        (when-not (and (integer? t) (pos? t))
          (fail (str where " :timeout must be a positive whole number of milliseconds, got "
                     (pr-str t))
                {:point point-kw :index idx :timeout t}))))
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

(defn- read-hooks-edn
  "One hooks.edn FILE, or {} when it does not exist -- a missing file is the
  empty configuration, never an error (a fresh install has no hooks and that is
  the normal state). A file that EXISTS but is broken -- not valid EDN, not a
  map, an unknown point key, a bad declaration -- is a hard, NAMED failure with
  the absolute path: an ignored hooks.edn is indistinguishable from one that
  says nothing, and the difference is the whole meaning of the file."
  [file]
  (let [f (io/file file)]
    (if-not (.exists f)
      {}
      (let [abs (.getAbsolutePath f)
            raw (try (edn/read-string (slurp f :encoding "UTF-8"))
                     (catch Exception e
                       (fail (str abs " is not valid EDN (" (ex-message e) ")")
                             {:path abs :reason :invalid-edn})))]
        (when-not (map? raw)
          (fail (str abs " must be an EDN map of hook point -> [declarations]")
                {:path abs :reason :not-a-map}))
        (into {}
              (map (fn [[k decls]]
                     (let [point (point-for k)]
                       (when-not point
                         (fail (str abs " names an unknown hook point " (pr-str k)
                                    "; the points are " (pr-str point-keys))
                               {:path abs :point k :reason :unknown-point}))
                       (when-not (sequential? decls)
                         (fail (str abs " point " (pr-str k)
                                    " must be a vector of declarations, not "
                                    (pr-str (type decls)))
                               {:path abs :point k :reason :not-a-vector}))
                       [k (vec (map-indexed #(check-declaration k point %1 %2) decls))])))
              raw)))))

;; ------------------------------------------------- session hooks (the eval face)
;;
;; A running session may grow hooks of its own, and switch any declaration off --
;; the ones it added AND the ones declared on disk. That is what `eval` is FOR
;; now: not a way to read the harness, but a way to give this session behaviour
;; it did not start with.
;;
;; TWO ORTHOGONAL AXES, the same pair the tool table uses (harness.tools):
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
  and a point that does not exist is refused by name.

  Session declarations are APPENDED to whatever the files declared: a hook a
  session grows cannot silently replace the user's, and the id prefix (@ versus
  #) makes the two readable apart in one table.

  An id is per session and stays put, so a declaration added at the start of a
  session can be switched off later by the same id."
  [thread-id point-kw decl]
  (let [point (point-for point-kw)]
    (when-not point
      (fail (str (pr-str point-kw) " is not a hook point; the points are "
                 (pr-str point-keys))
            {:point point-kw :reason :unknown-point}))
    (check-declaration point-kw point 0 decl)
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

  The id may name a session hook or an on-disk one; a hook this session cannot
  see is a no-op, because switching something off must never invent it.

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
  "The hook configuration for THREAD-ID, ON DISK: the configuration home's
  hooks.edn (the USER level) overlaid by the bound project's .harness/hooks.edn
  (the PROJECT level).

  A SHALLOW merge of top-level keys, project wins -- naming :pre-tool-use in a
  project REPLACES the user's :pre-tool-use declarations rather than appending
  to them. Same rule as harness.project/harness-config, and for the same reason:
  'what will actually run' should be readable in one file, not inferred from how
  two files nest.

  An unbound session sees the user level alone. Every call re-reads both files
  (the config.edn discipline), so an edit takes effect at the next trigger.

  This is the FILE half only; anything that wants to know what will actually run
  wants `effective-hooks` below, which folds the session's own overlay in."
  ([] (config nil))
  ([thread-id]
   (merge (read-hooks-edn (home/hooks-file))
          (when-let [dir (project/binding-for thread-id)]
            (read-hooks-edn (io/file dir ".harness" "hooks.edn"))))))

(defn effective-hooks
  "ID -> the declaration in force for THREAD-ID, over the WHOLE table: every
  point, with the on-disk declarations and the session's own folded together.

    {\"stop#0\"        {:point :stop :command \"notify.sh\" :source :config}
     \"pre-tool-use@1\" {:point :pre-tool-use :command \"gate.sh\" :source :session}
     ...}

  Every declaration carries :id (what a disable names), :point (which trigger
  runs it) and :source (:config or :session) -- enough for a session to read its
  own table and act on it, which is the whole point of exposing it.

  A DISABLED declaration is still in here, with :disabled? true. Availability is
  a separate question from presence, exactly as in the tool table: the caller
  that decides whether to run something asks `runnable`, not this.

  THIS IS THE SEAM the engine reads -- dispatch asks here, not of the files -- so
  a session's edit reaches its next trigger without anything being persisted."
  [thread-id]
  (into {}
        (concat
         (for [[point-kw decls] (config thread-id)
               [idx decl] (map-indexed vector decls)
               :let [id (declaration-id point-kw idx)]]
           [id (assoc decl :id id :point point-kw :source :config
                      :disabled? (disabled? thread-id id))])
         (for [[id decl] (get-in @overlays [thread-id :added])]
           [id (assoc decl :id id :source :session
                      :disabled? (disabled? thread-id id))]))))

(defn- declaration-order
  "The order two declarations of one point were written in: the file's first,
  in the file's own order, then what the session added. Derived from the id,
  because a map has no order worth relying on -- and the order is load-bearing:
  the first block wins, so the reader of a model's refusal can trace it to a
  specific line."
  [d]
  (let [[_ sep n] (re-matches #".*?([#@])(\d+)$" (:id d))]
    [(if (= "#" sep) 0 1) (Long/parseLong n)]))

(defn declarations-at
  "The declarations of POINT in force for THREAD-ID that this trigger should
  consider: session hooks and on-disk ones together, in the order they were
  written (the file's, then the session's).

  Disabled ones are LEFT OUT here rather than filtered by the caller: a switched
  off hook is one that does not fire, and that is a fact about the table, not a
  decision dispatch should have to remember to make. Read `effective-hooks` to
  see them."
  [thread-id point-kw]
  (->> (vals (effective-hooks thread-id))
       (filter #(and (= point-kw (:point %)) (not (:disabled? %))))
       (sort-by declaration-order)
       vec))

