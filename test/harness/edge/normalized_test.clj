(ns harness.edge.normalized-test
  "「已重整化」的判据，各对着**真记录**的形状（`.scratch/record-normalization` 票 01）。

  下面的夹具不是编出来的：行形状与行序照 `~/.clj-harness/projects/<project>/<uuid>.jsonl` 里
  真记录的那几行（走查读数在 `.scratch/record-normalization/evidence/real-records.md`）：
  **帧先落，它描述的那条 message 行紧跟其后**——工具答复的 `message` 行在 `TOOL_CALL_END` 之后，
  模型那条 `message` 行在 `TEXT_MESSAGE_END` 之后（答复的正文要先算出来才写得下）。"
  (:require [clojure.test :refer [deftest is testing]]
            [harness.edge.normalized :as normalized]))

(defn- frame [type payload] {:type "event" :payload (assoc payload :type type) :ts 1 :runId "r1"})
(defn- row   [source m]     {:type "message" :payload m :source source :ts 1 :runId "r1"})

(defn- a-normal-record
  "一份**已重整化**的记录，行序照真记录：一组帧，紧跟它描述的那条 message 行。
  一次没有正文的模型调用（TEXT_MESSAGE_START/END 空对）+ 一次工具调用与它的答复。"
  []
  [(frame "RUN_STARTED" {})
   (frame "TEXT_MESSAGE_START" {:messageId "r1-m1" :role "assistant"})
   (frame "TEXT_MESSAGE_END"   {:messageId "r1-m1"})
   (frame "TOOL_CALL_START" {:toolCallId "call_1"})
   (frame "TOOL_CALL_ARGS"  {:toolCallId "call_1" :delta "{}"})
   (frame "TOOL_CALL_END"   {:toolCallId "call_1"})
   (row "model" {:role "assistant" :content "" :tool_calls [{:id "call_1"}]})
   (frame "TOOL_CALL_RESULT" {:messageId "r1-t2" :toolCallId "call_1" :content "done"})
   (row "tool" {:role "tool" :tool_call_id "call_1" :content "done"})
   (frame "RUN_FINISHED" {})])

(defn- remove-frame [records frame-type]
  (vec (remove #(= frame-type (get-in % [:payload :type])) records)))

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

(deftest a-call-with-no-answer-row-is-not-normalized
  ;; 票 05 在 resume 之后找到的形状：答复只剩一行帧（TOOL_CALL_RESULT），没有 message 行。
  (let [records (vec (remove #(and (= "message" (:type %))
                                   (= "call_1" (get-in % [:payload :tool_call_id])))
                             (a-normal-record)))]
    (is (false? (:normalized? (normalized/normalized? records))))
    (is (re-find #"工具调用没有 message 行答复" (first (:reasons (normalized/normalized? records)))))))

(deftest an-answer-with-no-call-is-not-normalized
  (testing "答复在，它认领的那次调用（START/END 一整套）不在"
    (let [records (-> (a-normal-record)
                      (remove-frame "TOOL_CALL_START")
                      (remove-frame "TOOL_CALL_END"))]
      (is (false? (:normalized? (normalized/normalized? records))))
      (is (re-find #"没有它的 TOOL_CALL_START" (first (:reasons (normalized/normalized? records)))))))

  (testing "答复在，那一次调用的收尾（TOOL_CALL_END）不在"
    (let [records (remove-frame (a-normal-record) "TOOL_CALL_END")]
      (is (false? (:normalized? (normalized/normalized? records))))
      (is (re-find #"没有它的 END" (first (:reasons (normalized/normalized? records))))))))

(deftest a-torn-envelope-is-not-normalized
  (testing "TEXT_MESSAGE_START 没有它的 END：一次没说完的话"
    (let [records (remove-frame (a-normal-record) "TEXT_MESSAGE_END")]
      (is (false? (:normalized? (normalized/normalized? records))))
      (is (re-find #"没有它的 END" (first (:reasons (normalized/normalized? records)))))))

  (testing "END 没有它的 START"
    (let [records (remove-frame (a-normal-record) "TEXT_MESSAGE_START")]
      (is (false? (:normalized? (normalized/normalized? records))))
      (is (re-find #"没有它的 START" (first (:reasons (normalized/normalized? records))))))))

(deftest the-fold-and-the-plain-read-are-one-judgment
  (let [records (a-normal-record)]
    (testing "fold 的最终值交给 finish，答案与 normalized? 逐字相同"
      (is (= (normalized/normalized? records)
             (normalized/finish (reduce (fn [acc [i row]] ((:step normalized/fold) acc nil [i row]))
                                        ((:init normalized/fold))
                                        (map-indexed vector records))))))
    (testing "它读的是任何 seq，不要求调用者先把整份记录攥成一个向量"
      (is (= {:normalized? true :reasons []}
             (normalized/normalized? (map identity (a-normal-record))))))))

(defn- a-parked-record
  "一份**已重整化**的记录，它的一次调用停在人那儿：没有答复行，run 的终局帧点了它的名
  （`.scratch/session-*` 那张批准卡）。这是**停车**而不是缺行——记录说得明明白白：缺的不是行，
  是一个人的决定，答复由 resume 那一次补。"
  [parked?]
  [(frame "RUN_STARTED" {})
   (frame "TOOL_CALL_START" {:toolCallId "call_1"})
   (frame "TOOL_CALL_ARGS"  {:toolCallId "call_1" :delta "{}"})
   (frame "TOOL_CALL_END"   {:toolCallId "call_1"})
   (row "model" {:role "assistant" :content "" :tool_calls [{:id "call_1"}]})
   (if parked?
     (frame "RUN_FINISHED" {:outcome {:type "interrupt"
                                    :interrupts [{:id "i1" :reason "tool-approval"
                                                  :toolCallId "call_1"}]}})
     (frame "RUN_FINISHED" {}))])

(deftest a-call-waiting-for-a-person-is-not-a-missing-row
  (testing "停车的调用：终局帧的 outcome.interrupts 点了它的名 ⇒ 不算缺行"
    (is (= {:normalized? true :reasons []}
           (normalized/normalized? (a-parked-record true)))))
  (testing "而同一个 run 若只是结束了、没有人被问 ⇒ 那是真的没有答复"
    (let [records (a-parked-record false)]
      (is (false? (:normalized? (normalized/normalized? records))))
      (is (re-find #"工具调用没有 message 行答复" (first (:reasons (normalized/normalized? records))))))))
