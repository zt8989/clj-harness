(ns scratch-git-dir
  "Just the two git-route deftests of `harness.edge.http-test`, in an isolated home: the whole
  namespace is a long suite and this change touches two functions in it. Isolates first -- see
  docs/rules/testing.md; requiring the edge resolves a config root.

  Run: clojure -M:dev -m scratch-git-dir"
  (:require [clojure.test :as t]
            [harness.test-runner :as runner]
            [harness.edge.http-test]))

(defn -main [& _]
  (runner/isolate!)
  (let [counters (ref t/*initial-report-counters*)]
    (binding [t/*report-counters* counters]
      (t/test-vars [#'harness.edge.http-test/the-git-endpoint-reads-and-moves-the-sessions-working-tree
                    #'harness.edge.http-test/a-directory-this-home-lists-is-read-and-moved-without-a-session]))
    (let [{:keys [test pass fail error]} @counters]
      (println (str "test=" test " pass=" pass " fail=" fail " error=" error))
      (System/exit (if (and (pos? test) (zero? (+ fail error))) 0 1)))))
