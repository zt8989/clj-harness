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
  (:require [harness.edge.replay :as replay]
            [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [harness.cap.providers :as providers]
            [harness.edge.context :as context]
            [harness.edge.pressure :as pressure]
            [harness.edge.http :as http]
            [harness.test-support :as support]
            [harness.fake :as fake])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]))

;; ------------------------------------------------------------------ the records

(defn- record
  "One ROW of the record, as the file spells it and as every reader now sees it: `message`
  and `event` are the two types, `ts`/`runId` ride the envelope, and a harness FACT -- a
  provider change, a tool's three moments, a model call's start and end -- is an `event`
  carrying a CUSTOM frame named after it. KIND is the reader's answer (`replay/kind`):
  `message`, `event`, or that fact's name, which is why a fixture reads the way an
  assertion does."
  ([ts kind payload] (record ts "r1" kind payload))
  ([ts run-id kind payload]
   (if (= "message" kind)
     {:ts ts :runId run-id :type "message" :payload payload}
     {:ts ts :runId run-id :type "event"
      :payload (if (= "event" kind)
                 payload
                 {:type "CUSTOM" :name kind :value payload})})))

(defn- input [ts & msgs]
  (record ts "input" {:threadId "t" :messages (vec msgs)}))

(defn- user [id text] {:id id :role "user" :content text})

(defn- message
  "A `message` row -- one message of the conversation, as the record spells one since
  `.scratch/jsonl-two-kinds` 票 02.

  THE ENVELOPE IS PART OF THE MEANING, and getting it right is what this helper is for. A row
  reaches `harness.edge.replay/entries` only when it carries the source the conversation knows
  it by (`client` / `opening` / `injection`) and -- except for a client's message -- an id.
  THE ROWS THE RUN'S SIDE PRODUCED get into the conversation through the FRAMES instead, which
  is exactly why the ring's conversation bucket is read from the conversation and not from a
  run's own rows (ticket 01 of `.scratch/context-ring`). An assistant row written here stands
  in for what the frames would carry: the fixture wants those bytes in the conversation, and
  the source plus the id is the only thing that puts them there."
  [ts role text]
  (assoc (record ts "message" {:role role :content text})
         :source "client" :id (str "m" ts "-" role)))

(defn- system-prompt
  "The system message as the record holds it (owner, 2026-09-21): a `message` row -- the
  prompt IS the first element of the array the model was handed -- carrying `:source` =
  `system-prompt` and the bytes' `:hash` on its envelope."
  [ts text]
  (assoc (record ts "message" {:role "system" :content text})
         :source "system-prompt" :hash "h"))

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

(defn- context-of
  "RECORDS -> the section the route assembles, with the SIZES the band kept for the chosen call's
  array -- the same two arguments `records->context` takes from `harness.edge.http`. A suite that
  measured that array itself would be the second spelling of the rule this file exists to keep in
  one place."
  [records]
  (let [records (vec records)]
    (context/records->context records
                              (pressure/anchor-sizes (pressure/meter-of-records records)))))

(defn- tokens-of-parts [answer] (map :tokens (:parts answer)))
(defn- keys-of-parts [answer] (map :key (:parts answer)))

;; ------------------------------------------------------------- the three parts

