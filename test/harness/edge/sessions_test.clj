(ns harness.edge.sessions-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [harness.edge.ag-ui :as ag]
            [harness.edge.sessions :as sessions]
            [harness.infra.home :as home]
            [harness.kernel.event :as ev]))

;; THE LOG LINES BELOW ARE PRODUCED BY THE REAL EMITTER, not hand-written frames (the
;; same rule harness.edge.replay-test spells out): a log this file invented could encode
;; a frame shape the server never writes, and then the test would pass while the build
;; failed on every real log.

(defn- log-line [m] (json/write-str m))

(defn- input-line [run-id messages]
  (log-line {:ts 1 :runId run-id :kind "input"
             :payload {:threadId "t" :runId run-id :messages messages
                       :tools [] :context []}}))

(defn- event-lines [run-id events]
  (let [emit (ag/outbound "t" run-id)]
    (mapv (fn [frame] (log-line {:ts 2 :runId run-id :kind "event" :payload frame}))
          (vec (mapcat emit events)))))

(def ^:private seed {:id "u1" :role "user" :content "看看这个项目"})

(defn- write-log!
  "A settled conversation for THREAD-ID: one user turn, one answer. `extra` is spliced in
  as more frames before the run ends."
  [thread-id & [extra]]
  (let [f (io/file (home/projects-dir) "sessions-test"
                   (str (home/sanitize thread-id) ".jsonl"))]
    (.mkdirs (.getParentFile f))
    (spit f (str (str/join "\n"
                           (concat [(input-line "r1" [seed])]
                                   (event-lines "r1" (concat [(ev/run-start)]
                                                             extra
                                                             [(ev/text-delta "这是一个 Clojure 项目。")
                                                              (ev/run-end)]))))
                "\n")
          :encoding "UTF-8")
    f))

(defn- forget-everything! []
  (doseq [tid (keys (sessions/live))] (sessions/drop! tid)))

(use-fixtures :each (fn [f]
                      (forget-everything!)
                      (sessions/watch-unflushed! (constantly false))
                      (try (f) (finally (forget-everything!)
                                 (sessions/watch-unflushed! (constantly false))))))

;; ------------------------------------------------------------------ what it holds

(deftest a-thread-that-never-ran-has-an-empty-conversation
  (testing "no log is not an error -- a page opening a session yet to run gets nothing"
    (is (= [] (sessions/messages "never-ran"))))
  (testing "and asking for it is what puts it in the table"
    (is (contains? (sessions/live) "never-ran"))))

(deftest a-session-is-built-once-from-the-record
  (write-log! "t-build")
  (let [built (sessions/messages "t-build")]
    (testing "the conversation is the seed plus what the frames folded into"
      (is (= ["user" "assistant"] (mapv :role built)))
      (is (= seed (first built)))
      (is (= "这是一个 Clojure 项目。" (:content (second built)))))
    (testing "and the second ask is answered from memory, byte for byte"
      (is (= built (sessions/messages "t-build"))))))

