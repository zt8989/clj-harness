(ns harness.edge.record
  "THE RECORD WRITER: ONE LINE, WRITTEN BY THE CALLER, ON THE CALLER'S THREAD.

  ADR 0007 (`docs/adr/0007-the-record-is-written-synchronously.md`) overturned ADR 0002 decision 3.
  The queue and its consumer thread are gone, because what async bought was 'IO is not on the
  response path' -- and the response path is an ACK now (`POST /api/agent` hands the run to the
  socket) -- while it cost the one thing the record is asked for: WHEN A LINE IS ON DISK, AND
  WHICH LINE IT IS. `append!` answers the offset, so the fact families of ADR 0006 and the cursor
  of ticket 05 can be stamped without a callback on another thread.

  THREE THINGS SURVIVED THE REWRITE EXACTLY, and each is a reason it is a rewrite rather than a
  patch:

    1. THE PREPARE STEP (`prepare-with!`), run before EVERY line, and the RE-BASE it can force
       (`re-base!`). A conversation can be CARRIED BACK -- a leftover segment appended to this
       file -- and the offsets the earlier lines were given then no longer describe it. The base
       is counted, and possibly re-counted, inside the same critical section as the write.
    2. THE SINK (`set-sink!`), which is how a test sees every byte that goes in without a window
       into the writer's internals.
    3. THE OFFSET'S ARITHMETIC: the file held base+written lines before a write, so the line that
       just landed is the one at index base+written-1. It is a LINE COUNT (ADR 0003 decision 1/9:
       `seq` is the record offset of the line an entry arrived in, and it is replayable because it
       is a property of the file).

  A FAILED WRITE DOES NOT MAKE A HOLE. 'A record with a hole is not a shorter conversation, it is a
  different, unreplayable one.' So a line that cannot be written is HELD (per thread, in order),
  NOTHING more is written for that thread while it is degraded, and `retry!` is the one door that
  lands the held lines and everything behind them. `pending-count` is therefore the number of HELD
  lines -- 0 for a healthy thread, which is a fact about the synchronous writer rather than a
  promise about a queue.

  THE CALLER MUST NOT BE A `go` BLOCK. Writing here blocks for the length of a syscall plus the
  flush, and a blocked `go` block PARKS A core.async DISPATCH THREAD for everything else --
  measured on the first attempt: a run held at a tool seam stopped reaching it at all, because the
  body that logs its frames is a `go`. Routes run on http-kit threads and hooks on tool threads,
  which are fine; a run's body belongs on an `async/thread` (`harness.edge.http/run-agent!`).

  ONE HANDLE PER FILE, held open, flushed per line. Holding it is what saves the syscall the old
  `spit :append true` paid on EVERY line (open, write, close); flushing per line is not a
  leftover -- THE WINDOW READS THE RECORD WHILE A RUN IS IN FLIGHT (`harness.edge.replay/entries`
  is that reader), so a byte sitting in this buffer is a byte the reader cannot see."
  (:require [clojure.java.io :as io]
            [harness.infra.log :as log])
  (:import (java.io File FileOutputStream OutputStreamWriter Writer)))

;; -------------------------------------------------------------------- the seams

(defonce ^:private prepare
  ;; tid -> (fn [tid file] changed?)
  (atom (fn [_tid _file] false)))

(defn prepare-with!
  "Install STEP as the step that runs before EACH of a thread's lines reaches its file. It answers
  whether it CHANGED the file (the carry-back appends a segment), which is what forces a re-base."
  [step]
  (reset! prepare (or step (fn [_tid _file] false))))

(defn reset-prepare! []
  (reset! prepare (fn [_tid _file] false)))

(defonce ^:private sink
  ;; (fn [^File f line]) -- nil means the real one.
  (atom nil))

(defn set-sink!
  "Replace the write itself. FOR TESTS: it is the one door that sees every byte that goes in."
  [f]
  (reset! sink f))

(defn reset-sink! []
  (reset! sink nil))

;; ---------------------------------------------------------------- the open handles

(defonce ^:private handles
  ;; canonical path -> Writer, opened in APPEND mode and never left to the OS to reopen.
  ;; Keyed by PATH rather than by thread id: the file is what a handle is a handle ON, and two
  ;; threads of one conversation (a run's and a hook's) write to the same one.
  (atom {}))