(deftest the-parts-add-up-to-the-vendors-total
  ;; THE INVARIANT THE STACKED BAR RESTS ON. Nobody reports a split, so the three
  ;; parts are this namespace's own division -- and a division that does not reach
  ;; its own total draws a bar that stops short of its own number.
  (let [answer (context-of [(input 0 (user "u1" "hi"))
                            (system-prompt 1 "you are a coding agent")
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
                   (system-prompt 1 system-text)
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
                            (system-prompt 1 "s")
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
                            (system-prompt 1 "s")
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

(deftest a-compactions-own-call-is-not-the-rings-number
  ;; THE NUMBER A PERSON READ AS 'the compaction did not work' (2026-09-28, session
  ;; `86c1c343-…`): a summarizer's request carries A RANGE OF THE CONVERSATION plus an instruction,
  ;; not the conversation -- and it is the newest `model/end` of the run, so it used to become the
  ;; number under the composer one beat after a compaction had folded that very range away (27%
  ;; where the session stood at 66%). `harness.edge.trajectory/own-calls` is the rule that says it
  ;; is not the run's own call, and this is the ring's half of it (the meter's half is
  ;; `pressure_test/a-compactions-own-call-does-not-move-the-anchor`).
  (let [conversation  [(input 0 (user "u1" "hi"))
                       (system-prompt 1 "s")
                       (message 2 "user" "hi")
                       (start 10 1000 (tool-table 40))
                       (end 20 (usage 600 5))]
        ;; A compaction, as the record spells one: three facts around a call the HARNESS wrote for
        ;; itself -- no run id on those two rows, which is the mark.
        summarizer    [(record 30 nil "compaction/start" {:compactionId "c1"})
                       (record 31 nil "model/start" {:model "scripted" :context-window 1000})
                       (record 32 nil "model/end" {:usage (usage 300 5)})
                       (record 33 nil "context/compacted"
                               {:compactionId "c1" :summary "…" :tokens 300
                                :range {:start 0 :end 2}})
                       (record 34 nil "compaction/end" {:compactionId "c1"})
                       finished]]
    (testing "the summarizer's own request is not what the ring measures"
      (let [answer (context-of (concat conversation summarizer))]
        (is (= 600 (:usedTokens answer))
            "the last MEASUREMENT of the conversation, not of the range just folded away")
        (is (= 60 (:percent answer)))))
    (testing "and the next real call moves it, as it always did"
      (let [answer (context-of (concat conversation summarizer
                                      [(start 40 1000 nil) (end 50 (usage 500 5))]))]
        (is (= 500 (:usedTokens answer)))
        (is (= 50 (:percent answer)))))))

(deftest nothing-reported-is-not-zero
  (testing "no call reported a prompt: no number at all"
    (let [answer (context-of [(input 0 (user "u1" "hi"))
                              (system-prompt 1 "s")
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
                              (system-prompt 1 "s")
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
                              (system-prompt 1 "s")
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
                              (system-prompt 1 "s")
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
                              (system-prompt 1 "s")
                              (message 2 "user" "hi")
                              (start 10 nil nil)
                              (end 20 (usage 500 5))
                              finished
                              (record 800 "provider/changed" {:resolved {:context-window 100000}})])]
      (is (not (contains? answer :windowTokens))
          "the timeline line landed AFTER the call, so it is not this call's window")))

  (testing "nobody said a window: the number is there, the percentage is not"
    (let [answer (context-of [(input 0 (user "u1" "hi"))
                              (system-prompt 1 "s")
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
                              (system-prompt 1 "s")
                              (message 2 "user" "hi")
                              (start 10 0 nil)
                              (end 20 (usage 500 5))
                              finished
                              (message 30 "assistant" "x")])]
      (is (= 500 (:usedTokens answer)))
      (is (not (contains? answer :windowTokens))))))

(deftest a-run-that-has-not-finished-reports-its-split-too
  ;; THE SESSION IS BEING READ WHILE IT RUNS, and the ring draws the same three buckets it
  ;; draws for a finished one. It used to withhold them until the run's terminal frame, on the
  ;; reasoning that the message side lands one beat after that frame -- but the frame being
  ;; waited for is the RUN's, not the call's. The array a call was handed is written before it
  ;; goes out, and `harness.edge.pressure`'s anchor keeps it from the `model/start` on.
  ;; Withholding it left the page drawing one arc in the fallback colour (near black in the
  ;; light theme) for the whole length of every long run -- reported from a browser 2026-09-30.
  (let [answer (context-of [(input 0 (user "u1" "hi"))
                            (system-prompt 1 "s")
                            (message 2 "user" "hi")
                            (start 10 1000 nil)
                            (end 20 (usage 500 5))])]
    (is (= 500 (:usedTokens answer)))
    (is (= 1000 (:windowTokens answer)))
    (is (= 50 (:percent answer)))
    (is (= ["system" "tools" "conversation"] (keys-of-parts answer))
        "the split is drawn with no terminal frame anywhere in the log")
    (is (= 500 (reduce + (tokens-of-parts answer)))
        "and it is still the whole of the vendor's number")))

(deftest the-runs-own-terminal-frame-changes-nothing-about-the-split
  ;; The race the other readers also live with: the terminal frame is written and the message
  ;; tail one beat later. Neither is what the split is read from -- it is the array the call was
  ;; handed, snapshotted at that call -- so a log whose run has not ended answers the same
  ;; section a whole one does. Pinning the EQUALITY is the point: a reader that went back to
  ;; counting the run's own rows would answer something different here.
  (let [rows [(input 0 (user "u1" "hi"))
              (system-prompt 1 "s")
              (message 2 "user" "hi")
              (start 10 1000 nil)
              (end 20 (usage 500 5))]]
    (is (= (context-of rows) (context-of (conj rows finished))))
    (is (= 500 (:usedTokens (context-of rows))))))

(deftest the-split-describes-the-run-the-call-belonged-to
  ;; Two turns in one log: the numbers and the parts belong to the SECOND, not to
  ;; some average of the session.
  (let [answer (context-of [(input 0 (user "u1" "first"))
                            (system-prompt 1 "s")
                            (message 2 "user" "first")
                            (start 10 1000 (tool-table 10))
                            (end 20 (usage 100 5))
                            (record 30 "event" {:type "RUN_FINISHED" :threadId "t" :runId "r1"})
                            (message 40 "assistant" "one")
                            (input 5000 (user "u2" "second"))
                            (system-prompt 5001 "s")
                            (message 5002 "user" "second")
                            (start 5010 1000 (tool-table 10))
                            (end 5020 (usage 900 5))
                            (record 5030 "event" {:type "RUN_FINISHED" :threadId "t" :runId "r2"})
                            (message 5040 "assistant" "two")])]
    (is (= 900 (:usedTokens answer)) "the second turn's call")
    (is (= 900 (reduce + (tokens-of-parts answer))))))

(deftest the-conversation-bucket-is-the-whole-conversation
  ;; TICKET 01 OF `.scratch/context-ring`, PINNED. The conversation was counted as 'this run's
  ;; own rows' (`(:submitted run)` + `(:returned run)`); ADR 0002 keeps the history in the
  ;; session and has the client submit only NEW messages, so every earlier turn was missing
  ;; from the bucket -- and `apportion` then handed the vendor's total to the tool table, which
  ;; came out at 68.7% of a prompt it was under 3% of (measured 2026-09-30, session `62f30024-…`).
  ;; The bucket is now the array the call was handed, which IS the whole conversation.
  (let [first-turn (fn [text]
                     [(input 0 (user "u1" text))
                      (system-prompt 1 "s")
                      (message 2 "user" text)
                      (start 10 1000 (tool-table 10))
                      (end 20 (usage 100 5))
                      (record 30 "event" {:type "RUN_FINISHED" :threadId "t" :runId "r1"})
                      (message 40 "assistant" "one")])
        second-turn [(input 5000 (user "u2" "second"))
                     (system-prompt 5001 "s")
                     (message 5002 "user" "second")
                     (start 5010 1000 (tool-table 10))
                     (end 5020 (usage 900 5))]
        conversation (fn [history]
                       (let [answer (context-of (concat (first-turn history) second-turn))]
                         (is (= 900 (reduce + (tokens-of-parts answer)))
                             "the three buckets are still the whole of the vendor's number")
                         (nth (tokens-of-parts answer) 2)))]
    (is (> (conversation (apply str (repeat 4000 "h"))) (conversation "hi"))
        "growing the EARLIER turn grows the second call's conversation bucket")
    (is (> (conversation (apply str (repeat 4000 "h"))) 800)
        "and that history dominates the vendor's 900 -- a bucket of this run's rows alone
         would be a sliver of it, which is what put the tool table at two thirds")))

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
        ;; THE RUN IS READ FROM THE DOWNLINK NOW (`support/mux-run!`): the POST answers an ack,
        ;; the frames arrive on `events.mux`, and this hands back the run's SSE body as before.
        result (support/mux-run! port thread-id body nil)]
    (:body result)))

(defn- get-json [port path]
  (let [req (-> (HttpRequest/newBuilder (URI/create (str "http://127.0.0.1:" port path)))
                (.GET) (.build))
        resp (.send (HttpClient/newHttpClient) req
                    (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))]
    [(.statusCode resp) (json/read-str (.body resp) :key-fn keyword)]))

(defn- stats-until-complete
  "GET THREAD-ID's stats, waiting for the SPLIT to be there, or give up after ~3s and answer
  the last read.

  THE WAIT USED TO BE A REAL RACE. `harness.edge.context` withheld `:parts` for a run with no
  terminal frame, and the returned side was thought to land a beat after that frame -- so this
  case could read the endpoint the instant the SSE body closed and see the vendor's numbers
  with no split beside them (it happened once here while another suite ran in the same shell).
  The split is now the array the chosen call was HANDED, snapshotted at that call, so it is
  there as soon as the call reported. The loop is kept as a guard against a torn read on a
  loaded machine, not because a wait is expected."
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
