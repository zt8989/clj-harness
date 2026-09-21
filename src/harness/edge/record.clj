(ns harness.edge.record
  "THE RECORD WRITER: every line is queued, and ONE consumer thread appends it.

  This is ADR 0002 decision 3 (`docs/adr/0002-sessions-live-on-the-server.md`) and
  ticket 02 of `.scratch/sessions-live-on-the-server`. `harness.edge.http/log!`
  used to `spit` the line inside `log-lock` -- the write was on the response path,
  and the lock was there because a hook fires on a tool call's own thread, so two
  lines could be in flight at once. A queue makes that lock unnecessary rather
  than better: the consumer is the ONLY writer, so lines cannot interleave, and
  the response path only hands the line over.

  WHAT THE ASYNC BUYS, AND WHAT IT DOES NOT. It buys that IO is not on the
  response path. It does NOT buy 'fewer writes': the pace is still ONE WRITE PER
  FRAME (decision 6), so the lag between memory and the record is exactly the
  queue's backlog -- which is why the backlog is a number this namespace answers
  rather than a property nobody can see. Batching is deliberately not done here;
  a docstring that claimed it saved IO would be claiming something unmeasured.

  SO THE RECORD IS ALWAYS AN ORDERED PREFIX. Per thread a FIFO queue, one line
  written at a time, in the order they were handed over. A line that cannot be
  written (a full disk, a read-only directory) does NOT get skipped: the queue
  stops there, because writing the NEXT line would leave a hole, and a record
  with a hole is not a shorter conversation -- it is a different, unreplayable
  one. The failed thread is DEGRADED: `degraded` names it, the lines stay queued
  (nothing is silently lost), `pending?` keeps the session from being put away
  (`harness.edge.sessions`), and `retry!` lets the writer resume AT THE FAILED
  LINE when whatever was wrong has been fixed.

  A DEGRADED SESSION KEEPS RUNNING. The live run does not care where the bytes
  go: memory is the authority (ADR 0002 decision 1) and frames reach the browser
  over the run's own stream. What degrades is the RECORD -- the recovery source --
  and the surface that has to say so is the one that reads it (see
  `harness.edge.http/sofar-get`, which reports `:record`).

  THE OFFSET. `flushed-seq` answers the record offset of the last line in a
  thread's file, as far as this process knows: the length the file had when the
  writer began writing to it, plus every line appended since. That is the unit
  ticket 05 mints a conversation's sequence numbers in -- a record offset is
  append-only and replayable, which a pure in-memory counter is not (ADR 0003
  decision 9) -- and ticket 02's half of that contract is: one line in, one record
  out, in order, and the offset is answerable WHILE THE QUEUE IS BEHIND. It is nil
  only for a thread nothing has been handed to yet, where there is no base to count
  from; from the moment the writer looks at the file, a number is a fact about the
  file and not a promise about memory -- `(+ (flushed-seq tid) (pending-count tid))`
  is the offset the session's next entry will get.

  WHERE A LINE GOES IS THE CALLER'S ANSWER. The File is resolved by `log!` on the
  calling thread (a session's project can be rebound, and `log-file-for` is where
  that join lives) and carried in the queue item; this namespace never asks where
  a record belongs, so it needs neither `harness.cap.project` nor a second opinion
  about the log tree. The one thing that must happen BEFORE the first line of a
  thread is `harness.edge.http`'s carry-back, installed here as `prepare-with!`
  -- the same shape as `harness.edge.sessions/watch-unflushed!`: the fact belongs
  to this engine, the knowledge belongs to its caller.

  AND IT WRITES NOTHING ELSE. No sqlite, no process log: the only thing this
  namespace puts on disk is the line it was handed."
  (:require [clojure.java.io :as io]
            [harness.edge.sessions :as sessions])
  (:import (java.io File)
           (java.util.concurrent ConcurrentLinkedQueue LinkedBlockingQueue)))

;; ------------------------------------------------------- the caller's two seams

