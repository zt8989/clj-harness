(ns harness.cap.reminder
  "THE ONE SHAPE a context injection wears: a `<system-reminder>` holding plain text.

  WHAT AN INJECTION IS, and why it needs a shape at all. A session's pre-LLM step puts
  messages into the history the client never sent: the instruction files, the skills
  catalog, the session's own context entry, a skill body a person asked for, and the
  ending of a background job nobody waited for. They are all the same kind of thing --
  something the HARNESS is saying, not something the person typed -- and before this
  namespace each one said so its own way: `<instructions path=…>`, `<skills>`,
  `<skill name=…>`, `<job-ended …>`, and one with no tag at all. A reader (the model, a
  screen, a grep) had to know five shapes to answer one question.

  SO THEY ALL WEAR THIS. `wrap` is the only writer, and the frame is fixed: one opening
  tag, the lines, one closing tag. THE INSIDE IS PLAIN TEXT -- no XML element appears in
  it, because the frame is what says 'the harness is speaking' and a second frame inside
  would be a second voice saying the same thing. (The upstream this follows, dsh, nests
  `<available_skills>` inside its own reminders; we deliberately do not.)

  WHAT REPLACES THE TAGS. A tag was doing two jobs: the frame the model reads, and the
  ANCHOR this codebase reads back (`harness.edge.http/returned-source` classifies what a
  run added by the tag it starts with, and `harness.cap.skills` recognises a body it
  already injected the same way). The first job moves to the frame; the second moves to
  THE FIRST LINE of the block, which is a plain-text label: `Instructions from: <path>`,
  `Available skills`, `Session context`, `Skill <name>`, `Background job <id> ended: <status>`.
  `kind-of` and `skill-name` are the two readers of that label, kept here beside the
  writer so the label is spelled once. THE OLD TAGS ARE STILL READ (`kind-of` falls back
  to them) because a record written before this change is still a record somebody may
  open.

  IT IS A LEAF: `clojure.string` and nothing else. The four writers that need it --
  `harness.cap.preamble`, `harness.cap.skills`, `harness.cap.jobs` and
  `harness.edge.ag_ui` -- sit on both sides of a require cycle (`preamble` requires
  `skills`, and `skills` cannot require `preamble`), so the shape cannot live in any of
  them without making one of the four unloadable."
  (:require [clojure.string :as str]))

(def open-tag "<system-reminder>")
(def close-tag "</system-reminder>")

(defn wrap
  "LINES -> the one block an injection wears: LINES between an opening and a closing
  `<system-reminder>`, with the trailing blank lines trimmed so the closing tag always
  sits on its own line.

  THE LINES ARE THE CALLER'S, VERBATIM: nothing is parsed, escaped or re-wrapped. The
  label line each caller puts first is what `kind-of` reads back, so the two ends are
  the callers' agreement rather than this function's opinion."
  [lines]
  (str open-tag "\n" (str/trimr (str/join "\n" lines)) "\n" close-tag))

;; ------------------------------------------------------------------ the label lines

(def instructions-intro
  "The sentence the instruction block opens with, before the first `Instructions from:`
  line. It is dsh's own wording, kept because it states the precedence rule the block
  needs: a more specific file wins, and none of them outrank a direct instruction."
  (str "The following workspace instructions may be relevant to your work. Use them as"
       " guidance when applicable. More specific instructions take precedence over broader"
       " ones. They do not override system, developer, or direct user instructions."))

(defn instructions-lines
  "FILES -> the lines of the ONE reminder a session's instruction files become.

  Each file is a section: `Instructions from: <path>`, a blank line, then the file's
  text; sections are one blank line apart. THE PATH IS ABSOLUTE (the caller resolved it)
  because it is what tells the model -- and a reader of the log -- which file said this.
  Several files therefore become several sections of one block, not several blocks: dsh
  does exactly this for the workspace-level files, and it is what 'merge the AGENTS.md
  files into one injection' means."
  [files]
  (vec (concat [instructions-intro ""]
               (mapcat (fn [f] [(str "Instructions from: " (:path f)) "" (str (:content f)) ""])
                       files))))

