(ns harness.edge.follow-route-test
  "Ticket 02: GET /api/threads/<stem>/follow -- the read-only channel that lets a
  panel watch a delegation work.

  WHY THIS IS AN EDGE TEST. Every claim below is about a CHANNEL and a WIRE: what a
  client is handed when it connects, in what order, and when the stream ends. None of
  that exists below the edge -- the route, the bus and the record writer all live here
  or are wired here.

  ONE CASE IS BUILT RATHER THAN RUN, and it is worth saying why up front. Watching a
  delegation WHILE it runs would mean racing a scripted provider whose turns take
  microseconds; the test would then assert either 'we happened to catch it' or nothing
  at all. So the live case below puts the frames on the bus ITSELF, after the real
  delegation has already left a record: the channel cannot tell where a frame came
  from, which is exactly what makes the bus the right seam to drive (`frame-bus/
  publish!` is what `run-subagent!` calls), and the subscription is waited for before
  anything is published -- so the test is a race with a doorbell, not with luck."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.cap.frame-bus :as frame-bus]
            [harness.cap.project :as project]
            [harness.cap.providers :as providers]
            [harness.cap.subagents :as subagents]
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

(defn- follow-request [stem]
  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port*
                                               "/api/threads/" stem "/follow")))
      (.header "Accept" "text/event-stream")
      (.GET)
      (.build)))

