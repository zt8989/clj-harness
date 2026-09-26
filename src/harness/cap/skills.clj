(ns harness.cap.skills
  "Where a session's skills live.

  THE DEFAULT IS THE HOST'S OWN CONVENTION DIRECTORY, <os-home>/.agents/skills --
  the same files ZCode and Claude read on this machine, so one `git clone` of a
  skill serves every agent in the room. It is deliberately NOT under
  harness.infra.home/root: that root is where harness keeps ITS configuration, and
  moving it (a deployment, a test run) has not moved the machine's home
  directory. A bound session gets a second root, <project>/.agents/skills, so a
  project can pin the skills it depends on next to the code that uses them.

  `roots` IS PURE, AND THAT IS A SHAPE CONSTRAINT RATHER THAN A STYLE. It takes
  the configured value and the session's project directory and answers with
  paths: it does not read harness.edn, does not look up a binding, and does not
  require harness.cap.project. The reason is a cycle. The project fence has to know
  these roots -- a skill's body says 'read references/x.md', and that path lands
  outside the project directory -- so harness.cap.project requires THIS namespace,
  and a require back would be a cycle Clojure refuses at load. Each caller
  therefore supplies what it already has in hand: the fence has the binding, a
  tool body has its thread-id, a test has neither and passes nil."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.infra.home :as home]))

(def convention-dir
  "The host's own layout for skills, relative to a home directory: a directory
  of directories, each one a skill. Spelled once because there are two homes to
  build it under -- the OS home's and a bound project's -- and a second spelling
  is how the two drift apart."
  [".agents" "skills"])

(defn root-layers
  "The skill directories this session reads, each with the LAYER it came from, in
  PRECEDENCE ORDER -- earlier entries win a name conflict.

  SAME ARGUMENTS, SAME FRESHNESS as `roots`, which is DERIVED from this: which
  the defaults are and which of them is which layer is written once, here,
  because a second spelling of a default is how two answers start to disagree.

  A LAYER IS A FACT ABOUT WHICH DEFAULT ROOT THIS IS, never a name inferred from
  a position in a list. The two defaults genuinely are the machine's and the
  project's, so they carry :system and :project. A configured {:roots [..]}
  carries NO :layer AT ALL (absent rather than nil, so a reader can tell 'no
  layer' from 'a layer that happens to be nil') -- the configuration never said
  'system' or 'project' about those paths, and reading one off their order would
  be a guess dressed as a fact. Every entry carries :path whatever the
  configuration looks like, and that is what a caller falls back on; see
  `skill-list`.

  A THIRD LAYER IS ONE MORE ENTRY IN THE DEFAULT BELOW -- a plugin root, say --
  and not a new branch in whoever renders a list. That is the whole reason the
  layer travels WITH the root instead of being recomputed downstream."
  ([] (root-layers nil nil))
  ([skills-cfg project-dir]
   (let [cfg (home/as-config-section skills-cfg ":skills" project-dir)]
     (if (contains? cfg :roots)
       (mapv (fn [p] {:path (home/resolve-against project-dir p)})
             (home/path-list (:roots cfg)
                             "{:skills {:roots [\"/abs/skills\" \"relative/to/project\"]}}"
                             ":skills {:roots" project-dir))
       (cond-> [{:path (str (apply io/file (home/user-home) convention-dir))
                 :layer :system}]
         project-dir (conj {:path (str (apply io/file project-dir convention-dir))
                            :layer :project}))))))

(defn roots
  "The skill directories this session reads, as absolute path strings, in
  PRECEDENCE ORDER -- earlier entries win a name conflict.

  The paths alone, for callers that want nothing but a directory to look in (the
  fence, a tool body). A caller that has to SAY where a skill came from wants
  `root-layers`, which is where those two are decided.

  SKILLS-CFG is the `:skills` value from harness.edn (or nil), which the caller
  takes from (harness.cap.project/harness-config thread-id); PROJECT-DIR is that
  session's binding, or nil. Read fresh on every call, matching config.edn and
  harness.edn, so editing the configuration moves the roots without a restart.

  Two shapes:

    {:roots [\"/abs/skills\" \"relative/to/project\"]}   the whole list, replacing
                                                         both defaults
    (absent)                                             <os-home>/.agents/skills,
                                                         plus <project>/.agents/skills
                                                         when bound

  A configured list REPLACES the defaults rather than adding to them, which is
  what makes 'only the project's skills, please' expressible -- write
  {:roots [\".agents/skills\"]} and the OS home drops out of the picture. The
  relative spelling is the tool-path rule; see harness.infra.home/resolve-against."
  ([] (roots nil nil))
  ([skills-cfg project-dir]
   (mapv :path (root-layers skills-cfg project-dir))))

;; ------------------------------------------------------------------ the catalog
;;
;; A SKILL IS A DIRECTORY HOLDING A Skill.md, and its IDENTITY IS THE DIRECTORY
;; NAME -- never the frontmatter's `name`. That is what makes a name safe to
;; interpolate into a lookup: the caller asks for a name and gets a directory
;; that was listed a moment ago, so a name can never become a path. The
;; frontmatter's own `name` is read only to CHECK it agrees; a skill whose two
;; names disagree is broken rather than silently one of them.

(def skill-file
  "The file inside a skill directory. Matched EXACTLY, not case-insensitively:
  this repo's discipline is that a name nobody wrote is a named failure, and
  guessing which file somebody meant is how a typo becomes a silent no-op. Every
  host layout that produces these uses this spelling."
  "SKILL.md")

(def frontmatter-keys
  "The keys this reader understands. Everything else in a SKILL.md's frontmatter
  -- allowed-tools, license, metadata, argument-hint, user-invocable, hidden,
  disable-model-invocation -- belongs to the HOST that wrote the file, and is
  read-and-ignored rather than reported as a mistake: a file having a field we do
  not use is not an error.

  TWO of those are skipped KNOWING what they ask for, and for one reason: a
  SKILL.md says what a skill IS, never what this session may do with it.
  `allowed-tools` cannot widen the toolset -- the session and the editing mode
  decide that, and a file that could grant itself capabilities is a different
  security story -- and `disable-model-invocation` cannot close the model's path
  to a skill either: who may reach one is this session's business, answered in
  exactly two places, the `skill` tool and a person's `/name` (harness.kernel.tools/
  t-skill, harness.cap.skills/slash-request). A file that could switch either one
  off would be deciding something that is not the file's to decide.

  Neither skip is silent in the sense of being unreadable: a field outside this
  set travels through untouched, which is what lets a reader that DOES honor one
  of them -- another host, a later version of this one -- still find it here."
  #{:name :description})

(defn- escape-attr [s] (str/replace (str s) "\"" "&quot;"))

(defn- unquote-value [v]
  (let [v (str/trim v)]
    (if (and (>= (count v) 2)
             (or (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                 (and (str/starts-with? v "'") (str/ends-with? v "'"))))
      (subs v 1 (dec (count v)))
      v)))

(defn- frontmatter-block
  "TEXT -> the lines inside its leading `---` fence, or nil when there is none.
  A fence that opens and never closes is nil too: half a frontmatter is not
  frontmatter, and the skill is then reported as missing one."
  [text]
  (let [lines (str/split-lines text)]
    (when (and (seq lines) (= "---" (str/trim (first lines))))
      (let [end (first (keep-indexed (fn [i l] (when (and (pos? i) (= "---" (str/trim l))) i))
                                     lines))]
        (when end (subvec (vec lines) 1 end))))))

(defn- parse-frontmatter
  "FRONTMATTER LINES -> a {keyword value} map.

  Hand-rolled rather than YAML-parsed, and deliberately narrow: flat `key: value`
  pairs, quoted values, and the two block scalars (`|` and `>`) whose indented
  body folds to one line. That is everything this repo's own skills actually use;
  a general YAML reader would be a dependency bought to parse five keys, and the
  shapes it would additionally accept are shapes nobody has written."
  [lines]
  ;; `(seq ls)`, never a bare truthiness test: (rest [x]) is the EMPTY list, which
  ;; is truthy, and (first ()) is nil -- which would hand the regex a nil line and
  ;; blow up on the last line of every frontmatter.
  (loop [ls (seq lines) out {}]
    (if-not (seq ls)
      out
      (let [line (first ls)]
        (if-let [[_ k v] (re-matches #"\s*([A-Za-z0-9_.-]+)\s*:\s*(.*?)\s*" line)]
          (let [k (keyword k)]
            (if (contains? #{ "|" ">" "|-" ">-" } v)
              (let [body (take-while #(or (str/blank? %) (re-find #"^\s" %)) (rest ls))]
                (recur (seq (drop (count body) (rest ls)))
                       (assoc out k (str/join " " (remove str/blank? (map str/trim body))))))
              (recur (seq (rest ls)) (assoc out k (unquote-value v)))))
          ;; An indented continuation line (metadata.version) or a stray line:
          ;; skipped, not reported. See frontmatter-keys for why.
          (recur (seq (rest ls)) out))))))

(defn- broken
  "An entry that was found and cannot be used, with the reason attached. It
  STAYS in the catalog -- visible, and namable in a refusal -- because a skill
  that silently vanished from the list is indistinguishable from one that was
  never installed, and the second is a much harder thing to debug."
  [name root path reason]
  {:name name :dir (str (io/file root name)) :path path :root root
   :available? false :reason reason :description nil})

(defn- read-skill
  "ROOT + NAME -> the skill directory's entry, available or broken. Never throws:
  one broken skill on a menu must not sink a whole session (contrast
  harness.cap.preamble/gather, where the same failure stops the run -- an instruction
  file is a standing statement about the session, a skill is one entry on a menu).

  Each entry records the ROOT it came from, which is how a reader tells one layer
  from another. Recording a LAYER NAME instead was considered and dropped: a
  configured {:roots [..]} has no user/project distinction to report, and
  inventing one from a list position would be a guess dressed as a fact -- while
  the path says exactly where the skill is, whatever the configuration looks
  like."
  [root name]
  (let [dir  (io/file root name)
        file (io/file dir skill-file)
        path (str file)]
    (cond
      (not (.isFile file))
      nil                                   ; not a skill at all -- a helper dir

      :else
      (let [raw (try (slurp file :encoding "UTF-8")
                     (catch Throwable _ ::unreadable))]
        (cond
          (= ::unreadable raw)
          (broken name root path :unreadable)

          (nil? (frontmatter-block raw))
          (broken name root path :no-frontmatter)

          :else
          (let [fm (parse-frontmatter (frontmatter-block raw))]
            (cond
              (not= name (str (:name fm)))
              (broken name root path :name-mismatch)

              (str/blank? (str (:description fm)))
              (broken name root path :no-description)

              :else
              {:name name :dir (str dir) :path path :root root
               :available? true :reason nil
               :description (str/trim (str (:description fm)))})))))))

(defn scan
  "ROOTS -> the skills they hold, in precedence order: EARLIER ROOTS WIN A NAME
  CONFLICT, and the winner is the only entry for that name.

  Every directory that holds a SKILL.md is a skill; a directory that does not is
  skipped silently (that is how a shared `_shared/` or a `references/` folder
  stays out of the list). A skill that is FOUND BUT UNUSABLE is kept, with
  :available? false and a :reason -- see `broken`. Each entry carries :root, the
  directory it was found under. Read fresh on every call, so editing a SKILL.md
  or adding a directory takes effect on the next call."
  [roots]
  (->> roots
       (reduce (fn [by-name root]
                 (reduce (fn [acc entry]
                           (if (contains? acc (:name entry))
                             acc
                             (assoc acc (:name entry) entry)))
                         by-name
                         (keep #(read-skill root %)
                               (sort (or (some-> (io/file root) .list) [])))))
               {})
       vals
       (sort-by :name)
       vec))

(defn catalog-text
  "ROOTS -> the catalog block a run opens with, or nil when this session has no
  usable skills.

  The block is a LIST, never the bodies: a session with fifty skills pays for
  fifty lines and not for fifty documents. Descriptions are delivered WHOLE --
  no truncation -- because the trigger clause at the end of one ('Use when the
  user asks...') is exactly what the model selects on, and a length cap cuts
  precisely that. See the spec for the measurement behind the choice.

  A BROKEN skill is LEFT OUT, and it is the only thing left out: the block
  advertises what this session can LOAD, and a skill that cannot be loaded is not
  one of those. It is still in `scan`, which is where a reader finds out why --
  'the file is there and the capability is not' is the one outcome this design
  refuses to leave unexplained."
  [roots]
  (let [usable (filter :available? (scan roots))]
    (when (seq usable)
      (str/join "\n"
                (concat [(str "## Skills")
                         ""
                         "A skill is a set of instructions for a kind of task. Load one with the `skill`"
                         " tool when its description matches what you are about to do; its instructions"
                         " come back as that call's result, together with the directory they live in."
                         " A PERSON can load one too, by starting a message with `/name ` -- when that"
                         " happens the message keeps the `/name` as typed and the full text arrives as"
                         " the message right after it."]
                        (map (fn [{:keys [name description]}] (str "- " name ": " description))
                             usable))))))

(defn- menu-row
  "A `scan` entry -> the four things a row on the person's list needs. Deliberately
  not the whole entry: :dir and :root belong to the group that holds the row, and
  nothing else about an entry is a row's business (see `skill-list`)."
  [{:keys [name description available? reason]}]
  {:name name :description description :available? available? :reason reason})

(defn skill-list
  "ROOT-LAYERS (see `root-layers`) -> the skill list a PERSON sees:
  {:groups [{:layer ..? :root .. :skills [{:name .. :description .. :available? ..
  :reason ..}]}]}, empty groups left out.

  ONE GROUP PER ROOT THAT HOLDS SOMETHING, in precedence order, and that order is
  the mechanism rather than a display choice: `scan` has ALREADY dropped a skill
  whose name an earlier root supplied, so a name appears once, in the group of the
  root that won it. A shadowed skill is not here because it cannot be loaded --
  `scan` is the one place that answers 'whose is this name', and re-deriving the
  loser would be a second answer to a question that already has one.

  ONE DELIBERATE DIFFERENCE FROM THE MODEL'S CATALOG (`catalog-text`): BROKEN
  skills are here too, with :available? false and their :reason -- the same
  standing `scan` gives them, and the same rule: a skill that silently vanished
  and a skill that was never installed look identical from the outside, and the
  second is much harder to debug. The caller draws them as unpickable; being told
  WHY is the whole point of their being there. The catalog leaves them out
  because the block the model selects from may only offer what it can load.

  Empty groups are left out rather than drawn as a heading over nothing. A session
  whose roots hold no skills at all answers {:groups []} -- an ordinary state, not
  an error (contrast `catalog-text`, which answers nil because a block that says
  nothing should not be injected at all)."
  [root-layers]
  (let [entries (scan (mapv :path root-layers))]
    {:groups
     (into []
           (keep (fn [{:keys [path layer]}]
                   (let [mine (filterv #(= path (:root %)) entries)]
                     (when (seq mine)
                       (merge (when layer {:layer layer})
                              {:root   path
                               :skills (mapv menu-row mine)})))))
           root-layers)}))

(defn skill-for
  "ROOTS + NAME -> the skill NAME's directory entry, or nil.

  THE ONLY WAY A NAME BECOMES A PATH, and the reason it is a lookup through
  `scan` rather than a path join: `scan` only ever produces names it read off a
  directory listing, so a name that arrives from a model cannot name anything
  else. '../../etc/passwd' is not refused here -- it is simply not in the table."
  [roots name]
  (first (filter #(= name (:name %)) (scan roots))))

(defn body
  "ENTRY -> the text to inject, read FRESH from disk, or a named note when the
  skill is no longer readable.

  A body is the whole SKILL.md, frontmatter included: the file IS the
  instruction, and carving the frontmatter off would invent a second answer to
  'what does this skill say'. Not truncated, ever -- a clipped skill is a wrong
  instruction, which is worse than a large one.

  READ FRESH RATHER THAN FROZEN INTO THE CONVERSATION, unlike the reference
  implementation: the roots are the source of truth for what a skill says, so
  editing one takes effect on the next turn. The cost is stated plainly -- a
  skill edited mid-conversation changes under the model's feet -- and the case
  that cannot be papered over is a skill that has since been removed, which
  plants a one-line notice at the injection site rather than silently dropping
  instructions the model believes it is following."
  [{:keys [name path]}]
  (if (and path (.isFile (io/file path)))
    (try {:body (str/trim (slurp (io/file path) :encoding "UTF-8"))}
         (catch Throwable _
           {:missing (str "skill " (pr-str name) " could not be re-read from " path
                          "; it was in the catalog when it was loaded, and is not readable now")}))
    {:missing (str "skill " (pr-str name) " is no longer in any skill root;"
                   " its instructions cannot be read, and it should not be assumed")}))

;; ------------------------------------------------------------------ the body
;;
;; THE ONE SOURCE LEFT IS A PERSON'S `/name`. The MODEL'S path used to arrive here too
;; -- the tool answered with a confirmation line and this namespace turned that line
;; into a body -- and it does not any more: `skill`'s result IS the body, so it
;; rides the tool call and nothing is derived for it. That also retired the shared
;; `[skill-loaded]` prefix, which existed only to judge 'did a load really happen'.
;; See `.scratch/skill-body-in-result`.

(defn- loaded-names
  "The skill names a message vector already carries as injected bodies, in the
  order they appear. Used for idempotency and for the one-load-per-name rule --
  among the bodies this derivation wrote, which is only ever a person's `/name` --
  a body that arrived as a tool result carries no tag to be found."
  [messages]
  (into []
        (keep (fn [m]
                (when (= "user" (:role m))
                  (when-let [[_ n] (re-matches #"(?s)<skill name=\"([^\"]*)\">.*" (str (:content m)))]
                    (str/replace n "&quot;" "\"")))))
        messages))

(defn- skill-message
  "{:name .. :body ..} -> the user message that carries it. The tag is the frame
  the model reads (this is an instruction that arrived, not something the user
  just typed) and the anchor this namespace reads back."
  [name body]
  {:role "user"
   :content (str "<skill name=\"" (escape-attr name) "\">\n" body "\n</skill>")})

;; ------------------------------------------------------------ the slash form
;;
;; THE HUMAN'S WAY IN. A skill is loaded by the model with the `skill` tool -- whose
;; RESULT is the body, so nothing about that path is derived -- and by a person by
;; typing "/name ..." in the composer. A person's message carries no result of its
;; own, so this is the ONE source left for the derivation below: the ask ends as a
;; `<skill name=..>` user message appended at the end of the history.
;;
;; A NAME BOTH PATHS ASK FOR ARRIVES TWICE, and that is the decision rather than an
;; oversight: `loaded-names` recognises a body by the tag this namespace writes, and
;; a body that came back as a tool result carries no tag to be found. A person typing
;; `/alpha` after the model already loaded alpha is asking for it again, and inventing
;; a judgement about which tool results count as loads is exactly the second derivation
;; the shared `[skill-loaded]` prefix existed to avoid.
;; The trigger lives in the conversation rather than in a side record, which is the
;; same line the whole design draws: what a person SAID is part of the conversation
;; (so the "/name" is still there to be re-read on every turn, and survives a
;; refresh), while what the harness DERIVED from it -- the body, the catalog -- is
;; recomputed and never folded in. No bookkeeping, and nothing to lose on a refresh.

(def slash-pattern
  "What a slash-load looks like: the slash, the name, then a space, a newline, or
  the end of the message.

  The name is read in the charset a frontmatter key uses, because that is the
  charset a directory listing produces. ANCHORED AT THE START and the separator is
  REQUIRED, which is what keeps prose and paths out of it: `/alpha/beta` is a path,
  `see /alpha` is a sentence about a skill, and `/alphax` is a different (probably
  unknown) name rather than a prefix match on `alpha`."
  #"(?s)^/([A-Za-z0-9._-]+)(?:\s|$)")

(defn slash-request
  "TEXT -> the skill name TEXT asks to load by slash, or nil when it asks for
  nothing.

  It answers only WHICH NAME was asked for. Everything after that is the same body
  (and the same missing-body notice) every ask gets, so a second copy of 'how a body
  gets injected' does not exist.

  THE TEXT IS NOT REWRITTEN, and that is a decision rather than an omission: what
  the model reads as this message's own words is what the person typed, slash and
  all, and the body arrives beside it as its own message. Leaving the trigger in
  place costs one short line of context and buys the re-readability above --
  a trigger that had to be stripped to be recognized would need somewhere to
  remember that it had been."
  [text]
  (when (string? text)
    (second (re-find slash-pattern text))))

(defn- leading-text
  "A message's own words, as the string a slash-request is looked for in. A string
  is itself; a parts vector is its TEXT parts joined, so an image sent beside
  `/alpha` does not hide the request. Nil for content that carries no text."
  [content]
  (cond
    (string? content)     content
    (sequential? content) (str/join "\n" (keep #(when (= "text" (:type %)) (:text %)) content))
    :else                 nil))

(defn- slash-of
  "The name MESSAGE asks to load by slash, or nil. One place answers it, so the
  caller's guard ('did anything ask?') and its walk cannot disagree."
  [m]
  (when (= "user" (:role m))
    (slash-request (leading-text (:content m)))))

;; --------------------------------------------------- why a name has no body
;;
;; ONE WORDING FOR BOTH PATHS. 'Give me the body of NAME' is the same question
;; whether a model asked through the tool or a person asked with a slash, so the
;; two answers are spelled once here: a distinction written down twice is a
;; distinction free to disagree with itself.

(defn known-names
  "The skill names this session can load, in scan order. The list a refusal quotes
  and the list a notice quotes, so 'what can I load' has one answer."
  [roots]
  (vec (keep #(when (:available? %) (:name %)) (scan roots))))

(defn absent-notice
  "The sentence for a name no root holds: what was asked for, and what this
  session can load instead. A typo is the everyday way to read this."
  [roots skill-name]
  (let [known (known-names roots)]
    (str "no skill named " (pr-str skill-name) "; this session can load "
         (if (seq known) (pr-str known) "nothing"))))

(defn broken-notice
  "The sentence for a skill that is THERE and unusable, naming the reason and the
  file. Same shape as absent-notice's job: say which one and why, because 'the
  file is there and the capability is not' must never be a mystery.

  SKILL-NAME and not `name`: a parameter called `name` shadows clojure.core/name
  for the whole body, and this one needs it for the REASON, which is a keyword.
  That shadowing threw a ClassCastException here rather than returning a refusal
  -- see the note in harness.kernel.tools/t-skill, where the same expression used to
  live."
  [skill-name entry]
  (str "skill " (pr-str skill-name) " cannot be loaded: " (name (:reason entry))
       " (see " (:path entry) ")"))

(defn- load-text
  "ROOTS + NAME -> the text to splice for NAME: a skill's body, or a notice saying
  why there is none.

  The notice is the whole reason this is a function rather than a `cond` at the
  splice site: `derived-injections` can be reached with a name that was loadable
  when it was asked for and is not now, and 'the instructions you believe you are
  following are gone' is the last thing that may happen silently."
  [roots name]
  (let [entry (skill-for roots name)]
    (cond
      (nil? entry)
      (str (absent-notice roots name)
           " -- its instructions cannot be read here, and should not be assumed")

      (not (:available? entry))
      (broken-notice name entry)

      :else
      (or (:body (body entry)) (:missing (body entry))))))

(defn derived-injections
  "MESSAGES + ROOTS -> MESSAGES with the skill bodies a person's `/name` asked for,
  appended AT THE END.

  THE MODEL'S PATH IS NOT HERE. `skill` answers with the body itself, so a load by
  the model is an ordinary tool result the conversation already carries and this
  derivation has nothing to add -- and because the body IS one of the tool results,
  the vendor rule below cannot be broken by that path at all. What is left is the
  person's `/name`: the trigger is a user message, there is no result to carry the
  body, and this is what puts it beside them.

  WHERE A BODY GOES, AND WHY IT MOVED. It used to be spliced directly behind the
  message that asked for it. It is now the LAST thing in the history, and that is the
  same order the rest of a session's injections took: the system prompt, the question,
  the material for it, and the skill body closest to the end (see
  `harness.edge.ag_ui/inbound`, which puts the instruction files and the catalog just
  after the client's messages). A model reads what it asked for beside the question it
  is answering, which is where a person would put it.

  THE VENDOR'S RULE IS WHY IT CANNOT GO ANYWHERE IT LIKES, and appending satisfies it
  by construction: an OpenAI-shaped vendor refuses a request whose assistant message
  with tool_calls is not followed, IMMEDIATELY, by a tool message for each
  'tool_call_id' (HTTP 400, 'insufficient tool messages following tool_calls
  message'). A body spliced into the middle of a batch of results breaks that; a body
  at the very end is behind every result there is.

  IT IS DERIVED FROM THE CONVERSATION, NOT FROM A SIDE TABLE. A body's CARD is part of
  the conversation once a run has folded it in (`harness.edge.sessions`), and
  `sessions/model-view` realises its bytes back into the message the model read -- so
  'is this name already loaded' is answered by the conversation itself, and a body is
  added only for the name that is not there yet. The instruction files and the catalog
  behave the same way (`.scratch/session-opening`): an edited SKILL.md takes effect at
  its NEXT load rather than mid-session. The two properties that matter are:

    - IDEMPOTENT. Applying this to its own output changes nothing, because the
      body is already in place where it belongs. That is what lets the kernel
      apply it before every LLM call with no bookkeeping at all.
    - ONE LOAD PER NAME, AMONG THE ASKS THIS DERIVATION SEES. A name contributes one
      body however many times a person asks for it. TWO PATHS ARE TWO ASKS: a name the
      MODEL already loaded is not recognised here, because its body carries no tag --
      the note above the slash form is where that call is written down.

  A name that can no longer be read plants a one-line notice instead of vanishing,
  and an unknown name gets the same treatment: instructions the model believes it
  is following are the last thing to drop silently, and a typo that loaded nothing
  must not look like a skill that loaded nothing TO SAY.

  The result is a message vector and nothing else: the frames a person sees are the
  run's, and they are emitted where the history is assembled and applied (see
  `harness.edge.ag_ui/injected-frame`), not here -- a derivation that talked to a wire
  would be a derivation that could not be tested without one."
  [messages roots]
  (if (not-any? slash-of messages)
    messages
    ;; WHAT WAS ASKED FOR, IN ORDER, AND ONLY WHAT IS NOT ALREADY THERE. The walk is
    ;; over the whole history because the person's ask is in it, and `loaded-names` is
    ;; what makes the step idempotent: a body already in the history contributes
    ;; nothing, however many times it was asked for.
    (let [present (set (loaded-names messages))
          asked   (distinct (keep slash-of messages))
          missing (remove (conj present nil) asked)]
      (if (empty? missing)
        messages
        (into (vec messages)
              (map (fn [nm] (skill-message nm (load-text roots nm))))
              missing)))))
