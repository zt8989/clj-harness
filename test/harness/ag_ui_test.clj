(ns harness.ag-ui-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness.ag-ui :as ag]
            [harness.event :as ev]
            [harness.fake :as fake]
            [harness.loop :as loop]))

(defn- wire [events]
  (let [emit (ag/outbound "thr-1" "run-1")]
    (vec (mapcat emit events))))

(defn- types [frames] (mapv :type frames))

(defn- violations
  "Every structural rule an AG-UI client enforces, checked across a whole stream."
  [frames]
  (let [open (atom {})
        bad  (atom [])]
    (doseq [f frames]
      (let [t  (:type f)
            id (or (:messageId f) (:toolCallId f))]
        (cond
          (str/ends-with? t "_CHUNK")
          (swap! bad conj (str "chunk event emitted: " t))

          (contains? #{"TEXT_MESSAGE_START" "REASONING_MESSAGE_START"} t)
          (if (contains? @open id)
            (swap! bad conj (str "double START for " id))
            (swap! open assoc id :message))

          (contains? #{"TEXT_MESSAGE_CONTENT" "REASONING_MESSAGE_CONTENT"} t)
          (when-not (contains? @open (:messageId f))
            (swap! bad conj (str "CONTENT for unopened " (:messageId f))))

          (contains? #{"TEXT_MESSAGE_END" "REASONING_MESSAGE_END"} t)
          (if (contains? @open (:messageId f))
            (swap! open dissoc (:messageId f))
            (swap! bad conj (str "END for unopened " (:messageId f))))

          (= t "TOOL_CALL_START") (swap! open assoc id :tool)
          (= t "TOOL_CALL_ARGS")
          (when-not (contains? @open id) (swap! bad conj (str "ARGS for unopened " id)))
          (= t "TOOL_CALL_END") (swap! open dissoc id)
          (= t "RUN_FINISHED")
          (when (seq @open) (swap! bad conj (str "left open at finish: " (keys @open))))
          (= t "RUN_ERROR")
          (when (seq @open) (swap! bad conj (str "left open at error: " (keys @open)))))))
    @bad))

(deftest text-only-turn
  (let [frames (wire [(ev/run-start)
                      (ev/text-delta "he") (ev/text-delta "llo")
                      (ev/run-end)])]
    (is (= ["RUN_STARTED" "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT"
            "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END" "RUN_FINISHED"]
           (types frames)))
    (is (empty? (violations frames)))))

(deftest reasoning-group-closes-before-text-opens
  (let [frames (wire [(ev/run-start)
                      (ev/reasoning-delta "想") (ev/reasoning-delta "一下")
                      (ev/text-delta "hi")
                      (ev/run-end)])]
    (is (= ["RUN_STARTED" "REASONING_START" "REASONING_MESSAGE_START"
            "REASONING_MESSAGE_CONTENT" "REASONING_MESSAGE_CONTENT"
            "REASONING_MESSAGE_END" "REASONING_END"
            "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END"
            "RUN_FINISHED"]
           (types frames)))
    (is (empty? (violations frames)))))

(deftest tool-call-with-no-text-still-gets-a-parent
  (let [frames (wire [(ev/run-start)
                      (ev/tool-call "c1" "read" "{}")
                      (ev/tool-result "c1" "file contents" false)
                      (ev/text-delta "done")
                      (ev/run-end)])]
    (is (empty? (violations frames)))
    (testing "an empty text message is opened and closed around the call"
      (is (= ["RUN_STARTED"
              "TEXT_MESSAGE_START" "TEXT_MESSAGE_END"
              "TOOL_CALL_START" "TOOL_CALL_ARGS" "TOOL_CALL_END"
              "TOOL_CALL_RESULT"
              "TEXT_MESSAGE_START" "TEXT_MESSAGE_CONTENT" "TEXT_MESSAGE_END"
              "RUN_FINISHED"]
             (types frames))))
    (testing "the call is parented to that message"
      (let [parent (:parentMessageId (first (filter #(= "TOOL_CALL_START" (:type %)) frames)))]
        (is (= (:messageId (first (filter #(= "TEXT_MESSAGE_START" (:type %)) frames))) parent))))
    (testing "the next turn opens a fresh message id rather than reopening"
      (let [ids (map :messageId (filter #(= "TEXT_MESSAGE_START" (:type %)) frames))]
        (is (= 2 (count ids)))
        (is (= 2 (count (distinct ids))))))))

(deftest an-error-closes-whatever-is-open
  (let [frames (wire [(ev/run-start)
                      (ev/reasoning-delta "x") (ev/text-delta "y")
                      (ev/run-error "boom")])]
    (is (= "RUN_ERROR" (last (types frames))))
    (is (empty? (violations frames)))))

(deftest drives-the-real-loop-offline
  (let [emit   (ag/outbound "thr-9" "run-9")
        frames (atom [])]
    (loop/run! (fake/scripted [{:reasoning "想一下" :content ""
                                :tool-calls [{:id "c1" :name "eval" :arguments {:code "(+ 1 2)"}}]}
                               {:content "等于 3"}])
               []
               #(swap! frames into (emit %)))
    (is (empty? (violations @frames)))
    (is (= "RUN_STARTED" (first (types @frames))))
    (is (= "RUN_FINISHED" (last (types @frames))))
    (testing "reasoning was rendered rather than dropped"
      (is (some #(= "REASONING_MESSAGE_CONTENT" (:type %)) @frames)))
    (testing "the eval tool really ran and its output came back as a tool result"
      (is (some #(and (= "TOOL_CALL_RESULT" (:type %))
                      (str/includes? (str (:content %)) "3"))
                @frames)))))
