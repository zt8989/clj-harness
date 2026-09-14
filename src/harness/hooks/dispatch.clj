(ns harness.hooks.dispatch
  "The hook engine's half that RUNS something: pick the declarations a trigger
  matches, spawn each command with its payload on stdin, read the exit codes, and
  report one verdict plus one audit line.

  THE CONTRACT, in three lines:

    exit 0    allow           -- the run carries on
    exit 2    block           -- stderr is the reason, and it goes back to the model
    other     per the point's :on-error -- :proceed for an observer, :block for a
              gate. A TIMEOUT and a SPAWN FAILURE land here too: 'we could not
              run your hook' is a failure to decide, and a gate that cannot
              decide must not decide 'yes' (see harness.hooks/points).

  NOTHING DECLARED MEANS NOTHING HAPPENS, and that is the default state of a
  fresh install. `fire` with no matching declarations returns :allow without
  spawning, without waiting and without writing a line, so a run's frames and
  audit trail are byte-identical to a run before this capability existed.

  One audit line per trigger that had work to do: kind `hook/<point>` (the
  payload spelling -- hook/PreToolUse), carrying what matched, what each
  declaration answered, and the resulting verdict. Written through the REPORT
  function the caller hands in, which is harness.http's log! -- the only writer."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness.hooks :as hooks]
            [harness.shell :as shell]))

(def ^:private default-timeout-ms
  "How long a declaration without a :timeout may take. Generous for a gate --
  it sits in the run's path -- and the point's :on-error decides what happens
  when it is hit."
  10000)

(defn- jsonable
  "V as something clojure.data.json can write. A value the encoder refuses (a
  set, a lazy seq, anything exotic a fact happens to carry) becomes its printed
  form rather than taking the whole hook down: a hook that cannot read ONE field
  is still better than no hook at all, and the printed form is exactly what the
  author would have written by hand."
  [v]
  (try
    (json/write-str v)
    (json/write-str (pr-str v))
    (catch Exception _ (json/write-str (str v)))))

(defn- matches? [decl subject]
  (if-let [pattern (:matcher decl)]
    ;; The matcher was compiled at load (harness.hooks/check-declaration), so
    ;; this cannot throw here; a malformed pattern never got this far.
    (boolean (re-find (re-pattern pattern) (str subject)))
    true))

(declare fire)

