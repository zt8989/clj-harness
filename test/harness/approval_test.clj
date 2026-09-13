(ns harness.approval-test
  "Pre-tool human approval, kernel half: a marked call parks in the seam and the
  run ends on an interrupt instead of running the tool."
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [harness.ag-ui :as ag]
            [harness.fake :as fake]
            [harness.loop :as loop]
            [harness.memory :as mem]
            [harness.tools :as tools]
            [harness.wire :as wire]))

(def ^:private dir (str (System/getProperty "java.io.tmpdir") "/harness-approval-test"))

(defn- drain [ch]
  (loop [acc []]
    (if-let [ev (async/<!! ch)]
      (if (= :run/done (:type ev))
        {:history (:history ev) :seen acc}
        (recur (conj acc ev)))
      {:history nil :seen acc})))

(def ^:private lifecycle #{:tool/pre-execute :tool/execute :tool/post-execute})

(defn- phases [seen] (filterv #(contains? lifecycle (:type %)) seen))
(defn- results [seen] (filterv #(= :tool/result (:type %)) seen))
(defn- tool-msgs [history] (filterv #(= "tool" (:role %)) history))
(defn- run [provider messages thread-id]
  (drain (loop/run-chan provider messages {:thread-id thread-id})))

(defn- call [id name arguments]
  {:id id :name name :arguments arguments})

(deftest a-marked-call-parks-instead-of-running
  (let [thr  "thr-park-1"
        path (str dir "/parked.txt")
        _    (io/delete-file path true)
        _    (mem/session-require-approval! thr "write")
        {:keys [seen history]}
        (run (fake/scripted [{:content ""
                              :tool-calls [(call "c1" "write" {:path path :content "hello"})]}
                             {:content "done"}])
             [] thr)
        term (last seen)]

    (testing "the run ends on an interrupt, never on a run end"
      (is (= :run/interrupt (:type term)))
      (is (not-any? #(= :run/end (:type %)) seen)))

    (testing "the tool never ran and nothing was reported as its result"
      (is (false? (.exists (io/file path))))
      (is (empty? (results seen))))

    (testing "the seam parked at pre and closed its transit at post, no execute phase"
      (is (= [:tool/pre-execute :tool/post-execute] (mapv :type (phases seen))))
      (is (= :needs-approval (:outcome (first (phases seen))))))

    (testing "the interrupt names the call, and memory holds the parked record"
      (let [[int] (:interrupts term)]
        (is (= "c1" (:tool-call-id int)))
        (is (= "write" (:name int)))
        (is (string? (:id int)))
        (is (= thr (:thread-id (mem/parked (:id int)))))
        (is (= "c1" (:tool-call-id (mem/parked (:id int)))))
        (is (= 1 (count (mem/parked-calls thr))))))

    (testing "the parked call is left unanswered in the history"
      (is (some #(seq (:tool_calls %)) history))
      (is (empty? (tool-msgs history))))))

(deftest a-tool-can-declare-its-own-approval-requirement
  (let [thr "thr-park-flag"
        ran (atom false)]
    (mem/session-register! thr "probe"
      {:description "A probe." :requires-approval true
       :parameters {:type "object" :properties {} :required []} :required []
       :run (fn [_] (reset! ran true) "ran")})

    (testing "the flag is a harness concern, not part of the provider's tool schema"
      (let [spec (first (filter #(= "probe" (get-in % [:function :name]))
                                (tools/specs thr)))]
        (is (= #{:name :description :parameters} (set (keys (:function spec)))))))

    (testing "a declared requirement parks the call without a session opt-in"
      (let [{:keys [seen]} (run (fake/scripted [{:content ""
                                                 :tool-calls [(call "c1" "probe" {})]}
                                                {:content "done"}])
                                [] thr)]
        (is (= :run/interrupt (:type (last seen))))
        (is (false? @ran))))))

(deftest unmarked-tools-are-untouched
  (let [{:keys [seen history]}
        (run (fake/scripted [{:content ""
                              :tool-calls [(call "c1" "read" {:path "deps.edn"})]}
                             {:content "done"}])
             [] "thr-plain")]
    (is (= ["c1"] (mapv :id (results seen))))
    (is (= :run/end (:type (last seen))))
    (is (= 1 (count (tool-msgs history))))))

(deftest a-mixed-turn-parks-only-the-marked-call
  (let [thr  "thr-park-mixed"
        path (str dir "/mixed.txt")
        _    (io/delete-file path true)
        _    (mem/session-require-approval! thr "write")
        {:keys [seen history]}
        (run (fake/scripted [{:content ""
                              :tool-calls [(call "c1" "read" {:path "deps.edn"})
                                           (call "c2" "write" {:path path :content "x"})]}
                             {:content "done"}])
             [] thr)]
    (testing "the unmarked call of the same turn still runs"
      (is (= ["c1"] (mapv :id (results seen))))
      (is (= "c1" (:tool_call_id (first (tool-msgs history))))))

    (testing "the marked one parks: unanswered, and the file was never written"
      (is (= :run/interrupt (:type (last seen))))
      (is (= ["c2"] (mapv :tool-call-id (:interrupts (last seen)))))
      (is (false? (.exists (io/file path))))
      (is (= 1 (count (tool-msgs history)))))))

(deftest approval-is-session-scoped
  (mem/session-require-approval! "thr-a" "read")
  (is (true? (mem/session-approval-required? "thr-a" "read")))
  (is (false? (mem/session-approval-required? "thr-b" "read")))
  (let [{:keys [seen]}
        (run (fake/scripted [{:content ""
                              :tool-calls [(call "c1" "read" {:path "deps.edn"})]}
                             {:content "done"}])
             [] "thr-b")]
    (is (= :run/end (:type (last seen))))))

(deftest a-parked-run-reaches-the-wire-as-an-interrupt
  (let [thr    "thr-park-wire"
        emit   (ag/outbound thr "run-w")
        frames (atom [])
        _      (mem/session-require-approval! thr "read")]
    (doseq [event (:seen (run (fake/scripted [{:content ""
                                               :tool-calls [(call "c1" "read" {:path "deps.edn"})]}])
                              [] thr))]
      (swap! frames into (emit event)))
    (let [last-f (last @frames)]
      (is (empty? (wire/violations @frames)))
      (is (= "RUN_FINISHED" (:type last-f)))
      (is (= "interrupt" (get-in last-f [:outcome :type])))
      (is (= ["c1"] (mapv :toolCallId (get-in last-f [:outcome :interrupts]))))
      (is (not-any? #(= "TOOL_CALL_RESULT" (:type %)) @frames)))))

(deftest unknown-interrupts-are-not-invented
  (is (nil? (mem/parked "no-such-interrupt")))
  (is (empty? (mem/parked-calls "thr-that-never-parked"))))
