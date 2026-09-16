(ns harness.cap.editing
  "Which file-editing implementation this session is served by, and the knobs
  that implementation reads.

  TWO IMPLEMENTATIONS, ONE OF THEM IN EFFECT PER SESSION:

    :hashline     anchor-based editing: `read` returns `anchor│content` rows and
                  `replace`/`insert` address anchors, so a line is named by a
                  token nobody has to guess at, and a stale one is refused
                  instead of fuzzy-matched. THE DEFAULT since 2026-09-15.
    :str-replace  the original `edit` -- an exact `old_string` replaced, refused
                  when it is absent or not unique. Still first-class, still fully
                  tested, and one line of harness.edn away.

  WHY THE DEFAULT MOVED, and why it is one line to move back. Anchor editing is
  what the session does unless somebody says otherwise, because the thing it fixes
  is not a matter of taste: `old_string` makes the model retype the text it is
  changing, which is both where the mistakes come from and why an edit costs a
  re-read of the file afterwards. Nothing about the old mode was removed -- `edit`
  is registered, its behaviour is unchanged, and `:editing {:mode :str-replace}`
  puts it back in the toolset -- so a flip of this default is a decision somebody
  can reverse without losing a capability.

  WHY A CONFIGURATION AND NOT A CHOICE THE MODEL MAKES. The two are not
  variations of one thing -- they hand the model different workspaces
  (`old_string` versus a per-line token) and offering both at once means the
  model reliably reaches for the wrong one. So the choice is made once, by
  whoever runs the session, and the toolset is BUILT to match it.

  WHERE IT LIVES, AND WHY NOT config.edn. In harness.edn, beside the fence's
  :approval, with the same two-level shape -- so a project that wants anchors can
  say so without the user's home agreeing. config.edn is deliberately NOT the
  place: its documented shape is exactly three knobs (provider, model,
  reasoning-effort), and a fourth knob there is a NAMED failure rather than a
  value somebody quietly drops (see the session-configure tool body). Editing
  policy is policy; it belongs with the rest of harness.edn.

  THE ONE DEPARTURE FROM harness.edn's SHALLOW MERGE, and it is paid for here.
  harness-config replaces a top-level key WHOLE, project wins -- right for
  :approval, where 'what will the fence do' should be legible in one file, and
  wrong for this one. :editing composes KEY BY KEY, so a project that wants to
  turn :auto-read off does not have to restate the block and re-decide every
  default the user chose. harness-config's own behavior is untouched.

  Read fresh on every call, like every other config in this harness: editing
  harness.edn moves the mode without a restart.

  A BROKEN BLOCK IS A NAMED FAILURE, never a quiet fallback to the defaults. The
  defaults are what a session gets when nobody SAID anything -- no harness.edn at
  all, or no :editing key in one. A file that exists and says something
  unreadable is a different situation, and conflating the two would make 'the
  project asked for anchors' indistinguishable from 'the project's config was
  ignored'. That is the same distinction harness.cap.project/read-harness-edn draws
  for the fence, and it is reused rather than reinvented: one rule, one
  implementation.

  KEYS NOBODY READS ARE A FAILURE IN EITHER FILE, even when shadowed. A typo'd
  :modes is not a value that loses a merge -- it is a request that was never
  going to be honoured by either level, so it is reported against the level that
  wrote it. The check therefore walks both blocks rather than the merged result.
  The VALUES, by contrast, are checked as EFFECTIVE: a project overriding a
  broken user value has to be able to fix it, so a shadowed-and-overridden value
  is not itself an error."
  (:require [clojure.string :as str]
            [harness.cap.project :as project]))

;; ------------------------------------------------------------------ defaults

(def defaults
  "What a session is served by when nobody has said anything. Every key here is
  one the two implementations actually read -- a default for a knob nothing
  consults would be a promise this namespace cannot keep.

  `:mode` is the one that decides which EDITION of this harness a session gets:
  anchor editing, or the exact-string editor it had before. See the namespace
  docstring for why it moved and what moves it back."
  {:mode               :hashline
   :auto-read          true
   :anchor-grep        true
   :require-path       false
   :strict-input       false
   :boundary-dedup     :on
   :diff-context-lines 1})

