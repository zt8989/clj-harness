(ns harness.cap.jobs
  "The commands this process is running in the background, the records they leave
  behind, and the verbs over both.

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

  A COMMAND'S OUTPUT IS A FILE, AND THE FILE IS THE RECORD. A background job gets one
  from the moment it starts (`start!`: `<root>/jobs/<thread-id>/<job-id>.log`, every
  line appended and flushed as it arrives), and a FOREGROUND `bash` call gets one when
  its answer would not fit (`spill!`, `c1`, `c2`, …) -- the same directory, the same
  lifetime, the same ending line, because it is the same thing: what a command said.
  So there is nothing to lose and therefore nothing to report as lost -- no bounded
  tail, no dropped-line count, no cursor recording how much of it this session has
  already seen.

  READING IS STILL A FILE, AND NOW ALSO A VERB. The tools the model already has --
  `bash` (`tail` / `grep` / `cat`), `read`, `grep` -- read a record directly, which is
  enough for 'what did it say'. What they cannot answer is 'is it over yet': the
  caller's own loop is synchronous, so `output` (the face of `job_output`) answers
  the record's last line plus a window of what it said, and `wait: true` blocks on
  the job's own `:ended` promise until that line is written. The position in the
  record is the CALLER'S (`offset`, a line number) -- there is no cursor here, and
  that is on purpose: 'how much have I already seen' is a question this module has no
  standing to answer, and every reader that answered it invented its own edges.

  AND ITS STATE IS ITS LAST LINE. The command's own output is whatever the command
  wrote; this repo appends exactly one line of its own when the job is over --
  `[exit N]` once the stream has been drained and the process is gone, `[stopped]`
  when we stopped it. No `[exit` line therefore MEANS the command is still running,
  which is why a command that lets go of its stdout and lives on (`exec 1>&-`) gets
  no exit line instead of an invented one.

  ONE APPENDER AT A TIME. A record has a single appending Writer and every append
  takes its monitor, so lines land in the order they were written. Only the LAST
  line is claimed rather than written (an atomic get-and-set), because it is the one
  line two threads may both be reaching for: the pump whose stream just ended, and a
  `stop!` stopping the job at that same moment. Whoever claims it writes it; the
  other finds the record closed.

  THE RECORD LIVES IN THE CONFIGURATION HOME, and that is a decision rather than a
  convenience: `cap.project/fence` lists the config home as free, so `read` and
  `grep` reach a record without parking a human, and `bash` (which has no fence of
  its own) can `tail` it. It is NOT session history -- into no jsonl, no database,
  no audit line, and it does not survive the process: the exit hook deletes this
  process's records on the way out (`shutdown!`). A hard-killed process leaves them
  behind, which is the one moment they are most worth reading.

  ONE READER PER STREAM. `infra.shell/start` owns the two pipe pumps; the thread
  here is that queue's consumer and nothing else may read it. A queue nobody drains
  grows with the process -- which is exactly what a chatty background command would
  do to a long-lived harness."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.infra.home :as home]
            [harness.infra.log :as log]
            [harness.infra.shell :as shell])
  (:import [java.io Writer]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]
           [java.util.concurrent.atomic AtomicReference]))

(def ^:private exit-grace-ms
  "How long the pump waits, once the command's output has ended, for the PROCESS to
  be gone before it writes the record's `[exit N]` line.

  Both facts are needed and they do not arrive together. A command that lets go of
  its stdout (`exec 1>&-`) ends the stream while it goes on running, so an exit line
  written the moment the stream ends would be invented. And the exit code is only
  readable once the process has been reaped, which the pipe closing does not by
  itself announce -- so what looked like 'already gone' can be one moment early.
  This is the wait that covers that moment; a command still there when it runs out
  gets no exit line, and that absence is the honest answer."
  2000)

(defonce ^:private registry
  ;; thread-id -> {:jobs {job-id job}}. A job is
  ;;   {:id .. :handle <shell/start's> :path <its record> :writer <see below>}
  ;; THE MAP IS IMMUTABLE AND THE FILE IS NOT: what a job said is in the file, so
  ;; nothing here accumulates it. `:writer` is an AtomicReference to the record's
  ;; appending Writer, or to nil once the record's last line has been written and
  ;; the file closed -- it is both the open-or-closed answer and the lock every
  ;; append takes.
  (atom {}))

(defonce ^:private counters
  ;; thread-id -> how many jobs it has ever started. IDS DO NOT GO BACKWARDS and are
  ;; never reused: `j1` means one job for as long as the process lives, so a model
  ;; holding an old answer cannot address a different command by accident. Counted
  ;; per session, because an id only has to be unambiguous inside the session that
  ;; will use it.
  (atom {}))

(defonce ^:private record-counters
  ;; thread-id -> how many FOREGROUND records it has spilled. A separate count from
  ;; the job ids above, because `c3` and `j3` are two different kinds of thing: one
  ;; is a file a `bash` answer pointed at, the other is a command that can be
  ;; asked, stopped and waited for. Same discipline otherwise -- never reused.
  (atom {}))

(defonce ^:private spilled
  ;; thread-id -> [path ..] -- the records this process wrote for commands it ran in
  ;; the FOREGROUND. Nothing here is addressable and nothing here is live: a spilled
  ;; record is finished the moment it is written. It is held for one reason only --
  ;; `shutdown!` has to know what to take away (AGENTS.md's rule about what the
  ;; configuration home may keep).
  (atom {}))

(defonce ^:private exit-hook-installed
  (atom false))

(defn- path [thread-id job-id] [thread-id :jobs job-id])

(defn- record-path
  "Where THREAD-ID's JOB-ID keeps its record, under the configuration home.

  ONE PLACE BUILDS THIS STRING. The answers the tools give quote it back, and a path
  assembled twice is a path that will eventually be assembled differently -- the same
  reason the default timeout is interpolated into `bash`'s description rather than
  repeated there."
  [thread-id id]
  (str (io/file (home/root) "jobs" (home/sanitize thread-id) (str id ".log"))))

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
  same moment must not come away with the same id.

  AN ID THAT WAS HANDED OUT IS SPENT. `start!` asks for one only after the shell has
  spawned, so a command this machine cannot start consumes nothing -- but a job that
  dies after that (the record file cannot be opened, say) leaves a gap in the
  numbering rather than a number handed out twice, which is the one thing an id may
  not do."
  [thread-id]
  (let [[_ after] (swap-vals! counters update thread-id (fnil inc 0))]
    (str "j" (get after thread-id))))

