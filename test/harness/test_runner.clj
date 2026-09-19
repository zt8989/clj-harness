(ns harness.test-runner
  "Built-in clojure.test runner. No test-runner dependency: the cognitect one is a
  git coordinate, and git hosting is unreachable on this machine.

  It also isolates the filesystem: before anything else loads, the config root is
  pointed at a fresh temp directory. Without this, http_test writes its jsonl into
  the developer's REAL ~/.clj-harness -- which is how the \"tests pass but no log
  is on disk\" confusion started, since a sandboxed run gets that write redirected.

  alter-var-root, not binding: a test run starts a server whose logging happens on
  other threads, and a dynamic binding would not reach them. This process is a
  throwaway, so making the override global here is exactly the intent -- production
  code never touches it.

  The claim is checked at the END rather than assumed: the developer's real store
  is fingerprinted before the root moves and again after the suite runs, and any
  appearance or change fails the run. A file that simply sits there untouched --
  which is the normal state of a working install, and the case a bare exists?
  check would stop noticing -- passes, because an untouched file is exactly what
  the assertion is about.

  THE OS HOME IS PINNED TOO, AND TO A SIBLING OF THE ROOT RATHER THAN TO THE ROOT
  ITSELF. The host's convention files live there -- ~/AGENTS.md and the skills in
  ~/.agents/skills -- so a suite that read the developer's real home would depend
  on one person's dotfiles, and every existing assertion would gain messages it
  never asked for. The two temp directories are siblings on purpose: making the
  user home a subdirectory of the root would put it inside the fence's allowed
  set (the configuration home), which is exactly the question the fence tests
  ask, so the arrangement would quietly answer one of them for itself."
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [harness.infra.home :as home]
            [harness.test-support :as support]))

(def test-namespaces
  '[harness.kernel.install-test
    harness.kernel.event-test
    harness.test-support-test
    harness.infra.db-test
    harness.infra.shell-test
    harness.infra.env-test
    harness.kernel.llm-test
    harness.cap.skills-test
    harness.cap.preamble-test
    harness.kernel.tools-test
    harness.cap.mcp-test
    harness.cap.jobs-test
    harness.cap.mcp-wired-test
    harness.session-tools-test
    harness.approval-test
    harness.kernel.loop-test
    harness.edge.ag-ui-test
    harness.edge.replay-test
    harness.evals-test
    harness.cap.providers-test
    harness.cap.project-test
    harness.infra.log-test
    harness.cap.git-test
    harness.cap.editing-test
    harness.cap.subagents-test
    harness.cap.frame-bus-test
    harness.cap.editing-mode-tools-test
    harness.cap.hashline.anchors-test
    harness.cap.hashline.store-test
    harness.cap.hashline.read-test
    harness.cap.hashline.replace-test
    harness.cap.hashline.refusals-test
    harness.cap.hashline.write-test
    harness.cap.hashline.undo-test
    harness.cap.hashline.batch-test
    harness.cap.hashline.insert-test
    harness.cap.hashline.grep-test
    harness.cap.glob-test
    harness.cap.todos-test
    harness.cap.web-test
    harness.cap.web-search-test
    harness.kernel.hooks.install-test
    harness.kernel.hooks-test
    harness.kernel.hooks.dispatch-test
    harness.kernel.hooks-wired-test
    harness.cap.system-prompt-test
    harness.edge.stats-test
    harness.edge.trajectory-test
    harness.edge.context-test
    harness.edge.http-test
    harness.edge.delegation-test
    harness.edge.delegation-line-test
    harness.layers-test])

(def ^:private tmp-home
  (atom nil))

(def ^:private tmp-user-home
  (atom nil))

;; The seed config lives in harness.test-support, with the reason it exists: a test
;; that makes its OWN root (with-temp-env) needs the same file, and two copies of a
;; text two things must agree on is one copy too many.