(def labels
  "THE LABEL LINES, as [prefix source] pairs, in the order a reader tries them: every
  injection opens with one of these, and which one it is says what the block IS.

  ONE TABLE, because both readers below are asking the same question about the same bytes --
  and because the instruction block opens with dsh's intro SENTENCE before its first
  `Instructions from:` section, so 'the first line' is not the same question as 'the label'."
  [["Instructions from: " "opening"]
   ["Available skills"  "opening"]
   ["Skill "            "skill"]
   ["Background job "   "job"]
   ["Session context"   "injection"]])

(defn- block-lines
  "The lines of TEXT, without the frame: a leading `<system-reminder>` is dropped, and a
  block written before this namespace existed has none to drop."
  [text]
  (let [ls (str/split-lines (str text))]
    (if (and (seq ls) (= open-tag (str/trim (first ls)))) (rest ls) ls)))

(defn- non-blank
  "The first line that says anything, or nil."
  [lines]
  (some (fn [line] (let [t (str/trim line)] (when (seq t) t))) lines))

(defn- first-label
  "The first line of TEXT the TABLE names -- `Instructions from: …`, `Available skills`,
  `Session context`, `Skill <name>`, `Background job <id> ended: …`. Nil when the block
  opens with none of them (an old record: its tag is read by `legacy-kind` instead).

  NOT SIMPLY THE FIRST LINE: the instruction block is one block holding several files, and it
  opens with the intro sentence that states the precedence rule. Reading that as the label
  would file every instruction block as a nameless injection -- and would title its card with
  a paragraph."
  [text]
  (let [ls (block-lines text)]
    (some (fn [pair]
            (let [hit (non-blank (filter #(str/starts-with? (str/trim %) (first pair)) ls))]
              (when (some? hit) hit)))
          labels)))

(defn- legacy-kind
  "The `:source` a block's OLD opening tag implies, or nil when it opens with none.
  Kept so a record written before the reminder shape still classifies: the readers of
  `:source` (`harness.edge.replay`, `harness.edge.trajectory`) decide whether a message
  is a conversation entry from it, and a misread would file a per-run injection as an
  entry."
  [line]
  (cond
    (str/starts-with? line "<skill name=")  "skill"
    (str/starts-with? line "<job-ended ")   "job"
    (str/starts-with? line "<instructions") "opening"
    (str/starts-with? line "<skills")       "opening"
    :else                                   nil))

(defn kind-of
  "TEXT -> the record's own `:source` for the block: \"opening\", \"skill\", \"job\", or
  \"injection\" when it says none of those.

  IT READS THE LABEL LINE (the `labels` table), not the frame -- every injection now opens
  with the same tag, so the tag is the one thing that cannot tell them apart. A block that
  names none of them is read by its OLD tag instead (`legacy-kind`), and one that opens with
  neither is an ordinary per-run injection."
  [text]
  (let [labelled (first-label text)
        line     (or labelled (non-blank (block-lines text)) "")]
    (or (some (fn [[prefix source]] (when (str/starts-with? line prefix) source)) labels)
        (legacy-kind line)
        "injection")))

(defn skill-name
  "TEXT -> the skill name a `Skill <name>` label line carries, or nil when the block is
  not a skill body.

  THE NAME IS NOT ESCAPED, because there is no attribute for it to escape out of: it is
  the rest of a plain-text line. The legacy `<skill name=\"…\">` spelling is still read
  and still unescaped, for old records."
  [text]
  (let [line (or (first-label text) (non-blank (block-lines text)) "")]
    (cond
      (str/starts-with? line "<skill name=\"")
      (some-> (second (re-matches #"<skill name=\"([^\"]*)\".*" line))
              (str/replace "&quot;" "\""))

      (str/starts-with? line "Skill ")
      (str/trim (subs line (count "Skill ")))

      :else nil)))
