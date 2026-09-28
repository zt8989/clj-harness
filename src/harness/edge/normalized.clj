(ns harness.edge.normalized
  "已重整化（normalized）——这份记录能不能**继续写下去**（`.scratch/record-normalization`）。

  未重整化的会话是**只读**的：能看，不能续；要续必须先经 fork 重整化。

  这个命名空间**刻意不依赖 `harness.edge.replay`**：记录读侧要用它，反过来就成了环。
  它只需要两件最小的事——一行的种类（`type`）与它的信封（`payload`）。")

(def ^:private start-frames #{"TEXT_MESSAGE_START" "TOOL_CALL_START"})
(def ^:private end-frames   #{"TEXT_MESSAGE_END"   "TOOL_CALL_END"})

(defn- row-kind [row] (:type row))
(defn- row-payload [row] (:payload row))

(defn- frame-type
  "一行**帧**的类型。它住在 payload 里：记录的一行是
  `{:type \"event\" :payload {:type \"TEXT_MESSAGE_START\" ..}}`——行的种类是 `event`，
  帧的类型是 payload 里的那个。"
  [row]
  (get-in row [:payload :type]))

(defn- frame-id [row]
  (let [p (row-payload row)]
    (or (:messageId p) (:toolCallId p))))

(defn- message-id
  "一条 `message` 行的身份：信封上的 `:id`，或 payload 里的 `tool_call_id`（两者与帧的
  `messageId`/`toolCallId` 是同一个命名空间，`.scratch/jsonl-two-kinds` 票 02）。"
  [row]
  (or (:id row) (get-in row [:payload :tool_call_id])))

(defn- spans
  "身份 -> {:start <行号> :end <行号>}：宣告某样东西的那些帧坐在哪儿。"
  [indexed]
  (reduce (fn [acc [i row]]
            (let [t (frame-type row) id (frame-id row)]
              (cond
                (nil? id)                  acc
                (contains? start-frames t) (assoc-in acc [id :start] i)
                (contains? end-frames t)   (assoc-in acc [id :end] i)
                :else                      acc)))
          {} indexed))

(defn normalized?
  "RECORDS（一行一个 map）是否已重整化，答 `{:normalized? bool :reasons [句…]}`——理由是给人看的句子，
  前后端都用它。三条判据，每一条都对着这台机器上真存在的记录：

    (1) 每一行都是当前格式：有 `type` 与 `payload`（不是旧契约裸露的 `kind`）；
    (2) 每一条 `message` 都**夹在**它的 `START` 与 `END` 两行**之间**（同一条身份，同一份记录里，
        位置严格在内）——不是「有人提到过它」就算，而是它真的在信封里面；
    (3) 每一次工具调用都有答复：每个 `TOOL_CALL_START` 的 toolCallId，都有一行 `message` 以同一个
        身份答复它。**票 05 在 resume 之后丢掉的就是它**（答复只剩帧、没有行）。"
  [records]
  (let [rows        (vec records)
        indexed     (map-indexed (fn [i row] [i row]) rows)
        span        (spans indexed)
        old         (remove #(and (contains? % :type) (contains? % :payload)) rows)
        messages    (filter #(= "message" (row-kind %)) rows)
        calls       (into #{} (comp (filter #(= "TOOL_CALL_START" (frame-type %))) (keep frame-id)) rows)
        in-span?    (fn [i row]
                      (let [{:keys [start end]} (get span (message-id row))]
                        (and (some? start) (some? end) (< start i) (< i end))))
        loose       (keep-indexed (fn [i row]
                                    (when (and (= "message" (row-kind row))
                                               (not (in-span? i row)))
                                      row))
                                  rows)
        unanswered  (remove (into #{} (keep message-id) messages) calls)
        reasons     (cond-> []
                      (seq old)        (conj (str (count old) " 行还是旧格式（没有 type/payload 信封）"))
                      (seq loose)      (conj (str (count loose) " 条消息不在它的 START/END 之间（裸露或没收尾）"))
                      (seq unanswered) (conj (str (count unanswered) " 次工具调用没有 message 行答复")))]
    {:normalized? (empty? reasons) :reasons (vec reasons)}))
