(ns scratch-which-env
  "Which of the three environment guards actually skips the Git Bash profile branch -- so a
  fix does not change LANG (and with it how a child quotes a non-ASCII path) for nothing.

  isolate! first, per AGENTS.md."
  (:require [harness.test-runner :as runner]))

(defn- pb-ms [argv env-add]
  (let [t0 (System/currentTimeMillis)
        pb (ProcessBuilder. ^java.util.List (vec argv))]
    (when env-add
      (let [e (.environment pb)]
        (doseq [[k v] env-add] (.put e k v))))
    (let [p (.start pb)] (.waitFor p) (- (System/currentTimeMillis) t0))))

(defn- row [label env-add]
  (let [took (mapv (fn [_] (pb-ms ["C:\\Program Files\\Git\\bin\\bash.exe" "-lc" "git --version"]
                                  env-add))
                   (range 5))]
    (println (format "%-42s %s" label (pr-str took)))))

(defn -main [& _]
  (runner/isolate!)
  (println "== bash -lc 'git --version', one env var at a time ==")
  (row "(none)" nil)
  (row "WINELOADERNOEXEC=1" {"WINELOADERNOEXEC" "1"})
  (row "TERM=dumb" {"TERM" "dumb"})
  (row "LANG=C" {"LANG" "C"})
  (row "WINELOADERNOEXEC + TERM" {"WINELOADERNOEXEC" "1" "TERM" "dumb"})
  (row "LANG=C + TERM=dumb" {"LANG" "C" "TERM" "dumb"})
  (row "all three" {"WINELOADERNOEXEC" "1" "LANG" "C" "TERM" "dumb"})
  (flush))
