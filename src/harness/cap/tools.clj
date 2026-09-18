(ns harness.cap.tools
  "The eighteen tools this harness ships: their bodies, their faces, and nothing
  else. It is a CAPABILITY, so it lives here and not in harness.kernel.tools --
  which holds the seam that runs a tool, not any particular tool.

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
            [harness.cap.providers :as providers]
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
  origins carry their own (an external server's say :mcp)."
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
  that are gone -- so the write RELEASES them, clears the file's undo record, and
  hands back anchored rows for what it wrote (harness.cap.hashline.write). In
  str-replace mode none of that exists and this is the write it always was."
  [{:keys [path content] :as args}]
  (if (= :hashline (:mode (editing/editing-mode kernel-tools/*thread-id*)))
    (hashline-write/perform! kernel-tools/*thread-id* #(project/resolve-path kernel-tools/*thread-id* %) args
                             (editing/editing-mode kernel-tools/*thread-id*))
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

(defn- t-bash
  "`bash`'s body: one command, a bounded wait, and the answer.

  THE WAIT IS THE POINT. This used to go through `infra.shell/shell`
  (`clojure.java.shell/sh`), which has no timeout at all -- so a hung command hung
  the whole run, forever. `infra.shell/run` is the timeout-shaped one, and it
  stops the command TOGETHER WITH EVERYTHING IT STARTED, which is the half that
  matters: what a hung `npm test` leaves behind is a child of the shell, not the
  shell.

  A timeout is not an error: the command's own output is returned, with the limit
  appended in the same shape a non-zero exit gets. The model learns what happened
  and can try something narrower -- the same judgement `cap.git` makes about a git
  that hung ('a fact the caller may want to report')."
  [{:keys [command timeout]}]
  (let [dir   (project/binding-for kernel-tools/*thread-id*)
        limit (or (positive-int :timeout timeout) bash-default-timeout-ms)
        {:keys [exit out err] stopped :timeout} (shell/run {:command command
                                                            :dir (when dir dir)
                                                            :timeout-ms limit})
        body  (str out err)]
    (str (if (str/blank? body) "(no output)" body)
         (cond
           ;; A process that never finished has no exit code -- the limit is the
           ;; fact, and `exit` is nil here, so a `(zero? exit)` would throw.
           stopped           (str "\n[timed out after " limit "ms — the command was stopped]")
           (not (zero? exit)) (str "\n[exit " exit "]")))))

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
  boundary: eval can still reach harness.cap.providers/use-provider! directly, and bash
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
                           " declared in config.edn's :providers, not chosen per session")
                      {:unknown (vec extras)}))))
  (let [{:keys [provider model reasoning-effort]} args
        thread-id kernel-tools/*thread-id*
        change    (cond-> {}
                    (some? provider)         (assoc :provider provider)
                    (some? model)            (assoc :model model)
                    (some? reasoning-effort) (assoc :reasoning-effort reasoning-effort))]
    (when (empty? change)
      (throw (ex-info "nothing to change: give at least one of provider, model, reasoning-effort" {})))
    ;; ONE ATOM OPERATION, and it answers the transition it made. Reading the tier here
    ;; and writing it back would lose the change another thread made in between -- the
    ;; model endpoint writes this same tier -- and the change line below would then
    ;; record a before->after pair that never happened.
    (let [{:keys [before after resolved]} (providers/swap-override! thread-id change)]
      (providers/record-provider-change! thread-id before after "session-configure"
                                   after resolved)
      (str "session reconfigured: " (pr-str change)
           " -- effective now for this thread only."
           (when-let [m (:model resolved)] (str " Serving " m "."))
           (when (nil? thread-id)
             " (warning: no session in scope; the change landed on the process-wide slot)")))))

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
  "`anchor_grep`'s body. The search root is resolved for the session exactly as the
  file tools' paths are, so a relative root means what it means everywhere else."
  [args]
  (grep/perform! kernel-tools/*thread-id* #(project/resolve-path kernel-tools/*thread-id* %) args
                 (editing/editing-mode kernel-tools/*thread-id*)))

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
         :park-reason (fence nil)))

;; ---------------------------------------------------------------------- glob
;;
;; THE OTHER SEARCH QUESTION. `anchor_grep` answers "which lines say this"; this
;; answers "which files are named this", and its answer is a list of PATHS rather
;; than anchored rows -- there is no line here to name. So it carries no anchors,
;; belongs to neither editing family, and is served in BOTH modes (see
;; harness.cap.editing/families: a tool named in neither family is served always).

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
         :park-reason (fence nil)))

(register! "bash"
  (tool (str "Run a shell command (Git Bash on Windows, the host's shell elsewhere). The working"
             " directory is this session's project directory when one is bound, otherwise the"
             " process working directory. "
             "The command gets " bash-default-timeout-ms "ms to finish; `timeout` overrides that,"
             " in milliseconds. When the limit is reached the command is stopped -- together with"
             " everything it started -- and whatever it printed by then comes back, with a line"
             " saying it was stopped. A very large `timeout` means this run really does wait that"
             " long; for something that has to outlive the call, use `bash_background` instead.")
        {"command" {:type "string" :description "Command line."}
         "timeout" {:type "integer" :minimum 1
                    :description (str "How long to wait, in milliseconds. Default "
                                      bash-default-timeout-ms ".")}}
        [:command] t-bash))

;; ---------------------------------------------------------------- 后台执行
;;
;; THREE NAMES RATHER THAN ONE TOOL WITH AN `action`, following the editing
;; toolset's precedent (`replace` / `insert` / `undo_last_replace` are three names,
;; not one `edit {action}`): each schema then carries exactly its own arguments, so
;; the seam's missing-argument check answers for every verb, and a refusal belongs
;; to one verb instead of to a branch. The cost is three descriptions in every
;; request, which is the price of that clarity.
;;
;; THE WORK IS IN harness.cap.jobs. What is here is the faces.

(def ^:private bash-background-description
  (str "Run a shell command in the BACKGROUND: this call returns as soon as the command has"
       " started, and the command keeps running. Use it for something that has to outlive the"
       " call -- a dev server, a watcher, a slow test or build -- so you can carry on working"
       " while it runs. "
       "The answer is a job id (like `j1`); the command's output is NOT in it. "
       "NOTHING TELLS YOU WHEN IT FINISHES OR WHEN IT PRINTS SOMETHING: read it with"
       " `bash_output`, stop it with `bash_kill`. "
       "A job has NO timeout -- it runs until it ends or until it is stopped -- and it lives"
       " only as long as this harness process. "
       "The working directory is this session's project directory when one is bound,"
       " otherwise the process working directory, exactly as `bash`."))

(def ^:private bash-output-description
  (str "Read what a background job has printed SINCE YOU LAST READ IT, and whether it is still"
       " running. The answer is the new lines, plus one status line: `[running]`, or `[exit N]`"
       " once the command has ended. "
       "READING DOES NOT WAIT: `(no new output)` means there is nothing new right now, not that"
       " the job is finished -- the status line says which, and nothing will interrupt you when"
       " it changes. "
       "Only the last " jobs/tail-lines " lines are kept, so a job that printed more than you"
       " read says how many lines you missed."))

(defn- t-bash-background
  "`bash_background`'s body: hand the command to harness.cap.jobs and answer its id.
  The directory is resolved exactly as `bash`'s is, so a relative command means the
  same place in both."
  [{:keys [command]}]
  (let [dir (project/binding-for kernel-tools/*thread-id*)
        id  (jobs/start! kernel-tools/*thread-id* {:command command :dir dir})]
    (str "job " id " started; read its output with bash_output.")))

(defn- t-bash-output
  "`bash_output`'s body. All the arithmetic is harness.cap.jobs'."
  [{:keys [job]}]
  (jobs/read-output kernel-tools/*thread-id* job))

(def ^:private bash-kill-description
  (str "Stop a background job -- the command and everything it started -- and forget it."
       " Use it when a job has done what you needed, has gone wrong, or is holding something"
       " you want back (a port, a file). "
       "The answer carries whatever the job printed that you had not read yet, plus what"
       " happened: `[stopped]` if it was still running, or `[exit N]` if it had already"
       " ended by itself. Either way the job is gone afterwards -- reading a stopped job is"
       " the same as reading one that never existed."))

(defn- t-bash-kill
  "`bash_kill`'s body: stop it, answer with what it had said, forget it."
  [{:keys [job]}]
  (jobs/stop! kernel-tools/*thread-id* job))

(register! "bash_background"
  (tool bash-background-description
        {"command" {:type "string" :description "Command line."}}
        [:command] t-bash-background))

(register! "bash_output"
  (tool bash-output-description
        {"job" {:type "string" :description "Job id, as `bash_background` answered."}}
        [:job] t-bash-output))

(register! "bash_kill"
  (tool bash-kill-description
        {"job" {:type "string" :description "Job id, as `bash_background` answered."}}
        [:job] t-bash-kill))

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
  (tool web-fetch-description
        {"url" {:type "string"
                :description (str "The full address to fetch, including the https://"
                                  " prefix.")}}
        [:url] t-web-fetch))

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
  (tool web-search-description
        {"query" {:type "string" :description "What to search for."}
         "count" {:type "integer" :minimum 1
                  :description (str "How many results to ask for (default "
                                    search/default-count ", at most "
                                    search/max-count ").")}}
        [:query] t-web-search))

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
