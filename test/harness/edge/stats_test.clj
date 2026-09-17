(ns harness.edge.stats-test
  "The session's numbers, asserted over HAND-WRITTEN RECORDS.

  A record is four keys (see docs/architecture/edge.md), and every number the
  status strip draws is a fact about a handful of them -- so the fold is pinned
  here with records the test writes itself, not with a log produced by a run. That
  is what makes the arithmetic, the denominators and the ABSENCES assertable: a
  run can only show what that run happened to do, and the interesting cases
  (a vendor that reports nothing, a log that predates the model lines, a session
  still running) are exactly the ones a run will not produce on request."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.providers :as providers]
            [harness.edge.http :as http]
            [harness.edge.stats :as stats]
            [harness.fake :as fake]
            [harness.infra.home :as home])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

;; ------------------------------------------------------------------ the records

(defn- record
  "One jsonl record: {:ts ms, :runId s, :kind s, :payload v}, exactly as the edge
  writes it. The arguments run in the record's own order -- ts, kind, payload --
  with the run id optional in the middle."
  ([ts kind payload] (record ts "r1" kind payload))
  ([ts run-id kind payload] {:ts ts :runId run-id :kind kind :payload payload}))

(defn- input
  "An `input` line carrying a client's message list."
  [ts & msgs]
  (record ts "input" {:threadId "t" :messages (vec msgs)}))

(defn- user [id text] {:id id :role "user" :content text})

(defn- frame
  "An `event` line carrying one AG-UI frame."
  [ts type payload]
  (record ts "event" (merge {:type type} payload)))

(def ^:private finished (frame 90 "RUN_FINISHED" {:threadId "t" :runId "r1"}))

(defn- start [ts] (record ts "model/start" {:model "scripted" :base-url "http://x/v1"}))

(defn- end
  "A `model/end` line. USAGE-MAP is the VENDOR'S usage and it goes where the wire
  puts it: the payload is the whole telemetry, so the usage sits under :usage. A nil
  USAGE-MAP is a call that reported NOTHING -- an empty payload, not a payload of
  zeroes."
  [ts usage-map]
  (record ts "model/end" (if (nil? usage-map) {} {:usage usage-map})))

(defn- usage
  "A vendor's usage map, the way an OpenAI-compatible wire spells it."
  ([prompt completion] (usage prompt completion nil))
  ([prompt completion cached]
   (cond-> {:prompt_tokens prompt :completion_tokens completion
            :total_tokens (+ prompt completion)}
     (some? cached) (assoc :prompt_tokens_details {:cached_tokens cached}))))

(defn- conversation
  "One turn: the client's input, one model call that reported USAGE, and the frame
  that closes the run."
  [ts user-id usage-map]
  (let [t (+ ts 1000)]
    [(input ts (user user-id (str "ask " user-id)))
     (start t)
     (end (+ t 100) usage-map)
     (frame (+ t 200) "RUN_FINISHED" {:threadId "t" :runId "r1"})]))

;; ------------------------------------------------------------------- the answer

(defn- stats-of
  "RECORDS -> the answer, exactly as the route builds it. A collection, not loose
  arguments: a record is a map, and a map splatted as arguments becomes its ENTRIES
  -- which is how this helper's first version silently folded nothing."
  [records]
  (stats/records->stats (vec records)))

(deftest one-turn-one-call
  (let [s (stats-of (conversation 0 "u1" (usage 100 20 80)))]
    (is (= 1 (:turns s)) "one user message is one turn")
    (is (= 1 (:steps s)) "one model call is one step")
    (is (= 1 (:stepsWithUsage s)))
    (is (= {:totalTokens 120 :promptTokens 100 :completionTokens 20 :cachedTokens 80}
           (:usage s)))
    (is (= 80 (:cacheHitPercent s)))
    (testing "the speed is completion tokens over the call's own duration"
      ;; 20 tokens in 100ms = 200 tokens/second. The duration is the :ts difference
      ;; between the pair, never a gap to some other line.
      (is (= 200 (:outputTokensPerSecond s))))
    (is (false? (:incomplete s)) "the run's last frame is terminal")))

(deftest turns-are-counted-per-new-user-message
  (testing "each new user message opens a turn"
    (let [s (stats-of (concat (conversation 0 "u1" (usage 10 1))
                                    (conversation 5000 "u2" (usage 10 1))))]
      (is (= 2 (:turns s)))
      (is (= 2 (:steps s)))))

  (testing "a resume -- a second input with no new user message -- opens none"
    ;; The parked turn's continuation: the client restates the whole history under
    ;; the same runId. Counting it would make one turn read as two.
    (let [s (stats-of (concat (conversation 0 "u1" (usage 10 1))
                                    [(input 5000 (user "u1" "ask u1"))   ;; same id
                                     (start 5100)
                                     (end 5200 (usage 10 1))
                                     finished]))]
      (is (= 1 (:turns s)) "the same user message is the same turn")
      (is (= 2 (:steps s)) "but it IS a second model call")))

  (testing "an input that brings two new user messages brings two turns"
    (let [s (stats-of [(input 0 (user "u1" "a") (user "u2" "b"))
                   (start 100) (end 200 (usage 10 1)) finished])]
      (is (= 2 (:turns s))))))

