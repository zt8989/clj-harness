(ns scratch-git-read-cost
  "What ONE `/api/git` read costs the PRODUCT on this machine: `harness.cap.git/state` is
  two DIRECT `git` spawns (`status --porcelain=v2 --branch` then `branch --format`) -- argv,
  no shell, since `.scratch/git-read-cost`. The composer's branch strip asks for this
  whenever a session's directory is opened. Measured 2026-09-30: 1609-1744ms before (two
  `bash -lc`), **127-132ms** after.

  isolate! first, per AGENTS.md."
  (:require [clojure.string :as str]
            [harness.test-runner :as runner]))

(defn- ms [f]
  (let [t0 (System/currentTimeMillis)]
    (f)
    (- (System/currentTimeMillis) t0)))

(defn- build-repo! [run dir]
  (let [steps ["git init -q"
               "git config user.email test@example.invalid"
               "git config user.name 'harness test'"
               "git config commit.gpgsign false"
               "git add README.md"
               "git commit -q -m first"
               "git branch -M main"
               "git branch side"]]
    (.mkdirs (java.io.File. dir))
    (spit (java.io.File. dir "README.md") "hello\n" :encoding "UTF-8")
    (run (str/join " && " steps) dir)))

(defn -main [& _]
  (runner/isolate!)
  (let [shell-run (requiring-resolve 'harness.infra.shell/run)
        temp-dir  (requiring-resolve 'harness.test-support/temp-dir)
        git-state (requiring-resolve 'harness.cap.git/state)
        run       (fn [c dir] (shell-run {:command c :dir dir}))
        dir       (temp-dir "git-read")]
    (build-repo! run dir)
    (println "--- harness.cap.git/state (what one /api/git GET answers) ---")
    (dotimes [_ 6]
      (let [t0 (System/currentTimeMillis)
            s  (git-state dir)
            took (- (System/currentTimeMillis) t0)]
        (println (str took "ms  " (pr-str (select-keys s [:repo? :branch :dirty]))))))
    (flush)))
