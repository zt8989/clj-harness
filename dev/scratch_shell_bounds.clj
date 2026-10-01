(ns scratch-shell-bounds
  "Ticket 03's fourth checkbox: what a spawn actually costs on THIS machine, so the two
  timeout budgets can be trimmed to something that is still comfortably above the floor
  rather than kept at a number nobody re-measured.

    `bash -lc true`        the login profile alone
    `bash -lc \"node ...\"`  the profile PLUS starting the child the cases actually start

  isolate! first, per AGENTS.md."
  (:require [harness.test-runner :as runner]))

(defn- ms [f]
  (let [t0 (System/currentTimeMillis)]
    (f)
    (- (System/currentTimeMillis) t0)))

(defn- row [label f]
  (let [took (vec (repeatedly 5 #(ms f)))]
    (println (format "%-28s min %5dms  mean %5dms  max %5dms"
                     label (apply min took) (quot (reduce + took) (count took)) (apply max took)))))

(defn -main [& _]
  (runner/isolate!)
  (let [shell-run (requiring-resolve 'harness.infra.shell/run)
        support   (requiring-resolve 'harness.test-support/child-command)
        temp-dir  (requiring-resolve 'harness.test-support/temp-dir)
        pid-file  (java.io.File. (temp-dir "bounds") "child.pid")]
    ;; One warm-up each: the first call of anything pays class loading, and a budget is
    ;; about what a case costs once the JVM is warm.
    (shell-run {:command "true"})
    (println "--- what one spawn costs (n=5 each) ---")
    (row "bash -lc true" #(shell-run {:command "true"}))
    (row "bash -lc 'node --version'" #(shell-run {:command "node --version"}))
    (row "bash -lc <child-command>" #(shell-run {:command (support pid-file) :timeout-ms 5000}))
    (flush)))
