(ns harness.edge.fork-http-test
  "POST /api/threads/<stem>/fork, over real HTTP, against a home of the test's own. A fork
  needs no model and no run: the record is planted, the route is called, and what lands on
  disk is read back with the same readers a session uses."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.project :as project]
            [harness.edge.http :as http]
            [harness.edge.replay :as replay]
            [harness.infra.home :as home]
            [harness.test-support :as support])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(defonce ^:private client (HttpClient/newHttpClient))

(defn- post [port path body]
  (let [req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                 (.header "Content-Type" "application/json")
                 (.POST (HttpRequest$BodyPublishers/ofString (json/write-str (or body {}))
                                                             StandardCharsets/UTF_8))
                 (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body   (try (json/read-str (.body resp) :key-fn keyword)
                  (catch Throwable _ (.body resp)))}))

(defn- log-file [thread-id]
  (home/log-file (io/file (home/projects-dir) http/unbound-workspace) thread-id))

(defn- spit-lines! [f lines]
  (io/make-parents f)
  (spit f (apply str (map #(str % "\n") lines)) :encoding "UTF-8"))

(defn- header-line [thread-id]
  (json/write-str {:ts 1 :runId nil :type "event"
                   :payload {:type "CUSTOM" :name "record/header"
                             :value {:format 2 :thread thread-id :created 1}}}))

(defn- msg
  "A client message row: `server/client` and an `:id` are what `replay/our-entry?` asks for
  when it decides which rows are the conversation."
  [run-id id content]
  (json/write-str {:ts 2 :runId run-id :id id :source "client" :type "message"
                   :payload {:role "user" :content content}}))

(defn- frame [run-id frame]
  (json/write-str {:ts 2 :runId run-id :type "event" :payload frame}))

(defn- fact [name value]
  (json/write-str {:ts 3 :runId nil :type "event"
                   :payload {:type "CUSTOM" :name name :value value}}))

(defn- model-view [f]
  (let [records (vec (replay/read-records f))]
    (mapv #(get-in % [:message :content])
          (replay/model-nodes (replay/entries records)
                              (replay/compaction-facts records)
                              (replay/prune-facts records)))))

(deftest a-fork-copies-the-log-up-to-the-last-step
  (support/with-temp-env [_root _home]
    (let [stop (http/start! {:port 0})
          port (:local-port (meta stop))]
      (try
        (project/register-session! "src-1")
        (spit-lines! (log-file "src-1")
                     [(header-line "src-1")
                      (msg "r1" "u1" "the work I did")
                      (fact "step/end" {})
                      (msg "r2" "u2" "after the step")])

        (let [{:keys [status body]} (post port "/api/threads/src-1/fork" {})
              new-id (:threadId body)]
          (testing "the route answers where the fork landed"
            (is (= 200 status))
            (is (string? new-id))
            (is (= "src-1" (:from body)))
            (is (= 2 (:seq body)) "the step's end it cut after"))

          (let [new-f (log-file new-id)
                text  (slurp new-f :encoding "UTF-8")]
            (testing "the new record holds the conversation up to that step, and no more"
              (is (.exists new-f))
              (is (str/includes? text "the work I did"))
              (is (some #(= "step/end" (replay/kind %)) (replay/read-records new-f))
                  "the named line is KEPT")
              (is (not (str/includes? text "after the step")))
              (is (str/includes? text "forked"))
              (is (= ["the work I did"] (model-view new-f))))

            (testing "it reads as a log every reader accepts"
              (is (= :settled (:state (replay/record-state (vec (replay/read-records new-f))))))
              (is (some #(= "session/forked" (replay/kind %))
                        (replay/read-records new-f)))
              (is (not (nil? (replay/header-of (first (replay/read-records new-f)))))))

            (testing "the new session is a session: a row, a name, a listing entry"
              (is (project/session-exists? new-id))
              (is (= "[fork]" (project/title new-id)))))

          (testing "the parent is untouched"
            (is (= 4 (count (replay/read-records (log-file "src-1")))))))
        (finally (stop))))))
(deftest a-cut-inside-a-run-closes-it-so-the-fork-reads
  (support/with-temp-env [_root _home]
    (let [stop (http/start! {:port 0})
          port (:local-port (meta stop))]
      (try
        (project/register-session! "src-2")
        (spit-lines! (log-file "src-2")
                     [(header-line "src-2")
                      (msg "r2" "u1" "start")
                      (frame "r2" {:type "RUN_STARTED" :threadId "src-2" :runId "r2"})
                      (frame "r2" {:type "TEXT_MESSAGE_CONTENT" :messageId "m1" :delta "half"})
                      (fact "step/end" {})])
        (let [{:keys [status body]} (post port "/api/threads/src-2/fork" {})
              new-id (:threadId body)
              records (vec (replay/read-records (log-file new-id)))]
          (is (= 200 status))
          (testing "the run the cut left open is closed, so the record is readable"
            (is (= :settled (:state (replay/record-state records))))
            (is (some #(= "session/closed-off" (replay/kind %)) records))
            (is (some #(= "RUN_ERROR" (get-in % [:payload :type])) records))))
        (finally (stop))))))

(deftest a-session-with-nothing-to-cut-at-is-refused-by-name
  (support/with-temp-env [_root _home]
    (let [stop (http/start! {:port 0})
          port (:local-port (meta stop))]
      (try
        (project/register-session! "src-3")
        (spit-lines! (log-file "src-3")
                     [(header-line "src-3")
                      (msg "r1" "u1" "no compaction here")])
        (let [{:keys [status body]} (post port "/api/threads/src-3/fork" {})]
          (is (= 400 status))
          (is (= "no-fork-point" (:reason body))))
        (finally (stop))))))

(deftest an-unknown-session-is-refused-by-name
  (support/with-temp-env [_root _home]
    (let [stop (http/start! {:port 0})
          port (:local-port (meta stop))]
      (try
        (let [{:keys [status]} (post port "/api/threads/nobody/fork" {})]
          (is (= 404 status)))
        (finally (stop))))))

(defn- fetch
  "A GET through the same client the POSTs use (`get` is taken)."
  [port path]
  (let [req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                 (.GET)
                 (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp)
     :body   (try (json/read-str (.body resp) :key-fn keyword)
                  (catch Throwable _ (.body resp)))}))

(deftest the-cut-can-be-any-step-and-the-points-are-listable
  ;; owner, 2026-09-28: the cut is a LINE, and a `step/end` is one -- so a conversation that
  ;; was never compacted can still be forked, and a caller can pick WHICH moment to go back to.
  (support/with-temp-env [_root _home]
    (let [stop (http/start! {:port 0})
          port (:local-port (meta stop))]
      (try
        (project/register-session! "src-steps")
        (spit-lines! (log-file "src-steps")
                     [(header-line "src-steps")
                      (msg "r1" "u1" "first")
                      (frame "r1" {:type "RUN_STARTED" :threadId "src-steps" :runId "r1"})
                      (fact "step/end" {})
                      (msg "r2" "u2" "second")
                      (frame "r2" {:type "RUN_STARTED" :threadId "src-steps" :runId "r2"})
                      (fact "step/end" {})])

        (testing "the points a caller picks from are the record's step ends"
          (let [{:keys [status body]} (fetch port "/api/threads/src-steps/fork-points")]
            (is (= 200 status))
            (is (= [3 6] (mapv :seq (:points body))))
            (is (= [nil nil] (mapv :compactionId (:points body)))
                "no compaction follows either step -- and a point is a step, never a compaction")
            (is (= [[] []] (mapv :tools (:points body))))))

        (testing "a fork may be cut at a named step, and keeps that line"
          (let [{:keys [status body]} (post port "/api/threads/src-steps/fork" {:stepSeq 3})
                new-id (:threadId body)
                kinds  (mapv replay/kind (replay/read-records (log-file new-id)))]
            (is (= 200 status))
            (is (= 3 (:seq body)))
            (is (= 1 (count (filter #{"step/end"} kinds))))
            (is (not (str/includes? (slurp (log-file new-id) :encoding "UTF-8") "second")))
            (is (= "[fork]" (project/title new-id)) "the name rides along")))

        (testing "with no name at all it falls back to the LAST step, not to a refusal"
          (let [{:keys [status body]} (post port "/api/threads/src-steps/fork" {})]
            (is (= 200 status))
            (is (= 6 (:seq body)))))

        (testing "a line that is not a step's end is refused, and says so"
          (let [{:keys [status body]} (post port "/api/threads/src-steps/fork" {:stepSeq 0})]
            (is (= 400 status))
            (is (= "no-fork-point" (:reason body)))))

        (testing "a name of the wrong kind is refused before anything is copied"
          (let [{:keys [status body]} (post port "/api/threads/src-steps/fork" {:stepSeq "three"})]
            (is (= 400 status))
            (is (= "bad-cut" (:reason body)))))

        (finally (stop))))))

(deftest a-crashed-record-is-closed-off-before-the-next-run
  ;; ticket 02, corrected against the whole suite: a run that never reached a terminal is
  ;; REPAIRED (close-off), not refused -- a process that died mid-flight must not cost the
  ;; conversation its next run. What is left after the repair reads.
  (support/with-temp-env [_root _home]
    (let [stop (http/start! {:port 0})
          port (:local-port (meta stop))]
      (try
        (project/register-session! "src-crash")
        (spit-lines! (log-file "src-crash")
                     [(header-line "src-crash")
                      (msg "r1" "u1" "a run that never ended")
                      (frame "r1" {:type "RUN_STARTED" :threadId "src-crash" :runId "r1"})])
        (let [{:keys [status body]} (post port "/api/agent"
                                         {:threadId "src-crash" :append [] :tools []})]
          (is (not= 409 status) "the record was closed off, not refused")
          (is (not= "torn-record" (:reason body))))
        (testing "and the record now says so"
          (let [kinds (map replay/kind (replay/read-records (log-file "src-crash")))]
            (is (some #{"session/closed-off"} kinds))))
        (finally (stop))))))

(deftest a-closed-record-is-not-refused-by-the-gate
  (support/with-temp-env [_root _home]
    (let [stop (http/start! {:port 0})
          port (:local-port (meta stop))]
      (try
        (project/register-session! "src-ok")
        (spit-lines! (log-file "src-ok")
                     [(header-line "src-ok")
                      (msg "r1" "u1" "a finished run")
                      (frame "r1" {:type "RUN_FINISHED" :threadId "src-ok" :runId "r1"})])
        ;; the gate must NOT fire here: a well-shaped record goes on to the run edge
        (let [{:keys [status body]} (post port "/api/agent"
                                         {:threadId "src-ok" :append [] :tools []})]
          (is (not= 409 status))
          (is (not= "torn-record" (:reason body))))
        (finally (stop))))))
