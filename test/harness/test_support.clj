(ns harness.test-support
  "The pieces of cross-test state several namespaces have to arrange, and that are
  therefore here rather than written once in each of them.

  A HOOKS.EDN DECLARES FOR EVERY THREAD IN THIS PROCESS -- harness.kernel.hooks/config
  reads the file, not a thread -- so a test that writes one has to take it away
  again or the next test in the suite fires a hook it never declared. And the
  kernel's own rows are registered PROCESS-WIDE the moment harness.cap.system-prompt
  loads, so in a full suite they are in every thread's table before any test runs.

  And CONFIG.EDN, which is ONE FILE WITH TWO SECTIONS now: two namespaces write it,
  and the sections are positional, so getting the pair the wrong way round would
  still parse -- see `config-file!`.

  Nothing here holds state of its own, and none of it is a test-only door:
  switching the built-in rows off is the ordinary per-thread switch, and the whole
  point of those rows is that a session can switch one off.

  It lives under test/, beside harness.fake, and is NOT a test namespace --
  harness.test-runner lists the namespaces it runs, and this one has nothing to
  run."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.cap.hooks :as cap-hooks]
            [harness.cap.system-prompt :as system-prompt]
            [harness.cap.mcp :as cap-mcp]
            [harness.cap.tools :as cap-tools]
            [harness.infra.env :as env]
            [harness.infra.home :as home]
            [harness.infra.shell :as shell]
            [harness.kernel.hooks :as hooks]))

(def seed-config
  "A minimal config.edn, so a run that resolves a provider from config -- rather
  than from a scripted override -- has something to resolve. The INLINE form in
  the :default section, so it needs no :providers entry: :protocol :fake is the
  offline provider, and the endpoint is a URL that is never contacted.

  It declares NO modalities, which is deliberate -- an inline provider that says
  nothing about what it accepts is not guarded (see harness.edge.ag-ui/undeclared-
  input?), and a seeded config must not make every text-only integration test
  fail for a reason the test never stated.

  HERE RATHER THAN IN THE RUNNER because a test that makes its OWN root needs the
  same file: harness.test-runner seeds the run-wide one, `with-temp-env` seeds the
  per-test one, and a run without it is refused with 'no provider: name one in
  <root>/config.edn's :default' -- which is a confusing way to learn that a temp
  root was left bare."
  "{:default {:protocol :fake :base-url \"http://offline.invalid/v1\" :model \"seeded\"}}\n")

(defn seed-config!
  "Write the seed config into DIR, making it a root a run can work in."
  [dir]
  (spit (io/file dir "config.edn") seed-config :encoding "UTF-8"))

(defn config-text
  "DEFAULT-EDN and PROVIDERS-EDN as the text of the one config.edn a home is
  configured by: DEFAULT-EDN is its :default section, PROVIDERS-EDN its :providers
  section. Either may be nil, and is then written as {} -- an empty section, which
  the readers treat as 'this file says nothing about that'.

  HERE RATHER THAN IN ONE NAMESPACE because two of them write this file
  (providers-test and http-test) and the two sections are positional: a test that
  passed them the wrong way round would still parse, and would then be asking
  about a different file than it meant to.

  TEXT RATHER THAN DATA, and a string rather than a writer, for two reasons: a test
  can hand over exactly the bytes it is testing, including bytes that are SUPPOSED
  to fail; and half the callers write into a directory of their own (a fresh home,
  a child JVM) rather than the one harness.infra.home currently points at."
  ([default-edn] (config-text default-edn nil))
  ([default-edn providers-edn]
   (str "{:default "   (or default-edn "{}")
        "\n :providers " (or providers-edn "{}") "}\n")))

(defn hooks-file
  "The user-level hooks.edn: the file a declaration is written into."
  []
  (io/file (home/root) "hooks.edn"))

(defn write-hooks!
  "Write DECLS -- a map of point to [declaration ..] -- as the user's hooks.edn."
  [decls]
  (.mkdirs (.getParentFile (hooks-file)))
  (spit (hooks-file) (pr-str decls) :encoding "UTF-8"))