(defonce ^:private prepare
  ;; tid -> the step that must run before its FIRST line lands, or a no-op.
  (atom (constantly nil)))

(defn prepare-with!
  "Install F as the step that runs before EACH of a thread's lines reaches its
  file. F is handed the thread id and the File the line is going to, and answers
  TRUTHY WHEN IT CHANGED THAT FILE -- which re-bases the thread's offset.

  IT RUNS BEFORE EVERY LINE, not only the first, because the thing it is for is
  not a one-off: `harness.edge.http`'s carry-back looks for a leftover segment on
  the way to each record and stops looking only once it has found one. A hook
  that ran once at birth would miss the segment that appeared later -- which is
  exactly the accident (2026-09-18) the carry-back exists for.

  IT RUNS ON THE CONSUMER THREAD, and that is why it is a seam rather than a call
  at enqueue time: two threads enqueueing for one session would both find the
  same segment, and the consumer is single-threaded by construction. That is the
  serialization the old `log-lock` used to give it."
  [f]
  (reset! prepare (or f (constantly nil))))

(defn reset-prepare!
  "Forget the installed step. For tests, which have no carry-back to run."
  []
  (reset! prepare (constantly nil)))

(defonce ^:private sink
  ;; The write itself, as a fn of [File line]. Replaced by tests that need a write
  ;; to fail or to be slow; the real one is `spit`.
  (atom nil))

(defn set-sink!
  "Make F the write -- (fn [^File f line]) -- until `reset-sink!`. FOR TESTS: it is
  how a full disk and a slow writer are produced without one."
  [f]
  (reset! sink f))

(defn reset-sink!
  "Go back to writing the line to the file with `spit`."
  []
  (reset! sink nil))

(defn- real-sink! [^File f line]
  (spit f line :append true :encoding "UTF-8"))

;; ------------------------------------------------------------------ the table

(defonce ^:private threads
  ;; tid -> {:queue   <ConcurrentLinkedQueue of {:file File :line String}>
  ;;         :path    <String|nil> -- the file the offset below is measured in
  ;;         :base    <long|nil>   -- its own line count when this process started
  ;;                                  writing to it (see `re-base!`)
  ;;         :written <long>       -- lines THIS process has appended to it
  ;;         :failed  <map|nil>    -- the line the consumer stopped on, and why
  ;;         }
  ;; The queue is created once and never replaced: it is the one place a line
  ;; waits, and a writer must not lose a line by swapping a map.
  (atom {}))

(defonce ^:private pending
  ;; Lines queued across every thread and not yet written -- what `flush!` waits
  ;; on and what `pending?` reads. A separate atom rather than a sum over the
  ;; queues because `append!` must be able to answer 'is anything in flight'
  ;; without walking every session.
  (atom 0))

(defonce ^:private dirty
  ;; Thread ids with lines waiting, in the order they were handed a line.
  ;; Duplicates are harmless (a second `serve!` finds the queue empty); a MISSING
  ;; entry is not, which is why the enqueue puts it back even while it is being
  ;; served.
  (LinkedBlockingQueue.))

(defonce ^:private lock
  ;; The monitor `flush!` waits on and the consumer holds while it writes ONE
  ;; line. Holding it is what makes 'nothing is in flight' a fact rather than a
  ;; guess: a waiter that has it cannot be looking at a half-done write.
  (Object.))

(defn- entry-for
  "TID's entry, created on first use."
  [tid]
  (get (swap! threads
              (fn [m]
                (if (contains? m tid)
                  m
                  (assoc m tid {:queue   (ConcurrentLinkedQueue.)
                                :path    nil
                                :base    nil
                                :written 0
                                :failed  nil}))))
       tid))

(defn- file-lines
  "How many lines F already holds -- 0 when it is not there yet."
  [^File f]
  (if (.exists f)
    (with-open [r (io/reader f :encoding "UTF-8")]
      (count (line-seq r)))
    0))

