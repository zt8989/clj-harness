(ns scratch-agentmemory
  "Walk the REAL ~/.clj-harness/mcp.edn and hooks.edn through this harness's own
  machinery, in an isolated home: the MCP client connects and lists tools, and the
  hook engine fires PostToolUse and reports the audit line.

  Run: clojure -M:dev -m scratch-agentmemory"
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [harness.cap.hooks :as cap-hooks]
            [harness.cap.mcp :as mcp]
            [harness.infra.home :as home]
            [harness.kernel.hooks :as hooks]
            [harness.kernel.hooks.dispatch :as dispatch]
            [harness.test-runner :as runner]))

(def real-home
  (or (System/getenv "CLJ_HARNESS_HOME")
      (str (System/getProperty "user.home") "/.clj-harness")))

(defn- copy-in! [root file]
  (let [src (io/file real-home file)]
    (when (.exists src)
      (io/copy src (io/file root file))
      (println (str "  copied " file " (" (.length src) " bytes)")))))

(defn- hook-section [thread-id]
  (println "\n=== hooks.edn, as the engine reads it ===")
  (cap-hooks/install!)
  (println "declarations-at :session-start  ->" (count (hooks/declarations-at thread-id :session-start)))
  (println "declarations-at :post-tool-use ->" (count (hooks/declarations-at thread-id :post-tool-use)))
  (println "declarations-at :stop          ->" (count (hooks/declarations-at thread-id :stop)))
  (println "\n=== firing :post-tool-use through the engine ===")
  (let [audit (atom nil)
        verdict (dispatch/fire {:point :post-tool-use
                                :thread-id thread-id
                                :fact {:tool_name "write"
                                       :tool_input {:path "src/scratch.clj"}}
                                :audit (fn [line] (reset! audit line))})]
    (println "verdict:" (pr-str verdict))
    (println "audit:") (pp/pprint @audit)))

(defn- mcp-section [thread-id]
  (println "\n=== mcp.edn, connected through the harness's MCP client ===")
  (let [tools (mcp/tools-for thread-id)]
    (println "bridged tools:" (count tools))
    (println "sample:" (pr-str (vec (take 5 (sort (keys tools)))))))
  (println "\nledger:")
  (pp/pprint (mapv #(let [row (select-keys % [:server :transport :status :error])]
                      (cond-> row
                        (:tools %) (assoc :tools (count (:tools %)))))
                   (mcp/status thread-id))))

(defn -main [& _]
  (let [root (runner/isolate!)]
    (println "isolated root:" root)
    (println "seeding from" real-home)
    (copy-in! root "mcp.edn")
    (copy-in! root "hooks.edn")
    (let [thread-id "scratch-e2e"]
      (hook-section thread-id)
      (mcp-section thread-id))
    (shutdown-agents)))
