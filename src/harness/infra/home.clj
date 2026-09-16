(ns harness.infra.home
  "The one place that decides where this process keeps its files: config,
  providers, the .env that holds the api-key, the metadata store, and the tree
  the logs live in. Everything else derives its paths from here -- no bare
  relative slurp anywhere.

  TWO PATHS IN THE LOG TREE, TWO NAMESPACES, ONE DIRECTION. This namespace knows
  the tree's root (projects-dir) and the rule that turns an id into a filename
  (sanitize). Which WORKSPACE -- which project's directory -- a session's log
  belongs in needs the session's project, and that comes from the store, so the
  join lives in harness.edge.http (log-dir-for) and requires this namespace, never the
  reverse. Keeping it that way is what stops the kernel's knowledge of 'where
  things are' from depending on the database.

  The root is, in order of precedence:

    1. *root-override*        -- a dynamic var, BOUND BY TESTS ONLY. Binding
                                scope is its whole lifetime: it is a parameter
                                injected for one test run, not a second source
                                of configuration.
    2. CLJ_HARNESS_HOME       -- the environment variable. This is how a real
                                deployment relocates the root.
    3. ~/.clj-harness         -- the default.

  All of it is re-derived on every call (reading an env var is cheap), matching
  the config.edn rule: read it fresh, never cache it. A cached root would make
  both the environment variable and a test binding stop working mid-process.

  prompt.md is the deliberate exception and lives in the repository, not here:
  it is a reviewed code asset whose every change needs git history. Only the
  runtime state lives under this root.

  THE OS HOME IS A SECOND, SEPARATE FLOOR, and it is the second one this file
  answers about. `user-home` is where the HOST keeps its own conventions --
  ~/AGENTS.md, ~/.agents/skills -- which is a different question from where
  harness keeps its configuration, and the two answers must not be the same
  value by accident. Relocating this process's root (a deployment, a test run)
  must NOT move the host's convention directory out from under the skills and
  instructions that live there, which is why user-home follows no configuration
  at all and reads the same property on every platform."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:dynamic *root-override*
  "Test-only root override. Bound (never set!) by the test fixture so a run
  reads and writes a temp directory instead of the real one. Production code
  leaves this nil, so CLJ_HARNESS_HOME remains the single production knob."
  nil)

(def ^:dynamic *user-home-override*
  "Test-only override for USER-HOME, for the same reason and by the same rule as
  *root-override*: a test run must not read the developer's real ~/AGENTS.md or
  the skills in their ~/.agents/skills, or the suite would depend on one person's
  home directory -- and every existing assertion would gain messages it never
  asked for. Unbound in production, where the JVM's own user.home stands."
  nil)

(defn root
  "This process's configuration root, as a string path."
  []
  (or *root-override*
      (System/getenv "CLJ_HARNESS_HOME")
      (str (System/getProperty "user.home") "/.clj-harness")))

(defn user-home
  "The OS home directory, as a string path -- where the HOST's conventions live,
  not where harness keeps anything. Nothing under CLJ_HARNESS_HOME moves it: a
  deployment that relocates its configuration has not moved the machine's home
  directory, and a skills catalog that vanished because an env var changed would
  be the wrong kind of surprise.

  Deliberately NOT cached, matching root and the config.edn rule: the test
  fixture moves this mid-process, and a cached value would make that binding
  stop working."
  []
  (or *user-home-override*
      (System/getProperty "user.home")))

(defn config-file    [] (io/file (root) "config.edn"))

(defn providers-file
  "The RETIRED provider catalog file. Nothing reads it as a catalog any more -- the
  catalog is config.edn's :providers section -- and this accessor survives for two
  reasons only: the reader that refuses a home still holding one has to name it,
  and the tests have to plant one to prove that refusal fires. A path is a path;
  this one is kept so the failure can be about a file rather than a string."
  []
  (io/file (root) "providers.edn"))

(defn hooks-file     [] (io/file (root) "hooks.edn"))
(defn mcp-file       [] (io/file (root) "mcp.edn"))
(defn dotenv-file    [] (io/file (root) ".env"))

(defn config-backup-file
  "The one-generation backup a rewrite of config.edn leaves behind, so a write that
  loses something a person wrote is recoverable. NOT listed among the home's files:
  it is not a thing anyone edits, it is a thing anyone may recover FROM."
  []
  (io/file (root) "config.edn.bak"))

