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
  runtime state lives under this root."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:dynamic *root-override*
  "Test-only root override. Bound (never set!) by the test fixture so a run
  reads and writes a temp directory instead of the real one. Production code
  leaves this nil, so CLJ_HARNESS_HOME remains the single production knob."
  nil)

(defn root
  "This process's configuration root, as a string path."
  []
  (or *root-override*
      (System/getenv "CLJ_HARNESS_HOME")
      (str (System/getProperty "user.home") "/.clj-harness")))

(defn config-file    [] (io/file (root) "config.edn"))
(defn providers-file [] (io/file (root) "providers.edn"))
(defn hooks-file     [] (io/file (root) "hooks.edn"))
(defn dotenv-file    [] (io/file (root) ".env"))
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
