(ns harness.loop-test
  (:require [clojure.test :refer [deftest is testing]]
            [harness.fake :as fake]
            [harness.loop :as loop]))

(defn- drive [provider messages]
  (let [seen   (atom [])
        result (loop/run! provider messages #(swap! seen conj %))]
    {:history result :seen @seen}))

(defn- joined [seen type]
  (apply str (map :text (filter #(= type (:type %)) seen))))

(deftest text-only-run
  (let [{:keys [history seen]}
        (drive (fake/scripted [{:reasoning "thinking..." :content "hello world"}]) [])]
    (testing "streams reasoning then text, then ends"
      (is (= [:run/start :run/end]
             (mapv :type (remove #(#{:reasoning/delta :text/delta} (:type %)) seen))))
      (is (= "thinking..." (joined seen :reasoning/delta)))
      (is (= "hello world" (joined seen :text/delta))))
    (testing "the assistant message is appended verbatim, reasoning_content included"
      (is (= 1 (count history)))
      (is (= "hello world" (:content (last history))))
      (is (= "thinking..." (:reasoning_content (last history)))))))

(deftest tool-turn-runs-serially-and-feeds-failures-back
  (let [{:keys [history seen]}
        (drive (fake/scripted [{:content ""
                                :tool-calls [{:id "c1" :name "no-such-tool" :arguments {}}]}
                               {:content "done"}])
               [])]
    (testing "one serial tool round, then a final answer"
      (is (= [:run/start :tool/call :tool/result :text/delta :run/end]
             (mapv :type seen))))
    (testing "the call is fully accumulated before it is emitted"
      (is (= "{}" (:args (first (filter #(= :tool/call (:type %)) seen))))))
    (testing "a failing tool does not end the run -- it is fed back as the result"
      (is (true? (:error (first (filter #(= :tool/result (:type %)) seen)))))
      (is (= {:role "tool" :tool_call_id "c1" :content "unknown tool: no-such-tool"}
             (second history))))
    (testing "the run continues to a final answer"
      (is (= "done" (:content (last history)))))))

(deftest multiple-tool-calls-run-serially-in-order
  (let [{:keys [history seen]}
        (drive (fake/scripted [{:content ""
                                :tool-calls [{:id "c1" :name "no-such-tool" :arguments {}}
                                             {:id "c2" :name "no-such-tool" :arguments {}}]}
                               {:content "done"}])
               [])]
    (is (= ["c1" "c2"] (mapv :id (filter #(= :tool/result (:type %)) seen))))
    (is (= ["c1" "c2"] (mapv :tool_call_id (filter #(= "tool" (:role %)) history))))))

(deftest transport-failure-ends-the-run
  (let [{:keys [seen]}
        (drive {:protocol :explodes} [])]
    (is (= [:run/start :run/error] (mapv :type seen)))
    (is (string? (:message (last seen))))))
