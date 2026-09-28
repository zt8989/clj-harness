(ns harness.edge.normalized
  "已重整化（normalized）——这份记录能不能**继续写下去**（`.scratch/record-normalization`）。

  未重整化的会话是**只读**的：能看，不能续；要续必须先经 fork 重整化。"
  (:require [harness.edge.replay :as replay]))

(def ^:private start-frames
  "命名了『谁』的那些 START 帧：它们给出的 id 与 `message` 行信封上的 id 是同一个命名空间
  （`.scratch/jsonl-two-kinds` 票 02）。"
  #{"TEXT_MESSAGE_START" "TOOL_CALL_START"})

(defn- frame-type
  "一行**帧**的类型。注意它住在 payload 里：记录的一行是
  `{:type \"event\" :payload {:type \"TEXT_MESSAGE_START\" ..}}`——`replay/kind` 给的是行的种类
  （`event`/`message`），不是帧的类型。"
  [row]
  (get-in row [:payload :type]))

(defn- frame-id [row]
  (let [p (replay/payload row)]
    (or (:messageId p) (:toolCallId p))))

(defn normalized?
  "RECORDS（一行一个 map）是否已重整化，答 `{:normalized? bool :reasons [句…]}`——理由是给人看的句子，
  前后端都用它。三条判据，每一条都对着这台机器上真存在的记录：

    (1) 每一行都是当前格式：有 `type` 与 `payload`（不是旧契约裸露的 `kind`）；
    (2) 每一条 `message` 都有人宣告：它的**身份**（信封 `:id`，或 payload 里的 `tool_call_id`）
        出现在本记录某个 START 帧里（`messageId`/`toolCallId`）；
    (3) 每一次工具调用都有答复：每个 `TOOL_CALL_START` 的 toolCallId，都有一行 `message` 以同一个
        身份答复它。**票 05 在 resume 之后丢掉的就是它**（答复只剩帧、没有行）。"
  [records]
  (let [rows        (vec records)
        old         (remove #(and (contains? % :type) (contains? % :payload)) rows)
        announced   (into #{} (comp (filter #(contains? start-frames (frame-type %))) (keep frame-id)) rows)
        calls       (into #{} (comp (filter #(= "TOOL_CALL_START" (frame-type %))) (keep frame-id)) rows)
        messages    (filter #(= "message" (replay/kind %)) rows)
        identity    (fn [row] (or (:id row) (get-in row [:payload :tool_call_id])))
        unannounced (remove #(contains? announced (identity %)) messages)
        unanswered  (remove (into #{} (keep identity) messages) calls)
        reasons     (cond-> []
                      (seq old)         (conj (str (count old) " 行还是旧格式（没有 type/payload 信封）"))
                      (seq unannounced) (conj (str (count unannounced) " 条消息没有 START 帧宣告（它不在本记录的身份里）"))
                      (seq unanswered)  (conj (str (count unanswered) " 次工具调用没有 message 行答复")))]
    {:normalized? (empty? reasons) :reasons (vec reasons)}))
