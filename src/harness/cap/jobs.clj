(ns harness.cap.jobs
  "The commands this process is running in the background, and what they have said.

  A JOB IS NOT A SLOW TOOL CALL. `bash` returns when its command does, or at its
  own limit; a background job returns nothing at all until somebody asks, because
  what it models is a command NOBODY IS WAITING FOR -- a dev server, a watcher, a
  slow test run. So the shape here is a REGISTRY plus verbs over it, not a call
  that blocks differently (see the `bash` branch of .scratch/bash-lifetime/spec.md).

  PROCESS-LOCAL AND PER SESSION, like the parked approvals and the session tool
  overlay: `{thread-id {job-id job}}`, and the process exiting takes its jobs with
  it (`ensure-exit-hook!`). Jobs deliberately do NOT die with the run -- a job a
  run's end killed would be a background command nobody could ever check on, which
  is the only reason to start one.

  THE TAIL IS BOUNDED, THE CURSOR IS THE SESSION'S. Output is kept as the last
  `tail-lines` lines in memory, not spilled to a file: a file needs a home and a
  cleanup policy, and this is not session history -- it is a command's stdout and
  the reader is the tool that asked for it. Lines that scrolled off before the
  session read them are COUNTED rather than hidden, so 'you missed some' is a fact
  the reader can see instead of a gap it has to guess at.

  ONE READER PER STREAM. `infra.shell/start` owns the two pipe pumps; the thread
  here is that queue's consumer and nothing else may read it. A queue nobody drains
  grows with the process -- which is exactly what a chatty background command would
  do to a long-lived harness."
  (:require [clojure.string :as str]
            [harness.infra.shell :as shell]))

(def tail-lines
  "How many lines of a job's output are kept. A job is a command nobody is waiting
  for, so it may print far more than any reader wants: the tail is what a reader can
  act on, and the count of what was dropped is the honest part. One number, and the
  tool's description interpolates it."
  500)

