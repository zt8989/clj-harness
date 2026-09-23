(ns harness.edge.pressure-test
  "The pressure meter, asserted over HAND-WRITTEN RECORDS.

  Same discipline as harness.edge.context-test, and for the same reason: the cases that
  matter -- a vendor that went silent, a prompt the estimator underprices, an envelope
  that changed between two calls -- are exactly the ones a real run will not produce on
  request. So the fold is pinned with records this file writes.

  WHAT IS ASSERTABLE IS THE ANCHORING, not the token count. The estimate is an estimate,
  so the interesting statements are: the vendor's number is what the meter rests on, only
  the DELTA is estimated, the anchor is dropped when the prompt it measured is no longer
  the prompt being sent, and the numbers that need a window are absent when nobody
  declared one."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.providers :as providers]
            [harness.edge.http :as http]
            [harness.edge.pressure :as pressure]
            [harness.edge.replay :as replay]
            [harness.edge.stats :as stats]
            [harness.fake :as fake]
            [harness.infra.home :as home]
            [harness.test-support :as support])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

;; ------------------------------------------------------------------ the records

(defn- record
  "One ROW of the record: `message` and `event` are the two kinds, and a harness fact is
  an `event` carrying a CUSTOM frame named after it (the fixture spelling
  harness.edge.context-test uses)."
  ([ts kind payload] (record ts "r1" kind payload))
  ([ts run-id kind payload]
   (if (= "message" kind)
     {:ts ts :runId run-id :type "message" :payload payload}
     {:ts ts :runId run-id :type "event"
      :payload (if (= "event" kind) payload {:type "CUSTOM" :name kind :value payload})})))

(defn- sys
  "The system message as the record holds it: a `message` row with `:source` =
  `system-prompt` on the envelope."
  [ts text]
  (assoc (record ts "message" {:role "system" :content text})
         :source "system-prompt" :hash "h"))

(defn- entry
  "A message the CLIENT sent: a `message` row whose envelope carries the id and the
  `:source` a conversation entry is recognised by."
  [ts id text]
  (assoc (record ts "message" {:role "user" :content text})
         :source "client" :id id))

(defn- start
  ([ts] (start ts nil nil))
  ([ts window tools]
   (record ts "model/start"
           (cond-> {:model "scripted"}
             (some? window) (assoc :context-window window)
             (seq tools)    (assoc :tools tools)))))

(defn- end [ts usage-map]
  (record ts "model/end" (if (nil? usage-map) {} {:usage usage-map})))

(defn- usage [prompt completion]
  {:prompt_tokens prompt :completion_tokens completion :total_tokens (+ prompt completion)})

(def ^:private finished (record 900 "event" {:type "RUN_FINISHED" :threadId "t" :runId "r1"}))

(defn- tool-table [n]
  [{:type "function"
    :function {:name "read"
               :description (apply str (repeat n "d"))
               :parameters {:type "object" :properties {}}}}])

(defn- pressure-of [records] (pressure/records->pressure (vec records)))

;; ------------------------------------------------------------------ the anchoring

(deftest the-meter-anchors-on-the-vendor-and-estimates-only-the-delta
  (let [records [(entry 0 "u1" "hi")
                 (sys 1 "you are a coding agent")
                 (start 10 1000 (tool-table 40))
                 (end 20 (usage 500 10))
                 finished
                 ;; the next turn's message is already in the record; no call yet
                 (entry 101 "u2" "a question the vendor has never counted")
                 (sys 100 "you are a coding agent")]
        answer  (pressure-of records)
        delta   (pressure/estimate-message {:role "user" :content "a question the vendor has never counted"})]
    (is (= "usage" (:baseline answer)) "the vendor's sample is the anchor, not the estimate")
    (is (= (+ 500 delta) (:pressureTokens answer))
        "500 the vendor measured, plus only what has been added since")
    (is (= 1000 (:windowTokens answer)))
    (is (= 700 (:thresholdTokens answer)) "seven tenths of the window")
    (is (= 160 (:retainTokens answer)) "sixteen hundredths of it")
    (is (= (long (Math/round (* 100.0 (/ (double (+ 500 delta)) 1000.0))))
           (:percent answer)))))

