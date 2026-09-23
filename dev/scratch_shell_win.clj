(ns scratch-shell-win
  "EXPERIMENT, not a test: how long does a spawned command take to START on this machine?

  WHY IT EXISTS. Two cases in `harness.infra.shell-test` and one in `harness.kernel.tools-test`
  were red here and nowhere else, and the shape of the failure looked like the product's:
  a command killed at its limit came back with NO output, and a child the test asked to name
  itself never had. The actual cause is slower and duller -- `bash -lc` pays for /etc/profile
  and the person's own profile BEFORE it runs the command at all, and those cases allowed the
  command 1.5s, 2s and 4s.

  AND IT IS NOT A CONSTANT. Run on 2026-09-23, this probe answered 2217ms in the window those
  cases were failing and 1174ms later the same evening on an idle machine -- so the budgets
  they carried sat INSIDE that range rather than under it, which is the difference between
  'the machine is slow' and 'the budget is a coin flip'. 8s and 12s sit outside it.

  SO THIS PRINTS THE THREE FACTS THOSE CASES ONLY ASSERT ON:

    * the shell this process resolved, and the argv a spawn builds from it;
    * how long a `run` of a trivial command takes, and the instant the command itself saw
      (the gap between them IS the profile);
    * what a command that says something and then hangs comes back with at the limits the
      cases used, and whether the test's own child got as far as writing its pid down.

      clojure -M:dev -m scratch-shell-win

  It spawns and kills; it writes nothing outside its own temp directory and an isolated
  config root (`tr/isolate!`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.infra.shell :as shell]
            [harness.test-runner :as tr]
            [harness.test-support :as support]))

(tr/isolate!)

(def ^:private dir
  (io/file (System/getProperty "java.io.tmpdir")
           (str "scratch-shell-win-" (System/currentTimeMillis))))

(defn- elapsed
  "Run F, answering [ms-it-took what-it-answered]."
  [f]
  (let [t0 (System/currentTimeMillis)
        v (f)]
    [(- (System/currentTimeMillis) t0) v]))

(defn- show [label [ms res]]
  (println label "->" ms "ms"
           (pr-str (select-keys res [:exit :timeout])))
  (println "   out=" (pr-str (:out res)))
  (println "   err=" (pr-str (:err res))))

(defn -main [& _]
  (.mkdirs dir)

  (println "=== the shell this process resolved, and the argv a spawn builds")
  (println (pr-str (shell/resolution)))
  (println "  shell:" (pr-str (shell/spawn-argv :shell "echo hi")))
  (println "  program:" (pr-str (shell/spawn-argv :program "node server.js")))

  (println "\n=== 1. a trivial command: how long `run` takes, and when the command saw it")
  (let [t0 (System/currentTimeMillis)
        [ms res] (elapsed #(shell/run {:command "date +%s%3N" :timeout-ms 30000}))
        printed (some-> (:out res) str/trim-newline parse-long)]
    (println "  elapsed=" ms "ms  exit=" (:exit res))
    (println "  the command's own clock says it ran" (when printed (- printed t0)) "ms in")
    (println "  (that gap is the login profile, and the command could not have said anything"
             "before it)"))

  (println "\n=== 2. a command that says something and then hangs, at the limits the cases used")
  (show "  2000ms (the budget `what-a-command-printed-before-the-limit-comes-back` had)"
        (elapsed #(shell/run {:command "echo said-before-hanging; sleep 30" :timeout-ms 2000})))
  (show "  8000ms (the budget it has now)"
        (elapsed #(shell/run {:command "echo said-before-hanging; sleep 30" :timeout-ms 8000})))

  (println "\n=== 3. the child the tree-kill case asks to name itself (limit 4000ms, then 12000ms)")
  (doseq [limit [4000 12000]]
    (let [pid-file (io/file dir (str "child-" limit ".pid"))
          command (support/child-command pid-file)
          [ms res] (elapsed #(shell/run {:command command :timeout-ms limit}))]
      (println "  limit" limit "-> returned after" ms "ms"
               (pr-str (select-keys res [:exit :timeout])))
      (println "    the child named itself?" (.exists pid-file)
               (pr-str (when (.exists pid-file)
                         (str/trim (slurp pid-file :encoding "UTF-8")))))))
  (println "\n  (when the profile runs over budget, both rows above say `false` / `\"\"` -- that")
  (println "   was the red: nothing had run yet, so there was nothing to lose.)")

  (shutdown-agents))