(defn spit-atomically!
  "Write TEXT to F by writing a SIBLING temp file and renaming it over the target,
  so a failure part-way through leaves the previous contents rather than a truncated
  file. A sibling, so the rename is atomic on one filesystem -- the same discipline
  harness.cap.hashline.files/write-file! follows for the files it edits.

  HERE RATHER THAN IN THE ONE CALLER because it is a fact about this home's files
  rather than about any one of them, and because the next caller should not have to
  invent a third version of it. Returns F."
  [^java.io.File f ^String text]
  (let [tmp (io/file (str (.getAbsolutePath f) ".writing"))]
    (spit tmp text :encoding "UTF-8")
    (java.nio.file.Files/move (.toPath tmp) (.toPath f)
                              (into-array java.nio.file.CopyOption
                                          [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))
    f))

(defn write-env-line!
  "NAME=VALUE as one line of this home's .env: the line is REPLACED where it already
  is, and APPENDED when it is not.

  LINE SURGERY, NOT A REWRITE, and that is the whole point: a .env is hand-written
  and may carry a paragraph explaining each key, other tools' variables, `export`
  prefixes and quotes. Every other byte -- other lines, their order, their comments,
  their quoting -- comes out exactly as it went in, and an existing `export` prefix
  is kept.

  A VALUE WITH A NEWLINE IS REFUSED: this file is one fact per line, and a value
  that spans lines would silently become several facts, one of which is
  `sk-…` with no name. The newline style already in the file is used for anything
  appended, so a CRLF file does not end up half one and half the other.

  Returns F. The VALUE is not returned, logged or kept: it arrived from the caller,
  who already had it."
  [name value]
  (when (re-find #"[\r\n]" (str value))
    (throw (ex-info (str "a .env value cannot span lines (writing " (pr-str name) ")")
                    {:name name})))
  (let [f     (dotenv-file)
        old   (when (.exists f) (slurp f :encoding "UTF-8"))
        nl    (if (and old (str/includes? old "\r\n")) "\r\n" "\n")
        re    (re-pattern (str "(?m)^(export\\s+)?" (java.util.regex.Pattern/quote name) "\\s*=.*$"))
        found (when old (re-find re old))
        entry (str (when (and found (nth found 1)) "export ") name "=" value)]
    (spit-atomically!
     f
     (cond
       (nil? old) (str entry nl)
       (some? found) (str/replace-first old re (java.util.regex.Matcher/quoteReplacement entry))
       :else (str old (when-not (str/ends-with? old nl) nl) entry nl)))))

(defn parse-dotenv
  "A .env file's contents -> a {name value} map. Handles the shapes the format
  actually uses: `export` prefixes, surrounding single or double quotes, `#`
  comments, blank lines, and values that themselves contain `=` (only the first
  `=` splits).

  We parse it ourselves rather than lean on the dotenv library because that library
  resolves `.env` from the CURRENT DIRECTORY at namespace-load time and caches it in
  a def -- so it cannot be pointed at harness.infra.home, and it would miss an edit made
  while the process runs. Both of those matter here.

  PUBLIC because there is a second reader with a different question:
  harness.cap.providers/api-key-source reports WHERE a key comes from and must not have
  the value itself, so it asks this for the map and only tests a name's presence in
  it. Everything that wants a value uses `env-value` instead, and the precedence
  between the file and the environment -- and the order among several names -- is
  decided in `env-source`, once."
  [raw]
  (into {}
        (->> (str/split-lines raw)
             (map str/trim)
             (remove #(or (empty? %) (str/starts-with? % "#")))
             (map #(str/split % #"=" 2))
             (filter #(= 2 (count %)))
             (map (fn [[k v]]
                    [(str/replace (str/trim k) #"^export\s+" "")
                     (let [v (str/trim v)]
                       (if (and (>= (count v) 2)
                                (or (and (str/starts-with? v "\"") (str/ends-with? v "\""))
                                    (and (str/starts-with? v "'") (str/ends-with? v "'"))))
                         (subs v 1 (dec (count v)))
                         v))])))))

(defn env-source
  "NAMES, in the order they should be tried -> {:name .. :source :env-file|:environment},
  or {:name nil :source nil} when neither source has any of them.

  SOURCE-MAJOR, and that ordering is the RULE rather than an implementation
  detail: the home's .env is the single place that decides, so it is asked about
  EVERY name before the environment is asked about any. Specificity -- a
  provider's own derived name before the global fallback -- decides WITHIN a
  source, and only there. Name-major would be the other way round, and it would
  quietly break the promise this function's neighbour makes: an exported shell
  variable would override a file the person edited on purpose.

  NEVER THE VALUE, and that is why this exists as well as `env-value`: the
  settings panel reports WHICH line supplies a key (or which line to add), and
  reading the secret to answer a question about its presence is a habit worth not
  forming. `env-value` is this same order for a single name -- one rule, two
  questions, no second copy to drift.

  The file lives in the CONFIGURATION HOME (this namespace's root), re-read on
  every call like config.edn, so editing it takes effect without a restart."
  [names]
  (let [f (dotenv-file)
        from-file (when (.exists f)
                    (parse-dotenv (slurp f :encoding "UTF-8")))]
    (or (some (fn [n] (when (contains? from-file n) {:name n :source :env-file})) names)
        (some (fn [n] (when (some? (System/getenv n)) {:name n :source :environment})) names)
        {:name nil :source nil})))

(defn env-value
  "NAME's value from the home's .env, then from the environment -- nil when neither
  has it.

  ONE PLACE, TWO USERS, and that is why it is here rather than in either of them: a
  provider's api-key and a search vendor's key are the same kind of fact (a secret
  the person running this put somewhere OUTSIDE the repository), and two copies of
  the lookup would eventually disagree about precedence -- which is the only part of
  it anybody ever has to reason about.

  .ENV WINS OVER A REAL ENVIRONMENT VARIABLE, which is the dotenv library's
  documented precedence and what the provider key has always done: the home's file
  is the single place that decides, and a shell variable does not override it.
  `env-source` owns that order (and extends it across several names); this is it for
  one name, so a caller with a name in hand does not have to re-derive the rule.

  The file lives in the CONFIGURATION HOME (harness.infra.home/root), which is not the OS
  home -- and is re-read on every call, like config.edn, so editing it takes effect
  without a restart.

  NEVER LOGGED, NEVER RETURNED. What a caller does with the value is the caller's
  discipline; the api-key rule in prompt.md is what makes it a rule rather than a
  convention."
  [name]
  (let [f (dotenv-file)
        from-file (when (.exists f)
                    (get (parse-dotenv (slurp f :encoding "UTF-8")) name))]
    (or from-file (System/getenv name))))

(defn db-file
  "The home's metadata store -- see harness.infra.db. It lives beside the configuration
  files rather than under any one feature's directory: it is this home's store,
  and it has more than one tenant."
  []
  (io/file (root) "harness.db"))
(defn projects-dir
  "The tree the logs live in: one workspace directory per project, plus one
  reserved workspace for sessions that belong to none. This is the ROOT of that
  tree and nothing more -- which workspace a given session writes into is not a
  question about the root, so it is not answered here (see harness.edge.http's
  log-dir-for, which resolves the session's project first and is the only place
  the two facts are joined)."
  []
  (io/file (root) "projects"))

(defn sanitize
  "A thread id -> a filename-safe stem, AND a project path -> a directory name.
  The ONE rule the writer (harness.edge.http) and the readers (harness.edge.replay) must
  agree on: it is shared here so they cannot drift, while the paths around it
  stay separate -- replay takes its directory from the caller and never learns
  about this namespace, because the kernel must not read its own log."
  [thread-id]
  (str/replace (str thread-id) #"[^A-Za-z0-9._-]" "_"))

(defn log-file
  "The jsonl file for THREAD-ID, inside DIR.

  DIR is the CALLER's, and that is the whole shape of this function: which
  directory a session's log belongs in is a question about the session's project,
  which this namespace deliberately does not know (see projects-dir). The two
  sides that must agree are the filename rule -- shared here -- and the tree
  layout, which belongs to the writers and readers of the log tree. So the
  writer asks harness.edge.http for the directory and hands it in, and replay is
  handed one by its caller and stays a pure reader that knows nothing of this
  home."
  [dir thread-id]
  (io/file dir (str (sanitize thread-id) ".jsonl")))

(defn log-path
  "The same file as a STRING -- what asks like 'where is this conversation's log'
  want, since the answer usually goes into a message rather than into a file
  operation. Computed fresh every call, like everything else here."
  [dir thread-id]
  (str (log-file dir thread-id)))

(defn config
  "config.edn's text, re-read every time so it can be edited while the process runs.

  A MISSING FILE READS AS AN EMPTY ONE, and that is a reversal of what this function
  used to do (it refused by name, carrying the path) made deliberately: 'there is no
  file' and 'the file says nothing' are the same fact about a home, and the shape
  already spells 'says nothing'. The refusal was there so a missing file would not
  silently mean 'no configuration' -- but nothing is silent about it now: a run with
  no default tier fails by name and teaches the shape to write, and the built-in
  provider table stands on its own either way.

  IT CREATES NOTHING, which is why this stays a read: harness.cap.providers seeds the
  file at boot (that is what a process preparing its own home looks like), while a
  read-only caller -- an offline tool, the settings panel, a test asking what a home
  says -- leaves the home exactly as it found it."
  []
  (let [f (config-file)]
    (if (.exists f) (slurp f :encoding "UTF-8") "")))

;; ------------------------------------------------- configured lists of paths
;;
;; Two keys in harness.edn name lists of places to look -- :skills {:roots ..}
;; and :instructions {:files ..} -- and both mean the same thing by them: a list
;; of directories or files, relative entries resolved against the session's
;; project directory like any tool path. The SHAPE CHECK lives here rather than
;; in either consumer because it is this file's business (harness.edn is where
;; these keys come from, and this is the namespace that owns harness.edn), and
;; because the two consumers must not require each other: the skills half has to
;; stay reachable from harness.cap.project, and the preamble half does not.

(defn- config-files
  "The harness.edn files a value could have come from, as absolute paths -- the
  user level always, the bound project's when a project is bound. A failure
  names these rather than a bare key: 'the configuration' is not a file, and a
  reader told only the key has nowhere to go and look."
  [project-dir]
  (cond-> [(str (io/file (root) "harness.edn"))]
    project-dir (conj (str (io/file project-dir ".harness" "harness.edn")))))

(defn as-config-section
  "SECTION -> a map, or a NAMED failure when it is neither a map nor nil.

  Nil means the key was absent, which is the everyday case and the empty
  configuration. Anything else that is not a map -- {:skills 42}, a string, a
  vector -- fails HERE, before a consumer asks it for a key: `contains?` on a
  non-map throws a bare JVM error, which names neither the key nor the file, and
  a config mistake the reader cannot locate is the failure mode this whole
  discipline exists to prevent."
  [section key-name project-dir]
  (cond
    (nil? section) {}
    (map? section) section
    :else
    (let [files (config-files project-dir)]
      (throw (ex-info (str key-name " is " (pr-str section) ", not a map; write "
                           key-name " {:..}, e.g. " key-name " {} to say nothing"
                           " -- in " (if (= 1 (count files))
                                       (first files)
                                       (str (first files) " or " (second files))))
                      {:value section :files files})))))

(defn resolve-against
  "PATH as this session will use it: a RELATIVE path resolves against
  PROJECT-DIR when one is bound, and passes through unchanged when none is;
  absolute paths always pass through.

  The rule harness.cap.project/resolve-path applies to a tool's path argument,
  stated over an EXPLICIT directory so that the namespaces which must not
  require harness.cap.project can still honour it -- a relative entry in harness.edn
  has to mean the same thing wherever it is written."
  [project-dir path]
  (let [f (io/file path)]
    (if (or (.isAbsolute f) (nil? project-dir))
      path
      (str (io/file project-dir path)))))

(defn path-list
  "VALUES -> a vector of path strings, or a NAMED failure naming what arrived, an
  example of what to write instead, and which files to look in. EXPECTED is the
  example fragment the message quotes.

  An EMPTY vector is accepted and means what it says -- read nothing -- which is
  how a session turns one of these surfaces off without turning off everything
  that reads harness.edn. Refusing it would leave 'I want none' expressible only
  by pointing at a path that does not exist."
  [values expected key-name project-dir]
  (if (and (sequential? values) (every? string? values))
    (mapv identity values)
    (let [files (config-files project-dir)]
      (throw (ex-info (str key-name " is " (pr-str values)
                           ", not a list of paths; write " expected
                           " -- in " (if (= 1 (count files))
                                       (first files)
                                       (str (first files) " or " (second files))))
                      {:value values :files files})))))
