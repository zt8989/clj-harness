(ns scratch-shell-win
  "EXPERIMENT, not a test: why do the two `infra.shell` timeout cases come back with NOTHING?

  `harness.infra.shell-test` has two cases that fail on this machine:

    * `what-a-command-printed-before-the-limit-comes-back` -- `(str/includes? (str out)
      \"said-before-hanging\")` on a `run` that hit its limit, and `out` is the EMPTY string;
    * `a-command-that-does-not-finish-is-stopped-together-with-what-it-started` -- the child
      never named itself: `(Long/parseLong (str/trim (slurp pid-file)))` on \"\".

  Iteration 1 (kept in the commit message rather than here) showed the shape of it: a plain
  `echo hello-there` comes back with its output at a 5s limit, while at a 2s limit NOTHING the
  command said -- and nothing it WROTE (the child's pid file was never created) -- exists.
  That is not a lost-output bug: it is the command never having run yet, because `bash -lc`
  pays for the login profile first (1.7s measured in this session's own shell, against 0.22s
  for `-c`), and the cases allow it 2s and 4s.

  So this measures the cost AS THE PRODUCT SEES IT -- the elapsed time of a `run` whose
  command prints a timestamp, and how long the test's own child takes to name itself (through
  `support/child-pid`, the helper the failing case waits with).

      clojure -M:dev -m scratch-shell-win

  It spawns and kills; it writes nothing outside its own temp directory and an isolated config
  root (`tr/isolate!`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness.infra.shell :as shell]
            [harness.test-runner :as tr]
            [harness.test-support :as support]))

(tr/isolate!)

(def ^:private dir
  (io/file (System/getProperty "java.io.tmpdir")
           (str "scratch-shell-win-" (System/currentTimeMillis))))

(defn- elapsed [f]
  (let [t0 (System/currentTimeMillis)
        v (f)]
    [(- (System/currentTimeMillis) t0) v]))

(defn -main [& _]
  (.mkdirs dir)
  (println "=== the shell this process resolved")
  (println (pr-str (shell/resolution)))

  (println "\n=== 1. how long a trivial command takes through `run`, and when it says so")
  (let [t0 (System/currentTimeMillis)
        [ms res] (elapsed #(shell/run {:command "date +%s%3N" :timeout-ms 30000}))
        printed (some-> (:out res) str/trim str/trim-newline parse-long)]
    (println "  elapsed=" ms "ms  exit=" (:exit res) " timeout=" (:timeout res))
    (println "  the command said=" (pr-str (:out res)))
    (println "  so the login profile cost" (when printed (- printed t0)) "ms before it ran"))

  (println "\n=== 2. the same with `-c` instead (the shell's own argv, for comparison)")
  (let [t0 (System/currentTimeMillis)
        [ms res] (elapsed #(shell/run {:command "date +%s%3N"
                                       :kind :shell
                                       :timeout-ms 30000}))
        printed (some-> (:out res) str/trim str/trim-newline parse-long)]
    (println "  elapsed=" ms "ms  exit=" (:exit res))
    (println "  so that cost" (when printed (- printed t0)) "ms"))

  (println "\n=== 3. how long the TEST's child takes to name itself (budget 15s)")
  (let [pid-file (io/file dir "child.pid")
        command (support/child-command pid-file)
        [ms res] (elapsed #(shell/run {:command command :timeout-ms 30000}))]
    (println "  command=" (pr-str command))
    (println "  the run returned after" ms "ms with exit=" (:exit res) " timeout=" (:timeout res))
    (println "  pid-file exists?" (.exists pid-file))
    (let [[wait-ms pid] (elapsed #(support/child-pid pid-file 15000))]
      (println "  the child named itself after" wait-ms "ms of waiting:"
               (pr-str pid) "(nil means it never did)")))

  (println "\n=== 4. and the case's own shape, at the limit the case uses (2000ms)")
  (let [[ms res] (elapsed #(shell/run {:command "echo said-before-hanging; sleep 30"
                                       :timeout-ms 2000}))]
    (println "  elapsed=" ms "ms  timeout=" (:timeout res) " out=" (pr-str (:out res))))

  (shutdown-agents))
