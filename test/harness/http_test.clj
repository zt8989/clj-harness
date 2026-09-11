(ns harness.http-test
  "Integration: a real server, a real HTTP request, a real SSE body.

  This is the layer that catches what unit tests structurally cannot see -- a run that
  is generated and logged perfectly but never reaches the client, and converter state
  that is rebuilt per event. Both of those actually happened during development."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.fake :as fake]
            [harness.http :as http]
            [harness.wire :as wire])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def ^:private ui-origin "http://localhost:5173")

(def ^:private reasoning "\u9700\u8981\u5148\u770b\u4e00\u773c deps.edn\u3002")

(def ^:private script
  [{:reasoning reasoning :content ""
    :tool-calls [{:id "c1" :name "read" :arguments {:path "deps.edn"}}]}
   {:content "\u8fd9\u662f\u4e00\u4e2a Clojure \u9879\u76ee\u3002"}])

(defn- with-server [port f]
  (http/use-provider! (fake/scripted script))
  (let [stop (http/start! {:port port})]
    (try (f) (finally (stop) (http/use-provider! nil)))))

(defn- post-run [port]
  (let [body (json/write-str {:threadId "it-1" :runId "r1"
                              :messages [{:id "u1" :role "user"
                                          :content "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee"}]
                              :tools [] :context []})
        req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/")))
                 (.header "Content-Type" "application/json")
                 (.header "Accept" "text/event-stream")
                 (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                 (.build))]
    (.send (HttpClient/newHttpClient) req
           (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))))

(defn- header [resp name]
  (str (.orElse (.firstValue (.headers resp) name) "")))

(deftest serves-a-well-formed-run-over-real-http
  (with-server
   8097
   (fn []
     (let [resp   (post-run 8097)
           body   (.body resp)
           frames (wire/frames-from-sse body)]
       (testing "the headers a browser client needs, given it calls us directly"
         (is (= 200 (.statusCode resp)))
         (is (= "text/event-stream" (header resp "Content-Type")))
         (is (= ui-origin (header resp "Access-Control-Allow-Origin"))))
       (testing "a complete and structurally valid run reaches the CLIENT"
         (is (seq frames))
         (is (= "RUN_STARTED" (:type (first frames))))
         (is (= "RUN_FINISHED" (:type (last frames))))
         (is (= 1 (count (filter #(= "RUN_FINISHED" (:type %)) frames))))
         (is (empty? (wire/violations frames))))
       (testing "reasoning crossed the wire and reassembles intact"
         (is (= reasoning
                (apply str (map :delta (filter #(= "REASONING_MESSAGE_CONTENT" (:type %)) frames))))))
       (testing "the read tool really ran on the server"
         (is (str/includes? (str (:content (first (filter #(= "TOOL_CALL_RESULT" (:type %)) frames))))
                            ":paths")))))))

(deftest records-the-run-as-jsonl
  (with-server
   8098
   (fn []
     (post-run 8098)
     (let [f     (io/file (str (System/getProperty "user.home") "/.lisp-harness/logs") "it-1.jsonl")
           lines (mapv #(json/read-str % :key-fn keyword)
                       (str/split-lines (slurp f :encoding "UTF-8")))]
       (testing "both the inbound input and every emitted frame are on disk"
         (is (contains? (set (map :kind lines)) "input"))
         (is (contains? (set (map :kind lines)) "event")))
       (testing "the record holds the raw RunAgentInput, not a summary"
         (is (some #(= "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee"
                       (get-in % [:payload :messages 0 :content]))
                   lines)))))))

(deftest answers-the-cors-preflight
  (with-server
   8099
   (fn []
     (let [req  (-> (HttpRequest/newBuilder (URI/create "http://127.0.0.1:8099/"))
                    (.method "OPTIONS" (HttpRequest$BodyPublishers/noBody))
                    (.build))
           resp (.send (HttpClient/newHttpClient) req (HttpResponse$BodyHandlers/discarding))]
       (is (= 204 (.statusCode resp)))
       (is (= ui-origin (header resp "Access-Control-Allow-Origin")))
       (is (str/includes? (header resp "Access-Control-Allow-Methods") "POST"))))))
