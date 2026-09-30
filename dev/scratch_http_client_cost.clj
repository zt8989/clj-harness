(ns scratch-http-client-cost
  "Ticket 01's follow-up question: the ~300ms a REAL run costs the CLIENT -- where does it
  go, and is any of it a nameable wait the suite is paying ~120 times?

  `harness.test-support/mux-run!` is replicated here with a clock on each phase (WebSocket
  handshake, subscribe POST, ack POST, waiting for the terminal frame, close), and then the
  same six runs are done again with ONE shared HttpClient instead of the three fresh ones
  the helper makes per call.

  isolate! first, per AGENTS.md."
  (:require [clojure.data.json :as json]
            [harness.test-runner :as runner])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers WebSocket WebSocket$Listener]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent CompletableFuture]))

(defn- timed-run!
  "One run, timed per phase. NEW-CLIENT? says whether each of the three uses makes its own
  HttpClient (what the helper does) or all three share one."
  [port thread-id body new-client?]
  (let [shared  (when-not new-client? (HttpClient/newHttpClient))
        client  (fn [] (or shared (HttpClient/newHttpClient)))
        token   (str (java.util.UUID/randomUUID))
        pending (atom "")
        frames  (atom 0)
        seen    (promise)
        params  (java.net.URLEncoder/encode (json/write-str [{:threadId thread-id}]) "UTF-8")
        t0      (System/currentTimeMillis)
        ws      (-> (client)
                    (.newWebSocketBuilder)
                    (.buildAsync (URI/create (str "ws://127.0.0.1:" port "/api/events.mux"
                                                  "?subscriber=" token "&sessions=" params))
                                 (reify WebSocket$Listener
                                   (onText [_ socket data last]
                                     (swap! pending str data)
                                     (when last
                                       (let [frame (try (json/read-str @pending :key-fn keyword)
                                                        (catch Throwable _ nil))]
                                         (reset! pending "")
                                         (when-some [t (:type frame)]
                                           (swap! frames inc)
                                           (when (contains? #{"RUN_FINISHED" "RUN_ERROR"} t)
                                             (deliver seen true)))))
                                     (.request socket 1)
                                     (CompletableFuture/completedFuture nil))))
                    (.join))
        t-ws    (System/currentTimeMillis)
        _       (.send (client)
                       (-> (HttpRequest/newBuilder
                            (URI/create (str "http://127.0.0.1:" port "/api/events.mux/subscribe")))
                           (.header "Content-Type" "application/json")
                           (.POST (HttpRequest$BodyPublishers/ofString
                                   (json/write-str {:subscriber token
                                                    :subscribe [{:threadId thread-id}]})
                                   StandardCharsets/UTF_8))
                           (.build))
                       (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))
        t-sub   (System/currentTimeMillis)
        ack     (.send (client)
                       (-> (HttpRequest/newBuilder
                            (URI/create (str "http://127.0.0.1:" port "/api/agent")))
                           (.header "Content-Type" "application/json")
                           (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                           (.build))
                       (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))
        t-ack   (System/currentTimeMillis)
        _       (when (= 200 (.statusCode ack)) (deref seen 30000 nil))
        t-wait  (System/currentTimeMillis)
        _       (.sendClose ws WebSocket/NORMAL_CLOSURE "done")
        t-end   (System/currentTimeMillis)]
    {:ws        (- t-ws t0)
     :subscribe (- t-sub t-ws)
     :ack       (- t-ack t-sub)
     :wait      (- t-wait t-ack)
     :close     (- t-end t-wait)
     :total     (- t-end t0)
     :frames    @frames}))

(defn -main [& _]
  (runner/isolate!)
  (let [start!      (requiring-resolve 'harness.test-support/start-session!)
        use!        (requiring-resolve 'harness.cap.providers/use-provider!)
        scripted    (requiring-resolve 'harness.fake/scripted)
        http-start! (requiring-resolve 'harness.edge.http/start!)]
    (use! "c1" (scripted [{:content "hi"}]))
    (start! "c1")
    (let [stop (http-start! {:port 0})
          port (:local-port (meta stop))
          body (json/write-str {:threadId "c1"
                                :append [{:id "u1" :role "user" :content "hi"}]
                                :tools []})]
      (try
        (println "--- three fresh HttpClients per call (what mux_run! does) ---")
        (println "  ws   sub  ack  wait close TOTAL  frames")
        (dotimes [_ 6]
          (let [{:keys [ws subscribe ack wait close total frames]}
                (timed-run! port "c1" body true)]
            (println (format "%5d %4d %4d %5d %5d %5d %6d"
                             ws subscribe ack wait close total frames))))
        (println "--- ONE shared HttpClient for all three ---")
        (println "  ws   sub  ack  wait close TOTAL  frames")
        (dotimes [_ 6]
          (let [{:keys [ws subscribe ack wait close total frames]}
                (timed-run! port "c1" body false)]
            (println (format "%5d %4d %4d %5d %5d %5d %6d"
                             ws subscribe ack wait close total frames))))
        (finally (stop)))))
  (flush))
