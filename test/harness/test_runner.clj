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
            [harness.home :as home]))

(def test-namespaces
  '[harness.event-test
    harness.db-test
    harness.llm-test
    harness.skills-test
    harness.preamble-test
    harness.tools-test
    harness.session-tools-test
    harness.approval-test
    harness.loop-test
    harness.ag-ui-test
    harness.replay-test
    harness.evals-test
    harness.providers-test
    harness.project-test
    harness.log-test
    harness.git-test
    harness.editing-test
    harness.editing-mode-tools-test
    harness.hashline-anchors-test
    harness.hashline-store-test
    harness.hashline-read-test
    harness.hashline-replace-test
    harness.hashline-refusals-test
    harness.hashline-write-test
    harness.hashline-undo-test
    harness.hashline-batch-test
    harness.hashline-insert-test
    harness.hashline-grep-test
    harness.glob-test
    harness.todos-test
    harness.web-test
    harness.web-search-test
    harness.hooks-test
    harness.hooks-dispatch-test
    harness.hooks-wired-test
    harness.system-prompt-test
    harness.http-test])

(def ^:private tmp-home
  (atom nil))

(def ^:private tmp-user-home
  (atom nil))

(def ^:private seed-config
  "A minimal config.edn, so a run that resolves a provider from config -- rather
  than from the scripted override -- has something to resolve. The inline form,
  so it needs no providers.edn: :protocol :fake is the offline provider, and the
  endpoint is a URL that is never contacted.

  It declares NO modalities, which is deliberate -- an inline provider that says
  nothing about what it accepts is not guarded (see harness.ag-ui/undeclared-
  input?), and a seeded config must not make every text-only integration test
  fail for a reason the test never stated."
  "{:protocol :fake :base-url \"http://offline.invalid/v1\" :model \"seeded\"}\n")

(defn- seed!
  [dir]
  (spit (io/file dir "config.edn") seed-config :encoding "UTF-8"))

(defn isolate!
  "Point the config root AND the OS home at fresh temp directories for this
  process. Returns the root directory. Idempotent: a second call reuses the
  first pair.

  The two are siblings, never nested -- see the docstring above for why nesting
  would tamper with what the fence tests are asking."
  []
  (or @tmp-home
      (let [stamp (System/currentTimeMillis)
            dir   (io/file (System/getProperty "java.io.tmpdir")
                           (str "clj-harness-test-" stamp))
            home' (io/file (System/getProperty "java.io.tmpdir")
                           (str "clj-harness-test-home-" stamp))]
        (.mkdirs dir)
        (.mkdirs home')
        (seed! dir)
        (alter-var-root #'home/*root-override* (constantly (str dir)))
        (alter-var-root #'home/*user-home-override* (constantly (str home')))
        (reset! tmp-home (str dir))
        (reset! tmp-user-home (str home'))
        (str dir))))

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
  harness.home here -- by now the root points at the temp home, so asking again
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
                          " harness.home.")))
          false))))

(defn -main [& _]
  ;; The store's path is resolved through harness.home BEFORE the root moves, so
  ;; it comes from the one place that decides paths rather than a second copy of
  ;; the precedence rule -- and so the File in hand still names the developer's
  ;; real home for the rest of this run.
  (let [store  (home/db-file)
        before (store-state store)
        dir    (isolate!)]
    (println "test config root:" dir)
    (println "test OS home:" @tmp-user-home)
    (println "developer home store before this run:"
             (if before (str "present (" (first before) " bytes, left alone)") "absent"))
    (apply require test-namespaces)
    (let [{:keys [fail error]} (apply t/run-tests test-namespaces)
          isolated?            (isolation-verdict store before)
          broken               (+ (or fail 0) (or error 0) (if isolated? 0 1))]
      (cleanup!)
      (System/exit (if (zero? broken) 0 1)))))