(defn isolate!
  "Point the config root AND the OS home at fresh temp directories for this
  process. Returns the root directory. Idempotent: a second call reuses the
  first pair.

  The two are siblings, never nested -- see the docstring above for why nesting
  would tamper with what the fence tests are asking."
  []
  (or @tmp-home
      ;; MKDIR-TEMP, NOT `tmpdir + name`: the pair belongs to THIS process, and a
      ;; composed name would be the same path for the run happening beside it -- see
      ;; harness.test-support/temp-dir, which is where the how and the why live.
      (let [dir   (support/temp-dir "test")
            home' (support/temp-dir "test-home")]
        (support/seed-config! dir)
        (alter-var-root #'home/*root-override* (constantly dir))
        (alter-var-root #'home/*user-home-override* (constantly home'))
        (reset! tmp-home dir)
        (reset! tmp-user-home home')
        dir)))

(defn- cleanup! []
  (doseq [dir (remove nil? [@tmp-home @tmp-user-home])]
    (try
      (doseq [f (reverse (file-seq (io/file dir)))]
        (io/delete-file f true))
      (catch Exception e
        ;; Losing a temp dir is not worth failing a green suite over, but say so.
        (binding [*out* *err*]
          (println "warning: could not remove test dir" dir ":" (ex-message e)))))))

(defn- store-state
  "F as [bytes mtime], or nil when it is not there. Two numbers rather than a bare
  exists?: once the developer HAS a store -- the normal state of a working install
  -- `exists?` is true before and after and would notice nothing, while a byte
  count and an mtime still move if anything wrote to it. The store is rewritten in
  place and grown by appends, so any write moves at least one of the two."
  [^java.io.File f]
  (when (.exists f)
    [(.length f) (.lastModified f)]))

(defn- isolation-verdict
  "The post-run half of the isolation claim. A store in the developer's real home
  that APPEARED during this run, or changed while it ran, means some path resolved
  there -- and the run must not report green, because a suite that quietly writes
  to the real home is the exact failure the whole fixture exists to prevent (see
  this namespace's docstring for how that was discovered). The file is left where
  it is: tidying away evidence of the bug would be the second mistake.

  It answers TRUE OR FALSE, not 'nil-or-false': -main folds this into the exit
  code with `(if isolated? 0 1)`, and a `when-not` that let the good path fall off
  the end would answer NIL -- which that test reads as failure. The suite then
  exits 1 on a run with no failures at all, and an exit code that is 1 either way
  says nothing; a signal that cannot distinguish green from red is worse than no
  signal, because it is read as one.

  F is the File captured before `isolate!` ran, never one recomputed from
  harness.infra.home here -- by now the root points at the temp home, so asking again
  would compare the temp store against itself and pass while the real home was
  being written."
  [^java.io.File f before]
  (let [after (store-state f)]
    (if (= before after)
      true
      (do (binding [*out* *err*]
            (println (str "ISOLATION FAILURE: " (.getAbsolutePath f)
                          " changed during this run: " (pr-str before) " -> " (pr-str after)
                          " ([bytes mtime], nil meaning absent) -- some code path resolved"
                          " the store against the developer's real home instead of through"
                          " harness.infra.home.")))
          false))))

(defn run-suite!
  "Run NAMESPACES under the isolation protocol, and answer the process's exit code.

  PUBLIC FOR THE SAME REASON `isolate!` IS: a subset of the suite has to go through
  the SAME protocol rather than the convenient part of it. A targeted run that
  isolate!'d but never checked the verdict would report green on exactly the
  failure the verdict exists for, and one that never cleaned up would leave a temp
  root behind for every invocation -- and both halves are easy to forget when the
  caller assembles the run by hand. `-main` below is this function plus an exit,
  and `scripts/test.mjs` is the caller that made the door necessary.

  NOT `run!`: that name is `clojure.core`'s since 1.12, and shadowing it here would
  be a warning at load and a trap for whoever next requires this namespace."
  [namespaces]
  ;; The store's path is resolved through harness.infra.home BEFORE the root moves, so
  ;; it comes from the one place that decides paths rather than a second copy of
  ;; the precedence rule -- and so the File in hand still names the developer's
  ;; real home for the rest of this run.
  (let [store  (home/db-file)
        before (store-state store)
        dir    (isolate!)]
    (try
      (println "test config root:" dir)
      (println "test OS home:" @tmp-user-home)
      (println "developer home store before this run:"
               (if before (str "present (" (first before) " bytes, left alone)") "absent"))
      (apply require namespaces)
      (let [{:keys [fail error]} (apply t/run-tests namespaces)
            isolated?            (isolation-verdict store before)
            broken               (+ (or fail 0) (or error 0) (if isolated? 0 1))]
        (if (zero? broken) 0 1))
      ;; A NAMESPACE THAT WILL NOT LOAD THROWS OUT OF `require`, and the temp pair
      ;; must not survive that: the point of the arrangement is that a run leaves the
      ;; machine as it found it, green or red. Same for anything else that throws
      ;; between `isolate!` and the end -- the `finally` is what makes the cleanup a
      ;; property of the protocol rather than of the happy path.
      (finally (cleanup!)))))

(defn -main [& _]
  (System/exit (run-suite! test-namespaces)))
