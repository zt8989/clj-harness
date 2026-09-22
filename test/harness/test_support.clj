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

  AND ONE THING THAT IS NEITHER, though it lives here for the same reason: the
  VENDOR-SHAPED PROVIDER, which refuses a request the way an OpenAI-shaped vendor does
  (see the section at the end of this file). Two namespaces need a test that meets that
  refusal, and one copy of the sentence is what keeps them meeting the same one.

  It lives under test/, beside harness.fake, and is NOT a test namespace --
  harness.test-runner lists the namespaces it runs, and this one has nothing to
  run."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.cap.hooks :as cap-hooks]
            [harness.cap.project :as project]
            [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]
            [harness.cap.system-prompt :as system-prompt]
            [harness.cap.mcp :as cap-mcp]
            [harness.cap.tools :as cap-tools]
            [harness.infra.env :as env]
            [harness.infra.home :as home]
            [harness.infra.shell :as shell]
            [harness.kernel.hooks :as hooks]
            [harness.kernel.llm :as llm]))

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

(defonce ^:private live-temp-dirs
  ;; THE TREES `temp-dir` HAS HANDED OUT AND NOBODY HAS REMOVED YET, as absolute path
  ;; strings. A SET, so a path that is handed back twice (nothing does, but the registry
  ;; is not the place to find out) is still one entry, and an atom because `temp-dir` is
  ;; called from the run's thread and from test threads both.
  (atom #{}))

(defn tracked-temp-dirs
  "The paths `wipe-temp-dirs!` would take right now: the scratch trees this process has
  handed out and not yet cleaned up.

  EXPOSED FOR ONE ASSERTION THAT CANNOT HONESTLY BE MADE ANY OTHER WAY -- that the
  RUN's own root and OS home are NOT among them. The behavioural version of that
  question is 'wipe, then see whether the run still works', and on the day the
  invariant broke it would answer by deleting the run's root out from under the suite
  that is still writing into it: the reader would get hundreds of failures somewhere
  else and no line pointing here. Same shape as
  `harness.infra.db/store-paths-opened` -- a set kept by the thing that acts on it,
  read by the thing that checks it."
  []
  (vec (sort @live-temp-dirs)))

(defn wipe-temp-dirs!
  "Delete scratch trees `temp-dir` has handed out, and answer how many were asked for.

  WHY THE DELETE IS HERE RATHER THAN AT THE CALL SITES. A hundred-odd call sites make
  their own tree and NONE of them says who removes it, and that is what the machine
  looked like on 2026-09-22: 19,512 `clj-harness-*` directories under `java.io.tmpdir`,
  409MB of them, still climbing -- 10,148 of the lot were made the day before. A delete
  written at every call site is a delete the NEXT case forgets, and the case that
  forgets is the ordinary one rather than the careless one. The place that knows a
  tree's name as it is made is the place that hands it out.

  THE BARE CALL IS THE RUN'S, AND ONLY THE RUN'S -- its end, and the exit hook. Every
  namespace in the suite is LOADED BEFORE ANY OF THEM RUNS (`run-suite!` requires the
  whole list first), so a namespace that makes its tree at load time -- `(def ^:private
  root (support/temp-dir \"project\"))`, and a dozen of them do -- already has it in the
  registry while an earlier namespace's case is still running. A bare wipe from inside a
  case takes those loaded-early trees with it, and each of those namespaces then dies
  when it finally runs, with `no such directory: .../clj-harness-project-...` -- one
  message, in namespaces that have nothing to do with the case that fired it. Measured
  2026-09-22: 4 failures and 55 errors, every one of them that message, from a case that
  called this with no arguments.

  A CASE THAT WANTS TO WATCH A WIPE HANDS OVER ITS OWN TREES: `(wipe-temp-dirs! [a b])`
  takes exactly those and leaves the registry's other entries alone.

  THE REGISTRY IS EMPTIED BEFORE ANYTHING IS DELETED, so a tree made while this runs
  -- another thread's case, a namespace still starting -- is not lost by arriving after
  the list was read.

  SILENT, unlike `harness.test-runner/cleanup!`: this is a run's ordinary end and a
  process's exit, and a tree that is already gone (with-temp-env removed its own pair)
  is the normal case, not news. The count comes back so the caller can say it out loud."
  ([]
   (let [[dirs] (swap-vals! live-temp-dirs (constantly #{}))]
     (wipe-temp-dirs! dirs)))
  ([dirs]
   (doseq [dir dirs] (wipe-tree! dir))
   ;; WHAT WAS HANDED OVER IS NO LONGER THIS PROCESS'S TO REMOVE, whether or not the
   ;; platform let it go: a registry that keeps a tree the caller already wiped is a
   ;; registry that lies about what is left.
   (swap! live-temp-dirs #(apply disj % dirs))
   (count dirs)))

(defonce ^:private cleanup-hook-installed
  (atom false))

(defn ensure-cleanup-hook!
  "Make THIS PROCESS take its scratch trees with it when it goes, whatever the reason:
  a Ctrl+C, a SIGTERM, an orderly end.

  THE RUNNER'S `finally` IS NOT ENOUGH BY ITSELF, and that is the whole reason this
  exists. A JVM that receives SIGINT runs its shutdown hooks and then halts; the main
  thread's `finally` -- where `harness.test-runner/cleanup!` and its wipe live -- is NOT
  one of them. An interrupted suite would otherwise leave every tree it had made up to
  that moment, which is the same leak arriving by a different road.

  INSTALLED ONCE, by compare-and-set rather than by reading a flag: `temp-dir` is
  called from other threads too, and 'did I install it' has to have one answer. The
  call goes through harness.infra.shell/install-hook! for the reason that var exists --
  a test can then count installations without exiting a JVM."
  []
  (when (compare-and-set! cleanup-hook-installed false true)
    (shell/install-hook! (Thread. ^Runnable (fn [] (try (wipe-temp-dirs!)
                                                      (catch Throwable _ nil)))
                                   "test-scratch-cleanup")))
  nil)

(defn reset-cleanup-hook!
  "Forget that the hook was installed, so the next `ensure-cleanup-hook!` installs one.
  For tests that drive the installation, exactly as `harness.infra.shell/reset-exit-hook!`
  lets a test drive its own; a running process's hook is installed once and stays."
  []
  (reset! cleanup-hook-installed false))

(defn track-temp-dir!
  "Take DIR into the registry by hand -- for a tree this process makes WITHOUT
  `temp-dir` -- and answer it as a path string.

  THE SHAPE THIS IS FOR: a case that composes a SIBLING of a temp tree, `(str
  (temp-dir \"git\") \"-refuse\")`, and makes that instead. The name has to stay the
  composed one (the case asserts on it, and one of them is asserted on through the log
  directory the server names), so `temp-dir` never hears about it and the sweep never
  takes it. Measured 2026-09-22, with the sweep already in place: a full run left
  exactly 9 trees, every one of them this shape -- 7 through harness.cap.git-test's
  `scratch-repo`, 2 beside http-test's listing and archive fixtures -- where it had left
  265 before.

  CALLED BY THE HELPER THAT MAKES THE DIRECTORY, not at each call site: `scratch-repo`
  is the one door those seven go through, and `wipe-dir!` is where http-test's pair is
  made."
  [dir]
  (ensure-cleanup-hook!)
  (swap! live-temp-dirs conj (str dir))
  (str dir))

(defn temp-dir
  "A fresh, empty directory under the system temp directory, named for LABEL.

  MKDTEMP -- `Files/createTempDirectory` -- AND NOT A NAME COMPOSED HERE. `java.io.tmpdir`
  outlives this JVM, so a path built as `<tmpdir>/<label>` names THE SAME directory on
  every run and in every process at once: the tree a run left behind after a crash is
  one the next run silently inherits (replay-test had to delete a fixed directory by
  hand for exactly this, and http-test's git fixture had to invent a per-JVM name so it
  would stop running `git init` inside the previous run's repository), and two runs side
  by side -- the suite and a `--scripted` walkthrough, say -- write into one directory
  each. A clock in the name narrows the window and closes nothing: two processes read
  the same clock. Asking the OS for a name nothing holds, in the same call that creates
  it, is the one spelling that has neither problem.

  THE DIRECTORY COMES BACK EMPTY, so the delete-then-mkdir a fixed path needed first has
  nothing left to do -- and doing it anyway would take away the directory this just
  handed back.

  AND IT IS TAKEN BACK ON THE WAY OUT. The tree is registered in the same call that
  makes it and removed by `wipe-temp-dirs!` when the run ends, so a case makes its
  scratch tree and never mentions it again: nothing to write in a `finally`, and nothing
  to forget -- which is all the 19,512 trees under one day of runs ever were. (A tree
  made at LOAD time by a `def`, which a dozen namespaces do, is taken by the same sweep
  -- which is why that sweep belongs to the run and not to a case: see
  `wipe-temp-dirs!`.)

  `:track? false` IS FOR THE ONE CALLER THAT REMOVES ITS OWN: harness.test-runner/
  isolate! makes the run's root and OS home, and those two are the RUN's environment
  rather than a case's scratch tree. A wipe that could reach them would take the run out
  from under itself while it was still writing into them."
  ([label] (temp-dir label {}))
  ([label {:keys [track?] :or {track? true}}]
   (let [dir (str (java.nio.file.Files/createTempDirectory
                   (str "clj-harness-" label "-")
                   (make-array java.nio.file.attribute.FileAttribute 0)))]
     (when track?
       (ensure-cleanup-hook!)
       (swap! live-temp-dirs conj dir))
     dir)))

(defn shell-path
  "PATH spelled the way the shell this process spawns reads it: forward slashes.

  A PATH THAT GOES INTO A SHELL COMMAND IS NOT THE STRING `java.io.File` HANDS BACK.
  On Windows the JVM spells one `C:\\Users\\me\\gate.sh` and the shell `harness.infra.shell`
  resolves there is Git Bash, where a backslash is an escape -- so that word arrives as
  `C:Usersmegate.sh` and the command fails with `exit 127: command not found`, which reads
  as 'your hook is missing' rather than 'your path was eaten'. A hook's `:command` is
  SHELL TEXT (see harness.infra.shell/run), and so is anything spliced into a `bash` call,
  so a test that writes one has to write it in the shell's own spelling -- exactly as a
  person configuring a hook on Windows has to. Forward slashes are that spelling:
  Git Bash, cmd and PowerShell all take them, and on POSIX there is nothing to change.

  NOT FOR A PATH HANDED TO A TOOL. `read` / `write` / `glob` take the JVM's spelling
  because the JVM is what resolves them; this is only for the text of a command.
  (harness.cap.glob/tidy has the mirror-image note, and test/harness/kernel/tools_test.clj
  the same comparison made the other way round, for what bash answers with.)"
  [p]
  (str/replace (str p) "\\" "/"))

(defn outside-path
  "An ABSOLUTE path outside the project directory and the configuration home, with
  PARTS appended -- what a case hands a bound session when it wants the fence to
  fire.

  NOT `/etc/hosts`. That is the POSIX idiom for 'somewhere else' and it names
  nothing on Windows: `/etc` there is a ROOT-RELATIVE path (`C:\\etc` at best, and
  not absolute at all to `java.io.File`), so a bound session resolves it through
  the project and the call lands IN bounds -- the case then reports 'the fence did
  not fire' while the fence was never asked a question it could answer. What these
  cases need is a real place, absolute on whatever platform is running, that is
  neither of the two allowed roots; a sibling of this run's own temp trees is one
  (`harness.test-runner/isolate!` puts the root and the OS home side by side under
  the temp directory, and `with-temp-env` does the same, precisely so that neither
  contains the other)."
  [& parts]
  (let [base (io/file (System/getProperty "java.io.tmpdir") "clj-harness-outside")]
    (str (if (seq parts) (apply io/file base parts) base))))

(defn posix-permissions?
  "Does this platform track POSIX file permissions? Windows does not -- the calls
  throw UnsupportedOperationException there, which is why
  `harness.cap.hashline.files/mode-of` answers nil rather than an empty set: 'this
  platform does not track it' and 'the file has no permissions' are two different
  facts and only the second is worth restoring.

  A CASE THAT ASSERTS BITS CAME BACK HAS TO ASK THIS FIRST. Otherwise it asserts a
  POSIX-only capability on whatever machine runs the suite, and on Windows the
  failure arrives as an UnsupportedOperationException thrown from the test's own
  `setPosixFilePermissions` -- a red suite that names nothing about the harness.
  Asked of the platform rather than hardcoded, so the POSIX branch is still the one
  that runs where POSIX permissions exist."
  []
  (try
    (java.nio.file.Files/getPosixFilePermissions
     (.toPath (io/file (System/getProperty "java.io.tmpdir")))
     (make-array java.nio.file.LinkOption 0))
    true
    (catch UnsupportedOperationException _ false)
    (catch Exception _ false)))

(defn graceful-stop?
  "Can a process this harness stops still run a shutdown handler of its OWN before it
  dies? False on Windows, true on POSIX.

  `harness.infra.shell/kill-tree!` asks the process to stop -- `Process.destroy` --
  and what that means is the platform's business: on POSIX it is SIGTERM, which a
  handler may catch and answer with one last line, while on Windows it is
  TerminateProcess, which runs nothing at all. A case that read a 'we were signalled'
  note out of the child's own file was therefore asserting a POSIX-only outcome on
  whatever machine ran the suite: it could only ever be green on one of them, and on
  Windows it went red naming nothing about the harness.

  Asked of the platform rather than hardcoded -- the same rule as
  `posix-permissions?` -- so the branch that runs where handlers DO run is still the
  one that runs there. What such a case must not give up is the claim that matters on
  both: the process is gone (`wait-gone` / `gone-within?` ask the OS, not this)."
  []
  (not (str/includes? (str/lower-case (System/getProperty "os.name" "")) "win")))

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
  process holds a handle to it.

  NIL IS NOT ALIVE, rather than an error: the pid usually comes from `child-pid`,
  which answers nil when the child never booted, and `(is (alive? pid))` is meant to
  report that as a failed assertion -- not to throw a NullPointerException out of a
  case whose whole subject is a process that did not start."
  [pid]
  (boolean
   (when (some? pid)
     (when-let [h (.orElse (java.lang.ProcessHandle/of (long pid)) nil)]
       (.isAlive ^java.lang.ProcessHandle h)))))

(def ^:private child-pid-program
  "The program a command's CHILD runs: print the pid the operating system gave it,
  then stay alive until something kills it.

  NODE, because this repo already needs it (the ui, the fake MCP server) and because
  `process.pid` is the number `ProcessHandle/of` takes, on every platform. `sleep` is
  the obvious choice and the wrong one: what the shell prints for it is not that
  number -- see `child-command`.

  SINGLE QUOTES, NOT DOUBLE. The command line is ONE argv element handed to `bash -lc`,
  and on Windows the JVM builds the actual command line for that spawn -- where an
  embedded double quote is not carried through, so `node -e \"..\"` reaches bash as
  `node -e ..` and the shell then reads the program's own parentheses as syntax: the
  child never starts and the pid file is never written. Single quotes mean the same
  thing to bash on every platform and are not touched on the way in. (Measured on this
  machine: the double-quoted form exits 1 with `syntax error near unexpected token`.)"
  "node -e 'console.log(process.pid); setInterval(function(){}, 1000)'")

(defn child-command
  "A SHELL COMMAND that starts a background child and writes the child's OS PID to
  PID-FILE, then waits -- so the process this command runs under is a shell with a
  live child beneath it, and 'the whole tree' is a claim with more than one member.
  How the tree then dies -- a timeout, a stop, a shutdown -- is the caller's business.

  THE CHILD WRITES ITS OWN PID, and that is the point rather than a detail. Git
  Bash's `$!` is MSYS's OWN number for the process, and `ProcessHandle/of` cannot
  resolve it: `alive?` then answers false about a child that is very much running,
  and a case asserting 'the child is gone' passes without anything having been
  killed. Worse, the sanity check beside it -- 'the job really is running' -- fails,
  which is how this was found. Asking the child to print the number the OS gave it is
  the one spelling that is right on both platforms, and `wait` keeps the shell around
  so the child is not reparented before the claim can be checked."
  [^java.io.File pid-file]
  (str child-pid-program " > " (shell/quote-arg (shell-path (.getAbsolutePath pid-file)))
       " & wait"))

(defn child-pid
  "The pid the child `child-command` started wrote into PID-FILE, once it is there
  -- or nil, having waited MS for it.

  POLLED, NOT READ ONCE, and the difference is a test that fails about the machine
  rather than about the harness. The record names the pid only after the child has
  BOOTED (a shell, then node, then `console.log`), and how long that takes is not
  this test's business: a fixed `(Thread/sleep 1000)` was enough on a quiet machine
  and not enough when three namespaces ran back to back -- the read then got an empty
  file (the shell's `>` created it before anything printed into it) and `parseLong`
  threw NumberFormatException out of a case that meant to be asserting something
  else.

  THE SAME DEADLINE DISCIPLINE AS EVERY OTHER WAIT HERE: bounded, so a child that
  never boots fails the case that was written about it rather than hanging the suite;
  and nil rather than a throw, so the case can say what it saw."
  [^java.io.File pid-file ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (let [pid (try (let [text (str/trim (slurp pid-file :encoding "UTF-8"))]
                       (when (re-matches #"\d+" text) (Long/parseLong text)))
                     (catch Exception _ nil))]
        (cond
          pid pid
          (> (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 25) (recur)))))))

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

;; ------------------------------------------------- interleaving two threads
;;
;; A TEST THAT DOES NOT FORCE THE INTERLEAVING PASSES WHILE THE BUG IS PRESENT. Two
;; threads started one after the other usually run one after the other, and the code
;; under test then behaves exactly as it does with no bug at all: the assertion holds,
;; the test is green, and nothing was checked. Both helpers here WITNESS what they
;; forced, so a case can assert that the two threads really met instead of hoping.
;;
;; EVERY WAIT HAS A DEADLINE and a stuck one FAILS LOUDLY: a race case that hangs
;; takes the whole suite with it, and a suite that never finishes reads like a broken
;; machine rather than a broken test.

(defn holds-within?
  "Does PRED become true within MS? Polled, because what it asks about happens on
  another thread and there is nothing to block on -- and bounded, so a case whose
  premise never comes true fails with the assertion it was written for instead of
  hanging the suite."
  [pred ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 10) (recur))))))

(defn start-gate
  "A gate N threads must all reach before any of them is let through.

  Ask for one, hand `:arrive` to every thread, and call it as the last thing before the
  work being raced. Answers {:arrive .. :witness .. :n ..}:

    :arrive   what a thread calls -- returns once all N are there, and THROWS when
              they never all turn up within MS (naming how many did), so a case that
              failed to start its threads together fails instead of hanging
    :witness  how many threads have reached the gate, for an assertion

  The count is the point: it is what lets a test say 'these two were in flight at the
  same moment' as a fact rather than as an assumption. The two-argument form exists so
  the deadline itself can be tested without waiting half a minute for it."
  ([n] (start-gate n 30000))
  ([n ms]
   (let [ready (java.util.concurrent.CountDownLatch. (int n))
         there (atom 0)]
     {:n n
      :arrive  (fn []
                 (swap! there inc)
                 (.countDown ready)
                 (when-not (.await ready (long ms) java.util.concurrent.TimeUnit/MILLISECONDS)
                   (throw (ex-info (str "the start gate never opened: " @there " of " n
                                        " threads arrived within " ms
                                        "ms; the case is not racing anything")
                                   {:arrived @there :wanted n}))))
      :witness (fn [] @there)})))

(defn window-gate
  "Wrap the var SYM's value so that a call to it, while the gate is armed, BLOCKS
  inside the window -- after it is entered, before it does its work.

  This is how a race in the middle of a function is forced: wrap the var the window
  sits behind (a predicate, a reader, a reconfigure), let one thread reach it and stop
  there, then run the other thread's work and release.

  ALTER-VAR-ROOT, NOT `binding`: the server runs on its own threads, and a thread-local
  binding is silently ignored there -- the same reason `with-temp-env` uses it. Answers
  {:entered .. :release .. :restore ..}:

    :entered  how many calls have reached the window so far (the witness)
    :release  let every waiting call continue
    :restore  put the original value back -- call it in a `finally`

  A caller that forgets :restore leaves the var wrapped for every test after it, so
  this is deliberately a pair of calls in a try/finally rather than a macro with a body:
  the window is usually entered by a thread the test body did not start. The two-argument
  form sets how long a waiting call waits before going on without a release -- so the
  deadline itself is testable without a half-minute wait.

  THE THREE-ARGUMENT FORM HOLDS ONLY THE FIRST `hold` CALLS AND LETS THE REST THROUGH,
  which is what a window in a FUNCTION BOTH THREADS RUN needs: holding the first caller
  there while the second one runs to completion is the whole experiment, and holding the
  second one too would make the test wait for a release that only the test can give.
  `hold` 0 -- the default -- holds every call."
  ([sym] (window-gate sym 30000 0))
  ([sym ms] (window-gate sym ms 0))
  ([sym ms hold]
   (let [original @sym
         entered  (atom 0)
         go       (promise)]
     (alter-var-root sym (fn [_] (fn [& args]
                                   (let [n (swap! entered inc)]
                                     (when (or (zero? hold) (<= n hold))
                                       (deref go (long ms) false)))
                                   (apply original args))))
     {:entered (fn [] @entered)
      :release (fn [] (deliver go true))
      :restore (fn [] (alter-var-root sym (fn [_] original)))})))


;; ---------------------------------------------------------- the vendor's refusal
;;
;; A PROVIDER THAT IS THE VENDOR'S VALIDATOR, then the scripted fake underneath it. A
;; spy could only report the messages it was handed; this one REFUSES them the way an
;; OpenAI-shaped vendor does, so a run under test dies exactly where production died.
;;
;; THE RULE IS harness.kernel.llm/unanswered-tool-calls' -- the same function the run's
;; own guard reads -- so a test's vendor and production cannot disagree about which
;; histories are refused. Only the SENTENCE is copied, byte for byte, from a real
;; gateway (thread d841d970, 2026-09-18): a test must meet what production meets, and
;; the wording is the evidence. What production must no longer do is relay it.

(def unanswered-call-refusal
  "The vendor's own 400 body, verbatim: an assistant message whose tool_calls are not
  answered ADJACENTLY is refused before the model runs at all."
  (json/write-str {:error {:message (str "An assistant message with 'tool_calls' must be followed"
                                         " by tool messages responding to each 'tool_call_id'."
                                         " (insufficient tool messages following tool_calls message)")
                           :type "invalid_request_error"
                           :param ""
                           :code "invalid_request_error"}}))

(defn refuse-unanswered-calls!
  "Throw the 400 a vendor answers when an assistant message's tool_calls are not
  followed, immediately, by a tool message for every one of them. Nil -- quiet -- when
  the request is one the vendor would accept."
  [messages]
  (when-let [missing (seq (llm/unanswered-tool-calls messages))]
    (throw (ex-info (str "HTTP 400: " unanswered-call-refusal)
                    {:status 400 :unanswered (vec missing)}))))

(defmethod llm/stream! :vendor-shaped
  [provider messages on-event thread-id]
  (refuse-unanswered-calls! messages)
  (llm/stream! (assoc provider :protocol :fake) messages on-event thread-id))


;; ------------------------------------------------------- a session that exists

(defn start-session!
  "Begin THREAD-ID as a session of this home with NOTHING in it: the row a run needs,
  an empty conversation, and no record.

  A RUN OF AN ID THE STORE HAS NEVER HEARD OF IS REFUSED, and what that means in a test
  is that every case has to say 'this session exists' before it sends anything -- in the
  page, the new-task action is what says it (see `.scratch/sessions-live-on-the-server`,
  ticket 03). A fixture that names the threads it serves says it here, once, instead of
  at every call site.

  ALL THREE PARTS ARE NEEDED, and the reason is the same for each: the row, because the
  run edge insists on it; the drop, because the session TABLE is process-wide while the
  fixture is not, so an earlier case's conversation would otherwise be this one's history
  under the same id; and THE RECORD, because a conversation is BORN FROM ITS LOG when the
  table has none (`harness.edge.sessions/build`) -- deleting the entry alone would just
  make the next run read the previous case's bytes off disk and continue from those.

  `replay/find-log` is what finds the record, so a thread bound to a project is emptied
  where its log actually is. A case that runs the same session twice keeps all three by
  not calling this again."
  [thread-id]
  (let [id (str thread-id)]
    (sessions/drop! id)
    (when-some [f (replay/find-log (home/projects-dir) id)]
      (io/delete-file f true))
    (project/register-session! id)))
