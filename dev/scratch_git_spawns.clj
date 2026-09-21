(ns scratch-git-spawns
  "Ticket 01's two claims, as a script that either passes or prints why it did not:
  that folding `state` onto `git status --porcelain=v2 --branch` takes the process
  count from four to two (and `switch!` from nine to five), and that the ANSWERS
  did not move while the commands did.

  THE COUNTER IS TEMPORARY BECAUSE `with-redefs` IS: it wraps
  `harness.infra.shell/run` for the length of one call and is gone after it, so the
  code under test is the code that ships. Every spawn counted here is a git,
  because a git read is the only thing these calls do.

  THE 'BEFORE' HALF IS READ OUT OF THE BASELINE REVISION, NOT PARAPHRASED. `git show
  19e9d9f:src/harness/cap/git.clj` is the `state` this ticket started from, loaded
  under a second namespace name so both versions can answer the same repository a
  moment apart. A number quoted from the spec was measured on the Windows box that
  wrote it (4 / 9 / 8); a number this machine did not measure is not its number.

  THE TWO DIVERGENCES IT PRINTS ARE THE POINT, not noise. Both are answers that
  were WRONG before and are right now, and both are recorded in the spec: a
  detached head used to hand back `(HEAD detached at <sha>)` as if it were a branch
  name, and a repository whose index git cannot read used to answer a branch and
  ':dirty 0' -- everything except 'we could not read it'.

  Run: clojure -M:dev -m scratch-git-spawns"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.cap.git :as git]
            [harness.infra.shell :as shell]
            [harness.test-support :as support]
            [harness.test-runner :as runner]))

;; The code that ships, held onto before anything wraps it.
(def ^:private real-run shell/run)
(def ^:private spawns (atom 0))

;; THE FIXTURE IS COUNTED AT ITS OWN SEAM, not through the wrapper: `sh!` runs the
;; real `shell/run` on purpose (a fixture built through the instrumented one would
;; count itself as part of the code under test), so the count has to be taken where
;; the fixture actually spawns.
(def ^:private fixture-calls (atom 0))

(defn- counting-run
  "One spawn, counted, and then the real thing -- same arguments, same answer."
  [& args]
  (swap! spawns inc)
  (apply real-run args))

(defn- counted
  "F's ANSWER and what it cost in processes."
  [f]
  (reset! spawns 0)
  (let [answer (with-redefs [shell/run counting-run] (f))]
    {:answer answer :spawns @spawns}))

;; ---------------------------------------------------------------------------
;; fixtures -- real repositories, built with the same seam the code under test
;; uses, so that a fixture which failed to set itself up cannot read as a pass

(defn- sh!
  [dir command]
  (swap! fixture-calls inc)
  (let [{:keys [exit err]} (real-run {:command command :dir dir :timeout-ms 20000})]
    (when-not (= 0 exit)
      (throw (ex-info (str "fixture failed: " command) {:exit exit :err err})))))

(defn- repo!
  "A repository at DIR, one commit on `main`, a second branch `side`."
  [dir]
  (.mkdirs (io/file dir))
  (doseq [c ["git init -q"
             "git config user.email test@example.invalid"
             "git config user.name 'harness test'"
             "git config commit.gpgsign false"]]
    (sh! dir c))
  (spit (io/file dir "README.md") "hello\n" :encoding "UTF-8")
  (doseq [c ["git add README.md"
             "git commit -q -m first"
             "git branch -m main"
             "git branch side"]]
    (sh! dir c))
  dir)

(def ^:private baseline-rev
  "The `main` this ticket was cut from -- the spec's own baseline line. NAMED AS A
  REVISION AND NOT AS `HEAD`: the moment this lands, HEAD is the folded version, and
  a script that asked HEAD for the 'before' half would compare the new code against
  itself and report 2 -> 2 with a straight face."
  "19e9d9f")

;; ---------------------------------------------------------------------------
;; the version this branch started from, loaded beside the current one

(defn- load-old!
  "`git.clj` as of `baseline-rev`, under the name `harness.cap.git-old`, so the two
  versions can be asked the same question without a stash or a checkout."
  []
  (let [{:keys [exit out err]} (real-run {:command (str "git show " baseline-rev
                                                        ":src/harness/cap/git.clj")
                                          :dir (System/getProperty "user.dir")
                                          :timeout-ms 20000})]
    (when-not (= 0 exit)
      (throw (ex-info "cannot read the previous git.clj out of the baseline revision" {:err err})))
    (load-string (str/replace out "(ns harness.cap.git\n" "(ns harness.cap.git-old\n"))))

(def ^:private results (atom {:pass 0 :fail 0}))

(defn- check [what ok & [detail]]
  (swap! results update (if ok :pass :fail) inc)
  (println (format "  %-4s %s%s" (if ok "PASS" "FAIL") what
                   (if (and (not ok) detail) (str "\n         <- " detail) ""))))