(deftest the-vendor-measured-the-whole-prompt-not-just-the-new-message
  ;; THE POINT OF THE ANCHOR. A second call in the same turn is sent everything the
  ;; first was, so its meter must not re-count the history in characters -- the vendor
  ;; counted it exactly, and the estimate is only along for the ride.
  (let [records [(entry 0 "u1" "hi")
                 (sys 1 "s")
                 (start 10 1000 nil)
                 (end 20 (usage 400 5))
                 finished]
        one     (pressure-of records)
        grown   (pressure/records->pressure
                 (vec records)
                 [{:role "system" :content "s"}
                  {:role "user" :content "hi"}
                  {:role "user" :content (apply str (repeat 4000 "z"))}])]
    (is (= 400 (:pressureTokens one)) "nothing added since: exactly the vendor's number")
    (is (= "usage" (:baseline grown)))
    (is (= (+ 400 (pressure/estimate-message {:role "user" :content (apply str (repeat 4000 "z"))}))
           (:pressureTokens grown))
        "the assembled request's new piece is the only thing estimated")))

(deftest a-runs-own-injections-are-priced-on-both-sides
  ;; The pre-LLM step derives blocks -- a skill body, a job's ending, the run's own
  ;; context -- as user messages with NO id, so `harness.edge.replay/entries` drops them.
  ;; They are still in the array the call was handed, so the anchor and the current surface
  ;; must BOTH include them, or the injection surface is counted as delta on every run.
  (let [injection (assoc (record 2 "message" {:role "user" :content (apply str (repeat 400 "i"))})
                         :source "injection")
        records   [(entry 0 "u1" "hi")
                   (sys 1 "s")
                   injection
                   (start 10 1000 nil)
                   (end 20 (usage 5000 5))
                   finished]
        answer    (pressure-of records)]
    (is (= "usage" (:baseline answer)))
    (is (= 5000 (:pressureTokens answer))
        "the injection is in the anchor's price too, so it is not re-counted as delta")))

;; --------------------------------------------------------------- the fallbacks

(deftest a-window-nobody-declared-leaves-the-derived-numbers-out
  (let [answer (pressure-of [(entry 0 "u1" "hi")
                             (sys 1 "s")
                             (start 10 nil nil)
                             (end 20 (usage 500 5))
                             finished])]
    (is (= 500 (:pressureTokens answer)))
    (is (= "usage" (:baseline answer)))
    (is (not (contains? answer :windowTokens)))
    (is (not (contains? answer :percent)) "a percentage needs both halves")
    (is (not (contains? answer :thresholdTokens)))
    (is (not (contains? answer :retainTokens)))))

(deftest a-changed-envelope-drops-the-anchor
  (testing "the system message is not the one the vendor measured"
    (let [records [(entry 0 "u1" "hi")
                   (sys 1 "the old prompt")
                   (start 10 1000 nil)
                   (end 20 (usage 900 5))
                   finished
                   (sys 100 "a NEW prompt")
                   (entry 101 "u2" "more")]]
      (is (= "estimated" (:baseline (pressure-of records)))
          "a different envelope is a different prompt; the old total is not about it")))

  (testing "the tool table changed"
    (let [records [(entry 0 "u1" "hi")
                   (sys 1 "s")
                   (start 10 1000 (tool-table 10))
                   (end 20 (usage 900 5))
                   finished
                   (entry 101 "u2" "more")
                   (start 110 1000 (tool-table 40))]]
      (is (= "estimated" (:baseline (pressure-of records))))))

  (testing "the route changed"
    (let [records [(entry 0 "u1" "hi")
                   (sys 1 "s")
                   (start 10 1000 nil)
                   (end 20 (usage 900 5))
                   finished
                   (entry 101 "u2" "more")
                   (record 110 "model/start" {:model "another-model"})]]
      (is (= "estimated" (:baseline (pressure-of records)))))))

(deftest a-vendor-number-below-the-estimate-is-not-a-sample
  ;; An estimator that UNDERCOUNTS CJK is the expected case, and the anchor is adopted
  ;; exactly then. The guard is the other direction: a number below the estimator's own
  ;; reading is not a measurement of THIS prompt, so it anchors nothing.
  (let [records [(entry 0 "u1" (apply str (repeat 400 "x")))
                 (sys 1 "s")
                 (start 10 1000 nil)
                 (end 20 (usage 1 1))
                 finished]]
    (is (= "estimated" (:baseline (pressure-of records)))
        "the vendor said 1 token for a 400-character prompt")))

;; ----------------------------------------------------------------- the CJK bias

(deftest a-chinese-prompt-is-underpriced-and-the-anchor-buries-the-error
  (let [chinese  (apply str (repeat 2000 "汉"))
        records  [(entry 0 "u1" chinese)
                  (sys 1 "s")
                  (start 10 100000 nil)
                  (end 20 (usage 1500 10))
                  finished]
        estimate (pressure/estimate-messages [{:role "user" :content chinese}])
        answer   (pressure-of records)]
    (is (< estimate 800)
        "the estimator counts 2000 characters as about 500 tokens -- the 4-to-1 rule")
    (is (= "usage" (:baseline answer))
        "the vendor's 1500 is what the meter rests on; the estimate never sees the history")
    (is (> (:pressureTokens answer) 1000)
        "so the meter reports the vendor's number, not the four-to-one-low estimate")))

