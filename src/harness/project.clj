(ns harness.project
  "A session's project directory. thread-id -> binding is this namespace's
  whole state: an atom keyed by thread-id, asked rather than copied, nil meaning
  the normal case of NO binding. It is also the whole answer to 'where is this
  session rooted', which is why asking about it needs nothing beyond this
  namespace.

  The binding re-roots the file tools and the shell for one session:

    - resolve-path maps a RELATIVE path into the project directory; an
      absolute path passes through untouched.
    - binding-for answers the directory (or nil), which is what bash needs for
      its working directory, what the project endpoints report, and what a
      session asking about itself is told.

  A binding is a DEFAULT, not a fence -- the enforcement question is a separate,
  explicit boolean: out-of-bounds? answers whether a resolved path stays inside
  the directories a bound session is allowed to touch. The fence engages ONLY
  when a binding exists, so an unbound session gets false for everything -- the
  same regression guarantee resolve-path makes. What to DO about an
  out-of-bounds answer (park it, ask a human) is the tool seam's business, not
  this namespace's: here it is still only the WHERE question, now also stated
  as containment.

  The allowed set is itself configurable per project: .harness/harness.edn in
  the bound project (overlaid on the configuration home's user-level
  harness.edn) can add allow paths and tighten the fence. See harness-config
  for the two-level shape, and out-of-bounds? for what the fence does with it.

  WHAT A SESSION OPENS WITH is answered here too, for a structural reason rather
  than a topical one: skill-roots and preamble-files pair a harness.edn value
  with the session's binding, and this is the only namespace that can see the
  config reader, the binding, and both of their (deliberately pure, deliberately
  project-free) consumers."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.home :as home]
            [harness.preamble :as preamble]
            [harness.skills :as skills])
  (:import (java.io File)))

(defonce ^:private bindings
  (atom {}))
;; thread-id -> absolute path string of the project directory

(defn- absolute
  "DIR as an absolute path string. Kept as the input's own form where possible
  (getAbsolutePath does not chase symlinks), because the value is echoed back
  to the user and handed to ProcessBuilder -- both want what was asked for,
  resolved against the process working directory, not canonicalized."
  [dir]
  (str (.getAbsoluteFile (io/file dir))))

(defn bind!
  "Bind THREAD-ID's session to directory DIR, which must exist and be a
  directory -- anything else throws a NAMED error, because a typo'd path must
  fail where it was typed, not surface later as a confusing read failure.
  Returns the absolute path the binding stored.

  DIR of nil DROPS the binding (the unbind direction; rebinding to another
  directory is just binding again -- the last bind wins)."
  ([dir] (bind! nil dir))
  ([thread-id dir]
   (if (nil? dir)
     (do (swap! bindings dissoc thread-id)
         nil)
     (let [f (io/file dir)]
       (when-not (.exists f)
         (throw (ex-info (str "no such directory: " dir)
                         {:path (str dir) :reason :missing})))
       (when-not (.isDirectory f)
         (throw (ex-info (str "not a directory: " dir)
                         {:path (str dir) :reason :not-a-directory})))
       (let [abs (absolute dir)]
         (swap! bindings assoc thread-id abs)
         abs)))))

(defn binding-for
  "The project directory THREAD-ID's session is bound to, as an absolute path
  string -- or nil. Nil is the explicit, everyday answer for NO binding, never
  an error: most sessions are unbound, and an unbound session must behave
  exactly as it did before this namespace existed."
  [thread-id]
  (get @bindings thread-id))

(defn cwd-changed
  "The CwdChanged hook-event FACTS for a binding change: BEFORE (the previous
  directory, nil for a first bind) -> AFTER (the directory now bound). This is
  the event source the hook engine (P2) will wire at the binding-change point
  -- the payload shape is locked here, with a test, so it cannot drift between
  this ticket and the wiring. Field names follow the hook-payload convention
  (snake_case, aligned with the CodeBuddy list). What to DO with the event --
  spawning commands, timeouts, gating -- is the hook engine's business, not
  this namespace's: here it is only the fact that the working directory moved."
  [thread-id before after]
  {:hook        "CwdChanged"
   :thread_id   thread-id
   :project_dir after
   :before      before})

(defn resolve-path
  "PATH as this session will use it: relative paths resolve against the
  session's project directory when one is bound, and pass through UNCHANGED
  when none is; absolute paths always pass through. Absolute means absolute
  per java.io.File (a drive letter on Windows, a leading / on Unix).

  The unbound case is the identity function ON PURPOSE: it is the regression
  guarantee that a session without a binding sees byte-for-byte the behavior
  the tools had before project directories existed."
  ([path] (resolve-path nil path))
  ([thread-id path]
   (let [f (io/file path)]
     (if (or (.isAbsolute f) (nil? (binding-for thread-id)))
       path
       (str (io/file (binding-for thread-id) path))))))

(defn- under?
  "Canonical containment: PATH's canonical form is DIR's canonical form or
  starts with it followed by a separator -- so C:\\proj contains C:\\proj\\a.txt
  but never C:\\project2, however alike the strings look. Both sides are
  canonicalized, so .. segments, case differences and symlinks collapse to the
  same answer whatever form the path arrived in. A path that cannot be
  canonicalized at all (a broken link, an invalid name) is provably inside
  NOTHING and answers false -- the caller decides what an unprovable path
  costs, and for the fence that cost is an approval."
  [path dir]
  (try
    (let [cp  (.getCanonicalPath (io/file path))
          cd  (.getCanonicalPath (io/file dir))
          sep (File/separator)]
      (boolean (or (= cp cd)
                   (str/starts-with? cp (if (.endsWith cd sep) cd (str cd sep))))))
    (catch Exception _ false)))

