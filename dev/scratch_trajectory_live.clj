(ns scratch-trajectory-live
  "EXPERIMENT, not a test: while a run is IN FLIGHT, what does the trajectory say a tool
  call looks like?

  The question comes from the owner's report (2026-09-25): the running trajectory shows
  the user's message and the injected blocks, but NOT the tool calls the run is making.
  The claim is that the tool-call lines the kernel leaves mid-run (`tools/pre-execute`
  and friends) are not items -- only the run's RETURNED `message` rows are, and those
  land at `:run/done`, one beat after the terminal frame. So the trajectory grows a tool
  item only once the run is over.

  THE RUN IS HELD AT THE TOOL SEAM with a real `sleep` inside `bash`, so nothing is
  patched: the audit lines are written as they happen, the returned messages are not, and
  the trajectory is sampled while the answer is still owed.

  Run: clojure -M:dev -m scratch-trajectory-live"
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness.cap.providers :as providers]
            [harness.edge.http :as http]
            [harness.edge.replay :as replay]
            [harness.edge.sessions :as sessions]
            [harness.edge.trajectory :as trajectory]
            [harness.fake :as fake]
            [harness.infra.home :as home]
            [harness.test-runner :as test-runner]
            [harness.test-support :as support]))

(def ^:private tid "trajectory-live-probe")

(def ^:private script
  ;; ONE tool call the run must WAIT for: `bash` sleeps six seconds, which is the only
  ;; state the question is about (a tool that finishes at once leaves no window to look in).
  [{:tool-calls [{:id "c1" :name "bash" :arguments {"command" "sleep 6"}}]}
   {:content "done"}])

(defn- fire-run!
  "POST a run over a socket of our own and do not read the answer -- the client that walked
  away, which is what a refresh is."
  [port]
  (let [body  (json/write-str {:threadId tid
                               :append   [{:id "u1" :role "user" :content "go"}]
                               :tools    []})
        bytes (.getBytes body java.nio.charset.StandardCharsets/UTF_8)
        sock  (java.net.Socket. "127.0.0.1" (int port))
        out   (.getOutputStream sock)]
    (.write out (.getBytes (str "POST /api/agent HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                                "Content-Type: application/json\r\n"
                                "Content-Length: " (count bytes) "\r\n\r\n")
                           java.nio.charset.StandardCharsets/UTF_8))
    (.write out bytes)
    (.flush out)
    sock))

(defn- trajectory-now
  "The trajectory as a reader would be handed it right now: the held view, answered."
  []
  (let [state  (trajectory/view-value tid)
        answer (trajectory/trajectory-answer state)
        turns  (:turns answer)]
    {:incomplete (:incomplete answer)
     :turns (mapv (fn [turn]
                    {:index (:index turn)
                     :kinds (mapv :kind (:items turn))
                     :tools (mapv (fn [item]
                                    {:name      (:name item)
                                     :executed  (:executed item)
                                     :hasResult (pos? (count (str (:result item))))})
                                  (filter #(= "tool" (:kind %)) (:items turn)))})
                  turns)}))

(defn- until? [f ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (cond (f) true
            (< (System/currentTimeMillis) deadline) (do (Thread/sleep 50) (recur))
            :else false))))

(defn- want! [ok what]
  (when-not ok (throw (ex-info (str "the probe's premise did not hold: " what) {})))
  (println "ok:" what))

(defn- tool-lines
  "The `tools/*` lines the record of TID holds right now -- read off the FILE, so this is
  what has actually landed rather than what a fold believes."
  []
  (let [f (replay/locate (home/projects-dir) tid)]
    (when (.exists f)
      (try
        (->> (replay/read-records f)
             (map replay/kind)
             (filter #(str/starts-with? (str %) "tools/")))
        (catch Throwable _ [])))))

(defn -main [& _]
  (test-runner/isolate!)
  (providers/use-provider! tid (fake/scripted script))
  (support/start-session! tid)
  (let [stop (http/start! {:port 0})
        port (:local-port (meta stop))]
    (try
      (Thread/sleep 200)
      (let [sock (fire-run! port)]
        (try
          (want! (until? #(sessions/running? tid) 60000) "the run is registered")
          (println "  live-entry?" (some? (sessions/live-entry tid))
                   "trajectory-state?" (some? (sessions/fold-value tid :trajectory)))
          (want! (until? #(seq (tool-lines)) 15000)
                 "a `tools/*` line reached the record while the run is still in flight")
          (let [shots (atom [])]
            (dotimes [_ 6]
              (swap! shots conj (assoc (trajectory-now) :tool-lines (vec (tool-lines))))
              (Thread/sleep 700))
            (println "while the run was in flight:")
            (doseq [shot @shots] (println "   " (pr-str shot))))
          (.close sock)
          (finally
            (want! (until? #(not (sessions/running? tid)) 60000) "the run ended")
            (Thread/sleep 400)
            (println "after settle: " (pr-str (trajectory-now))))))
      (finally
        (stop)
        (providers/use-provider! tid nil)))))