(defn- re-base!
  "Start measuring THREAD-ID's offset in F, at the length F has NOW.

  CALLED WHENEVER THE FILE THE WRITER IS WRITING TO CHANGES UNDER IT, which is
  three occasions and each is a real one:

    - the first line this process writes for the thread (nothing to measure from);
    - the thread's conversation MOVED -- a rebind carries the log to another
      workspace, and the old file's length says nothing about the new one's;
    - the prepare step CHANGED the file (`carry-back!` appends a leftover segment
      and renames its source), so the offsets the earlier lines were given no
      longer describe the file.

  WITHOUT THIS THE OFFSET IS A LIE in each of those cases, and ticket 05's whole
  contract is that the offset it mints sequence numbers from is replayable."
  [tid ^File f]
  (swap! threads assoc-in [tid :path] (.getAbsolutePath f))
  (swap! threads assoc-in [tid :base] (file-lines f))
  (swap! threads update-in [tid :written] (fn [_] 0)))

;; -------------------------------------------------------------------- the API

(defn append!
  "Hand LINE to the writer for THREAD-ID, to be appended to FILE.

  FILE IS RESOLVED BY THE CALLER and carried with the line, because where a
  record belongs is a fact about the session's project (see the namespace note).
  The line is written in the order this was called, once per call -- there is no
  batching, and nothing here decides to skip or merge one."
  [thread-id ^File file line]
  (let [tid (str thread-id)
        q   (:queue (entry-for tid))]
    ;; COUNT FIRST, THEN ENQUEUE: the consumer decrements on the way out, and an
    ;; increment that lost that race would make the counter speak a negative.
    (swap! pending inc)
    (.add q {:file file :line line})
    (.put dirty tid)
    nil))

(defn pending-count
  "How many of THREAD-ID's lines have been handed over and not yet written."
  [thread-id]
  (if-some [e (get @threads (str thread-id))]
    (.size ^ConcurrentLinkedQueue (:queue e))
    0))

(defn pending?
  "Is there anything of THREAD-ID's that has not reached the record?

  THIS IS THE PIN `harness.edge.sessions` ASKS ABOUT before putting a session
  away. Without it, 'put away' would mean rebuilding from a record behind the
  session -- silently, which is the one thing ADR 0002 decision 5 refuses."
  [thread-id]
  (pos? (pending-count thread-id)))

(defn flushed-seq
  "The record offset of the last line in THREAD-ID's file, as far as this process
  knows: the length the file had when the writer began writing to it, plus
  everything appended since (`re-base!` is where the first half comes from and when
  it moves).

  NIL MEANS NOTHING HAS BEEN VOUCHED FOR AT ALL: no line has been handed over for
  this thread, so there is no file to measure. It is not 0 -- a conversation whose
  file already holds 300 lines has not been written at offset 0.

  A NUMBER IS A FACT ABOUT THE FILE, NOT ABOUT MEMORY, and it is true the moment the
  writer looks: the base is counted, and the offset re-based, inside the same
  critical section as the write. So a degraded thread's `flushed-seq` is the record's
  real length -- behind, and honest about where it stops -- and
  `(+ (flushed-seq tid) (pending-count tid))` is the offset the next entry will get."
  [thread-id]
  (if-some [e (get @threads (str thread-id))]
    (when-some [base (:base e)]
      (+ base (:written e)))
    nil))

(defn degraded
  "THREAD-ID's failure, or nil when its lines have all reached the record: a map
  naming the reason, the file, the moment, and how many lines are waiting behind
  the one that failed.

  A FAILURE IS NOT A DROPPED LINE: the line that failed is still at the head of
  the queue and every line after it is still behind it, so the record remains an
  ordered prefix and `retry!` resumes exactly there."
  [thread-id]
  (when-some [f (:failed (get @threads (str thread-id)))]
    (assoc f :pending (pending-count thread-id))))

