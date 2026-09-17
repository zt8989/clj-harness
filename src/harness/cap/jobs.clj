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

(defn- update-job!
  "Change the job at PATH in the registry -- and nothing at all when it is not there.

  NOT `(update-in reg path (fn [j] (when j (f j))))`: `update-in` assocs whatever the
  function returns, so a nil answer RE-CREATES the entry as nil. That is not
  hypothetical, it is how a stopped job came back: the pump is still holding a
  `:next-line` when a stop removes the job and kills its process, so the end of the
  stream arrives a moment later and the entry reappears -- and every later refusal then
  lists a job that is not there. The check decides whether to touch the map at all."
  [path f]
  (swap! registry (fn [reg] (if (get-in reg path) (update-in reg path f) reg))))

(defn- add-line!
  "Append LINE to the job's tail, dropping the oldest line once the tail is full.
  Written as `swap!` on the registry rather than as a mutable deque inside the job, so
  that a reader computes its whole answer from ONE snapshot: with a deque the lines and
  the cursor it pairs them with could come from two different moments."
  [thread-id job-id line]
  (update-job! (path thread-id job-id)
               (fn [j]
                 (-> j
                     (update :total inc)
                     (update :lines (fn [ls]
                                      (let [ls (conj ls line)]
                                        (if (> (count ls) tail-lines)
                                          (subvec ls 1)
                                          ls))))))))

(defn- mark-ended!
  "Record that the command's output stream has ended. The pump calls this when the
  queue answers eof -- which happens only after the reader loop has consumed every
  line the command wrote, so 'ended' is the moment the tail is COMPLETE."
  [thread-id job-id]
  (update-job! (path thread-id job-id) #(assoc % :stream-ended? true)))

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
              (shell/eof? line)     (mark-ended! thread-id job-id)
              (shell/timeout? line) (recur)
              :else                 (do (add-line! thread-id job-id line)
                                        (recur))))))
      (catch Exception _ nil))))

(defn- ended-line
  "`[exit N]` for a process that is gone. Asked of the process rather than remembered,
  and the `?` is a last resort: it is what is left for the moment between 'it was gone'
  and the exit code being read, which is the only way `exitValue` can fail here."
  [handle]
  (str "[exit " (try (.exitValue ^Process (:process handle))
                     (catch Exception _ "?"))
       "]"))

(defn- status-line
  "How JOB is doing, AS THE READER CAN KNOW IT: `[running]` until the command's output
  stream has ENDED (see `mark-ended!`) and the process is gone.

  WHY BOTH, when the process is the thing that died: the tail and the status line are
  read together, and a process that has exited while lines are still queued would
  answer `(no new output)` + `[exit 0]` -- which reads as 'it printed nothing more'.
  Until the pump has seen the end of the stream, `[running]` is the honest answer (the
  next read gets the rest), and once it has, every line the command ever printed is
  either in the tail or counted as dropped."
  [j]
  (if (and (:stream-ended? j) (not ((:alive? (:handle j)))))
    (ended-line (:handle j))
    "[running]"))

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
  [status unread]
  (str/join "\n" (concat
                  (when (pos? (:gap unread))
                    [(str "[" (:gap unread) " earlier lines were dropped]")])
                  (if (seq (:lines unread)) (:lines unread) ["(no new output)"])
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

(defn- with-job
  "Look this session's JOB-ID up and CHANGE it in the same step, answering the job as
  it was BEFORE that change -- or throw when the session does not have it.

  THE ONE STEP IS THE POINT, and it is why both verbs go through here instead of each
  doing its own `swap-vals!`: reading takes the lines and moves the cursor, stopping
  takes the lines and leaves the table, and a reader racing either one must see a job
  with its lines or no job at all -- never a job whose output was taken away from it.
  CHANGE is a fn of the registry and the job's path."
  [thread-id job-id change]
  (let [p (path thread-id job-id)
        [before _] (swap-vals! registry (fn [reg] (if (get-in reg p) (change reg p) reg)))]
    (or (get-in before p)
        (throw (unknown-job thread-id job-id)))))

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
           {:id job-id :handle handle :lines [] :total 0 :cursor 0 :stream-ended? false})
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
  (let [j (with-job thread-id job-id
                    (fn [reg _] (update-in reg [thread-id :jobs] dissoc job-id)))
        running? ((:alive? (:handle j)))]
    (when running? (close! j))
    ;; `[stopped]` only ever comes from here (a read says `[running]` or `[exit N]`),
    ;; so a reader can tell 'I stopped this' from 'it died'. A job that was ALREADY
    ;; gone reports its own exit code, stream drained or not: this answer hands back
    ;; every unread line, so there is nothing left for a later read to catch.
    (answer (if running? "[stopped]" (ended-line (:handle j))) (unread j))))

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
  (let [j (with-job thread-id job-id
                    (fn [reg p] (assoc-in reg (conj p :cursor)
                                          (get-in reg (conj p :total)))))]
    (answer (status-line j) (unread j))))
