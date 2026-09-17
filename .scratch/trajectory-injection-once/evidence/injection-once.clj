;; Evidence: what a run's `message` record says about an injected skill body, and
;; what the trajectory route then draws out of it.
;;
;; Run from the worktree root:
;;   clojure -M:test -e '(load-file ".scratch/trajectory-injection-once/evidence/injection-once.clj")'
;;
;; THE VENDOR IS SCRIPTED AND THE HOME IS A TEMP DIR (runner/isolate!), so neither
;; the developer's ~/.clj-harness nor a real api-key is touched -- the rule in
;; AGENTS.md. What this proves is the RECORD and the READ side, end to end over a
;; REAL edge: which of a run's `message` lines are the submitted side (what the
;; first LLM call actually got) and which are the kernel's own tail, and the
;; trajectory the route folds out of exactly those lines.
;;
;; The session has TWO turns and the first one is a `/name`, because that is the
;; shape the ticket is about: the ask stays in the history the client restates, so
;; the body is re-derived and spliced in on the second run too -- between two
;; messages the client holds.

(require '[clojure.data.json :as json]
         '[clojure.java.io :as io]
         '[clojure.java.shell :as sh]
         '[clojure.string :as str]
         '[harness.cap.project :as project]
         '[harness.cap.providers :as providers]
         '[harness.edge.http :as http]
         '[harness.edge.replay :as replay]
         '[harness.fake :as fake]
         '[harness.infra.home :as home]
         '[harness.test-support :as support]
         '[harness.test-runner :as runner])

(import '[java.net URI]
        '[java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
          HttpResponse$BodyHandlers]
        '[java.nio.charset StandardCharsets])

(def root (runner/isolate!))

(def thread "evidence-injection-once")
(def proj   (support/temp-dir "evidence-injection"))

;; One project: ONE instruction file and ONE skill, so both kinds of injected
;; context are real files the server reads fresh (the skill roots are the project
;; layer; the OS-home layer is the temp one isolate! pinned).
(spit (io/file proj "AGENTS.md") "PROJECT RULE\n" :encoding "UTF-8")
(let [d (io/file proj ".agents/skills/alpha")]
  (.mkdirs d)
  (spit (io/file d "SKILL.md")
        "---\nname: alpha\ndescription: alpha does a thing\n---\n\nALPHA BODY\n"
        :encoding "UTF-8"))
(project/bind! thread proj)

(defn- post-run [port messages]
  (let [body (json/write-str {:threadId thread
                              :runId    (str (java.util.UUID/randomUUID))
                              :messages messages
                              :tools    [] :context []})
        req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/")))
                 (.header "Content-Type" "application/json")
                 (.header "Accept" "text/event-stream")
                 (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                 (.build))]
    (.body (.send (HttpClient/newHttpClient) req
                  (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)))))

(defn- read-records []
  (->> (slurp (replay/locate (home/projects-dir) thread) :encoding "UTF-8")
       str/split-lines
       (map #(json/read-str % :key-fn keyword))))

(defn- head [s]
  (let [s (str/replace (str s) #"\n" " ")]
    (subs s 0 (min 52 (count s)))))

(defn- label
  "One provider-shaped message, named by the thing the ticket is about: the frozen
  system message, an injected block, an injected BODY, or who said it."
  [m]
  (let [c (str (:content m))]
    (cond
      (= "system" (:role m))              "system"
      (str/starts-with? c "<instructions") (str "INJECTED block  " (head c))
      (str/starts-with? c "<skills>")      (str "INJECTED block  " (head c))
      (str/starts-with? c "<skill name=")  (str "INJECTED body   " (head c))
      :else (format "%-8s        %s" (:role m) (head c)))))

(defn- halves
  "The record split the way harness.edge.http writes it: an `input` opens a run, the
  `message` lines before its first `event` are the submitted side, the rest are the
  kernel's own tail."
  [recs]
  (loop [[r & more] recs
         current    nil
         acc        []]
    (cond
      (nil? r) (cond-> acc current (conj current))

      (= "input" (:kind r))
      (recur more {:submitted [] :returned [] :streaming false} (cond-> acc current (conj current)))

      (= "message" (:kind r))
      (recur more
             (when current
               (if (:streaming current)
                 (update current :returned conj (:payload r))
                 (update current :submitted conj (:payload r))))
             acc)

      (= "event" (:kind r))
      (recur more (when current (assoc current :streaming true)) acc)

      :else (recur more current acc))))

(defn- show-halves []
  (doseq [[i run] (map-indexed vector (halves (read-records)))
          [side msgs] [["submitted" (:submitted run)] ["returned " (:returned run)]]]
    (println (format "  run %d %s (%d lines)" (inc i) side (count msgs)))
    (doseq [m msgs] (println (str "    " (label m))))))

(defn- curl [port path] (:out (sh/sh "curl" "-s" (str "http://127.0.0.1:" port path))))

(defn- show-trajectory [port]
  (let [body (json/read-str (curl port (str "/api/threads/" thread "/trajectory"))
                            :key-fn keyword)]
    (doseq [turn (:turns body)]
      (println (format "  turn %d (%d items)" (:index turn) (count (:items turn))))
      (doseq [i (:items turn)]
        (println (format "    %-9s %-8s %s" (:kind i) (or (:source i) "") (head (:text i))))))))

(defn- serve! [f]
  (providers/use-provider! thread (fake/scripted [{:content "first"} {:content "second"}]))
  (let [stop (http/start! {:port 0})
        port (:local-port (meta stop))]
    (try
      (f port)
      (finally
        (stop)
        (providers/use-provider! thread nil)))))

(def ask {:id "u1" :role "user" :content "/alpha fix the bug"})

(serve!
 (fn [port]
   (post-run port [ask])
   (Thread/sleep 500)                      ;; the tail messages land after RUN_FINISHED
   ;; The second turn: the client restates its whole history and adds one message.
   (post-run port [ask {:id "a1" :role "assistant" :content "first"}
                  {:id "u2" :role "user" :content "and another thing"}])
   (Thread/sleep 500)

   (println "=== the record: the submitted side vs the kernel's own tail ===")
   (show-halves)

   (println)
   (println (str "=== GET /api/threads/" thread "/trajectory (asked from outside, over curl) ==="))
   (show-trajectory port)))

(println)
(println "temp config root:" root)
(println "temp project:" proj)
