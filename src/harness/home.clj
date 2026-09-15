(ns harness.home
  "The one place that decides where this process keeps its files: config,
  providers, the .env that holds the api-key, the jsonl logs, and the metadata
  store. Everything else derives its paths from here -- no bare relative slurp
  anywhere.

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
(defn logs-dir       [] (io/file (root) "logs"))
(defn db-file
  "The home's metadata store -- see harness.db. It lives beside the configuration
  files rather than under any one feature's directory: it is this home's store,
  and it has more than one tenant."
  []
  (io/file (root) "harness.db"))

(defn sanitize
  "A thread id -> a filename-safe stem. The ONE rule the writer (harness.http)
  and the reader (harness.replay) must agree on: it is shared here so they
  cannot drift, while the paths around it stay separate -- replay takes its
  directory from the caller and never learns about this namespace, because the
  kernel must not read its own log."
  [thread-id]
  (str/replace (str thread-id) #"[^A-Za-z0-9._-]" "_"))

(defn log-file
  "The jsonl file for THREAD-ID.

  Two arities on purpose: the writer under this root, and replay's -- which is
  handed a DIRECTORY by its caller and must stay a pure reader that knows
  nothing of harness.home. Both go through sanitize, so the two sides agree on
  the filename without the reader depending on the writer's home."
  ([thread-id]     (io/file (logs-dir) (str (sanitize thread-id) ".jsonl")))
  ([dir thread-id] (io/file dir (str (sanitize thread-id) ".jsonl"))))

(defn log-path
  "The same file as a STRING -- what asks like 'where is this conversation's log'
  want to hear, since the answer usually goes into a message rather than into a
  file operation. Computed fresh every call, like everything else here: the root
  can move (CLJ_HARNESS_HOME, a test binding) between calls, and a cached path
  would silently point at the wrong file."
  [thread-id]
  (str (log-file thread-id)))

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