(defn wipe-hooks!
  "Take the user's hooks.edn away again, whether or not it is there."
  []
  (io/delete-file (hooks-file) true))

(defn wipe-tree!
  "Delete DIR and everything under it, bottom-up.

  BOTTOM-UP BECAUSE `io/delete-file` DOES NOT RECURSE: on a non-empty directory its
  `silently` flag turns the failure into a scheduled deleteOnExit, so the obvious
  one-line version of this leaves the tree exactly where it was -- a skill planted by
  one test was then read by every test after it."
  [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(defn temp-dir
  "A fresh, empty directory under the system temp directory, named for LABEL."
  [label]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "clj-harness-" label "-" (System/nanoTime)))]
    (.mkdirs d)
    (str d)))

(defmacro with-temp-env
  "Run BODY with the configuration ROOT and the OS HOME pointed at a fresh pair of
  temp directories of this test's own, deleted on the way out. ROOT and HOME are
  bound to their path strings.

  A TEST THAT NEEDS A HOME MAKES ONE. `harness.test-runner/isolate!` makes ONE pair
  for the whole run, and every test in the JVM shares it -- so a file planted there
  is a file the next test reads (an <instructions> block nobody declared, a skill
  called `alpha` in a catalog), and the cleanup written to undo it is a delete
  against the OS home, which is the developer's REAL home the moment the override is
  not in force. Neither problem exists if the directory belongs to the test.

  THE PROJECT DIRECTORY AND ANY CONFIG FILE ARE THE BODY'S TO MAKE, under ROOT or
  HOME: this only decides what `harness.infra.home/root` and `user-home` answer.

  ALTER-VAR-ROOT, NOT BINDING: the kernel runs its loop and its tools on their own
  threads, and a thread-local binding does not reach them -- a bound home would be
  silently ignored by everything below the test body. Restored in a `finally`, so
  the shared pair is back even when the body throws."
  [[root home] & body]
  `(let [root#     (temp-dir "root")
         home#     (temp-dir "home")
         prev-root# @#'home/*root-override*
         prev-user# @#'home/*user-home-override*]
     ;; A BARE ROOT REFUSES EVERY RUN: with no config.edn there is no :default
     ;; provider, and the run's first hook fails with 'no provider: name one in
     ;; <root>/config.edn's :default'. The seed is the smallest file that answers it.
     (seed-config! root#)
     (alter-var-root #'home/*root-override*      (constantly root#))
     (alter-var-root #'home/*user-home-override* (constantly home#))
     (try (let [~root root#
                ~home home#]
            ~@body)
          (finally
            (alter-var-root #'home/*root-override*      (constantly prev-root#))
            (alter-var-root #'home/*user-home-override* (constantly prev-user#))
            (wipe-tree! root#)
            (wipe-tree! home#)))))

(defn without-builtins!
  "Switch the kernel's own rows off for THREAD-ID, so a test asking about the
  DECLARED rows sees only those. Per-thread, like every other switch, so it cannot
  leak into another test's thread.

  The rows are found by SOURCE rather than by a list of their names: a fourth
  built-in row would otherwise have to be remembered here, and a helper that
  quietly missed one would make a 'nothing declared, nothing fired' assertion pass
  for the wrong reason."
  [thread-id]
  (doseq [row (vals (hooks/effective-hooks thread-id))
          :when (= :built-in (:source row))]
    (hooks/session-disable! thread-id (:id row))))

(defn with-builtins
  "Install THE APP'S CAPABILITIES for the duration of ONE test namespace, and
  withdraw them afterwards: harness.cap.tools' built-in tools, the hook-file reader
  (harness.cap.hooks) and the kernel's own three SystemPrompt rows
  (harness.cap.system-prompt) -- the same three the composition root installs.

  A FIXTURE RATHER THAN SOMETHING LOADED FOR YOU, and the change is the point of
  the install door: before it, requiring harness.kernel.tools put the built-ins in
  every process that loaded the seam, so 'why does this test have a `read` tool'
  had no answer inside the test. Now a namespace that drives tools says so in one
  line, and harness.kernel.install-test -- which asserts what an EMPTY table does
  -- simply does not use it.

  :once, not :each: the layer is what the namespace needs, not what each test
  needs, and re-installing per test would churn the registry 600 times for
  nothing. The teardown runs even when a test throws, so a failing namespace
  cannot leak a tool table into the next one."
  [f]
  (let [teardowns [(cap-tools/install!) (cap-hooks/install!) (system-prompt/install!)]]
    (try (f) (finally (doseq [td teardowns] (td))))))

(defn with-machine
  "Run F with the MACHINE STUBBED: the shell harness.infra.shell resolves to, and
  the enhancer set harness.infra.env's probe answers with.

  BOTH HALVES ARE INJECTABLE ON PURPOSE. Everything the <env> block states is a fact
  about the machine this suite happens to run on, so a test that asserted them against
  that machine could only ever assert the one shape it has -- and the shapes that
  matter most (a Windows shell, a name that is missing) are exactly the ones it does
  not have. Both answers are process-wide caches, so they are reset around the body:
  a stub must not become the answer the next test reads."
  [resolution probe f]
  (try
    (with-redefs [shell/resolve* (constantly resolution)
                  env/probe*    (constantly probe)]
      (shell/reset-resolution!)
      (env/reset-probe!)
      (f))
    (finally
      (env/reset-probe!)
      (shell/reset-resolution!))))


(defn with-mcp
  "The same capabilities, PLUS harness.cap.mcp -- the external servers a home
  declares, whose tools arrive through the seam's `:tools-for` rather than as a
  map written down at setup.

  A SEPARATE FIXTURE rather than part of the one above, because that one is what
  every tool-driving namespace in this suite uses and MCP is exactly the
  capability most of them do not have: a table with an external server in it is a
  different table, and the tests that are about the SEAM should keep asserting
  against the one they were written for."
  [f]
  (let [teardowns [(cap-tools/install!) (cap-hooks/install!)
                   (system-prompt/install!) (cap-mcp/install!)]]
    (try (f) (finally (doseq [td teardowns] (td))))))

;; ------------------------------------------------- processes a test started
;;
;; The three questions a test about SPAWNING asks, here rather than written once per
;; namespace: the feature that added background jobs put the same two helpers in
;; three files, and a helper that drifts in one of them is a test that stops saying
;; what it meant.

(defn alive?
  "Is PID a live process? Asked of the OS rather than of a JVM object, because the pid
  in hand is usually a GRANDCHILD -- the one a command started -- and nothing in this
  process holds a handle to it."
  [pid]
  (boolean (when-let [h (.orElse (java.lang.ProcessHandle/of (long pid)) nil)]
             (.isAlive ^java.lang.ProcessHandle h))))

(defn gone-within?
  "Did PID disappear within MS? Polled rather than asked once: a process that was just
  killed can still be seen for a moment -- it is a zombie until its own parent reaps
  it, and that parent is usually not this process -- so a single read is a coin toss."
  [pid ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (cond
        (not (alive? pid)) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 50) (recur))))))

(defn read-until
  "Call READ -- a fn of no arguments answering a string -- until PRED is true of
  `{:answer <the last answer> :lines <every line seen so far, minus the status and
  placeholder lines>}`, or MS runs out. Answers that state.

  READS ARE THE ONLY WAY TO SEE A JOB, so 'wait for the job' is 'keep reading it':
  the loop is what the model itself has to do, and it is the same loop whether the
  caller drives harness.cap.jobs directly or goes through the tool seam."
  [read pred ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop [seen []]
      (let [answer (read)
            seen   (into seen (remove #(or (re-find #"^\[" %) (= "(no new output)" %))
                                      (str/split-lines answer)))
            state  {:answer answer :lines seen}]
        (cond
          (pred state) state
          (> (System/currentTimeMillis) deadline) state
          :else (do (Thread/sleep 50) (recur seen)))))))