(defn- next-record-id!
  "The session's next FOREGROUND record id -- `c1`, `c2`, … Same one-step arithmetic as
  the job ids above, and the same promise: a number handed out is never handed out
  again, so a `bash` answer quoting a record path can never be pointing at a later
  command's record."
  [thread-id]
  (let [[_ after] (swap-vals! record-counters update thread-id (fnil inc 0))]
    (str "c" (get after thread-id))))

;; ------------------------------------------------------------------- the record

(defn- open-record!
  "Start JOB-ID's record file, under the config home, and answer the Writer that
  appends to it.

  TRUNCATING rather than appending: job ids do not repeat inside a process, so the
  only file this can find is one a HARD-KILLED earlier process left behind, and that
  file belongs to a command that is not this one. The parent directory is made here
  too -- the record's own directory is the only thing a job adds to the home. UTF-8,
  because that is what `infra.shell` decoded the command's output with: a record that
  re-encoded those lines would turn a command's own bytes into a guess."
  [thread-id job-id]
  (let [f (io/file (record-path thread-id job-id))]
    (io/make-parents f)
    (io/writer f :encoding "UTF-8")))

(defn- append-line!
  "Append LINE to JOB's record and flush it, so a reader that looks NOW sees every
  line that has been written so far -- `tail` on a growing file is the whole point,
  and a line sitting in a buffer is a line the model cannot see.

  NOTHING HAPPENS once the record's last line has been written: the Writer is claimed
  away then (`write-last-line!`), and a pump line arriving after `[exit N]` or
  `[stopped]` would put output after the answer."
  [job line]
  (locking (:writer job)
    (when-let [^Writer w (.get ^AtomicReference (:writer job))]
      (.write w (str line "\n"))
      (.flush w))))

(defn- write-last-line!
  "Write LINE as the record's LAST line -- after everything already in it, and ONCE.

  THE CLAIM IS ONE ATOMIC STEP, and that is what makes it once. Two threads reach for
  this line in the ordinary course of things: the pump that has seen the process go,
  and a `stop!` stopping the job at that same moment. Whoever gets the Writer writes
  it; the other finds nil and leaves the record alone.

  Answers true when this call is the one that wrote the line, and nil when the record
  already had its last line -- neither is an error, and neither is reported as one.

  AND IT RELEASES EVERYBODY WAITING ON THIS JOB (`:ended`). The moment this line
  lands is the moment a reader can know how the command went, so it is the moment a
  `job_output {wait: true}` stops waiting -- whoever wrote the line, the pump or a
  `stop!`. Delivering here rather than in each caller is what keeps that promise in
  one place."
  [job line]
  (locking (:writer job)
    (when-let [^Writer w (.getAndSet ^AtomicReference (:writer job) nil)]
      (try
        (.write w (str line "\n"))
        (.flush w)
        true
        (catch Exception _ nil)
        (finally
          (try (.close w) (catch Exception _ nil))
          (when-let [ended (:ended job)] (deliver ended line)))))))

(defn- close-record!
  "Let go of JOB's record file without writing anything more -- what `shutdown!`
  wants, since its records are about to be deleted."
  [job]
  (locking (:writer job)
    (when-let [^Writer w (.getAndSet ^AtomicReference (:writer job) nil)]
      (try (.close w) (catch Exception _ nil)))))

(defn- delete-record!
  "Take the record at PATH off the disk.

  BEST EFFORT ON PURPOSE: a record that will not delete is a file in this process's
  own home, and failing a tool call -- or an exit -- over it helps nobody. It is
  logged all the same, because a cleanup that silently stopped working is a cleanup
  nobody knows is gone."
  [path]
  (let [f (io/file path)]
    (when (and (.exists f) (not (.delete f)))
      (log/warn! :jobs/record-not-deleted {:path path}))))

(defn- ended-line
  "`[exit N]` for a process that is gone. Asked of the process rather than remembered,
  and the `?` is a last resort: it is what is left for the moment between 'it was gone'
  and the exit code being read, which is the only way `exitValue` can fail here."
  [handle]
  (str "[exit " (try (.exitValue ^Process (:process handle))
                     (catch Exception _ "?"))
       "]"))