(def ^:private vocab
  "Key -> how to recognise a legal value, and the phrase naming what IS legal.
  DATA rather than a cond, because every failure message has to state the legal
  set and a hand-written message per key is exactly how the two drift apart:
  adding a value to a predicate and forgetting the sentence beside it would make
  the error tell the reader to do something that then fails again."
  {:mode               {:ok    #(contains? #{:hashline :str-replace} %)
                        :legal ":hashline or :str-replace"}
   :auto-read          {:ok    boolean? :legal "true or false"}
   :anchor-grep        {:ok    boolean? :legal "true or false"}
   :require-path       {:ok    boolean? :legal "true or false"}
   :strict-input       {:ok    boolean? :legal "true or false"}
   :boundary-dedup     {:ok    #(contains? #{:on :strict :off} %)
                        :legal ":on, :strict or :off"}
   :diff-context-lines {:ok    #(and (integer? %) (<= 0 % 10))
                        :legal "an integer 0-10"}})

(defn- known-keys-phrase []
  (str/join ", " (map pr-str (sort-by str (keys defaults)))))

;; ------------------------------------------------------------------- reading

(defn- blocks
  "The two :editing blocks as [{:level :path :block} ..], user first. Each level
  is TAGGED with the file it came from, because every failure below has to name a
  file: ':editing is the wrong shape' and 'that key is unknown' are only
  actionable once the reader knows which of the two harness.edn files to open.

  A block that is not a map is refused HERE, before anything merges: it is a
  statement about the file, not a value that loses a precedence contest."
  [thread-id]
  (let [{:keys [user project files]} (project/harness-edn-levels thread-id)]
    (mapv (fn [level]
            (let [path  (get files level)
                  block (:editing (get {:user user :project project} level))]
              (cond
                (nil? block) {:level level :path path :block {}}
                (map? block) {:level level :path path :block block}
                :else (throw (ex-info (str "harness.edn :editing must be an EDN map, but the "
                                           (name level) " level at " path
                                           " says " (pr-str block))
                                      {:path path :level level
                                       :reason :editing-not-a-map})))))
          [:user :project])))

(defn- origin
  "Key -> {:level .. :path ..} for every key that was SAID, later levels
  overwriting earlier ones exactly as the merge does. Keys nobody said are
  absent, which is how the value check below tells a stated value from a default
  -- the defaults are legal by construction and must never be re-validated."
  [ls]
  (reduce (fn [m {:keys [level path block]}]
            (reduce (fn [m k] (assoc m k {:level level :path path})) m (keys block)))
          {} ls))

(defn- check-known-keys! [ls]
  (doseq [{:keys [level path block]} ls
          k                          (keys block)]
    (when-not (contains? defaults k)
      (throw (ex-info (str "harness.edn :editing does not understand " (pr-str k)
                           " (the " (name level) " level at " path "); it takes "
                           (known-keys-phrase))
                      {:key k :path path :level level
                       :reason :unknown-editing-key})))))

(defn- check-values! [merged origin]
  (doseq [[k v] merged
          :when (contains? origin k)]
    (let [spec (vocab k)]
      (when-not ((:ok spec) v)
        (let [{:keys [level path]} (origin k)]
          (throw (ex-info (str "harness.edn :editing " k " must be " (:legal spec)
                               ", but the " (name level) " level at " path
                               " says " (pr-str v))
                          {:key k :value v :path path :level level
                           :reason :bad-editing-value})))))))

;; ---------------------------------------------------------------- resolution

(defn editing-mode
  "The editing configuration THREAD-ID's session is served by: `defaults` with
  the two harness.edn levels applied :editing-key by :editing-key, project
  winning. An unbound session composes the user level alone.

  Throws on a broken block, an unknown key, or an illegal effective value, each
  naming the file it came from. A missing file is not a broken one: an unbound
  session, or a home with no harness.edn at all, is the everyday case and
  answers with the defaults.

  Re-read on every call, so harness.edn edits take effect on the next ask."
  ([] (editing-mode nil))
  ([thread-id]
   (let [ls     (blocks thread-id)
         origin (origin ls)
         merged (merge defaults (:block (first ls)) (:block (second ls)))]
     (check-known-keys! ls)
     (check-values! merged origin)
     merged)))

;; ------------------------------------------------- which tools the mode serves

