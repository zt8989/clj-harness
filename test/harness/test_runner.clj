(ns harness.test-runner
  "Built-in clojure.test runner. No test-runner dependency: the cognitect one is a
  git coordinate, and git hosting is unreachable on this machine."
  (:require [clojure.test :as t]))

(def test-namespaces
  '[harness.event-test
    harness.llm-test
    harness.tools-test
    harness.loop-test
    harness.ag-ui-test
    harness.replay-test
    harness.http-test])

(defn -main [& _]
  (apply require test-namespaces)
  (let [{:keys [fail error]} (apply t/run-tests test-namespaces)]
    (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1))))
