(ns scratch-git-spawn-options
  "Ticket `.scratch/git-read-cost/issues/01`. The three directions the spec names, measured
  so the choice is made against numbers:

    (a) today               two `shell/run` calls -> two `bash -lc` profiles
    (b) env guards          the same `bash -lc`, with WINELOADERNOEXEC/LANG/TERM set for the
                            child (what infra/shell.clj's docstring calls the deliberate
                            lever: 770ms -> 333ms)
    (c) direct spawn        `git` itself, no shell at all
    (d) two-in-one          ONE `bash -lc` running both git commands

  Every row is timed with the same ProcessBuilder here, so the shape is comparable; the
  today row is also timed through the real `harness.cap.git/state` for the end-to-end number.

  isolate! first, per AGENTS.md."
  (:require [clojure.string :as str]
            [harness.test-runner :as runner]))

(defn- pb-ms
  "Run ARGV with a ProcessBuilder, optionally adding ENV-ADD, and answer [ms exit]."
  [argv env-add]
  (let [t0 (System/currentTimeMillis)
        pb (ProcessBuilder. ^java.util.List (vec argv))]
    (when env-add
      (let [e (.environment pb)]
        (doseq [[k v] env-add] (.put e k v))))
    (let [p (.start pb)
          exit (.waitFor p)]
      [(- (System/currentTimeMillis) t0) exit])))

(defn- row [label argv env-add]
  (let [took (mapv (fn [_] (first (pb-ms argv env-add))) (range 5))]
    (println (format "%-34s %s" label (str/join " " (map #(str % "ms") took))))))

(defn -main [& _]
  (runner/isolate!)
  (let [bash      "C:\\Program Files\\Git\\bin\\bash.exe"
        temp-dir  (requiring-resolve 'harness.test-support/temp-dir)
        shell-run (requiring-resolve 'harness.infra.shell/run)
        git-state (requiring-resolve 'harness.cap.git/state)
        dir       (temp-dir "spawn-options")
        git       "git"]
    ;; a small repository, built once (one spawn)
    (.mkdirs (java.io.File. dir))
    (spit (java.io.File. dir "README.md") "hello\n" :encoding "UTF-8")
    (shell-run {:command (str/join " && "
                                   ["git init -q"
                                    "git config user.email t@e.invalid"
                                    "git config user.name 't'"
                                    "git config commit.gpgsign false"
                                    "git add README.md"
                                    "git commit -q -m first"
                                    "git branch -M main"
                                    "git branch side"])
                :dir dir})
    (println "== one process each; the unit is a spawn ==")
    (row "bash -lc 'git --version'" [bash "-lc" "git --version"] nil)
    (row "  + WINELOADERNOEXEC/LANG/TERM"
         [bash "-lc" "git --version"]
         {"WINELOADERNOEXEC" "1" "LANG" "C" "TERM" "dumb"})
    (row "git --version (no shell)" [git "--version"] nil)
    (println)
    (println "== the read itself, in a small repository ==")
    (row "two spawns, one command each"
         [bash "-lc" (str "cd '" dir "' && git status --porcelain=v2 --branch")]
         {"WINELOADERNOEXEC" "1" "LANG" "C" "TERM" "dumb"})
    (let [took (mapv (fn [_]
                       (let [t0 (System/currentTimeMillis)]
                         (git-state dir)
                         (- (System/currentTimeMillis) t0)))
                     (range 5))]
      (println (format "%-34s %s" "harness.cap.git/state (today)"
                       (str/join " " (map #(str % "ms") took)))))
    (row "both commands, ONE shell (naive)"
         [bash "-lc" (str "cd '" dir "' && git status --porcelain=v2 --branch && "
                          "git branch --format=%(refname) --sort=-committerdate")]
         {"WINELOADERNOEXEC" "1" "LANG" "C" "TERM" "dumb"})
    (println)
    (println "== the git commands themselves, no shell, no cd ==")
    (row "git status (direct)" [(str git) "status" "--porcelain=v2" "--branch"] nil)
    (flush)))