(deftest absences-are-not-zeroes
  (testing "a call that reported nothing is a step, and contributes NO tokens"
    (let [s (stats-of [(input 0 (user "u1" "hi"))
                   (start 100) (end 200 nil) finished])]
      (is (= 1 (:steps s)))
      (is (= 0 (:stepsWithUsage s)))
      (is (not (contains? s :usage)) "usage is absent, not {}")
      (is (not (contains? s :cacheHitPercent)))
      (is (not (contains? s :outputTokensPerSecond)) "no output count, no rate")))

  (testing "a key no call reported is missing from usage; the ones they did are summed"
    (let [s (stats-of [(input 0 (user "u1" "hi"))
                   (start 100) (end 200 {:prompt_tokens 50 :completion_tokens 5})
                   finished])]
      (is (= {:totalTokens 55 :promptTokens 50 :completionTokens 5} (:usage s))
          "total falls back to prompt + completion for a call that gave both")
      (is (not (contains? (:usage s) :cachedTokens))
          "no vendor reported a cache, so there is no cache number")))

  (testing "a log from before the model lines: turns yes, everything else ABSENT"
    ;; The distinction the strip depends on: no model/start line means 'this record
    ;; cannot tell', NOT 'no call ever happened'.
    (let [s (stats-of [(input 0 (user "u1" "hi")) finished])]
      (is (= 1 (:turns s)))
      (is (not (contains? s :steps)))
      (is (not (contains? s :stepsWithUsage)))
      (is (not (contains? s :usage)))))

  (testing "a session with nothing in it at all"
    (let [s (stats-of [])]
      (is (= 0 (:turns s)))
      (is (= {:turns 0 :incomplete false} s)
          "nothing but the two keys that are always answerable"))))

(deftest the-denominators-are-the-calls-that-reported
  (testing "the cache rate and the speed leave out the calls that could not feed them"
    (let [s (stats-of [(input 0 (user "u1" "hi"))
                   ;; a call with a full report: 80/100 cached, 20 tokens in 100ms
                   (start 100) (end 200 (usage 100 20 80))
                   ;; a call that reported nothing at all: it is a step, and it is in
                   ;; neither denominator
                   (start 300) (end 400 nil)
                   ;; a call with tokens but no cache cell: it feeds the total and the
                   ;; speed, and it stays out of the cache ratio
                   (start 500) (end 600 {:prompt_tokens 100 :completion_tokens 10})
                   finished])]
      (is (= 3 (:steps s)))
      (is (= 2 (:stepsWithUsage s)))
      (is (= 80 (:cacheHitPercent s)) "cached 80 over prompt 100 -- the third call is not in it")
      (is (= {:totalTokens 230 :promptTokens 200 :completionTokens 30 :cachedTokens 80}
             (:usage s))
          "230 is call one's 120 plus call three's 110 (its prompt + completion)")
      (testing "30 completion tokens over 200ms of calls that reported both"
        (is (= 150 (:outputTokensPerSecond s))))))

  (testing "an unterminated call is a step with no duration and no tokens"
    ;; The session is being read WHILE it runs: the run has started, one call has
    ;; come back, and the next one is still streaming.
    (let [s (stats-of [(frame 0 "RUN_STARTED" {:threadId "t" :runId "r1"})
                             (input 0 (user "u1" "hi"))
                             (start 100) (end 200 (usage 100 20 80))
                             (start 300)])]        ;; still streaming
      (is (= 2 (:steps s)))
      (is (= 1 (:stepsWithUsage s)))
      (is (= 200 (:outputTokensPerSecond s)) "one call's tokens over one call's time")
      (is (true? (:incomplete s)) "the last frame is not terminal"))))