(defn- write-exit-line!
  "Append the record's `[exit N]` line -- but ONLY for a process that is gone.

  The `when-not` is the whole judgement: a command that let go of its stdout and
  lives on is still running, and its record must not carry an exit code. The absence
  of that line is how a reader knows."
  [job]
  (when-not ((:alive? (:handle job)))
    (write-last-line! job (ended-line (:handle job)))))

(defn- settle!
  "Wait, BOUNDED, for JOB's process to be gone now that its output has ended.

  The pipe closing and the process being reaped are two different moments, and the exit
  code can only be read at the second -- so without this wait an ordinary command's
  record would end on its last line of output, because `[exit N]` was being written a
  moment too late to be written at all. A command still there when the wait runs out
  (the `exec 1>&-` shape) gets no exit line, which is the honest answer.

  `isAlive` is not enough on its own: it can still say true for a process the kernel has
  already finished with."
  [job]
  (.waitFor ^Process (:process (:handle job)) (long exit-grace-ms) TimeUnit/MILLISECONDS))

(defn- pumping!
  "Drain this job's line queue into its record, on a thread of its own, until either
  the command's stdout ends or the job leaves the registry (a stop, or a shutdown).

  The queue is polled rather than blocked on forever so that 'this job is gone' is
  noticed while the command is still silent: a job that says nothing for an hour
  must not hold a thread that nobody can stop.

  A JOB THAT LEFT THE REGISTRY IS STILL OWED THE EXIT LINE. A stop that arrives in
  the moment between the command ending and this thread draining what it wrote takes
  the entry away, and the record would then end on a line of the command's output --
  a record that looks like a command still running. `write-exit-line!` answers for
  that case too, and the claim makes it safe to ask: a job we stopped has already had
  `[stopped]` written into the same slot."
  [thread-id job-id job]
  (future
    (try
      (loop []
        (when (and (get-in @registry (path thread-id job-id))
                   ;; AND THE RECORD IS STILL OPEN. An entry now OUTLIVES its job
                   ;; (a stopped one stays, so `job_output` and a second `job_kill`
                   ;; can answer for it), so the registry is no longer the thing that
                   ;; says 'stop reading': the claimed Writer is. Without this the
                   ;; loop would spin forever on a job whose pumps are cancelled,
                   ;; asking a dead queue for a line every second.
                   (some? (.get ^AtomicReference (:writer job))))
          (let [line ((:next-line (:handle job)) 1000)]
            (cond
              (shell/eof? line)     (do (settle! job)
                                        (write-exit-line! job))
              (shell/timeout? line) (recur)
              :else                 (do (append-line! job line)
                                        (recur))))))
      (catch Exception _ nil))
    (write-exit-line! job)))

(defn- close!
  "Stop a job's process -- and everything it started -- and stop its pumps."
  [j]
  (try ((:close! (:handle j))) (catch Throwable _ nil)))

