(ns harness.edge.delegation-line-test
  "Ticket 03: a delegation writes ITS NAME into the parent's record, and a read
  route hands it back.

  WHY THIS IS AN EDGE TEST, NOT A CAP TEST. The line is written by
  harness.edge.http/run-subagent! -- only the edge holds the parent's `log!`
  and runs on the parent's tool thread -- and the route that reads it back is
  an edge route. Everything below is a claim about a RECORD and a WIRE answer,
  and neither exists below the edge.

  THE THREE CLAIMS, in the order a reader meets them:

    1. the parent's record holds one `delegation` row per delegation, keyed by
       the toolCallId of the call that made it, written BEFORE the child's own
       first row lands (so a card in the parent's conversation can be opened
       while the child still runs);
    2. GET /api/threads/<stem>/delegations reads those rows back, read-only,
       tolerating a half-written last row -- a parent whose child is running
       right now is the case the route exists for;
    3. the row is a record ABOUT the conversation, not a message or a frame in
       it: it is one of the CUSTOM frames the harness writes about itself, so
       nothing that folds a conversation can see it -- asserted here through a
       real rebuild, not assumed."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.fake :as fake]
            [harness.infra.db :as db]
            [harness.infra.home :as home]
            [harness.edge.http :as http]
            [harness.edge.replay :as replay]
            [harness.cap.project :as project]
            [harness.cap.providers :as providers]
            [harness.test-support :as support])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

(def ^:dynamic *port* nil)

(defn- wipe! [] (support/wipe-hooks!))

(use-fixtures :each (fn [f] (wipe!) (f) (wipe!)))

(defn- with-server
  "A live server on an OS-chosen port, with a scripted provider pinned to THREAD.

  THE SESSION IS BORN FIRST: the run edge refuses an action for a conversation the
  store has never heard of (`harness.edge.http/refuse-unknown-session!`), and
  registering the id is what a page's POST /api/sessions does."
  [thread turns f]
  (providers/use-provider! thread (fake/scripted turns))
  (support/start-session! thread)
  (let [stop (http/start! {:port 0})
        port (:local-port (meta stop))]
    (try (binding [*port* port] (f))
         (finally (stop) (providers/use-provider! thread nil)))))

