(ns scratch-specs-cost
  "Ticket 02's first checkbox: PROVE where `tools/specs`'s 127ms-with-a-thread goes, by
  counting the work rather than guessing.

  `specs` filters `effective-tools` with `(served? thread-id name)`, and `served?` asks every
  installed narrowing policy per name. One of them (`cap.editing`) resolves the session's
  config on every ask -- `editing-mode` -> `blocks` -> `project/harness-config`, whose own
  docstring says it is re-read on every call. If that is the 127ms, `harness-config` is
  called once per tool name inside one `specs`.

  The collaborators are `requiring-resolve`d INSIDE -main so isolate! runs before any of them
  loads, per AGENTS.md."
  (:require [harness.test-runner :as runner]))

(defn- ms [f]
  (let [t0 (System/currentTimeMillis)]
    (f)
    (- (System/currentTimeMillis) t0)))

(defn- counting!
  "Install a counting wrapper on the var named SYM, and answer the fn that puts it back."
  [sym counter]
  (let [v    (requiring-resolve sym)
        real @v]
    (alter-var-root v (fn [_] (fn [& args] (swap! counter inc) (apply real args))))
    (fn [] (alter-var-root v (constantly real)))))

(defn -main [& _]
  (runner/isolate!)
  ;; THE TABLE HAS TO HAVE SOMETHING IN IT: the base registry is installed by the
  ;; composition root, and it is `cap.tools/install!` that also puts the EDITING narrowing
  ;; policy in -- the one whose `served?` resolves the session's config on every ask.
  ((requiring-resolve 'harness.cap.tools/install!))
  (let [specs        (requiring-resolve 'harness.kernel.tools/specs)
        effective    (requiring-resolve 'harness.kernel.tools/effective-tools)
        editing-mode (requiring-resolve 'harness.cap.editing/editing-mode)
        harness-conf (requiring-resolve 'harness.cap.project/harness-config)
        tid          "specs-cost-thread"
        names        (count (effective tid))
        calls        (atom 0)]
    (println "effective-tools:" names "names")
    ;; WARM FIRST, THEN MEASURE: the first call of anything here pays class loading, and one
    ;; cold number says more about the JVM than about `specs`.
    (dotimes [_ 5] (specs tid) (specs nil))
    (let [run (fn [label f]
                (let [took (vec (repeatedly 10 #(ms f)))]
                  (println (format "%-12s min %dms  mean %dms" label (apply min took)
                                   (quot (reduce + took) (count took))))))]
      (run "specs(nil)" #(specs nil))
      (run "specs(tid)" #(specs tid))
      (run "editing-mode" #(editing-mode tid))
      (run "harness-conf" #(harness-conf tid)))
    (let [restore (counting! 'harness.cap.project/harness-config calls)]
      (try (specs tid) (finally (restore))))
    (println "harness-config calls inside ONE specs(tid):" @calls)
    (reset! calls 0)
    (let [restore (counting! 'harness.cap.project/harness-config calls)]
      (try (specs nil) (finally (restore))))
    (println "harness-config calls inside ONE specs(nil):" @calls)
    (flush)))
