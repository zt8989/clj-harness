(ns harness.edge.context-test
  "The composer's context ring, asserted over HAND-WRITTEN RECORDS.

  Same discipline as harness.edge.stats-test, and for the same reason: the
  interesting cases -- a call whose vendor reported nothing, a model nobody gave a
  window, a log written before the window was recorded on the call, a run still
  going -- are exactly the ones a real run will not produce on request. So the fold
  is pinned with records this file writes.

  WHAT THE SPLIT CAN AND CANNOT BE ASSERTED AS. The three parts are an estimate, so
  there is no vendor number to compare them to; what is assertable is what makes
  them honest -- they ADD UP to the vendor's total, they MOVE with the bytes of the
  part they describe, and they are ABSENT when the record does not describe the
  prompt. Those are the three groups below."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.providers :as providers]
            [harness.edge.context :as context]
            [harness.edge.http :as http]
            [harness.test-support :as support]
            [harness.fake :as fake])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

;; ------------------------------------------------------------------ the records

(defn- record
  "One jsonl record: {:ts ms, :runId s, :kind s, :payload v}, as the edge writes it."
  ([ts kind payload] (record ts "r1" kind payload))
  ([ts run-id kind payload] {:ts ts :runId run-id :kind kind :payload payload}))

(defn- input [ts & msgs]
  (record ts "input" {:threadId "t" :messages (vec msgs)}))

(defn- user [id text] {:id id :role "user" :content text})

(defn- message [ts role text]
  (record ts "message" {:role role :content text}))

(defn- start
  "A `model/start` line. WINDOW and TOOLS are written only when the test has one --
  the event's own rule: a key that is not there is not a null."
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
  "A tool table of roughly N characters -- one function spec, described the way the
  wire describes them."
  [(into {:type "function"}
         {:function {:name "read"
                     :description (apply str (repeat n "d"))
                     :parameters {:type "object" :properties {}}}})])

(defn- context-of [records] (context/records->context (vec records)))

(defn- tokens-of-parts [answer] (map :tokens (:parts answer)))
(defn- keys-of-parts [answer] (map :key (:parts answer)))

;; ------------------------------------------------------------- the three parts

(deftest the-parts-add-up-to-the-vendors-total
  ;; THE INVARIANT THE STACKED BAR RESTS ON. Nobody reports a split, so the three
  ;; parts are this namespace's own division -- and a division that does not reach
  ;; its own total draws a bar that stops short of its own number.
  (let [answer (context-of [(input 0 (user "u1" "hi"))
                            (message 1 "system" "you are a coding agent")
                            (message 2 "user" "hi")
                            (start 10 1000 (tool-table 40))
                            (end 20 (usage 500 10))
                            finished
                            (message 30 "assistant" "hello")])]
    (is (= 500 (:usedTokens answer)))
    (is (= 1000 (:windowTokens answer)))
    (is (= 50 (:percent answer)) "500 of 1000, rounded")
    (is (= ["system" "tools" "conversation"] (keys-of-parts answer))
        "the order the panel draws, and the wire's spelling of an enum-shaped value")
    (is (= 500 (reduce + (tokens-of-parts answer)))
        "the three parts are the whole of :usedTokens, not a share of it")
    (is (every? pos? (tokens-of-parts answer))
        "every bucket is in the prompt: a zero would be a bucket nothing went into")))