(defonce ^:private registry
  ;; thread-id -> {:jobs {job-id job}}. A job is
  ;;   {:id .. :handle <shell/start's> :lines [..] :total n :cursor n}
  ;; where `:total` counts every line ever written and `:lines` holds the last
  ;; `tail-lines` of them -- so the number dropped before a reader got there is
  ;; `(- total (count lines))`, and `:cursor` is the absolute index the session has
  ;; read up to.
  (atom {}))

(defonce ^:private counters
  ;; thread-id -> how many jobs it has ever started. IDS DO NOT GO BACKWARDS and are
  ;; never reused: `j1` means one job for as long as the process lives, so a model
  ;; holding an old answer cannot address a different command by accident. Counted
  ;; per session, because an id only has to be unambiguous inside the session that
  ;; will use it.
  (atom {}))

(defonce ^:private exit-hook-installed
  (atom false))

(defn- path [thread-id job-id] [thread-id :jobs job-id])

(defn- known-ids
  "This session's job ids, for a refusal that says what the caller could have meant."
  [thread-id]
  (vec (sort (keys (:jobs (get @registry thread-id))))))

(defn- unknown-job
  "The refusal for a job id this session does not have. Named, with the ids that DO
  exist -- the same shape `skill` uses for an unknown skill and `read` for a missing
  file: a refusal that does not say what is there sends the reader hunting."
  [thread-id job-id]
  (let [ids (known-ids thread-id)]
    (ex-info (str "unknown job: " job-id ". "
                  (if (seq ids)
                    (str "This session's jobs are " (str/join ", " ids) ".")
                    "This session has no background jobs.")
                  " A job lives only as long as this harness process.")
             {:reason :unknown-job :job job-id :known ids})))

(defn- next-id!
  "The session's next job id. `swap-vals!` because the number to hand out and the map
  that remembers it have to move in the same step -- two calls starting a job at the
  same moment must not come away with the same id."
  [thread-id]
  (let [[_ after] (swap-vals! counters update thread-id (fnil inc 0))]
    (str "j" (get after thread-id))))

;; ------------------------------------------------------------------- the output

(defn- add-line!
  "Append LINE to the job's tail, dropping the oldest line once the tail is full.
  Written as `swap!` on the registry rather than a mutable deque inside the job, so
  a reader always sees a whole job: the immutable-data rule for this codebase, and
  the reason a reader can compute its answer from one snapshot."
  [thread-id job-id line]
  (swap! registry update-in (path thread-id job-id)
         (fn [j]
           (when j
             (-> j
                 (update :total inc)
                 (update :lines (fn [ls]
                                  (let [ls (conj ls line)]
                                    (if (> (count ls) tail-lines)
                                      (subvec ls 1)
                                      ls)))))))))

(defn- pumping!
  "Drain this job's line queue into its tail, on a thread of its own, until either
  the command's stdout ends or the job leaves the registry (a stop, or a shutdown).

  The queue is polled rather than blocked on forever so that 'this job is gone' is
  noticed while the command is still silent: a job that says nothing for an hour
  must not hold a thread that nobody can stop."
  [thread-id job-id handle]
  (future
    (try
      (loop []
        (when (get-in @registry (path thread-id job-id))
          (let [line ((:next-line handle) 1000)]
            (cond
              (shell/eof? line)     nil
              (shell/timeout? line) (recur)
              :else                 (do (add-line! thread-id job-id line)
                                        (recur))))))
      (catch Exception _ nil))))

(defn- status-line
  "Whether the job is running, or what it exited with. ASKED OF THE PROCESS rather
  than remembered: nothing had to happen for a job to end, so the answer is a
  question and not a field somebody has to keep up to date."
  [handle]
  (if ((:alive? handle))
    "[running]"
    (str "[exit " (try (.exitValue ^Process (:process handle))
                       (catch Exception _ "?"))
         "]")))

(defn- unread
  "What J has that its session has not read: the lines, and how many lines scrolled
  off the tail before it could read them."
  [j]
  (let [dropped (- (:total j) (count (:lines j)))
        from    (max (:cursor j) dropped)]
    {:lines (subvec (vec (:lines j)) (- from dropped))
     :gap   (- from (:cursor j))}))

(defn- answer
  "One shape for every job answer there is: what it said, what was lost, and one
  status line. The dropped-line line is ABSENT when nothing was dropped, so a normal
  read is the command's own output and nothing else."
  [status line-loss]
  (str/join "\n" (concat
                  (when (pos? (:gap line-loss))
                    [(str "[" (:gap line-loss) " earlier lines were dropped]")])
                  (if (seq (:lines line-loss)) (:lines line-loss) ["(no new output)"])
                  [status])))

(defn- close!
  "Stop a job's process -- and everything it started -- and stop its pumps."
  [j]
  (try ((:close! (:handle j))) (catch Throwable _ nil)))

(defn shutdown!
  "Stop every background job this process started, in every session, and forget
  them all.

  Called by the JVM-exit hook below, and callable directly -- which is how it is
  tested. A forked JVM against this repo's config home is a known hang
  (harness.cap.mcp-test records that discovery), so the reap is asserted by calling
  this and watching the processes go, never by forking.

  NOT called when a run ends: a background command that a run's end killed would be
  one nobody could check on later, which is the whole point of starting it."
  []
  (let [jobs (vec (mapcat (fn [[_ v]] (vals (:jobs v))) @registry))]
    (reset! registry {})
    (doseq [j jobs] (close! j))
    nil))

(defn install-hook!
  "Hand R to the JVM to run at exit. The JVM's own call, behind a var so that a test
  can count the installations without exiting a JVM -- 'installed once' is the
  property, and a second hook would run the reap twice."
  [^Runnable r]
  (.addShutdownHook (Runtime/getRuntime) r))

(defn ensure-exit-hook!
  "Install the reap-on-exit hook, ONCE per process. A harness that is killed (or
  exits normally) must not leave background commands running with nobody attached
  to them -- that leak is what the shutdown hook is for, and the same judgement
  harness.cap.mcp makes about the servers it spawns.

  Idempotent by compare-and-set rather than by checking a count: two threads
  starting jobs at the same moment is the ordinary case, and 'did I install it'
  must have one answer."
  []
  (when (compare-and-set! exit-hook-installed false true)
    (install-hook! (Thread. ^Runnable (fn [] (try (shutdown!) (catch Throwable _ nil)))
                            "bash-jobs-shutdown")))
  nil)