(defn- same-answer
  "Whether the two versions answered the same thing, with :branches compared as a
  SET: their order is git's own (--sort=-committerdate), and with two branches made
  in the same second the tie-break is not something either version promises."
  [scenario old new]
  (let [norm #(update % :branches (fnil sort []))]
    (check (str scenario " -- answers unchanged")
           (= (norm old) (norm new))
           (str "old " (pr-str old) "\n            new " (pr-str new)))))

(defn -main [& _]
  (runner/isolate!)
  (let [root (support/temp-dir "git-spawns")
        old-state  (do (load-old!) (resolve 'harness.cap.git-old/state))
        old-switch (resolve 'harness.cap.git-old/switch!)]

    (println "\n== ANSWERS: the same repositories, both versions ==")
    (let [clean (repo! (str root "/clean"))
          dirty (repo! (str root "/dirty"))
          plain (support/temp-dir "git-spawns-plain")]
      (spit (io/file dirty "scratch.txt") "x\n" :encoding "UTF-8")
      (same-answer "clean repository" (old-state clean) (git/state clean))
      (same-answer "one untracked file" (old-state dirty) (git/state dirty))
      (same-answer "not a repository at all" (old-state plain) (git/state plain))
      (same-answer "nil directory" (old-state nil) (git/state nil))
      (same-answer "empty string" (old-state "") (git/state ""))
      (same-answer "a directory that is not there"
                   (old-state (str root "/nope")) (git/state (str root "/nope"))))

    (let [unborn (support/temp-dir "git-spawns-unborn")]
      (sh! unborn "git init -q")
      (sh! unborn "git config user.email test@example.invalid")
      (sh! unborn "git config user.name 'harness test'")
      (spit (io/file unborn "first.txt") "x\n" :encoding "UTF-8")
      (same-answer "unborn head (no commit yet)" (old-state unborn) (git/state unborn)))

    ;; THE LISTING FIX. Old and new must DIFFER here, and the difference is the
    ;; sentence git prints for a detached head arriving as if it were a branch.
    (let [detached (repo! (str root "/detached"))]
      (sh! detached "git checkout -q --detach HEAD")
      (let [old (old-state detached)
            new (git/state detached)]
        (println (format "\n  %-28s old :branches %s" "detached head" (pr-str (:branches old))))
        (println (format "  %-28s new :branches %s" "" (pr-str (:branches new))))
        (check "detached -- the pseudo-name is gone, the branches stay"
               (and (nil? (:branch old)) (nil? (:branch new))
                    (some #(str/starts-with? % "(HEAD detached") (:branches old))
                    (= #{"main" "side"} (set (:branches new))))
               (str "old " (pr-str (:branches old))))))

    ;; THE RECORDED DIVERGENCE, asserted rather than hidden: an index git cannot
    ;; read fails `status` with the same 128 a non-repository answers with, so the
    ;; mapping's first row claims it. The old version answered a branch and
    ;; ':dirty 0' there -- a confident 'nothing in the way' about a file it could
    ;; not read.
    (let [corrupt (repo! (str root "/corrupt"))]
      (spit (io/file corrupt ".git/index") "garbage" :encoding "UTF-8")
      (let [old (old-state corrupt)
            new (git/state corrupt)]
        (println (format "\n  %-28s old %s" "unreadable index" (pr-str old)))
        (println (format "  %-28s new %s" "" (pr-str new)))
        (check "unreadable index -- 128 is 'not a repository', as the mapping says"
               (and (true? (:repo? old)) (= {:repo? false} new))
               (str "old " (pr-str old)))))

    (println "\n== PROCESSES: one `state`, one successful `switch!`, one fixture ==")
    (let [repo     (repo! (str root "/counts"))
          old-repo (repo! (str root "/counts-old"))
          state-n  (counted #(git/state repo))
          switch-n (counted #(git/switch! repo "side"))
          old-n    (counted #(old-state repo))
          old-s    (counted #(old-switch old-repo "side"))
          fix      (do (reset! fixture-calls 0)
                       (repo! (str root "/counts-fixture"))
                       @fixture-calls)]
      (println (format "  state    old %d  ->  new %d" (:spawns old-n) (:spawns state-n)))
      (println (format "  switch!  old %d  ->  new %d" (:spawns old-s) (:spawns switch-n)))
      (println (format "  one fixture (a real repo, built once): %d" fix))
      (check "state 4 -> 2" (= 4 (:spawns old-n)) (str "old " (:spawns old-n)))
      (check "state costs 2" (= 2 (:spawns state-n)) (str "new " (:spawns state-n)))
      (check "switch! 9 -> 5" (and (= 9 (:spawns old-s)) (= 5 (:spawns switch-n)))
             (str "old " (:spawns old-s) " new " (:spawns switch-n)))
      (check "one fixture still costs 8" (= 8 fix) (str "got " fix))
      ;; NOTHING TO ASK GIT IS NOT A GIT CALL. A nil directory, an empty string and
      ;; a path that is not there are answered before any spawn; a directory that
      ;; exists and is not a repository is one spawn, because 'is this a
      ;; repository?' is a question only git can answer.
      (let [nothing (mapv #(counted %) [#(git/state nil)
                                        #(git/state "")
                                        #(git/state (str root "/nope"))])]
        (check "nil / an empty string / a missing path spawn NOTHING"
               (every? zero? (map :spawns nothing))
               (pr-str (mapv :spawns nothing))))
      (check "a plain directory costs the one spawn that says so"
             (= 1 (:spawns (counted #(git/state (support/temp-dir "git-spawns-plain2"))))))
      (println (format "\n  new state   %s" (pr-str (:answer state-n))))
      (println (format "  new switch! %s\n"
                       (pr-str (or (:ok (:answer switch-n)) (:answer switch-n))))))

    (println (format "%d passed, %d failed\n" (:pass @results) (:fail @results)))
    (System/exit (if (zero? (:fail @results)) 0 1))))