(defn retry!
  "Let the writer try THREAD-ID's failed line again, and everything behind it.

  THE DOOR FOR 'the disk is back': a degraded session is held in memory and its
  lines are still queued, so this is all it takes to carry on. The consumer
  resumes at the FAILED line -- never at the line after it, which would be the
  hole this namespace exists to refuse."
  [thread-id]
  (let [tid (str thread-id)]
    (when (contains? @threads tid)
      (swap! threads assoc-in [tid :failed] nil)
      (.put dirty tid)))
  nil)

;; ----------------------------------------------------------------- the consumer

(defn- mark-failed!
  "Record that ITEM could not be written to F, and stop THREAD-ID's queue there."
  [tid ^File f ^Throwable t]
  (swap! threads assoc-in [tid :failed]
         {:reason (or (ex-message t) (str (class t)))
          :file   (.getAbsolutePath f)
          :at     (System/currentTimeMillis)}))

(defn- serve!
  "Write TID's queued lines until the queue is empty or one of them cannot be
  written. Answers :drained or :blocked.

  THE PREPARE STEP RUNS BEFORE EVERY LINE and the offset is re-based if it says
  it changed the file, both inside the lock: the step may itself append (the
  carry-back), and a base counted before that append would put this thread's
  offsets short by the carried segment."
  [tid]
  (let [q (:queue (get @threads tid))]
    (loop []
      (if-some [item (.peek q)]
        (let [^File f (:file item)
              ;; THE TRY IS INSIDE THE LOOP, NOT AROUND THE `recur`: a recur may
              ;; not cross a try, and reaching for the next line after a failure
              ;; is the one thing this loop must not do anyway.
              outcome (try
                        (locking lock
                          (when-some [parent (.getParentFile f)] (.mkdirs parent))
                          (let [changed? (@prepare tid f)
                                e        (get @threads tid)]
                            (when (or (nil? (:base e))
                                      (not= (.getAbsolutePath f) (:path e))
                                      changed?)
                              (re-base! tid f)))
                          ((or @sink real-sink!) f (:line item))
                          (.poll q)
                          (swap! pending dec)
                          (swap! threads update-in [tid :written] (fnil inc 0))
                          (.notifyAll lock))
                        :written
                        (catch Throwable t
                          (mark-failed! tid f t)
                          ;; Whoever is waiting on `flush!` must not go on waiting
                          ;; for a line that will never be written.
                          (locking lock (.notifyAll lock))
                          :blocked))]
          (if (= outcome :written)
            (recur)
            outcome))
        :drained))))

(defn- consume!
  "The consumer loop: take a thread id, write what it has queued, repeat.

  A THREAD LEFT BLOCKED IS NOT TAKEN AGAIN until `retry!` puts it back, and that
  is what keeps other sessions being written while one is degraded -- the failure
  is per thread, not per process."
  []
  (loop []
    (let [tid (try (.take dirty) (catch InterruptedException _ nil))]
      (when (some? tid)
        (try (serve! tid) (catch Throwable _ nil))
        (recur)))))

(defonce ^:private consumer (atom nil))

(defn start-consumer!
  "Start the one thread that writes every record this process makes. Idempotent:
  a second call answers the thread already going rather than a second writer --
  two writers is exactly the thing this namespace exists to make impossible."
  []
  (or @consumer
      (let [t (doto (Thread. ^Runnable consume! "harness-record-writer")
                (.setDaemon true))]
        (if (compare-and-set! consumer nil t)
          (do (.start t) t)
          @consumer))))

(defn stop-consumer!
  "Stop the writer thread. FOR TESTS, and for a process that is going away: the
  lines still queued are NOT written by this -- `flush!` is the door that drains."
  []
  (when-some [^Thread t @consumer]
    (when (compare-and-set! consumer t nil)
      (.interrupt t))))

;; ------------------------------------------------------------------- draining

(defn- stuck-count
  "Lines waiting behind a failure -- the part of the backlog draining cannot
  clear. `flush!` is done when only these are left."
  []
  (reduce + 0 (map (fn [[_ e]]
                     (if (:failed e) (.size ^ConcurrentLinkedQueue (:queue e)) 0))
                   @threads)))

