(ns scratch-mux-cost
  "Two numbers ticket 01's verdict needs, so the remaining seconds are judged against a
  measurement rather than a story:

    1. one REAL run over the mux -- subscribe, POST, frames, close -- the thing almost
       every http_test case does at least once;
    2. one `harness.infra.shell/run` spawn (bash -lc), which is what the two git cases
       spend their time on.

  The collaborators are `requiring-resolve`d INSIDE -main rather than aliased at the ns
  level, so isolate! runs before any of them loads -- per AGENTS.md.

  isolate! first."
  (:require [clojure.data.json :as json]
            [harness.test-runner :as runner]))

(defn- ms [f]
  (let [t0 (System/currentTimeMillis)]
    (f)
    (- (System/currentTimeMillis) t0)))

(defn -main [& _]
  (runner/isolate!)
  (let [shell-run (requiring-resolve 'harness.infra.shell/run)
        mux-run!  (requiring-resolve 'harness.test-support/mux-run!)
        start!    (requiring-resolve 'harness.test-support/start-session!)
        use!      (requiring-resolve 'harness.cap.providers/use-provider!)
        scripted  (requiring-resolve 'harness.fake/scripted)
        http-start! (requiring-resolve 'harness.edge.http/start!)]
    (println "--- one bash -lc spawn (`git --version`) ---")
    (dotimes [_ 6]
      (println (str (ms #(shell-run {:command "git --version"})) "ms")))
    (println "--- one real scripted run over the mux (no tools) ---")
    (use! "m1" (scripted [{:content "hi"}]))
    (start! "m1")
    (let [stop (http-start! {:port 0})
          port (:local-port (meta stop))
          body (json/write-str {:threadId "m1"
                                :append [{:id "u1" :role "user" :content "hi"}]
                                :tools []})]
      (try
        (dotimes [_ 6]
          (println (str (ms #(mux-run! port "m1" body nil)) "ms")))
        (finally (stop)))))
  (flush))
