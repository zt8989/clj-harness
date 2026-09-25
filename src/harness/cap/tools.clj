(ns harness.cap.tools
  "The tools this harness ships: their bodies, their faces, and nothing else. It is
  a CAPABILITY, so it lives here and not in harness.kernel.tools -- which holds the
  seam that runs a tool, not any particular tool.

  NOBODY COUNTS THEM HERE. The roster is what this file's `register!` calls add up
  to, and a number written into a sentence is a number that goes stale in silence
  the next time a tool is added -- ask the table (`harness.kernel.tools/specs`)
  rather than this paragraph.

  THE SEAM DOES NOT KNOW THESE EXIST. `harness.kernel.tools` has a registry, an
  install door and a spec vocabulary; none of the names below appear in it. They
  arrive through `install!` at setup, from the composition root
  (`harness.edge.http`) or from a test's fixture, and `teardown` takes them away
  again.

  WHAT LIVES WHERE, for the reader who came here looking for the seam: the
  three-phase execution, the three outcomes, the parking and the hook gates are
  all in harness.kernel.tools. What is here is what a model can ask for.

  THE BODIES SPLIT TWO WAYS, and the split is the editing mode rather than
  anything in this file: `read` and `write` keep one name and change their
  behaviour, so they carry a `:describe` (see harness.kernel.tools/tool-face),
  and the anchor-addressed tools are simply absent from the table a
  str-replace session is served (see harness.cap.editing)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.cap.editing :as editing]
            [harness.cap.glob :as glob]
            [harness.cap.hashline.grep :as grep]
            [harness.cap.hashline.replace :as replace]
            [harness.cap.hashline.serve :as serve]
            [harness.cap.hashline.undo :as undo]
            [harness.cap.hashline.write :as hashline-write]
            [harness.cap.jobs :as jobs]
            [harness.cap.project :as project]
            [harness.cap.skills :as skills]
            [harness.cap.todos :as todos]
            [harness.cap.web :as web]
            [harness.cap.web.search :as search]
            [harness.infra.shell :as shell]
            [harness.kernel.tools :as kernel-tools])
  (:import [java.util.regex Pattern]))

;; ------------------------------------------------------------------ the table
;;
;; `register!` here is NOT the seam's (the seam has no such function any more):
;; it accumulates this namespace's own definitions into a map that `install!`
;; hands over. Keeping those forms in their original shape is deliberate --
;; they moved out of the seam verbatim, so a reader diffing the two files sees
;; bodies, not a rewrite.

(defonce ^:private built-ins (atom {}))

