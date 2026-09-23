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

  The claim is checked at the END rather than assumed, and it is checked TWO WAYS
  because one of them cannot tell two very different things apart:

    * WHAT THIS PROCESS DID -- `harness.infra.db/store-paths-opened`, emptied the
      moment the root moves: a store the suite opened in the developer's real home
      is the violation, and it is reported BY NAME.
    * WHAT THE FILE LOOKS LIKE -- its [bytes mtime] before the root moved and again
      after the suite ran. A change with no opening behind it is somebody ELSE's
      write: a live harness session keeps anchors, todo lists and session rows in
      that store while a person works, and blaming the suite for it is how a green
      run gets reported as red (measured 2026-09-20: one anchored file read by the
      live session moved the mtime, while a full suite run left the file
      byte-and-mtime identical). That is REPORTED, and it is not a failure.

  An untouched file -- the normal state of a working install, and the case a bare
  exists? check would stop noticing -- passes, because an untouched file is exactly
  what the assertion is about.

  LEAVING THE MACHINE AS IT FOUND IT IS THE SAME CLAIM, and it covers the scratch trees
  the cases make: `cleanup!` removes the run's own pair and
  `harness.test-support/wipe-temp-dirs!` removes what the cases left behind -- 19,512
  `clj-harness-*` directories had accumulated under one day's worth of runs (measured
  2026-09-22, 409MB, still climbing) when only the first half of this existed.

  THE OS HOME IS PINNED TOO, AND TO A SIBLING OF THE ROOT RATHER THAN TO THE ROOT
  ITSELF. The host's convention files live there -- ~/AGENTS.md and the skills in
  ~/.agents/skills -- so a suite that read the developer's real home would depend
  on one person's dotfiles, and every existing assertion would gain messages it
  never asked for. The temp directories are siblings on purpose: making the user home
  a subdirectory of the root would put it inside the fence's allowed set (the
  configuration home), which is exactly the question the fence tests ask, so the
  arrangement would quietly answer one of them for itself. THE THIRD SIBLING is the
  fence's temp answer (harness.infra.env/*temp-dir-override*), pinned away from the
  fixtures for the same reason: java.io.tmpdir is where every one of them lives, so the
  run answers 'this machine's temp directory' with a directory none of them is under --
  and the fence tests can still hand the gate a path it must call out of bounds.

  A RUN ALSO HAS A TIME LIMIT, and it is a hard one -- see `run-with-deadline` and
  `limit-report` below. A suite that stops returning used to be invisible: on
  2026-09-20 a reflective call in a hot loop held `harness.kernel.tools-test` for
  tens of minutes with no output and no verdict, three `jstack`s were needed to
  find it, and one run nobody was watching stayed alive for four days. Now the
  namespace is named, its stack is printed, and the process exits 2."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [harness.infra.db :as db]
            [harness.infra.env :as env]
            [harness.infra.home :as home]
            [harness.test-support :as support]))

(def test-namespaces
  ;; FIRST, because it is about the fixture every other namespace here runs under:
  ;; the verdict that says this run never went to the developer's home.
  '[harness.test-runner-test
    harness.kernel.install-test
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
    harness.cap.ask-test
    harness.session-tools-test
    harness.approval-test
    harness.kernel.loop-test
    harness.edge.ag-ui-test
    harness.edge.replay-test
    harness.evals-test
    harness.cap.providers-test
    harness.cap.project-test
    harness.cap.claims-test
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
    harness.edge.pressure-test
    harness.edge.compaction-test
    harness.edge.ui-test
    harness.edge.http-test
    ;; THE RECORD WRITER AND THE SESSION TABLE ARE SEPARATE NAMESPACES ON PURPOSE (ticket
    ;; 02), and BOTH HAD TO BE ADDED HERE BY HAND: this list is a literal, so a namespace
    ;; that is not in it does not run in a full suite, and a green run says nothing about
    ;; it. That happened twice in this feature -- sessions-test (ticket 01) and
    ;; record-test (ticket 02) each ran only when named on the command line, and the
    ;; "all green" line above them counted neither. A new test namespace belongs in this
    ;; list in the same commit as the file.
    harness.edge.sessions-test
    harness.edge.record-test
    harness.edge.delegation-test
    harness.edge.delegation-line-test
    harness.edge.follow-route-test
    harness.layers-test])

