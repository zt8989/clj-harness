(ns harness.shell
  "The one place that decides which shell this process spawns, and how to run
  something in it with a payload on stdin and a bounded wait.

  WHY IT IS ITS OWN NAMESPACE: three callers now spawn shell commands -- the
  `bash` tool, the hook engine running a declared hook command, and an MCP
  server -- and the trap below is a property of the MACHINE, not of any caller.
  Written down twice, one of them would eventually be fixed and the other not.

  TWO KINDS OF SPAWN, and the difference is who talks first. `run` is the one-shot
  kind (stdin in, answer out, bounded wait). `start` is the LONG-LIVED kind: a
  process that is still there after it has answered, and that a caller talks to
  line by line. A hook is the first kind; an MCP server is the second.

  THE TRAP. On Windows, `bash` on PATH is C:\\WINDOWS\\System32\\bash.exe -- the
  WSL launcher, which is a different filesystem entirely and, from a JVM, fails
  by producing nothing at all. So where a Git Bash install is found it is pinned
  by absolute path. Nowhere else does a second bash exist to be captured by, so
  the fallback IS the answer on macOS and Linux: the lookup is the same on every
  platform, which is why this is a search rather than an os.name test."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.io BufferedReader]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(defonce binary
  (or (first (filter #(.exists (io/file %))
                     ["C:\\Program Files\\Git\\bin\\bash.exe"
                      "C:\\Program Files\\Git\\usr\\bin\\bash.exe"]))
      "bash"))

(defn quote-arg
  "S as a single-quoted POSIX word, for a caller that is BUILDING a command line
  rather than passing one. The `'\\''` dance is the only way to put a quote
  inside a single-quoted word.

  Here rather than in the two namespaces that need it, for this namespace's own
  reason: a command line goes through `bash -lc`, so how a value is quoted is a
  property of how this process spawns things -- and a second copy of it is a
  second chance for one caller to be fixed and the other left open."
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn shell
  "Run COMMAND in this process's shell, with OPTIONS forwarded to
  clojure.java.shell/sh (:dir, :out-enc). Returns {:exit :out :err} unchanged.

  For callers that want the answers, not the process: no stdin, no timeout.

  Options whose value is nil are DROPPED rather than passed through: since
  Clojure 1.12 clojure.java.shell resolves :dir eagerly, so `{:dir nil}` is not
  'no directory' but a null file to resolve -- the unbound-session case, which is
  the common one."
  [command & {:as opts}]
  (apply shell/sh binary "-lc" command
         (mapcat identity (assoc (into {} (remove (comp nil? val)) opts)
                                 :out-enc "UTF-8"))))

