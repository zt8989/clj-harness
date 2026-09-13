(ns harness.test-runner
  "Built-in clojure.test runner. No test-runner dependency: the cognitect one is a
  git coordinate, and git hosting is unreachable on this machine.

  It also isolates the filesystem: before anything else loads, the config root is
  pointed at a fresh temp directory. Without this, http_test writes its jsonl into
  the developer's REAL ~/.clj-harness -- which is how the \"tests pass but no log
  is on disk\" confusion started, since a sandboxed run gets that write redirected.

  alter-var-root, not binding: a test run starts a server whose logging happens on
  other threads, and a dynamic binding would not reach them. This process is a
  throwaway, so making the override global here is exactly the intent -- production
  code never touches it."
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [harness.home :as home]))

(def test-namespaces
  '[harness.event-test
    harness.llm-test
    harness.tools-test
    harness.session-tools-test
    harness.approval-test
    harness.loop-test
    harness.ag-ui-test
    harness.replay-test
    harness.http-test])

(def ^:private tmp-home
  (atom nil))

(defn- seed!
  "A minimal config.edn, so a run that resolves a provider from config -- rather
  than from the scripted override -- has something to resolve. This is a provider
  that never gets used: every integration test installs a scripted one."
  [dir]
  (spit (io/file dir "config.edn")
        "{:protocol :fake}\n"
        :encoding "UTF-8"))

(defn isolate!
  "Point the config root at a fresh temp directory for this process. Returns the
  directory. Idempotent: a second call reuses the first directory."
  []
  (or @tmp-home
      (let [dir (io/file (System/getProperty "java.io.tmpdir")
                         (str "clj-harness-test-" (System/currentTimeMillis)))]
        (.mkdirs dir)
        (seed! dir)
        (alter-var-root #'home/*root-override* (constantly (str dir)))
        (reset! tmp-home (str dir))
        (str dir))))

(defn- cleanup! []
  (when-let [dir @tmp-home]
    (try
      (doseq [f (reverse (file-seq (io/file dir)))]
        (io/delete-file f true))
      (catch Exception e
        ;; Losing the temp dir is not worth failing a green suite over, but say so.
        (binding [*out* *err*]
          (println "warning: could not remove test home" dir ":" (ex-message e)))))))

(defn -main [& _]
  (let [dir (isolate!)]
    (println "test config root:" dir)
    (apply require test-namespaces)
    (let [{:keys [fail error]} (apply t/run-tests test-namespaces)]
      (cleanup!)
      (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))))
