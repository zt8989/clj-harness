(ns scratch-image-e2e
  "THE TIGHT LOOP for the picture-in-the-record RUN_ERROR: one test var, over the real
  edge, in seconds rather than the 120 the whole http-test namespace costs.

  Phase 1 of /diagnosing-bugs. `a-picture-in-the-record-does-not-stop-the-next-run` is
  the user's symptom (第一次可以发送图片，第二次继续报错): a run answers RUN_FINISHED after
  the session has been put away and born again out of its own record.

  Run: clojure -M:dev -m scratch-image-e2e [thread-id-suffix]
  Exits 1 while the bug lives (that is the point), 0 once it is fixed."
  (:require [clojure.test :as t]
            [harness.edge.http-test :as http-test]
            [harness.test-runner :as runner]))

;; ABSOLUTELY FIRST: nothing below may resolve the real ~/.clj-harness.
(runner/isolate!)

(let [result (t/run-test-var #'http-test/a-picture-in-the-record-does-not-stop-the-next-run)]
  (println "run-test-var:" result)
  (flush)
  ;; 124 is the process's exit on a thrown fixture; a plain failure gives :fail.
  (System/exit (if (and (zero? (:fail result 0)) (zero? (:error result 0))) 0 1)))