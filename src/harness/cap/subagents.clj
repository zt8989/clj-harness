(ns harness.cap.subagents
  "Subagents: what they ARE, which names each one serves, and the delegation that
  hands one a task. Two are built in -- `general` (everything the main agent has,
  except `eval`) and `explore` (only the tools that cannot change anything) -- and
  a home may add its own in config.edn.

  THE WHOLE FEATURE IS ONE TOOL CALL FROM THE OUTSIDE. The main agent's table gains
  `subagent`, whose arguments are a subagent's name and a task. By default the
  subagent runs its own conversation and its final message becomes that call's result;
  with `run_in_background` the call returns at once and that message becomes the record
  of a BACKGROUND JOB (`harness.cap.jobs`), whose ending is announced to the model the
  way a command's is. No new wire
  frame, no new endpoint, no second protocol: a client that can draw a tool call
  already draws this one.

  ---- the two things a person can see, and where each one comes from

  A PANEL asks two questions and this namespace answers both from their owners:
  what are the subagents (the definitions in force -- `definitions`, which reads
  config.edn) and what has been delegated (the RECORD of it, which is the store's
  -- `runs`, which asks harness.cap.project). Neither is derived from the other:
  a definition that has never run is a row with no record, and a record whose
  definition was since deleted is a row with no definition, and both are ordinary.

  THE SETTINGS FORM WRITES, AND ONLY EVER THE `:subagents` BLOCK. `put-definition!`
  and `remove-definition!` rewrite this home's config.edn with one generation of
  backup (the writing is harness.cap.providers', see `change-session!`), preserving every
  other key
  value, and validate the whole block BEFORE the file is opened -- so a refused
  entry leaves the home exactly as it was, which is the promise the form shows the
  server's own sentence for. THE NEXT DELEGATION IS WHAT PICKS IT UP: a range is
  computed per delegation (see `table-for`), so there is no restart and no cache to
  invalidate.

  ---- what a subagent's tools are, and when that is decided

  A subagent's tool range is DERIVED, at the moment of delegation, from the main
  session's own table:

    :all        what the main agent serves now, minus this subagent's :exclude.
    :read-only  only the names that can PROVE they change nothing. A tool proves it
                by declaring `:read-only true` on itself AND carrying the built-in
                source; anything whose provenance this namespace cannot check --
                a tool an external server contributed, a tool this session
                registered itself -- is left out. Fewer tools is the failure to
                prefer here: an exploring subagent that cannot write is doing its
                job, and one that can is a surprise nobody asked for.

  DERIVED NOW, NOT DECLARED ONCE. The main session's table moves under it --
  the editing mode subtracts a family, a session adds a tool, an MCP server comes
  up or goes down -- so a range computed at setup would be a statement about a
  table that no longer exists. The answer to 'which names does this session serve'
  is the seam's (`harness.kernel.tools/served?`), asked per name, so a subagent's
  range is the SAME KIND of statement as the main session's table and narrows it by
  the ordinary rules.

  TWO NAMES ARE NEVER IN ANY RANGE, AND THAT IS A POST-CONDITION RATHER THAN A
  DEFAULT: `eval` (the harness's own process, which no delegation should hand out)
  and `agent` itself (a subagent that could delegate is a tree nobody bounded).
  No configuration reaches them, and a configuration that NAMES one is refused
  rather than quietly obeyed -- see check-entry!, which says why.

  ---- how the range is enforced

  Through the seam's narrowing door, beside the editing mode's subtraction. The
  seam asks every installed policy in turn; the first that says 'not served' owns
  the refusal. So a name outside a subagent's range is ABSENT from the tool list
  the model is handed (specs filters on the same question) and its call is refused
  BY NAME with a sentence saying whose range it is not in -- the same treatment the
  other editing mode's tools get, and for the same reason: a model that cannot see
  a tool reads its absence as 'this does not exist' and goes looking for a way
  around the restriction instead of working inside it.

  ---- who a subagent thread is

  A live table, process-local, keyed by the subagent's own thread id: which
  definition it is running, whose session delegated it, and the range frozen at
  that moment. It is the SAME fact the narrowing answers from, so there is one
  answer to 'what may this thread run' rather than a copy per reader. It lives no
  longer than the delegation does -- 'this subagent is running' is a fact about
  this process, exactly as a parked approval is, while the RECORD of a delegation
  (which sessions exist, who they belong to) is the store's business.

  A SUBAGENT CANNOT WAIT FOR A HUMAN. Its run has nobody watching it: a call that
  parks would leave a card no one is looking at, and a subagent stopped forever.
  So a thread in this table is answered by the seam's unattended policy with the
  fact itself -- 'this needs an approval and a subagent cannot wait for one' --
  and carries on with the tools it has. That is the honest end of the story rather
  than a promise of something this ticket does not build (a park that bubbles to
  the delegating session is a later one)."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [harness.cap.jobs :as jobs]
            [harness.cap.project :as project]
            ;; The one write path (see `change-subagents!`): the file's shape check, the
            ;; atomic write and the backup all live there rather than here.
            [harness.cap.providers :as providers]
            [harness.infra.home :as home]
            [harness.kernel.hooks :as hooks]
            [harness.kernel.hooks.dispatch :as hook]
            [harness.kernel.tools :as tools]))

;; ---------------------------------------------------------------- the definitions

(def delegation-tool
  "The name of the tool a main agent delegates with -- and one of the two names no
  subagent ever serves."
  "subagent")

(def forbidden
  "The two names no range contains, whatever a configuration says. See this
  namespace's docstring: a post-condition of the derivation, not a default of an
  exclusion list."
  #{"eval" delegation-tool})

(def baselines
  "The two ways a range can be stated. `:all` is 'what the main agent serves'; the
  `:exclude` list takes names out of it. `:read-only` starts from the names that
  can prove they change nothing and takes names out of THAT."
  #{:all :read-only})

(def entry-keys
  "The keys a subagent definition takes. A CLOSED SET, and check-entry! refuses an
  unknown one by name: the shape of a range is baseline-plus-exclusions, and a key
  that looks like a way to put a tool back (`:allow`, `:tools`) is exactly the
  request the two forbidden names exist to refuse."
  #{:description :baseline :exclude})

(def built-ins
  "The two subagents every home has, in the order they are offered. They are
  ordinary definitions -- a config.edn entry with the same name REPLACES one
  whole -- and the only thing they get from being here is that they cannot be
  deleted, because there would then be no names to delegate to in a fresh home."
  [{:name        "general"
    :description (str "A second pair of hands with everything this session has, except"
                      " `eval` and the ability to delegate again. Use it for work that"
                      " is long, self-contained and would otherwise fill this"
                      " conversation with detail you do not need to keep.")
    :baseline    :all
    :exclude     []}
   {:name        "explore"
    :description (str "Read-only: it finds things and reads them and cannot change"
                      " anything. Use it for questions whose answer is a location or a"
                      " quotation -- where something is implemented, what a file says --"
                      " rather than work that edits.")
    :baseline    :read-only
    :exclude     []}])

(defn- built-in-definitions
  "The built-ins as definitions, each with every key present. Spelled out here
  rather than derived, so that `definitions` (below) can build on it without
  re-deciding what a complete definition looks like."
  []
  (mapv (fn [d] (merge {:baseline :all :exclude [] :description ""} d)) built-ins))

(defn- known-tools
  "Every tool name this process's base table knows -- the vocabulary a range's
  exclusions are checked against. Read from the seam rather than written down: a
  tool that was removed must stop being a name an entry may exclude, and a tool
  that was added must become one."
  []
  (set (keys (tools/effective-tools nil))))

(defn check-entry!
  "NAME -> ENTRY as a full definition, or a NAMED failure. The one place a
  definition is judged, so the settings form, a hand-edited config.edn and this
  namespace's own reader cannot disagree about what a legal one is.

  Refused by name, each for its own reason:

    - a blank name, or an entry that is not a map;
    - a key the shape does not have. This is the only thing that could look like a
      way to put a tool BACK into a range, and there is none: a range is a baseline
      and a list of exclusions, which is what makes `eval` unreachable rather than
      merely unreached;
    - a baseline that is neither :all nor :read-only;
    - an exclusion naming a tool that does not exist, which is a request that would
      never take effect and would read as though it had;
    - an exclusion naming `eval` or the delegation tool. Those are never in ANY
      range, so putting one here asks for something the entry cannot be honoured
      for -- and a request that cannot be honoured is refused rather than dropped.

  THE FIRST PARAMETER IS NOT CALLED `name`, and that is load-bearing rather than
  taste: `clojure.core/name` is what sorts the two baselines into a sentence below,
  and a parameter that shadowed it would turn 'the baseline must be :all or
  :read-only' into a ClassCastException about a String not being a function -- a
  message about the wrong thing entirely, at the moment a person is trying to find
  out what they typed wrong."
  [the-name entry]
  (let [tool-name (some-> the-name str str/trim)]
    (when (str/blank? (str tool-name))
      (throw (ex-info "a subagent needs a name; it is how the main agent asks for it."
                      {:reason :blank-name :field :name})))
    (when-not (map? entry)
      (throw (ex-info (str "the definition of " (pr-str tool-name) " must be an EDN map,"
                           " but it says " (pr-str entry))
                      {:reason :not-a-map :field :name :name tool-name})))
    (when-let [unknown (first (sort (remove entry-keys (keys entry))))]
      (throw (ex-info (str "a subagent definition does not understand " (pr-str unknown)
                           "; it takes " (str/join ", " (map pr-str (sort entry-keys)))
                           ". A range is a baseline plus exclusions, and there is no key"
                           " that puts a tool back: " (str/join " and " (sort forbidden))
                           " are never served to any subagent.")
                      {:reason :unknown-key :field (name unknown) :name tool-name})))
    (let [{:keys [baseline exclude description]} entry]
      (when-not (contains? baselines baseline)
        (throw (ex-info (str "the baseline of " (pr-str tool-name) " must be "
                             (str/join " or " (map pr-str (sort-by name baselines)))
                             ", but it says " (pr-str baseline))
                        {:reason :bad-baseline :field :baseline :name tool-name})))
      (when (and (some? exclude) (not (sequential? exclude)))
        (throw (ex-info (str "the exclusions of " (pr-str tool-name)
                             " must be an array of tool names, but they are "
                             (pr-str exclude))
                        {:reason :bad-exclude :field :exclude :name tool-name})))
      (let [names (mapv str exclude)]
        (doseq [n names]
          (when (contains? forbidden n)
            (throw (ex-info (str (pr-str n) " is never served to any subagent, so it cannot"
                                 " be excluded by " (pr-str tool-name) ": the entry would"
                                 " read as though it were otherwise available. Nothing"
                                 " reaches it, which is the point of it being forbidden.")
                            {:reason :forbidden-tool :field :exclude :name tool-name
                             :tool n})))
          (when-not (contains? (known-tools) n)
            (throw (ex-info (str (pr-str n) " is not a tool this harness knows, so excluding"
                                 " it from " (pr-str tool-name) " would change nothing."
                                 " The names are: " (str/join ", " (sort (known-tools))))
                            {:reason :unknown-tool :field :exclude :name tool-name
                             :tool n}))))
        (when (and (some? description) (not (string? description)))
          (throw (ex-info (str "the description of " (pr-str tool-name) " must be a string,"
                               " but it is " (pr-str description))
                          {:reason :bad-description :field :description :name tool-name})))
        {:name        tool-name
         :description (or description "")
         :baseline    baseline
         :exclude     names}))))

(defn- overlay
  "ONE definition laid over a list BY NAME: an entry with a name already taken
  replaces it IN PLACE, which is what keeps the two built-ins at the top of the
  list where a reader expects them rather than at the bottom where the file
  happened to mention them. A name nobody has taken is appended."
  [subagents d]
  (if (some #(= (:name %) (:name d)) subagents)
    (mapv (fn [x] (if (= (:name x) (:name d)) d x)) subagents)
    (conj subagents d)))

(defn- user-block
  "The :subagents block this home's config.edn declares, and the path it came from.
  ONE HOME, ONE STATEMENT: what the settings form writes and what the sidebar reads have
  to be the same statement, which is exactly why there is nowhere else for it to come
  from (there used to be a project level that could shadow it -- .scratch/config-merge)."
  []
  (let [session (project/harness-config nil)]
    {:block (:subagents session) :path (project/harness-config-path)}))

(defn definitions
  "The subagents in force, in order, plus what went wrong reading them -- or nil.

    {:subagents [definition ..] :problem <string|nil> :path <the file|nil>}

  TOLERANT ON PURPOSE, and the tolerance is the difference between a configuration
  MISTAKE and a broken harness. Reading is asked on the way to EVERY request (the
  delegating tool's face lists the names), so a reader that threw on a bad entry
  would turn one typo in an optional block into a harness that cannot talk to a
  model at all. What it does instead: the entries it can honour are honoured, the
  built-ins stay, and the FIRST thing it could not honour is reported in :problem
  for whoever asks a question that depends on the answer -- the delegating tool
  refuses rather than run a range somebody meant to narrow and could not, and the
  description says so before the model tries.

  A file that does not exist is the empty configuration, the same as everywhere
  else; a file that exists and cannot be read at all is reported as a problem, not
  thrown."
  ([] (definitions nil))
  ([_thread-id]
   (try
     (let [{:keys [block path]} (user-block)]
       (cond
         (nil? block)
         {:subagents (built-in-definitions) :problem nil :path path}

         (not (map? block))
         {:subagents (built-in-definitions) :path path
          :problem (str "config.edn's :session :subagents must be a map of name -> definition,"
                        " but the file says " (pr-str block))}

         :else
         (reduce (fn [acc [n entry]]
                   (try (update acc :subagents overlay (check-entry! n entry))
                        (catch Exception e
                          (if (:problem acc)
                            acc
                            (assoc acc :problem (ex-message e))))))
                 {:subagents (built-in-definitions) :problem nil :path path}
                 block)))
     (catch Throwable t
       {:subagents (built-in-definitions) :path nil :problem (ex-message t)}))))

(defn definition-for
  "The subagent called NAME in the set in force, or nil."
  [definitions name]
  (some #(when (= (:name %) name) %) (:subagents definitions)))

(defn known-names
  "The names a delegation may ask for, in the order they are offered -- what a
  refusal lists and what the delegating tool's parameter enumerates."
  [definitions]
  (mapv :name (:subagents definitions)))

;; --------------------------------------------------------- what a panel reads

(defn built-in?
  "Is this definition one of the two the CODE provides?
  Asked by name against the built-in list rather than by comparing whole maps, and
  the difference is the settings form's whole behaviour: a config.edn entry that
  replaced a built-in keeps its name's standing, so 'explore' stays the row that is
  editable and not deletable however much of it somebody rewrote. What a person is
  told is true either way -- the built-in is what supplies the name, and the file is
  an overlay on it."
  [definition]
  (contains? (set (map :name built-ins)) (:name definition)))

(defn wire-definition
  "One definition as a client reads it: the four fields plus whether this name is
  one the CODE supplies. The extra field is not decoration -- it is what the two
  screens decide their buttons from (a built-in gets Edit and no Remove; a custom
  one gets both), and a client that re-derived it from a hard-coded list of two
  names would be a second answer to a question this namespace already owns."
  [definition]
  (assoc definition :builtin (built-in? definition)))

;; ------------------------------------------------------------ the derived range

(defn- provable-read-only?
  "Can this session prove that TOOL-NAME -- the definition it holds under that name --
  changes nothing when it runs?

  TWO CONDITIONS, and they are this project's two named unprovable provenances rather
  than one:

    - the name must not be one THE SESSION PUT THERE ITSELF. `session-register!` keeps
      the map it was handed, tags and all, so a name that arrived that way can wear
      `:source :builtin` and `:read-only true` and be neither -- reading the map is not
      reading its papers, and the session's own registrations are the one road into a
      table that this namespace can see the start of;
    - the definition must say so on itself: `:source :builtin`, the tag
      `harness.cap.tools` stamps when a built-in enters the table, and `:read-only
      true`, which only the built-ins carry. A definition an external server
      contributed is excluded here -- its roster is a declaration about a process this
      harness does not own.

  Everything else is out, and 'out' is the direction the spec chose: fewer tools is
  the failure to prefer."
  [thread-id tool-name tool]
  (and (not (tools/session-added? thread-id tool-name))
       (= :builtin (:source tool))
       (true? (:read-only tool))))

(defn table-for
  "NAME->TOOL the delegation of DEFINITION from PARENT-THREAD-ID serves, computed
  NOW.

  The main session's table, minus this definition's exclusions, minus the two
  forbidden names, minus the names this session does not serve at all (the editing
  mode's subtraction is a statement about the session, so it is a statement about
  what a delegation from it can inherit). Under `:read-only`, only the names that
  can prove they change nothing.

  PURE, and that is the point of it being a separate function: it answers a
  question, it does not freeze an answer anywhere. Who is running as what is the
  live table's business (begin!), and freezing happens there."
  [parent-thread-id definition]
  (let [excluded (set (:exclude definition))
        read-only? (= :read-only (:baseline definition))]
    (into {}
          (remove (fn [[tool-name tool]]
                    (or (contains? forbidden tool-name)
                        (contains? excluded tool-name)
                        (not (tools/served? parent-thread-id tool-name))
                        (and read-only? (not (provable-read-only? parent-thread-id tool-name tool))))))
          (tools/effective-tools parent-thread-id))))

;; ------------------------------------------------------------------ the live table

(defonce ^:private live
  (atom {}))

(defonce ^:private finished
  ;; WHEN THE DELEGATIONS THIS PROCESS WATCHED END, keyed by the subagent's thread id.
  ;;
  ;; IT HAS TO BE REMEMBERED RATHER THAN DERIVED, and the reason is the same one that makes
  ;; `:running` a memory: the store's row says a delegation HAPPENED, and nothing anywhere
  ;; says when it stopped -- the child's record has its endings, but folding every child's
  ;; log for a pane that draws four rows would be the walk the pane-data rule exists to
  ;; retire (`docs/rules/panel-data.md`). So a delegation ENDING is stamped here, exactly as
  ;; its beginning lives on the store's `created_at`.
  ;;
  ;; WHAT IT DOES NOT KNOW IS HONEST: a delegation left by a previous process has no entry
  ;; here, so its row draws the start and NO duration -- the same answer the row gives for
  ;; `:running false` (`runs`' own docstring argues that distinction).
  ;;
  ;; BOUNDED, because a long-lived process delegates a lot: the map keeps the most recent
  ;; `finished-kept` endings (the store keeps the rows; this is only the clock on them).
  (atom {}))

(def ^:private finished-kept
  "How many ending clocks this process keeps. Far more than any pane draws, far less than
  a working day's delegations -- see the note on `finished`."
  512)

(defn- remember-finished!
  "Stamp THREAD-ID's ending, keeping the newest `finished-kept` of them."
  [thread-id]
  (swap! finished (fn [m]
                    (-> (assoc m thread-id (System/currentTimeMillis))
                        (as-> all (into {} (take finished-kept (sort-by val > all))))))))

;; ---------------------------------------------------- what to tell when a delegation moves

(defonce ^:private change-hook
  ;; A fn of the PARENT thread id -- the session whose pane draws this delegation -- or nil.
  ;; Installed by the composition root (`harness.edge.http/start!`), for the reason
  ;; `harness.cap.jobs/set-change-hook!` gives: a capability does not know about sockets.
  (atom nil))

(defn set-change-hook!
  "Point the seam at F -- a fn of the PARENT thread id, called when a delegation of that
  session STARTS or ENDS. Answers F."
  [f]
  (reset! change-hook f)
  f)

(defn- announce!
  "Say that PARENT's delegations moved. A throwing hook (a closed downlink) must not reach
  the run."
  [parent]
  (when (and (some? parent) (some? @change-hook))
    (try ((deref change-hook) parent) (catch Throwable _ nil)))
  nil)
;; subagent-thread-id -> {:id .. :parent .. :definition .. :table {name tool} :started-at ms}
;;
;; The id is a token, and it is what makes ENDING a delegation safe: this entry may
;; have been replaced by a later delegation on the same thread id (a client that
;; reuses an id, a retried run), and the ending of the first must then leave the
;; second alone. Same shape as the seam's turn plan, for the same reason.

(defn begin!
  "Record that THREAD-ID is now running DEFINITION, delegated by PARENT-THREAD-ID
  over TABLE. Answers the fn that ends it -- which removes THIS entry and nothing
  else."
  [thread-id {:keys [parent definition table]}]
  (let [id (str (java.util.UUID/randomUUID))]
    (swap! live assoc thread-id {:id id :parent parent :definition definition
                                 :table table :started-at (System/currentTimeMillis)})
    ;; A NEW DELEGATION CLEARS AN OLD ENDING ON THE SAME THREAD ID: an id can be reused
    ;; (a retried run, a client that mints the same one twice), and a stale ending clock
    ;; would make the new row look already finished.
    (swap! finished dissoc thread-id)
    (announce! parent)
    (fn end! []
      ;; THE ENTRY LEAVES BEFORE ANYBODY IS TOLD, and that order is the whole of it. A frame
      ;; built while the entry is still in `live` reports this delegation as STILL RUNNING
      ;; (`runs` reads the live table for `:running`), so the last word a pane heard about it
      ;; would be "running" and the row would sit there saying 运行中 for good -- measured
      ;; against a background delegation, whose pane IS watching while it ends.
      ;;
      ;; THE ENDING IS STAMPED ONLY IF THIS WAS STILL THE LIVE DELEGATION (the `id` check),
      ;; which is the same rule the removal follows: ending the FIRST delegation must not
      ;; stop the second one's clock. Removal and the `ended?` flag come out of ONE swap, so
      ;; 'this call removed the entry' and 'this call may announce' cannot disagree.
      (let [ended? (volatile! false)]
        (swap! live (fn [m]
                      (if (= id (get-in m [thread-id :id]))
                        (do (vreset! ended? true) (dissoc m thread-id))
                        m)))
        (when @ended?
          (remember-finished! thread-id)
          (announce! parent))
        nil))))

(defn live-subagent
  "What THREAD-ID is running as, or nil when it is not a subagent thread."
  [thread-id]
  (get @live thread-id))

(defn running
  "Every delegation in flight: the live table as a vector of
  {:thread-id .. :parent .. :definition .. :started-at ..}, oldest first. What a
  reader asking 'what is running right now' gets; nothing here is persisted, so a
  restart answers the empty vector, exactly as the parked calls do."
  []
  (->> @live
       (map (fn [[thread-id rec]] (assoc rec :thread-id thread-id)))
       (sort-by :started-at)
       vec))

(defn runs
  "Every delegation this home has a record of, newest first -- the other half of
  what a subagent panel draws.

    {:thread-id .. :parent .. :subagent .. :project .. :delegated-at <ms> :finished-at <ms|nil>
     :running <bool>}

  `:delegated-at` IS THE START AND `:finished-at` IS THE END, as two instants a row draws
  (开始时间 + 持续时间, ticket 01 of `.scratch/task-pane-push`). The end is this PROCESS's
  memory of watching the delegation finish (`finished`, above), so it is nil for a
  delegation that is still going AND for one an earlier process left behind -- both draw a
  start and no duration, which is the honest answer either way.

  THE RECORD IS THE STORE'S, THE RUNNING FLAG IS THIS PROCESS'S, and they are two
  different kinds of fact kept in the two places that can hold them: 'this home
  delegated something once' survives a restart because it is a row (see
  project/begin-subagent!), while 'this delegation is in flight' is a table in
  memory that a restart empties -- exactly as a parked call is. A row whose
  :running is false is a delegation that finished, or one left by a previous
  process; both read the same on screen, and neither is a lie.

  NOT DERIVED FROM THE DEFINITIONS. A record whose name is not in `definitions` is
  ordinary: somebody delegated to a custom subagent and then deleted it, which is
  precisely the case a panel has to keep showing rather than quietly dropping.

  IT IS THE STORE THAT DECIDES WHAT A RECORD IS, not the log tree: the two columns
  `begin-subagent!` writes are the whole of 'this was a delegation and who asked
  for it', and a session whose log was deleted by hand is still a delegation this
  home ran."
  []
  (let [in-flight (into {} (map (fn [r] [(:thread-id r) r]) (running)))]
    (->> (project/sessions)
         (keep (fn [row]
                 (when-let [subagent (:subagent row)]
                   {:thread-id    (:id row)
                    :parent       (:parent-id row)
                    :subagent     subagent
                    :project      (:path row)
                    :delegated-at (:created-at row)
                    :finished-at  (get @finished (:id row))
                    :running      (contains? in-flight (:id row))})))
         (sort (fn [a b] (compare (:delegated-at b) (:delegated-at a))))
         vec)))

;; ---------------------------------------------------------------- the narrowing

(defn- range-phrase
  "What DEFINITION's range is, in words a sentence can carry. Used by a refusal, so
  it says what the range IS rather than what it is called."
  [definition]
  (str (if (= :read-only (:baseline definition))
         "only the tools that cannot change anything"
         "everything the main agent has")
       (when-let [ex (seq (sort (:exclude definition)))]
         (str ", except " (str/join ", " ex)))
       ", and never " (str/join " or " (sort forbidden))))

(defn served?
  "Does this session serve TOOL-NAME? Yes for every thread that is not a subagent's
  -- this policy speaks for subagent threads and stays out of every other thread's
  way -- and for a subagent thread, yes exactly when the name is in the range
  frozen at its delegation."
  [thread-id tool-name]
  (if-let [rec (get @live thread-id)]
    (contains? (:table rec) tool-name)
    true))

(defn unserved-message
  "What a subagent is told when it calls a name outside its range. It says WHO it
  is, WHAT its range is, and WHAT to do instead -- because the failure this
  sentence exists to prevent is a model treating a restriction as a bug and
  hunting for a way around it."
  [thread-id tool-name]
  (if-let [{:keys [definition]} (get @live thread-id)]
    (if (= delegation-tool tool-name)
      (str tool-name " is not served to a subagent: you are the " (:name definition)
           " subagent, and a subagent cannot delegate -- that is what keeps a"
           " delegation one level deep. Do the work with the tools you have"
           " yourself, and say in your answer what you could not do.")
      (str tool-name " is not part of this subagent's range: you are the "
           (:name definition) " subagent, which serves " (range-phrase definition)
           ". Work with the tools you have, and say in your answer what you could"
           " not do."))
    (str tool-name " is not served in this session.")))

(defn- reason-phrase
  "WHY a call needed a human, as a clause a sentence can carry."
  [reason]
  (case reason
    :tool-declares   "the tool declares that it needs one"
    :session-asks    "this session asks for one before that tool runs"
    :out-of-bounds   "the path it names is outside the project"
    :sensitive-path  "the path it names is one this home declares sensitive"
    :elicitation     "the tool stopped to ask a question"
    (str "reason: " (name (or reason :unspecified)))))

(defn unattended-message
  "What a subagent's call is answered with instead of being parked. Nil for a
  thread that is not a subagent's, which is what keeps every other run's approval
  flow byte-for-byte what it was."
  [thread-id tool-name reason]
  (when (get @live thread-id)
    (str "this call needs a human's approval (" (reason-phrase reason) "), and a"
         " subagent has nobody to ask: " tool-name " did not run. Find another way,"
         " or say in your answer what you could not do.")))

(defn- system-prompt-block
  "The <subagent> block: who this thread is, what it has, and what it cannot do.
  Appended to the system message by an installed row -- see install! -- and
  silent for every thread that is not a subagent's.

  WHAT IT HAS IS READ OFF THE FROZEN RANGE, not derived again here: the range is the
  one thing `begin!` decided, and a block that recomputed it from the session's table
  would be a second answer to the same question -- free to drift from the table the
  calls are actually judged by, and wrong in the one direction that matters, since a
  name the block promises and the seam then refuses is a model hunting for a way
  around a restriction it was told it did not have."
  [payload]
  (let [thread-id (get payload "thread_id")]
    (if-let [{:keys [parent definition table]} (get @live thread-id)]
      {:exit 0 :err ""
       :out (str "<subagent>\n"
                 "You are the " (:name definition) " subagent. The main agent of"
                 " session " parent " delegated one task to you; your FINAL MESSAGE"
                 " is the answer that goes back to it, so make it the answer rather"
                 " than a description of what you did.\n"
                 "You have: "
                 (let [names (sort (keys table))]
                   (if (seq names) (str/join ", " names) "no tools at all"))
                 ".\n"
                 "You cannot delegate to another subagent, and you cannot ask a human"
                 " for anything: a call that would need somebody's approval comes back"
                 " to you saying so, and you have to find another way or say what you"
                 " could not do.\n"
                 "</subagent>")}
      {:exit 0 :err "" :out ""})))

;; ------------------------------------------------------------------- the tool

(def ^:private subagent-params
  "`subagent`'s arguments. The name's description is filled in per session (see face)
  because the list of names is the session's own; the shape itself does not move."
  {:type "object"
   :properties
   {"name"   {:type "string" :description "Which subagent to delegate to."}
    "prompt" {:type "string"
              :description (str "The task, stated so it can be done without this"
                                " conversation: what to find out or do, and what the"
                                " answer should contain.")}
    "run_in_background"
    {:type "boolean"
     :description
     (str "Run the delegation in the BACKGROUND and answer at once with a job id"
          " (like `j1`): the subagent goes on working while you do something else."
          " Read its answer, or wait for it, with `job_output` and that id -- and"
          " its ending is put in front of you before your next model call. Default"
          " false: the call WAITS for the answer and returns it as this call's result.")}}})

(defn- face
  "`subagent`'s face for THREAD-ID: the names on offer, what each one is for, and --
  when the configuration could not be read -- that fact. A tool whose subject is
  the session's own definitions has to describe itself per session, or a model
  reads a stale list."
  [thread-id]
  (let [{:keys [subagents problem]} (definitions thread-id)
        names (mapv :name subagents)]
    {:description
     (str "Delegate a task to a subagent: it runs in its own conversation, with its"
          " own tool range, and its final message comes back to you as this call's"
          " result. Nothing else about the exchange enters this conversation, so a"
          " subagent is how you have something long, self-contained or noisy done"
          " without filling your own context with it. "
          "By DEFAULT this call waits for the subagent's final message and returns it;"
          " set `run_in_background` to true to start it as a background job instead --"
          " the call then returns at once with a job id, and you read or wait on it with"
          " `job_output`. "
          "Available: "
          (str/join "; " (map (fn [d] (str (:name d) " -- " (:description d))) subagents))
          ". "
          "Its answer is what you asked for, not a step towards it: if the subagent"
          " says it could not do something, that is part of the result."
          (when problem
            (str " NOTE: this home's subagent configuration could not be read ("
                 problem "), so only the built-in subagents are listed and NO"
                 " delegation will run until that is fixed.")))
     :parameters
     (cond-> (assoc-in subagent-params [:properties "name" :description]
                       (str "Which subagent"
                            (if (seq names) (str ": " (str/join ", " names)) "") "."))
       (seq names) (assoc-in [:properties "name" :enum] names))}))

(defonce ^:private runner
  (atom nil))
;; The installed fn that runs one delegation: (fn [{:keys [parent-thread-id
;; thread-id definition task]}] -> {:answer string}). It is INSTALLED rather than
;; required because running a conversation means the provider, the record and the
;; hook sink -- the edge's business, not a capability's -- and a second
;; implementation of them here is how the two would drift.

(defn- open-thread!
  "Freeze DEFINITION's range as THREAD-ID and make every call the thread makes from here
  on answered by that range. Answers the fn that closes the thread again.

  THE SAME STEPS WHETHER THE DELEGATION RUNS NOW OR IN THE BACKGROUND, and that is the
  point of it being a function: a background delegation's live-table entry, its store
  row and its registered tools are facts about a delegation that is RUNNING, and they
  must be true by the time the call that started it returns."
  [thread-id parent-thread-id definition table]
  (let [end! (begin! thread-id {:parent     parent-thread-id
                                :definition definition
                                :table      table})]
    (project/begin-subagent! thread-id {:parent   parent-thread-id
                                        :subagent (:name definition)})
    (doseq [[n t] table] (tools/session-register! thread-id n t))
    end!))

(defn- converse!
  "Run THIS thread's conversation to its final message, with SubagentStart/Stop around
  it. Answers the answer string."
  [parent-thread-id definition task thread-id run!]
  (hook/emit :subagent-start {:subagent (:name definition)})
  (try
    (str (:answer (run! {:parent-thread-id parent-thread-id
                         :thread-id        thread-id
                         :definition      definition
                         :task            task})))
    (finally
      (hook/emit :subagent-stop {:subagent (:name definition)}))))

(defn- run-now!
  "One delegation the CALLING TOOL waits for: open the thread, run it, close the
  thread whatever happened, and answer the subagent's final message."
  [parent-thread-id definition task table run!]
  (let [thread-id (str (java.util.UUID/randomUUID))
        end!      (open-thread! thread-id parent-thread-id definition table)]
    (try
      (converse! parent-thread-id definition task thread-id run!)
      (finally (end!)))))

(defn- run-in-background!
  "One delegation the CALLING TOOL does NOT wait for. Answers `{:id <job id>}` at once.

  THE RECORD IS THE ANSWER. The delegation runs on a future exactly as it would on the
  calling thread -- same range, same conversation, its own thread id -- and its final
  message is written to the job's record, which is what `job_output` reads and what the
  ending notice points at. THE JOB IS A MANAGED ONE (`jobs/start-managed!`): it has no
  process, so `job_kill` cancels this future instead of killing a tree, and `stop!`
  claims the record with `[stopped]` exactly as it does for a command.

  A DELEGATION THAT DIED STILL ENDS ITS JOB: the `catch` writes what little there is to
  say and ends the record with `[exit 1]`, so a `job_output {wait: true}` never waits
  forever on a job this process has already given up on."
  [parent-thread-id definition task table run!]
  (let [command (str "subagent " (:name definition) ": " task)
        {:keys [id append! finish! attach!]}
        (jobs/start-managed! parent-thread-id {:command command})
        work (future
               (let [thread-id (str (java.util.UUID/randomUUID))
                     end!      (open-thread! thread-id parent-thread-id definition table)]
                 (try
                   (append! (converse! parent-thread-id definition task thread-id run!))
                   (finish! "[exit 0]")
                   (catch Throwable t
                     (append! (str "the delegation failed: " (or (ex-message t) (str t))))
                     (finish! "[exit 1]"))
                   (finally (end!)))))]
    ;; THE CANCEL REACHES THE FUTURE WHATEVER ENDS THE JOB (`stop!` from the model or from
    ;; the pane, a session being put away, the JVM going) -- `close!` is what calls it.
    (attach! #(future-cancel work))
    {:id id}))

(defn- run-one
  "One delegation, in the order the pieces have to happen in:

    1. freeze the range and open the subagent's thread in the live table -- so
       every call it makes from here on is answered by ITS range, not its
       parent's;
    2. give it a session of its own in the store, which is also what binds it to
       the parent's project. WITHOUT THIS a relative path would resolve against
       the process's working directory, which is the single most surprising thing a
       subagent could do: same task, different files, determined by where the
       server happens to have been started;
    3. register the range as its session's tools, so the definitions the range was
       derived from are the ones its calls run (a tool the parent's session added
       exists nowhere else, and registering is how a session hands one on);
    4. SubagentStart, the run, SubagentStop -- the stop in a `finally`, because a
       delegation that died still stopped;
    5. close the thread whatever happened.

  `BACKGROUND?` DECIDES WHO WAITS. False is the ordinary delegation, and the answer is
  the subagent's final message; true hands the same run to a future and answers a job
  id -- see `run-in-background!`."
  [parent-thread-id definition task background?]
  (let [table (table-for parent-thread-id definition)
        run!  @runner]
    (when-not run!
      (throw (ex-info (str "no delegation runner is installed, so there is nothing to run"
                           " the " (:name definition) " subagent with")
                      {:reason :no-runner})))
    (if background?
      (run-in-background! parent-thread-id definition task table run!)
      (run-now! parent-thread-id definition task table run!))))

(defn- known-phrase [definitions]
  (let [names (known-names definitions)]
    (if (seq names)
      (str "this home has " (str/join ", " names))
      "this home has no subagents at all")))

(defn- background-receipt
  "What a background delegation answers with: which job it is, and the one verb that
  reaches it -- the same sentence a command's `job` receipt ends on."
  [definition {:keys [id]}]
  (str "subagent " (:name definition) " is running in the background as job " id
       "; read its answer, or wait for it, with `job_output {\"job\": \"" id "\"}`."
       " Its ending will be put in front of you before your next model call."))

(defn- t-subagent
  "`subagent`'s body: find the subagent, then hand it the task. Everything it can get
  wrong is refused by name -- an unknown name lists the ones that exist, an empty
  task says what a task is for, an unreadable configuration says which file -- so
  the model can fix its call instead of guessing at what happened.

  `run_in_background` CHANGES WHO WAITS, not what runs: the same delegation, on a
  managed job (`run-in-background!`) whose ending is announced like a command's."
  [args]
  (let [parent-thread-id tools/*thread-id*
        defs             (definitions parent-thread-id)
        requested        (some-> (get args :name) str str/trim)
        task             (some-> (get args :prompt) str str/trim)
        background?      (true? (get args :run_in_background))]
    (when (str/blank? (str requested))
      (throw (ex-info (str "name a subagent to delegate to; " (known-phrase defs) ".")
                      {:reason :no-subagent-named
                       :known (known-names defs)})))
    (when (str/blank? (str task))
      (throw (ex-info (str "a delegation needs a task: put what the " requested
                           " subagent should do in `prompt`.")
                      {:reason :empty-task :name requested})))
    (when-let [problem (:problem defs)]
      (throw (ex-info (str "this home's subagents cannot be read, so nothing is delegated: "
                           problem
                           (when-let [p (:path defs)] (str " (in " p ")"))
                           " -- fix that first; a range somebody meant to narrow and"
                           " could not is not one this harness will run with.")
                      {:reason :unreadable-subagents
                       :path (:path defs)})))
    (if-let [definition (definition-for defs requested)]
      (let [answer (run-one parent-thread-id definition task background?)]
        (if background?
          (background-receipt definition answer)
          answer))
      (throw (ex-info (str "no subagent called " (pr-str requested) " is defined here; "
                           (known-phrase defs) ".")
                      {:reason :unknown-subagent :name requested
                       :known (known-names defs)})))))

(def ^:private subagent-tool
  "The delegating tool, as the seam's table holds it."
  {:description "Delegate a task to a subagent."
   :parameters  {:type "object" :properties {} }
   :required    [:name :prompt]
   :run         t-subagent
   ;; Its subject is the session's own definitions, so its face is per session:
   ;; which names exist, and what each one is for.
   :describe    face})

;; ------------------------------------------------------ where the write goes
;;
;; THE SETTINGS FORM'S HALF, and the word that matters in it is ONLY. config.edn is
;; hand-written and holds other people's keys -- :editing, :skills, :approval, :instructions,
;; :mcp, whatever a home has -- so this namespace's save changes ONE key (:session
;; :subagents) and hands everything else back untouched. The writing itself is
;; harness.cap.providers' (`change-session!`): the file's shape check, the atomic write and
;; the one-generation config.edn.bak live THERE, and there is deliberately no second writer
;; here -- two writers of one file are two rules that drift
;; (.scratch/config-merge/spec.md ticket 05).
;;
;; VALIDATE, THEN WRITE. Every refusal the form can show (a blank name, a baseline that is
;; not one of the two, an exclusion naming no tool, an exclusion naming one of the two
;; forbidden names, a name already taken) is decided BEFORE the file is opened -- so a
;; refused save leaves the home byte-for-byte as it was.

(defn- check-block!
  "A whole :subagents block, judged entry by entry -- the same check the READER
  applies, run before anything is written rather than after.

  THE READER STAYS TOLERANT AND THIS DOES NOT, and the asymmetry is the point (see
  `definitions`): a typo somebody made in a file has to leave a harness that still
  runs, so reading reports the first thing it could not honour and carries on with
  the built-ins. A SAVE is a person asking for a change and being told yes or no,
  and 'yes' followed by a file the next read refuses would be the one outcome worse
  than a refusal -- so the whole block is judged, including entries this save did
  not touch. A file holding a broken entry is one the panel already reports and one
  the form cannot save its way out of until the entry is fixed, and the refusal
  names it, which is the whole of what somebody needs in order to go and fix it."
  [block]
  (when-not (map? block)
    (throw (ex-info (str "config.edn's :session :subagents must be a map of name -> definition,"
                         " but this would write " (pr-str block))
                    {:reason :not-a-map :field :subagents})))
  (doseq [[n entry] block] (check-entry! n entry))
  block)

(defn- change-subagents!
  "CHANGE -- a function of the :subagents block -> the block to write -- and answers
  the block now in force.

  THE WHOLE FILE IS READ AND THE WHOLE FILE IS WRITTEN, with one key replaced: an
  absent file is the empty configuration, a key this namespace does not own is
  carried through untouched, and a file that cannot be READ AT ALL is a named
  failure that refuses the save -- because rewriting something nobody could parse
  is how a form would become the thing that lost it.

  VALIDATION IS BEFORE THE WRITE, which is the whole of 'a refused save changes
  nothing': an empty home gets no config.edn out of a refusal, and an existing one
  is not so much as opened for writing."
  [change]
  (let [raw  (project/harness-config nil)
        next (check-block! (change (or (:subagents raw) {})))]
    (providers/change-session! (fn [session] (assoc session :subagents next)))
    next))

(defn- entry-from-wire
  "A settings form's row -> the entry `check-entry!` judges.

  THE BASELINE ARRIVES AS THE STRING JSON HAS and becomes the keyword the file
  holds; anything this does not recognise is handed ON unchanged rather than
  guessed at, so the refusal a person reads is the one check-entry! words
  ('the baseline of \"x\" must be :all or :read-only') and not a second, vaguer
  one invented here. The exclusions and the description pass through as they came
  -- their own checks live in the same place.

  NOTHING IS FILTERED OUT ON THE WAY IN, and that is the whole difference between
  this and a `select-keys`: a row carrying a key the shape does not have has to
  reach `check-entry!` so it is REFUSED, not silently dropped on the floor. The
  key worth catching is the one that looks like a way to put a tool back
  (`:allow`, `:tools`) -- dropping it would turn 'give the subagent eval' into a
  save that quietly succeeded and did not. `:name` is the one exception, and only
  because it is the first argument to `put-definition!` rather than a key of the
  entry: a caller that repeats it is saying the same thing twice, not asking for
  something the shape cannot hold."
  [row]
  (let [b (:baseline row)]
    (cond-> (dissoc row :name)
      (= "all" (str b))       (assoc :baseline :all)
      (= "read-only" (str b)) (assoc :baseline :read-only))))

(defn put-definition!
  "NAME + ROW (the form's shape) + REPLACE? -> the definition now in force, and the
  user-level config.edn rewritten to hold it.

  CREATE OR REPLACE, ONE FUNCTION FOR BOTH, and REPLACE? is what tells them apart.
  It is not decoration: 'new subagent' and 'edit this one' are the same write, and
  the difference a person cares about is that the first must not silently take a
  name that is already in use. Left to itself a form would let somebody type
  `explore` into the new-subagent name field and quietly replace the built-in --
  which is a change nobody asked for, delivered by a screen that looked like it was
  adding something. So a name already in force is REFUSED unless the caller says
  it means to replace it, and the two screens are what say so.

  THE VALIDATION ORDER IS THE SENTENCE ORDER: the entry is judged first (so a blank
  name is refused for being blank rather than for being taken), and only then is
  the name looked up. Nothing touches the file until both have passed."
  [name row replace?]
  (let [d    (check-entry! name (entry-from-wire row))
        defs (definitions)
        in-force (definition-for defs (:name d))]
    (when (and in-force (not replace?))
      (throw (ex-info (str "there is already a subagent called " (pr-str (:name d))
                           " (" (:description in-force) "). Pick another name, or edit"
                           " that one -- saving here would replace it.")
                      {:reason :name-taken :field :name :name (:name d)})))
    (change-subagents! (fn [block] (assoc block (:name d) (dissoc d :name))))
    d))

(defn remove-definition!
  "NAME -> the definition that was removed, and the user-level config.edn rewritten
  without it.

  BUILT-INS ARE NOT DELETABLE, and the refusal says why rather than just no: they
  are supplied by the code, so there is nothing in the file to take away, and a
  fresh home with no subagents would have no names to delegate to at all. What can
  be done to a built-in is EDIT it -- a same-name entry in the file overrides it --
  and the sentence points there.

  A NAME THE FILE DOES NOT HOLD IS REFUSED TOO, including a custom subagent whose
  entry somebody typed into the wrong level: this removes what the reader would
  have found, and the reader reads the user level (see `user-block`). Removing by
  ENTRY rather than by key, so a hand-written file whose keys are symbols is not
  a file this cannot clean up."
  [the-name]
  (let [n     (some-> the-name str str/trim)
        defs  (definitions)
        known (definition-for defs n)]
    (when (str/blank? (str n))
      (throw (ex-info "name a subagent to remove." {:reason :blank-name :field :name})))
    (when-not known
      (throw (ex-info (str "no subagent called " (pr-str n) " is defined here; "
                           (known-phrase defs) ".")
                      {:reason :unknown-subagent :name n :known (known-names defs)})))
    (when (built-in? known)
      (throw (ex-info (str (pr-str n) " is built in: it comes from the code, so there is"
                           " nothing in config.edn to remove. Edit it instead -- an entry"
                           " with its name replaces it wherever it appears.")
                      {:reason :built-in :name n})))
    (change-subagents! (fn [block]
                         (into {} (remove (fn [[k _]] (= n (some-> k str str/trim))) block))))
    known))

;; ------------------------------------------------------------------ installing

(defn install!
  "Put this capability's three contributions in, and answer the ONE teardown that
  takes them all out again:

    - the `subagent` tool, so a session can delegate at all;
    - the narrowing policy, so a subagent thread is held to the range it was
      delegated with -- asked AFTER any policy installed before it, which is what
      keeps a doubly-refused name refused in the more specific words;
    - the unattended policy, so a subagent's call that needs a human is answered
      with that fact instead of parking a run nobody is watching;
    - one SystemPrompt row, which tells a subagent thread who it is.

  RUN is the delegation runner: (fn [{:keys [parent-thread-id thread-id
  definition task]}] -> {:answer string}). It arrives through this door rather
  than being required because running a conversation means the provider, the
  record and the hook sink -- the edge's business. A process that installs this
  without one serves the `subagent` tool and answers every call to it with 'no
  delegation runner is installed', which is the honest state of a process that
  cannot run a conversation; the composition root always passes one."
  [{:keys [run]}]
  (reset! runner run)
  (let [tools-teardown (tools/install! {:name "subagents"
                                        :tools {delegation-tool subagent-tool}
                                        :narrow {:served? served?
                                                 :refuse  unserved-message}
                                        :unattended unattended-message})
        hooks-teardown (hooks/install! {:name "subagent rows"
                                        :builtins {:system-prompt
                                                   [["subagent" {:run system-prompt-block}]]}})]
    (fn teardown []
      (hooks-teardown)
      (tools-teardown)
      (reset! runner nil)
      nil)))
