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
  declarations is a later layer on top of `effective-hooks`.

  Nothing here is a truth source beyond what is on disk: every read re-reads the
  files (the config.edn discipline), so editing hooks.edn takes effect on the
  next trigger, not the next restart."
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

;; ------------------------------------------------------------------ the read

(defn config
  "The hook configuration for THREAD-ID, merged from two levels: the
  configuration home's hooks.edn (the USER level), overlaid by the bound
  project's .harness/hooks.edn (the PROJECT level).

  A SHALLOW merge of top-level keys, project wins -- naming :pre-tool-use in a
  project REPLACES the user's :pre-tool-use declarations rather than appending
  to them. Same rule as harness.project/harness-config, and for the same reason:
  'what will actually run' should be readable in one file, not inferred from how
  two files nest.

  An unbound session sees the user level alone. Every call re-reads both files
  (the config.edn discipline), so an edit takes effect at the next trigger."
  ([] (config nil))
  ([thread-id]
   (merge (read-hooks-edn (home/hooks-file))
          (when-let [dir (project/binding-for thread-id)]
            (read-hooks-edn (io/file dir ".harness" "hooks.edn"))))))

(defn effective-hooks
  "Point key -> the declarations in force for THREAD-ID, on-disk only: the
  two-level assembly above, for every point, including the ones with no
  declarations (an empty vector is a fact worth being able to read: 'this point
  is wired and nothing is listening').

  THIS IS THE SEAM the rest of the engine reads. Dispatch calls it to ask what to
  run; the session-level overlay that lets a running session add or switch off
  its own declarations overlays THIS function's answer rather than reaching into
  the files, so on-disk configuration stays one thing and the per-session
  additions stay another."
  [thread-id]
  (let [cfg (config thread-id)]
    (into {} (map (fn [k] [k (vec (get cfg k))])) point-keys)))