(defn- read-harness-edn
  "One harness.edn FILE, or {} when it does not exist -- a missing file is the
  empty configuration, never an error: most projects have no .harness/ at all.
  A file that EXISTS but is broken -- not valid EDN, or not a map -- is a hard,
  NAMED failure carrying the absolute path. A silently ignored config would be
  indistinguishable from a config that says nothing, and the fence's whole
  meaning depends on that distinction never blurring."
  [file]
  (let [f (io/file file)]
    (if-not (.exists f)
      {}
      (let [abs (.getAbsolutePath f)
            v   (try
                  (edn/read-string (slurp f :encoding "UTF-8"))
                  (catch Exception e
                    (throw (ex-info (str "harness.edn is not valid EDN: " abs
                                         " (" (ex-message e) ")")
                                    {:path abs :reason :invalid-edn}))))]
        (when-not (map? v)
          (throw (ex-info (str "harness.edn must be an EDN map: " abs)
                          {:path abs :reason :not-a-map})))
        v))))

(defn harness-config
  "The .harness/harness.edn configuration for THREAD-ID, merged from two
  levels: the configuration home's harness.edn (the USER level), overlaid by
  the bound project's .harness/harness.edn (the PROJECT level).

  The merge is a SHALLOW merge of top-level keys, project wins -- a project
  :approval replaces the user's whole :approval, it does not merge into it.
  That is deliberate, and now written down: a deep merge would make 'what
  will the fence actually do' a function of how two files nest, when the
  point of a project-level override is to be legible in one file.

  Each level is read fresh on every call (the config.edn discipline), so
  editing harness.edn moves the fence without a restart. A missing level is
  the empty map. The first consumer is the approval boundary:
  :approval {:allow [..]} adds paths to the fence's allowed set,
  :approval {:strict true} removes the project directory from it. The
  skills/mcp/hooks subdirectories of .harness/ are RESERVED for their own
  consumers -- this reader only ever opens harness.edn."
  ([] (harness-config nil))
  ([thread-id]
   (merge (read-harness-edn (io/file (home/root) "harness.edn"))
          (when-let [dir (binding-for thread-id)]
            (read-harness-edn (io/file dir ".harness" "harness.edn"))))))

;; ------------------------------------------- what a session opens with
;;
;; THE COMPOSITION LIVES HERE, and this is the only namespace that can host it.
;; Two questions -- "which directories hold this session's skills" and "which
;; instruction files does it read" -- are each answered by a PURE function of
;; (configured value, project directory), because the namespaces answering them
;; must not require this one (see harness.skills, whose roots the fence needs).
;; Somebody has to pair the config with the binding, and that somebody needs to
;; see harness.edn, the binding, and both readers: only this namespace can.
;;
;; It is also the right home by subject matter -- harness.edn is where these keys
;; come from, and this is the namespace that reads it.

(defn skill-roots
  "The skill directories THREAD-ID's session reads, in precedence order. The
  session-facing answer: it does the pairing described above, so a model (or a
  human, in the REPL) asks one question instead of three.

  Read fresh on every call, like harness-config itself: editing harness.edn or
  rebinding the project moves the roots on the next call, with no restart."
  [thread-id]
  (skills/roots (:skills (harness-config thread-id)) (binding-for thread-id)))

(defn preamble-files
  "The instruction files THREAD-ID's session reads, in the order they are
  presented. The same pairing as skill-roots, for the other half."
  [thread-id]
  (preamble/instruction-files (:instructions (harness-config thread-id))
                              (binding-for thread-id)))

(defn out-of-bounds?
  "TRUE when PATH, as THREAD-ID's session resolves it, lands outside every
  directory a bound session may touch. The allowed set:

    - the project directory itself -- UNLESS the project's harness.edn set
      :approval {:strict true}, which tightens the fence until every project
      path needs an approval too;
    - the configuration home (config.edn, providers.edn, .env -- reading
      one's own configuration is the fence's explicit allowance, and strict
      does not tighten it away: the config home is harness's own ground, not
      the project's);
    - :approval {:allow [..]} -- extra paths the project declares free of
      the fence, each resolved for the session like any tool path (relative
      to the project root, absolute passes through);
    - the session's SKILL ROOTS (skill-roots, below), for the same reason the
      configuration home is here and with the same status: they are not project
      files, they are what the host and the human installed for their agents. A
      skill's body routinely says 'read references/x.md', and a path like that
      resolves NEXT TO THE SKILL -- so without this every reference file would
      park a human, which would make loading a skill useless.

      Note what is NOT here and must not be: the CONTENT of an instruction file.
      An AGENTS.md that says 'read ~/notes/x.md' does not make ~/notes/x.md
      allowed. The roots are places the harness was configured to look, not
      capabilities a document can grant itself.

  The config is read fresh per call, so harness.edn edits take effect on the
  next tool call. The fence engages ONLY when a binding exists: an unbound
  session answers false for every path, byte-for-byte the pre-binding
  behavior. This fn is the WHERE question as a boolean -- whether to PARK an
  out-of-bounds call is the tool seam's decision, made through the ordinary
  approval flow."
  [thread-id path]
  (boolean
   (when-let [dir (binding-for thread-id)]
     (let [{:keys [approval]}   (harness-config thread-id)
           {:keys [allow strict]} approval
           resolved (resolve-path thread-id path)
           allowed  (concat (when-not strict [dir])
                            [(home/root)]
                            (skill-roots thread-id)
                            (map #(resolve-path thread-id %) (or allow [])))]
       (not (some #(under? resolved %) allowed))))))
