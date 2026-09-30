(ns scratch-git-fixture-cost
  "The fixture change of ticket 01, measured WITHIN ONE WINDOW so the machine's load
  cancels out: build the same repository the OLD way (eight `shell/run` calls) and the NEW
  way (one `&&` chain) alternately, and time each.

  Whole-suite wall clocks move 15-20% between runs on this machine, so a before/after taken
  at two different times is not evidence. This is: the only difference between the two
  branches here is how many `bash -lc` spawns the same eight git commands cost.

  isolate! first, per AGENTS.md."
  (:require [clojure.string :as str]
            [harness.test-runner :as runner]))

(defn- build-old!
  "Eight spawns, exactly the shape `init-repo!` had."
  [run dir]
  (.mkdirs (java.io.File. dir))
  (spit (java.io.File. dir "README.md") "hello\n" :encoding "UTF-8")
  (run "git init -q" dir)
  (run "git config user.email test@example.invalid" dir)
  (run "git config user.name 'harness test'" dir)
  (run "git config commit.gpgsign false" dir)
  (run "git add README.md" dir)
  (run "git commit -q -m first" dir)
  (run "git branch -M main" dir)
  (run "git branch side" dir))

(defn- build-new!
  "One spawn, the shape `init-repo!` has now."
  [run dir]
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
    (let [{:keys [exit]} (run (str/join " && " steps) dir)]
      (when-not (zero? (long (or exit 1)))
        (throw (ex-info "chained fixture failed" {:dir dir :exit exit}))))))

(defn- ms [f]
  (let [t0 (System/currentTimeMillis)]
    (f)
    (- (System/currentTimeMillis) t0)))

(defn -main [& _]
  (runner/isolate!)
  (let [shell-run (requiring-resolve 'harness.infra.shell/run)
        temp-dir  (requiring-resolve 'harness.test-support/temp-dir)
        run       (fn [c dir] (shell-run {:command c :dir dir}))]
    (println "--- one repository, old way (8 spawns) vs new way (1 spawn), alternating ---")
    (dotimes [i 3]
      (let [old-ms (ms #(build-old! run (temp-dir (str "fixture-old-" i))))
            new-ms (ms #(build-new! run (temp-dir (str "fixture-new-" i))))]
        (println (format "round %d:  old %5dms   new %5dms   saved %5dms"
                         (inc i) old-ms new-ms (- old-ms new-ms)))))
    (println "--- the two git cases build three repositories between them ---")
    (flush)))
