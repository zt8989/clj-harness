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

;; ------------------------------------------- fork 把一份记录变成可以继续写的（`.scratch/record-normalization` 票 03）
;;
;; 判据（票 01）说了「已重整化」是什么，票 02 的门说了未重整化的记录不许写。这一票是**那把钥匙**：
;; fork 复制一份、把它修成合格的，原会话一个字节不动。

(defn- answered-by-a-frame-only
  "One FINISHED run whose one call was answered by a FRAME and never by a row: the shape the walk in
  `.scratch/record-normalization/evidence/real-records.md` found in a real record (票 05 of
  `.scratch/record-stream` is the bug that used to drop that row)."
  [thread run-id call-id content]
  [(frame run-id {:type "RUN_STARTED" :threadId thread :runId run-id})
   (frame run-id {:type "TOOL_CALL_START" :toolCallId call-id})
   (frame run-id {:type "TOOL_CALL_END" :toolCallId call-id})
   (frame run-id {:type "TOOL_CALL_RESULT" :messageId (str run-id "-t2")
                  :toolCallId call-id :content content})
   (frame run-id {:type "RUN_FINISHED" :threadId thread :runId run-id})])

(defn- tool-row-for
  "The `message` row answering CALL-ID on the record THREAD-ID names, or nil."
  [thread-id call-id]
  (first (filter #(and (= "message" (replay/kind %))
                       (= call-id (get-in (replay/payload %) [:tool_call_id])))
                 (replay/read-records (log-file thread-id)))))

(defn- rows-of
  "The rows of THREAD-ID's record that are the CONVERSATION's: the file's own furniture (the header)
  and the fork's audit line left out, so two forks of one record can be compared."
  [thread-id]
  (->> (replay/read-records (log-file thread-id))
       (remove (fn [r] (contains? #{"record/header" "session/forked"} (replay/kind r))))
       (mapv (fn [r] [(replay/kind r) (replay/payload r)]))))

(deftest a-fork-writes-the-row-a-frame-alone-was-answering-with
  (support/with-temp-env [_root _home]
    (let [stop (http/start! {:port 0})
          port (:local-port (meta stop))]
      (try
        (project/register-session! "src-row")
        (spit-lines! (log-file "src-row")
                     (concat [(header-line "src-row")
                              (msg "r1" "u1" "read deps.edn")]
                             (answered-by-a-frame-only "src-row" "r1" "c1" ":paths [\"src\"]")
                             [(fact "step/end" {})]))
        (let [parent  (slurp (log-file "src-row") :encoding "UTF-8")
              before  (:body (fetch port "/api/threads/src-row/sofar"))
              forked  (:body (post port "/api/threads/src-row/fork" {}))
              new-id  (:threadId forked)
              answer  (:body (fetch port (str "/api/threads/" new-id "/sofar")))
              second  (:threadId (:body (post port (str "/api/threads/" new-id "/fork") {})))
              again   (:body (fetch port (str "/api/threads/" second "/sofar")))]
          (testing "THE PRECONDITION: a call answered by a frame alone is a record nobody may write to"
            (is (false? (:normalized before)))
            (is (= ["1 次工具调用没有 message 行答复"] (:normalizationReasons before))))
          (testing "the fork's record IS normalized, and the row is on it saying what the frame said"
            (is (true? (:normalized answer)))
            (is (= [] (:normalizationReasons answer)))
            (let [row (tool-row-for new-id "c1")]
              (is (some? row) "the row that answer never landed as is there now")
              (is (= ":paths [\"src\"]" (:content (replay/payload row))))))
          (testing "the parent is untouched, byte for byte"
            (is (= parent (slurp (log-file "src-row") :encoding "UTF-8"))))
          (testing "and forking a record that already has every row adds nothing: the fork is idempotent"
            (is (string? second) "the second fork landed")
            (is (true? (:normalized again)) "and its record is normalized too")
            (is (= (rows-of new-id) (rows-of second)) "no row was added by the second fork")))
        (finally (stop))))))

(deftest a-fork-drops-the-half-written-end-with-the-cut
  ;; 记录断在半路（进程被杀）：切点落在**最后一步的结束**，没写完的那半截根本没进新文件；
  ;; 而被切断那次调用的答复由修缮补上——帧和行一起（票 01/02 落的那一处写手）。
  (support/with-temp-env [_root _home]
    (let [stop (http/start! {:port 0})
          port (:local-port (meta stop))]
      (try
        (project/register-session! "src-cut")
        (spit-lines! (log-file "src-cut")
                     [(header-line "src-cut")
                      (msg "r1" "u1" "start")
                      (frame "r1" {:type "TOOL_CALL_START" :toolCallId "c1"})
                      (frame "r1" {:type "TOOL_CALL_END" :toolCallId "c1"})
                      (fact "step/end" {})
                      ;; ...and then a run that never came back, with a text envelope still open
                      (frame "r2" {:type "RUN_STARTED" :threadId "src-cut" :runId "r2"})
                      (frame "r2" {:type "TEXT_MESSAGE_START" :messageId "r2-m1" :role "assistant"})])
        (let [resp   (post port "/api/threads/src-cut/fork" {})
              new-id (:threadId (:body resp))
              answer (:body (fetch port (str "/api/threads/" new-id "/sofar")))]
          (is (= 200 (:status resp)))
          (is (= 4 (:seq (:body resp))) "the cut is the last step's end, so the half-written part is not copied")
          (is (true? (:normalized answer)) "the fork's product may be written to")
          (is (= [] (:normalizationReasons answer)))
          (is (some? (tool-row-for new-id "c1")) "and the call this record never answered has its row now"))
        (finally (stop))))))

(deftest an-old-contract-record-is-refused-by-name-not-migrated
  ;; 旧格式（顶层 `kind`）在今天**读都读不到**（`replay/read-row` 按名字拒绝 :old-contract），所以
  ;; fork 也只能照直说。要不要真的迁移旧记录，是这一票里唯一还没定的取舍——先明确拒绝，别静默改写。
  (support/with-temp-env [_root _home]
    (let [stop (http/start! {:port 0})
          port (:local-port (meta stop))]
      (try
        (project/register-session! "src-old")
        (spit-lines! (log-file "src-old")
                     [(json/write-str {:ts 1 :runId "r1" :kind "input"
                                       :payload {:threadId "src-old"}})
                      ;; A SECOND LINE, because the reader tolerates ONE torn line at the END of a
                      ;; file (`rows-tolerating-a-torn-last-line`) -- a single old-format line would be
                      ;; swallowed as 'the writer was mid-flush' and this case would pass for the wrong
                      ;; reason. EVERY OTHER LINE IS READ STRICTLY, and that is where the refusal is.
                      (json/write-str {:ts 2 :runId "r1" :kind "event"
                                       :payload {:type "CUSTOM" :name "model/start" :value {}}})])
        (let [{:keys [status body]} (post port "/api/threads/src-old/fork" {})]
          (is (= 400 status))
          (is (= "old-contract" (:reason body)) "the reader refuses it by name, and the route says so")
          (is (str/includes? (:error body) "old contract")))
        (finally (stop))))))