(def ^:private tmp-home
  (atom nil))

(def ^:private tmp-user-home
  (atom nil))

(def ^:private tmp-temp-dir
  (atom nil))

;; The seed config lives in harness.test-support, with the reason it exists: a test
;; that makes its OWN root (with-temp-env) needs the same file, and two copies of a
;; text two things must agree on is one copy too many.

(defn- cleanup! []
  (doseq [dir (remove nil? [@tmp-home @tmp-user-home @tmp-temp-dir])]
    (try
      (doseq [f (reverse (file-seq (io/file dir)))]
        (io/delete-file f true))
      (catch Exception e
        ;; Losing a temp dir is not worth failing a green suite over, but say so.
        (binding [*out* *err*]
          (println "warning: could not remove test dir" dir ":" (ex-message e)))))))

(defn- ensure-cleanup-hook!
  "Make THIS PROCESS take the run's OWN pair with it when it is stopped from outside --
  SIGTERM, Ctrl+C -- and not only when the run ends by itself.

  THE `finally` IN `run-suite!` IS NOT ENOUGH, and that is measured rather than assumed:
  a JVM that is signalled runs its shutdown hooks and halts, and the main thread's
  `finally` is not one of them (2026-09-22: a two-line program sleeping inside a `try`
  printed nothing from its `finally` after a SIGTERM). A suite stopped at 14s kept this
  pair and lost every case tree, because the case trees have a hook of their own
  (harness.test-support/ensure-cleanup-hook!) and this pair had none.

  INSTALLED WHERE THE PAIR IS MADE, so that no pair ever exists during a moment when
  this process's exit has nothing to remove it -- the same ordering
  harness.infra.shell/ensure-exit-hook! keeps for its children. Installed once, because
  its caller makes the pair once.

  BEST EFFORT, and measured as such: two suites stopped at 14s left nothing at all, and
  a file written into the root after the listing was taken is the one thing a delete
  cannot win against -- that is one directory per interrupted run at worst, against the
  ~274 trees a whole run used to leave."
  []
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable cleanup! "test-run-cleanup"))
  nil)