(deftest putting-one-away-and-building-it-again-gives-the-same-bytes
  (write-log! "t-again")
  (let [built (sessions/messages "t-again")]
    (sessions/drop! "t-again")
    (is (not (contains? (sessions/live) "t-again"))
        "the drop is the reason the next ask has to read the record")
    (is (= built (sessions/messages "t-again"))
        "ONE ASSERTION, and it is the joint between 'the log is still the record' and
         'memory is the authority': the fold has to land on the same bytes or putting
         a session away would be a silent edit")))

(deftest the-cards-are-not-part-of-the-conversation
  ;; A card is a message the log carries so the screen can draw it again. It is not
  ;; something the model read, and handing one to a provider is a named refusal
  ;; (`harness.edge.ag-ui/provider-part` has no case for a `data` part) -- which is
  ;; exactly the failure this keeps from happening.
  (write-log! "t-cards" [(ev/context-injected {:role "user"
                                               :content "<instructions path=\"AGENTS.md\">规矩</instructions>"})])
  (let [built (sessions/messages "t-cards")]
    (testing "the conversation is still just the user turn and the answer"
      (is (= ["user" "assistant"] (mapv :role built))))
    (testing "no message carries a data part"
      (is (not-any? (fn [m] (some #(= "data" (:type %)) (:content m))) built)))
    (testing "and the injected bytes are nowhere in it"
      (is (not-any? #(str/includes? (str (:content %)) "规矩") built)))))

;; ------------------------------------------------------------------- its lifetime

(deftest an-idle-session-is-put-away
  (sessions/touch! "t-idle")
  (let [touched (:touched-at (get (sessions/live) "t-idle"))]
    (testing "not yet -- a hair under the TTL it stays"
      (is (= [] (sessions/sweep! (+ touched (dec sessions/idle-ttl-ms)))))
      (is (contains? (sessions/live) "t-idle")))
    (testing "and at the TTL it goes, and the sweep says which one"
      (is (= ["t-idle"] (sessions/sweep! (+ touched sessions/idle-ttl-ms))))
      (is (not (contains? (sessions/live) "t-idle"))))))

(deftest a-session-with-a-run-going-is-not-put-away
  (sessions/run-started! "t-running" "r1")
  (let [touched (:touched-at (get (sessions/live) "t-running"))
        later   (+ touched (* 100 sessions/idle-ttl-ms))]
    (testing "however long it has been going, the session stays"
      (is (= [] (sessions/sweep! later)))
      (is (contains? (sessions/live) "t-running")))
    (testing "and the idle clock starts again when the run ends"
      (sessions/run-finished! "t-running" "r1")
      (let [ended (:touched-at (get (sessions/live) "t-running"))]
        (is (= [] (sessions/sweep! (+ ended (dec sessions/idle-ttl-ms))))
            "the run's END is the touch: a long run must not be eligible the instant it
             stops, and `later` is 100 TTLs past `touched`")
        (is (= ["t-running"] (sessions/sweep! (+ ended sessions/idle-ttl-ms))))))))

(deftest two-runs-of-one-session-and-only-one-of-them-ends
  ;; Two runs of one session is .scratch/session-after-refresh ticket 05's refusal to
  ;; make. Until it lands the table must still be right about the fact it owns.
  (sessions/run-started! "t-two" "r1")
  (sessions/run-started! "t-two" "r2")
  (sessions/run-finished! "t-two" "r1")
  (let [touched (:touched-at (get (sessions/live) "t-two"))]
    (is (= #{"r2"} (:runs (get (sessions/live) "t-two"))))
    (is (= [] (sessions/sweep! (+ touched (* 100 sessions/idle-ttl-ms))))
        "r1 ending must not unpin a session r2 is still running")))

(deftest bytes-that-have-not-reached-the-record-hold-a-session
  (sessions/touch! "t-pending")
  (sessions/watch-unflushed! #(= % "t-pending"))
  (let [touched (:touched-at (get (sessions/live) "t-pending"))
        later   (+ touched (* 100 sessions/idle-ttl-ms))]
    (testing "putting it away would mean rebuilding from a record behind it, silently"
      (is (= [] (sessions/sweep! later)))
      (is (contains? (sessions/live) "t-pending")))
    (testing "and once the bytes land it goes"
      (sessions/watch-unflushed! (constantly false))
      (is (= ["t-pending"] (sessions/sweep! later))))))

;; ------------------------------------------------------------ the running ceiling

(deftest the-running-ceiling-is-refused-by-name
  (dotimes [i sessions/max-running]
    (sessions/run-started! (str "t-limit-" i) (str "r" i)))
  (is (= sessions/max-running (sessions/running-count)))
  (testing "one more is a named refusal, not a queue"
    (let [e (try (sessions/run-started! "t-limit-over" "r-over") nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "the run does not start")
      (is (= :too-many-running-sessions (:reason (ex-data e))))
      (is (= sessions/max-running (:limit (ex-data e))))
      (is (str/includes? (ex-message e) (str sessions/max-running))
          "the sentence names the number, because that is the thing to argue with")))
  (testing "and it is not counted, nor pinned"
    (is (= sessions/max-running (sessions/running-count)))
    (is (not (contains? (sessions/live) "t-limit-over"))))
  (testing "a run the table already holds is not a second one"
    (sessions/run-started! "t-limit-0" "r0")
    (is (= sessions/max-running (sessions/running-count)))))

;; ------------------------------------------------------------- and it writes nothing

(deftest asking-for-a-session-writes-nothing
  (let [f        (write-log! "t-quiet")
        before   [(.length f) (.lastModified f)]
        fileseq  #(vec (sort (map str (file-seq (home/projects-dir)))))]
    (let [files-before (fileseq)]
      (sessions/messages "t-quiet")
      (sessions/touch! "t-never-ran")
      (sessions/run-started! "t-quiet" "r1")
      (sessions/run-finished! "t-quiet" "r1")
      (sessions/sweep! (System/currentTimeMillis))
      (testing "the log it read is the log it left"
        (is (= before [(.length f) (.lastModified f)])))
      (testing "and no file appeared anywhere under the log tree"
        (is (= files-before (fileseq)))))))

;; ------------------------------------------------------------------ the sweeper

(deftest the-sweeper-is-started-once
  (let [stop  (sessions/start!)
        stop2 (sessions/start!)]
    (try
      (is (fn? stop))
      (is (fn? stop2) "a second start answers a stop fn rather than a second clock")
      (finally (stop) (stop2)))))
