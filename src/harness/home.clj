(ns harness.home
  "The one place that decides where this process keeps its files: config,
  providers, the .env that holds the api-key, the metadata store, and the tree
  the logs live in. Everything else derives its paths from here -- no bare
  relative slurp anywhere.

  TWO PATHS IN THE LOG TREE, TWO NAMESPACES, ONE DIRECTION. This namespace knows
  the tree's root (projects-dir) and the rule that turns an id into a filename
  (sanitize). Which WORKSPACE -- which project's directory -- a session's log
  belongs in needs the session's project, and that comes from the store, so the
  join lives in harness.http (log-dir-for) and requires this namespace, never the
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
(defn providers-file [] (io/file (root) "providers.edn"))
(defn hooks-file     [] (io/file (root) "hooks.edn"))
(defn dotenv-file    [] (io/file (root) ".env"))

(defn parse-dotenv
  "A .env file's contents -> a {name value} map. Handles the shapes the format
  actually uses: `export` prefixes, surrounding single or double quotes, `#`
  comments, blank lines, and values that themselves contain `=` (only the first
  `=` splits).

  We parse it ourselves rather than lean on the dotenv library because that library
  resolves `.env` from the CURRENT DIRECTORY at namespace-load time and caches it in
  a def -- so it cannot be pointed at harness.home, and it would miss an edit made
  while the process runs. Both of those matter here.

  PUBLIC because there is a second reader with a different question:
  harness.providers/api-key-source reports WHERE a key comes from and must not have
  the value itself, so it asks this for the map and only tests a name's presence in
  it. Everything that wants a value uses `env-value` instead, which is where the
  precedence between the file and the environment is decided -- once."
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

  The file lives in the CONFIGURATION HOME (harness.home/root), which is not the OS
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
  "The home's metadata store -- see harness.db. It lives beside the configuration
  files rather than under any one feature's directory: it is this home's store,
  and it has more than one tenant."
  []
  (io/file (root) "harness.db"))
(defn projects-dir
  "The tree the logs live in: one workspace directory per project, plus one
  reserved workspace for sessions that belong to none. This is the ROOT of that
  tree and nothing more -- which workspace a given session writes into is not a
  question about the root, so it is not answered here (see harness.http's
  log-dir-for, which resolves the session's project first and is the only place
  the two facts are joined)."
  []
  (io/file (root) "projects"))

(defn sanitize
  "A thread id -> a filename-safe stem, AND a project path -> a directory name.
  The ONE rule the writer (harness.http) and the readers (harness.replay) must
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
  writer asks harness.http for the directory and hands it in, and replay is
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
  "config.edn, re-read every time so it can be edited while the process runs.
  A missing file is a hard, NAMED failure: the message carries the absolute
  path and the knob that moves it, so the reader knows exactly what to create
  and where."
  []
  (let [f (config-file)]
    (when-not (.exists f)
      (throw (ex-info (str "config.edn not found at " (.getAbsolutePath f)
                           "; create it or set CLJ_HARNESS_HOME")
                      {:path (.getAbsolutePath f)})))
    (slurp f :encoding "UTF-8")))

;; ------------------------------------------------- configured lists of paths
;;
;; Two keys in harness.edn name lists of places to look -- :skills {:roots ..}
;; and :instructions {:files ..} -- and both mean the same thing by them: a list
;; of directories or files, relative entries resolved against the session's
;; project directory like any tool path. The SHAPE CHECK lives here rather than
;; in either consumer because it is this file's business (harness.edn is where
;; these keys come from, and this is the namespace that owns harness.edn), and
;; because the two consumers must not require each other: the skills half has to
;; stay reachable from harness.project, and the preamble half does not.

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

  The rule harness.project/resolve-path applies to a tool's path argument,
  stated over an EXPLICIT directory so that the namespaces which must not
  require harness.project can still honour it -- a relative entry in harness.edn
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