;; TWO IMPLEMENTATIONS, TWO TOOLSETS, AND ONE PLACE THAT DECIDES. A session is
;; served ONE editing toolset, never both: the two hand the model different
;; workspaces (an exact `old_string` versus a per-line anchor), and a model that
;; can see both will reach for whichever it recognises first -- after which the
;; file is being edited by two schemes whose bookkeeping disagrees.
;;
;; THIS OVERTURNS tool-toggles' "a tool never disappears from a toolset", and the
;; overturning is PAID FOR here rather than assumed. That ruling's argument was
;; that a model which cannot see a capability reads its absence as "this does not
;; exist" and goes looking for a way around it. The answer is that the absence is
;; never silent: a call to an unserved name is refused BY NAME, and the refusal
;; says what this session edits with instead and which harness.edn key switches
;; back. So the model learns the capability exists, learns what replaced it, and
;; learns how to get it -- which is more than the visible-but-refused version
;; ever told it.
;;
;; A tool named in NEITHER family belongs to no editing implementation and is
;; served always (bash, read, write, eval, hooks...). Membership is by tool NAME
;; and the table is data, so the mechanism has no per-tool code: adding an
;; anchor-mode tool is adding a name here.

(def families
  "Editing mode -> the tool NAMES it is served by, plus the phrases a refusal
  needs. `:edits-by` says what this mode's editing is, in the form the refusal
  reads out loud ('this session edits by anchor'); `:label` names the OTHER
  mode's tool as a thing rather than a command; `:substitute` names the tool to
  reach for instead. Kept beside the names rather than derived, because a message
  assembled from parts that live in three places is how an error ends up telling
  the reader to do something that does not work."
  {:str-replace {:tools      #{"edit"}
                 :edits-by   "an exact old_string"
                 :label      "the exact-string editor"
                 :substitute "edit"}
   :hashline    {:tools      #{"replace" "insert" "anchor_grep" "undo_last_replace"}
                 :edits-by   "anchor"
                 :label      "the anchor-based editor"
                 :substitute "replace"}})


(def ^:private family-of
  "Tool NAME -> the editing mode it belongs to. Derived from `families`, which is
  the only place a tool's allegiance is declared."
  (into {} (for [[mode {:keys [tools]}] families, n tools] [n mode])))

(def ^:private config-key-phrase
  ":editing {:mode %s} in harness.edn")

(def ^:private search-tool
  "The one tool inside a family that has a knob of its own, and the knob.

  `anchor_grep` searches rather than edits, so a session can reasonably want
  `replace`/`insert`/`undo_last_replace` without it -- and `:anchor-grep false`
  means exactly that: the tool is not served, and nothing takes its place. (The
  str-replace mode has no search tool of its own to fall back to; the alternative
  is `bash`, which the model may use whenever it likes.)"
  {"anchor_grep" :anchor-grep})

(defn served?
  "Is tool NAME served in THREAD-ID's session? True for a tool that belongs to no
  editing implementation (ask `family-of`) and for one belonging to the mode in
  force; false for the other mode's tools, which is what keeps a session's
  toolset down to ONE editing scheme -- and false for a tool this session has
  switched off with its own knob.

  The configuration is resolved per call, so a session that changes its
  harness.edn changes its toolset on the next ask -- there is no cache to
  invalidate and no restart to perform."
  [thread-id name]
  (let [config (editing-mode thread-id)
        family (get family-of name)
        knob   (get search-tool name)]
    (and (not (and knob (false? (get config knob))))
         (or (nil? family) (= family (:mode config))))))

(defn unserved-message
  "What the model is told when it calls a tool this session does not serve. It
  answers three questions in one sentence each: which capability this is, what
  takes its place, and how to get it back. Never 'unknown tool' -- the tool exists
  and is a real way to work with files; it is simply not this session's way, and
  saying otherwise would send the model hunting for a workaround to a restriction
  that is one config line deep."
  [thread-id name]
  (let [mode   (:mode (editing-mode thread-id))
        other  (if (= mode :hashline) :str-replace :hashline)
        knob   (get search-tool name)]
    (if (and knob (false? (get (editing-mode thread-id) knob)))
      ;; Switched off by its own key rather than taken away by the mode: saying
      ;; 'this session edits by anchor, and anchor_grep is the anchor-based
      ;; editor' would be nonsense, and the way back is a different key.
      (str name " is switched off in this session: harness.edn says "
           (pr-str knob) " false. Nothing takes its place -- use `bash` if you"
           " need a search. To switch it back, write :editing {" knob " true} in"
           " harness.edn.")
      (str name " is not served in this session: this session edits by "
           (get-in families [mode :edits-by])
           ", and " name " is " (get-in families [other :label]) "."
           " Use " (get-in families [other :substitute]) " instead."
           " To switch, write " (format config-key-phrase (pr-str other))
           "."))))
