(ns scratch-refresh-watch
  "EXPERIMENT, not a test: while a run is IN FLIGHT, does a page that only holds the
  WINDOW hear anything?

  The question comes from the owner's report (2026-09-25): a whole-page refresh that
  lands in a session the server is still answering draws the turn as far as it had got
  and then never grows again -- and the turn appears in one lump when the run ends.
  Two things would produce that, and they are told apart by this script:

    * the window feed pushes the half-written answer as the record grows  -> it does not;
    * the session's doorbell only rings when the run ENDS                  -> it does.

  It holds the run at the tool seam (a real `sleep`, so nothing is patched), asks
  `harness.edge.http/window-page` what a window would answer right now -- which DOES
  carry the growing answer, because a window reads the record while a run is in flight
  -- and counts the doorbell rings at the same moment. A window watcher would have got
  nothing, because nothing rang.

  Run: clojure -M:dev -m scratch-refresh-watch"
  (:require [clojure.data.json :as json]
            [harness.cap.providers :as providers]
            [harness.edge.http :as http]
            [harness.edge.sessions :as sessions]
            [harness.fake :as fake]
            [harness.test-runner :as test-runner]
            [harness.test-support :as support]))

(def ^:private tid "watch-probe")

(def ^:private answer
  ;; A LONG ANSWER, PACED, so the turn is still being written for several seconds -- which
  ;; is the only state the question is about (a real vendor takes time; the double does
  ;; not unless it is asked to, `:pace-ms`).
  (apply str (for [n (range 60)]
               (str "chunk-" n " "))))

(def ^:private script [{:reasoning "thinking about it" :content answer}])

(defn- fire-run!
  "POST a run over a socket of our own and do not read the answer -- the client that
  walked away, which is what a refresh is."
  [port]
  (let [body  (json/write-str {:threadId tid
                               :append   [{:id "u1" :role "user" :content "hi"}]
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

(defn- window-now
  "The window's own arithmetic, read where a watcher's push would read it."
  []
  (let [{:keys [entries baseSeq hasMore]} ((var-get #'http/window-page) tid nil)]
    {:count   (count entries)
     :baseSeq baseSeq
     :hasMore (boolean hasMore)
     :entries (mapv (fn [e] {:seq (:seq e)
                             :id  (get-in e [:message :id])
                             :len (count (str (get-in e [:message :content])))})
                    entries)}))

(defn- answer-so-far
  "What the half-written answer's own length is, as the window answers it."
  [ ] ; no arguments
  (let [entry (last (:entries (window-now)))]
    (:len entry)))

(defn- until? [f ms]
  (let [deadline (+ (System/currentTimeMillis) (long ms))]
    (loop []
      (cond (f) true
            (< (System/currentTimeMillis) deadline) (do (Thread/sleep 50) (recur))
            :else false))))

(defn- want! [ok what]
  (when-not ok (throw (ex-info (str "the probe's premise did not hold: " what) {})))
  (println "ok:" what))

(defn -main [& _]
  (test-runner/isolate!)
  (providers/use-provider! tid (fake/scripted script {:pace-ms 150}))
  (support/start-session! tid)
  (let [stop (http/start! {:port 0})
        port (:local-port (meta stop))
        rings (atom [])]
    (try
      (sessions/watch! tid (fn [_ ev] (swap! rings conj ev)))
      (Thread/sleep 200)
      (println "at rest: rings=" (count @rings))
      (let [sock (fire-run! port)]
        (try
          (want! (until? #(sessions/running? tid) 5000) "the run is registered")
          ;; EVERY SAMPLE BELOW IS WHAT A WATCHER WOULD BE HANDED: the window's own
          ;; arithmetic over the record, and the rings the doorbell rang as it grew.
          (want! (until? #(pos? (long (answer-so-far))) 15000)
                 "the half-written answer reached the window's answer")
          (let [shots (atom [])]
            (dotimes [_ 8]
              (swap! shots conj {:rings (count @rings) :len (answer-so-far)})
              (Thread/sleep 500))
            (println "while the run was being written:")
            (doseq [shot @shots] (println "   " (pr-str shot)))
            ;; THE GROWTH IS IN THE NUMBERS THEMSELVES (the answer's own length, sampled while
            ;; the run wrote it), and so is the doorbell (the ring count): a reader that saw
            ;; `:len` climbing and `:rings` climbing is a reader being told, repeatedly, that
            ;; there is more to show.
            (println "  -> the last sample's answer is " (answer-so-far)
                     "characters after" (count @rings) "rings")
          (.close sock)
          (finally
            (want! (until? #(not (sessions/running? tid)) 30000) "the run ended")
            (Thread/sleep 300)
            (println "after settle: rings=" (count @rings))
            (println "after settle: window says" (pr-str (window-now))))))
      (finally
        (stop)
        (providers/use-provider! tid nil)))))