(defn- handle-for ^Writer [^File f]
  (let [p (.getAbsolutePath f)]
    (or (get @handles p)
        (let [w (OutputStreamWriter. (FileOutputStream. f true) "UTF-8")]
          (if (contains? (swap! handles assoc p w) p)
            (get @handles p)
            (do (.close w) (get @handles p)))))))

(defn release-handles!
  "Flush and close every open handle, NOW. Two callers, and the second is the reason this is
  public: the EXIT PATH (a process that goes away normally must not leave a line in a buffer --
  that would be uglier than a crash, which at least has an excuse), and A MOVE -- `move-log!`
  RENAMES this conversation's file, and **WINDOWS WILL NOT RENAME A FILE THAT HAS AN OPEN
  HANDLE** (measured: the rebind route answered 400 and its whole suite went red). The next
  line opens a fresh one, so the cost is one syscall on a move, which happens when a person
  re-binds a project -- not per line."
  []
  (doseq [[_ ^Writer w] @handles]
    (try (.flush w) (catch Throwable _ nil))
    (try (.close w) (catch Throwable _ nil)))
  (reset! handles {}))

(defn- close-handles! [] (release-handles!))

(defn- real-sink! [^File f line]
  ;; OPEN, WRITE, CLOSE -- THE HANDLE IS NOT HELD, and that is a WITHDRAWN DECISION rather than an
  ;; optimisation left undone. ADR 0007 decision 3 said 'one handle per file, held open', for the
  ;; syscall `spit :append true` paid on every line. Measured (thirteen assertions, six cases):
  ;; **A LOG'S LIFE INCLUDES BEING DELETED, RENAMED AND MOVED** -- a test wipes one, `project-post
  ;; remove` deletes one, the carry-back renames a leftover segment, `move-log!` moves a
  ;; conversation's file -- AND WINDOWS REFUSES EVERY ONE OF THOSE WHILE A HANDLE IS OPEN. The
  ;; held handle cost five suites to save a syscall per line; what ticket 01 of
  ;; `.scratch/event-persistence` actually needed was the SYNC write and the offset, and neither
  ;; of those is a handle.
  ;;
  ;; PER LINE, NOT PER BATCH, for the reason it always was: the window READS this file while a run
  ;; is in flight, so bytes left in a buffer are bytes the reader cannot see.
  (spit f line :append true :encoding "UTF-8"))

;; ------------------------------------------------------------------ the table

(defonce ^:private threads
  ;; tid -> {:path    <String|nil>  the file the offset below is measured in
  ;;         :base    <long|nil>    its own line count when this process started writing to it
  ;;         :written <long>        lines THIS process has appended to it
  ;;         :held    <vector>      {:file :line :lands} of lines that could NOT be written, in
  ;;                                the order they were handed over -- see the namespace note
  ;;         :failed  <map|nil>     the first of them, and why
  ;;         :retries <long>        times the door was used and the disk refused AGAIN (L2 below)
  ;;         :since-force <long>    lines written since the last promise (`fsync-every` is the pace)
  ;;         :fsync   <map|nil>     {:at :failures :why} -- the promise, and whether it was kept
  ;;         :lost    <bool|nil>    torn down with lines still held -- L3, and no reader can tell
  ;;         }
  (atom {}))

(defonce ^:private lock
  ;; The write lock. `harness.edge.http/log!` used to hold it while resolving WHERE a line goes
  ;; (`move-log!` rewrites the binding and moves the file); that half stayed THERE, and this now
  ;; covers the write itself. `lands` is called after it is released, because it is the caller's
  ;; code and it takes the SESSION's lock -- holding this one while doing that is the lock-order
  ;; inversion ticket 01 of `.scratch/event-persistence` spells out.
  (Object.))

(defn- entry-for [tid]
  (get (swap! threads
              (fn [m] (if (contains? m tid) m (assoc m tid {:path nil :base nil :written 0
                                                             :held [] :failed nil
                                                             :since-force 0 :fsync nil
                                                             :retries 0 :lost nil}))))
       tid))

;; ------------------------------------------------------------------ the promise

(def fsync-every
  "HOW MANY LINES THE WRITER PUTS DOWN BEFORE IT ASKS THE DISK TO PROMISE THEM -- ADR 0007 decision
  2's 'every N batches', and the N ticket 04 of `.scratch/event-persistence` had to name.

  IT IS A `def` RATHER THAN A NUMBER AT THE CALL SITE, because a case has to be able to move it: at
  64, a test that wants to watch the promise happen would write 64 lines -- a test about the number
  rather than about the promise. NIL OR 0 TURNS THIS MOMENT OFF, which is how a case isolates the
  other two."
  64)

(defonce ^:private forcer
  ;; (fn [^File f]) -> nil when the bytes are promised, a reason when they are not.
  (atom nil))

(defn set-forcer!
  "Replace the PROMISE itself. FOR TESTS, and the twin of `set-sink!`: that seam sees every byte
  that goes in, this one sees every fsync ASKED FOR -- and asking is the only thing a promise has
  to show without a crash to measure against."
  [f]
  (reset! forcer f))

(defn reset-forcer! []
  (reset! forcer nil))

(defn- force-file!
  "Ask the OS to put F's bytes past a crash, answering NIL when they are promised and the reason
  they are not otherwise.

  IT OPENS A HANDLE FOR THE LENGTH OF THIS CALL, deliberately: ADR 0007 decision 3 bought a held
  handle for one syscall per line and had to give it back -- WINDOWS REFUSES TO RENAME A FILE THAT
  HAS A HANDLE ON IT, and a log's life includes being renamed and moved (see `real-sink!`). An
  fsync every `fsync-every` lines is not worth reopening that decision.

  A FILE THAT IS NOT THERE IS NOT A FAILURE, and neither is a file no line of this process's ever
  reached: there is nothing to promise."
  [^File f]
  (try
    (if-some [f' @forcer]
      (f' f)
      (when (.exists f)
        (with-open [raf (java.io.RandomAccessFile. f "rw")]
          (.force (.getChannel raf) true))
        nil))
    (catch Throwable t (or (ex-message t) (str (class t))))))

(defn- force-now!
  "Ask for F's promise for THREAD-ID and WRITE DOWN WHAT HAPPENED. A failed fsync is a fact about
  the record that a later reader has to be able to meet (`health`): the bytes ARE in the file and
  will not survive a crash, and the file's LENGTH says nothing about the difference."
  [tid ^File f]
  (if-some [why (force-file! f)]
    (swap! threads update-in [tid :fsync]
           (fn [m] (-> (or m {}) (assoc :why why) (update :failures (fnil inc 0)))))
    (swap! threads update-in [tid :fsync]
           (fn [m] (-> (or m {})
                       (assoc :at (System/currentTimeMillis) :failures 0)
                       (dissoc :why))))))

(defn- force-due!
  "MOMENT ONE OF THREE (ADR 0007 decision 2): every `fsync-every` lines.

  CALLED INSIDE THE WRITE LOCK, right behind the line that landed, because 'how many lines since
  the last promise' is the same fact as 'how many lines this file holds' -- the two move together
  or neither of them is true. A REFUSAL DOES NOT FAIL THE LINE: the bytes are written, and the
  promise is a second, weaker fact that `health` carries apart."
  [tid ^File f]
  (let [every (long (or fsync-every 0))]
    (when (pos? every)
      (let [n (long (get-in (swap! threads update-in [tid :since-force] (fnil inc 0))
                          [tid :since-force]))]
        (when (>= n every)
          (swap! threads assoc-in [tid :since-force] 0)
          (force-now! tid f))))))
(defn- file-lines
  "How many lines F already holds -- 0 when it is not there yet."
  [^File f]
  (if (.exists f)
    (with-open [r (io/reader f :encoding "UTF-8")]
      (count (line-seq r)))
    0))

(defn- re-base!
  "Start measuring THREAD-ID's offset in F, at the length F has NOW.

  CALLED WHENEVER THE FILE THE WRITER IS WRITING TO CHANGES UNDER IT, which is three occasions
  and each is a real one: the first line this process writes for the thread; the thread's
  conversation MOVED (a rebind carries the log to another workspace); and the prepare step
  CHANGED the file (the carry-back). WITHOUT THIS THE OFFSET IS A LIE in each of those cases, and
  the whole contract is that the offset is replayable."
  [tid ^File f]
  (swap! threads assoc-in [tid :path] (.getAbsolutePath f))
  (swap! threads assoc-in [tid :base] (file-lines f))
  (swap! threads update-in [tid :written] (fn [_] 0)))

(defn- offset-of
  "The offset the line that just landed got: base+written lines were in the file before it."
  [tid]
  (let [e (get @threads tid)]
    (+ (long (:base e)) (dec (long (:written e))))))

(defn- mark-failed! [tid ^File f ^Throwable t]
  (swap! threads assoc-in [tid :failed]
         {:reason (or (ex-message t) (str (class t)))
          :file   (.getAbsolutePath f)
          :at     (System/currentTimeMillis)}))

(defn- hold-line! [tid ^File f line lands]
  (swap! threads update-in [tid :held] (fnil conj []) {:file f :line line :lands lands}))

;; -------------------------------------------------------------------- the API

(declare mark-failed!)

(defn append!
  "Write LINE for THREAD-ID to FILE, ON THIS THREAD, and answer the offset it got -- or nil when
  the write could not go through (the line is then HELD, and `retry!` is the door that lands it).

  FILE IS RESOLVED BY THE CALLER and carried with the line, because where a record belongs is a
  fact about the session's project (`harness.edge.http/log!` decides it under its own lock).

  LANDS, WHEN GIVEN, IS CALLED WITH THE OFFSET -- inline, and AFTER this namespace's lock is
  released, because it is the caller's code and it takes the session's lock.

  A LINE THAT CANNOT BE WRITTEN calls nothing and IS NOT LOST: it takes its place in the held
  queue, and NOTHING more is written for that thread until `retry!` -- writing the next one would
  leave a hole, and a record with a hole is a different, unreplayable conversation."
  ([thread-id ^File file line]
   (append! thread-id file line nil))
  ([thread-id ^File file line lands]
   (let [tid (str thread-id)]
     (entry-for tid)
     (if (some? (:failed (get @threads tid)))
       ;; DEGRADED: this line waits behind the one that failed.
       (do (hold-line! tid file line lands) nil)
       (let [landed (atom nil)
             ok?    (try
                      (locking lock
                        (when-some [parent (.getParentFile file)] (.mkdirs parent))
                        (let [changed? (@prepare tid file)
                              e        (get @threads tid)]
                          (when (or (nil? (:base e))
                                    (not= (.getAbsolutePath file) (:path e))
                                    changed?)
                            (re-base! tid file)))
                        ((or @sink real-sink!) file line)
                        (swap! threads update-in [tid :written] (fnil inc 0))
                        (force-due! tid file)
                        (reset! landed (offset-of tid))
                        true)
                      (catch Throwable t
                        (mark-failed! tid file t)
                        (hold-line! tid file line lands)
                        false))]
         (when (and ok? (some? lands))
           (try (lands @landed) (catch Throwable _ nil)))
         @landed)))))

(defn pending-count
  "How many of THREAD-ID's lines are HELD -- a line that could not be written, and everything
  handed over behind it. 0 for a healthy thread: the write is done when `append!` returns, so
  there is no queue to count."
  [thread-id]
  (count (:held (get @threads (str thread-id)))))

(defn pending?
  "Is there anything of THREAD-ID's that has not reached the record?

  THIS IS THE PIN `harness.edge.sessions` ASKS ABOUT before putting a session away. Without it,
  'put away' would mean rebuilding from a record behind the session -- silently, which is the one
  thing ADR 0002 decision 5 refuses."
  [thread-id]
  (pos? (pending-count thread-id)))

(defn flushed-seq
  "The record offset of the last line in THREAD-ID's file: the length the file had when this
  process began writing to it, plus everything appended since (`re-base!` is where the first half
  comes from and when it moves).

  NIL MEANS NOTHING HAS BEEN VOUCHED FOR AT ALL: no line has been written for this thread, so
  there is no file to measure. It is not 0 -- a conversation whose file already holds 300 lines
  has not been written at offset 0.

  SYNCHRONOUSLY TRUE (ADR 0007): the write and this number are decided under the same lock, so
  there is no 'behind' for a healthy thread to be."
  [thread-id]
  (if-some [e (get @threads (str thread-id))]
    (when-some [base (:base e)]
      (+ base (:written e)))
    nil))

(defn degraded
  "THREAD-ID's failure, or nil when its lines have all reached the record: a map naming the reason,
  the file, the moment, and how many lines are held behind the one that failed."
  [thread-id]
  (when-some [f (:failed (get @threads (str thread-id)))]
    (assoc f :pending (pending-count thread-id))))


(defn fsync!
  "MOMENT TWO OF THREE (ADR 0007 decision 2), and the verb a test asks for a promise by hand: put
  what THREAD-ID's record holds on the platter NOW.

  THE MOMENT IS 'THE SESSION IS PUT AWAY' -- the sweeper puts an idle conversation away and `drop!`
  is the explicit door (`harness.kernel.session`, which reaches this through its `:put-away!` seam).
  That is when this process stops being the one that would flush it: an entry gone from the table
  has nobody left to promise its tail."
  ([thread-id]
   (let [tid (str thread-id)]
     (locking lock
       (if-some [p (:path (get @threads tid))]
         (do (force-now! tid (io/file p))
             (nil? (:why (:fsync (get @threads tid)))))
         ;; NOTHING THIS PROCESS WROTE, SO NOTHING TO PROMISE -- which is true rather than absent.
         true)))))

(defn health
  "HOW TRUSTWORTHY THREAD-ID'S RECORD IS RIGHT NOW, as one of FOUR LEVELS, each with the criterion
  that puts a thread there and THE SENTENCE a surface may say (ticket 04 of
  `.scratch/event-persistence`):

    L0  :ok      nothing is held: every line this process wrote is in the file.
    L1  :behind  a line could not be written, so it and everything handed over behind it are HELD,
                 and the disk has not been asked to take them again. NOTHING IS LOST -- `retry!`
                 resumes AT that line, never after it.
    L2  :stuck   the door was used and the disk refused AGAIN. The backlog is still there and the
                 disk is not coming back on its own.
    L3  :lost    the writer was TORN DOWN (the process is exiting) with lines still held, so the
                 record is now SHORTER THAN THE CONVERSATION -- and no later read can tell.

  LEVELS 1 AND 2 ARE ONE FAILURE AT TWO AGES, and splitting them is the whole point of a graded
  answer: a hiccup is not an incident, and a surface that says one word for both teaches its
  reader to ignore it. Level 3 is not an age at all -- it is the moment those lines stop being
  recoverable.

  THE LEVELS ARE ABOUT WHAT THE FILE CONTAINS, NOT ABOUT WHETHER IT WILL SURVIVE A CRASH. A failed
  fsync does not move the level: the bytes ARE in the record, a reader sees them, and the only
  thing in doubt is a crash -- which `:fsync` carries apart, because the two facts rest on
  different evidence (a line count and a syscall).

  `:says` IS THE SENTENCE, spelled here rather than at every surface, so that the page, the log
  and a test say the same thing about the same fact."
  [thread-id]
  (let [tid  (str thread-id)
        e    (get @threads tid)
        held (count (:held e))
        why  (:reason (:failed e))
        left (when-some [w why] (str " (" w ")"))]
    (cond
      (:lost e)
      {:level 3 :state :lost :pending held :retries (long (or (:retries e) 0)) :fsync (:fsync e)
       :says (str "this conversation's record is SHORTER THAN THE CONVERSATION: " held
                  " line(s) were still held when the writer was torn down" left
                  ", and no later read can tell")}

      (and (pos? held) (pos? (long (or (:retries e) 0))))
      {:level 2 :state :stuck :pending held :retries (long (:retries e)) :fsync (:fsync e)
       :says (str "this conversation's record is stuck: the disk refused again when the held lines"
                  " were handed back" left ", so " held " line(s) are still not in it")}

      (pos? held)
      {:level 1 :state :behind :pending held :retries 0 :fsync (:fsync e)
       :says (str "this conversation's record is behind by " held " line(s): the disk refused a"
                  " write" left ". Nothing is lost -- the lines are held in order, and the next"
                  " write that goes through resumes at the first of them")}

      :else
      {:level 0 :state :ok :pending 0 :retries 0 :fsync (:fsync e)
       :says "everything this process wrote is in the record"})))
(defn retry!
  "Write THREAD-ID's held lines, in order, and let later ones through again.

  THE DOOR FOR 'the disk is back'. It resumes AT THE FAILED LINE -- never at the line after it,
  which would be the hole this namespace exists to refuse. It holds the write lock for the whole
  drain, so a line handed over from another thread while this runs lands AFTER the held ones:
  order is preserved, which is the only property a record has."
  [thread-id]
  (let [tid (str thread-id)]
    (when (contains? @threads tid)
      (locking lock
        (swap! threads assoc-in [tid :failed] nil)
        (let [held (:held (get @threads tid))]
          (swap! threads assoc-in [tid :held] [])
          ;; RE-ENTRANT (Clojure's `locking` is a monitor): the drain calls `append!`, which takes
          ;; this same lock, and that is deliberate -- one writer, one order.
          (doseq [item held]
            (append! tid (:file item) (:line item) (:lands item)))
          ;; WHETHER THE DOOR WAS TRIED AND THE DISK REFUSED AGAIN -- the difference between a
          ;; hiccup and an incident (`health`). The clear above already said 'trying'.
          (if (:failed (get @threads tid))
            (swap! threads update-in [tid :retries] (fnil inc 0))
            (swap! threads assoc-in [tid :retries] 0))))
      nil)))

(defn flush!
  "Answer what is still not on disk -- `{:pending n :degraded {tid failure}}` -- so a caller can
  say what did not make it rather than assume.

  WITH A SYNCHRONOUS WRITER THIS IS ALMOST ALWAYS `{:pending 0 :degraded {}}`, and that is the
  point rather than a vestige: THE JVM-EXIT HOOK STILL CALLS IT (`shutdown!`), and what it has to
  report now is the one case that holds lines -- a write that failed."
  ([] (flush! 10000))
  ([_timeout-ms]
   (locking lock
     {:pending  (reduce + 0 (map (fn [[_ e]] (count (:held e))) @threads))
      :degraded (into {} (keep (fn [[tid e]] (when (:failed e) [tid (:failed e)])) @threads))})))

;; ------------------------------------------------------------ the process hooks

(defn install-hook!
  "Hand R to the JVM to run at exit. The JVM's own call, behind a var so a test can count the
  installations without exiting a JVM -- 'installed once' is the property, and a second hook would
  close the handles twice."
  [^Runnable r]
  ;; A THREAD, NOT THE RUNNABLE: `Runtime.addShutdownHook` takes a Thread, and the same wrap
  ;; lives in `harness.infra.shell/install-hook!` -- two hooks, one shape.
  (.addShutdownHook (Runtime/getRuntime)
                    (Thread. ^Runnable r "harness-record-shutdown")))


(defn- force-all!
  "MOMENT THREE OF THREE (ADR 0007 decision 2): the process is going away, so every file this
  process wrote gets its promise -- ONCE EACH, because a file is what a promise is about, not a
  thread (two threads of one conversation write to one file)." []
  (doseq [p (distinct (keep :path (vals @threads)))]
    (force-file! (io/file p))))

(defn- give-up!
  "The writer is being torn down, so lines that are still held WILL NOT BE WRITTEN. This is the
  moment ticket 04's L3 exists for, and the only moment it can be said: once the process is gone
  the record is simply shorter than the conversation, and no later read can tell the difference." []
  (doseq [tid (keys @threads)
          :when (pos? (pending-count tid))]
    (swap! threads assoc-in [tid :lost] true)
    (log/error! :record/lost
                (ex-info "the record writer is going away with lines still held"
                         {:thread-id tid :pending (pending-count tid)})
                {:says (:says (health tid))})))
(defn shutdown!
  "The exit path, in the order those facts have to be said: NAME WHAT WILL NOT SURVIVE (L3), flush
  what can be flushed, promise every file this process wrote, then close every handle. A process
  that exits normally must not lose its tail." []
  (let [state (flush! 1000)]
    (give-up!)
    (force-all!)
    (close-handles!)
    state))

(defonce ^:private exit-hook-installed (atom false))

(defn ensure-exit-hook!
  "Install the drain-on-exit hook, ONCE per process."
  []
  (when (compare-and-set! exit-hook-installed false true)
    (install-hook! (fn [] (shutdown!)))))

(defn start!
  "Bring the writer up: the exit hook, and nothing else -- there is no consumer thread to start
  (ADR 0007). Idempotent."
  []
  (ensure-exit-hook!))

(defn reset-writer!
  "Forget every thread's table and close every handle. FOR TESTS."
  []
  (close-handles!)
  (reset! threads {}))