(deftest each-part-moves-with-its-own-bytes
  ;; The split is measured, not decoration: grow one part and ITS number moves.
  (let [records (fn [system-text table-size conversation-text]
                  [(input 0 (user "u1" "hi"))
                   (message 1 "system" system-text)
                   (message 2 "user" conversation-text)
                   (start 10 1000 (tool-table table-size))
                   (end 20 (usage 1000 10))
                   finished
                   (message 30 "assistant" conversation-text)])
        [sys _ _]   (tokens-of-parts (context-of (records "short" 10 "hi")))
        [sys' _ _]  (tokens-of-parts (context-of (records (apply str (repeat 400 "s")) 10 "hi")))
        [_ tools _] (tokens-of-parts (context-of (records "short" 10 "hi")))
        [_ tools' _] (tokens-of-parts (context-of (records "short" 400 "hi")))]
    (is (< sys sys') "a longer system message is a bigger share of the same prompt")
    (is (< tools tools') "a longer tool table is a bigger share")))

(deftest the-rounding-drift-lands-on-the-largest-part
  ;; Three parts of a number that does not divide: the sum still lands exactly, and
  ;; the token or two of drift goes to the largest bucket rather than to whichever
  ;; one happened to be last.
  (let [answer (context-of [(input 0 (user "u1" "hi"))
                            (message 1 "system" "s")
                            (message 2 "user" (apply str (repeat 3000 "c")))
                            (start 10 1000 (tool-table 30))
                            (end 20 (usage 1001 10))
                            finished
                            (message 30 "assistant" "x")])]
    (is (= 1001 (reduce + (tokens-of-parts answer)))
        "three parts of a number that does not divide still add up to it")
    (let [[_ _ third] (tokens-of-parts answer)]
      (is (> third 900) "and the conversation -- much the largest bucket -- got the drift"))))

;; ---------------------------------------------------------------- the absences

(deftest a-call-that-reported-nothing-does-not-erase-the-last-measurement
  (let [answer (context-of [(input 0 (user "u1" "hi"))
                            (message 1 "system" "s")
                            (message 2 "user" "hi")
                            (start 10 1000 nil)
                            (end 20 (usage 700 5))
                            ;; a second call whose vendor went silent mid-stream
                            (start 30 1000 nil)
                            (end 40 nil)
                            finished
                            (message 50 "assistant" "x")])]
    (is (= 700 (:usedTokens answer)) "the last MEASUREMENT, not a blank")
    (is (= 70 (:percent answer)))))

(deftest nothing-reported-is-not-zero
  (testing "no call reported a prompt: no number at all"
    (let [answer (context-of [(input 0 (user "u1" "hi"))
                              (message 1 "system" "s")
                              (start 10 1000 nil)
                              (end 20 nil)
                              finished])]
      (is (= {} answer)
          "an empty section -- the ring is not drawn rather than drawn at zero")))

  (testing "a log from before the model lines has no context section either"
    (is (= {} (context-of [(input 0 (user "u1" "hi")) finished])))))

(deftest the-window-comes-from-the-call-itself
  (testing "the call's own line is the primary source"
    (let [answer (context-of [(input 0 (user "u1" "hi"))
                              (message 1 "system" "s")
                              ;; a session that was switched to another model: the
                              ;; timeline says 8000, the call that actually ran says 1000
                              (record 5 "provider/init" {:provider "openrouter" :model "m" :context-window 8000})
                              (start 10 1000 nil)
                              (end 20 (usage 500 5))
                              finished
                              (message 30 "assistant" "x")])]
      (is (= 1000 (:windowTokens answer)) "the call's window, not the timeline's")
      (is (= 50 (:percent answer)))))

  (testing "a log older than that key falls back to the provider timeline"
    ;; A `provider/init` PAYLOAD *IS* THE RESOLUTION, spliced at the top level beside
    ;; :source (harness.edge.http/provider-line, and http_test pins the same shape) --
    ;; not nested under :resolved. A reader that only knew the nested form would answer
    ;; 'this model declared no window' for every session that never changed provider.
    (let [answer (context-of [(record 5 "provider/init" {:provider "kongming"
                                                         :model "m"
                                                         :context-window 2000
                                                         :source "default"})
                              (input 0 (user "u1" "hi"))
                              (message 1 "system" "s")
                              (message 2 "user" "hi")
                              (start 10 nil nil)          ;; written before the key existed
                              (end 20 (usage 500 5))
                              finished
                              (message 30 "assistant" "x")])]
      (is (= 2000 (:windowTokens answer)))
      (is (= 25 (:percent answer)))))

  (testing "and a provider/changed line nests the same fact under :resolved"
    ;; The other shape, from the other writer (harness.edge.http's :resolved). Both are
    ;; the same fact about the same moment, so both are read.
    (let [answer (context-of [(record 5 "provider/changed" {:verdict "approved"
                                                             :resolved {:provider "openrouter"
                                                                        :model "m"
                                                                        :context-window 5000}})
                              (input 0 (user "u1" "hi"))
                              (message 1 "system" "s")
                              (message 2 "user" "hi")
                              (start 10 nil nil)
                              (end 20 (usage 500 5))
                              finished
                              (message 30 "assistant" "x")])]
      (println "DEBUG changed-case answer:" (pr-str answer))
      (is (= 5000 (:windowTokens answer)))
      (is (= 10 (:percent answer)))))

  (testing "a window that moved later in the session is not the one an earlier call ran under"
    (let [answer (context-of [(input 0 (user "u1" "hi"))
                              (message 1 "system" "s")
                              (message 2 "user" "hi")
                              (start 10 nil nil)
                              (end 20 (usage 500 5))
                              finished
                              (record 800 "provider/changed" {:resolved {:context-window 100000}})])]
      (is (not (contains? answer :windowTokens))
          "the timeline line landed AFTER the call, so it is not this call's window")))

  (testing "nobody said a window: the number is there, the percentage is not"
    (let [answer (context-of [(input 0 (user "u1" "hi"))
                              (message 1 "system" "s")
                              (message 2 "user" "hi")
                              (start 10 nil nil)
                              (end 20 (usage 500 5))
                              finished
                              (message 30 "assistant" "x")])]
      (is (= 500 (:usedTokens answer)))
      (is (not (contains? answer :windowTokens)))
      (is (not (contains? answer :percent)) "a percentage needs both halves")))

  (testing "a window no one could divide by is not one -- and is not a crash either"
    (let [answer (context-of [(input 0 (user "u1" "hi"))
                              (message 1 "system" "s")
                              (message 2 "user" "hi")
                              (start 10 0 nil)
                              (end 20 (usage 500 5))
                              finished
                              (message 30 "assistant" "x")])]
      (is (= 500 (:usedTokens answer)))
      (is (not (contains? answer :windowTokens))))))

(deftest a-run-that-has-not-finished-reports-the-numbers-and-no-split
  ;; The session is being read WHILE it runs: the call came back, the split of what
  ;; it was sent cannot be complete yet (the returned side of the message record
  ;; lands one beat after the run's terminal frame).
  (let [answer (context-of [(input 0 (user "u1" "hi"))
                            (message 1 "system" "s")
                            (message 2 "user" "hi")
                            (start 10 1000 nil)
                            (end 20 (usage 500 5))])]
    (is (= 500 (:usedTokens answer)))
    (is (= 1000 (:windowTokens answer)))
    (is (= 50 (:percent answer)))
    (is (not (contains? answer :parts))
        "half a message set would make the conversation look like a small share")))

(deftest a-finished-run-whose-tail-has-not-landed-says-the-same
  ;; The race the other readers also live with: the terminal frame is written and the
  ;; message tail one beat later. Counting only what is on disk would divide the
  ;; prompt by a conversation that has not arrived.
  (let [answer (context-of [(input 0 (user "u1" "hi"))
                            (message 1 "system" "s")
                            (message 2 "user" "hi")
                            (start 10 1000 nil)
                            (end 20 (usage 500 5))
                            finished])]
    (is (= 500 (:usedTokens answer)))
    (is (not (contains? answer :parts)))))

(deftest the-split-describes-the-run-the-call-belonged-to
  ;; Two turns in one log: the numbers and the parts belong to the SECOND, not to
  ;; some average of the session.
  (let [answer (context-of [(input 0 (user "u1" "first"))
                            (message 1 "system" "s")
                            (message 2 "user" "first")
                            (start 10 1000 (tool-table 10))
                            (end 20 (usage 100 5))
                            (record 30 "event" {:type "RUN_FINISHED" :threadId "t" :runId "r1"})
                            (message 40 "assistant" "one")
                            (input 5000 (user "u2" "second"))
                            (message 5001 "system" "s")
                            (message 5002 "user" "second")
                            (start 5010 1000 (tool-table 10))
                            (end 5020 (usage 900 5))
                            (record 5030 "event" {:type "RUN_FINISHED" :threadId "t" :runId "r2"})
                            (message 5040 "assistant" "two")])]
    (is (= 900 (:usedTokens answer)) "the second turn's call")
    (is (= 900 (reduce + (tokens-of-parts answer))))))

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
                              ;; THE ACTION'S OWN ENTRIES (ticket 03), not the
                              ;; conversation: the server holds that.
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

(defn- stats-until-complete
  "GET THREAD-ID's stats until the route reports the SPLIT, or give up after ~3s and
  answer the last read.

  THE RETURNED SIDE LANDS ONE BEAT AFTER THE TERMINAL FRAME. `harness.edge.context`
  turns that into a rule -- a run whose tail has not landed has no parts at all, because
  half a message set would make the conversation look like a small share of a large
  prompt -- and this case reads the endpoint the instant the SSE body closes, so on a
  loaded machine it can read the record one line short of complete and see the vendor's
  numbers with no split beside them. That is what happened once here (the strip's
  numbers arrived, `:parts` was absent) while another suite ran in the same shell.

  So the case waits for the run to be WHOLE, which is the state it is about: what the
  route folds once a real run has finished. 'Absent while the run is still going' is
  pinned on hand-written records above, where it is deterministic."
  [port thread-id]
  (loop [tries 0]
    (let [answer (get-json port (str "/api/threads/" thread-id "/stats"))]
      (if (or (seq (get-in answer [1 :context :parts])) (>= tries 120))
        answer
        (do (Thread/sleep 25) (recur (inc tries)))))))

(deftest the-endpoint-answers-the-context-section-over-real-http
  ;; The whole path once: a real run writes the log, the route folds it, and the
  ;; composer's one GET carries both the strip's numbers and the ring's context.
  (let [thread-id "context-endpoint"]
    (with-server thread-id
      [{:content "" :tool-calls [{:id "c1" :name "no-such-tool" :arguments {}}]
        :usage {:prompt_tokens 1000 :completion_tokens 40 :total_tokens 1040}}
       {:content "done"
        :usage {:prompt_tokens 1200 :completion_tokens 8 :total_tokens 1208}}]
      (fn [port]
        (send-run! port thread-id)
        (let [[status body] (stats-until-complete port thread-id)
              ctx (:context body)]
          (is (= 200 status))
          (is (= 2 (:steps body)) "the strip's numbers are still there beside it")
          (is (= 1200 (:usedTokens ctx)) "the LAST call's prompt, the one that filled the window")
          (is (= 128000 (:windowTokens ctx))
              "the window the scripted provider declared on the call's own line")
          (is (= 1 (:percent ctx)) "1200 of 128000, rounded")
          (is (= ["system" "tools" "conversation"] (map :key (:parts ctx))))
          (is (= 1200 (reduce + (map :tokens (:parts ctx))))
              "the bar reaches the end -- and the total is the vendor's own number")
          (is (pos? (:tokens (first (:parts ctx))))
              "the assembled system prompt is a real share of a real run")
          (is (pos? (:tokens (last (:parts ctx))))
              "and so is the conversation, however short this one was"))))))

(deftest the-endpoint-says-not-here-for-a-session-with-no-log
  ;; The refusal is `stats`' own, which this section rides on: one rule for one shape.
  (with-server "context-no-log" [{:content "unused"}]
    (fn [port]
      (let [[status body] (get-json port "/api/threads/never-ran/stats")]
        (is (= 404 status))
        (is (string? (:error body)))))))
