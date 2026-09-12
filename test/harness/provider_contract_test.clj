(ns harness.provider-contract-test
  "Provider seam contract. Any provider behind llm/stream! must satisfy
  these to plug into loop/run! without touching loop/tools/event.
  03's new provider passes by calling check! with its own factory."
  (:require [clojure.test :refer [deftest is testing]]
            [harness.fake :as fake]
            [harness.loop :as loop]))

(defn check!
  "MAKE-PROVIDER takes a turns vector and returns a fresh provider.
  A turn is {:reasoning s :content s :tool-calls [{:id :name :arguments}]}."
  [make-provider]
  (let [drive (fn [turns]
                (let [seen (atom [])]
                  {:history (loop/run! (make-provider turns) []
                                       #(swap! seen conj %))
                   :seen @seen}))]
    (testing "verbatim append: reasoning_content and tool_calls survive the round"
      (let [{:keys [history]} (drive [{:reasoning "r" :content "hi"}])]
        (is (= "hi" (:content (last history))))
        (is (= "r" (:reasoning_content (last history))))))
    (testing "serial tool rounds, failures fed back, run continues"
      (let [{:keys [history seen]} (drive [{:content ""
                                            :tool-calls [{:id "c1" :name "no-such-tool"
                                                          :arguments {}}
                                                         {:id "c2" :name "no-such-tool"
                                                          :arguments {}}]}
                                           {:content "done"}])]
        (is (= ["c1" "c2"] (mapv :id (filter #(= :tool/call (:type %)) seen))))
        (is (= ["c1" "c2"] (mapv :tool_call_id
                                 (filter #(= "tool" (:role %)) history))))
        (is (= "done" (:content (last history))))))
    (testing "termination: a turn with no tool calls ends the run"
      (let [{:keys [history seen]} (drive [{:content "bye"}])]
        (is (= 1 (count history)))
        (is (= :run/end (last (mapv :type seen))))))))

(deftest fake-satisfies-provider-contract
  (check! fake/scripted))
