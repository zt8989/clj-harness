(ns harness.infra.language
  "Which language this harness speaks, resolved from a chain of sources: config.edn's
  `:ui :language`, then the OPERATING SYSTEM's user language, then the TERMINAL's, then
  English.

  WHY THIS IS NOT THE JVM'S LOCALE. `java.util.Locale/getDefault` -- and `LANG` /
  `LC_ALL` with it -- describe the terminal this process happens to have been started
  from. A person can run the harness from a shell that says `LANG=en_US` while the
  machine's own language is Chinese, and the language a reader wants the harness to
  speak is the SYSTEM's, not the shell's. So the system half is asked of the OS itself
  (on macOS, the preferred-languages list `defaults read -g AppleLanguages`), and the
  terminal is only what answers after it.

  WHY config.edn IS THE FIRST SOURCE. The language is a setting of THIS home, living
  beside the provider and the model -- see harness.cap.providers for the file and its
  shape. It is read FRESH every time, the way the rest of that file is, so a person who
  edits it gets the new language on the next run without a restart.

  THE LIST IS CLOSED. Two languages; a third is another locales/ directory in the UI
  plus one entry here. A tag this list does not name is not an error -- it is 'no answer
  from this source', and the chain moves on. Only the chain's end is a fallback."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [harness.infra.env :as env]
            [harness.infra.home :as home]
            [harness.infra.shell :as shell]))

(def supported
  "The languages this harness speaks, as keywords. THE LIST IS CLOSED, and it is the
  same pair the interface's own catalogs hold: adding a language is a locales/ directory
  there plus a row here, and neither side may quietly accept a third."
  #{:en :zh})

(def names
  "What each supported language is CALLED, for the line the model reads. The tag alone
  (`zh`) is the value a machine matches on; the name is what a reader -- a model reading
  a system prompt -- is actually told to write in."
  {:en "English" :zh "Chinese"})

(def section-keys
  "The keys config.edn's `:ui` section may carry. One today, and a named set rather than
  an open map because the top level is closed: a `:ui` typo must fail by name the way a
  top-level typo does, not sit there doing nothing."
  #{:language})

(defn base-language
  "A language tag reduced to one this harness speaks, or nil.

  THE BASE SUBTAG IS WHAT DECIDES, and the same collapse the interface's own resolver
  makes: `zh`, `zh-CN`, `zh_TW` and `zh-Hans-CN` are all Chinese, `en_US.UTF-8` is
  English, and a language nobody here speaks (`fr`, `C`, `POSIX`) is nil. Nil keeps the
  chain moving -- 'I do not know this tag' and 'the answer is English' are different
  facts, and only the caller knows whether there is another source to ask."
  [tag]
  (when (string? tag)
    (let [base (-> tag
                   str/lower-case
                   (str/replace "_" "-")
                   (str/split #"-")
                   first)]
      (case base "en" :en "zh" :zh nil))))

(defn config-language
  "The language config.edn's `:ui` section names, or nil when it names none.

  READ FRESH, like the rest of config.edn. A malformed file is NOT this function's
  failure to report: harness.cap.providers owns the file's shape and answers a broken
  one by name. Here a file that cannot be parsed is simply 'no answer from this source',
  so the block that states the language cannot turn a config typo into a second, worse
  sentence about itself."
  []
  (try
    (let [raw (edn/read-string (home/config))]
      (when-let [language (get-in raw [:ui :language])]
        (when (contains? supported language) language)))
    (catch Exception _ nil)))

(def ^:private system-timeout-ms
  "How long the OS may take to name its language. Short: one small `defaults` read, in
  front of a run. A machine that cannot answer says so by answering nil, and the chain
  moves to the terminal."
  5000)

(defn- system-language*
  "The OS's user language, asked of the OS rather than of this process's locale -- or nil
  when this platform has no answer here. The uncached read behind `system-language`.

  MACOS ASKS ITS PREFERRED-LANGUAGES LIST. `defaults read -g AppleLanguages` answers an
  ordered plist array (`zh-Hans`, `zh-Hans-CN`, `en-CN`); the FIRST tag it can reduce is
  the answer. OTHER PLATFORMS RETURN NIL ON PURPOSE: a headless Linux box has no single
  'system language' a shell can be asked for, and answering with `LANG` here would be
  the terminal wearing the system's name. Nil keeps that honest and hands the question
  to the next link."
  []
  (case (env/platform)
    "macos"
    (try
      (let [{:keys [exit out timeout]}
            (shell/run {:command "defaults read -g AppleLanguages"
                        :timeout-ms system-timeout-ms})]
        (when (and (not timeout) (= 0 exit))
          (->> (re-seq #"[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]+)*" (str out))
               (keep base-language)
               first)))
      (catch Exception _ nil))
    nil))

(defonce ^:private system-probed
  ;; A fact about the MACHINE, asked once -- the same rule and shape as
  ;; harness.infra.env's enhancer probe: held in a vector so that nil (this machine
  ;; would not answer) is a cached answer too, rather than a question asked on every
  ;; run.
  (atom nil))

(defn system-language
  "The OS's user language, or nil when this platform could not be asked. ONE SPAWN PER
  PROCESS, cached: a machine's own language does not change while a session runs, and a
  probe on every run would put a shell spawn in front of every message."
  []
  (if-let [cached @system-probed]
    (first cached)
    (let [language (system-language*)]
      (reset! system-probed [language])
      language)))

(defn reset-system!
  "Forget the cached system answer so the next `system-language` asks again. For tests
  that drive each link of the chain; a running process's machine does not change under
  it."
  []
  (reset! system-probed nil))

(defn terminal-language
  "The language this process's own environment is set to -- `LC_ALL`, then `LANG`, then
  the JVM's locale -- reduced to one this harness speaks, or nil.

  THE LAST LINK BEFORE ENGLISH, and deliberately the least trusted: it is the shell the
  harness was started from, not the machine."
  []
  (or (base-language (not-empty (System/getenv "LC_ALL")))
      (base-language (not-empty (System/getenv "LANG")))
      (base-language (System/getProperty "user.language"))))

(defn resolved
  "The language this harness speaks, by the chain: config.edn, the OS's user language,
  the terminal's, then English. Never nil -- the end of the chain is a real answer."
  []
  (or (config-language) (system-language) (terminal-language) :en))

(defn line
  "The `<env>` block's language line: the value, with what to CALL it.

  IT ALWAYS EXISTS. This line is the anchor the system prompt and the `ask` tool both
  point at -- 'answer in the language `<env>` names' -- so it may not vanish because a
  source was absent. The chain's end is English, and that is what gets stated.

  The tag is the value a machine matches on; the name is what a model reading the
  prompt is told to write in. Both, because either alone makes a reader guess."
  []
  (let [language (resolved)]
    (str "language: " (names language) " (" (name language) ")")))
