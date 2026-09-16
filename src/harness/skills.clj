(ns harness.skills
  "Where a session's skills live.

  THE DEFAULT IS THE HOST'S OWN CONVENTION DIRECTORY, <os-home>/.agents/skills --
  the same files ZCode and Claude read on this machine, so one `git clone` of a
  skill serves every agent in the room. It is deliberately NOT under
  harness.home/root: that root is where harness keeps ITS configuration, and
  moving it (a deployment, a test run) has not moved the machine's home
  directory. A bound session gets a second root, <project>/.agents/skills, so a
  project can pin the skills it depends on next to the code that uses them.

  `roots` IS PURE, AND THAT IS A SHAPE CONSTRAINT RATHER THAN A STYLE. It takes
  the configured value and the session's project directory and answers with
  paths: it does not read harness.edn, does not look up a binding, and does not
  require harness.project. The reason is a cycle. The project fence has to know
  these roots -- a skill's body says 'read references/x.md', and that path lands
  outside the project directory -- so harness.project requires THIS namespace,
  and a require back would be a cycle Clojure refuses at load. Each caller
  therefore supplies what it already has in hand: the fence has the binding, a
  tool body has its thread-id, a test has neither and passes nil."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.home :as home]))

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
  takes from (harness.project/harness-config thread-id); PROJECT-DIR is that
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
  relative spelling is the tool-path rule; see harness.home/resolve-against."
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
  -- allowed-tools, license, metadata, argument-hint, user-invocable, hidden --
  belongs to the HOST that wrote the file, and is read-and-ignored rather than
  reported as a mistake: a file having a field we do not use is not an error.

  Note what is NOT here: `allowed-tools`. A skill cannot widen this session's
  toolset. The toolset is decided by the session and the editing mode, and a
  file that could grant itself capabilities is a different security story."
  #{:name :description :disable-model-invocation})

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
  harness.preamble/gather, where the same failure stops the run -- an instruction
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
               :description (str/trim (str (:description fm)))
               :disable-model-invocation?
               (boolean (or (true? (:disable-model-invocation fm))
                            (= "true" (str (:disable-model-invocation fm)))))})))))))

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

  A skill with disable-model-invocation is LEFT OUT: the file says this is not
  for the model to decide to use, and a session has no other way to invoke one.
  It is still in `scan`, which is where a reader can find out why it is absent --
  'the file is there and the capability is not' is the one outcome this design
  refuses to leave unexplained."
  [roots]
  (let [usable (filter #(and (:available? %) (not (:disable-model-invocation? %)))
                       (scan roots))]
    (when (seq usable)
      (str/join "\n"
                (concat [(str "## Skills")
                         ""
                         "A skill is a set of instructions for a kind of task. Load one with the `skill`"
                         " tool when its description matches what you are about to do; its full text then"
                         " joins this conversation, and it stays available for the rest of the session."
                         " A PERSON can load one too, by starting a message with `/name ` -- when that"
                         " happens the message keeps the `/name` as typed and the full text is the"
                         " message right after it, so it is already in this conversation."]
                        (map (fn [{:keys [name description]}] (str "- " name ": " description))
                             usable))))))