(defn run
  "Run COMMAND the way A: once, with STDIN written to it, and no more than
  TIMEOUT-MS of waiting. Returns

    {:exit n :out \"..\" :err \"..\"}                 it finished
    {:exit nil :out \"..\" :err \"..\" :timeout true} it was killed at the limit

  The output it produced BEFORE the timeout is returned rather than discarded:
  a hook that hangs after printing its reason should still be readable.

  A command that cannot be spawned at all throws -- :exit only means anything for
  a process that started, and callers must not read 'we never ran it' as
  'it exited 0'."
  [{:keys [command stdin dir timeout-ms]}]
  (let [pb (doto (ProcessBuilder. [binary "-lc" command])
             (.redirectErrorStream false))
        _  (when dir (.directory pb (io/file dir)))
        p  (.start pb)
        w  (future
             (try
               (with-open [os (.getOutputStream p)]
                 (.write os (.getBytes (str stdin) StandardCharsets/UTF_8)))
               (catch Exception _ nil)))
        ;; Drain both pipes on their own threads: a process that fills a pipe
        ;; buffer blocks forever, so reading them only after waitFor is how a
        ;; well-behaved hook becomes a timeout.
        o  (future (slurp (.getInputStream p) :encoding "UTF-8"))
        e  (future (slurp (.getErrorStream p) :encoding "UTF-8"))
        done (.waitFor p (long (or timeout-ms 30000)) TimeUnit/MILLISECONDS)]
    (when-not done (.destroyForcibly p) (.waitFor p))
    (let [out (try (deref o 5000 "") (catch Exception _ ""))
          err (try (deref e 5000 "") (catch Exception _ ""))]
      @w
      (if done
        {:exit (.exitValue p) :out out :err err}
        {:exit nil :out out :err err :timeout true}))))

;; ----------------------------------------------------------- long-lived spawn

(def ^:private eof
  "What `:next-line` answers once the process has closed its stdout. A keyword
  rather than nil, because nil is what an empty line reads as and 'it ended' and
  'it printed nothing' are different facts."
  ::eof)

(defn eof? [v] (= eof v))

(defn timeout?
  "Did `:next-line` run out of patience? Asked here rather than compared against
  a literal, because `::timeout` is THIS namespace's keyword and a caller writing
  its own `::timeout` would be comparing two different keywords that print the
  same -- a bug that looks like 'the timeout case never fires'."
  [v]
  (= ::timeout v))

(defn start
  "Spawn COMMAND as a LONG-LIVED process -- the kind `run` cannot do: a process
  that has to still be there after it answers, and that is talked to line by line.

  Returns a handle, or throws when the process cannot be started at all (the
  shell itself missing, the process limit): a caller must be able to tell 'it
  never started' from 'it started and said nothing':

    {:process p
     :next-line (fn [timeout-ms] -> string | ::eof | ::timeout)
     :write-line! (fn [s] -> boolean)     ; one line out, flushed; false = it is gone
     :stderr (fn [] -> string)            ; what it has written to stderr SO FAR
     :alive? (fn [] -> boolean)
     :close! (fn [])}                     ; kill it and stop the pumps

  STDERR IS DRAINED AND ONLY DRAINED. A process that fills its stderr pipe blocks
  forever, so it is read on its own thread into a buffer -- and that buffer is
  DIAGNOSTICS, never protocol. A caller that parsed stderr as if it were an answer
  would be reading a log line as a decision, which is the same mistake the hook
  engine refuses to make.

  `:env` is ADDED to the inherited environment rather than replacing it: a server
  declared with one token still needs PATH to find its own runtime."
  [{:keys [command dir env]}]
  (let [pb (doto (ProcessBuilder. [binary "-lc" command])
             (.redirectErrorStream false))
        _  (when dir (.directory pb (io/file dir)))
        _  (when (seq env) (.putAll (.environment pb) (into {} env)))
        p  (.start pb)
        q  (LinkedBlockingQueue.)
        os (.getOutputStream p)
        err (StringBuilder.)
        ;; One thread per pipe, both started before anything reads: see the
        ;; stderr note above, and the same reason for stdout -- a caller that is
        ;; not reading right now must not be able to wedge the server.
        out-pump (future
                   (try
                     (with-open [r (io/reader (.getInputStream p) :encoding "UTF-8")]
                       (loop []
                         (when-let [line (.readLine ^BufferedReader r)]
                           (.put q line)
                           (recur))))
                     (catch Exception _ nil)
                     (finally (.put q eof))))
        err-pump (future
                   (try
                     (with-open [r (io/reader (.getErrorStream p) :encoding "UTF-8")]
                       (loop []
                         (when-let [line (.readLine ^BufferedReader r)]
                           (locking err (.append err (str line "\n")))
                           (recur))))
                     (catch Exception _ nil)))]
    {:process p
     :next-line (fn [timeout-ms]
                  (let [v (.poll q (long (or timeout-ms 1000)) TimeUnit/MILLISECONDS)]
                    (cond (nil? v) ::timeout
                          (= eof v) eof
                          :else v)))
     :write-line! (fn [s]
                    (try
                      (.write os (.getBytes (str s "\n") StandardCharsets/UTF_8))
                      (.flush os)
                      true
                      (catch Exception _ false)))
     :stderr (fn [] (locking err (.toString err)))
     :alive? (fn [] (.isAlive p))
     :close! (fn []
               (.destroy p)
               (when-not (.waitFor p 2 TimeUnit/SECONDS) (.destroyForcibly p))
               (try (.close os) (catch Exception _ nil))
               (future-cancel out-pump)
               (future-cancel err-pump)
               nil)}))