(defn isolate!
  "Point the config root, the OS home AND the fence's temp answer at fresh temp
  directories for this process. Returns the root directory. Idempotent: a second
  call reuses the first set.

  All of them are siblings, never nested -- see the docstring above for why nesting
  would tamper with what the fence tests are asking.

  THE PAIR IS NOT A CASE'S SCRATCH TREE, and this namespace's `cleanup!` is the only
  thing that removes it: it has to outlive every case in the run, so it is made with
  `:track? false`. Left in the registry it would be within reach of
  harness.test-support/wipe-temp-dirs! -- which the run calls at its end and the case
  trees' own shutdown hook calls at every exit -- and a wipe that took it would delete
  the root the run is still writing into. The invariant is asserted in
  test/harness/test_runner_test.clj, and it is asked of the registry rather than by
  wiping, because the destructive way to ask it reports the answer as hundreds of
  failures somewhere else.

  AND IT IS REMOVED BY A HOOK OF ITS OWN, not only by `run-suite!`'s `finally`: see
  `ensure-cleanup-hook!` -- a run stopped from outside never reaches that `finally`,
  and the pair would otherwise be the one thing a Ctrl+C left behind."
  []
  (or @tmp-home
      ;; MKDIR-TEMP, NOT `tmpdir + name`: the pair belongs to THIS process, and a
      ;; composed name would be the same path for the run happening beside it -- see
      ;; harness.test-support/temp-dir, which is where the how and the why live.
      (let [dir   (support/temp-dir "test" {:track? false})
            home' (support/temp-dir "test-home" {:track? false})
            tmp'  (support/temp-dir "test-tmp" {:track? false})]
        (support/seed-config! dir)
        (alter-var-root #'home/*root-override* (constantly dir))
        (alter-var-root #'home/*user-home-override* (constantly home'))
        (reset! tmp-home dir)
        (reset! tmp-user-home home')
        (alter-var-root #'env/*temp-dir-override* (constantly [tmp']))
        (reset! tmp-temp-dir tmp')
        ;; BEFORE THE PAIR IS HANDED BACK, so that no run ever exists during a moment
        ;; when this process's exit has nothing to remove it.
        (ensure-cleanup-hook!)
        dir)))

(defn- store-state
  "F as [bytes mtime], or nil when it is not there. Two numbers rather than a bare
  exists?: once the developer HAS a store -- the normal state of a working install
  -- `exists?` is true before and after and would notice nothing, while a byte
  count and an mtime still move if anything wrote to it. The store is rewritten in
  place and grown by appends, so any write moves at least one of the two."
  [^java.io.File f]
  (when (.exists f)
    [(.length f) (.lastModified f)]))

(defn isolation-verdict
  "The post-run half of the isolation claim, decided from TWO facts: OPENED, the
  store paths this process resolved (see harness.infra.db/store-paths-opened), and
  BEFORE/AFTER, the developer's store fingerprinted as [bytes mtime] on either side
  of the run (nil meaning absent).

    this process opened the real store  ->  FAILURE, named
    somebody else changed that file     ->  a NOTE, and the run stays green
    neither                             ->  green, silently

  WHY THE FIRST FACT DECIDES IT. 'The file moved' is evidence about a FILE, not
  about a process, and the developer's machine has a second process that legitimately
  writes that file all day: the harness session they are working in -- anchors from
  every edit, todo lists, session rows -- whose own store IS the one the assertion
  is about. A verdict made of the file alone therefore reports the fixture's own
  neighbour as a violation (measured 2026-09-20: an idle full-suite run left the
  file byte-and-mtime identical, while a single anchored `read` by the live session
  moved its mtime). Naming the paths this process opened answers the question that
  was actually being asked -- 'did the suite go to the developer's home' -- and the
  fingerprint stays, demoted to what it can honestly say: somebody wrote it.

  THE FILE IS LEFT WHERE IT IS in every branch: tidying away evidence would be the
  second mistake.

  It answers TRUE OR FALSE, not 'nil-or-false': -main folds this into the exit
  code with `(if isolated? 0 1)`, and a `when-not` that let the good path fall off
  the end would answer NIL -- which that test reads as failure. The suite then
  exits 1 on a run with no failures at all, and an exit code that is 1 either way
  says nothing; a signal that cannot distinguish green from red is worse than no
  signal, because it is read as one.

  F is the File captured before `isolate!` ran, never one recomputed from
  harness.infra.home here -- by now the root points at the temp home, so asking again
  would compare the temp store against itself and pass while the real home was
  being written.

  PUBLIC FOR THE SAME REASON `isolate!` AND `run-suite!` ARE: both branches of the
  claim are driven from one place, and a branch only the happy path ever reaches is
  a branch nobody has seen work. `test/harness/test_runner_test.clj` drives all
  three."
  [^java.io.File f before opened after]
  (let [path (-> f .getAbsolutePath)
        moved? (not= before after)]
    (cond
      (contains? opened path)
      (do (binding [*out* *err*]
            (println (str "ISOLATION FAILURE: this process opened the developer's real store"
                          " at " path " during the run, and that is the one thing the"
                          " fixture exists to prevent -- some code path resolved the store"
                          " against the developer's real home instead of through"
                          " harness.infra.home. (The file itself is "
                          (if moved? (str "changed: " (pr-str before) " -> " (pr-str after))
                              "unchanged: a read in the wrong home is already the wrong home")
                          ".)")))
          false)

      moved?
      (do (binding [*out* *err*]
            (println (str "ISOLATION NOTE: " path " changed during this run: "
                          (pr-str before) " -> " (pr-str after)
                          " ([bytes mtime], nil meaning absent) -- and THIS PROCESS NEVER"
                          " OPENED IT, so it was another process: a live harness session"
                          " keeps its own state (anchors, todo lists, session rows) in that"
                          " store while somebody works. NOT a failure.")))
          true)

      :else
      true)))

;; ------------------------------------------------------------ the time limits

;; WHAT THEY ARE FOR. A suite that never returns used to leave no trace of WHY:
;; 2026-09-20, a reflective call in a hot loop held `harness.kernel.tools-test` for
;; tens of minutes, the run printed its last `Testing` line and then nothing, and
;; finding the namespace took three `jstack`s (one run nobody watched stayed alive
;; for four days). The fix for THAT bug is in `harness.cap.hashline.anchors`; what
;; is here is the guarantee that the next one cannot be invisible.
;;
;; THE NUMBERS ARE HEADROOM, NOT TARGETS. Healthy on this developer machine
;; (2026-09-20): the whole suite ~110s, its slowest namespace ~16s
;; (`harness.kernel.tools-test`, the one that hung). 300s is that namespace times
;; nineteen, 1800s is the whole suite times sixteen -- and BOTH ARE FOR A STUCK RUN
;; ONLY: a limit that fires on a merely slow machine gets raised until it is no
;; limit at all, which is why they are read from the environment and why the
;; refusal below is loud.

(def ^:private default-namespace-limit-ms (* 5 60 1000))
(def ^:private default-run-limit-ms (* 30 60 1000))

(defn limit-ms
  "RAW -- seconds, as the string an environment variable holds, or nil for unset --
  in milliseconds, or DEFAULT-MS when RAW is nil.

  ANYTHING THAT IS NOT A POSITIVE WHOLE NUMBER OF SECONDS IS REFUSED rather than
  ignored: somebody who set a hard limit and silently got none has been told
  something false about their run, and silence about a time limit is exactly the
  failure this section exists to end."
  [raw default-ms]
  (if (nil? raw)
    default-ms
    (let [seconds (parse-long raw)]
      (when-not (and seconds (pos? seconds))
        (throw (ex-info (str "test time limit must be a positive number of seconds, got "
                             (pr-str raw))
                        {:value raw})))
      (* 1000 seconds))))

(defn- limits
  "The two limits in force for this run, in milliseconds.

  ENV: CLJ_HARNESS_TEST_NAMESPACE_TIMEOUT_SECS (default 300) is the budget for ONE
  namespace, and for loading the whole list, which is one `require` call and so
  cannot name a single namespace if it hangs. CLJ_HARNESS_TEST_RUN_TIMEOUT_SECS
  (default 1800) is the budget for the RUN, checked between namespaces -- a
  namespace already running is not cut short by it, because its own budget is what
  can name it."
  []
  {:namespace-ms (limit-ms (System/getenv "CLJ_HARNESS_TEST_NAMESPACE_TIMEOUT_SECS")
                           default-namespace-limit-ms)
   :run-ms       (limit-ms (System/getenv "CLJ_HARNESS_TEST_RUN_TIMEOUT_SECS")
                           default-run-limit-ms)})

(defn run-with-deadline
  "Run F on a thread of its own and answer what became of it, instead of throwing
  or waiting forever:

    {:status :ok      :value v :ms n}
    {:status :threw   :threw e :ms n}
    {:status :timeout :budget-ms b :ms n :state \"RUNNABLE\" :stack [frame ...]}

  A THREAD OF ITS OWN IS THE ONLY WAY TO PUT A LIMIT ON IT. Work that has stopped
  returning cannot be interrupted from outside -- the 2026-09-20 hang was a
  reflective call in a loop, with no sleep and no lock in it to interrupt -- so the
  runner hands the work to a DAEMON thread and stops waiting. The thread is not
  killed (nothing can kill it safely); it is ABANDONED, which is the honest thing
  to do with a thread whose state is unknown, and the process exits right after.

  THE STACK IS THE POINT of the timeout branch: it is the `jstack` nobody thought
  to take, captured while the thread is still there to be asked. `state` comes with
  it because RUNNABLE and WAITING are different stories -- a spin versus a lock.

  PUBLIC FOR THE SAME REASON `isolate!` IS: a branch a test cannot reach is a
  branch nobody has seen work, and the timeout branch is only reachable from a
  thunk that does not return. `test/harness/test_runner_test.clj` drives all three."
  [budget-ms f]
  (let [started (System/currentTimeMillis)
        answer  (promise)
        ;; ONE deliver per promise, so a thunk that finishes (or throws) after the
        ;; limit fired is harmless: nobody is waiting for it any more.
        thread  (doto (Thread. (fn [] (deliver answer (try {:value (f)}
                                                           (catch Throwable e {:threw e}))))
                               "test-runner")
                  (.setDaemon true)
                  (.start))
        result  (deref answer budget-ms ::timeout)
        ms      (- (System/currentTimeMillis) started)]
    (if (identical? ::timeout result)
      {:status :timeout, :budget-ms budget-ms, :ms ms,
       :state (str (.getState thread)), :stack (vec (.getStackTrace thread))}
      (assoc result :status (if (contains? result :threw) :threw :ok), :ms ms))))

(defn- seconds-text
  "MS as seconds: `300s` for a whole number of them (which is what a limit is, and
  how the environment variable is written) and `16.3s` otherwise (which is what a
  namespace's elapsed time is)."
  [ms]
  (if (zero? (mod ms 1000))
    (str (quot ms 1000) "s")
    (str (quot ms 1000) "." (quot (mod ms 1000) 100) "s")))

(defn limit-report
  "The lines to print when a run hits one of its limits. Three kinds, because the
  three are fixed differently and whoever reads this needs to know which:

    :loading    the namespaces never finished LOADING -- nothing has run, and the
                stack names the file that was being loaded
    :namespace  one namespace never finished running -- its own counters are lost
                with it, so nothing it asserted has a verdict
    :run        the run passed its total budget between namespaces -- nothing is
                stuck, there is simply more suite than budget

  RETURNS THE LINES instead of printing them, which is what makes the wording
  testable without a test that hangs; `print-limit-report!` is the printing half."
  [{:keys [kind subject budget-ms ms total ran not-run state stack]}]
  (let [finished (count ran)
        head     (case kind
                   :loading
                   (str "TEST RUN TIMEOUT: the test namespaces did not finish LOADING within "
                        (seconds-text budget-ms) " -- the run stops here, exit code 2.")

                   :namespace
                   (str "TEST RUN TIMEOUT: " subject " did not finish within "
                        (seconds-text budget-ms) " -- the run stops here, exit code 2, rather"
                        " than waiting for a verdict it may never reach.")

                   :run
                   (str "TEST RUN TIMEOUT: the run passed its total budget of "
                        (seconds-text budget-ms) " between namespaces -- it stops here, exit code 2."))
        detail   (case kind
                   :loading
                   ["  Nothing has run yet, so there are no counters to report: the frames below"
                    "  name the file that was being loaded, which is where to look."]

                   :namespace
                   [(if (zero? finished)
                      (str "  It was still running when the limit hit, so its assertions have NO verdict"
                           " -- and nothing had finished before it, so there are no counters below to read"
                           " either.")
                      (str "  It was still running when the limit hit, so its assertions have NO verdict:"
                           " the counters below are the " finished " namespaces that FINISHED, and this"
                           " one is not among them."))
                    (str "  thread state: " state)
                    "  where it was, captured before the process leaves (the jstack nobody took):"]

                   :run
                   ;; THE ELAPSED TIME IS ITS OWN LINE, and it is added only when
                   ;; there is one: this report is printed on the way out, so it
                   ;; must not be the thing that throws when a caller hands it a
                   ;; map that is missing a key.
                   (cond-> [(str "  Nothing is stuck: the suite is slower than the budget. "
                                 finished " of " total " namespaces had finished, loading included.")]
                     (number? ms) (conj (str "  " (seconds-text ms) " had elapsed when the check ran."))))]
    (vec (concat [head]
                 detail
                 ;; THE STACK STRAIGHT AFTER THE LINE THAT PROMISES IT, and the
                 ;; names of what never ran last: the stack answers "where", the
                 ;; names answer "so what did I lose".
                 (when (seq stack)
                   (cons "  stack:" (map #(str "    " %) (take 25 stack))))
                 (when (seq not-run)
                   [(str "  never started: " (str/join ", " not-run))])))))

(defn print-limit-report!
  "Print LIMIT-REPORT's lines for M, and answer them.

  ON STDERR, and that is not a style choice: one way to hang this suite is a
  test writing to a stdout that nobody is reading any more (the pipe is full and
  stays full), and a report sent to that same stream is the one message that never
  arrives. `isolation-verdict` prints its bad news to *err* for the same reason."
  [m]
  (let [lines (limit-report m)]
    (binding [*out* *err*]
      (doseq [line lines] (println line))
      (flush))
    lines))

;; -------------------------------------------------------------- running them

(defn- run-namespaces!
  "Run NAMESPACES one at a time, each under its own deadline, and answer

    {:status :ok,       :counters {...}, :ran [name ...]}
    {:status :threw,    :threw e, :subject name}
    {:status :timeout,  :kind :namespace|:run, ... }   ;; the shape limit-report reads

  ONE NAMESPACE AT A TIME IS THE POINT: a limit has to be attached to something,
  and the something is one namespace's run. `test-ns` is the per-namespace half of
  `run-tests` (it prints the same `Testing` line and no summary), which is why the
  summary at the end of the run is printed by this runner instead of by
  clojure.test -- see `run-suite!`."
  [namespaces started run-ms namespace-ms]
  (loop [todo     (seq namespaces)
         counters {:test 0, :pass 0, :fail 0, :error 0}
         ran      []]
    (if-let [ns (first todo)]
      (let [elapsed (- (System/currentTimeMillis) started)]
        (if (>= elapsed run-ms)
          {:status :timeout, :kind :run, :budget-ms run-ms, :ms elapsed,
           :counters counters, :ran ran, :not-run (vec todo)}
          (let [result (run-with-deadline namespace-ms #(t/test-ns ns))]
            (case (:status result)
              :threw   {:status :threw, :threw (:threw result), :subject ns}

              :timeout {:status :timeout, :kind :namespace, :subject ns,
                        :budget-ms (:budget-ms result), :ms (:ms result),
                        :state (:state result), :stack (:stack result),
                        :counters counters, :ran ran, :not-run (vec (rest todo))}

              :ok      (do
                         ;; ONE LINE PER NAMESPACE, so a post-mortem can see which
                         ;; namespace is slow -- and, on a hang, which `Testing`
                         ;; line has no line under it.
                         (println (str "  [" (seconds-text (:ms result)) "] " ns))
                         (recur (next todo)
                                (merge-with + counters (:value result))
                                (conj ran ns)))))))
      {:status :ok, :counters counters, :ran ran})))

(defn exit-code
  "The process's exit code, from OUTCOME (`run-namespaces!`'s answer) and ISOLATED?
  (`isolation-verdict`'s):

    0  the run finished and nothing failed
    1  something failed -- a test, or the isolation verdict
    2  a time limit was hit, so the run never reached a verdict; a partial green
       summary is not one, and neither is a partial red one

  A LIMIT OUTRANKS A FAILURE even when the namespaces that DID run had failures:
  the reader's next move is different (go look at the stuck namespace, not at the
  red one), and an exit code that cannot say which is worse than no code at all."
  [outcome isolated?]
  (let [counters (:counters outcome)]
    (cond
      (not= :ok (:status outcome))
      2

      (pos? (+ (:fail counters 0) (:error counters 0) (if isolated? 0 1)))
      1

      :else
      0)))

(defn run-suite!
  "Run NAMESPACES under the isolation protocol AND the time limits, and answer the
  process's exit code: 0 green, 1 a failure (a test, or the isolation verdict), 2 a
  limit hit -- a run that never reached a verdict is a different thing to tell a
  reader, and a different thing to go and fix.

  PUBLIC FOR THE SAME REASON `isolate!` IS: a subset of the suite has to go through
  the SAME protocol rather than the convenient part of it. A targeted run that
  isolate!'d but never checked the verdict would report green on exactly the
  failure the verdict exists for, and one that never cleaned up would leave a temp
  root behind for every invocation -- and both halves are easy to forget when the
  caller assembles the run by hand. `-main` below is this function plus an exit --
  it is the caller that made the door necessary, and the namespaces handed to it on
  the command line are the ones it runs.

  NOT `run!`: that name is `clojure.core`'s since 1.12, and shadowing it here would
  be a warning at load and a trap for whoever next requires this namespace.

  THE SUMMARY AT THE END IS THIS RUNNER'S, not clojure.test's, and it is the same
  two lines it has always printed: `run-tests` prints one summary for a whole list
  of namespaces, so a limit could not be attached to any one of them, and the
  runner now calls `test-ns` per namespace and adds the counters up itself."
  [namespaces]
  ;; The store's path is resolved through harness.infra.home BEFORE the root moves, so
  ;; it comes from the one place that decides paths rather than a second copy of
  ;; the precedence rule -- and so the File in hand still names the developer's
  ;; real home for the rest of this run.
  (let [store   (home/db-file)
        before  (store-state store)
        ;; BEFORE `isolate!`, so a limit that cannot be parsed fails without leaving
        ;; a temp pair behind on its way out.
        {:keys [namespace-ms run-ms]} (limits)
        started (System/currentTimeMillis)
        dir     (isolate!)]
    ;; FROM HERE ON, ANYTHING THAT OPENS THE DEVELOPER'S STORE IS THIS RUN'S FAULT, and
    ;; the record starts empty so that it says exactly that: the two lines above --
    ;; the fingerprint -- are the only business this process ever has there.
    (db/forget-store-paths-opened!)
    (try
      (println "test config root:" dir)
      (println "test OS home:" @tmp-user-home)
      (println "developer home store before this run:"
               (if before (str "present (" (first before) " bytes, left alone)") "absent"))
      (println "time limits:" (str (quot namespace-ms 1000) "s per namespace, "
                                   (quot run-ms 1000) "s for the run")
               "(CLJ_HARNESS_TEST_NAMESPACE_TIMEOUT_SECS / CLJ_HARNESS_TEST_RUN_TIMEOUT_SECS)")
      ;; LOADING IS FINGERPRINTED TOO, and by the same budget: it is one `require`
      ;; call for the whole list, so it cannot name a namespace if it hangs -- the
      ;; stack in the report names the FILE, which is the next best thing.
      (let [loaded   (run-with-deadline namespace-ms #(apply require namespaces))
            outcome  (cond
                       (:threw loaded) {:status :threw, :threw (:threw loaded)}
                       (= :timeout (:status loaded))
                       {:status :timeout, :kind :loading, :budget-ms namespace-ms,
                        :ms (:ms loaded), :state (:state loaded), :stack (:stack loaded),
                        :ran [], :not-run (vec namespaces)}
                       :else (run-namespaces! namespaces started run-ms namespace-ms))
            counters (:counters outcome)
            ;; THE VERDICT IS CHECKED IN EVERY BRANCH, including the two that end
            ;; early: "did this run touch the developer's home" is a question about
            ;; the process, and a process that is exiting on a timeout or on a
            ;; fixture that blew up is exactly when nobody would think to ask it.
            isolated? (isolation-verdict store before
                                         (db/store-paths-opened)
                                         (store-state store))]
        (cond
          (:threw outcome) (throw (:threw outcome))

          (= :ok (:status outcome))
          (t/do-report (assoc counters :type :summary))

          :else
          (do (print-limit-report! (assoc outcome :total (count namespaces)))
              ;; WHAT DID RUN, labelled: the summary below is real but partial, and
              ;; an unlabelled one on a run that never finished would be read as the
              ;; verdict for the whole suite.
              (when (pos? (:test counters 0))
                (println "\nthe namespaces that finished, before the limit:")
                (t/do-report (assoc counters :type :summary)))))
        (exit-code outcome isolated?))
      ;; A NAMESPACE THAT WILL NOT LOAD THROWS OUT OF `require`, and the temp pair
      ;; must not survive that: the point of the arrangement is that a run leaves the
      ;; machine as it found it, green or red. Same for anything else that throws
      ;; between `isolate!` and the end -- the `finally` is what makes the cleanup a
      ;; property of the protocol rather than of the happy path.
      (finally
        (cleanup!)
        ;; THE CASES' TREES GO THE SAME WAY, and from here rather than from each of the
        ;; hundred-odd call sites that make one: see harness.test-support/wipe-temp-dirs!
        ;; for the how and the why. It comes AFTER `cleanup!` because the run's own pair
        ;; is deliberately not in that registry -- `cleanup!` removes the run's
        ;; environment, the wipe removes what the run's cases left behind, and neither
        ;; reaches into the other's set.
        (let [scratch (support/wipe-temp-dirs!)]
          (when (pos? scratch)
            (println (str "test scratch directories removed: " scratch))))))))

(defn -main
  "Run the suite -- all of it, or only the namespaces named on the command line.

  A TARGETED RUN IS STILL THE WHOLE PROTOCOL: the namespace list is the only thing a
  caller supplies, and the fingerprint, the isolation, the verdict, the cleanup and
  the exit code all stay where `run-suite!` put them. The spelling this replaces --
  `clojure -M:test -e \"(isolate!) (run-tests 'x)\"` -- is the convenient half of the
  protocol and none of the rest.

  THE TIME LIMITS COME WITH IT, and exit 2 is theirs: 0 is green and 1 is red, and
  \"the suite never finished\" is neither -- it is the answer nobody used to get."
  [& args]
  (System/exit (run-suite! (if (seq args) (mapv symbol args) test-namespaces))))