(defn- menu-row
  "A `scan` entry -> the four things a row on the person's list needs. Deliberately
  not the whole entry: :dir and :root belong to the group that holds the row, and
  :disable-model-invocation? is not a row's business (see `skill-list`)."
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

  TWO DELIBERATE DIFFERENCES FROM THE MODEL'S CATALOG (`catalog-text`), and they
  are about who is choosing:

    - `disable-model-invocation` skills ARE here. The file says the MODEL may not
      decide to use it; a person typing /name is the person deciding, and the
      server loads it for them -- so a list of what a person can load that left it
      out would be answering a different question than the one it is asked.
    - BROKEN skills are here too, with :available? false and their :reason -- the
      same standing `scan` gives them, and the same rule: a skill that silently
      vanished and a skill that was never installed look identical from the
      outside, and the second is much harder to debug. The caller draws them as
      unpickable; being told WHY is the whole point of their being there.

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

;; ------------------------------------------------------------------ injection

(def loaded-prefix
  "The opening of the confirmation the `skill` tool answers with, and therefore
  also the TEST OF WHETHER A LOAD HAPPENED. One string, two users: the tool
  writes it and derived-injections looks for it.

  The reason it is shared rather than duplicated is that the second use is a
  JUDGEMENT ABOUT THE FIRST. A call that was vetoed, disabled, or made with a
  missing argument never reaches the tool body and so never writes this line --
  and reconstructing that fact anywhere else would mean re-deriving which of
  those happened, in a second place, from a log."

  "[skill-loaded]")

(defn loaded-summary
  "NAME + CHARS -> what the tool answers with. It reads as a statement about the
  SESSION rather than about the call -- after this the instructions are in the
  conversation -- because that is what the model needs to know, and because it
  is what the derivation below then looks for."
  [name chars]
  (str loaded-prefix " " name
       " is now in this conversation and stays available for the rest of the session"
       " (" chars " chars). Follow it unless a later instruction supersedes it."))

(defn- load-confirmations
  "A message vector -> the tool results that ARE skill loads, as a
  {tool-call-id result} map.

  A tool result is a load when its content starts with loaded-prefix. The result
  carries no tool name of its own -- only the id of the call that produced it --
  so the name comes from the assistant message's tool_calls; matching those two
  up is what makes this work on any provider-shaped history."
  [messages]
  (let [ids (into #{}
                  (comp (filter #(= "assistant" (:role %)))
                        (mapcat :tool_calls)
                        (filter #(= "skill" (get-in % [:function :name])))
                        (map :id))
                  messages)]
    (into {}
          (comp (filter #(= "tool" (:role %)))
                (filter #(contains? ids (:tool_call_id %)))
                (filter #(str/starts-with? (str (:content %)) loaded-prefix))
                (map (juxt :tool_call_id :content)))
          messages)))

(defn- loaded-names
  "The skill names a message vector already carries as injected bodies, in the
  order they appear. Used for idempotency and for the one-load-per-name rule."
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
;; THE HUMAN'S WAY IN. A skill is loaded by the model through the `skill` tool,
;; and by a person by typing "/name ..." in the composer -- and those are the only
;; two ways. They are not two mechanisms: both are SOURCES for the same
;; derivation, both end as a `<skill name=..>` user message spliced in after the
;; message that asked, and both obey the same one-load-per-name rule. Keeping them
;; one derivation is what stops the two paths from drifting apart.
;;
;; The trigger lives in the conversation rather than in a server-side record, for
;; the reason the whole design is derived rather than accumulated: the client owns
;; the history, so the "/name" a person typed is still there to be re-read on every
;; turn -- no bookkeeping, and nothing to lose on a refresh.

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

  It answers only WHICH NAME was asked for. Everything after that is the
  derivation the tool path shares, so a second copy of 'how a body gets injected'
  does not exist.

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
  and the list a notice quotes, so 'what can I load' has one answer.

  `disable-model-invocation` skills are NOT in it: they are loadable by a person
  who knows the name (see slash-request) but this list is what to answer a MODEL
  with, and the flag is the file saying the model may not have it."
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
  -- see the note in harness.tools/t-skill, where the same expression used to
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
  "MESSAGES + ROOTS -> MESSAGES with the skill bodies this conversation has
  loaded spliced in, each directly after the message that asked for it.

  IT IS DERIVED, NOT ACCUMULATED, and the difference is forced rather than chosen.
  applepi's server holds the session, so its tool can push a message into history
  and persist it. Here the CLIENT owns the conversation and the server is
  stateless per run: an injection held server-side dies on refresh, and one sent
  to the client gets rendered. So the body is recomputed from the conversation
  itself, every time, and the two properties that makes possible are the ones that
  matter:

    - IDEMPOTENT. Applying this to its own output changes nothing, because the
      body is already in place where it belongs. That is what lets the kernel
      apply it before every LLM call with no bookkeeping at all.
    - FIRST LOAD WINS, ACROSS SOURCES. A name contributes one body however many
      times it is asked for, and it does not matter whether the ask was the
      model's tool call or a person's slash -- they are two ways to ask the same
      question, not two gets of the same instructions.

  TWO SOURCES, and keeping them here together is the point:

    - a tool result that IS a skill load -- judged by the shared confirmation
      prefix, never by re-deriving which of veto/disabled/missing-argument
      happened (see load-confirmations);
    - a user message opening with `/name` -- the human's path (see slash-request).

  A name that can no longer be read plants a one-line notice instead of vanishing,
  and an unknown name gets the same treatment: instructions the model believes it
  is following are the last thing to drop silently, and a typo that loaded nothing
  must not look like a skill that loaded nothing TO SAY.

  The result is a message vector and nothing else: no AG-UI frame is produced for
  any of this, which is exactly why a client never sees these messages."
  [messages roots]
  (let [confirmations (load-confirmations messages)
        name-of       (into {}
                            (for [m messages
                                  :when (= "assistant" (:role m))
                                  tc    (:tool_calls m)
                                  :when (= "skill" (get-in tc [:function :name]))
                                  :let  [args (try (json/read-str (str (get-in tc [:function :arguments]))
                                                                  :key-fn keyword)
                                                   (catch Throwable _ {}))]
                                  :when (contains? confirmations (:id tc))]
                              [(:id tc) (str (:name args))]))]
    (if (and (empty? confirmations) (not-any? slash-of messages))
      messages
      (let [present (set (loaded-names messages))]
        (loop [out [] seen present [m & more :as ms] messages]
          (if (empty? ms)
            out
            (let [out (conj out m)
                  nm  (or (get name-of (:tool_call_id m)) (slash-of m))]
              (if (and nm (not (contains? seen nm)))
                (recur (conj out (skill-message nm (load-text roots nm))) (conj seen nm) more)
                (recur out seen more)))))))))
