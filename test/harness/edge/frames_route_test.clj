(ns harness.edge.frames-route-test
  "`GET /api/threads/<stem>/frames` -- the REPLAY half a delegation panel reads before the
  page-wide downlink carries the child's live tail (ticket 04 of `.scratch/events-mux-and-host`).

  WHY THIS IS AN EDGE TEST. Every claim below is about a ROUTE and a RECORD: what a panel is
  handed for a child it did not run, in what order, and what the numbers on those frames are
  for. None of that exists below the edge.

  THE ONE THING A SUITE CANNOT SEE is the client joining the replay to the live tail -- that
  is `lib/follow.ts` and it is proven in a browser (`walkthrough-follow.mjs`). What is pinned
  here is the HALF THE SERVER OWES: the frames, their order, and the `:seq` the join counts by."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.project :as project]
            [harness.cap.providers :as providers]
            [harness.edge.http :as http]
            [harness.edge.replay :as replay]
            [harness.fake :as fake]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.test-support :as support])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def ^:dynamic *port* nil)

(defn- wipe! [] (support/wipe-hooks!))

(use-fixtures :each (fn [f] (wipe!) (f) (wipe!)))

;; ----------------------------------------------------------------- the harness

(defn- with-server
  "A live server on an OS-chosen port, with a scripted provider pinned to THREAD.

  THE SESSION IS BORN FIRST: a run of an id the store has never heard of is refused
  (`harness.edge.http/refuse-unknown-session!`), and registering is what a page's
  POST /api/sessions does."
  [thread turns f]
  (providers/use-provider! thread (fake/scripted turns))
  (support/start-session! thread)
  (let [stop (http/start! {:port 0})
        port (:local-port (meta stop))]
    (try (binding [*port* port] (f))
         (finally (stop) (providers/use-provider! thread nil)))))

(defn- post-run
  "One action on the run edge: the entries it adds, and nothing per-run -- the server
  mints the run id, and `:messages` is refused by name."
  [thread-id]
  (let [body (json/write-str {:threadId thread-id
                              :append   [{:id "u1" :role "user" :content "go"}]
                              :tools [] :context []})
        req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* "/api/agent")))
                 (.header "Content-Type" "application/json")
                 (.header "Accept" "text/event-stream")
                 (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                 (.build))]
    (.send (HttpClient/newHttpClient) req
           (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))))

(defn- api-get [path]
  (let [req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* path)))
                 (.header "Accept" "application/json")
                 (.GET)
                 (.build))
        resp (.send (HttpClient/newHttpClient) req
                    (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
    {:status (.statusCode resp)
     :type   (some-> (.firstValue (.headers resp) "content-type") (.orElse nil))
     :body   (json/read-str (.body resp) :key-fn keyword)}))

(defn- workspace-of
  "The workspace THREAD-ID's records are in, asked the way the SERVER asks it."
  [thread-id]
  (io/file (home/projects-dir)
           (if-let [identity (project/identity-for thread-id)]
             (home/sanitize identity)
             http/unbound-workspace)))

(defn- log-file [thread-id] (home/log-file (workspace-of thread-id) thread-id))

(defn- read-lines [^java.io.File f]
  (->> (str/split-lines (slurp f :encoding "UTF-8"))
       (remove str/blank?)
       (keep #(try (json/read-str % :key-fn keyword) (catch Throwable _ nil)))
       (vec)))

(defn- log-lines [thread-id]
  (let [f (log-file thread-id)]
    (when (.exists f) (read-lines f))))

(defn- wait-for
  "The log, once PRED holds of it or MS has passed."
  [thread-id pred ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (let [ls (log-lines thread-id)]
        (if (or (pred ls) (> (System/currentTimeMillis) deadline))
          ls
          (do (Thread/sleep 50) (recur)))))))

(defn- child-of
  "The child session the STORE says THREAD-ID delegated to."
  [thread-id]
  (some-> (db/select "SELECT id FROM sessions WHERE parent_id = ?" thread-id) first :id))

(defn- child-log
  "The child's record once its run is on disk: the terminal frame is the row that says the
  run is over, and the writer is a consumer off the run's own thread."
  [thread-id]
  (wait-for thread-id
            (fn [ls] (some #(and (= "event" (replay/kind %))
                                 (contains? #{"RUN_FINISHED" "RUN_ERROR"}
                                            (:type (replay/payload %))))
                           ls))
            4000))

(defn- frames-of [lines] (->> lines (filter replay/frame?) (mapv replay/payload)))

;; ---------------------------------------------------------------- the fixtures

(def ^:private task "where does the child's replay come from")

(def ^:private script
  "One delegation, then the closing round. The child reads a file, so its record holds more
  than the two frames that would bracket an empty answer."
  [{:content "" :tool-calls [{:id "d1" :name "agent"
                              :arguments {:name "explore" :prompt task}}]}
   {:content "" :tool-calls [{:id "c1" :name "read" :arguments {:path "deps.edn"}}]}
   {:content "the replay is the record's own frames"}
   {:content "ok"}])

;; ------------------------------------------------------------------ the reading

(deftest the-frames-route-answers-the-replay-as-json-and-keeps-the-number
  ;; TICKET 04: the replay half a panel reads BEFORE the downlink carries the live tail. The
  ;; same frames the old follow channel replayed, as JSON -- and unlike that wire each frame
  ;; KEEPS its `:seq`, because that number is what lets the client drop a live frame the
  ;; replay already handed over (the two sources overlap by construction).
  (let [thread "fr-json"]
    (with-server thread script
      (fn []
        (post-run thread)
        (let [child (child-of thread)
              _     (child-log child)
              {:keys [status body]} (api-get (str "/api/threads/" child "/frames"))
              parsed   body
              fs       (:frames parsed)
              recorded (frames-of (child-log child))]
          (testing "the replay opens with the run, then the snapshot inside it"
            (is (= 200 status))
            (is (= "RUN_STARTED" (:type (first fs))))
            (is (= "MESSAGES_SNAPSHOT" (:type (second fs)))))
          (testing "every recorded frame is there, in order, terminal last"
            (is (seq recorded))
            (is (= (mapv :type recorded) (mapv :type (into [(first fs)] (drop 2 fs)))))
            (is (= "RUN_FINISHED" (:type (last fs)))))
          (testing "a finished child is not claimed to be running"
            (is (false? (:running parsed))))
          (testing "AND THE NUMBER IS KEPT -- the boundary the client dedupes by"
            (is (some #(contains? % :seq) fs))))))))

(deftest the-frames-route-refuses-a-stem-that-is-nowhere
  (with-server "fr-404" script
    (fn []
      (let [{:keys [status]} (api-get "/api/threads/never-was-here/frames")]
        (is (= 404 status))))))
