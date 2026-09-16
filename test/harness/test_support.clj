(ns harness.test-support
  "The two pieces of cross-test state several namespaces have to arrange, and that
  are therefore here rather than written once in each of them.

  A HOOKS.EDN DECLARES FOR EVERY THREAD IN THIS PROCESS -- harness.kernel.hooks/config
  reads the file, not a thread -- so a test that writes one has to take it away
  again or the next test in the suite fires a hook it never declared. And the
  kernel's own rows are registered PROCESS-WIDE the moment harness.cap.system-prompt
  loads, so in a full suite they are in every thread's table before any test runs.

  Nothing here holds state of its own, and none of it is a test-only door:
  switching the built-in rows off is the ordinary per-thread switch, and the whole
  point of those rows is that a session can switch one off.

  It lives under test/, beside harness.fake, and is NOT a test namespace --
  harness.test-runner lists the namespaces it runs, and this one has nothing to
  run."
  (:require [clojure.java.io :as io]
            [harness.cap.hooks :as cap-hooks]
            [harness.cap.system-prompt :as system-prompt]
            [harness.cap.tools :as cap-tools]
            [harness.infra.home :as home]
            [harness.kernel.hooks :as hooks]))

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
  withdraw them afterwards: harness.cap.tools' eleven tools, the hook-file reader
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