(deftest a-half-written-last-line-is-dropped-and-the-rest-still-counts
  ;; The strip asks while a run streams, so this reader must survive a line caught
  ;; mid-flush. replay would refuse the whole file; a statistic is not a rebuild.
  (let [f (java.io.File/createTempFile "stats-lines" ".jsonl")]
    (try
      (spit f (str (json/write-str (input 0 (user "u1" "hi"))) "\n"
                   (json/write-str (start 100)) "\n"
                   "{\"ts\":200,\"runId\":\"r1\",\"kind\":\"model/e")
            :encoding "UTF-8")
      (is (= {:turns 1 :steps 1 :stepsWithUsage 0 :incomplete false}
             (stats/log-stats f))
          "the truncated line is gone; the two whole ones are folded")
      (finally (.delete f))))

  (testing "but a line that is not the last one is still a hard failure"
    (let [f (java.io.File/createTempFile "stats-lines" ".jsonl")]
      (try
        (spit f (str "not json at all\n"
                     (json/write-str (input 0 (user "u1" "hi"))) "\n")
              :encoding "UTF-8")
        (is (thrown-with-msg? Exception #"not valid JSON" (stats/log-stats f))
            "a corrupt line before the last one names itself")
        (finally (.delete f))))))

;; ----------------------------------------------------------------- the endpoint

(defn- with-server
  "A live edge with a scripted provider pinned to THREAD-ID. Deliberately thinner
  than http_test's: this namespace needs one thread, one script and one GET, and
  grows no machinery it does not use."
  [thread-id turns f]
  (providers/use-provider! thread-id (fake/scripted turns))
  (let [stop (http/start! {:port 0})]
    (try
      (f (:local-port (meta stop)))
      (finally
        (stop)
        (providers/use-provider! thread-id nil)))))

(defn- send-run!
  "One real AG-UI run, drained. Returns its response body."
  [port thread-id]
  (let [body (json/write-str {:threadId thread-id :runId (str (java.util.UUID/randomUUID))
                              :messages [{:id "u1" :role "user" :content "看看这个项目"}]
                              :tools [] :context []})
        req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/api/agent")))
                 (.header "Content-Type" "application/json")
                 (.header "Accept" "text/event-stream")
                 (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                 (.build))]
    (.body (.send (HttpClient/newHttpClient) req
                  (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)))))

(defn- get-json [port path]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                (.GET)
                (.build))
        resp (.send (HttpClient/newHttpClient) req
                    (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
    [(.statusCode resp) (json/read-str (.body resp) :key-fn keyword)]))

(deftest the-endpoint-folds-what-the-run-wrote
  ;; The whole path, once, over real HTTP: a real run writes the log, and the route
  ;; folds it. The scripted vendor reports the numbers, so the answer is checkable
  ;; by hand -- and so is the shape of the JSON the strip will read.
  (let [thread-id "stats-endpoint"]
    (with-server thread-id
      [{:content "" :tool-calls [{:id "c1" :name "no-such-tool" :arguments {}}]
        :usage {:prompt_tokens 1000 :completion_tokens 40 :total_tokens 1040
                :prompt_tokens_details {:cached_tokens 900}}}
       {:content "done"
        :usage {:prompt_tokens 1200 :completion_tokens 8 :total_tokens 1208
                :prompt_tokens_details {:cached_tokens 1100}}}]
      (fn [port]
        (send-run! port thread-id)
        (let [[status body] (get-json port (str "/api/threads/" thread-id "/stats"))]
          (is (= 200 status))
          (is (= thread-id (:threadId body)))
          (is (= 1 (:turns body)) "one user message")
          (is (= 2 (:steps body)) "a tool round is two model calls")
          (is (= 2 (:stepsWithUsage body)))
          (is (= {:totalTokens 2248 :promptTokens 2200 :completionTokens 48
                  :cachedTokens 2000}
                 (:usage body)))
          (is (= 91 (:cacheHitPercent body)) "2000 of 2200, rounded")
          (is (pos? (:outputTokensPerSecond body)))
          (is (false? (:incomplete body))))))))

(deftest the-endpoint-says-not-here-for-a-session-that-has-no-log
  (with-server "stats-no-log" [{:content "unused"}]
    (fn [port]
      (let [[status body] (get-json port "/api/threads/never-ran/stats")]
        (is (= 404 status))
        (is (= "never-ran" (:threadId body)))
        (is (string? (:error body)) "the locator's own sentence, not a blank 404")))))

(deftest the-endpoint-refuses-a-method-it-does-not-serve
  ;; `stats` is the first GET on this shape; the shape is still closed, and a verb
  ;; nobody serves still falls through to the run endpoint rather than being
  ;; answered 405 by a route that was never about it.
  (with-server "stats-methods" [{:content "unused"}]
    (fn [port]
      (let [req  (-> (HttpRequest/newBuilder
                      (URI/create (str "http://127.0.0.1:" port "/api/threads/stats-methods/rebuild")))
                     (.GET) (.build))
            resp (.send (HttpClient/newHttpClient) req
                        (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
        (is (= 405 (.statusCode resp)) "GET rebuild is still not a thing")))))