(defn- sse-frames
  "The SSE body as maps. `.body`, not `(:body ..)`: a java.net.http response is not a
  map, and keyword lookup on it answers nil in silence."
  [^HttpResponse resp]
  (->> (str/split-lines (.body resp))
       (keep #(when (str/starts-with? % "data: ") (subs % 6)))
       (mapv #(json/read-str % :key-fn keyword))))

(defn- terminal-type?
  "Is this frame one of the two the AG-UI vocabulary uses to end a run?"
  [frame]
  (contains? #{"RUN_FINISHED" "RUN_ERROR"} (:type frame)))

(defn- sse-comments
  "The SSE COMMENT lines of a body -- `: <sentence>`. They are the route's answers that
  are NOT frames, and they are comments for a reason the route's docstring gives: a
  client's parser fails a run on a frame it does not know, so the harness may only ever
  say a non-frame thing on this channel as a comment."
  [^HttpResponse resp]
  (->> (str/split-lines (.body resp))
       (keep #(when (str/starts-with? % ": ") (subs % 2)))
       (vec)))

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
  "The child's record once its run is on disk: the terminal frame is the row that says
  the run is over, and the writer is a consumer off the run's own thread."
  [thread-id]
  (wait-for thread-id
            (fn [ls] (some #(and (= "event" (replay/kind %))
                                 (contains? #{"RUN_FINISHED" "RUN_ERROR"}
                                            (:type (replay/payload %))))
                           ls))
            4000))

(defn- frames-of [lines] (->> lines (filter replay/frame?) (mapv replay/payload)))

;; ---------------------------------------------------------------- the fixtures

(def ^:private task "where is the follow route's dedupe")

(def ^:private script
  "One delegation, then the closing round. The child reads a file, so its record holds
  more than the two frames that would bracket an empty answer."
  [{:content "" :tool-calls [{:id "d1" :name "agent"
                              :arguments {:name "explore" :prompt task}}]}
   {:content "" :tool-calls [{:id "c1" :name "read" :arguments {:path "deps.edn"}}]}
   {:content "the dedupe is the publisher's number"}
   {:content "ok"}])

;; ------------------------------------------------------------------ the reading

(deftest a-finished-delegations-record-is-replayed-and-then-ended
  (let [thread "fw-finished"]
    (with-server thread script
      (fn []
        (post-run thread)
        (let [child (child-of thread)
              _     (child-log child)
              ;; THE CHILD, NOT THE PARENT: the parent's frames went to the client that
              ;; started its run, on that run's own stream.
              resp  (.send (HttpClient/newHttpClient) (follow-request child)
                           (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))
              fs    (sse-frames resp)]
          (testing "the channel is an SSE of the record, and it closes by itself"
            (is (= 200 (.statusCode resp)))
            (is (str/starts-with? (.orElse (.firstValue (.headers resp) "content-type") "")
                                  "text/event-stream")))
          (testing "THE STREAM OPENS WITH THE RUN, not with the snapshot"
            ;; `@ag-ui/client` REFUSES a stream whose first event is not `RUN_STARTED`
            ;; ("First event must be 'RUN_STARTED'"), and a snapshot sent ahead of the
            ;; run is exactly what a real panel was failed by in a browser: the wire
            ;; looked reasonable and the runtime would not read it. The record's own
            ;; opening frame is what goes first, and a real child's record opens with
            ;; the run -- `run-subagent!` writes `RUN_STARTED` before it says anything.
            (is (= "RUN_STARTED" (:type (first fs))))
            (is (= (:runId (first (frames-of (child-log child)))) (:runId (first fs)))
                "the child's own run, not a number this route invented"))
          (testing "and then the task it was handed, as a snapshot inside the run"
            ;; THE MESSAGE NO FRAME CARRIES. Frames hold what a run RETURNED; the task
            ;; is a `message` row the delegating model sent, so without this frame a
            ;; panel would show answers to a question it never showed.
            (let [snapshot (second fs)]
              (is (= "MESSAGES_SNAPSHOT" (:type snapshot)))
              (is (= [task] (mapv :content (:messages snapshot))))
              (is (= ["user"] (mapv :role (:messages snapshot))))
              ;; AND ONLY WHAT THE FRAMES CANNOT REBUILD: every message this snapshot
              ;; names must be one no replayed frame mentions, or the panel would draw
              ;; it twice.
              (is (empty? (filter (set (keep :messageId (drop 2 fs)))
                                  (map :id (:messages snapshot)))))))
          (testing "every wire frame the record holds is replayed, in order, around it"
            (let [recorded (frames-of (child-log child))
                  replayed (into [(first fs)] (drop 2 fs))]
              (is (seq recorded))
              (is (= (mapv :type recorded) (mapv :type replayed)))
              (is (contains? (set (map :type fs)) "RUN_FINISHED")
                  "including the terminal one"))
            (testing "and the number the boundary counts by never reaches a client"
              ;; :seq is the bus's bookkeeping (see follow-get): it is written into the
              ;; record so both sources count the same thing, and stripped here.
              (is (not-any? #(contains? % :seq) fs))))
          (testing "the terminal is the ending, and nothing is said behind it"
            (is (= "RUN_FINISHED" (:type (last fs))))
            (is (empty? (sse-comments resp))
                "a finished run needs no comment: the terminal is the client's ending")))))))

(deftest a-thread-nobody-is-running-answers-what-there-is-and-ends
  ;; NO DELEGATION IS IN FLIGHT AND THE RECORD HAS NO TERMINAL: the case a panel meets
  ;; when it opens on a conversation that was never delegated to, or on one whose
  ;; process died mid-run. The channel must say so rather than hang -- a stream that
  ;; never produces and never ends is indistinguishable from 'still running'.
  (let [thread "fw-idle"
        frame  {:type "TEXT_MESSAGE_CONTENT" :messageId "m1" :delta "half a thought"}]
    (with-server thread []                       ;; never run: nothing consumes turns
      (fn []
        (let [f (log-file thread)]
          (.mkdirs (.getParentFile f))
          ;; A ROW THE READER ACCEPTS, AND THEN A TORN ONE. The half line is what a
          ;; live writer legitimately leaves: the tolerant reader drops it and keeps
          ;; the rest, which is the whole reason this route does not use the strict
          ;; one (`rebuild` refuses a torn line; a watcher cannot).
          (spit f (str (json/write-str {:ts 1 :runId "r1" :type "event" :payload frame})
                       "\n"
                       "{\"ts\": 2, \"runId\": \"r1\", \"type\": \"even")
                :encoding "UTF-8")
          (let [resp (.send (HttpClient/newHttpClient) (follow-request thread)
                            (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))
                fs   (sse-frames resp)]
            (testing "what the record holds comes back, torn row and all"
              (is (= 200 (.statusCode resp)))
              ;; A RECORD THAT DOES NOT OPEN WITH A RUN GETS ONE ANYWAY, carrying the
              ;; child's own thread and run: the stream a runtime consumes has to be
              ;; well-formed whatever the file holds, and this record was written by
              ;; hand to hold one frame and a torn line.
              (is (= "RUN_STARTED" (:type (first fs))))
              (is (= thread (:threadId (first fs))))
              (is (= "r1" (:runId (first fs))) "the run the record's own rows name")
              (is (= [frame] (rest fs))))
            (testing "and then it says nobody is running this thread"
              ;; A COMMENT, NOT A FRAME: the client's parser fails a run on a frame it
              ;; does not know, so this channel's non-frame answers are comments -- and
              ;; the CLOSE is what a client acts on.
              (let [said (sse-comments resp)]
                (is (= 1 (count said)))
                (is (str/includes? (first said) thread))
                (is (str/includes? (first said) "not running"))))
            (testing "with no terminal frame invented for a run that never finished"
              (is (not-any? terminal-type? fs)))))))))

(deftest the-boundary-drops-a-frame-the-replay-already-delivered
  ;; THE RACE THE TICKET DEMANDS AN ANSWER FOR. Subscribe-then-replay means a frame
  ;; that landed between the two can arrive from BOTH sources; the answer is the
  ;; publisher's per-thread number, and this is that answer being exercised: the same
  ;; number twice must produce ONE frame, and the next number must still get through.
  ;;
  ;; THE RECORD IS WRITTEN BY HAND AND THE FRAMES ARE PUBLISHED BY HAND, because the
  ;; boundary is between two sources and a REAL delegation cannot hold still on it: a
  ;; running child would have finished before the test could publish anything, and a
  ;; finished one is already over (`over?` ends the channel before the bus is read --
  ;; see the first case). So: a record with frames and NO terminal, a thread put back
  ;; in the live table (which is what `run-one` does while it runs), and the frames the
  ;; publisher would have sent.
  (let [thread "fw-boundary"
        frames [{:type "RUN_STARTED" :threadId "fw-boundary"}
                {:type "TEXT_MESSAGE_START" :messageId "m0" :role "assistant"}
                {:type "TEXT_MESSAGE_CONTENT" :messageId "m0" :delta "one"}]
        ;; THE NUMBERS THE RECORD WOULD HOLD, written the way `run-subagent!` writes
        ;; them: the same count the bus carries, which is the whole reason a boundary
        ;; can be drawn at all.
        rows   (map-indexed (fn [i f]
                              (json/write-str {:ts (inc i) :runId "r1"
                                               :type "event"
                                               :payload (assoc f :seq (inc i))}))
                            frames)
        live   {:type "TEXT_MESSAGE_CONTENT" :messageId "m0" :delta "still working"}
        done   {:type "RUN_FINISHED" :threadId "fw-boundary"}]
    (with-server thread []
      (fn []
        (let [f (log-file thread)]
          (.mkdirs (.getParentFile f))
          (spit f (str (str/join "\n" rows) "\n") :encoding "UTF-8")
          ;; THE ONE LIE IN THIS FILE, and it is deliberate: no delegation is running
          ;; here. `begin!` is exactly what `run-one` does for the duration of a run,
          ;; so the route cannot tell the difference -- which is the point.
          (let [end! (subagents/begin! thread
                                       {:parent     "fw-nowhere"
                                        :definition (subagents/definition-for
                                                     (subagents/definitions thread) "explore")
                                        :table      {}})]
            (try
              (let [read-fut (future (.send (HttpClient/newHttpClient) (follow-request thread)
                                            (HttpResponse$BodyHandlers/ofString
                                             StandardCharsets/UTF_8)))]
                ;; A RACE WITH A DOORBELL: the route subscribes inside `on-open`, and a
                ;; frame published before that is dropped by design (nobody was
                ;; listening). Waiting for the subscriber is what makes this test
                ;; deterministic instead of lucky.
                (let [deadline (+ (System/currentTimeMillis) 4000)]
                  (loop []
                    (when (and (zero? (frame-bus/subscriber-count thread))
                               (< (System/currentTimeMillis) deadline))
                      (Thread/sleep 20)
                      (recur))))
                (is (= 1 (frame-bus/subscriber-count thread)) "the channel is subscribed")
                ;; 1. THE NUMBER THE REPLAY ALREADY HANDED OVER. Dropped.
                (frame-bus/publish! thread (assoc live :seq 3 :delta "delivered twice"))
                ;; 2. The next frame. Delivered.
                (frame-bus/publish! thread (assoc live :seq 4))
                ;; 3. And the terminal, which ends the channel.
                (frame-bus/publish! thread (assoc done :seq 5))
                (let [fs           (sse-frames @read-fut)
                      after-replay (drop (count frames) fs)]
                  (testing "the replay comes first, whole"
                    (is (= (mapv :type frames) (mapv :type (take (count frames) fs)))))
                  (testing "the duplicate is dropped and the next number is not"
                    ;; READ OFF THE WIRE, where the number is NOT: the route strips
                    ;; `:seq` on the way out (it is the boundary's bookkeeping), so the
                    ;; duplicate is identified by what it SAID -- the frame published
                    ;; with the replay's own number carried a different delta, and if
                    ;; the dedupe were missing it would be the first thing here.
                    (is (= ["TEXT_MESSAGE_CONTENT" "RUN_FINISHED"]
                           (mapv :type after-replay)))
                    (is (= "still working" (:delta (first after-replay)))
                        "the frame the replay had NOT delivered")
                    (is (= 2 (count after-replay))
                        "one live frame and the terminal -- no third copy"))
                  (testing "and the channel ends behind the terminal it delivered"
                    (is (terminal-type? (last fs)))
                    (is (empty? (sse-comments @read-fut))
                        "the terminal is the ending -- no comment behind it"))))
              (finally (end!)))))))))

(deftest following-a-stem-that-is-nowhere-is-the-ordinary-json-404
  (let [thread "fw-missing"]
    (with-server thread []
      (fn []
        (let [{:keys [status body]} (api-get "/api/threads/never-was/follow")]
          ;; A STREAM THAT OPENED just to say the thread does not exist would be a
          ;; stream pretending it does; the management edge's own 404 is the answer.
          (is (= 404 status))
          (is (str/includes? (str (:error body)) "never-was")))))))
(deftest the-frames-route-answers-the-replay-as-json-and-keeps-the-number
  ;; TICKET 04: the replay half a panel reads BEFORE the downlink carries the live tail.
  ;; The same frames the follow channel replays, as JSON -- and unlike the follow wire each
  ;; frame KEEPS its `:seq`, because that number is what lets the client drop a live frame
  ;; the replay already handed over (the two sources overlap by construction).
  (let [thread "fr-json"]
    (with-server thread script
      (fn []
        (post-run thread)
        (let [child (child-of thread)
              _     (child-log child)
              {:keys [status body]} (api-get (str "/api/threads/" child "/frames"))
              ;; `api-get` parses already; the body is the map.
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
