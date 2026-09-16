;; Ticket 02 evidence: the stats endpoint, answered over real HTTP to a real curl.
;;
;; Starts the real edge with a scripted vendor pinned, runs ONE conversation whose
;; usage numbers are easy to check by hand, then asks the route with a real curl
;; (`clojure.java.shell`) and prints curl's own bytes next to what each number is
;; made of.
;;
;;   clojure -M:test -e '(load-file ".scratch/composer-status/evidence/stats-endpoint.clj")'
;;
;; THE VENDOR IS SCRIPTED AND THE HOME IS A TEMP DIR (isolate!): no real api-key and
;; no real ~/.clj-harness, per AGENTS.md. See the README in this directory for what
;; that leaves unproven -- the vendor's own numbers are pinned by llm_test against
;; the recorded response in test/harness/fixtures/deepseek_sse.txt.

(require '[clojure.data.json :as json]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as shell]
         '[clojure.string :as str]
         '[harness.cap.providers :as providers]
         '[harness.edge.http :as http]
         '[harness.fake :as fake]
         '[harness.infra.home :as home]
         '[harness.test-runner :as runner])

(import '[java.net URI]
        '[java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
          HttpResponse$BodyHandlers]
        '[java.nio.charset StandardCharsets])

(def thread-id "stats-evidence")

(def usage-1 {:prompt_tokens 1000 :completion_tokens 40 :total_tokens 1040
              :prompt_tokens_details {:cached_tokens 900}})
(def usage-2 {:prompt_tokens 1200 :completion_tokens 8 :total_tokens 1208
              :prompt_tokens_details {:cached_tokens 1100}})

(def root (runner/isolate!))

(providers/use-provider! thread-id
                         (fake/scripted [{:content ""
                                          :tool-calls [{:id "c1" :name "no-such-tool" :arguments {}}]
                                          :usage usage-1}
                                         {:content "done" :usage usage-2}]))

(def stop (http/start! {:port 0}))
(def port (:local-port (meta stop)))

(defn- post-run! []
  (let [body (json/write-str {:threadId thread-id
                              :runId    "evidence-run"
                              :messages [{:id "u1" :role "user" :content "看看这个项目"}]
                              :tools    [] :context []})
        req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/")))
                 (.header "Content-Type" "application/json")
                 (.header "Accept" "text/event-stream")
                 (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                 (.build))]
    (.body (.send (HttpClient/newHttpClient) req
                  (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)))))

(post-run!)

(Thread/sleep 500)  ;; the tail messages land after RUN_FINISHED

(def log-file (home/log-file (str (io/file (home/projects-dir) http/unbound-workspace)) thread-id))

(println "PORT" port)
(println "THREAD" thread-id)
(println "SCRIPTED-USAGE-1" (json/write-str usage-1))
(println "SCRIPTED-USAGE-2" (json/write-str usage-2))
(println)
(println "THE TWO CALLS AS THE LOG RECORDED THEM (start -> end, in ms):")
(doseq [l (str/split-lines (slurp log-file :encoding "UTF-8"))
        :let [r (json/read-str l :key-fn keyword)]
        :when (#{"model/start" "model/end"} (:kind r))]
  (println " " (:ts r) (:kind r) (json/write-str (:payload r))))

(def url (str "http://127.0.0.1:" port "/api/threads/" thread-id "/stats"))

(println)
(println "$ curl -s" url)
(println (str/trim (:out (shell/sh "curl" "-s" url))))

(println)
(println "THE SAME ANSWER, KEY BY KEY (and what each one is made of):")
(let [body (json/read-str (:out (shell/sh "curl" "-s" url)) :key-fn keyword)]
  (println " turns                 " (:turns body) "      <- one user message")
  (println " steps                 " (:steps body) "      <- two model/start lines (a tool round is two calls)")
  (println " stepsWithUsage        " (:stepsWithUsage body) "      <- both calls reported")
  (println " usage.totalTokens     " (get-in body [:usage :totalTokens]) "   <- 1040 + 1208")
  (println " usage.promptTokens    " (get-in body [:usage :promptTokens]) "   <- 1000 + 1200")
  (println " usage.completionTokens" (get-in body [:usage :completionTokens]) "     <- 40 + 8")
  (println " usage.cachedTokens    " (get-in body [:usage :cachedTokens]) "   <- 900 + 1100")
  (println " cacheHitPercent       " (:cacheHitPercent body) "      <- 2000 / 2200 = 90.9% -> 91")
  (println " outputTokensPerSecond " (:outputTokensPerSecond body)
           "   <- 48 completion tokens over the two durations printed above")
  (println " incomplete            " (:incomplete body))
  (println)
  (println " (a 404 for a session with no log)")
  (println " "
           (str/trim (:out (shell/sh "curl" "-s" "-o" "/dev/null" "-w" "%{http_code}"
                                     (str "http://127.0.0.1:" port "/api/threads/never-ran/stats"))))
           (str/trim (:out (shell/sh "curl" "-s" (str "http://127.0.0.1:" port "/api/threads/never-ran/stats"))))))

(stop)
(providers/use-provider! thread-id nil)
