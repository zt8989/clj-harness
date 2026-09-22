(ns scratch-shutdown
  "EXPERIMENT, not a test: what survives this process's exit?

  Starts three kinds of spawned process -- a BACKGROUND job (cap.jobs' territory,
  which has an exit hook of its own), an IN-FLIGHT foreground command (infra.shell/run
  on a future, the shape a `bash` tool call has while it is running) and a LONG-LIVED
  process (infra.shell/start, the shape an MCP server has) -- then blocks. Send the
  JVM a signal from outside and look at what is still alive.

  Run it so that SIGINT is NOT ignored at JVM startup (a plain `&` in a
  non-interactive shell would ignore it, and the JVM then refuses to install a
  handler at all, which measures nothing):

      bash -c 'trap - INT; exec clojure -M:dev -m scratch-shutdown' &
      kill -INT <jvm pid>

  or, for the SIGTERM path (how scripts/dev.mjs stops a backend):

      clojure -M:dev -m scratch-shutdown & kill -TERM <jvm pid>"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.cap.jobs :as jobs]
            [harness.infra.shell :as shell]
            [harness.test-runner :as tr]
            [harness.test-support :as support]))

(tr/isolate!)

(def dir
  (io/file (System/getProperty "java.io.tmpdir")
           (str "scratch-shutdown-" (System/currentTimeMillis))))

(defn -main [& _]
  (.mkdirs dir)
  (let [bg-pid   (io/file dir "bg.pid")
        fg-pid   (io/file dir "fg.pid")
        live-pid (io/file dir "live.pid")
        bg       (jobs/start! "scratch" {:command (support/child-command bg-pid)})
        _        (future (shell/run {:command (support/child-command fg-pid)
                                     :timeout-ms 600000}))
        live     (shell/start {:command (support/child-command live-pid) :shape :shell})]
    (Thread/sleep 4000)
    (println "SCRATCH dir=" (.getAbsolutePath dir))
    (println "SCRATCH job=" (:id bg))
    (println "SCRATCH bg-pid=" (str/trim (slurp bg-pid :encoding "UTF-8")))
    (println "SCRATCH fg-pid=" (str/trim (slurp fg-pid :encoding "UTF-8")))
    (println "SCRATCH live-pid=" (str/trim (slurp live-pid :encoding "UTF-8")))
    (println "SCRATCH alive-before=" ((:alive? live)))
    (println "SCRATCH pid=" (.pid (java.lang.ProcessHandle/current)))
    (flush)
    @(promise)))