(defn- declaration-for
  "The declarations of POINT in force for THREAD-ID that this trigger should run:
  the point's declarations -- on-disk and session-added, in written order, with
  the disabled ones already left out -- whose :matcher selects FACT's subject.

  Only declarations WITH a matcher can fail to match, and only a point that
  names a match target can have matchers at all -- validation sees to that."
  [thread-id point-kw fact]
  (let [point (hooks/point-for point-kw)
        subject (get fact (:matches point))]
    (filterv #(matches? % subject) (hooks/declarations-at thread-id point-kw))))

(defn- answer-in
  "The advanced decision a command put on STDOUT, as a map -- or nil.

  Exit codes are the base protocol (0 allow, 2 block); this is how a hook says
  something the code cannot, which only PermissionRequest needs today: an ANSWER
  to a parked call, \"approve\" or \"deny\", instead of bothering a human.

  Read only when the command exited 0 -- a hook that failed cannot also be
  answering -- and only when stdout is a JSON OBJECT. Unparseable, or JSON of
  another shape, is not an answer: a hook that prints a log line must not be
  read as having decided something."
  [run]
  (when (and (zero? (or (:exit run) -1))
             (not (str/blank? (str/trim (str (:out run))))))
    (try
      (let [v (json/read-str (str/trim (str (:out run))))]
        (when (map? v) v))
      (catch Exception _ nil))))

(defn- verdict-of
  "One declaration's run -> {:outcome :allow|:block|:error :reason .. :answer ..}.

  The reason is always the command's OWN output, never ours: on a block it is
  the stderr the author wrote (that is what gets fed back to the model), and on a
  failure it is stderr when there is any, else a line saying what went wrong.
  An author's message beats a message about the author.

  :answer carries what the command put on stdout as JSON, if anything -- read
  even when the point does not use answers, so a caller can report that a hook
  said something. Only the caller knows whether an answer means anything there."
  [point run]
  (let [{:keys [exit out err timeout]} run
        why (if (str/blank? (str/trim (str err))) (str/trim (str out)) (str/trim (str err)))
        answer (answer-in run)]
    (cond
      timeout       {:outcome :error :reason (str "hook timed out after its timeout: " why)
                     :on-error (:on-error point)}
      (nil? exit)   {:outcome :error :reason (str "hook could not be run: " why)
                     :on-error (:on-error point)}
      (zero? exit)  {:outcome :allow :reason why :answer answer}
      (= 2 exit)    {:outcome :block :reason why}
      :else         {:outcome :error
                     :reason (str "hook exited " exit ": " why)
                     :on-error (:on-error point)})))

(defn- outcome->verdict
  "A declaration's outcome folded onto the point's failure policy. A block is a
  block; a failure becomes whatever the point says a failure means."
  [point {:keys [outcome reason on-error]}]
  (if (or (= :block outcome)
          (and (= :error outcome) (= :block (or on-error (:on-error point)))))
    {:verdict :block :reason reason}
    {:verdict :allow :reason (when (= :error outcome) reason)}))

(def ^:dynamic *sink*
  "The run currently firing hooks, or nil. Bound by the edge (harness.http) for
  the duration of a run, because the edge is what knows the two things a sink
  needs and the kernel does not: which thread this run serves, and where an audit
  line is written.

  UNBOUND MEANS HOOKS DO NOT FIRE -- deliberately the default every other caller
  gets. An offline tool, a scripted test, a replay: none of them has an audit
  writer, and a hook whose verdict nobody records is worse than no hook, because
  it changes a run silently. So the capability is opt-in at the edge, and
  everything below the edge stays byte-identical without it."
  nil)

(defn emit
  "Fire POINT with FACT as part of the current run, if one is bound. Returns the
  same map `fire` does, or :allow when there is no sink -- the caller's code path
  is the same either way, which is what makes the unbound case free."
  [point fact]
  (if *sink*
    (fire (assoc *sink* :point point :fact fact))
    {:verdict :allow :reason nil :matched 0}))

(defn fire
  "Fire POINT for THREAD-ID with FACT, reporting through AUDIT (a fn of the
  audit line's payload). Returns

    {:verdict :allow|:block :reason <string|nil> :matched n}

  :block is the ONLY outcome that asks the caller to change course. When several
  declarations match, they all run -- one hook cannot hide another from being
  told what happened -- and the FIRST block wins, in declaration order, so the
  reason a model is given is the one the earliest gate wrote. :reason also
  carries an observer's failure when nothing blocked, for callers that want to
  say a hook was broken; the verdict still says :allow, because it is not theirs
  to act on.

  :answer is what a command put on stdout as a JSON object, from the first one
  that did. It is a THIRD thing to say -- neither allow nor block, but a decision
  about something else -- and today only PermissionRequest has a use for it: an
  answer to a parked call, so a human need not be asked. A point that ignores
  answers simply ignores this field; nothing about the base protocol changes.

  AUDIT is called exactly once per trigger that had matching declarations --
  nothing matched, nothing written. The line's runId is the sink's business
  (harness.http binds it), not something this returns."
  [{:keys [point thread-id fact audit]}]
  (let [point-kw point
        p        (hooks/point-for point-kw)
        _        (when-not p
                   (throw (ex-info (str "not a hook point: " (pr-str point-kw))
                                   {:point point-kw})))
        decls    (declaration-for thread-id point-kw fact)]
    (if (empty? decls)
      {:verdict :allow :reason nil :matched 0}
      (let [payload (json/write-str (into {"hook" (:name p)
                                           "thread_id" thread-id
                                           "project_dir" (get fact :project_dir)}
                                          (map (fn [[k v]] [(name k) (str v)]))
                                          (select-keys fact (:payload p))))
            results (mapv (fn [d]
                            (let [run (try
                                        (shell/run {:command (:command d)
                                                    :stdin payload
                                                    :timeout-ms (:timeout d default-timeout-ms)})
                                        (catch Exception e
                                          ;; A command that cannot be spawned at all:
                                          ;; the shell itself is missing, or the
                                          ;; process limit. Reported as a failure
                                          ;; of this declaration rather than thrown
                                          ;; -- the run's fate is the point's call,
                                          ;; not an exception's.
                                          {:exit nil :out "" :err (or (ex-message e) "spawn failed")}))
                                  outcome (verdict-of p run)]
                              (merge (outcome->verdict p outcome)
                                     {:outcome (:outcome outcome)
                                      :answer (:answer outcome)
                                      :exit (:exit run)
                                      :timeout (boolean (:timeout run))})))
                          decls)
            blocked (first (filter #(= :block (:verdict %)) results))
            ;; A reason is returned for the FIRST thing that was not a plain
            ;; allow: a block's message, or an observer's failure. It never
            ;; changes the verdict -- an observer's failure is not the caller's
            ;; to act on -- but dropping it here would make the engine the only
            ;; place that knew a hook was broken, and the caller unable to say so.
            failed (first (filter #(= :error (:outcome %)) results))
            ;; The FIRST declaration that answered. One answer is the decision:
            ;; two hooks both answering a park is a configuration question, and
            ;; taking the earliest keeps it the same rule as the first block wins.
            answered (first (filter :answer results))]
        (when audit
          (audit {:point (:name p)
                  :thread_id thread-id
                  :matched (count decls)
                  :verdict (if blocked :block :allow)
                  :reason (:reason (or blocked failed))
                  :answer (:answer answered)
                  :results (mapv #(select-keys % [:exit :timeout :outcome :verdict :reason :answer])
                                 results)}))
        {:verdict (if blocked :block :allow)
         :reason (:reason (or blocked failed))
         :answer (:answer answered)
         :matched (count decls)}))))
