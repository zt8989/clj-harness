(ns harness.edge.normalized-test
  "「已重整化」的判据，各对着真记录的形状（`.scratch/record-normalization` 票 01）。"
  (:require [clojure.test :refer [deftest is testing]]
            [harness.edge.normalized :as normalized]))

(defn- row [kind payload] {:type kind :payload payload :ts 1 :runId "r1"})
(defn- frame [type payload] (row "event" (assoc payload :type type)))
(defn- message [m id] (assoc (row "message" m) :id id))

(defn- a-normal-record
  "一份**已重整化**的记录：每条消息都夹在它自己的 START/END 之间，每次调用都有答复。
  行形状照真记录（`{:type \"event\" :payload {:type \"TEXT_MESSAGE_START\" ..}}`）。"
  []
  [(frame "RUN_STARTED" {})
   (frame "TEXT_MESSAGE_START" {:messageId "m1"})
   (message {:role "assistant" :content "ok"} "m1")
   (frame "TEXT_MESSAGE_END" {:messageId "m1"})
   (frame "TOOL_CALL_START" {:toolCallId "c1"})
   (message {:role "tool" :tool_call_id "c1" :content "done"} "c1")
   (frame "TOOL_CALL_END" {:toolCallId "c1"})
   (frame "RUN_FINISHED" {})])

(deftest a-normalized-record-is-normalized
  (is (= {:normalized? true :reasons []} (normalized/normalized? (a-normal-record)))))

(deftest an-old-format-record-is-not
  ;; `~/.clj-harness/logs/*.jsonl` 就是这个形状：`kind`，没有 `type`/`payload`。
  (let [old [{:kind "message" :payload {:role "user" :content "hi"}}
             {:kind "event"   :payload {:type "CUSTOM" :name "model/start" :value {}}}]]
    (is (false? (:normalized? (normalized/normalized? old))))
    (is (re-find #"旧格式" (first (:reasons (normalized/normalized? old)))))
    (testing "而且一条没有信封的行就够判它不可续"
      (is (false? (:normalized? (normalized/normalized? (conj (a-normal-record) (first old)))))))))

(deftest a-message-outside-its-envelope-is-not-normalized
  (testing "有身份、但没有 START 宣告它"
    (let [records (conj (a-normal-record) (row "message" {:role "user" :content "naked"}))]
      (is (false? (:normalized? (normalized/normalized? records))))
      (is (re-find #"不在它的 START/END 之间" (first (:reasons (normalized/normalized? records)))))))
  (testing "有 START 却没收尾（END 缺）"
    (let [records (vec (remove #(= "TEXT_MESSAGE_END" (get-in % [:payload :type]))
                               (a-normal-record)))]
      (is (false? (:normalized? (normalized/normalized? records))))))
  (testing "START/END 都在，消息却落在信封之外（END 之后）"
    (let [records (:rows (reduce (fn [{:keys [rows] :as acc} row]
                                   (if (= "message" (:type row))
                                     (update acc :rows conj row)   ; 先把消息攒着
                                     (update acc :rows conj row)))
                                 {:rows []}
                                 (let [r (a-normal-record)
                                       msg (nth r 2)
                                       rest (vec (concat (subvec r 0 2) (subvec r 3)))]
                                   (conj rest msg))))]                  ; 消息挪到最后
      (is (false? (:normalized? (normalized/normalized? records)))))))

(deftest a-tool-call-with-no-answer-row-is-not-normalized
  ;; 票 05 在 resume 之后找到的形状：答复只剩一行帧（TOOL_CALL_RESULT），没有 message 行。
  (let [records (vec (remove #(and (= "message" (:type %))
                                   (= "c1" (get-in % [:payload :tool_call_id])))
                             (a-normal-record)))]
    (is (false? (:normalized? (normalized/normalized? records))))
    (is (re-find #"工具调用没有 message 行答复" (first (:reasons (normalized/normalized? records)))))))