(defn shutdown!
  "Stop every background job this process started, in every session, and forget
  them all -- records included.

  THE RECORDS GO BECAUSE THE ANSWER THAT NAMED THEM IS GONE TOO. A job lives as long
  as this process and no longer, and a record pointing at a command nobody can ask
  about any more is litter in the configuration home. It is also why `stop!` does NOT
  delete: there, the path was just handed to a caller to read.

  Called by the JVM-exit hook below, and callable directly -- which is how it is
  tested. A forked JVM against this repo's config home is a known hang
  (harness.cap.mcp-test records that discovery), so the reap is asserted by calling
  this and watching the processes go, never by forking.

  NOT called when a run ends: a background command that a run's end killed would be
  one nobody could check on later, which is the whole point of starting it.

  THE FOREGROUND RECORDS GO WITH THE JOB ONES (`spill!`), for the same reason and on
  the same clock: a `bash` answer that pointed at a file is as dead as the file when
  this process goes, and both are this process's litter in its own home."
  []
  (let [jobs    (vec (mapcat (fn [[_ v]] (vals (:jobs v))) @registry))
        records (vec (mapcat val @spilled))]
    (reset! registry {})
    (reset! spilled {})
    ;; The order is the order of the filesystem: stop the processes, let go of the
    ;; records, and only then delete them -- a file still held open is a file
    ;; Windows will not delete. The pumps cannot write after the reset anyway (the
    ;; last line is claimed, and a claim is once).
    (doseq [j jobs] (close! j))
    (doseq [j jobs] (close-record! j))
    (doseq [j jobs] (delete-record! (:path j)))
    (doseq [p records] (delete-record! p))
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

;; --------------------------------------------------------------- the answer
;;
;; A COMMAND CAN SAY MORE THAN AN ANSWER MAY CARRY, and this section is where that
;; ceiling is decided and where the overflow goes. Three judgements are in it, and
;; each of them is a decision rather than a detail:
;;
;;   BY BYTES, NOT BY LINES. One line of JSON can be a hundred kilobytes, so a line
;;   budget is a budget with a hole in it.
;;
;;   THE TAIL, NOT THE HEAD. What a reader wants from a command that said a great
;;   deal is where it ended up -- a test run's first lines are all `Testing …`. The
;;   whole of it is in the record, and `grep` reaches the middle.
;;
;;   THE OVERFLOW IS WRITTEN, NOT DROPPED, and the answer says how many bytes are
;;   missing and where the rest is. Silently cutting output is how a model comes to
;;   believe it has seen everything (see .scratch/job-output/spec.md, which deleted
;;   a whole verb that had a hole exactly like that one).
;;
;; ONE BUDGET, TWO READERS: `bash` cuts each of its two streams with it, and
;; `job_output` answers with a window of a record that fits inside it. Both tool
;; descriptions interpolate the number rather than writing their own.

(def answer-budget-bytes
  "How many bytes of a command's own output one answer carries before the rest is
  left in the record for a reader to fetch.

  ONE SOURCE. `bash` and `job_output` both interpolate this number into their own
  descriptions and both cut their answers down to it -- a second literal would be a
  second answer to 'how much fits', and the one in the description would be the
  wrong one for however long it took somebody to notice."
  8000)

(defn- utf8-bytes [s]
  (alength (.getBytes ^String s StandardCharsets/UTF_8)))

(defn- char-bytes
  "How many bytes CH takes in UTF-8, as an OVER-estimate: each half of a surrogate
  pair counts three, so the pair comes to six where four would do. Over-counting is
  the safe direction -- it is what keeps a multi-byte character from being cut in
  half -- and the exact number comes from `utf8-bytes` once the cut is made."
  [ch]
  (let [c (int ch)]
    (cond (< c 0x80) 1, (< c 0x800) 2, :else 3)))

(defn- line-start
  "I moved forward to the start of the next line, so a tail never begins with the back
  half of a line. I itself when there is no line break after it: one enormous line is
  still better than an empty answer."
  [s i]
  (if (or (zero? i) (= \newline (.charAt s (dec i))))
    i
    (if-let [j (str/index-of s "\n" i)]
      (inc j)
      i)))

(defn tail-within-budget
  "S as the largest TAIL of it that fits BUDGET bytes, and how many bytes that leaves
  out: `{:text .. :omitted ..}`. Answers the whole of S -- with nothing omitted --
  when it fits, so a caller can branch on `:omitted` alone.

  THE CUT IS MADE AT A LINE BOUNDARY where there is one, and never inside a
  character: an answer that began with the second half of a line, or with a broken
  UTF-8 sequence, would be a bug the reader has to guess at. The omitted count is
  exact -- it is measured against the text that is actually handed back, not against
  the cut point."
  [s budget]
  (let [total (utf8-bytes s)]
    (if (<= total budget)
      {:text s :omitted 0}
      (loop [i (count s), used 0]
        (if (zero? i)
          ;; Unreachable while every char counts at least one byte, and cheap
          ;; insurance against a budget of zero.
          {:text "" :omitted total}
          (let [w (char-bytes (.charAt ^String s (dec i)))]
            (if (> (+ used w) budget)
              (let [text (subs s (line-start s i))]
                {:text text :omitted (- total (utf8-bytes text))})
              (recur (dec i) (+ used w)))))))))

(defn truncation-line
  "The line an answer carries when it could not carry everything: how many bytes are
  missing (from WHICH stream, when the answer has two of them), and where the whole
  of it can be read. NIL PATH means the record could not be written, and then the
  line says that instead of naming a file that is not there."
  ([omitted path] (truncation-line omitted path nil))
  ([omitted path what]
   (str "[truncated: omitted " omitted " bytes" (when what (str " of " what))
        "; " (if path
               (str "the whole output is " path)
               "the whole output could not be written to a record")
        "]")))

(defn spill!
  "Write TEXT as the record of a command THIS CALL ran in the foreground, and answer
  where it is -- or nil when it could not be written.

  A FOREGROUND RECORD IS NOT A JOB. There is no process to stop, no id anyone will
  address, and nothing to read while it grows: it is written once, whole, by the call
  that ran the command. It lives exactly where a job's record lives, though, and for
  the same two reasons -- the configuration home is free of the fence, so `read` and
  `grep` reach it with no human in the way, and `shutdown!` takes this process's
  records with it.

  FAILING TO WRITE IS NOT AN ERROR the caller has to handle: the answer it was going
  to point at is already bounded, so the call still returns a tail and a line saying
  the rest could not be kept. It is logged, because a record nobody can write is a
  leak, not a hiccup."
  [thread-id text]
  (try
    (let [p (record-path thread-id (next-record-id! thread-id))
          f (io/file p)]
      ;; REGISTERED BEFORE IT IS WRITTEN: a half-written file is litter too, and the
      ;; exit hook has to know about it either way.
      (swap! spilled update thread-id (fnil conj []) p)
      (io/make-parents f)
      (spit f text :encoding "UTF-8")
      (ensure-exit-hook!)
      p)
    (catch Exception e
      (log/warn! :jobs/record-not-written {:error (ex-message e)})
      nil)))

;; ------------------------------------------------------------------- the verbs

(defn- with-job
  "Look this session's JOB-ID up and CHANGE it in the same step, answering the job as
  it was BEFORE that change -- or throw when the session does not have it.

  THE ONE STEP IS THE POINT: the answer a caller gets and the table it leaves behind
  come from one snapshot, so a caller stopping a job and a pump writing to that job
  cannot see two different tables. 'Change' is a fn of the registry and the job's
  path, and it is applied ONLY when the entry is there: `update-in` assocs whatever
  the function returns, so a change written as `(fn [reg _] ...)` against a missing
  path would put the entry back as nil."
  [thread-id job-id change]
  (let [p (path thread-id job-id)
        [before _] (swap-vals! registry (fn [reg] (if (get-in reg p) (change reg p) reg)))]
    (or (get-in before p)
        (throw (unknown-job thread-id job-id)))))

(defn start!
  "Start COMMAND as a background job for THREAD-ID, in DIR. Answers the new job's id
  and where its record is: `{:id \"j1\" :path \"…\"}`.

  THROWS when the command cannot be spawned at all (this machine has no shell, the
  process limit), and registers NOTHING in that case -- a job record for a process
  that never started would be a path pointing at nothing, and the file it left behind
  would be a command's last words that no command ever said.

  The command goes to the shell a foreground `bash` call would use (`:shape :shell`)
  rather than to `cmd /c` on Windows -- the promise here is the same as `bash`'s."
  [thread-id {:keys [command dir]}]
  (let [handle (shell/start {:command command :dir dir :shape :shell})
        job-id (next-id! thread-id)
        p      (record-path thread-id job-id)]
    (try
      (let [job {:id job-id :handle handle :path p
                 :writer (AtomicReference. (open-record! thread-id job-id))
                 ;; DELIVERED WHEN THE RECORD GETS ITS LAST LINE, whoever writes it.
                 ;; A `job_output {wait: true}` blocks on this rather than polling the
                 ;; file: the line and this and the moment are one event, so there is
                 ;; nothing to poll for.
                 :ended (promise)}]
        (ensure-exit-hook!)
        (swap! registry assoc-in (path thread-id job-id) job)
        (pumping! thread-id job-id job)
        {:id job-id :path p})
      (catch Throwable t
        (close! {:handle handle})
        (delete-record! p)
        (throw t)))))

;; --------------------------------------------------------------- reading a job
;;
;; TWO FACTS, AND THE RECORD ANSWERS BOTH. What a job SAID is its file, and how it
;; went is the file's last line -- a convention this repo already keeps
;; (`.scratch/job-output/spec.md` decision 6): `[exit N]` once the stream is drained
;; and the process is gone, `[stopped]` when we stopped it, and NO such line means it
;; is still running.
;;
;; THOSE ARE THE ONLY STATES. There is no separate enum of running/stopping/
;; completed/killed/failed to invent, keep and get wrong: the two things a reader
;; wants to know are whether it is over and how it ended, and the last line says
;; both. `job_output`'s answer prints that line as its first line and the command's
;; own output below it.

(defn- record-lines
  "PATH's record as a vector of lines -- empty when the file is empty, and empty when
  it is not there at all (a caller asking about a record that has been taken away
  gets an answer, not an exception)."
  [path]
  (try
    (let [text (slurp path :encoding "UTF-8")]
      (if (str/blank? text) [] (vec (str/split-lines text))))
    (catch Exception _ [])))

(defn- ending-of
  "The last line of the record at PATH when it is one of the lines this repo appends

  -- `[exit N]` or `[stopped]` -- and nil when the record does not end on one.

  THE `[exit ?]` SPELLING IS INCLUDED because `ended-line` can produce it: it is what
  is left for the moment between the process being gone and its exit code being
  readable, and a reader that did not recognise it would report a finished job as a
  running one."
  [path]
  (when-let [last (peek (record-lines path))]
    (when (re-matches #"\[(exit [^\]]*|stopped)\]" last) last)))

(defn- terminal?
  "Is JOB over? Asked of the record rather than of the process, and asked as ONE fact:

  `write-last-line!` claims the Writer at the same moment it writes the ending, so
  'the record is closed' and 'the last line is written' cannot disagree. A write that
  fails still closes the record -- what is over, is over."
  [job]
  (nil? (.get ^AtomicReference (:writer job))))

(defn- line-bytes [line]
  (alength (.getBytes ^String (str line "\n") StandardCharsets/UTF_8)))

(defn- tail-window
  "Where the largest SUFFIX of LINES that fits BUDGET bytes begins -- always at least
  one line, so a single enormous line is answered with rather than swallowed."
  [lines budget]
  (let [n (count lines)]
    (loop [i n, used 0]
      (if (zero? i)
        i
        (let [b (line-bytes (nth lines (dec i)))]
          (cond
            (= i n)               (recur (dec i) b)
            (> (+ used b) budget) i
            :else                 (recur (dec i) (+ used b))))))))

(defn- forward-window
  "Where a window that BEGINS at START stops when it runs into BUDGET bytes -- at
  least the line at START, for the same reason as `tail-window`."
  [lines start budget]
  (loop [i start, used 0]
    (if (>= i (count lines))
      i
      (let [b (line-bytes (nth lines i))]
        (if (or (= i start) (<= (+ used b) budget))
          (recur (inc i) (+ used b))
          i)))))

(defn job-output-default-timeout-ms
  "How long `job_output` waits for a job when the call says `wait` and does not say for
  how long.

  NOT A LIMIT ON THE JOB -- a job has none, and this changes nothing about it. It is
  the caller saying 'this is how long I am willing to sit here', and when it runs out
  the answer is the state of things as they are (`[running]`), which is an answer
  and not an error. The tool's description interpolates it, so there is one number."
  []
  120000)

(defn- mark-told!
  "Record that the ending of JOB-ID has been handed to the model.

  THE GUARD IS THE POINT: `update-in` puts back whatever the function returns, so a
  change written for a missing path would recreate the entry as nil -- the trap
  `with-job` documents, and the same one a job that leaves the registry springs here."
  [thread-id job-id]
  (let [p (path thread-id job-id)]
    (swap! registry (fn [reg]
                      (if-let [job (get-in reg p)]
                        (if (terminal? job) (assoc-in reg (conj p :told?) true) reg)
                        reg)))))

(defn output
  "What JOB-ID has said, and how it went, as
  `{:status .. :lines [..] :from .. :to .. :total ..}`:

  - `:status` -- the record's last line when the job is over (`[exit N]` / `[stopped]`),
    else `[running]`. ONE LINE, because that is what the record itself says.
  - `:lines`  -- the window of the command's own lines this answer carries. `:from`
    and `:to` are its 1-based line numbers IN THE RECORD, so they can be checked
    against `grep -n` on the same file, and `:total` is how many lines there are.

  WHERE THE WINDOW IS, when the caller did not say: THE TAIL. 'What has it said
  lately' is what a glance at a job asks, and a job that has printed ten thousand
  lines should not answer with its first hundred. `offset` asks for a stretch that
  begins somewhere (`read`'s own convention, 1-based, INTO the record), and `limit`
  caps how many lines come back. Both are bounded by `answer-budget-bytes` -- the
  same ceiling a `bash` answer has, from the same place.

  WAIT MEANS WAIT FOR IT TO BE OVER: `wait: true` blocks until the record is closed
  or `timeout` runs out, and a timeout is an ordinary answer (`[running]` plus
  whatever it has said so far), never an error. There is nothing to poll and no way
  to be notified otherwise -- a job is a command nobody is waiting for, and this is
  how a caller decides to wait anyway.

  IT WAITS FOR THE ENDING LINE, NOT FOR THE STREAM: a command that lets go of its
  stdout and lives on (`exec 1>&-`) has an ended stream and no ending line, and no
  ending line is the honest answer to 'is it over' -- so a `wait` on that one runs to
  the timeout and says `[running]` rather than inventing an end for it.

  THE JOB MUST BELONG TO THIS SESSION, and a job that is over still answers: its
  record is kept until this process goes."
  [thread-id job-id {:keys [offset limit wait timeout]}]
  (let [job (with-job thread-id job-id (fn [reg _] reg))]
    (when (and wait (not (terminal? job)))
      (deref (:ended job) (long (or timeout job-output-default-timeout-ms)) ::timeout))
    ;; HANDING BACK AN ENDING IS TELLING. A job whose ending the model has just been
    ;; shown -- by a read, or by a wait that ended while it waited -- has no notice
    ;; coming: an ending it has already read is not news. ASKED AFTER THE WAIT,
    ;; because the wait is often exactly what ended it.
    (when (terminal? job) (mark-told! thread-id job-id))
    (let [lines    (record-lines (:path job))
          over?    (terminal? job)
          status   (if over? (or (peek lines) "[exit ?]") "[running]")
          content  (if over? (vec (butlast lines)) lines)
          total    (count content)
          ;; WHERE THE WINDOW IS. `offset` starts one where the reader says (and a
          ;; number past the end of the record is an EMPTY window rather than an
          ;; error -- the status is still the truth, and `:from`/`:to`/`:total` say
          ;; what happened). With no offset it is the TAIL: the last lines that fit,
          ;; which is what a glance at a job asks for.
          [from to] (if offset
                      (let [start (min (dec offset) total)
                            end   (min total (+ start (or limit
                                                         (forward-window content start answer-budget-bytes))))]
                        [start (max start end)])
                      [(if limit
                         (max 0 (- total limit))
                         (tail-window content answer-budget-bytes))
                       total])]
      {:status status
       :lines  (subvec content from to)
       :from   (inc from)
       :to     to
       :total  total})))

(defn stop!
  "Stop JOB-ID -- it and everything it started. Answers
  `{:id .. :path .. :stopped? .. :ending ..}`: the record's location, whether THIS
  call is what stopped it, and the record's last line (which is how it went, whether
  or not this call had anything to do with it).

  THE RECORD SURVIVES THE STOP, and that is the point of answering with its path:
  'stop it, then read what it said' is the ordinary order, and the alternative -- a
  stop that took the output with it -- would make the model decide whether to read
  before knowing whether it needed to.

  `[stopped]` IS CLAIMED BEFORE ANYTHING IS KILLED, so the pump that wakes to a dead
  process cannot write `[exit N]` after we have said `[stopped]`. A command that had
  ALREADY ended writes nothing here: its exit code is the honest last line, and the
  pump is the one holding the tail it has not drained yet.

  THE CALL DOES NOT WAIT FOR THE PROCESS TO DIE. Killing a tree is `destroy`, a
  bounded wait and then `destroyForcibly` (see `infra.shell`), and that wait is the
  wrong thing to spend a tool call on: what the caller asked for is 'stop it', and
  the fact it needs back is the one `[stopped]` already states. So the tree is walked
  on a thread of its own and the answer comes back at once.

  THE JOB STAYS, AND ASKING AGAIN IS ALLOWED. A stopped (or finished) job keeps its
  entry, with its record closed -- which is what makes `job_output` able to answer
  for a job that is over, and a second `job_kill` able to answer the same thing
  again instead of refusing an id it handed out itself. The entry holds a path and a
  closed writer, and the process goes with this process.

  THROWS for a job id this session does not have."
  [thread-id job-id]
  ;; AND ITS ANSWER IS ALWAYS AN ENDING -- `[stopped]` or the one it had already
  ;; written -- so this call tells the model, and no notice follows it.
  (let [job (with-job thread-id job-id (fn [reg p] (assoc-in reg (conj p :told?) true)))
        running? (and (not (terminal? job)) ((:alive? (:handle job))))]
    (if running?
      (do (write-last-line! job "[stopped]")
          (future (try (close! job) (catch Throwable _ nil))))
      ;; NOT RUNNING: the exit line is written here only if the record is still open
      ;; (the pump may have beaten us to it), and the claim makes asking twice safe.
      (write-exit-line! job))
    {:id job-id :path (:path job) :stopped? running?
     :ending (ending-of (:path job))}))

;; ---------------------------------------------------- telling the model it is over
;;
;; A JOB NOBODY IS WAITING FOR STILL HAS TO BE HEARD FROM. The three verbs above are
;; all things the MODEL does, and the whole reason a job exists is that the model went
;; off to do something else -- so without a fourth channel the ending of a background
;; command sits in a file until somebody remembers to ask, and a model that is running
;; a synchronous loop does not remember to ask.
;;
;; SO THE ENDING IS PUT IN FRONT OF IT, as a message in the history the next model call
;; is sent. The seam for that already exists and is not this namespace's: the kernel
;; runs a session's pre-LLM step before EVERY call (`cap.project/before-llm`, which
;; composes `before-llm` below with the skills half), so a job that ends while the model
;; is busy is in front of it at the very next call, and one that ends after the turn is
;; in front of it at the next turn's first call.
;;
;; IT IS NOT A PUSH. Nothing wakes the model up, no run is started for a notice, and no
;; frame goes to the client: it rides whatever call comes next.
;;
;; AND IT IS SAID ONCE. That claim needs a place to live -- see `take-notices!`.

(defn- notice
  "The message that tells the model JOB is over, and ONLY THAT: which job, where its
  record is, and the line the record ends on.

  THREE THINGS AND NOTHING ELSE. A notice is a fact, not an answer: it does not carry a
  tail of what the command said (a record of five thousand lines is announced in the
  same few bytes as an empty one), it does not repeat the truncation sentence, and it
  says nothing about how to read the record -- the tool descriptions are where that
  belongs, and they are in front of the model on every request.

  THE TAG IS THE FRAME the model reads and the anchor a reader can grep for, exactly as
  `<skill name=…>` and `<instructions path=…>` are for their own blocks. The path rides
  on it as an attribute rather than as a sentence: it is metadata about the block, not
  something the command said.

  THE PATH GOES IN UNESCAPED, and that is a judgement rather than an oversight: it is
  this harness's own configuration home plus a `home/sanitize`d id, whose rule admits
  only `[A-Za-z0-9._-]`, so a quote can appear in it only if somebody named their home
  with one -- and a check for that would be a lot of code for that."
  [job]
  (let [ending (or (ending-of (:path job)) "[exit ?]")]
    {:role "user"
     :content (str "<job-ended id=\"" (:id job) "\" path=\"" (:path job) "\">"
                   ending "</job-ended>")}))

(defn take-notices!
  "The messages that tell THREAD-ID's model about jobs that have finished and whose
  ending it has NOT been handed yet -- MARKING THEM TOLD in the same step.

  THREE WAYS AN ENDING REACHES THE MODEL, and this is the third: `job_output` showed it
  a terminal record, `job_kill` showed it how the command went, or this. Whichever came
  first is the one that counts; a model that asked is not told again.

  THE MARK IS THE ONLY MEMORY THIS CAN HAVE. A skill body can be recognised in the
  history -- `<skill name=…>` stays in the conversation, because the CLIENT keeps
  resending it -- so that derivation is idempotent for free. A notice has no such anchor:
  the client never holds one (it is computed per call and sent to nobody), so 'has this
  been said' lives here, in the registry: process-local, per session, the same lifetime
  as the jobs themselves. A process that dies takes the unsaid endings with it, and the
  records are still there to be asked about.

  IN ONE STEP, the registry's own discipline: the entries this answers about and the
  entries it leaves behind come from one snapshot, so a job ending at this very moment
  is in this answer or in the next one -- never in both, never in neither.

  A MODEL THAT READ THE FILE ITSELF IS NOT MARKED: `tail` on a record leaves no trace
  here, so that job is announced once anyway. The notice is three facts and said once,
  and being told something twice costs less than never being told at all."
  [thread-id]
  (let [pending? (fn [job] (and (terminal? job) (not (:told? job))))
        [before _] (swap-vals! registry
                               (fn [reg]
                                 (if-let [jobs (get-in reg [thread-id :jobs])]
                                   (reduce-kv (fn [r id job]
                                                (if (pending? job)
                                                  (assoc-in r [thread-id :jobs id :told?] true)
                                                  r))
                                              reg jobs)
                                   reg)))]
    (->> (vals (get-in before [thread-id :jobs]))
         (filter pending?)
         (sort-by :id)
         (mapv notice))))

(defn before-llm
  "HISTORY with a notice appended for every job that has finished since the model was
  last told -- the jobs half of a session's pre-LLM step, in the same shape
  `harness.cap.project/before-llm` has (and composed by it).

  WHAT IT LOOKS LIKE WHEN THERE IS NOTHING TO SAY IS THE HISTORY ITSELF, unchanged: a
  session with no finished jobs pays a call to this function and nothing else."
  [history thread-id]
  (into (vec history) (take-notices! thread-id)))

;; -------------------------------------------------------- what a command sends away

(defn- closing-double-quote
  "The index of the `\"` that closes the double-quoted run starting at FROM, or -1.
  A backslash inside double quotes escapes the next character, and that is the one
  place this differs from the single-quoted run."
  [command from]
  (loop [i from]
    (cond
      (>= i (count command)) -1
      (= \\ (.charAt command i)) (if (< (inc i) (count command)) (recur (+ i 2)) -1)
      (= \" (.charAt command i)) i
      :else (recur (inc i)))))

(defn- command-tokens
  "COMMAND as a flat sequence of tokens, for `output-redirect` to walk.

  Each token is `{:text .. :op? .. :dup? ..}`: a WORD is a run of characters, quote
  removal already done; an OPERATOR (`>`, `>>`, `&>`) is `:op?`; `>&` is an operator
  that REDIRECTS NOTHING (`:dup?`, the `2>&1` spelling); and the separators (`;`,
  `&`, `|`, whitespace, newline) produce nothing at all.

  QUOTES AND BACKSLASHES ARE HONOURED, and that is the only parsing this does: a
  quoted run -- or a backslash-escaped character -- goes into the word verbatim, so
  `echo \"a > b\"` is two words with no operator in them and `\\>` is a word. An
  UNTERMINATED quote runs to the end of the command: the token is what the shell would
  have made of the string it was given."
  [command]
  (let [n (count command)]
    (loop [i 0, word (StringBuilder.), tokens []]
      (let [flush-word (fn [] (if (pos? (.length word))
                               (conj tokens {:text (.toString word) :op? false :dup? false})
                               tokens))]
        (if (>= i n)
          (flush-word)
          (let [c (.charAt command i)]
            (cond
              ;; a quoted run: everything up to the matching quote is word text
              (= c \') (let [j (.indexOf command "'" (inc i))]
                         (if (neg? j)
                           (recur n (doto word (.append (subs command (inc i)))) tokens)
                           (recur (inc j) (doto word (.append (subs command (inc i) j))) tokens)))
              (= c \") (let [j (closing-double-quote command (inc i))]
                         (if (neg? j)
                           (recur n (doto word (.append (subs command (inc i)))) tokens)
                           (recur (inc j) (doto word (.append (subs command (inc i) j))) tokens)))
              ;; a backslash escapes the next character into the word
              (= c \\) (if (< (inc i) n)
                         (recur (+ i 2) (doto word (.append (.charAt command (inc i)))) tokens)
                         (recur n (doto word (.append c)) tokens))
              ;; a redirect operator, doubled or not
              (= c \>) (let [doubled? (and (< (inc i) n) (= \> (.charAt command (inc i))))
                             dup?     (and (not doubled?) (< (inc i) n)
                                           (= \& (.charAt command (inc i))))]
                         (recur (+ i (if doubled? 2 1)) (StringBuilder.)
                                (conj (flush-word)
                                      {:text (cond doubled? ">>" dup? ">&" :else ">")
                                       :op? true :dup? dup?})))
              ;; `&>` redirects both streams; a bare `&` is a separator
              (= c \&) (if (and (< (inc i) n) (= \> (.charAt command (inc i))))
                         (let [dup? (and (< (+ i 2) n) (= \& (.charAt command (+ i 2))))]
                           (recur (+ i (if dup? 3 2)) (StringBuilder.)
                                  (conj (flush-word)
                                        {:text (if dup? "&>&" "&>")
                                         :op? true :dup? false})))
                         (recur (inc i) (StringBuilder.) (flush-word)))
              (or (Character/isWhitespace c) (= c \;) (= c \|) (= c \newline))
              (recur (inc i) (StringBuilder.) (flush-word))
              :else (recur (inc i) (doto word (.append c)) tokens))))))))

(defn output-redirect
  "The path COMMAND sends its own stdout or stderr to, or nil when it sends it
  nowhere in particular -- the model's own shape (`… > /tmp/clj-api.txt 2>&1`).

  BEST EFFORT, AND THAT IS THE DESIGN. This is a judgement about a shell command
  made without a shell: quotes, variables, command substitution and a nested shell
  all have ways of hiding a redirection, and none of them is worth an interpreter
  here. Missing one costs a note that was not printed; it never costs the command,
  because nothing is refused on this answer. Only the FIRST target is answered --
  the question is 'where did the output go', and the answer to the model is one
  path, not a parse of the command.

  A target of `&1`, `&2` or `&-` is a file-descriptor copy, not a place: the `2>&1`
  in the shape above sends nothing away by itself. `| tee FILE` is not a redirect
  either -- tee copies the stream and stdout still comes back to us, so that
  command's record is a good one."
  [command]
  (loop [tokens (command-tokens command), expect? false]
    (when-let [{:keys [text op? dup?]} (first tokens)]
      (cond
        (and op? (not dup?)) (recur (rest tokens) true)
        (and expect? (not op?)) (when-not (contains? #{"&1" "&2" "&-"} text) text)
        :else (recur (rest tokens) false)))))
