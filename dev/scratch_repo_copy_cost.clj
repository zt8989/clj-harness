(ns scratch-repo-copy-cost
  "Ticket 06, measured WITHIN ONE WINDOW so the machine's load cancels out:

    (a) one repository the OLD way (eight `shell/run` calls) vs the chained way -- the
        `cap/git_test` half;
    (b) THREE repositories (what `edge/http_test`'s two git cases need between them) built
        one at a time vs one build plus two copies -- the http_test half.

  Whole-suite wall clocks move 15-20% between runs on this machine (another agent runs
  suites here too), so a before/after taken at two different times is not evidence.

  isolate! first, per AGENTS.md."
  (:require [clojure.string :as str]
            [harness.test-runner :as runner]))

(def ^:private steps
  ["git init -q"
   "git config user.email test@example.invalid"
   "git config user.name 'harness test'"
   "git config commit.gpgsign false"
   "git add README.md"
   "git commit -q -m first"
   "git branch -m main"
   "git branch side"])

(defn- ms [f]
  (let [t0 (System/currentTimeMillis)]
    (f)
    (- (System/currentTimeMillis) t0)))

(defn- finish! [dir]
  (.mkdirs (java.io.File. dir))
  (spit (java.io.File. dir "README.md") "hello\n" :encoding "UTF-8"))

(defn- build-old!
  "Eight spawns, exactly the shape `cap/git_test/build-repo!` had."
  [run dir]
  (finish! dir)
  (doseq [s steps] (run s dir))
  dir)

(defn- build-new!
  "One spawn, the shape both fixtures have now."
  [run dir]
  (finish! dir)
  (run (str/join " && " steps) dir)
  dir)

(defn -main [& _]
  (runner/isolate!)
  (let [shell-run  (requiring-resolve 'harness.infra.shell/run)
        temp-dir   (requiring-resolve 'harness.test-support/temp-dir)
        copy-tree! (requiring-resolve 'harness.test-support/copy-tree!)
        run        (fn [c dir] (shell-run {:command c :dir dir}))]
    (println "--- (a) one repository: eight spawns vs one chain ---")
    (dotimes [i 3]
      (let [old-ms (ms #(build-old! run (temp-dir (str "old-" i))))
            new-ms (ms #(build-new! run (temp-dir (str "new-" i))))]
        (println (format "round %d: old %5dms  new %5dms  saved %5dms"
                         (inc i) old-ms new-ms (- old-ms new-ms)))))
    (println "--- (b) three repositories: build each vs build one + copy two ---")
    (dotimes [i 3]
      (let [three (ms #(dotimes [k 3] (build-new! run (temp-dir (str "three-" i "-" k)))))
            one   (ms #(let [tpl (build-new! run (temp-dir (str "tpl-" i)))]
                         (dotimes [k 2] (copy-tree! tpl (temp-dir (str "copy-" i "-" k))))))]
        (println (format "round %d: 3 builds %5dms  1 build + 2 copies %5dms  saved %5dms"
                         (inc i) three one (- three one)))))
    (flush)))
