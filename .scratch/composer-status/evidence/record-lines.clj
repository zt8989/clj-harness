;; Ticket 01 evidence: a REAL run through the real HTTP edge leaves one pair of
;; model/start + model/end lines per model call, and a call that dies leaves its
;; model/end with an empty payload.
;;
;; Run from the worktree root:
;;   clojure -M:test -e '(load-file ".scratch/composer-status/evidence/record-lines.clj")'
;;
;; THE VENDOR IS SCRIPTED (harness.fake) AND THE HOME IS A TEMP DIR (isolate!):
;; neither the developer's ~/.clj-harness nor a real api-key is touched, which is
;; the rule in AGENTS.md. What this proves is the RECORD side end to end -- the
;; kernel's events, the edge's two jsonl mappings, the pairing, and the empty end
;; for a call that never answered. The vendor's own numbers are pinned by
;; `llm_test`'s assertions against the recorded response in
;; test/harness/fixtures/deepseek_sse.txt.

(require '[clojure.data.json :as json]
         '[clojure.java.io :as io]
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

(def root (runner/isolate!))

(defn- post-run
  "One real AG-UI run against the live server. PORT is the one the OS gave it."
  [port thread-id]
  (let [body (json/write-str {:threadId thread-id
                              :runId    (str (java.util.UUID/randomUUID))
                              :messages [{:id "u1" :role "user" :content "看看这个项目"}
                                         {:id "u2" :role "user" :content "再来一轮"}]
                              :tools    [] :context []})
        req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/")))
                 (.header "Content-Type" "application/json")
                 (.header "Accept" "text/event-stream")
                 (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                 (.build))]
    (.body (.send (HttpClient/newHttpClient) req
                  (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)))))

(defn- serve!
  "Pin PROVIDER to THREAD-ID, run one conversation, hand the log file's lines to F."
  [thread-id provider turns f]
  (providers/use-provider! thread-id (fake/scripted turns))
  (when (= :explodes (:protocol provider))
    ;; :explodes has no defmethod, so the call throws before anything streams --
    ;; the same "the call never answered" the kernel has to close a segment for.
    (providers/use-provider! thread-id {:protocol :explodes}))
  (let [stop (http/start! {:port 0})
        port (:local-port (meta stop))]
    (try
      (post-run port thread-id)
      (Thread/sleep 400)                       ;; the tail messages land after RUN_FINISHED
      (let [f' (home/log-file (str (io/file (home/projects-dir) http/unbound-workspace))
                              thread-id)]
        (f (str/split-lines (slurp f' :encoding "UTF-8"))))
      (finally
        (stop)
        (providers/use-provider! thread-id nil)))))

(defn- interesting
  "The lines worth showing: everything except the frames themselves, which are the
  conversation and are unchanged by this ticket. Prints `kind payload` per line."
  [lines]
  (->> lines
       (map #(json/read-str % :key-fn keyword))
       (remove #(= "event" (:kind %)))
       (map (fn [l] (format "%s  %s" (:kind l) (json/write-str (:payload l)))))
       (str/join "\n")))

(defn- kind-counts [lines]
  (->> lines
       (map #(json/read-str % :key-fn keyword))
       (map :kind)
       frequencies
       (sort-by key)))

(println "=== A: a scripted vendor that REPORTS usage (two calls, one tool round) ===")
(def a-lines
  (serve! "evidence-reports"
          {:protocol :fake}
          [{:content ""
            :reasoning "先读一下。"
            :tool-calls [{:id "c1" :name "no-such-tool" :arguments {}}]
            :usage {:prompt_tokens 769 :completion_tokens 324 :total_tokens 1093
                    :prompt_tokens_details {:cached_tokens 754}
                    :completion_tokens_details {:reasoning_tokens 296}}}
           {:content "done"
            :usage {:prompt_tokens 1093 :completion_tokens 12 :total_tokens 1105
                    :prompt_tokens_details {:cached_tokens 1093}}}]
          identity))
(println (interesting a-lines))
(println "line kinds:" (kind-counts a-lines))

(println)
(println "=== B: a call that never answered -- its segment still CLOSES ===")
(def b-lines
  (serve! "evidence-dies" {:protocol :explodes} [] identity))
(println (interesting b-lines))
(println "line kinds:" (kind-counts b-lines))

(println)
(println "root:" root)