(defn reset-exit-hook!
  "Forget that the hook was installed, so the next `ensure-exit-hook!` installs one.
  For tests that drive the installation, exactly as `shell/reset-resolution!` lets a
  test drive the shell chain; a running process's hook is installed once and stays."
  []
  (reset! exit-hook-installed false))

;; ------------------------------------------------------------------- the verbs

(defn start!
  "Start COMMAND as a background job for THREAD-ID, in DIR. Answers the new job's
  id.

  THROWS when the command cannot be spawned at all (this machine has no shell, the
  process limit), and registers NOTHING in that case: a job record for a process
  that never started would answer every later read with '(no new output)' about a
  command that was never there.

  The command goes to the shell a foreground `bash` call would use (`:shape :shell`)
  rather than to `cmd /c` on Windows -- the promise here is the same as `bash`'s."
  [thread-id {:keys [command dir]}]
  (let [handle (shell/start {:command command :dir dir :shape :shell})
        job-id (next-id! thread-id)]
    (ensure-exit-hook!)
    (swap! registry assoc-in (path thread-id job-id)
           {:id job-id :handle handle :lines [] :total 0 :cursor 0})
    (pumping! thread-id job-id handle)
    job-id))

(defn stop!
  "Stop JOB-ID -- it and everything it started -- and forget it.

  THE ANSWER STILL CARRIES WHAT THE SESSION NEVER READ. A stop that silently dropped
  the last lines would make 'read it, then stop it' and 'stop it' two different
  amounts of information, and the short one is what a caller reaches for when the
  job has already gone wrong.

  A JOB THAT HAS ALREADY ENDED IS FORGOTTEN TOO, and answers with its exit code:
  this is the only verb that leaves the registry, so `bash_kill` means 'stop caring
  about this job' in both cases. Asking twice therefore gets the unknown-job refusal
  the second time -- idempotent in the only way that matters, since the second caller
  finds it gone rather than finding it twice.

  THROWS for a job id this session does not have."
  [thread-id job-id]
  (let [p (path thread-id job-id)
        ;; Taking the unread lines and leaving the registry are ONE step: a reader
        ;; racing this sees either a job with its lines or no job at all, never a job
        ;; whose output was taken and then forgotten.
        [before _] (swap-vals! registry
                               (fn [reg]
                                 (if (get-in reg p)
                                   (update-in reg [thread-id :jobs] dissoc job-id)
                                   reg)))]
    (if-let [j (get-in before p)]
      (let [running? ((:alive? (:handle j)))
            line-loss (unread j)]
        (when running? (close! j))
        ;; `[stopped]` only ever comes from here (a read says `[running]` or
        ;; `[exit N]`), so a reader can tell 'I stopped this' from 'it died'.
        (answer (if running? "[stopped]" (status-line (:handle j))) line-loss))
      (throw (unknown-job thread-id job-id)))))

(defn read-output
  "What JOB-ID has printed since this session last read it, and whether it is still
  running: its new lines, a line per batch that scrolled off the tail before the
  session got there, and one status line (`[running]`, or `[exit N]`).

  READING DOES NOT WAIT. Nothing tells this harness that a job produced output or
  that it ended, so `(no new output)` means 'nothing new right now', never 'it is
  finished' -- the status line is what says which.

  THROWS for a job id this session does not have. Taking the lines and moving the
  cursor is ONE step, so two readers of one job cannot be handed the same lines."
  [thread-id job-id]
  (let [p (path thread-id job-id)
        ;; swap-vals! rather than read-then-write: the lines returned and the cursor
        ;; left behind have to be the same step, or two concurrent readers both get
        ;; them and the next read reports a gap that never happened.
        [before _] (swap-vals! registry
                               (fn [reg]
                                 (if (get-in reg p)
                                   (assoc-in reg (conj p :cursor)
                                             (get-in reg (conj p :total)))
                                   reg)))]
    (if-let [j (get-in before p)]
      (answer (status-line (:handle j)) (unread j))
      (throw (unknown-job thread-id job-id)))))
