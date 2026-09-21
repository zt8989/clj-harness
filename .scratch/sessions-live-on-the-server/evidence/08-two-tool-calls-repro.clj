(ns two-tool-calls-repro
  "MINIMAL REPRO of a main-side defect found while resolving the brand-header merge
  (2026-09-21), NOT caused by the merge: a session whose turn held TWO tool calls in ONE
  provider message cannot be continued.

  WHAT IT DOES, with no server fixtures from any test namespace:
    1. isolate the home (harness.test-runner/isolate!, the protocol AGENTS.md requires)
    2. pin a scripted provider whose single turn returns ONE assistant message with
       tool-calls c1 (read deps.edn) and c2 (read README.md)
    3. run it once over the real edge (POST /api/agent) -- the run finishes fine
    4. print the conversation the session kept
    5. run a SECOND action on that same session and print its terminal frame

  WHAT SHOULD HAPPEN: the second run answers (RUN_FINISHED).
  WHAT DOES HAPPEN (main as of 47bdeb3, and the merged tree): RUN_ERROR, refused by
  harness.kernel.loop/drive! before any provider call --
    'this run's history leaves 1 tool call unanswered ... (c1)'
  because `harness.edge.ag-ui/outbound` emits one assistant TEXT_MESSAGE per tool call,
  so the session holds assistant{c1}, assistant{c2}, tool{c1}, tool{c2} -- and
  `harness.kernel.llm/unanswered-tool-calls` implements the vendor's rule as ADJACENCY
  (tool messages directly behind the assistant message that named them).

  RUN IT (from the repo root; the :test alias is the classpath, nothing else):
      clojure -M:test .scratch/sessions-live-on-the-server/evidence/08-two-tool-calls-repro.clj
  EXIT CODE: 0 when the second run is answered (fixed), 1 when it is refused (bug present).
  It writes only under the runner's per-process temp home; ~/.clj-harness is untouched."
  (:require [harness.test-runner :as runner]))

(runner/isolate!)
(require '[harness.edge.http :as http]
         '[harness.edge.sessions :as sessions]
         '[harness.cap.providers :as providers]
         '[harness.cap.project :as project]
         '[harness.fake :as fake]
         '[clojure.data.json :as json])
(import '[java.net URI]
        '[java.net.http HttpRequest HttpClient HttpResponse$BodyHandlers HttpRequest$BodyPublishers]
        '[java.nio.charset StandardCharsets])

(def two-calls-in-one-turn
  [{:content ""
    :tool-calls [{:id "c1" :name "read" :arguments {:path "deps.edn"}}
                 {:id "c2" :name "read" :arguments {:path "README.md"}}]}
   {:content "both files read"}])

(defn- post! [port body]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/api/agent")))
                (.header "Content-Type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString (json/write-str body) StandardCharsets/UTF_8))
                (.build))]
    (.body (.send (HttpClient/newHttpClient) req
                  (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)))))

(defn- frames [sse]
  (mapv #(json/read-str (second %) :key-fn keyword) (re-seq #"data: (\{.*\})" sse)))

(let [tid  "two-tool-calls"
      _    (providers/use-provider! tid (fake/scripted two-calls-in-one-turn))
      _    (project/register-session! tid)
      stop (http/start! {:port 0})
      port (:local-port (meta stop))]
  (try
    (let [r1 (frames (post! port {:threadId tid
                                  :append [{:id "u1" :role "user" :content "看看这个项目"}]
                                  :tools []}))]
      (println ":run1-terminal" (:type (last r1)))
      (println ":session-shape"
               (pr-str (mapv (fn [m] [(:role m) (:id m) (mapv :id (:toolCalls m))])
                             (sessions/messages tid))))
      (let [r2 (frames (post! port {:threadId tid
                                    :append [{:id "u2" :role "user" :content "再问一句"}]
                                    :tools []}))
            t2 (:type (last r2))]
        (println ":run2-terminal" t2)
        (println ":run2-message" (:message (last r2)))
        (println (if (= "RUN_FINISHED" t2)
                   ":VERDICT FIXED -- a second action on a session that used two tool calls is answered"
                   ":VERDICT BUG PRESENT -- a second action on a session that used two tool calls is refused"))
        (shutdown-agents)
        (System/exit (if (= "RUN_FINISHED" t2) 0 1))))
    (finally (stop))))
