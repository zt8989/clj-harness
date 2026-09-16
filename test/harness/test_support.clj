(ns harness.test-support
  "The two pieces of cross-test state several namespaces have to arrange, and that
  are therefore here rather than written once in each of them.

  A HOOKS.EDN DECLARES FOR EVERY THREAD IN THIS PROCESS -- harness.hooks/config
  reads the file, not a thread -- so a test that writes one has to take it away
  again or the next test in the suite fires a hook it never declared. And the
  kernel's own rows are registered PROCESS-WIDE the moment harness.system-prompt
  loads, so in a full suite they are in every thread's table before any test runs.

  Nothing here holds state of its own, and none of it is a test-only door:
  switching the built-in rows off is the ordinary per-thread switch, and the whole
  point of those rows is that a session can switch one off.

  It lives under test/, beside harness.fake, and is NOT a test namespace --
  harness.test-runner lists the namespaces it runs, and this one has nothing to
  run."
  (:require [clojure.java.io :as io]
            [harness.home :as home]
            [harness.hooks :as hooks]))

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
