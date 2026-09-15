(ns harness.preamble
  "The blocks a run opens with, other than the frozen system prompt.

  Everything here is injected on the USER side. prompt.md stays the one and only
  system message -- it is frozen so the provider's prefill cache keeps hitting,
  and a second system message would turn harness.ag-ui/inbound's rule ('a leading
  system message is replaced by the frozen prompt, otherwise it is prepended')
  into a rule about a family of them. Everything else the model is handed at the
  start of a run -- the instruction files, the skills catalog -- arrives as an
  ordinary user message whose tag says what it is. That is also what makes these
  blocks invisible to the client: they never become an AG-UI frame, so there is
  nothing for a front end to filter and nothing for one to draw.

  THIS NAMESPACE OWNS THE ASSEMBLY, in the sense that matters: the order the
  blocks come out in is a decision rather than an accident of how they were
  gathered, so exactly one function makes it. Callers supply what to look at --
  paths resolved for their session -- and get back the messages, in order.

  THE DEFAULT IS THE HOST'S OWN CONVENTION: <os-home>/AGENTS.md, the file this
  machine's agents already read, plus <project>/AGENTS.md in a bound session so a
  project can state its own rules next to its code. Never anything under
  harness.home/root -- see harness.home/user-home for why those are two
  different floors."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.home :as home]
            [harness.skills :as skills])
  (:import [java.nio ByteBuffer]
           [java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets]
           [java.nio.file Files]))

(def convention-file
  "The host's own name for a directory's instruction file. Spelled once because
  there are two directories to look in -- the OS home and a bound project's --
  and a second spelling is how they drift apart."
  "AGENTS.md")

