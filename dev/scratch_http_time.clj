(ns scratch-http-time
  "WHERE `harness.edge.http-test`'s ~210s GOES -- one line per deftest.

  Ticket `.scratch/test-suite-performance/issues/01-...`: the namespace is the largest
  single point in the suite (210s against a 300s per-namespace limit), and only the WHOLE
  namespace's time is known. This measures each deftest while running the namespace the
  SAME way the runner does -- one `t/run-tests`, fixtures and all -- and reads the timing
  off the two report events clojure.test already emits around each var, so nothing about
  the cases changes.

  isolate! is called by -main BEFORE the test namespace is required, per AGENTS.md: this
  process must never resolve a store against the developer's real ~/.clj-harness."
  (:require [clojure.test :as t]
            [harness.test-runner :as runner]))

(defn- ns->var-times
  "Run NS-SYM the way the runner does and answer [wall-ms [[var-name ms] ...]], reading
  each var's time off :begin-test-var / :end-test-var. Failures and errors still print,
  through the default report, so a red case is visible next to its time."
  [ns-sym]
  (require ns-sym)
  (let [default-report t/report
        starts   (atom {})
        times    (atom [])
        started  (System/currentTimeMillis)]
    (binding [t/report
              (fn [m]
                (case (:type m)
                  :begin-test-var (swap! starts assoc (:var m) (System/currentTimeMillis))
                  :end-test-var   (let [v (:var m)]
                                    (when-let [s (get @starts v)]
                                      (swap! times conj [(:name (meta v))
                                                         (- (System/currentTimeMillis) s)])))
                  nil)
                (when (#{:fail :error} (:type m))
                  (default-report m)))]
      (t/run-tests ns-sym))
    [(- (System/currentTimeMillis) started) @times]))

(defn -main [& args]
  (runner/isolate!)
  (let [ns-sym (if (seq args) (symbol (first args)) 'harness.edge.http-test)
        [total times] (ns->var-times ns-sym)]
    (println)
    (println "===== per deftest, slowest first =====")
    (doseq [[n ms] (sort-by second > times)]
      (println (format "%8dms  %s" ms n)))
    (println (format "===== %d deftests, summed %dms, namespace wall %dms ====="
                     (count times) (reduce + (map second times)) total))
    (let [top (take 15 (sort-by second > times))]
      (println (format "top-15 account for %dms (%.1f%% of the namespace wall)"
                       (reduce + (map second top))
                       (* 100.0 (/ (double (reduce + (map second top))) total)))))
    (flush)))
