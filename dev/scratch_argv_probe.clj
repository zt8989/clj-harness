(ns scratch-argv-probe
  "Where an argument can be lost on Windows: Java quotes each one for the OS, and whether
  it ARRIVES whole depends on who parses the Windows command line on the other side.

    - a NATIVE program (`node`, `java`) reads the line by the MSVCRT rules Java wrote it in;
    - an MSYS program (`bash`) RE-PARSES it, and can split on a space and eat a quote;
    - and `git.exe` is the one that matters here, because `switch!` hands it a BRANCH NAME,
      and git allows a `'` in a ref name (it forbids a space, so that half cannot arise).

  isolate! first, per AGENTS.md."
  (:require [clojure.string :as str]
            [harness.test-runner :as runner]))

(defn- show [label result]
  (println (format "%-46s %s" label (pr-str (select-keys result [:exit :out :err])))))

(defn -main [& _]
  (runner/isolate!)
  (let [resolution  (requiring-resolve 'harness.infra.shell/resolution)
        run-program (requiring-resolve 'harness.infra.shell/run-program)
        bash        (:command (resolution))
        quote-arg   "a'b"
        space-arg   "a b"]
    (println "== native: only Java's quoting is in play ==")
    (doseq [node ["C:\\Users\\zhouteng\\scoop\\persist\\nvm\\nodejs\\v24.9.0\\node.exe"]]
      (when (.exists (java.io.File. node))
        (show "node, arg with a quote"
              (run-program {:argv [node "-e" "console.log(JSON.stringify(process.argv.slice(1)))"
                                   quote-arg]})
              )
        (show "node, arg with a space"
              (run-program {:argv [node "-e" "console.log(JSON.stringify(process.argv.slice(1)))"
                                   space-arg]}))))
    (println)
    (println "== msys: bash re-parses the Windows line ==")
    (show "bash, arg with a quote"
          (run-program {:argv [bash "-c" "printf '<%s>\\n' \"$@\"" "x" quote-arg]}))
    (show "bash, arg with a space"
          (run-program {:argv [bash "-c" "printf '<%s>\\n' \"$@\"" "x" space-arg]}))
    (println)
    (println "== the one that matters: git echoing a ref name back ==")
    ;; `cat-file -t` names the object it could not find, so the argument comes back out.
    (show "git cat-file -t a'b"
          (run-program {:argv ["git" "cat-file" "-t" quote-arg]}))
    (show "git cat-file -t \"a b\""
          (run-program {:argv ["git" "cat-file" "-t" space-arg]}))
    (show "git --exec-path (which git is this)"
          (run-program {:argv ["git" "--exec-path"]}))
    (flush)))