(defn instruction-files
  "The instruction files this session reads, as path strings, in the order they
  should be presented.

  CFG is the `:instructions` value from harness.edn (or nil), which the caller
  takes from (harness.project/harness-config thread-id); PROJECT-DIR is that
  session's binding, or nil. Read fresh on every call, matching config.edn and
  harness.edn, so editing the configuration takes effect on the next run.

  Two shapes:

    {:files [\"/abs/AGENTS.md\" \"docs/AGENTS.md\"]}   the whole list, replacing
                                                      both defaults
    (absent)                                          <os-home>/AGENTS.md, plus
                                                      <project>/AGENTS.md when bound

  THE PATHS ARE WHERE TO LOOK, NOT WHAT IS THERE. A file that does not exist is
  not filtered out here -- this answers the session's configuration, and reading
  is gather's job. That split is what lets a missing file be the quiet, everyday
  case and a file that exists but cannot be read be the loud one."
  ([] (instruction-files nil nil))
  ([cfg project-dir]
   (let [c (home/as-config-section cfg ":instructions" project-dir)]
     (if (contains? c :files)
       (mapv #(home/resolve-against project-dir %)
             (home/path-list (:files c)
                             "{:instructions {:files [\"AGENTS.md\"]}}"
                             ":instructions {:files" project-dir))
       (cond-> [(str (io/file (home/user-home) convention-file))]
         project-dir (conj (str (io/file project-dir convention-file))))))))

;; -------------------------------------------------------------------- reading

(defn- read-utf8
  "F's bytes, decoded as UTF-8 -- or not at all. Malformed input is a FAILURE
  rather than a replacement character, which is the difference between 'this
  file is not text' and 'this file says something slightly odd'. slurp's default
  decoder would silently splice U+FFFD into a binary file and hand the model a
  set of instructions nobody wrote, which is worse than not starting."
  [^java.io.File f]
  (let [bytes (Files/readAllBytes (.toPath f))
        dec   (doto (.newDecoder StandardCharsets/UTF_8)
                (.onMalformedInput CodingErrorAction/REPORT)
                (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (try
      (str (.decode dec (ByteBuffer/wrap bytes)))
      (catch CharacterCodingException e
        (throw (ex-info "not valid UTF-8" {:cause :encoding}))))))

(defn- escaped [s] (str/replace (str s) "\"" "&quot;"))

(defn- instruction-message
  "One folded instruction file -> the user message that carries it. The path is
  on the tag so the model (and a reader of the log) can see which file said
  this, and it is rendered ABSOLUTE because that is what the caller resolved."
  [{:keys [path content]}]
  {:role "user"
   :content (str "<instructions path=\"" (escaped path) "\">\n" content "\n</instructions>")})

(defn gather
  "FILES -> the material a run opens with, read fresh:

    {:instructions [{:path .. :content ..}]   in the order given, so the more
                                              specific file comes last
     :skills       <the catalog text, or nil> when ROOTS is given
     :skipped      [{:path .. :reason ..}]}

  Two failures, two policies, and the split is deliberate:

    - A file that is NOT THERE, or is EMPTY, is skipped. Both are everyday
      states -- most projects have no AGENTS.md, and a placeholder file says
      nothing -- and neither is worth stopping a run over.
    - A file that IS there but cannot be READ is a NAMED failure carrying the
      absolute path. This is the session's own configuration, the same family as
      config.edn and harness.edn: a run that quietly proceeded without it would
      be following rules the user did not write, which is the one thing a session
      must never do silently.

  The contrast with harness.skills is the point of the split rather than an
  inconsistency: a skill is one entry on a menu and a broken one must not sink a
  session, while an instruction file is a standing statement about how the
  session must work."
  [{:keys [files roots]}]
  (reduce
   (fn [acc path]
     (let [f (io/file path)]
       (cond
         (not (.exists f))
         (update acc :skipped conj {:path path :reason :missing})

         (.isDirectory f)
         (update acc :skipped conj {:path path :reason :not-a-file})

         :else
         (let [content (try
                         (str/trim (read-utf8 f))
                         (catch Throwable t
                           (throw (ex-info (str "cannot read the instruction file " path
                                                ": " (ex-message t)
                                                " -- it states how this session must work, so the"
                                                " run will not start on a guess about what it says")
                                           {:path path :reason :unreadable}))))]
           (if (str/blank? content)
             (update acc :skipped conj {:path path :reason :empty})
             (update acc :instructions conj {:path path :content content}))))))
   {:instructions [] :skipped [] :skills (some-> roots skills/catalog-text)}
   files))

(defn- skills-message
  "The catalog block, or nil when this session has no usable skills. Same shape
  as an instruction message and for the same reason: a user message and nothing
  else, so it never becomes an AG-UI frame."
  [text]
  (when (seq text)
    {:role "user" :content (str "<skills>\n" text "\n</skills>")}))

(defn messages
  "GATHERED -> the ordered user messages a run opens with.

  THE ORDER IS THE DECISION THIS FUNCTION EXISTS TO MAKE, and it is semantics
  rather than typography:

    1. the instruction files, in the order they were resolved -- the OS home's
       first, the project's second, so the more specific statement is the nearer
       one;
    2. the skills catalog, last of the opening blocks and still ahead of the
       conversation.

  Standing rules first, then the menu of what else is available: a model that
  reads in order meets the constraints it must always honour before the optional
  capabilities it may reach for."
  [{:keys [instructions skills]}]
  (cond-> (mapv instruction-message instructions)
    (seq skills) (conj (skills-message skills))))

(defn report
  "GATHERED -> what a session (or a human) asking 'what does this run open with'
  should be told: each block with its source and its size, and everything that
  was skipped with the reason.

  Sizes are CHARACTER COUNTS of what was actually injected, not byte sizes of
  the files: the model's cost is the text it is handed, and trimmed whitespace
  is not handed to it. Nothing is truncated to fit here -- an oversized
  AGENTS.md goes in whole -- which is exactly why the number is worth reporting."
  [{:keys [instructions skills skipped]}]
  (cond-> {:instructions (mapv (fn [{:keys [path content]}]
                                 {:path path :chars (count content)})
                               instructions)
           :skipped (mapv identity skipped)}
    (seq skills) (assoc :skills {:chars (count skills)})))