(defn- register!
  "Put NAME->TOOL into THIS LAYER's table. The `:source` is stamped here, once,
  rather than at every call site: this map IS the built-in half of the table,
  so a row in it knows where it came from by construction. Rows from other
  origins carry their own (an external server's say :mcp).

  `:read-only true` is the OTHER thing a row may declare about itself, and it is a
  TOOL's statement rather than a switch: 'running me cannot change anything'. Only
  five rows carry it, and every one of them is a tool whose body does nothing but
  read -- a file, a tree, two web endpoints. It is not a hint and not a default: a
  tool that does not say it is treated as one that can write, which is the whole
  reason a `:read-only` subagent range can be derived without a list of names
  somebody has to remember to update. Whether a DECLARATION is believable is the
  derivation's question, not this one's -- see harness.cap.subagents, which trusts
  it only from a source whose provenance it can prove."
  [name tool] (swap! built-ins assoc name (assoc tool :source :builtin)))

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

;; The file tools resolve their path through harness.cap.project first: a session
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
  (let [p (project/resolve-path kernel-tools/*thread-id* path)
        {:keys [text]} (serve/read! kernel-tools/*thread-id* p
                                    {:offset (positive-int :offset offset)
                                     :limit  (positive-int :limit limit)})]
    text))

(defn- t-read
  "`read`'s body, dispatched on this session's editing mode."
  [{:keys [path] :as args}]
  (let [p (project/resolve-path kernel-tools/*thread-id* path)]
    (if (= :hashline (:mode (editing/editing-mode kernel-tools/*thread-id*)))
      (anchored-read (assoc args :path p))
      (slurp p :encoding "UTF-8"))))

(defn- t-write
  "`write`'s body, dispatched on this session's editing mode.

  In anchor mode the file's anchors are not merely stale -- they address lines
  that are gone -- so the write RELEASES them and clears the file's undo record;
  the answer states that, and where to get anchors again, rather than handing
  lines back (harness.cap.hashline.write). In str-replace mode none of that
  exists and this is the write it always was."
  [{:keys [path content] :as args}]
  (if (= :hashline (:mode (editing/editing-mode kernel-tools/*thread-id*)))
    (hashline-write/perform! kernel-tools/*thread-id* #(project/resolve-path kernel-tools/*thread-id* %) args)
    (let [p (project/resolve-path kernel-tools/*thread-id* path)]
      (write-file! p content)
      (str "wrote " (count content) " chars to " p))))

(defn- t-edit [{:keys [path old_string new_string]}]
  (let [p (project/resolve-path kernel-tools/*thread-id* path)
        s (slurp p :encoding "UTF-8")
        n (count (re-seq (re-pattern (Pattern/quote old_string)) s))]
    (when (zero? n) (throw (ex-info (str "old_string not found in " p) {})))
    (when (> n 1)
      (throw (ex-info (str "old_string occurs " n " times in " p ", make it unique") {})))
    (write-file! p (str/replace-first s old_string new_string))
    (str "edited " p)))

(def ^:private bash-default-timeout-ms
  "How long a `bash` call waits for its command before stopping it, in
  MILLISECONDS. One source, and the tool's own description interpolates it: a
  description with the number written into it is a description that goes on saying
  two minutes after somebody changes the default."
  120000)

(def ^:private shell-names
  "The names a `bash` call may give for `shell` -- and the ONLY source of them: the
  description advertises this list, the schema's `:enum` publishes it, the validation
  accepts it and the refusal quotes it.

  DERIVED FROM THE KINDS THE SHELL LAYER KNOWS, so a name a refusal offers is always a
  name that is accepted. A hand-written list could drift into the one lie a refusal must
  not tell -- an answer that says 'what it has is: bash' while `shell: \"bash\"` is then
  rejected as a word nobody knows."
  (->> shell/candidates (map :kind) distinct (mapv name)))

(def ^:private shell-names-text
  "The same list as prose, so the description and the refusals do not write it out again."
  (str/join ", " (map #(str "`" % "`") shell-names)))

(defn- named-shell
  "The KIND a `bash` call named, or nil when it named none. The refusals happen here, and
  there are TWO of them on purpose:

    - a word that is not one of `shell-names`: the reader fixes that by reading the list,
      and the sentence says what the list is;
    - one of those names that THIS MACHINE does not have: re-reading fixes nothing, and
      the sentence says what the machine does have instead.

  BOTH REFUSE RATHER THAN FALLING BACK. Running a line under a shell nobody asked for
  hands the caller's `%VAR%` or `$env:VAR` to somebody who does not read it, and the
  answer then reads as a bug in the command rather than as a shell that was substituted."
  [named]
  (when (some? named)
    (let [s (str named)]
      (when-not (some #{s} shell-names)
        (throw (ex-info (str "`shell` must name one of " shell-names-text
                             ", or be left out to use this machine's own shell; "
                             (pr-str s) " is not one of them.")
                        {:argument :shell :reason :unknown-shell :value s})))
      (try
        (shell/require-shell! (keyword s))
        (keyword s)
        (catch clojure.lang.ExceptionInfo e
          ;; `require-shell!` already wrote the sentence (and listed what this machine
          ;; has); what it cannot know is that the caller named it as an ARGUMENT.
          (throw (ex-info (ex-message e) (assoc (ex-data e) :argument :shell))))))))

(defn- work-dir
  "The directory a `bash` call runs in: the session's project binding, or -- when the
  call names one -- `workdir`, resolved the way every other path in this table is
  (relative to the project directory).

  MEMBERSHIP IS NOT CHECKED, and that is not an oversight: `bash` has no fence at
  all -- a command can `cd` anywhere or read anything inside its own string -- so
  parking a `workdir` would gate a door the same command opens by itself. What IS
  checked is the one thing the shell cannot fix: it has to be a directory, and
  which of the two ways it is not one is said out loud.

  `nil` (no `workdir` given) answers the binding, exactly as before -- including
  the unbound case, where the binding is nil and the child inherits this process's
  own working directory."
  [thread-id workdir]
  (if (nil? workdir)
    (project/binding-for thread-id)
    (let [d (project/resolve-path thread-id workdir)
          f (io/file d)]
      (when-not (.isDirectory f)
        (throw (ex-info (str "`workdir` must be a directory; "
                             (if (.exists f)
                               (str d " is a file.")
                               (str "nothing is there at " d ".")))
                        {:argument :workdir :path d :reason :not-a-directory})))
      d)))

(defn- record-text
  "What a spilled answer leaves in its record: the bytes the answer would have carried
  if it were unbounded -- stdout and then stderr, the order the answer itself uses --
  plus the ending line, terminated, so a reader of the record finds one account of
  what the command said and how it ended.

  The newline is put back when the command's own output did not end on one: without
  it the ending would be welded onto the last line the command printed, and the
  record's last line would be that line plus `[exit 0]`."
  [out err ending]
  (let [body (str out err)]
    (str (if (and (seq body) (not (str/ends-with? body "\n"))) (str body "\n") body)
         ending "\n")))

(defn- redirect-note
  "A note for the answer when COMMAND sends its own output to a file, or nil.

  A NOTE, NOT A REFUSAL, and the judgement behind it is best effort on purpose (see
  `cap.jobs/output-redirect`): quotes, variables and a nested shell all have ways of
  hiding a redirection, and holding up a legitimate command -- `> report.csv` is real
  work -- on a best-effort judgement pays a real thing for a posture. The command runs
  either way; without this line a background caller is just handed a record that will
  stay empty, with nothing saying why.

  IT IS A DIAGNOSIS, NOT ADVICE. 'read that file instead' is the one sentence here that
  says what to do, and it stays because the path in it is the only place the target is
  named -- a model that saw an empty record with no note would be looking for a bug."
  [command]
  (when-let [target (jobs/output-redirect command)]
    (str " note: this command sends its own output to " target ", so the job's record will"
         " stay empty -- that file is where its output is.")))

(defn- read-with
  "The one sentence both background receipts end on: how to read what the job said.

  THE SAME SHAPE THE NOTICE USES, on purpose. `cap.jobs/notice` puts
  `Read what it said with job_output {\"job\": \"j…\"}.` in the history when a job ends, and a
  model that has seen one of these has seen the other -- the next move is the same in both
  cases.

  AN ID, NOT A PATH. `job_output` asks by id, and so does `job_kill`; where the record sits
  under the configuration home is this repo's own shape, and a RECEIPT quoting it is an
  echo -- the reader hands it out itself, once the window it carries is short of the whole
  record (`.scratch/job-receipt-no-path`)."
  [job-id]
  (str "read it with `job_output {\"job\": \"" job-id "\"}`."))

(defn- t-bash
  "`bash`'s body: one command, and the call waits for it.

  THE WAIT IS THE POINT. This used to go through
  `infra.shell/shell` (`clojure.java.shell/sh`), which has no timeout at all -- so a
  hung command hung the whole run, forever. `infra.shell/run` is the timeout-shaped
  one, and it stops the command TOGETHER WITH EVERYTHING IT STARTED, which is the
  half that matters: what a hung `npm test` leaves behind is a child of the shell, not
  the shell.

  A timeout is not an error: the command's own output is returned, with the limit
  appended in the same shape a non-zero exit gets. The model learns what happened and
  can try something narrower -- the same judgement `cap.git` makes about a git that
  hung ('a fact the caller may want to report').

  `stdin` IS WRITTEN AND THEN CLOSED (`infra.shell/run` has always done that): a
  command that reads it sees EOF rather than waiting for a parent that is never going
  to type. `workdir` is where it runs -- see `work-dir` for what is, and is not,
  checked about it.

  NOT WAITING IS `t-job`, A VERB OF ITS OWN. The directory resolution, the record and
  the spawn are shared; what is not shared is the question 'does this call wait', and
  a caller made to answer it with a boolean is the caller that got it wrong before
  (`bash-record`'s session: a `job` whose description said 'slow', and a model that
  built its own `join` out of `sleep`).

  AND THE FIELD IS NOT HERE. `run_in_background` is not one of this verb's arguments,
  so nothing below reads one: it is not a key to have an opinion about, it is a key
  this tool does not have, and a body that lectured about it would be describing the
  other verb. The schema is what tells a caller the language of a call."
  [{:keys [command timeout stdin workdir] shell-named :shell}]
  (let [dir   (work-dir kernel-tools/*thread-id* workdir)
        ;; RESOLVED BEFORE ANYTHING IS STARTED, so a kind this machine does not have
        ;; refuses without a job id, a record file or a half-started process to clean up.
        kind  (named-shell shell-named)
        limit (or (positive-int :timeout timeout) bash-default-timeout-ms)
        {:keys [exit out err] stopped :timeout} (shell/run {:command command
                                                            :stdin stdin
                                                            :dir (when dir dir)
                                                            :timeout-ms limit
                                                            :kind kind
                                                            ;; THE HANDLE A STOP NEEDS: if the run
                                                            ;; this call serves is stopped, the command
                                                            ;; must die with it, tree and all (ticket 08 of
                                                            ;; `.scratch/session-after-refresh`). `*stop*`
                                                            ;; is the slot the execution seam bound for THIS
                                                            ;; call -- nil outside a run, and then nobody is
                                                            ;; asking to stop it.
                                                            :on-spawn
                                                            (fn [p]
                                                              (when-let [stop kernel-tools/*stop*]
                                                                (reset! stop #(shell/stop-tree! p))))})
        ;; THE ENDING IS ONE FACT, WRITTEN ONCE. Below the budget the answer's
        ;; shape is exactly what it was (`[exit N]` only for a non-zero one); when
        ;; the output did not fit, the record ends on this line and so does the
        ;; answer, so the two can never disagree about how the command ended.
        ending (cond stopped (str "[timed out after " limit "ms — the command was stopped]")
                     (not (zero? exit)) (str "[exit " exit "]"))
        out*  (jobs/tail-within-budget out jobs/answer-budget-bytes)
        err*  (jobs/tail-within-budget err jobs/answer-budget-bytes)
        body  (str (:text out*) (:text err*))]
    (if (and (zero? (:omitted out*)) (zero? (:omitted err*)))
      (str (if (str/blank? body) "(no output)" body)
           (when ending (str "\n" ending)))
      ;; Over budget: the whole of it goes to a record, the answer keeps the tail of
      ;; each stream -- they are counted separately, so a single line of stderr is
      ;; never squeezed out by a loud stdout -- and the bytes left out are said out
      ;; loud, per stream, next to where the rest can be read.
      (let [ending (or ending "[exit 0]")
            path   (jobs/spill! kernel-tools/*thread-id*
                                (record-text out err ending))]
        (str body
             (when (pos? (:omitted out*))
               (str "\n" (jobs/truncation-line (:omitted out*) path "stdout")))
             (when (pos? (:omitted err*))
               (str "\n" (jobs/truncation-line (:omitted err*) path "stderr")))
             "\n" ending)))))

(defn- t-job
  "`job`'s body: hand the command to harness.cap.jobs and answer AT ONCE with the
  job's id and how to read what it says.

  THE SAME COMMAND `bash` RUNS, RUN THE OTHER WAY. `work-dir` resolves the directory
  in one place for both, the record is written by one module, and the shell kind is
  resolved BEFORE anything starts -- so a kind this machine does not have refuses
  without a job id, a record file or a half-started process to clean up.

  A JOB HAS NO TIMEOUT, so `timeout` is not one of this verb's arguments at all: a
  number that limits nothing is worse than no number, because it reads like a promise.
  `stdin` is not one either -- nothing feeds a background command, so text sent as one
  would never be read. Neither is refused, and that is the point of deleting them: a
  body only reads the keys its own schema declares (`t-bash` says the same about the
  verb it shares the spawn with), so there is nothing here to drop, and nothing to
  argue with. To write to a command's stdin, run it with `bash`; to wait for one, call
  `job_output` with `wait`."
  [{:keys [command workdir] shell-named :shell}]
  (let [dir  (work-dir kernel-tools/*thread-id* workdir)
        kind (named-shell shell-named)]
    (let [{:keys [id]} (jobs/start! kernel-tools/*thread-id*
                                     {:command command :dir dir :kind kind})]
      ;; TWO FACTS AND NOTHING ELSE. How it went is not known yet (it has just started),
      ;; and the sentence that says how to read it is the same one the notice carries -- the
      ;; model's next move is the same in both cases. NO PATH: where the record sits under the
      ;; configuration home is this repo's business, and `job_output` asks by id.
      (str "job " id " started; " (read-with id) (redirect-note command)))))


(defn- t-eval [{:keys [code]}]
  (let [sw (java.io.StringWriter.)
        v  (binding [*ns* (the-ns 'harness.user) *out* sw]
             (last (map eval (read-string (str "[" code "]")))))
        printed (.toString sw)]
    (clip (str (when (seq printed) (str printed "\n"))
               (pr-str v)))))

(defn- t-skill
  "Load a skill into the conversation. Answers with the confirmation that
  harness.cap.skills/derived-injections then looks for -- that string is the ONLY
  record that a load happened, which is why it is shared rather than written
  twice (see harness.cap.skills/loaded-prefix).

  The name never becomes a path: harness.cap.skills/skill-for looks it up among the
  names an actual directory listing produced, so '../../etc/passwd' is refused
  as an unknown skill rather than resolved into anything.

  THE MODEL'S HALF OF A TWO-WAY LOAD. A person loads a skill by typing `/name` in
  the composer (harness.cap.skills/slash-request); this tool is the model's way, and
  the two differ in exactly one place: `disable-model-invocation` is refused here
  and allowed there.

  NOT marked :requires-approval. Reading instructions is not a side effect, and
  everything the body goes on to ask for is gated by its own seam: the fence
  still parks a file path, a declared approval still parks its tool. Gating the
  read would make the instructions harder to obtain than the actions they
  describe, which is backwards."
  [{:keys [name]}]
  (let [roots (project/skill-roots kernel-tools/*thread-id*)
        entry (skills/skill-for roots name)]
    (cond
      (nil? entry)
      (throw (ex-info (skills/absent-notice roots name)
                      {:name name :known (skills/known-names roots)}))

      (not (:available? entry))
      ;; The sentence comes from harness.cap.skills/broken-notice, which spells it once
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

;; -------------------------------------------------------------- the built-ins

;; ---------------------------------------------------------------- the fence
;;
;; A DECLARATION, NOT A CASE IN THE SEAM. The file tools carry `:park-reason`, and
;; harness.kernel.tools collects the keyword it answers -- without knowing what
;; "out of bounds" means, or that paths and projects are involved at all. The rule
;; itself lives here, where the tools do.
;;
;; When the session is bound to a project directory, a path resolving outside the
;; project directory AND the configuration home parks for approval before it runs.
;; Unbound sessions are untouched: the fence is a property of the tool MARKER,
;; engaged only by a binding, and the park itself is the ordinary approval flow.

(defn- fenced-path
  "The path the fence should judge for this call, or nil when there is nothing to
  judge.

  Usually that is the `path` argument. `replace` and `insert` pass a DERIVE instead,
  because they name their file by ANCHOR: the anchor is what says which file the
  call is about (harness.cap.hashline.replace/target-path). Without it, an
  anchor-addressed edit to a file outside the project would run with no fence at
  all, there being no path argument to look at.

  Best effort on purpose: this runs BEFORE the argument checks, so a malformed
  payload must come back as nil -- nothing to judge -- rather than as an exception
  from a derivation handed nonsense. The argument check reports that payload one
  line later, with the useful message."
  [derive thread-id parsed]
  (or (:path parsed)
      (when derive (try (derive thread-id parsed) (catch Throwable _ nil)))))

(defn- fence
  "The park rule a fence-marked tool declares: a call whose target resolves outside
  the session's project directory and the configuration home parks for a human.
  DERIVE names that target when the tool has no `path` argument (see fenced-path).

  ONE SOURCE, NOT TWO. `harness.cap.project/out-of-bounds?` answers the same
  question the `<project>` block of the system message describes -- CONTEXT.md's
  围栏 -- so the model is told exactly the rule it is held to. An unbound session
  answers false, which is what keeps the pre-binding behaviour byte for byte.

  A CALL WITH NO PATH AT ALL IS NOT A FENCE CASE. The fence is a question about a
  path, and asking it about a missing one would answer with an exception from inside
  java.io instead of the honest fact that the argument is absent -- which the seam's
  missing-arguments check says one line later. Reading 'no path' as 'not out of
  bounds' is what keeps that check reachable."
  [derive]
  (fn [thread-id parsed]
    (let [p (fenced-path derive thread-id parsed)]
      (when (and (some? p) (project/out-of-bounds? thread-id p))
        :out-of-bounds))))

(register! "read"
  (assoc (tool read-plain-description
               {"path" {:type "string" :description "File path."}}
               [:path] t-read)
         :park-reason (fence nil)
         :read-only true
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
       " answer says how much was written and where, and that the anchors are gone"
       " -- read the file to get the anchors for what is there now. Writing content"
       " is not knowing its line numbers, so no lines are handed back. "
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
         :park-reason (fence nil)
         :describe write-face))

(register! "edit"
  (assoc (tool "Replace an exact string in a file. Fails if old_string is absent or not unique. A relative path resolves against this session's project directory when one is bound. When bound, a path resolving outside the project directory and the configuration home parks for human approval first."
               {"path"       {:type "string" :description "File path."}
                "old_string" {:type "string" :description "Exact text to replace."}
                "new_string" {:type "string" :description "Replacement text."}}
               [:path :old_string :new_string] t-edit)
         :park-reason (fence nil)))

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
  looked at the same path before this ran (see `fence` and `fenced-path`)."
  [args]
  (replace/perform! kernel-tools/*thread-id* #(project/resolve-path kernel-tools/*thread-id* %) args
                    (editing/editing-mode kernel-tools/*thread-id*)))

(register! "replace"
  (assoc (tool replace-description
               (get replace-params :properties)
               [:remove_from :replacement_lines] anchor-replace)
         ;; The fence has no `path` argument to look at when the model omits it, so
         ;; the target is derived here -- the same derivation the body uses, so the
         ;; call that parks and the call that runs are about the same file.
         :park-reason (fence (fn [thread-id parsed]
                               (replace/target-path thread-id parsed (editing/editing-mode thread-id))))))

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
         ;; Same derivation as `replace`: the fence has no `path` to look at when
         ;; the model omits it, so the target comes from the anchor.
         :park-reason (fence (fn [thread-id parsed]
                               (replace/target-path thread-id parsed (editing/editing-mode thread-id))))))

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
  (undo/perform! kernel-tools/*thread-id* #(project/resolve-path kernel-tools/*thread-id* %) args
                 (editing/editing-mode kernel-tools/*thread-id*)))

(register! "undo_last_replace"
  (assoc (tool undo-description
               {"path" {:type "string"
                        :description (str "The file whose last edit should be taken"
                                          " back.")}}
               [:path] t-undo)
         :park-reason (fence nil)))

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
  "`grep`'s body. The search root is resolved for the session exactly as the
  file tools' paths are, so a relative root means what it means everywhere else."
  [args]
  (grep/perform! kernel-tools/*thread-id* #(project/resolve-path kernel-tools/*thread-id* %) args
                 (editing/editing-mode kernel-tools/*thread-id*)))

(register! "grep"
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
         :park-reason (fence nil)
         :read-only true))

;; ---------------------------------------------------------------------- glob
;;
;; THE OTHER SEARCH QUESTION. `grep` answers "which lines say this"; this
;; answers "which files are named this", and its answer is a list of PATHS rather
;; than anchored rows -- there is no line here to name. So it carries no anchors,
;; belongs to neither editing family, and is served in BOTH modes (see
;; harness.cap.editing/families: a tool named in neither family is served always).

(def ^:private glob-description
  (str "Find files by name, with a glob pattern, and get their paths back -- ready to"
       " hand to `read`. Use this for \"which files are there\" questions; use"
       " `grep` when you are looking for content. "
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
  (glob/perform! kernel-tools/*thread-id* #(project/resolve-path kernel-tools/*thread-id* %) args))

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
         :park-reason (fence nil)
         :read-only true))

(register! "bash"
  (tool (str "Run a shell command (Git Bash on Windows, the host's shell elsewhere) and WAIT for"
             " it. To start one and go do something else, use `job`. "
             "`stdin` is written to the command and then closed, so a command that reads its"
             " input sees EOF rather than waiting for a parent that never types; `workdir` is the"
             " directory to run in (relative paths resolve against this session's project"
             " directory, which is also what a call without `workdir` uses; when no project is"
             " bound that is the process working directory). "
             "`shell` names WHICH SHELL INTERPRETS `command`: " shell-names-text ", or leave it"
             " out for this machine's own. It is NOT 'run the same line somewhere else' --"
             " `command` is a line written FOR the shell you name: `echo %CD%` for cmd,"
             " `Write-Output $env:TEMP` for the two PowerShells, `echo $PWD` for the bash ones."
             " A name this machine does not have is refused by name, and so is a word that is"
             " not one of the above; neither falls back to another shell. "
             "THE COMMAND GETS " bash-default-timeout-ms "ms TO FINISH; `timeout` overrides that,"
             " in milliseconds. When the limit is reached the command is stopped -- together with"
             " everything it started -- and whatever it printed by then comes back, with a line"
             " saying it was stopped. A very large `timeout` means this call really does wait that"
             " long: waiting for a slow command is what this tool is FOR, and how long the command"
             " takes is not the question. "
             "The answer carries at most " jobs/answer-budget-bytes " bytes of what the command"
             " printed -- the tail of it, each stream counted on its own. When there is more, the"
             " whole output is written to a file and the answer says how many bytes are missing"
             " and where that file is.")
        {"command" {:type "string" :description "Command line."}
         "shell"   {:type "string" :enum shell-names
                    :description (str "Which shell interprets `command` -- one of "
                                      shell-names-text ", or left out for this machine's own"
                                      " (Git Bash on Windows, the host's shell elsewhere)."
                                      " `command` has to be written for the shell you name."
                                      " A name this machine does not have, or a word that is"
                                      " not one of the above, is refused rather than falling"
                                      " back to another shell.")}
         "stdin"   {:type "string"
                    :description (str "Text to write to the command's standard input, then close"
                                      " it. Nothing means an empty stream, closed.")}
         "workdir" {:type "string"
                    :description (str "Directory to run in; relative paths resolve against this"
                                      " session's project directory, as they do for `read`. It"
                                      " must be a directory.")}
         "timeout" {:type "integer" :minimum 1
                    :description (str "How long to wait, in milliseconds. Default "
                                      bash-default-timeout-ms ".")}}
        [:command] t-bash))

;; ---------------------------------------------------------------- 后台执行
;;
;; THREE NAMES, ONE ACT EACH: `job` starts, `job_output` reads, `job_kill` stops.
;; Following the editing toolset's precedent (`replace` / `insert` / `undo_last_replace`
;; are three names, not one `edit {action}`): each schema then carries exactly its own
;; arguments, so the seam's missing-argument check answers for every verb, and a refusal
;; belongs to one verb instead of to a branch.
;;
;; THE STARTING VERB IS ITS OWN, and that reverses `.scratch/bash-background` decision 1
;; (which merged it into `bash` as `run_in_background`). One description that has to
;; explain two modes, plus a boolean asking the model 'does this call wait', puts the
;; judgement in the wrong place -- `bash-record`'s session read a `job`/`bash` boundary as
;; 'slow versus fast' and built a `join` out of `sleep`. What the two modes genuinely
;; share -- the working directory, the record, the spawn -- is shared in the BODIES
;; (`work-dir`, `cap.jobs`), not by one face having two halves.
;;
;; THE WORK IS IN harness.cap.jobs. What is here is the faces.

(def ^:private job-description
  (str "Start a shell command in the background and return at once: the answer is a job id"
       " (like `j1`) and how to read what it says -- the command's output is not in it. Use"
       " this when you are going off to do something else; use `bash` when you are going to"
       " wait. "
       "`workdir` is the directory to run in, resolved exactly as `bash` resolves it; `shell`"
       " names WHICH SHELL INTERPRETS `command` -- one of " shell-names-text ", or left out for"
       " this machine's own, and `command` has to be a line written for the shell you name. "
       "A job has NO timeout, and nothing feeds it `stdin`: it runs until it ends or until"
       " `job_kill` stops it. (Neither is an argument of this verb, so there is nothing to"
       " send.) "
       "WHEN IT ENDS YOU ARE TOLD: its ending is put in front of you before your next model"
       " call, so you do not have to remember to ask. Read what it has said, or wait for it,"
       " with `job_output`; stopping it is `job_kill`. "
       "The JOB lives only as long as this harness process; its RECORD does not -- the file stays"
       " in the configuration home, outliving the id that named it."))

(register! "job"
  (tool job-description
        {"command" {:type "string" :description "Command line."}
         "shell"   {:type "string" :enum shell-names
                    :description (str "Which shell interprets `command` -- one of "
                                      shell-names-text ", or left out for this machine's own"
                                      " (Git Bash on Windows, the host's shell elsewhere)."
                                      " `command` has to be written for the shell you name."
                                      " A name this machine does not have, or a word that is"
                                      " not one of the above, is refused rather than falling"
                                      " back to another shell.")}
         "workdir" {:type "string"
                    :description (str "Directory to run in; relative paths resolve against this"
                                      " session's project directory, as they do for `read`. It"
                                      " must be a directory.")}}
        [:command] t-job))

(def ^:private job-kill-description
  (str "Stop a background job -- the command and everything it started. Use it when a job has"
       " done what you needed, has gone wrong, or is holding something you want back (a port, a"
       " file). "
       "The answer says how it went -- `[stopped]` if this call stopped it, or the last line it"
       " had already written -- and how to read what it said. THE RECORD STAYS: its last line is"
       " `[stopped]` or `[exit N]`, and `job_output` reads it before or after stopping. "
       "Stopping does not wait for the process to go -- the answer comes back as soon as the"
       " kill is requested. Asking again is fine and answers the same thing, because a job that"
       " is over is kept until this process ends (the RECORD it left stays after that); only an id"
       " this session never had is refused."))

(defn- t-job-kill
  "`job_kill`'s body: stop it, and answer with how it went -- the record's own last line
  (the same line `job_output` would print) -- and how to read what it said."
  [{:keys [job]}]
  (let [{:keys [id stopped? ending]} (jobs/stop! kernel-tools/*thread-id* job)]
    ;; THREE FACTS: which job, how it went, and how to read what it said. No advice beyond
    ;; that -- the rest lives in this tool's own description, which the model has already read.
    (str "job " id (if stopped? " stopped"
                       (str " was already over" (when ending (str " (" ending ")"))))
         "; " (read-with id))))

(def ^:private job-output-description
  (str "Read what a background job has said, and how it went. "
       "The LAST line of the answer is how it stands: `[exit N]` once the command is gone,"
       " `[stopped]` if it was stopped, `[running]` while it is still going (a job that has"
       " said nothing yet is `[running]` too). Above it is what it has said -- by default the"
       " LAST " jobs/answer-budget-bytes " bytes of it, so a command that has printed thousands"
       " of lines answers with its end rather than its beginning. "
       "`offset` (1-based, a line number of the record) reads from a given line instead, and"
       " `limit` caps how many lines come back; the answer names which lines it showed and how"
       " many there are in all, so a long record can be walked in order. Nothing is ever lost:"
       " when that window is not the whole record, the answer also names the record's path, so"
       " the rest can be `read` / `grep` / `bash`ed instead of paged through. "
       "`wait: true` blocks until the command is over -- or until `timeout` (default "
       jobs/job-output-default-timeout-ms "ms) runs out, and that is not an error: the answer"
       " is `[running]` with whatever the command has said so far. A job that ends while you"
       " are busy is announced to you before your next model call (see `job`), and that"
       " announcement is what names this verb -- so `wait` is for standing still and waiting"
       " for it now. "
       "A job that is over still answers -- but only inside the process that started it: an"
       " id does not survive a restart, though the record it left is still a file on disk. A"
       " record this call cannot reach is therefore not a record that is gone."))

(defn- t-job-output
  "`job_output`'s body: the facts harness.cap.jobs reads off the record, as the answer a
  model reads. The arithmetic -- which lines, what status -- is the module's; what is here
  is the shape.

  THE PATH COMES BACK WHEN THE WINDOW IS NOT THE WHOLE RECORD. A `bash` answer says where
  the rest of an over-budget output is (`jobs/truncation-line`); this is the same fact for a
  record, and the same moment: a window that leaves something out is exactly when the reader
  can use the file (`read` / `grep` / `bash`). When the window IS the record, there is
  nothing beyond it to name, and the answer stays as short as it was."
  [{:keys [job offset limit wait timeout]}]
  (let [{:keys [status lines from to total path]}
        (jobs/output kernel-tools/*thread-id* job
                     {:offset  (positive-int :offset offset)
                      :limit   (positive-int :limit limit)
                      :wait    wait
                      :timeout (positive-int :timeout timeout)})]
    (str (cond
           ;; THE TWO EMPTY CASES ARE DIFFERENT FACTS, and a reader can tell them
           ;; apart: a job that has not printed is not a job whose lines the reader
           ;; asked for by a number that is past the end of them.
           (seq lines)     (str (str/join "\n" lines) "\n")
           ;; `bash`'s words for a command that said nothing, so a model that has
           ;; seen one of these has seen the other.
           (zero? total)   "(no output)\n"
           :else           "(no lines in that range)\n")
         ;; THE RANGE/PATH LINE IS PART OF THE WINDOW'S OWN SENTENCE -- it is what this answer is
         ;; a window OF -- so it stays with the body, above the ending. Putting it under the status
         ;; would leave the answer ending on "…the whole record is /path]" and nowhere saying how
         ;; the command went.
         (when (and (seq lines) (or (> from 1) (< to total)))
           (str "[" total " lines in all; this answer shows lines " from "-" to
                "; the whole record is " path "]\n"))
         ;; THE ENDING IS THE LAST LINE, matching the record it was read from (whose own last line
         ;; this is) and matching `bash` (whose answer ends the same way). It was the FIRST line
         ;; until `.scratch/readback-verbs` ticket 03: a reader that wanted to know how the command
         ;; went had to scroll past the answer to find it, and a reader that skimmed the top read
         ;; the status as the command's first output line.
         status)))

(register! "job_kill"
  (tool job-kill-description
        {"job" {:type "string" :description "Job id, as `job` answered."}}
        [:job] t-job-kill))

(register! "job_output"
  (tool job-output-description
        {"job"     {:type "string" :description "Job id, as `job` answered."}
         "offset"  {:type "integer" :minimum 1
                    :description (str "Line number of the record to read from (1-based)."
                                      " Default: the tail of it, within "
                                      jobs/answer-budget-bytes " bytes.")}
         "limit"   {:type "integer" :minimum 1
                    :description "How many lines to return at most."}
         "wait"    {:type "boolean"
                    :description (str "Wait for the command to finish (or for `timeout`) before"
                                      " answering. Default false: answer now.")}
         "timeout" {:type "integer" :minimum 1
                    :description (str "How long `wait` waits, in milliseconds. Default "
                                      jobs/job-output-default-timeout-ms ".")}}
        [:job] t-job-output))

;; -------------------------------------------------------------------- job_list
;;
;; THE FAMILY'S FOURTH HAND, and the first whose subject is not a single id: `job` starts one,
;; `job_output` reads one, `job_kill` stops one -- all three ADDRESS an id the model already
;; holds -- while a model that has lost it (a compaction, a restart, a turn that scrolled away)
;; has nothing to address at all. This is the door back to it, and it opens on two things at
;; once: the jobs this process is still holding, and the records earlier runs left in the same
;; directory.

(def ^:private job-list-description
  (str "List this session's background jobs and the records earlier runs left behind, one row"
       " per RECORD: the id, which run it was (`this run`, or the stamp in its filename), how it"
       " went, and where the record is -- plus the command, for a job THIS process still holds. "
       "Ids beginning with `j` are JOBS (something to read, wait for, stop); ids beginning with"
       " `c` are records a FOREGROUND call spilled (`bash` keeps one when its output did not fit"
       " the answer) -- they have no job behind them, so nothing can be stopped and no id can be"
       " addressed, and the row says which kind it is. "
       "A row without its command says so rather than leaving a blank: a record holds what the"
       " command SAID, and only this process's own entry ever had the command itself. "
       "The status is the record's own last line (`[exit N]` / `[stopped]`), or `[running]`"
       " while it is still being written -- the same words `job_output` prints, from the same"
       " place -- `[running]` while the process that wrote the record is still there, and `[exit ?]`"
       "Jobs that are still RUNNING come first, then the rest newest first; at most "
       jobs/max-listed-records " rows are drawn, and the answer says how many it left out. "
       "READ-ONLY: nothing here deletes, moves or rewrites a record. Use it when you do not have"
       " an id in hand; use `job_output` once you do."))

(defn- record-row
  "One row of `job_list`, as the line a model reads.

  THE TWO KINDS OF ID SHARE ONE COLUMN and the row does not repeat which is which: the prefix
  already says it (`j*` a job, `c*` a foreground record), and one spelling of that is enough.

  THE RUN IS NAMED FOR WHAT IT IS: this process's stamp reads `this run`, because the raw stamp
  (`20260924T193221314-5376`) is a filename fragment whose only job is to be unique. ANOTHER
  run's stamp is printed raw, because a reader who wants to go looking in that directory has
  nothing else to go on."
  [row]
  (str (:id row)
       " · " (if (:this-run? row) "this run" (:run row))
       " · " (:status row)
       " · " (if-let [command (:command row)]
               (jobs/command-line command)
               "(no command kept -- a record holds what it said)")
       " · " (:path row)))

(defn- t-job-list
  "`job_list`'s body: the rows harness.cap.jobs reads off the session's own directory, as the
  answer a model reads. Which files, in which order, and what each one's status is are that
  module's; what is here is the shape.

  IT IS THE ONE ANSWER IN THIS FAMILY THAT ADDRESSES NO ID, and that is its whole reason to
  exist: the other three hands all need one, and a model that has just been born into a session
  (a restart, a compaction) has none."
  [_]
  (let [rows (jobs/records-for kernel-tools/*thread-id*)]
    (if (empty? rows)
      jobs/no-jobs-line
      (let [shown   (vec (take jobs/max-listed-records rows))
            hidden  (- (count rows) (count shown))
            running (count (filter :running? rows))
            ;; THE CAP SAYS SO, AND SAYS WHERE TO LOOK: a listing that stopped at fifty rows
            ;; without a word would be a listing that lies about how much there is.
            more    (when (pos? hidden)
                      (str "\n(" hidden " more not listed; the whole directory is "
                           (jobs/records-dir kernel-tools/*thread-id*) ")"))]
        (str (str/join "\n" (map record-row shown))
             "\n"
             (count rows) " record" (when (not= 1 (count rows)) "s")
             ": " running " still running, " (- (count rows) running) " ended."
             more)))))

(register! "job_list"
  ;; NO PARAMETERS, said rather than left out: the question is what this session HAS, and there
  ;; is no second way to ask it -- `todo_read`'s shape, and for the same reason.
  (tool job-list-description {} [] t-job-list))

(register! "eval"
  (tool "Evaluate Clojure in this process. Defs persist across calls."
        {"code" {:type "string" :description "Clojure source."}}
        [:code] t-eval))

;; The skills this session can load, by name, are announced in the run's opening
;; messages (harness.cap.preamble) -- one line each, with the description their own
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

;; --------------------------------------------------------- todo_write / todo_read
;;
;; The session's task list, and the only tool here whose subject is the run rather
;; than the tree. It belongs to NEITHER editing family (it touches no file), so it
;; is served in both modes.
;;
;; The list REPLACES rather than appends, which is what makes it state -- and what
;; makes two calls in one message meaningless. `sole-call-of-its-name?` is the
;; check, and it is the seam's own turn plan rather than anything tool-specific:
;; the run loop is the only place that sees a whole message (see `plan-turn`).
;;
;; TWO HANDS, ONE LIST. `todo_write` replaces the list and `todo_read` hands it
;; back; both exist because the list lives in the STORE rather than in the
;; conversation -- a compressed context, a later process and an eval all read the
;; same row, so a model that no longer has the list in front of it needs a way back
;; to it. The rendering and the refusal for a call with no session in scope are
;; harness.cap.todos's (beside `items-for`, where the list is read from), so
;; `todo_read`'s body is one line the same way this one's rules are not here either.

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
       " run. The answer says how many items are stored and how they stand -- the"
       " list itself is what you just sent, so it is not repeated back. "
       "Call this at most ONCE per message: a list is replaced whole, so two"
       " calls in one message have nothing to merge and NEITHER is applied."))

(declare sole-call-of-its-name?)

(defn- t-todo-write
  "`todo_write`'s body. Its rules live in harness.cap.todos (one place, shared with any
  other caller); the one thing here is the per-MESSAGE rule, because this is the
  layer that can see a whole message.

  The argument is read out of ARGS rather than destructured: the natural binding
  name for it is `todos`, which is what this namespace calls harness.cap.todos."
  [args]
  (when-not (kernel-tools/sole-call-of-its-name? "todo_write")
    (throw (ex-info (str "this message holds more than one todo_write. A task list is"
                         " replaced WHOLE, so two calls in one message have nothing to"
                         " merge -- neither was applied. Send the list once, and the"
                         " next update in a later message.")
                    {:reason :second-todo-write-in-turn})))
  (todos/render (todos/write! kernel-tools/*thread-id* (:todos args))))

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

(def ^:private todo-read-description
  (str "Read this session's task list back -- the items you are working through, in"
       " order, with the state of each one. Use it when the list is no longer in"
       " front of you: it belongs to the session and is stored with it, so it"
       " outlives the run that wrote it and a later call, another process or an"
       " eval can hand it back. "
       "No arguments: the list is this session's, and there is no second way to ask. "
       "The answer is one line per item, then one sentence with how many there are"
       " and how they stand. A session that never wrote a list and a session that"
       " cleared it get the same answer."))

(defn- t-todo-read
  "`todo_read`'s body. The answer -- and the refusal for a call with no session in
  scope -- is harness.cap.todos's, beside `items-for`, which is where the list is
  read from in the first place."
  [_]
  (todos/read-back kernel-tools/*thread-id*))

(register! "todo_read"
  ;; NO PARAMETERS, and said rather than left out: the list belongs to the session, so
  ;; there is no second way to ask for it. `{}` and `[]` are what a schema with
  ;; nothing to declare looks like -- the tool takes an empty object of arguments.
  (tool todo-read-description {} [] t-todo-read))

;; ------------------------------------------------------------------ web_fetch
;;
;; The only tool here whose subject is neither the tree nor the run: it leaves the
;; machine. NOT MARKED :requires-approval, and that is a decision rather than an
;; oversight -- `bash` reaches the network today with no gate at all, so a park on
;; this one would be a speed bump that reads as a wall. A session that wants the
;; gate installs one (see harness.cap.web's docstring).

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
  harness.cap.web's -- this is the tool's face, not a second implementation of them."
  [{:keys [url]}]
  (web/fetch-text url))

(register! "web_fetch"
  (assoc (tool web-fetch-description
               {"url" {:type "string"
                       :description (str "The full address to fetch, including the https://"
                                         " prefix.")}}
               [:url] t-web-fetch)
         ;; It leaves the machine, which is why it carries no approval gate (see
         ;; above) -- but it changes nothing anywhere, which is why a read-only
         ;; range includes it: an exploring subagent that cannot look up a
         ;; document is a subagent doing half the job.
         :read-only true))

;; ----------------------------------------------------------------- web_search
;;
;; The other half of reading the web: `web_fetch` reads a URL you already have, this
;; finds one. Its destination is fixed by harness.cap.web.search, not chosen per call by
;; the model -- see that namespace for why neither of them is marked for approval.

(def ^:private web-search-description
  (str "Search the web and get a few results back -- a title, a URL and a snippet"
       " for each. Read one of them with `web_fetch`. "
       "Use it when you do not already know the address; when you do, `web_fetch` is"
       " one call instead of two. "
       "Needs a search key: " (str/join ", " search/key-vars) " are looked for in the"
       " configuration home's .env and then in the environment, and the FIRST one set"
       " is the vendor that answers (" (first search/key-vars) " wins if several are)."
       " With none of them the call is refused by name and nothing else breaks. "
       "`count` defaults to " search/default-count " and may be at most "
       search/max-count "."))

(defn- t-web-search
  "`web_search`'s body. The vendors' wires -- request, key, response -- are
  harness.cap.web.search's; this is the tool's face."
  [args]
  (search/perform args))

(register! "web_search"
  (assoc (tool web-search-description
               {"query" {:type "string" :description "What to search for."}
                "count" {:type "integer" :minimum 1
                         :description (str "How many results to ask for (default "
                                           search/default-count ", at most "
                                           search/max-count ").")}}
               [:query] t-web-search)
         :read-only true))

;; ----------------------------------------------------------------------- ask
;;
;; THE ONE TOOL WHOSE PURPOSE IS TO STOP. Every other tool here does something and
;; then answers; this one answers by NOT running -- it parks the call on a question
;; and the person's answer arrives as this call's result on the way back in. The
;; machinery is the elicitation chain that harness.cap.mcp already drives for a
;; server's `elicitation/create`, which is why none of it is new: `suspend!` parks
;; under :reason :elicitation, `GET /api/elicitation` hands the client the question
;; and its shape, and `resume` carries the answers.
;;
;; NOT MARKED :requires-approval, AND THAT IS THE DECISION RATHER THAN AN
;; OVERSIGHT. Approving is something a person does TO a call that was going to run
;; anyway; here the person is the one being asked, and the park IS the feature --
;; a fence in front of it would ask somebody to approve asking them. It follows
;; that this tool never reaches the seam's :approved/:vetoed arm, and so it takes
;; its own decision exactly the way cap.mcp does (see t-ask).
;;
;; ONE CALL, A LIST OF QUESTIONS. The run stops once and the person answers
;; everything in front of them; a tool that asked one question per call would make
;; a conversation out of a form.
;;
;; A QUESTION MAY OFFER CANDIDATES. `options` becomes an `enum` on the property, so
;; the card draws a choice with the client's EXISTING enum rule -- this tool does not
;; invent a second way to draw one, and a question with no candidates is a text box
;; exactly as before. `multiple` makes it a multi-select (an `array` of that enum);
;; `allow_other` adds "or type your own" as a way out of a list that does not happen
;; to contain their answer.
;;
;; THE "OR TYPE YOUR OWN" SWITCH IS EXPLICIT RATHER THAN ASSUMED, and that is the
;; one place the same field rules serve two masters. The card draws a server's form
;; from the server's schema too (harness.cap.mcp), and growing an extra input under
;; every enum somebody else declared would be this client editing their question. So
;; the permission rides on the schema as `x-allow-other` -- a key no server writes --
;; and a schema without it renders exactly what it renders today.

(def ^:private ask-description
  (str "Ask the person one or more questions, and get their answers back as this "
       "call's result. "
       "The call PARKS the run until they answer, so put everything you need into "
       "ONE call rather than asking in a series of single-question calls. "
       "Use it for something only they can tell you -- a fact, a preference, a "
       "choice between approaches -- and not for anything you can find out "
       "yourself. "
       "One entry per question: `question` is the sentence they read, and `key` "
       "names that answer. Keep the key stable, so the same question asked later "
       "carries the same one. "
       "`options` offers a closed list to pick from; add `allow_other` when they may "
       "need an answer that is not on it, and `multiple` when the answer may be "
       "several of them. "
       "Write the question in the language the <env> block names, so the person "
       "reads it in their own language. "
       "What comes back is one line per question. A question they left blank comes "
       "back as \"(no answer)\", a multiple-choice question they ticked nothing on "
       "comes back as \"(nothing chosen)\", and a person who declines the form "
       "altogether comes back as a refusal. None of them is an error, and none "
       "means the run failed."))

(defn- ask-options
  "ENTRY's `options`, as the candidates in the order the model wrote them -- or nil
  when the question offers none.

  VERBATIM, because a candidate is the model's own word for a thing and the answer
  is compared against it: a space in \"Hong Kong branch\" or a comma in \"a, b\" is
  part of the word, and trimming or reordering here would put a DIFFERENT string on
  the card than the one an answer will be matched to. Only the element's TYPE is
  normalised (`str`), because a model may write `1` where it means the string \"1\".

  A LIST NOBODY COULD PICK FROM IS REFUSED HERE rather than drawn: an empty one, or
  one carrying a blank candidate, is a form with a line on it that cannot be
  answered -- and the person is who would find that out."
  [i m]
  (when (some? (:options m))
    (let [raw (:options m)]
      (when-not (sequential? raw)
        (throw (ex-info (str "ask's question " (inc i) " writes `options` as something"
                             " other than a list. Give the candidates as a list of"
                             " strings.")
                        {:reason :options-not-a-list :index i})))
      (let [os (mapv str raw)]
        (when (empty? os)
          (throw (ex-info (str "ask's question " (inc i) " offers an empty list of"
                               " options, which is nothing to pick from. Leave"
                               " `options` out to ask it as a written answer.")
                          {:reason :empty-options :index i})))
        (when-let [blank (first (filter str/blank? os))]
          (throw (ex-info (str "ask's question " (inc i) " offers a blank candidate,"
                               " which nobody could pick. Every entry of `options`"
                               " has to say what it is.")
                          {:reason :blank-option :index i :option blank})))
        os))))

(defn- ask-question
  "One entry of `questions`, as {:key .. :question .. :options .. :multiple ..}.

  A BARE STRING IS ACCEPTED as the question with no key, because a model reaches
  for that shape when there is only one thing to ask, and the key it would have
  written is `q1` anyway. A MISSING KEY IS DERIVED from the entry's position, for
  the same reason: refusing a call whose meaning was never in doubt would cost a
  person nothing and the model a round trip. A MISSING SENTENCE IS REFUSED -- there
  would be nothing to put on the card, and a key is not a question.

  `multiple` WITHOUT `options` IS REFUSED, and the reason is that the question would
  have no shape: a multi-select is \"which of these\", and with nothing to choose
  between, what the model meant is not recoverable from what it wrote -- \"list the
  hosts\" is a written answer whose answer happens to have newlines in it, not a row
  of tick boxes. `allow_other` without `options` is NOT refused: a written answer
  already takes anything, so the form that comes out is the one that was asked for
  and the switch is merely redundant.

  BOTH SWITCHES ARE READ AS THE OBVIOUS BOOLEAN rather than refused for a non-boolean
  truthy: a model that wrote `\"multiple\": \"true\"` meant one thing by it, and
  costing a person the difference over the spelling would be pedantry with a price."
  [i q]
  (let [m           (if (map? q) q {:question q})
        question    (some-> (:question m) str str/trim)
        key         (some-> (:key m) str str/trim)
        options     (ask-options i m)
        multiple    (boolean (:multiple m))]
    (when (str/blank? question)
      (throw (ex-info (str "ask's question " (inc i) " carries no sentence to put on"
                           " the card. Each entry needs a `question`.")
                      {:reason :question-without-a-sentence :index i})))
    (when (and multiple (nil? options))
      (throw (ex-info (str "ask's question " (inc i) " asks for several answers but"
                           " offers no `options` to choose them from. Selecting"
                           " several needs a list to select from; leave `multiple`"
                           " out to ask for a written answer.")
                      {:reason :multiple-without-options :index i})))
    {:key         (if (str/blank? key) (str "q" (inc i)) key)
     :question    question
     :options     options
     :multiple    multiple
     :allow-other (and (some? options) (boolean (:allow_other m)))}))

(defn- ask-questions
  "ARGS' question list, as [{:key .. :question ..} ..], in the order asked.

  IT REFUSES BEFORE ANYBODY IS ASKED. A form nobody can answer correctly -- nothing
  in it, or two questions that would come back under one key -- is a wasted
  interruption, so it is refused here while the cost is still the model's, not a
  person's."
  [args]
  (let [raw (or (:questions args) [])]
    (when-not (sequential? raw)
      (throw (ex-info "ask takes `questions` as a LIST of {key, question} entries."
                      {:reason :questions-not-a-list})))
    (let [qs (vec (map-indexed ask-question raw))]
      (when (empty? qs)
        (throw (ex-info "ask needs at least one question." {:reason :ask-with-no-questions})))
      (let [dupes (->> qs (map :key) frequencies
                       (keep (fn [[k n]] (when (> n 1) k))) sort)]
        (when (seq dupes)
          (throw (ex-info (str "ask needs one key per question, and these repeat: "
                               (str/join ", " dupes)
                               ". The key is what an answer comes back under, so two"
                               " questions sharing one would lose an answer.")
                          {:reason :duplicate-ask-keys :keys dupes}))))
      qs)))

(defn- ask-property
  "ONE question as the schema property that draws it.

  THREE SHAPES, and every one of them is a shape the client's field rules already
  know -- this tool does not invent a way to draw a choice. A written answer is
  `{\"type\" \"string\"}`, byte for byte what this tool has always sent and what a
  server's own free-text field is. A choice is that same string carrying an `enum`
  of the candidates, which those rules draw as a select. Several choices are an
  `array` of that enum, which they draw as a row of tick boxes.

  `x-allow-other` IS AN EXTENSION RATHER THAN JSON SCHEMA, and it is there because
  the permission is a property of the FORM and the standard has no word for it. It
  rides on the property (where the client reads it) and only on a property that HAS
  candidates: a written answer needs nobody's permission to be written, so a schema
  for one never carries the key -- which is what keeps a server's own elicitation
  rendering exactly what it rendered before this existed."
  [{:keys [question options multiple allow-other]}]
  (let [typed (if multiple
                {:type "array" :items {:type "string" :enum options}}
                (cond-> {:type "string"} options (assoc :enum options)))]
    (cond-> (assoc typed :description question)
      allow-other (assoc :x-allow-other true))))

(defn- ask-schema
  "The questions as the JSON Schema the client draws.

  THE KEY IS THE PROPERTY NAME, because an answer comes back as one map keyed by
  property name -- that is the whole of what the client's form rules do -- and this
  tool is the side that matches an answer to the question that asked for it. The
  sentence rides as the description, which is where a card puts it.

  An ARRAY MAP so the fields follow the order they were asked in, up to the size at
  which Clojure promotes a small map to a hash map. Past that the order is the
  client's; nothing here depends on it."
  [questions]
  {:type "object"
   :properties (into (array-map) (map (juxt :key ask-property)) questions)})

(defn- ask-prompt
  "The question face as the one line an interrupt carries: every question, joined.

  THE LINE IS THE INTERRUPT'S OWN, so a client that never fetches the schema still
  has the questions in front of it -- and for the ordinary one-question call it is
  that question verbatim. Joined rather than stacked because a card renders this as
  one line, and a newline in it would be a break nobody sees."
  [questions]
  (str/join " / " (map :question questions)))

(defn- answer-for
  "The value this question came back with, or nil.

  TOLERANT IN TWO DIRECTIONS, and the second one is the difference between a wrong
  answer and no answer at all. The payload's keys are the CLIENT's -- this harness
  parses the request with keyword keys, so a keyword is what is usually there, and a
  client that did it differently is still answering. And a key no keyword can be
  made from is looked up as the string it is rather than thrown on: the person has
  already filled the form in by the time this runs, and losing their answer to a
  lookup's opinion of a legal name would be the worst failure here."
  [answers key]
  (let [kw (try (keyword key) (catch Throwable _ nil))]
    (or (get answers kw) (get answers key))))

(def ^:private no-answer-label "(no answer)")

(def ^:private nothing-chosen-label "(nothing chosen)")

(defn- chosen-words
  "One picked item as words, or nil when there is nothing worth saying."
  [item]
  (let [t (str/trim (str item))]
    (when-not (str/blank? t) t)))

(defn- answer-body
  "ONE answer as the words the model reads.

  THREE STATES, AND THEY ARE THREE DIFFERENT FACTS: an answer; an EMPTY collection,
  which says \"none of these\" and is a real answer to a question that offered
  choices; and nothing at all, which is the person leaving the line alone. The last
  two are the pair a model cannot reconstruct by itself, so they are told apart
  rather than collapsed into one silence -- each is something to act on that the
  other is not.

  A COLLECTION COMES BACK AS ITS ITEMS, comma-separated: the question was \"which
  ones\" and what the model should get is the ones. Nothing here ever BUILDS a
  joined string for the wire, though -- the picks crossed as a list because an
  option may itself contain a comma (see the schema), and joining is only how the
  answer is said out loud at the end."
  [answer]
  (cond
    (sequential? answer) (if-let [items (seq (keep chosen-words answer))]
                           (str/join ", " items)
                           nothing-chosen-label)

    (string? answer)     (or (chosen-words answer) no-answer-label)

    (nil? answer)        no-answer-label

    :else                (str answer)))

(defn- answer-lines
  "The answers as what the model reads: one line per question, in the order asked.

  NO JSON DUMP. The model wrote the questions and is about to act on the answers, so
  the matching is done here rather than handed over as a map for it to redo. A
  question nobody answered SAYS SO rather than going missing, so 'they skipped it'
  and 'the answer was empty' are the same fact stated once instead of inferred from
  an absence."
  [questions answers]
  (str/join "\n"
            (map (fn [{:keys [key question]}]
                   (str "- " question " -> " (answer-body (answer-for answers key))))
                 questions)))

(defn- t-ask
  "`ask`'s body: park the call on its questions, or -- on the way back in -- hand
  the person's answers to the model.

  THE DECISION IS TAKEN HERE, the way harness.cap.mcp takes an MCP server's. The
  seam's own :approved/:vetoed arm belongs to calls that were PARKED FOR APPROVAL,
  and this tool is never one of them -- it carries no :requires-approval and no
  :park-reason, so `approval-reason` answers nil for it and that arm is unreachable.
  Asking the parked record directly is therefore not a shortcut past the seam; it is
  the only path this call has.

  TAKEN, NOT READ. `take-decision!` hands a decision over exactly once, so a replay
  of the same interrupt cannot answer the same call twice: the second transit finds
  nothing, parks again, and the run stops on the same question rather than carrying
  on with an answer nobody gave twice.

  A REFUSAL IS AN ANSWER. `:vetoed` is a person declining the form, which is a thing
  a model can act on -- it does not fail the call, and it does not read as a broken
  tool. That is the same judgment cap.mcp makes when it folds a human's no into the
  `decline` it sends a server."
  [args]
  (let [questions (ask-questions args)
        rec       (kernel-tools/parked-for-call kernel-tools/*thread-id*
                                                kernel-tools/*tool-call-id*)
        decision  (when rec (kernel-tools/take-decision! (:interrupt-id rec)))]
    (case (:verdict decision)
      :approved (answer-lines questions (:payload decision))
      :vetoed   "The person declined to answer."
      (kernel-tools/suspend! kernel-tools/*thread-id* kernel-tools/*tool-call-id*
                             {:asked-by :model
                              :prompt   (ask-prompt questions)
                              :schema   (ask-schema questions)}))))

(register! "ask"
  (tool ask-description
        {"questions" {:type "array" :minItems 1
                      :description (str "The questions to put to the person, in the order"
                                        " they should read them.")
                      :items {:type "object"
                              :properties {"key" {:type "string"
                                                  :description (str "A short, stable name"
                                                                    " for this answer. The"
                                                                    " same question asked"
                                                                    " again carries the"
                                                                    " same key.")}
                                           "question" {:type "string"
                                                       :description (str "The sentence the"
                                                                         " person reads: one"
                                                                         " question, asked"
                                                                         " plainly.")}
                                           "options" {:type "array"
                                                      :items {:type "string"}
                                                      :description (str "The answers to choose"
                                                                        " from, when the answer"
                                                                        " is one of a known set."
                                                                        " Without it the question"
                                                                        " is a box they type"
                                                                        " into. Leave the list"
                                                                        " out rather than"
                                                                        " guessing at it, and"
                                                                        " give each candidate"
                                                                        " exactly as the answer"
                                                                        " should come back.")}
                                           "multiple" {:type "boolean"
                                                       :description (str "True when they may pick"
                                                                         " more than one of"
                                                                         " `options`. Needs"
                                                                         " `options`.")}
                                           "allow_other" {:type "boolean"
                                                          :description (str "True when they may"
                                                                            " answer in their own"
                                                                            " words instead of"
                                                                            " picking, for a"
                                                                            " candidate the list"
                                                                            " does not have. Needs"
                                                                            " `options` -- an"
                                                                            " answer they type is"
                                                                            " already what a"
                                                                            " question without"
                                                                            " them is.")}}
                              :required ["key" "question"]}}}
        [:questions] t-ask))

;; ------------------------------------------------------------------ installing

(defn install!
  "Put this harness's built-in tools into the seam, and answer the TEARDOWN that
  takes them out again.

  Name it, so a refusal can say who switched something off: the contribution
  carries `:name` (\"built-ins\" here) and harness.kernel.tools keeps it beside
  the layer. A switch-off whose only attribution was a UUID would be a refusal
  nobody could act on.

  NAMED FOR WHAT IT IS, not for where it is called. The composition root calls it
  at setup and keeps the teardown for shutdown; a test calls it in a fixture and
  calls the teardown afterwards, which is the whole reason the door returns one."
  []
  ;; THE PLANNER COMES WITH THE TOOLS, because it is about THEM: the one-commit
  ;; batch exists to stop two `replace` calls in one message from losing data, and
  ;; it is `harness.cap.hashline.replace` that knows how to fold them. The seam
  ;; installs it through the same door as the definitions, so one setup call is
  ;; still the whole wiring, and `teardown` withdraws both.
  ;; ...AND SO DOES THE NARROWING POLICY, for the same reason: it is about these
  ;; tools. harness.cap.editing decides which names a session is served (the two
  ;; editing schemes cannot both be in one toolset) and what to tell the model when
  ;; one is not served. The seam asks; it does not know there are two schemes.
  (kernel-tools/install! {:name "built-ins"
                          :tools @built-ins
                          :planner replace/plan-turn
                          :narrow {:served? editing/served?
                                   :refuse  editing/unserved-message}}))
