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

  A JOB'S OUTPUT IS A FILE, AND THE FILE IS THE RECORD. `start!` makes
  `<root>/jobs/<thread-id>/<job-id>.log` in the configuration home, every line the
  command prints is appended to it and flushed, and the model reads it with the
  tools it already has: `bash` (`tail` / `grep` / `cat`), `read`, `grep`. So there
  is nothing to lose and therefore nothing to report as lost -- no bounded tail, no
  dropped-line count, no cursor recording how much of it this session has already
  seen. A record is a file, and reading a file is a problem this repo does not need
  to solve a second time.

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

(defonce ^:private exit-hook-installed
  (atom false))

(defn- path [thread-id job-id] [thread-id :jobs job-id])

(defn- record-path
  "Where THREAD-ID's JOB-ID keeps its record, under the configuration home.

  ONE PLACE BUILDS THIS STRING. The answers the tools give quote it back, and a path
  assembled twice is a path that will eventually be assembled differently -- the same
  reason the default timeout is interpolated into `bash`'s description rather than
  repeated there."
  [thread-id job-id]
  (str (io/file (home/root) "jobs" (home/sanitize thread-id) (str job-id ".log"))))

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

  Answers true when this call is the one that wrote the line, false when somebody
  Answers true when this call is the one that wrote the line, and nil when the record
  already had its last line -- neither is an error, and neither is reported as one."
  [job line]
  (locking (:writer job)
    (when-let [^Writer w (.getAndSet ^AtomicReference (:writer job) nil)]
      (try
        (.write w (str line "\n"))
        (.flush w)
        true
        (catch Exception _ nil)
        (finally (try (.close w) (catch Exception _ nil)))))))

(defn- close-record!
  "Let go of JOB's record file without writing anything more -- what `shutdown!`
  wants, since its records are about to be deleted."
  [job]
  (locking (:writer job)
    (when-let [^Writer w (.getAndSet ^AtomicReference (:writer job) nil)]
      (try (.close w) (catch Exception _ nil)))))

(defn- delete-record!
  "Take JOB's record off the disk.

  BEST EFFORT ON PURPOSE: a record that will not delete is a file in this process's
  own home, and failing a tool call -- or an exit -- over it helps nobody. It is
  logged all the same, because a cleanup that silently stopped working is a cleanup
  nobody knows is gone."
  [job]
  (let [f (io/file (:path job))]
    (when (and (.exists f) (not (.delete f)))
      (log/warn! :jobs/record-not-deleted {:path (:path job)}))))

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
        (when (get-in @registry (path thread-id job-id))
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
  one nobody could check on later, which is the whole point of starting it."
  []
  (let [jobs (vec (mapcat (fn [[_ v]] (vals (:jobs v))) @registry))]
    (reset! registry {})
    ;; The order is the order of the filesystem: stop the processes, let go of the
    ;; records, and only then delete them -- a file still held open is a file
    ;; Windows will not delete. The pumps cannot write after the reset anyway (the
    ;; last line is claimed, and a claim is once).
    (doseq [j jobs] (close! j))
    (doseq [j jobs] (close-record! j))
    (doseq [j jobs] (delete-record! j))
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
                 :writer (AtomicReference. (open-record! thread-id job-id))}]
        (ensure-exit-hook!)
        (swap! registry assoc-in (path thread-id job-id) job)
        (pumping! thread-id job-id job)
        {:id job-id :path p})
      (catch Throwable t
        (close! {:handle handle})
        (delete-record! {:path p})
        (throw t)))))

(defn stop!
  "Stop JOB-ID -- it and everything it started -- and forget it. Answers
  `{:id .. :path .. :stopped? ..}`: the record's location, and whether this call is
  what stopped it or the command had already ended by itself.

  THE RECORD SURVIVES THE STOP, and that is the point of answering with its path:
  'stop it, then read what it said' is the ordinary order, and the alternative -- a
  stop that took the output with it -- would make the model decide whether to read
  before knowing whether it needed to.

  `[stopped]` IS CLAIMED BEFORE THE KILL, so the pump that wakes to a dead process
  cannot write `[exit N]` after we have said `[stopped]`. A command that had ALREADY
  ended writes nothing here: its exit code is the honest last line, and the pump is
  the one holding the tail it has not drained yet.

  A JOB THAT HAS ALREADY ENDED IS FORGOTTEN TOO, and this is the only verb that
  leaves the registry, so `job_kill` means 'stop caring about this job' in both
  cases. Asking twice therefore gets the unknown-job refusal the second time --
  idempotent in the only way that matters, since the second caller finds it gone
  rather than finding it twice.

  THROWS for a job id this session does not have."
  [thread-id job-id]
  (let [job (with-job thread-id job-id
                      (fn [reg _] (update-in reg [thread-id :jobs] dissoc job-id)))
        running? ((:alive? (:handle job)))]
    (if running?
      (do (write-last-line! job "[stopped]")
          (close! job))
      (write-exit-line! job))
    {:id job-id :path (:path job) :stopped? running?}))

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