(defn- post-run
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

(defn- api-get
  "A GET against the management edge, parsed."
  [path]
  (let [req (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" *port* path)))
        req (.header req "Accept" "application/json")
        resp (.send (HttpClient/newHttpClient) (.build req)
                    (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
    {:status (.statusCode resp)
     :body   (json/read-str (.body resp) :key-fn keyword)}))

(defn- frames
  [^HttpResponse response]
  (->> (str/split-lines (.body response))
       (keep #(when (str/starts-with? % "data: ") (subs % 6)))
       (mapv #(json/read-str % :key-fn keyword))))

(defn- workspace-of
  [thread-id]
  (io/file (home/projects-dir)
           (if-let [identity (project/identity-for thread-id)]
             (home/sanitize identity)
             http/unbound-workspace)))

(defn- log-file [thread-id]
  (home/log-file (workspace-of thread-id) thread-id))

(defn- read-lines [^java.io.File f]
  (->> (str/split-lines (slurp f :encoding "UTF-8"))
       (remove str/blank?)
       (keep #(try (json/read-str % :key-fn keyword) (catch Throwable _ nil)))
       (vec)))

(defn- log-lines
  [thread-id]
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


(defn- subagent-log
  "The child's record: the jsonl of the session the STORE says THREAD-ID delegated to.

  ASKED OF THE STORE, because there is nothing in the file itself to match on any
  more: the child's record opens with the TASK as its own first message
  (`.scratch/jsonl-two-kinds` 票 02 retired the `input` row that used to name the
  delegator), and the workspace it sits in is shared with every other delegation this
  JVM ran."
  [thread-id]
  (let [id (some-> (db/select "SELECT id FROM sessions WHERE parent_id = ?" thread-id)
                   first :id)
        f  (when id (log-file id))]
    (when (and id f (.exists f))
      {:thread-id id :lines (read-lines f)})))

(def ^:private task "where is the delegation line written")

(def ^:private script
  "One delegation, then the closing round -- the child reads nothing, so its
  whole conversation is one turn and the test is about the LINES, not the
  child's work."
  [{:content "" :tool-calls [{:id "d1" :name "agent"
                              :arguments {:name "explore" :prompt task}}]}
   {:content "delegated and answered"}])

(deftest the-parents-record-holds-the-delegation-line
  (let [thread "dgl-parent"]
    (with-server thread script
      (fn []
        (post-run thread)
        ;; WAITED FOR: the child's record exists only after the delegation opened
        ;; it, which is the moment the parent's row is claimed to be there -- so
        ;; this wait is what makes the parent read below a fair one.
        (wait-for thread (fn [_ls] (some? (subagent-log thread))) 4000)
        (let [{child :thread-id} (subagent-log thread)
              lines (log-lines thread)
              rows  (filter #(= "delegation" (replay/kind %)) lines)
              row   (first rows)]
          (testing "one row, naming the call that made it"
            (is (= 1 (count rows)))
            (is (= "d1" (:toolCallId (replay/payload row)))
                "the toolCallId of the delegating call, not a position"))
          (testing "and naming the child"
            (is (= "explore" (:subagent (replay/payload row))))
            (is (some? child))
            (is (= child (:threadId (replay/payload row)))
                "the child session's id -- the stem every read route takes"))
          (testing "written before the child's own first row"
            ;; THE CHILD'S FIRST ROW IS ITS TASK, now that the `input` row is gone:
            ;; a conversation begins with what it was asked. The child's record
            ;; cannot have started before the parent learned about it, by
            ;; construction of run-subagent! -- so this half of the claim is the
            ;; parent's row carrying a `ts` that is not later than the child's
            ;; first row's.
            (let [child-first (first (:lines (subagent-log thread)))]
              (is (= "where is the delegation line written"
                     (:content (replay/payload child-first)))
                  "the child's record opens with the task it was delegated")
              (is (<= (:ts row) (:ts child-first))
                  "the parent learned the child before the child spoke"))))))))

(deftest delegations-route-reads-the-parents-rows-back
  (let [thread "dgl-route"]
    (with-server thread script
      (fn []
        (post-run thread)
        (wait-for thread (fn [_ls] (some? (subagent-log thread))) 4000)
        (let [child (:thread-id (subagent-log thread))
              {:keys [status body]} (api-get (str "/api/threads/" thread "/delegations"))]
          (testing "the answer is the rows, keyed to the asked thread"
            (is (= 200 status))
              (is (= thread (:threadId body)))
              (is (= 1 (count (:delegations body))))
              (let [row (first (:delegations body))]
                (is (= "d1" (:toolCallId row)))
                (is (= "explore" (:subagent row)))
                (is (= child (:threadId row)))
                (is (some? (:at row)) "the millisecond the delegation was made"))))
          (testing "a thread that never delegated answers an empty list, not an error"
            (let [{:keys [status body]} (api-get "/api/threads/never-was/delegations")]
              ;; A stem with no log is 404 -- the locator's sentence; a thread
              ;; that exists and simply never delegated is the EMPTY answer.
              ;; never-was has no log at all, so 404 is the honest answer here.
              (is (= 404 status))
              (is (str/includes? (:error body) "never-was"))))
          (testing "a verb nobody serves falls through, not 405"
            ;; The verb set is CLOSED: a name outside it never matched this
            ;; shape's route at all, so the answer comes from the table's own
            ;; 404 -- that is the pre-existing contract this edge chose
            ;; (docs/architecture/edge.md, the route table's closing rule),
            ;; and 405 here would claim a route existed and refused.
            (let [{:keys [status body]} (api-get (str "/api/threads/" thread "/no-such-verb"))]
              (is (= 404 status))
              (is (str/includes? (str (:error body)) "no-such-verb"))))))))

(deftest the-delegation-line-never-enters-the-rebuilt-conversation
  (let [thread "dgl-rebuild"]
    (with-server thread script
      (fn []
        (post-run thread)
        (wait-for thread (fn [_ls] (some? (subagent-log thread))) 4000)
        (let [req (-> (HttpRequest/newBuilder
                       (URI/create (str "http://127.0.0.1:" *port* "/api/threads/" thread "/rebuild")))
                      (.header "Content-Type" "application/json")
                      (.POST (HttpRequest$BodyPublishers/ofString "{}" StandardCharsets/UTF_8))
                      (.build))
              resp (.send (HttpClient/newHttpClient) req
                          (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))
              body (json/read-str (.body resp) :key-fn keyword)
              texts (->> (:messages body)
                         (mapcat (fn [m] [(:content m)]))
                         (remove str/blank?)
                         (map str))]
          ;; The row is a record ABOUT the conversation. If it leaked into the
          ;; messages, the model's next run would see a line nobody said.
          (testing "no delegation row is folded into the messages"
            (is (not-any? #(str/includes? % "delegation") texts))
            (is (not-any? #(str/includes? % "d1") texts)))
          (testing "and the conversation is the ordinary one"
            (is (some #(str/includes? % "delegated and answered") texts)
                "the assistant's answer survived")
            ;; The TASK is not a message of its own -- it lives inside the
            ;; `agent` call's arguments, which is how a delegation enters a
            ;; conversation at all (one tool call, not a quoted transcript).
            (let [calls (->> (:messages body)
                             (mapcat :toolCalls)
                             (filter #(= "agent" (get-in % [:function :name]))))]
              (is (= 1 (count calls)))
              (is (str/includes? (str (get-in (first calls) [:function :arguments])) task)
                  "the delegating call carries the task in its arguments"))))))))
