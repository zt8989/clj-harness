(ns harness.shell
  "The one place that decides which shell this process spawns, and how to run
  something in it with a payload on stdin and a bounded wait.

  WHY IT IS ITS OWN NAMESPACE: two callers now spawn shell commands -- the `bash`
  tool, and the hook engine running a declared hook command -- and the trap below
  is a property of the MACHINE, not of either caller. Written down twice, one of
  them would eventually be fixed and the other not.

  THE TRAP. On Windows, `bash` on PATH is C:\\WINDOWS\\System32\\bash.exe -- the
  WSL launcher, which is a different filesystem entirely and, from a JVM, fails
  by producing nothing at all. So where a Git Bash install is found it is pinned
  by absolute path. Nowhere else does a second bash exist to be captured by, so
  the fallback IS the answer on macOS and Linux: the lookup is the same on every
  platform, which is why this is a search rather than an os.name test."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

(defonce binary
  (or (first (filter #(.exists (io/file %))
                     ["C:\\Program Files\\Git\\bin\\bash.exe"
                      "C:\\Program Files\\Git\\usr\\bin\\bash.exe"]))
      "bash"))

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