(defn- degraded-map []
  (into {} (keep (fn [[tid e]] (when (:failed e) [tid (:failed e)])) @threads)))

(defn- report []
  {:pending  @pending
   :degraded (degraded-map)})

(defn flush!
  "Wait until everything this process has queued has been written -- or until
  TIMEOUT-MS is up. Answers {:pending n :degraded {tid failure}} so a caller can
  say what did not make it rather than assume.

  A LINE BEHIND A FAILURE IS NOT WAITED FOR: a degraded thread never drains, and
  a `flush!` that sat out its whole timeout on one is a shutdown that hangs. The
  answer names it instead.

  THE JVM-EXIT HOOK CALLS THIS (`shutdown!`), because a process that exits
  normally must not lose its tail -- that would be uglier than a crash, which at
  least has an excuse."
  ([] (flush! 10000))
  ([timeout-ms]
   (if (= (Thread/currentThread) @consumer)
     ;; The consumer cannot wait for itself. A test driving `flush!` from the
     ;; writer's own thread gets the state as it stands rather than a deadlock.
     (report)
     (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
       (locking lock
         (loop []
           (if (or (<= @pending (stuck-count))
                   (>= (System/currentTimeMillis) deadline))
             (report)
             (do
               ;; `.wait` RELEASES THE LOCK, which is what lets the consumer in to
               ;; write the very line being waited for; `notifyAll` wakes this up.
               (try (.wait lock 5) (catch InterruptedException _ nil))
               (recur)))))))))

;; ------------------------------------------------------------ the process hooks

(defn install-hook!
  "Hand R to the JVM to run at exit. The JVM's own call, behind a var so a test
  can count the installations without exiting a JVM -- 'installed once' is the
  property, and a second hook would drain twice."
  [^Runnable r]
  (.addShutdownHook (Runtime/getRuntime) r))

(defonce ^:private exit-hook-installed (atom false))

(defn ensure-exit-hook!
  "Install the drain-on-exit hook, ONCE per process. Idempotent by compare-and-set
  rather than by checking a count: two threads enqueueing their first line at the
  same moment is the ordinary case, and 'did I install it' must have one answer."
  []
  (when (compare-and-set! exit-hook-installed false true)
    (install-hook! (Thread. ^Runnable (fn [] (try (flush! 5000) (catch Throwable _ nil)))
                            "harness-record-shutdown")))
  nil)

(defn reset-exit-hook!
  "Forget that the hook was installed, so the next `ensure-exit-hook!` installs
  one. For tests that drive the installation, the same shape
  `harness.cap.jobs/reset-exit-hook!` has."
  []
  (reset! exit-hook-installed false))

(defn shutdown!
  "Drain what is queued and stop the writer. Called by the JVM-exit hook, and
  callable directly -- which is how it is tested (a forked JVM against this repo's
  config home is a known hang; see `harness.cap.jobs/shutdown!`)."
  []
  (let [outcome (flush! 5000)]
    (stop-consumer!)
    outcome))

;; ------------------------------------------------------------- the composition

(defn start!
  "Bring the writer up: pin sessions with unwritten lines, install the exit hook,
  start the consumer. Idempotent -- the composition root may be called more than
  once by a test suite (the shape `harness.kernel.hooks/install!` lives with too),
  and one thing here must NOT happen twice: a second writer."
  []
  (sessions/watch-unflushed! pending?)
  (ensure-exit-hook!)
  (start-consumer!)
  nil)

(defn reset-writer!
  "Drop every queued line and every count, and stop the consumer. FOR TESTS: the
  clean slate between cases that `harness.edge.sessions/drop!` is for the table. A
  process that calls this with lines still queued is a process throwing them away
  on purpose."
  []
  (stop-consumer!)
  (reset! threads {})
  (reset! pending 0)
  (.clear dirty)
  nil)
