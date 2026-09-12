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
            [harness.replay :as replay]
            [harness.wire :as wire])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def ^:private ui-origin "http://localhost:5173")

(def ^:private reasoning "\u9700\u8981\u5148\u770b\u4e00\u773c deps.edn\u3002")

(def ^:private script
  [{:reasoning reasoning :content ""
    :tool-calls [{:id "c1" :name "read" :arguments {:path "deps.edn"}}
                 {:id "c2" :name "read" :arguments {:path "README.md"}}]}
   {:content "\u8fd9\u662f\u4e00\u4e2a Clojure \u9879\u76ee\u3002"}])

(defn- with-server [port f]
  (http/use-provider! (fake/scripted script))
  (let [stop (http/start! {:port port})]
    (try (f) (finally (stop) (http/use-provider! nil)))))

(defn- post-run
  "A real request for THREAD-ID. The run id is random so that two runs -- whether for
  different threads or for the same thread at different times -- never share frame ids.
  A repeated run id would make two runs' frames collide in a rebuilt conversation."
  [port thread-id]
  (let [body (json/write-str {:threadId thread-id :runId (str (java.util.UUID/randomUUID))
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

(def ^:private log-dir (str (System/getProperty "user.home") "/.lisp-harness/logs"))

(defn- header [resp name]
  (str (.orElse (.firstValue (.headers resp) name) "")))

(deftest serves-a-well-formed-run-over-real-http
  (with-server
   8097
   (fn []
     (let [resp   (post-run 8097 "it-1")
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
       (testing "both tool calls were answered on the wire, each keyed to its own call"
         (let [results (filter #(= "TOOL_CALL_RESULT" (:type %)) frames)]
           (is (= #{"c1" "c2"} (set (map :toolCallId results))))
           (is (= 2 (count results)))
           (is (= 2 (count (distinct (map :messageId results)))))))
       (testing "the read tool really ran on the server"
         (is (str/includes? (str (:content (first (filter #(and (= "TOOL_CALL_RESULT" (:type %))
                                                                (= "c1" (:toolCallId %)))
                                                          frames))))
                            ":paths")))))))

(defn- wait-for-recorded
  "Poll the thread's log, parsed, until PRED holds over the parsed lines or MS
  elapses. Needed because the returned side of the message record lands one beat
  after the terminal frame -- :run/done reaches the consumer only after the SSE
  has closed -- so a reader that races the consumer sees a file without it."
  [f pred ms]
  (let [read   (fn [] (mapv #(json/read-str % :key-fn keyword)
                            (str/split-lines (slurp f :encoding "UTF-8"))))
        finish (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [lines (read)]
        (if (or (pred lines) (> (System/currentTimeMillis) finish))
          lines
          (do (Thread/sleep 25) (recur)))))))

(deftest records-the-run-as-jsonl
  (with-server
   8098
   (fn []
     ;; Delete first, like the replay e2e does: the assertions below use
     ;; first/last over the parsed lines, so leftover runs from earlier test
     ;; executions must not bleed in.
     (io/delete-file (io/file log-dir "it-1.jsonl") true)
     (post-run 8098 "it-1")
     (let [f     (io/file log-dir "it-1.jsonl")
           lines (wait-for-recorded f
                                    #(some (fn [l] (and (= "message" (:kind l))
                                                        (= "assistant" (get-in l [:payload :role]))))
                                           %)
                                    2000)
           msgs  (mapv :payload (filter #(= "message" (:kind %)) lines))]
       (testing "both the inbound input and every emitted frame are on disk"
         (is (contains? (set (map :kind lines)) "input"))
         (is (contains? (set (map :kind lines)) "event")))
       (testing "the record holds the raw RunAgentInput, not a summary"
         (is (some #(= "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee"
                       (get-in % [:payload :messages 0 :content]))
                   lines)))
       (testing "the submitted system prompt is on disk VERBATIM"
         (let [sys (first (filter #(= "system" (:role %)) msgs))]
           (is (some? sys))
           ;; The posted context is empty, so what was submitted is prompt.md
           ;; and nothing else.
           (is (= (slurp "prompt.md" :encoding "UTF-8") (:content sys)))))
       (testing "the user's message is recorded in the provider's shape"
         (is (some #(and (= "user" (:role %))
                         (= "\u770b\u770b\u8fd9\u4e2a\u9879\u76ee" (:content %)))
                   msgs))
         ;; ag/inbound strips the AG-UI-only fields, :id among them -- the record
         ;; holds what the model will see, not what the client sent.
         (is (some #(and (= "user" (:role %)) (not (contains? % :id))) msgs)))
       (testing "every LLM return is on disk VERBATIM"
         (let [assistants (filter #(= "assistant" (:role %)) msgs)]
           (is (= reasoning (:reasoning_content (first assistants))))
           ;; The whole tool_calls payload, not just the ids: the record holds
           ;; the provider message unrebuilt.
           (is (= [{:id "c1" :type "function"
                    :function {:name "read" :arguments "{\"path\":\"deps.edn\"}"}}
                   {:id "c2" :type "function"
                    :function {:name "read" :arguments "{\"path\":\"README.md\"}"}}]
                  (:tool_calls (first assistants))))
           (is (= "\u8fd9\u662f\u4e00\u4e2a Clojure \u9879\u76ee\u3002"
                  (:content (last assistants))))))
       (testing "tool results are recorded as the tool messages they became"
         ;; History appends tool messages in the provider's call order, whatever
         ;; the completion order on the wire was.
         (let [tools (filter #(= "tool" (:role %)) msgs)]
           (is (= ["c1" "c2"] (mapv :tool_call_id tools)))
           ;; The content is what the read tool actually returned.
           (is (some #(and (= "c1" (:tool_call_id %))
                           (= (slurp "deps.edn" :encoding "UTF-8") (:content %)))
                     tools))))))))

(deftest the-log-the-server-writes-is-one-replay-can-read
  ;; Every other replay test builds its log with the emitter directly. This one goes
  ;; through the real edge -- real server, real request, real file -- because that is
  ;; the only way to catch a disagreement about the log's name or its line format, and
  ;; the two sides live in different namespaces on different sides of dev/src.
  (with-server
   8095
   (fn []
     (io/delete-file (io/file log-dir "replay-e2e.jsonl") true)
     (post-run 8095 "replay-e2e")
     (let [history (replay/history log-dir "replay-e2e")]
       (testing "the reader found the file the writer wrote, and rebuilt a conversation"
         (is (= "system" (:role (first history))))
         (is (some #(= "user" (:role %)) history)))
       (testing "the reasoning the server emitted is folded back for the model"
         (is (= reasoning
                (:reasoning_content
                 (first (filter #(and (= "assistant" (:role %)) (:tool_calls %)) history))))))
       (testing "and the tools the server actually ran are in the rebuilt conversation"
         ;; Match c1 by its own id: README.md also contains ":paths", so content
         ;; alone could be satisfied by the other call's result.
         (is (some #(and (= "c1" (:tool_call_id %))
                         (str/includes? (str (:content %)) ":paths"))
                   (filter #(= "tool" (:role %)) history))))))))

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