(deftest measure-with-an-assembled-request-prices-what-the-record-cannot-see
  ;; THE LIVE SEAM: a run's request is assembled before it is written, so a trigger has
  ;; to be able to ask about it. The record supplies the anchor; the caller supplies the
  ;; surface. Same fold, one argument more.
  (let [records [(entry 0 "u1" "hi")
                 (sys 1 "s")
                 (start 10 1000 nil)
                 (end 20 (usage 300 5))
                 finished]
        answer  (pressure/records->pressure
                 (vec records)
                 [{:role "system" :content "s"}
                  {:role "user" :content "hi"}
                  {:role "user" :content "a brand new question"}])]
    (is (= (+ 300 (pressure/estimate-message {:role "user" :content "a brand new question"}))
           (:pressureTokens answer)))))

(deftest a-log-that-does-not-exist-yet-is-a-session-with-no-calls-behind-it
  (let [answer (pressure/log-pressure (java.io.File. "/no/such/log.jsonl")
                                      [{:role "system" :content "s"}
                                       {:role "user" :content "hi"}])]
    (is (= "estimated" (:baseline answer)))
    (is (= (pressure/estimate-messages [{:role "system" :content "s"}
                                        {:role "user" :content "hi"}])
           (:pressureTokens answer)))
    (is (not (contains? answer :windowTokens)))))

;; ----------------------------------------------------------------- the endpoint

(defn- with-server [thread-id turns f]
  (providers/use-provider! thread-id (fake/scripted turns))
  (support/start-session! thread-id)
  (let [stop (http/start! {:port 0})]
    (try
      (f (:local-port (meta stop)))
      (finally
        (stop)
        (providers/use-provider! thread-id nil)))))

(defn- send-run! [port thread-id]
  (let [body (json/write-str {:threadId thread-id
                              :append [{:id "u1" :role "user" :content "看看这个项目"}]
                              :tools []})
        req  (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port "/api/agent")))
                 (.header "Content-Type" "application/json")
                 (.header "Accept" "text/event-stream")
                 (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8))
                 (.build))]
    (.body (.send (HttpClient/newHttpClient) req
                  (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8)))))

(defn- get-json [port path]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                (.GET) (.build))
        resp (.send (HttpClient/newHttpClient) req
                    (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
    [(.statusCode resp) (json/read-str (.body resp) :key-fn keyword)]))

(defn- stats-until-pressure
  [port thread-id]
  (loop [tries 0]
    (let [answer (get-json port (str "/api/threads/" thread-id "/stats"))]
      (if (or (= "usage" (get-in answer [1 :pressure :baseline])) (>= tries 120))
        answer
        (do (Thread/sleep 25) (recur (inc tries)))))))

(deftest the-endpoint-answers-the-pressure-section-and-the-run-leaves-it-on-the-record
  ;; THE WHOLE PATH ONCE: a real run writes the log, the route folds it into a
  ;; `:pressure` section, and the meter is ALSO on the record, so a later reader (a
  ;; compaction trigger, a person) can see what the harness thought before the call.
  (let [thread-id "pressure-endpoint"]
    (with-server thread-id
      [{:content "done" :usage {:prompt_tokens 50000 :completion_tokens 8 :total_tokens 50008}}]
      (fn [port]
        (send-run! port thread-id)
        (let [[status body] (stats-until-pressure port thread-id)
              p (:pressure body)]
          (is (= 200 status))
          (is (= "usage" (:baseline p)) "the vendor's number anchored the meter")
          (is (= 128000 (:windowTokens p)) "the window the scripted provider declared")
          (is (= 89600 (:thresholdTokens p)) "seven tenths of it")
          (is (= 20480 (:retainTokens p)) "sixteen hundredths of it")
          (is (>= (:pressureTokens p) 50000)
              "the vendor's 50000 anchors it, plus whatever the run added since")
          (let [line (->> (stats/read-records (replay/locate (home/projects-dir) thread-id))
                          (filter #(= "context/pressure" (replay/kind %)))
                          last
                          replay/payload)]
            (is (some? line) "every run leaves a context/pressure line before its first call")
            (is (= 89600 (:thresholdTokens line)) "carrying the threshold it decided on")
            (is (= 20480 (:retainTokens line)) "and the retain budget")
            (is (= "estimated" (:baseline line))
                "written BEFORE the call, so there is no vendor sample behind it yet")))))))
